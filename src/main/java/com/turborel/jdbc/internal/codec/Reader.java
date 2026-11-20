package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads the protocol's fields from a message's body, front to back ([PROTOCOL Values and fields]).
 *
 * <p>Every read is checked against what is left, so a body cut short, a flag neither 0 nor 1 or a
 * string not UTF-8 fails the connection (SQLCODE -919, SQLSTATE 08006), never an unchecked
 * exception (RULES.md R-ERR-1). A count is never trusted to reserve memory: a list grows only by
 * the items read, and a length is checked against the bytes left before they are copied, so a
 * false count fails as the body runs out (R-ERR-4).
 */
public final class Reader {
    /**
     * How deep arrays, rows, multisets and structured values nest in a value or a type, as
     * turborel's {@code MAX_TYPE_NESTING}: deeper fails, so a reply cannot exhaust the stack.
     */
    public static final int MAX_NESTING = 16;

    private final byte[] bytes;
    private final String what;
    private final boolean stored;
    private int at;
    private int depth;

    /**
     * A reader of a body received.
     *
     * @param bytes the body, which the reader does not copy and never changes
     * @param what what the body is, for messages, as "reply"
     */
    public Reader(byte[] bytes, String what) {
        this(bytes, what, false);
    }

    private Reader(byte[] bytes, String what, boolean stored) {
        this.bytes = bytes;
        this.what = what;
        this.stored = stored;
    }

    /**
     * A reader of a value's own bytes within a body, as a Complete's condition: one malformed
     * fails as a value damaged (SQLCODE -916, SQLSTATE TB916), the frame that holds it whole, so
     * its request fails alone ([PROTOCOL Values and fields]).
     *
     * @param bytes the bytes, which the reader does not copy and never changes
     * @param what what they are, for messages
     * @return the reader
     */
    public static Reader stored(byte[] bytes, String what) {
        return new Reader(bytes, what, true);
    }

    /**
     * The failure of a malformed body, at the byte reached.
     *
     * @param cite the section of the protocol it breaks, from {@link Cite}
     * @param why what is wrong
     * @return a connection failure, for the caller to throw; of a reader {@link #stored}, a value
     *     damaged
     */
    public TurborelException malformed(String cite, String why) {
        if (stored) {
            return Failures.corrupt(cite + " a " + what + " is malformed at byte " + at + ": " + why);
        }
        return Broken.connection(cite + " a " + what + " received is malformed at byte " + at + ": " + why);
    }

    /**
     * The next bytes, as they are.
     *
     * @param count how many
     * @return a copy of them
     * @throws TurborelException if fewer are left
     */
    public byte[] take(long count) throws TurborelException {
        if (count < 0 || count > bytes.length - at) {
            throw malformed(Cite.FRAMES, "it ends too soon");
        }
        int from = at;
        at += (int) count;
        return Arrays.copyOfRange(bytes, from, at);
    }

    /**
     * A {@code u8}.
     *
     * @return its value, 0 to 255
     * @throws TurborelException if none is left
     */
    public int u8() throws TurborelException {
        need(1);
        return Byte.toUnsignedInt(bytes[at++]);
    }

    /**
     * A {@code u32}.
     *
     * @return its value, 0 to 2^32 - 1
     * @throws TurborelException if fewer than four bytes are left
     */
    public long u32() throws TurborelException {
        return Integer.toUnsignedLong(bits32());
    }

