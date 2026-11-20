package com.turborel.jdbc.internal.codec;

import static com.turborel.jdbc.internal.codec.Fixture.assertBeyond;
import static com.turborel.jdbc.internal.codec.Fixture.assertBroken;
import static com.turborel.jdbc.internal.codec.Fixture.assertMisuse;
import static com.turborel.jdbc.internal.codec.Fixture.assertNotInRepertoire;
import static com.turborel.jdbc.internal.codec.Fixture.assertOutOfRange;
import static com.turborel.jdbc.internal.codec.Fixture.cat;
import static com.turborel.jdbc.internal.codec.Fixture.hex;
import static com.turborel.jdbc.internal.codec.Fixture.reader;
import static com.turborel.jdbc.internal.codec.Fixture.str;
import static com.turborel.jdbc.internal.codec.Fixture.u32;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The fields messages are made of, written and read ([PROTOCOL Values and fields]). */
class FieldsTest {
    /**
     * Verifies: [PROTOCOL Values and fields] u8, u32 and u64 are written least significant byte
     * first and read back at their full unsigned range.
     */
    @Test
    void unsignedIntegersAreLittleEndian() throws TurborelException {
        byte[] bytes = new Writer()
                .u8(0xAB)
                .u32(0x0403_0201L)
                .u64(0x0807_0605_0403_0201L)
                .toByteArray();
        assertArrayEquals(hex("AB 01020304 0102030405060708"), bytes);
        Reader r = reader(bytes);
        assertEquals(0xAB, r.u8());
        assertEquals(0x0403_0201L, r.u32());
        assertEquals(0x0807_0605_0403_0201L, r.u64());
        r.finish();
        Reader max = reader(hex("FF FFFFFFFF FFFFFFFFFFFFFFFF"));
        assertEquals(255, max.u8());
        assertEquals(0xFFFF_FFFFL, max.u32());
        assertEquals(-1L, max.u64());
    }

    /**
     * Verifies: [PROTOCOL Values and fields] a field written i32 as u32 is the signed number's
     * bits: -919 reads back as -919, not as an unsigned number.
     */
    @Test
    void signedNumbersTravelAsTheirBits() throws TurborelException {
        byte[] bytes = new Writer().bits32(-919).toByteArray();
        assertArrayEquals(hex("69FCFFFF"), bytes);
        assertEquals(-919, reader(bytes).bits32());
        assertEquals(0xFFFF_FC69L, reader(bytes).u32());
    }

    /**
     * Verifies: [PROTOCOL Values and fields] an i128 is 16 bytes, two's complement, least
     * significant first, at both of its limits; one past them is refused before it is sent.
     */
    @Test
    void i128IsSixteenBytesAtItsLimits() throws TurborelException {
        assertArrayEquals(
                hex("01000000000000000000000000000000"),
                new Writer().i128(BigInteger.ONE).toByteArray());
        assertArrayEquals(
                hex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"),
                new Writer().i128(BigInteger.ONE.negate()).toByteArray());
        assertArrayEquals(
                hex("00000000000000000000000000000080"),
                new Writer().i128(Writer.I128_MIN).toByteArray());
        assertArrayEquals(
                hex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFF7F"),
                new Writer().i128(Writer.I128_MAX).toByteArray());
        for (BigInteger edge : List.of(Writer.I128_MIN, Writer.I128_MAX, BigInteger.ZERO, BigInteger.valueOf(-2))) {
            assertEquals(edge, reader(new Writer().i128(edge).toByteArray()).i128());
        }
        assertOutOfRange(() -> new Writer().i128(Writer.I128_MAX.add(BigInteger.ONE)));
        assertOutOfRange(() -> new Writer().i128(Writer.I128_MIN.subtract(BigInteger.ONE)));
    }

    /**
     * Verifies: [PROTOCOL Values and fields] a number past its field's width is refused, never cut
     * short: a u8 past 255 or below 0, a u32 past 2^32 - 1 or below 0.
     */
    @Test
    void aNumberPastItsFieldIsRefused() {
        assertOutOfRange(() -> new Writer().u8(256));
        assertOutOfRange(() -> new Writer().u8(-1));
        assertOutOfRange(() -> new Writer().u32(0x1_0000_0000L));
        assertOutOfRange(() -> new Writer().u32(-1));
    }

    /**
     * Verifies: [PROTOCOL Values and fields] a flag is a u8, 0 or 1; any other byte fails the
     * connection.
     */
    @Test
    void aFlagIsZeroOrOne() throws TurborelException {
        assertArrayEquals(hex("0100"), new Writer().flag(true).flag(false).toByteArray());
        Reader r = reader(hex("0100"));
        assertTrue(r.flag());
        assertFalse(r.flag());
        assertBroken(() -> reader(hex("02")).flag());
        assertBroken(() -> reader(hex("FF")).flag());
    }

    /**
     * Verifies: [PROTOCOL Values and fields] a string is its length in bytes, u32, then its
     * UTF-8; one not UTF-8 fails the connection, and one that is not Unicode is refused before it
     * is sent, never replaced.
     */
    @Test
    void aStringIsItsLengthThenItsUtf8() throws TurborelException {
        assertArrayEquals(hex("03000000 414E4E"), new Writer().string("ANN").toByteArray());
        assertArrayEquals(hex("02000000 C3A9"), new Writer().string("é").toByteArray());
        assertArrayEquals(hex("04000000 F09F9880"), new Writer().string("😀").toByteArray());
        assertEquals("😀", reader(hex("04000000 F09F9880")).string());
        assertEquals("", reader(hex("00000000")).string());
        assertBroken(() -> reader(hex("02000000 C328")).string());
        assertBroken(() -> reader(hex("03000000 EDA080")).string());
        assertNotInRepertoire(() -> new Writer().string("\uD83D"));
    }

