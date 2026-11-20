package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;

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

    private static Reply read(byte kind, Reader r) throws TurborelException {
        return switch (kind) {
            case Reply.CHALLENGE -> new Reply.Challenge(r.octets());
            case Reply.READY -> new Reply.Ready(r.string(), CancelKey.read(r), r.strings(), r.string());
            case Reply.PREPARED -> new Reply.Prepared(r.u32(), r.list(Parameter::read), r.list(Column::read));
            case Reply.DESCRIPTION -> new Reply.Description(r.list(Column::read));
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
