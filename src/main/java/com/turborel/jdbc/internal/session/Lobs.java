package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Completed;
import com.turborel.jdbc.internal.codec.Failures;
import com.turborel.jdbc.internal.codec.Lob;
import com.turborel.jdbc.internal.codec.LobKind;
import com.turborel.jdbc.internal.codec.LobRequest;
import com.turborel.jdbc.internal.codec.Octets;
import com.turborel.jdbc.internal.codec.Reply;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A connection's large objects ([CLIENT Large objects]): one a result sends by its locator read by
 * ranges, or as a stream, a chunk a request; a new one written a piece at a time and sent as a
 * parameter by its locator; a locator freed or held. One a result sends whole is read from the
 * value itself. A locator is valid until the transaction it was given in ends, a held one until a
 * rollback. As Turborel's {@code client/src/lobs.rs}.
 */
public final class Lobs {
    /** The longest value a client sends whole for a large object parameter, as the server sends one. */
    public static final int INLINE_OCTETS = 64 * 1024;

    /** The most octets a read or an append moves at once, Turborel's {@code CHUNK_OCTETS}, 256 KiB. */
    public static final int CHUNK_OCTETS = 262_144;

    /** The bits that mark a UTF-8 octet that continues a character. */
    private static final int CONTINUATION_MASK = 0xC0;

    private static final int CONTINUATION = 0x80;

    /** The longest UTF-8 character, in octets. */
    private static final int MOST_UTF8 = 4;

    /** The fewest octets of a character of more than one. */
    private static final int LEAST_MULTI_OCTET = 2;

    /** No index found. */
    private static final int NONE = -1;

    /** What a stream gives at its end. */
    private static final int END = -1;

    /** The SQLSTATE of octets that are not UTF-8: character not in repertoire. */
    private static final String NOT_UTF8 = "22021";

    private final Connection connection;

    Lobs(Connection connection) {
        this.connection = connection;
    }

    /**
     * At most a chunk of {@code lob} from octet {@code from}, from 0, at most {@code count} of them;
     * of a character large object, its characters if {@code characters}, whole ones. Fewer than
     * asked only at its end, or at a chunk's.
     *
     * @param lob the large object
     * @param from where to begin, in octets or characters
     * @param count how many, in octets or characters
     * @param characters whether in characters, of a character large object
     * @return the octets read
     * @throws TurborelException SQLSTATE 0F001 if its locator is no longer valid; a connection
     *     failure
     */
    public byte[] read(Lob lob, long from, long count, boolean characters) throws TurborelException {
        if (lob instanceof Lob.Whole whole) {
            return heldRange(whole, from, count, characters);
        }
        Reply reply = request(new LobRequest.Read(locatorOf(lob), from, count, characters));
        if (reply instanceof Reply.LobData data) {
            return data.data().toByteArray();
        }
        throw connection.lose("[CLIENT Large objects] the server sent a locator for a Read");
    }

    /**
     * {@code lob}'s octets as a stream, read a chunk at a time.
     *
     * @param lob the large object
     * @return its reader
     */
    public InputStream reader(Lob lob) {
        return new Reader(lob);
    }

    /**
     * A new large object of {@code kind}, written a piece at a time.
     *
     * @param kind its kind
     * @return its writer
     * @throws TurborelException a connection failure; the server's
     */
    public Writer create(LobKind kind) throws TurborelException {
        Reply reply = request(new LobRequest.Create(kind));
        if (reply instanceof Reply.LobLocator locator) {
            return new Writer(kind, locator.locator());
        }
        throw connection.lose("[CLIENT Large objects] the server sent data for a Create");
    }

    /**
     * {@code lob}'s locator no longer valid, nor the value it named.
     *
     * @param lob the large object
     * @return the completion
     * @throws TurborelException SQLSTATE 0F001 if it is not valid; SQLSTATE 22000 if it was sent
     *     whole; a connection failure
     */
    public Completed free(Lob lob) throws TurborelException {
        return completed(new LobRequest.Free(locatorOf(lob)));
    }

    /**
     * {@code lob}'s locator valid past its transaction's commit, until freed or a transaction is
     * rolled back.
     *
     * @param lob the large object
     * @return the completion
     * @throws TurborelException as {@link #free}
     */
    public Completed hold(Lob lob) throws TurborelException {
        return completed(new LobRequest.Hold(locatorOf(lob)));
    }

    private static long locatorOf(Lob lob) throws TurborelException {
        if (lob instanceof Lob.Locator locator) {
            return locator.locator();
        }
        throw misused("[CLIENT Large objects] the large object was sent whole: it has no locator");
    }

    /** A failure of the program's own use of a value, as Turborel's {@code misused}: SQLSTATE 22000. */
    private static TurborelException misused(String message) {
        return new TurborelException(Failures.DATA_SQLCODE, "22000", message);
    }

    private Reply request(LobRequest request) throws TurborelException {
        connection.send(request);
        Reply reply = connection.receive();
        if (reply instanceof Reply.LobData || reply instanceof Reply.LobLocator) {
            return reply;
        }
        throw connection.statements().unexpected(reply, "the server sent no Lob reply");
    }

    private Completed completed(LobRequest request) throws TurborelException {
        connection.send(request);
        return connection.statements().completion();
    }

