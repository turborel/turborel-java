package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Reads the server's replies ([PROTOCOL Replies]): a kind the client does not know, a body cut
 * short or bytes left over after the last field fail the connection, SQLCODE -919 and SQLSTATE
 * 08006 ([PROTOCOL Frames]).
 */
public final class ReplyCodec {
    /** The Lob reply's {@code what} of data. */
    private static final int LOB_DATA = 0;

    /** The Lob reply's {@code what} of a locator. */
    private static final int LOB_LOCATOR = 1;

    private ReplyCodec() {}

    /**
     * The reply of a frame's kind and body.
     *
     * @param kind the frame's kind, as the server sent it
     * @param body its body
     * @return the reply
     * @throws TurborelException a connection failure if no reply is of the kind, or the body is
     *     malformed
     */
    public static Reply decode(byte kind, byte[] body) throws TurborelException {
        Reader r = new Reader(body, "reply");
        Reply reply = read(kind, r);
        r.finish();
        return reply;
    }

    /**
     * Whether a reply of {@code kind} and {@code body} is its request's last, by the order a
     * statement's replies keep: a Description, Rows, a Notice or a Status has more after it, a
     * Complete has if its tail says more (tag 5), and any other reply is the last. So a request
     * whose reply held a value the client cannot take is read past to its end ([PROTOCOL Values
     * and fields]).
     *
     * @param kind the frame's kind
     * @param body its body, which need not decode
     * @return whether it ends its request
     */
    public static boolean endsRequest(byte kind, byte[] body) {
        return switch (kind) {
            case Reply.DESCRIPTION, Reply.ROWS, Reply.NOTICE, Reply.STATUS -> false;
            case Reply.COMPLETE -> {
                Completed head = completedHead(kind, body);
                yield head == null || !head.fields().more();
            }
            default -> true;
        };
    }

    /**
     * The head of a Complete whose whole may not decode: its SQLCODE, SQLSTATE and whether a
     * transaction is active, and of its tail the commit's position and whether more replies
     * follow, which come before a condition, the one field of it the client may fail to take.
     * So the session's state is noted as the whole would note it ([PROTOCOL Values and fields]).
     *
     * @param kind the frame's kind
     * @param body its body
     * @return the head, or none if the frame is no Complete or its head does not read
     */
    public static @Nullable Completed completedHead(byte kind, byte[] body) {
        if (kind != Reply.COMPLETE) {
            return null;
        }
        Reader r = new Reader(body, "reply");
        try {
            int sqlcode = r.bits32();
            String sqlstate = r.string();
            boolean active = r.flag();
            Long position = null;
            boolean more = false;
            Tail tags = new Tail(Tail.COMPLETE);
            for (int tag = tags.next(r); tag == Tail.POSITION || tag == Tail.MORE; tag = tags.next(r)) {
                if (tag == Tail.POSITION) {
                    position = r.u64();
                } else {
                    more = true;
                }
            }
            return new Completed(
                    sqlcode, sqlstate, active, new Completed.Fields(position, more, null, List.of(), List.of(), null));
        } catch (TurborelException cut) {
            return null;
        }
    }

    private static Reply read(byte kind, Reader r) throws TurborelException {
        return switch (kind) {
            case Reply.CHALLENGE -> new Reply.Challenge(r.octets());
            case Reply.READY -> new Reply.Ready(r.string(), CancelKey.read(r), r.strings(), r.string());
            case Reply.PREPARED -> prepared(r);
            case Reply.DESCRIPTION -> new Reply.Description(Column.readAll(r));
            case Reply.ROWS -> new Reply.Rows(r.list(ValueCodec::readRow));
            case Reply.SUSPENDED -> new Reply.Suspended();
            case Reply.COMPLETE -> new Reply.Complete(Completed.read(r));
            case Reply.ERROR -> new Reply.Error(Failure.read(r));
            case Reply.LOB -> lob(r);
            case Reply.NOTICE -> notice(r);
            case Reply.STATUS -> new Reply.Status(r.string(), r.string());
            case Reply.PONG -> new Reply.Pong();
            default -> throw Broken.connection(Cite.FRAMES + " no reply is of kind " + Byte.toUnsignedInt(kind));
        };
    }

    /**
     * A Prepared: its number, input parameters and columns, then its tail: the columns' declared
     * types, tag 19, and of a CALL its procedure and each {@code ?}'s mode, tag 20.
     */
    private static Reply prepared(Reader r) throws TurborelException {
        long statement = r.u32();
        List<Parameter> parameters = r.list(Parameter::read);
        List<Column> columns = r.list(Column::read);
        Called called = null;
        Tail tail = new Tail(Tail.PREPARED);
        for (int tag = tail.next(r); tag != Tail.END; tag = tail.next(r)) {
            if (tag == Tail.DECLARED) {
                columns = Column.declared(r, columns);
            } else {
                called = Called.read(r);
            }
        }
        return new Reply.Prepared(statement, parameters, columns, called);
    }

    private static Reply lob(Reader r) throws TurborelException {
        int what = r.u8();
        return switch (what) {
            case LOB_DATA -> new Reply.LobData(r.octets());
            case LOB_LOCATOR -> new Reply.LobLocator(r.u32());
            default -> throw r.malformed(Cite.REPLIES, "no Lob reply is of kind " + what);
        };
    }

    private static Reply notice(Reader r) throws TurborelException {
        String sqlstate = r.string();
        String message = r.string();
        Diagnostics diagnostics = Diagnostics.read(r);
        return new Reply.Notice(sqlstate, message, diagnostics == null ? Diagnostics.NONE : diagnostics);
    }
}