    /**
     * Verifies: [PROTOCOL Values and fields] bytes are their length, u32, then the bytes; a list
     * is its count then each item, and strings a list of strings.
     */
    @Test
    void bytesAndListsAreCounted() throws TurborelException {
        assertArrayEquals(
                hex("02000000 0102"), new Writer().bytes(new byte[] {1, 2}).toByteArray());
        assertArrayEquals(hex("00000000"), new Writer().bytes(Octets.EMPTY).toByteArray());
        byte[] strings = new Writer().strings(List.of("a", "bc")).toByteArray();
        assertArrayEquals(cat(u32(2), str("a"), str("bc")), strings);
        assertEquals(List.of("a", "bc"), reader(strings).strings());
        assertEquals(Octets.of(new byte[] {1, 2}), reader(hex("02000000 0102")).octets());
    }

    /**
     * Verifies: [PROTOCOL Values and fields] a count larger than what follows fails as the body
     * runs out, so it cannot make the reader reserve memory: a list or bytes claiming 2^32 - 1
     * items in a body of none fails at once.
     */
    @Test
    void aFalseCountFailsAsTheBodyRunsOut() {
        assertBroken(() -> reader(hex("FFFFFFFF")).list(Reader::u8));
        assertBroken(() -> reader(hex("FFFFFFFF 00")).bytes());
        assertBroken(() -> reader(hex("FFFFFFFF")).strings());
        assertBroken(() -> reader(hex("05000000 0102")).octets());
    }

    /**
     * Verifies: [PROTOCOL Frames] a body cut short fails the connection, at every field, and bytes
     * left over after the last field fail it too.
     */
    @Test
    void aBodyCutShortOrWithBytesLeftFails() {
        assertBroken(() -> reader(new byte[0]).u8());
        assertBroken(() -> reader(hex("010203")).u32());
        assertBroken(() -> reader(hex("01020304050607")).u64());
        assertBroken(() -> reader(new byte[15]).i128());
        assertBroken(() -> reader(hex("00")).take(-1));
        assertBroken(() -> reader(hex("00")).finish());
    }

    /**
     * Verifies: [PROTOCOL Values and fields] a key is 16 bytes with no length before them; it is
     * read and written whole, compared by its bytes, and never shown ([PROTOCOL Ready 'R']).
     */
    @Test
    void aKeyIsSixteenBytesWithNoLength() throws TurborelException {
        byte[] sixteen = hex("000102030405060708090A0B0C0D0E0F");
        CancelKey key = CancelKey.read(reader(sixteen));
        Writer w = new Writer();
        key.encode(w);
        assertArrayEquals(sixteen, w.toByteArray());
        assertEquals(CancelKey.of(sixteen), key);
        assertNotEquals(CancelKey.of(new byte[16]), key);
        assertNotEquals(key, sixteen);
        assertEquals(CancelKey.of(new byte[16]).hashCode(), key.hashCode());
        assertEquals("CancelKey(..)", key.toString());
        assertMisuse(() -> CancelKey.of(new byte[15]));
        assertBroken(() -> CancelKey.read(reader(new byte[15])));
    }

    /**
     * Verifies: [PROTOCOL Values] parts nest at most 16 deep in a value or a type; deeper fails
     * the connection, so a reply cannot exhaust the stack.
     */
    @Test
    void nestingIsBounded() throws TurborelException {
        Reader r = reader(new byte[0]);
        assertEquals(Reader.MAX_NESTING, depth(r, Reader.MAX_NESTING));
        assertBroken(() -> depth(reader(new byte[0]), Reader.MAX_NESTING + 1));
    }

    /**
     * Verifies: [PROTOCOL Values and fields] bytes received or given are copied as they are made
     * and given back, compared by
     * content, so no holder changes what another holds.
     */
    @Test
    void octetsNeverChange() {
        byte[] source = {1, 2, 3};
        Octets octets = Octets.of(source);
        source[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, octets.toByteArray());
        octets.toByteArray()[1] = 9;
        assertEquals(Octets.of(new byte[] {1, 2, 3}), octets);
        assertEquals(Octets.of(new byte[] {1, 2, 3}).hashCode(), octets.hashCode());
        assertNotEquals(Octets.EMPTY, octets);
        assertNotEquals(octets, source);
        assertEquals(3, octets.length());
        assertEquals("010203", octets.toString());
    }

    /**
     * Verifies: [PROTOCOL Frames] a message the client makes past what a Java array holds is
     * refused as past Turborel's limits, never thrown as Java's own error.
     */
    @Test
    void aBodyPastAJavaArrayIsRefused() {
        assertBeyond(() -> new Writer().raw(new byte[16]).ensure(Integer.MAX_VALUE));
    }

    private static int depth(Reader r, int levels) throws TurborelException {
        return levels == 0 ? 0 : r.nested(inner -> 1 + depth(inner, levels - 1));
    }
}
