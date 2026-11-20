package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Broken;
import com.turborel.jdbc.internal.codec.Completed;
import com.turborel.jdbc.internal.codec.FrameHeader;
import com.turborel.jdbc.internal.codec.Frames;
import com.turborel.jdbc.internal.codec.Octets;
import com.turborel.jdbc.internal.codec.Reply;
import com.turborel.jdbc.internal.codec.ReplyCodec;
import java.util.Arrays;

/**
 * How a connection reads the server's replies ([PROTOCOL Values and fields]): each frame whole,
 * then decoded, so a value in it the client cannot take fails its request alone, the rest of that
 * request's replies read past first, and the connection goes on; a malformed frame loses it.
 */
final class Receiver {
    /** The SQLSTATE class of a warning, which a completion's SQLSTATE begins. */
    private static final String WARNING_CLASS = "01";

    private final Connection connection;

    Receiver(Connection connection) {
        this.connection = connection;
    }

    /**
     * The server's next reply, each Notice and Status before it taken. A reply holding a value
     * the client cannot take fails its request with that value's error, after the rest of the
     * request's replies are read past if {@code replies}, each taken as ever; the connection goes
     * on. A malformed frame loses it ([PROTOCOL Values and fields]).
     */
    Reply receive(boolean replies) throws TurborelException {
        while (true) {
            Frames.Frame frame = frame();
            Reply reply = decoded(frame.kind(), frame.body().toByteArray(), replies);
            if (!took(reply)) {
                return noted(reply);
            }
        }
    }

    /** The next frame whole, the connection lost if none comes. */
    private Frames.Frame frame() throws TurborelException {
        try {
            return connection
                    .wire()
                    .frame(connection.maxBody(), connection.told().compress() != null);
        } catch (TurborelException failure) {
            throw connection.lostBy(failure);
        }
    }

    /** Takes a Notice or a Status, and says whether it was one. */
    private boolean took(Reply reply) {
        if (reply instanceof Reply.Notice notice) {
            connection.told().notice(notice);
        } else if (reply instanceof Reply.Status status) {
            connection.told().status(status.name(), status.value());
        } else {
            return false;
        }
        return true;
    }

    /** The reply of {@code kind} and {@code body}; of one that does not decode, its request failed. */
    private Reply decoded(byte kind, byte[] body, boolean replies) throws TurborelException {
        try {
            return ReplyCodec.decode(kind, body);
        } catch (TurborelException failure) {
            skipped(kind, body, failure);
            if (replies) {
                drained(kind, body);
            }
            throw failure;
        }
    }

    /**
     * Takes a frame that did not decode, for {@code failure}: the connection lost if it ends it,
     * as a malformed frame's does; else of a Complete, what its head says noted as its whole would
     * be.
     */
    private void skipped(byte kind, byte[] body, TurborelException failure) throws TurborelException {
        if (Broken.endsConnection(failure)) {
            throw connection.lostBy(failure);
        }
        Completed head = ReplyCodec.completedHead(kind, body);
        if (head != null) {
            noted(new Reply.Complete(head));
        }
    }

    /**
     * Reads past the rest of a request's replies once one, of {@code kind} and {@code body}, held a
     * value the client cannot take: up to the one that ends them, each taken as ever, or skipped
     * as that one was.
     */
    private void drained(byte kind, byte[] body) throws TurborelException {
        byte at = kind;
        byte[] read = body;
        while (!ReplyCodec.endsRequest(at, read)) {
            Frames.Frame next = frame();
            at = next.kind();
            read = next.body().toByteArray();
            try {
                Reply reply = ReplyCodec.decode(at, read);
                if (!took(reply)) {
                    noted(reply);
                }
            } catch (TurborelException failure) {
                skipped(at, read, failure);
            }
        }
    }

    /**
     * What the server sent as it closed, each frame inflated if it came compressed: each Notice or
     * Status read past, to an Error, which is the commit's failure; nothing else is an answer to
     * Terminate.
     */
    void answered(byte[] rest) throws TurborelException {
        int at = 0;
        while (rest.length - at >= FrameHeader.BYTES) {
            FrameHeader header =
                    FrameHeader.decode(Arrays.copyOfRange(rest, at, at + FrameHeader.BYTES), connection.maxBody());
            int from = at + FrameHeader.BYTES;
            int end = (int) Math.min(rest.length, (long) from + header.length());
            Frames.Frame frame = Frames.unwrap(
                    new Frames.Frame(header.kind(), Octets.of(Arrays.copyOfRange(rest, from, end))),
                    connection.told().compress() != null,
                    connection.maxBody());
            Reply reply = ReplyCodec.decode(frame.kind(), frame.body().toByteArray());
            if (reply instanceof Reply.Error error) {
                throw error.failure().toException();
            }
            if (!took(reply) && !(reply instanceof Reply.Pong)) {
                throw Broken.connection("[PROTOCOL Terminate 'X'] the server answered Terminate");
            }
            at = end;
        }
    }

    /** The reply, its statement's end noted: whether a transaction goes on, and what it completed with. */
    Reply noted(Reply reply) {
        if (reply instanceof Reply.Complete complete) {
            Completed c = complete.completed();
            connection.ended(c.active(), c.sqlstate().startsWith(WARNING_CLASS) ? c.sqlstate() : null);
            connection.told().completed(c.fields());
        } else if (reply instanceof Reply.Error error) {
            connection.ended(error.failure().active(), null);
        } else if (reply instanceof Reply.Ready) {
            // Ready ends a login or a ChangeUser, which renews the session as Reset does.
            connection.ended(false, null);
        } else if (opensTransaction(reply)) {
            connection.opened(reply instanceof Reply.Description);
        }
        return reply;
    }

    /** Whether a reply means a transaction is active: a query's rows are read in one, a locator lives in one. */
    private static boolean opensTransaction(Reply reply) {
        boolean rows =
                reply instanceof Reply.Description || reply instanceof Reply.Rows || reply instanceof Reply.Suspended;
        return rows || reply instanceof Reply.LobData || reply instanceof Reply.LobLocator;
    }
}