    /** The range of a large object held whole, in characters if asked and it is a character one. */
    static byte[] heldRange(Lob.Whole whole, long from, long count, boolean characters) {
        byte[] bytes = whole.octets().toByteArray();
        if (!characters || whole.kind() == LobKind.BINARY) {
            int start = (int) Math.min(from, bytes.length);
            int end = (int) Math.min((long) start + count, bytes.length);
            return Arrays.copyOfRange(bytes, start, end);
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        int[] picked = text.codePoints().skip(from).limit(count).toArray();
        return new String(picked, 0, picked.length).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The longest prefix of {@code bytes}, at most {@code most} octets, of whole UTF-8 characters,
     * as Turborel's {@code char_cut}: a character the end of {@code bytes} splits is left out too.
     */
    static int charCut(byte[] bytes, int length, int most) {
        int cut = Math.min(most, length);
        if (cut < length) {
            while (cut > 0 && (bytes[cut] & CONTINUATION_MASK) == CONTINUATION) {
                cut--;
            }
            return cut;
        }
        int lead = NONE;
        for (int back = 1; back <= MOST_UTF8 && cut - back >= 0; back++) {
            if ((bytes[cut - back] & CONTINUATION_MASK) != CONTINUATION) {
                lead = cut - back;
                break;
            }
        }
        if (lead == NONE) {
            return cut;
        }
        int ones = Integer.numberOfLeadingZeros(~(bytes[lead] << (Integer.SIZE - Byte.SIZE)));
        int width = ones >= LEAST_MULTI_OCTET && ones <= MOST_UTF8 ? ones : 1;
        return lead + width > cut ? lead : cut;
    }

    /** A large object's octets as a stream, a chunk read at a time. */
    private final class Reader extends InputStream {
        private final Lob lob;
        private long at;
        private byte[] chunk = new byte[0];
        private int taken;

        Reader(Lob lob) {
            this.lob = lob;
        }

        private long octets() {
            return lob instanceof Lob.Locator locator
                    ? locator.octets()
                    : ((Lob.Whole) lob).octets().length();
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? END : Byte.toUnsignedInt(one[0]);
        }

        @Override
        public int read(byte[] out, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (taken >= chunk.length) {
                if (at >= octets()) {
                    return END;
                }
                try {
                    chunk = Lobs.this.read(lob, at, CHUNK_OCTETS, false);
                } catch (TurborelException failure) {
                    throw new IOException(failure);
                }
                if (chunk.length == 0) {
                    throw new IOException("the server sent less of the large object than its length");
                }
                at += chunk.length;
                taken = 0;
            }
            int n = Math.min(chunk.length - taken, length);
            System.arraycopy(chunk, taken, out, offset, n);
            taken += n;
            return n;
        }
    }

    /**
     * A new large object written a piece at a time, sent in Appends of at most a chunk, a
     * character one cut where a character ends; {@link #finish} gives the value to send as a
     * parameter.
     */
    public final class Writer extends OutputStream {
        private final LobKind kind;
        private final long locator;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private long octets;
        private long characters;
        private byte[] carry = new byte[0];

        Writer(LobKind kind, long locator) {
            this.kind = kind;
            this.locator = locator;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            pending.write(bytes, offset, length);
            octets += length;
            if (kind == LobKind.CHARACTER) {
                for (int i = offset; i < offset + length; i++) {
                    if ((bytes[i] & CONTINUATION_MASK) != CONTINUATION) {
                        characters++;
                    }
                }
            }
            try {
                while (pending.size() >= CHUNK_OCTETS) {
                    appendPiece();
                }
            } catch (TurborelException failure) {
                throw new IOException(failure);
            }
        }

        /** Sends the first chunk pending, a character string cut where a character ends. */
        private void appendPiece() throws TurborelException {
            byte[] all = pending.toByteArray();
            int cut = kind == LobKind.CHARACTER ? charCut(all, all.length, CHUNK_OCTETS) : CHUNK_OCTETS;
            cut = Math.min(Math.max(cut, 1), all.length);
            pending.reset();
            pending.write(all, cut, all.length - cut);
            append(Arrays.copyOf(all, cut));
        }

        private void append(byte[] bytes) throws TurborelException {
            if (kind == LobKind.CHARACTER) {
                checkUtf8(bytes, false);
            }
            completed(new LobRequest.Append(locator, Octets.of(bytes)));
        }

        /**
         * Checks a character large object's octets are UTF-8 as they are sent, a character an
         * append splits carried to the next, and none left split at the end: the server at
         * b48f85b2 does not check them as the large object is finished, so the client refuses
         * them first (SQLSTATE 22021), as Turborel's client says the server would.
         */
        private void checkUtf8(byte[] bytes, boolean last) throws TurborelException {
            byte[] all = new byte[carry.length + bytes.length];
            System.arraycopy(carry, 0, all, 0, carry.length);
            System.arraycopy(bytes, 0, all, carry.length, bytes.length);
            int cut = last ? all.length : charCut(all, all.length, all.length);
            try {
                StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(all, 0, cut));
            } catch (java.nio.charset.CharacterCodingException e) {
                throw new TurborelException(
                        Failures.DATA_SQLCODE,
                        NOT_UTF8,
                        "[CLIENT Large objects] a character large object's octets are not UTF-8");
            }
            carry = Arrays.copyOfRange(all, cut, all.length);
        }

        @Override
        public void flush() throws IOException {
            try {
                flushPending();
            } catch (TurborelException failure) {
                throw new IOException(failure);
            }
        }

        private void flushPending() throws TurborelException {
            if (pending.size() > 0) {
                byte[] bytes = pending.toByteArray();
                pending.reset();
                append(bytes);
            }
        }

        /**
         * The large object written, to send as a parameter by its locator: valid until its
         * transaction ends, and used once.
         *
         * @return the large object
         * @throws TurborelException a connection failure; the server's: SQLSTATE 22021 if a
         *     character one's octets are not UTF-8, 22001 if it is longer than the longest
         */
        public Lob.Locator finish() throws TurborelException {
            flushPending();
            if (kind == LobKind.CHARACTER) {
                checkUtf8(new byte[0], true);
            }
            return new Lob.Locator(kind, locator, octets, characters);
        }
    }
}
