package com.turborel.jdbc.internal.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A value as Turborel writes it ({@code src/value.rs}'s {@code Display} and each type's literal). */
class ValueTextTest {
    private static final long MICROS_PER_DAY = 86_400_000_000L;
    private static final long MICROS_PER_SECOND = 1_000_000L;

    private static Value exact(long units, long scale) {
        return new Value.Exact(BigInteger.valueOf(units), scale);
    }

    private static long day(LocalDate date) {
        return (date.toEpochDay() - LocalDate.of(1, 1, 1).toEpochDay()) * MICROS_PER_DAY;
    }

    /**
     * Verifies: [PROTOCOL Values] numbers are written as Turborel writes them: an exact number
     * plainly, its scale's digits after its point, past 18 of scale with a power of ten; an
     * approximate one as its shortest digits, E and its exponent; a decimal floating point one
     * plainly from an adjusted exponent of -6 to 33, else with E.
     */
    @Test
    void numbers() {
        assertEquals("1.50", ValueText.of(exact(150, 2)));
        assertEquals("-0.005", ValueText.of(exact(-5, 3)));
        assertEquals("7", ValueText.of(exact(7, 0)));
        assertEquals("7E-20", ValueText.of(exact(7, 20)));
        assertEquals("1.5E3", ValueText.of(new Value.Approximate(1500.0)));
        assertEquals("1E-1", ValueText.of(new Value.Approximate(0.1)));
        assertEquals("1E0", ValueText.of(new Value.Approximate(1.0)));
        assertEquals("-2.5E-7", ValueText.of(new Value.Approximate(-2.5e-7)));
        assertEquals("0E0", ValueText.of(new Value.Approximate(0.0)));
        // The shortest digits, as Rust's Display finds them, where JDK 17's Double.toString is not
        // the shortest: 1E23, 2E23 and 5E-324; the rest as Python's repr, which finds the same.
        String[][] rust = {
            {"1.0E23", "1E23"},
            {"2.0E23", "2E23"},
            {"4.9E-324", "5E-324"},
            {"2.0E-3", "2E-3"},
            {"2.2250738585072014E-308", "2.2250738585072014E-308"},
            {"1.7976931348623157E308", "1.7976931348623157E308"},
            {"0.30000000000000004", "3.0000000000000004E-1"},
            {"0.3333333333333333", "3.333333333333333E-1"},
            {"4.35", "4.35E0"},
            {"100", "1E2"},
            {"1.2345678901234568E17", "1.2345678901234568E17"},
            {"1.0E-5", "1E-5"},
            {"2.7216092808335446E276", "2.7216092808335446E276"},
            {"3.035766360112293E-65", "3.035766360112293E-65"},
            {"6.665830898196862E-279", "6.665830898196862E-279"},
            {"2.3558459513947972E198", "2.3558459513947972E198"}
        };
        for (String[] each : rust) {
            assertEquals(each[1], ValueText.of(new Value.Approximate(Double.parseDouble(each[0]))), each[0]);
        }
        assertEquals("-0E0", ValueText.of(new Value.Approximate(-0.0)));
        assertEquals("NaN", ValueText.of(new Value.Approximate(Double.NaN)));
        assertEquals("inf", ValueText.of(new Value.Approximate(Double.POSITIVE_INFINITY)));
        assertEquals("-inf", ValueText.of(new Value.Approximate(Double.NEGATIVE_INFINITY)));
        assertEquals("1.5", ValueText.of(new Value.Decfloat(BigInteger.valueOf(15), -1)));
        assertEquals("1200", ValueText.of(new Value.Decfloat(BigInteger.valueOf(12), 2)));
        assertEquals("0", ValueText.of(new Value.Decfloat(BigInteger.ZERO, 0)));
        assertEquals("0.000005", ValueText.of(new Value.Decfloat(BigInteger.valueOf(5), -6)));
        assertEquals("5E-8", ValueText.of(new Value.Decfloat(BigInteger.valueOf(5), -8)));
        assertEquals("1E40", ValueText.of(new Value.Decfloat(BigInteger.ONE, 40)));
        assertEquals("-1.23E40", ValueText.of(new Value.Decfloat(BigInteger.valueOf(-123), 38)));
        assertEquals("CAST('1.5' AS DECFLOAT)", ValueText.literal(new Value.Decfloat(BigInteger.valueOf(15), -1)));
    }

