package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Appends the protocol's fields to a growing body, each integer least significant byte first
 * ([PROTOCOL Values and fields]).
 *
 * <p>A value the program gave that a field cannot hold, such as a number past a {@code u32} or a
 * string that is not Unicode, is refused with a {@link TurborelException} (SQLCODE -910), never
 * cut short or replaced, so what reaches the server is what the program gave.
 */
public final class Writer {
    /** The bytes a body starts with room for. */
    private static final int INITIAL = 64;

    /** The longest array the JVM makes safely, a little under {@code Integer.MAX_VALUE}. */
    private static final int MAX_ARRAY = Integer.MAX_VALUE - 8;

    /** The greatest {@code u8}. */
    private static final int U8_MAX = 0xFF;

    /** The greatest {@code u32}. */
    static final long U32_MAX = 0xFFFF_FFFFL;

    /** The bytes of an {@code i128}. */
    static final int I128_BYTES = 16;

    /** The bits of an {@code i128} below its sign bit. */
    private static final int I128_MAGNITUDE_BITS = 127;

    /** The least {@code i128}, -2^127. */
    static final BigInteger I128_MIN =
            BigInteger.ONE.shiftLeft(I128_MAGNITUDE_BITS).negate();

    /** The greatest {@code i128}, 2^127 - 1. */
    static final BigInteger I128_MAX =
            BigInteger.ONE.shiftLeft(I128_MAGNITUDE_BITS).subtract(BigInteger.ONE);

    /** A byte of all ones, which fills a negative number's higher bytes. */
    private static final byte ALL_ONES = (byte) U8_MAX;

    /** How much a body's room grows when it is full. */
    private static final int GROWTH = 2;

    private byte[] buffer = new byte[INITIAL];
    private int size;

    /** An empty body. */
    public Writer() {
        // The buffer is made above, empty.
    }

    /**
     * Appends a {@code u8}.
     *
     * @param value the byte, 0 to 255
     * @return this writer
     * @throws TurborelException if the value is past a {@code u8}
     */
    public Writer u8(int value) throws TurborelException {
        if (value < 0 || value > U8_MAX) {
            throw Failures.misuse(Cite.FIELDS + " " + value + " is past a u8");
        }
        ensure(1);
        buffer[size++] = (byte) value;
        return this;
    }

    /**
     * Appends a {@code u32}.
     *
     * @param value the number, 0 to 2^32 - 1
     * @return this writer
     * @throws TurborelException if the value is past a {@code u32}
     */
    public Writer u32(long value) throws TurborelException {
        if (value < 0 || value > U32_MAX) {
            throw Failures.misuse(Cite.FIELDS + " " + value + " is past a u32");
        }
        return bits32((int) value);
    }

    /**
     * Appends an {@code int}'s bits as a {@code u32}: a signed field written {@code i32 as u32},
     * or a count, which Java's sizes keep within a {@code u32}.
     *
     * @param bits the bits
     * @return this writer
     * @throws TurborelException if the body would be longer than a Java array holds
     */
    public Writer bits32(int bits) throws TurborelException {
        ensure(Integer.BYTES);
        ByteBuffer.wrap(buffer, size, Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(bits);
        size += Integer.BYTES;
        return this;
    }

    /**
     * Appends a {@code long}'s bits as a {@code u64}, or as an {@code i64 as u64}.
     *
     * @param bits the bits
     * @return this writer
     * @throws TurborelException if the body would be longer than a Java array holds
     */
    public Writer u64(long bits) throws TurborelException {
        ensure(Long.BYTES);
        ByteBuffer.wrap(buffer, size, Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(bits);
        size += Long.BYTES;
        return this;
    }

    /**
     * Appends an {@code i128}, two's complement.
     *
     * @param value the number, -2^127 to 2^127 - 1
     * @return this writer
     * @throws TurborelException if the value is past an {@code i128}
     */
    public Writer i128(BigInteger value) throws TurborelException {
        if (value.compareTo(I128_MIN) < 0 || value.compareTo(I128_MAX) > 0) {
            throw Failures.misuse(Cite.FIELDS + " " + value + " is past an i128");
        }
        byte[] big = value.toByteArray();
        byte fill = value.signum() < 0 ? ALL_ONES : 0;
        ensure(I128_BYTES);
        for (int i = 0; i < I128_BYTES; i++) {
            int from = big.length - 1 - i;
            buffer[size + i] = from >= 0 ? big[from] : fill;
        }
        size += I128_BYTES;
        return this;
    }

    /**
     * Appends a flag, 0 or 1.
     *
     * @param set whether it is set
     * @return this writer
     * @throws TurborelException if the body would be longer than a Java array holds
     */
    public Writer flag(boolean set) throws TurborelException {
        ensure(1);
        buffer[size++] = set ? (byte) 1 : 0;
        return this;
    }

    /**
     * Appends bytes, their count first.
     *
     * @param bytes the bytes
     * @return this writer
     * @throws TurborelException if the body would be longer than a Java array holds
     */
    public Writer bytes(byte[] bytes) throws TurborelException {
        bits32(bytes.length);
        return raw(bytes);
    }

    /**
     * Appends octets, their count first.
     *
     * @param octets the octets
     * @return this writer
     * @throws TurborelException if the body would be longer than a Java array holds
     */
    public Writer bytes(Octets octets) throws TurborelException {
        return bytes(octets.shared());
    }

    /**
     * Appends a string, its UTF-8's length first.
     *
     * @param text the string
     * @return this writer
     * @throws TurborelException if the string is not Unicode, as one with a lone surrogate is not
     */
    public Writer string(String text) throws TurborelException {
        return bytes(utf8(text));
    }

    /**
     * Appends a list of strings, their count first.
     *
     * @param strings the strings
     * @return this writer
     * @throws TurborelException if a string is not Unicode
     */
    public Writer strings(List<String> strings) throws TurborelException {
        bits32(strings.size());
        for (String each : strings) {
            string(each);
        }
        return this;
    }

    /**
     * Appends bytes as they are, with no count.
     *
     * @param bytes the bytes
     * @return this writer
     * @throws TurborelException if the body would be longer than a Java array holds
     */
    public Writer raw(byte[] bytes) throws TurborelException {
        ensure(bytes.length);
        System.arraycopy(bytes, 0, buffer, size, bytes.length);
        size += bytes.length;
        return this;
    }

    /**
     * The bytes written.
     *
     * @return a copy of the body
     */
    public byte[] toByteArray() {
        return Arrays.copyOf(buffer, size);
    }

    /**
     * The UTF-8 of a string, refused if it is not Unicode, never replaced.
     *
     * @param text the string
     * @return its UTF-8
     * @throws TurborelException if it holds a lone surrogate
     */
    static byte[] utf8(String text) throws TurborelException {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            byte[] out = new byte[encoded.remaining()];
            encoded.get(out);
            return out;
        } catch (CharacterCodingException e) {
            throw Failures.misuse(Cite.FIELDS + " a string is not Unicode: it holds a lone surrogate");
        }
    }

    /** Room for {@code more} bytes, refused past what a Java array holds. */
    void ensure(int more) throws TurborelException {
        if (more > MAX_ARRAY - size) {
            throw Failures.beyond(Cite.FRAMES + " a message would be longer than a Java array holds");
        }
        int needed = size + more;
        if (needed > buffer.length) {
            long doubled = (long) buffer.length * GROWTH;
            buffer = Arrays.copyOf(buffer, (int) Math.min(MAX_ARRAY, Math.max(doubled, needed)));
        }
    }
}