    /**
     * A {@code u32}'s bits, as a signed field written {@code i32 as u32} is read.
     *
     * @return its bits
     * @throws TurborelException if fewer than four bytes are left
     */
    public int bits32() throws TurborelException {
        need(Integer.BYTES);
        int value = ByteBuffer.wrap(bytes, at, Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt();
        at += Integer.BYTES;
        return value;
    }

    /**
     * A {@code u64}'s bits; compare them as unsigned (RULES.md R-NUM-1).
     *
     * @return its bits
     * @throws TurborelException if fewer than eight bytes are left
     */
    public long u64() throws TurborelException {
        need(Long.BYTES);
        long value = ByteBuffer.wrap(bytes, at, Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getLong();
        at += Long.BYTES;
        return value;
    }

    /**
     * An {@code i128}, two's complement.
     *
     * @return its value
     * @throws TurborelException if fewer than sixteen bytes are left
     */
    public BigInteger i128() throws TurborelException {
        byte[] little = take(Writer.I128_BYTES);
        byte[] big = new byte[little.length];
        for (int i = 0; i < little.length; i++) {
            big[i] = little[little.length - 1 - i];
        }
        return new BigInteger(big);
    }

    /**
     * A flag.
     *
     * @return whether it is set
     * @throws TurborelException if none is left, or it is neither 0 nor 1
     */
    public boolean flag() throws TurborelException {
        int value = u8();
        if (value > 1) {
            throw malformed(Cite.FIELDS, "a flag is neither 0 nor 1");
        }
        return value == 1;
    }

    /**
     * Bytes, their count first.
     *
     * @return a copy of them
     * @throws TurborelException if fewer are left than the count says
     */
    public byte[] bytes() throws TurborelException {
        return take(u32());
    }

    /**
     * Octets, their count first.
     *
     * @return the octets
     * @throws TurborelException if fewer are left than the count says
     */
    public Octets octets() throws TurborelException {
        return Octets.owning(bytes());
    }

    /**
     * A string, its UTF-8's length first.
     *
     * @return the string
     * @throws TurborelException if it is cut short or is not UTF-8
     */
    public String string() throws TurborelException {
        byte[] utf8 = bytes();
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8))
                    .toString();
        } catch (CharacterCodingException e) {
            throw malformed(Cite.FIELDS, "a string is not UTF-8");
        }
    }

    /**
     * A list, its count first, each item read by {@code item}.
     *
     * @param item what reads one item
     * @param <T> the items' type
     * @return the items
     * @throws TurborelException if an item cannot be read
     */
    public <T> List<T> list(Read<T> item) throws TurborelException {
        long count = u32();
        // Not reserved for the count: what is held grows only with items read.
        List<T> items = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            items.add(item.read(this));
        }
        return items;
    }

    /**
     * A list of strings, its count first.
     *
     * @return the strings
     * @throws TurborelException if one cannot be read
     */
    public List<String> strings() throws TurborelException {
        return list(Reader::string);
    }

    /**
     * What {@code read} reads, one level deeper within arrays, rows, multisets and structured
     * values.
     *
     * @param read what reads the nested part
     * @param <T> what it reads
     * @return what it read
     * @throws TurborelException if parts nest deeper than {@link #MAX_NESTING}, or it fails
     */
    public <T> T nested(Read<T> read) throws TurborelException {
        if (depth >= MAX_NESTING) {
            throw malformed(Cite.VALUES, "its arrays and rows nest deeper than " + MAX_NESTING);
        }
        depth++;
        try {
            return read.read(this);
        } finally {
            depth--;
        }
    }

    /**
     * Whether every byte has been read: a message's tail is read while any are left.
     *
     * @return whether none is left
     */
    public boolean atEnd() {
        return at >= bytes.length;
    }

    /**
     * Checks that every byte was read.
     *
     * @throws TurborelException if bytes follow the last field
     */
    public void finish() throws TurborelException {
        if (!atEnd()) {
            throw malformed(Cite.FRAMES, "bytes follow its end");
        }
    }

    private void need(int count) throws TurborelException {
        if (count > bytes.length - at) {
            throw malformed(Cite.FRAMES, "it ends too soon");
        }
    }

    /**
     * What reads a part of a body.
     *
     * @param <T> what it reads
     */
    @FunctionalInterface
    public interface Read<T> {
        /**
         * Reads the part.
         *
         * @param reader where it is read from
         * @return what was read
         * @throws TurborelException if it is malformed
         */
        T read(Reader reader) throws TurborelException;
    }
}