    /**
     * Verifies: [PROTOCOL Values] a datetime is shown in its displacement, a date as its year,
     * month and day, a time to its precision, a zoned one with its displacement; its literal names its
     * kind.
     */
    @Test
    void datetimes() {
        long date = day(LocalDate.of(2026, 11, 20));
        assertEquals("2026-11-20", ValueText.of(new Value.Datetime(Value.Moment.DATE, false, 0, (short) 0, date)));
        long utc = (7 * 3600 + 35 * 60 + 9) * MICROS_PER_SECOND + 500_000;
        Value time = new Value.Datetime(Value.Moment.TIME, true, 1, (short) 330, utc);
        assertEquals("13:05:09.5+05:30", ValueText.of(time));
        Value west = new Value.Datetime(Value.Moment.TIME, true, 0, (short) -90, 0);
        assertEquals("22:30:00-01:30", ValueText.of(west));
        Value stamp = new Value.Datetime(Value.Moment.TIMESTAMP, false, 6, (short) 0, date + 123);
        assertEquals("2026-11-20 00:00:00.000123", ValueText.of(stamp));
        assertEquals("TIMESTAMP '2026-11-20 00:00:00.000123'", ValueText.literal(stamp));
        assertEquals(
                "DATE '0001-01-01'", ValueText.literal(new Value.Datetime(Value.Moment.DATE, false, 0, (short) 0, 0)));
    }

    /**
     * Verifies: [PROTOCOL Values] an interval is written in its qualifier's fields, the first as it is
     * and each after it of two digits, a second's fraction to its precision, a negative one with a
     * sign; its literal names its qualifier.
     */
    @Test
    void intervals() {
        Qualifier yearMonth = new Qualifier(IntervalField.YEAR, IntervalField.MONTH, 2, 0);
        assertEquals("2-02", ValueText.of(new Value.Interval(yearMonth, BigInteger.valueOf(26))));
        Value back = new Value.Interval(yearMonth, BigInteger.valueOf(-26));
        assertEquals("-2-02", ValueText.of(back));
        assertEquals("INTERVAL -'2-02' YEAR(2) TO MONTH", ValueText.literal(back));
        Qualifier dayToSecond = new Qualifier(IntervalField.DAY, IntervalField.SECOND, 2, 3);
        long units = MICROS_PER_DAY + (2 * 3600 + 3 * 60 + 4) * MICROS_PER_SECOND + 500_000;
        Value span = new Value.Interval(dayToSecond, BigInteger.valueOf(units));
        assertEquals("1 02:03:04.500", ValueText.of(span));
        assertEquals("INTERVAL '1 02:03:04.500' DAY(2) TO SECOND(3)", ValueText.literal(span));
        Qualifier second = new Qualifier(IntervalField.SECOND, IntervalField.SECOND, 2, 3);
        assertEquals("SECOND(2, 3)", ValueText.qualifier(second));
        assertEquals("4.500", ValueText.of(new Value.Interval(second, BigInteger.valueOf(4_500_000))));
        Qualifier minute = new Qualifier(IntervalField.MINUTE, IntervalField.MINUTE, 2, 0);
        assertEquals("MINUTE(2)", ValueText.qualifier(minute));
        assertEquals("5", ValueText.of(new Value.Interval(minute, BigInteger.valueOf(5 * 60 * MICROS_PER_SECOND))));
        assertEquals(
                "HOUR(2) TO MINUTE",
                ValueText.qualifier(new Qualifier(IntervalField.HOUR, IntervalField.MINUTE, 2, 0)));
    }

