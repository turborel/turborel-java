package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.auth.Trust;
import com.turborel.jdbc.internal.codec.Frames;
import com.turborel.jdbc.internal.codec.Writer;
import com.turborel.jdbc.internal.transport.Duplex;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The session tests' shared fixture (RULES.md R-TEST-7): a stream of the server's bytes given in
 * advance, which records what the client wrote, and the server's frames built for it.
 */
final class Script implements Duplex {
    static final byte[] KEY = new byte[16];

    private final InputStream in;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private boolean closed;

    Script(byte[] server) {
        this.in = new ByteArrayInputStream(server);
    }

    @Override
    public InputStream input() {
        return in;
    }

    @Override
    public OutputStream output() {
        return out;
    }

    @Override
    public void bound(@Nullable Duration within) {
        // Nothing waits on bytes given in advance.
    }

    @Override
    public void close() {
        closed = true;
    }

    /** Whether the client closed the stream. */
    boolean closed() {
        return closed;
    }

    /** What the client wrote. */
    byte[] written() {
        return out.toByteArray();
    }

    /** How many bytes the client wrote. */
    int writtenSize() {
        return out.size();
    }

    /** A connection logged in over the frames given, Ready first, granted {@code granted}. */
    static Connection after(List<String> granted, byte[]... replies) throws TurborelException {
        byte[][] all = new byte[replies.length + 1][];
        all[0] = ready(granted);
        System.arraycopy(replies, 0, all, 1, replies.length);
        return Connection.open(new Script(cat(all)), "ann", new Trust(), Offer.none());
    }

    static byte[] frame(char kind, Writer body) throws TurborelException {
        return Frames.frame((byte) kind, body.toByteArray());
    }

    static byte[] ready(List<String> granted) throws TurborelException {
        return frame('R', new Writer().string("ANN").raw(KEY).strings(granted).string("turborel 0.0.0 (alpha)"));
    }

    static byte[] status(String name, String value) throws TurborelException {
        return frame('T', new Writer().string(name).string(value));
    }

    static byte[] error(String sqlstate) throws TurborelException {
        return error(-919, sqlstate);
    }

    static byte[] error(int sqlcode, String sqlstate) throws TurborelException {
        return frame(
                'E',
                new Writer()
                        .bits32(sqlcode)
                        .string(sqlstate)
                        .string("refused")
                        .flag(false)
                        .flag(false)
                        .flag(false));
    }

    /** A Complete of SQLCODE 0, no transaction active after it, its tail empty. */
    static byte[] complete() throws TurborelException {
        return frame('K', new Writer().bits32(0).string("00000").flag(false));
    }

    static byte[] pong() throws TurborelException {
        return frame('G', new Writer());
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** A sink that records the columns it was given and the size of each batch of rows. */
    static final class Recorded implements Sink {
        final List<List<com.turborel.jdbc.internal.codec.Column>> columns = new java.util.ArrayList<>();
        final List<Integer> batches = new java.util.ArrayList<>();

        @Override
        public void columns(List<com.turborel.jdbc.internal.codec.Column> given) {
            columns.add(given);
        }

        @Override
        public void rows(List<List<com.turborel.jdbc.internal.codec.Value>> rows) {
            batches.add(rows.size());
        }

        /** The rows of every batch. */
        int rows() {
            return batches.stream().mapToInt(Integer::intValue).sum();
        }
    }
}
