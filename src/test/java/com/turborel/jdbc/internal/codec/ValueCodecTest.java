package com.turborel.jdbc.internal.codec;

import static com.turborel.jdbc.internal.codec.Fixture.assertBeyond;
import static com.turborel.jdbc.internal.codec.Fixture.assertBroken;
import static com.turborel.jdbc.internal.codec.Fixture.assertOutOfRange;
import static com.turborel.jdbc.internal.codec.Fixture.cat;
import static com.turborel.jdbc.internal.codec.Fixture.decoded;
import static com.turborel.jdbc.internal.codec.Fixture.encoded;
import static com.turborel.jdbc.internal.codec.Fixture.i128;
import static com.turborel.jdbc.internal.codec.Fixture.str;
import static com.turborel.jdbc.internal.codec.Fixture.u32;
import static com.turborel.jdbc.internal.codec.Fixture.u64;
import static com.turborel.jdbc.internal.codec.Fixture.u8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.turborel.jdbc.TurborelException;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/** The scalar values, written and read ([PROTOCOL Values]). */
class ValueCodecTest {
    /**
     * Verifies: [PROTOCOL Values] tag 0, the null value, has no body; tag 1, a character string,
     * is a string, of either character type alike.
     */
    @Test
    void nullAndCharacterStrings() throws TurborelException {
        assertArrayEquals(u8(0), encoded(Value.NULL));
        assertEquals(Value.NULL, decoded(u8(0)));
        byte[] ann = cat(u8(1), str("ANN"));
        assertArrayEquals(ann, encoded(new Value.Text("ANN")));
        assertEquals(new Value.Text("ANN"), decoded(ann));
    }

    /**
     * Verifies: [PROTOCOL Values] tag 2, an exact number, units i128 then scale u32, reaches a
     * BigDecimal with no loss over the whole i128 range and every u32 scale, as the page's worked
     * example writes the number 1.
     */
    @Test
    void exactNumbersKeepEveryUnitAndScale() throws TurborelException {
        byte[] one = Fixture.hex("02 01000000000000000000000000000000 00000000");
        assertArrayEquals(one, encoded(new Value.Exact(BigInteger.ONE, 0)));
        assertEquals(BigDecimal.ONE, ((Value.Exact) decoded(one)).toBigDecimal());
        for (BigInteger units : new BigInteger[] {Writer.I128_MIN, Writer.I128_MAX, BigInteger.valueOf(-12_345)}) {
            for (long scale : new long[] {0, 2, 38, Integer.MAX_VALUE, 0xFFFF_FFFFL}) {
                Value.Exact exact = new Value.Exact(units, scale);
                assertEquals(exact, decoded(cat(u8(2), i128(units), u32(scale))));
                assertEquals(exact, decoded(encoded(exact)));
            }
        }
        assertEquals(new BigDecimal(Writer.I128_MAX, 2), new Value.Exact(Writer.I128_MAX, 2).toBigDecimal());
        assertBeyond(() -> new Value.Exact(BigInteger.ONE, Integer.MAX_VALUE + 1L).toBigDecimal());
        assertOutOfRange(() -> encoded(new Value.Exact(Writer.I128_MAX.add(BigInteger.ONE), 0)));
        assertOutOfRange(() -> encoded(new Value.Exact(BigInteger.ONE, -1)));
    }

    /**
     * Verifies: [PROTOCOL Values] an exact number made of a BigDecimal is the same number: a
     * negative scale is made 0, the units multiplied to match.
     */
    @Test
    void anExactNumberOfABigDecimal() throws TurborelException {
        assertEquals(new Value.Exact(BigInteger.valueOf(150), 2), Value.Exact.of(new BigDecimal("1.50")));
        assertEquals(new Value.Exact(BigInteger.valueOf(1000), 0), Value.Exact.of(new BigDecimal("1E+3")));
        assertEquals(
                new BigDecimal("1000"), Value.Exact.of(new BigDecimal("1E+3")).toBigDecimal());
    }

    /**
     * Verifies: [PROTOCOL Values] tag 3, an approximate number, is the bits of an IEEE 754
     * double: NaN's payload and -0.0 kept bit for bit.
     */
    @Test
    void approximateNumbersKeepTheirBits() throws TurborelException {
        assertArrayEquals(cat(u8(3), u64(Double.doubleToRawLongBits(1.5))), encoded(new Value.Approximate(1.5)));
        long payload = 0x7FF8_0000_0000_0123L;
        Value nan = decoded(cat(u8(3), u64(payload)));
        assertArrayEquals(cat(u8(3), u64(payload)), encoded(nan));
        assertEquals(new Value.Approximate(-0.0), decoded(cat(u8(3), u64(Double.doubleToRawLongBits(-0.0)))));
        assertNotEquals(new Value.Approximate(0.0), new Value.Approximate(-0.0));
        assertNotEquals(new Value.Approximate(0.0), new Value.Truth(false));
        assertEquals(new Value.Approximate(Double.NaN).hashCode(), new Value.Approximate(Double.NaN).hashCode());
        assertEquals(
                new Value.Approximate(Double.MAX_VALUE), decoded(encoded(new Value.Approximate(Double.MAX_VALUE))));
    }