    /**
     * Verifies: [PROTOCOL Values] strings, binary strings, truth values and large objects are written
     * as Turborel writes them: a binary string as its hexadecimal digits, a large object held whole
     * as its text or digits, one sent by locator as what it is; their literals quoted.
     */
    @Test
    void stringsAndLargeObjects() throws TurborelException {
        assertEquals("NULL", ValueText.of(Value.NULL));
        assertEquals("it's", ValueText.of(new Value.Text("it's")));
        assertEquals("'it''s'", ValueText.literal(new Value.Text("it's")));
        assertEquals("TRUE", ValueText.of(new Value.Truth(true)));
        assertEquals("FALSE", ValueText.literal(new Value.Truth(false)));
        Value binary = new OctetValue.Binary(Octets.of(new byte[] {0x0A, (byte) 0xFF}));
        assertEquals("0AFF", ValueText.of(binary));
        assertEquals("X'0AFF'", ValueText.literal(binary));
        assertEquals("hé", ValueText.of(Lob.Whole.ofText("hé")));
        assertEquals("'hé'", ValueText.literal(Lob.Whole.ofText("hé")));
        Value blob = new Lob.Whole(LobKind.BINARY, Octets.of(new byte[] {1}));
        assertEquals("01", ValueText.of(blob));
        assertEquals("X'01'", ValueText.literal(blob));
        assertEquals(
                "<CHARACTER LARGE OBJECT of 5 characters>", ValueText.of(new Lob.Locator(LobKind.CHARACTER, 1, 9, 5)));
        assertEquals("<BINARY LARGE OBJECT of 9 octets>", ValueText.literal(new Lob.Locator(LobKind.BINARY, 1, 9, 9)));
    }

    /**
     * Verifies: [PROTOCOL Values] JSON, XML, UUID and a plug-in's values are written as their text, a
     * JSON item with no text as what is wrong; composite values as their literals, each part's
     * literal inside.
     */
    @Test
    void textsAndComposites() throws TurborelException {
        OctetValue.Json json = OctetValue.Json.ofText("{\"a\":1}");
        assertEquals("{\"a\":1}", ValueText.of(json));
        assertEquals("JSON('{\"a\":1}')", ValueText.literal(json));
        Value datetime = new Value.Datetime(Value.Moment.DATE, false, 0, (short) 0, 0);
        assertTrue(ValueText.of(OctetValue.Json.of(new JsonItem.ScalarItem(datetime)))
                .startsWith("<"));
        assertEquals("<a/>", ValueText.of(new Value.Xml("<a/>")));
        assertEquals("XMLPARSE(CONTENT '<a/>' PRESERVE WHITESPACE)", ValueText.literal(new Value.Xml("<a/>")));
        Value.Uuid uuid = Value.Uuid.parse("919108f7-52d1-4320-9bac-f847db4148a8");
        assertEquals(
                "UUID '919108f7-52d1-4320-9bac-f847db4148a8'", ValueText.literal(uuid == null ? Value.NULL : uuid));
        Value plugin = new OctetValue.Plugin("POINT", "(1 2)", Octets.of(new byte[0]));
        assertEquals("(1 2)", ValueText.of(plugin));
        assertEquals("(1 2)", ValueText.literal(plugin));
        Value array = new Value.Array(List.of(exact(1, 0), new Value.Text("a"), Value.NULL));
        assertEquals("ARRAY[1, 'a', NULL]", ValueText.of(array));
        assertEquals("MULTISET[DATE '0001-01-01']", ValueText.of(new Value.Multiset(List.of(datetime))));
        assertEquals("ROW(1.50, TRUE)", ValueText.of(new Value.Row(List.of(exact(150, 2), new Value.Truth(true)))));
        assertEquals("S.T(1)", ValueText.of(new Value.Structured(new TypeName("S", "T"), List.of(exact(1, 0)))));
        assertEquals("REF(0102)", ValueText.of(new OctetValue.Reference(Octets.of(new byte[] {1, 2}))));
    }
}