    /**
     * Verifies: [PROTOCOL Values] tags 8, 9 and 11: a date, a time and a timestamp, each zoned,
     * precision, the displacement's i16 in the low half of a u32, and the instant in microseconds
     * of UTC, i64 as u64; displacements -12:59 and +14:00 at the edges, one past them refused
     * with Turborel's code, SQLSTATE 22009.
     */
    @Test
    void datetimesCarryZoneDisplacementAndInstant() throws TurborelException {
        Value.Datetime east = new Value.Datetime(Value.Moment.TIMESTAMP, true, 6, (short) 840, 1_700_000_000_000_000L);
        assertArrayEquals(cat(u8(11), u8(1), u8(6), u32(840), u64(1_700_000_000_000_000L)), encoded(east));
        Value.Datetime west = new Value.Datetime(Value.Moment.TIME, true, 0, (short) -779, -1);
        assertArrayEquals(cat(u8(9), u8(1), u8(0), u32(0xFCF5), u64(-1)), encoded(west));
        assertEquals(west, decoded(encoded(west)));
        Value.Datetime date = new Value.Datetime(Value.Moment.DATE, false, 0, (short) 0, 0);
        assertEquals(date, decoded(cat(u8(8), u8(0), u8(0), u32(0), u64(0))));
        assertBroken(() -> decoded(cat(u8(11), u8(0), u8(0), u32(0x1_0000), u64(0))));
        assertBroken(() -> decoded(cat(u8(11), u8(2), u8(0), u32(0), u64(0))));
        for (short past : new short[] {841, -780}) {
            TurborelException refused = org.junit.jupiter.api.Assertions.assertThrows(
                    TurborelException.class, () -> encoded(new Value.Datetime(Value.Moment.DATE, false, 0, past, 0)));
            assertEquals("22009", refused.getSQLState());
            assertEquals(-301, refused.sqlcode());
        }
        assertOutOfRange(() -> encoded(new Value.Datetime(Value.Moment.DATE, false, 256, (short) 0, 0)));
    }

    /**
     * Verifies: [PROTOCOL Values] tag 13, an interval: its qualifier as the INTERVAL type writes
     * it, then units i128, of every pair of fields.
     */
    @Test
    void intervalsOfEveryQualifier() throws TurborelException {
        for (IntervalField start : IntervalField.values()) {
            for (IntervalField end : IntervalField.values()) {
                if (end.number() < start.number()) {
                    continue;
                }
                Value.Interval i = new Value.Interval(new Qualifier(start, end, 9, 6), Writer.I128_MIN);
                byte[] bytes =
                        cat(u8(13), u32(start.number()), u32(end.number()), u32(9), u32(6), i128(Writer.I128_MIN));
                assertArrayEquals(bytes, encoded(i));
                assertEquals(i, decoded(bytes));
            }
        }
        assertBroken(() -> decoded(cat(u8(13), u32(7), u32(0), u32(0), u32(0), i128(BigInteger.ONE))));
    }

    /**
     * Verifies: [PROTOCOL Values] tag 14, a truth value: 0 false, 1 true; another byte fails, and
     * unknown is the null value.
     */
    @Test
    void truthValues() throws TurborelException {
        assertArrayEquals(Fixture.hex("0E01"), encoded(new Value.Truth(true)));
        assertEquals(new Value.Truth(false), decoded(Fixture.hex("0E00")));
        assertBroken(() -> decoded(Fixture.hex("0E02")));
    }

    /**
     * Verifies: [PROTOCOL Values] tag 15, decimal floating point: coefficient i128, then exponent
     * i32 as u32, in the canonical form Turborel writes and takes; another form, more than 34
     * digits, or an exponent past DECFLOAT's range is refused.
     */
    @Test
    void decfloatsInTheirCanonicalForm() throws TurborelException {
        Value.Decfloat d = Value.Decfloat.of(new BigDecimal("1.500"));
        assertEquals(new Value.Decfloat(BigInteger.valueOf(15), -1), d);
        assertArrayEquals(cat(u8(15), i128(BigInteger.valueOf(15)), u32(-1)), encoded(d));
        assertArrayEquals(encoded(d), encoded(new Value.Decfloat(BigInteger.valueOf(1500), -3)));
        assertEquals(new BigDecimal("1.5"), d.toBigDecimal());
        assertEquals(d, decoded(encoded(d)));
        assertEquals(new Value.Decfloat(BigInteger.ZERO, 0), Value.Decfloat.of(new BigDecimal("0.000")));
        assertEquals(new Value.Decfloat(BigInteger.ZERO, 0), decoded(cat(u8(15), i128(BigInteger.ZERO), u32(0))));
        BigInteger max = BigInteger.TEN.pow(34).subtract(BigInteger.ONE);
        assertEquals(new Value.Decfloat(max, 6111), decoded(cat(u8(15), i128(max), u32(6111))));
        assertEquals(new Value.Decfloat(BigInteger.ONE, -6143), decoded(cat(u8(15), i128(BigInteger.ONE), u32(-6143))));
        assertBroken(() -> decoded(cat(u8(15), i128(BigInteger.TEN), u32(0))));
        assertBroken(() -> decoded(cat(u8(15), i128(BigInteger.ZERO), u32(1))));
        assertBroken(() -> decoded(cat(u8(15), i128(BigInteger.TEN.pow(34).add(BigInteger.ONE)), u32(0))));
        assertBroken(() -> decoded(cat(u8(15), i128(BigInteger.ONE), u32(6145))));
        assertBroken(() -> decoded(cat(u8(15), i128(BigInteger.ONE), u32(-6144))));
        assertOutOfRange(() ->
                Value.Decfloat.of(new BigDecimal(max.multiply(BigInteger.TEN).add(BigInteger.ONE))));
        assertOutOfRange(() -> Value.Decfloat.of(new BigDecimal("1E+6145")));
        assertOutOfRange(() -> Value.Decfloat.of(new BigDecimal("1E-6144")));
    }

    /**
     * Verifies: [PROTOCOL Frames] a value of a tag no value has, 5, 7, 10, 12, 18, 19 or 28,
     * fails the connection.
     */
    @Test
    void aTagOfNoValueFails() {
        for (int tag : new int[] {5, 7, 10, 12, 18, 19, 28, 255}) {
            assertBroken(() -> decoded(u8(tag)));
        }
    }
}
