package com.turborel.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.internal.codec.CharUnits;
import com.turborel.jdbc.internal.codec.DataType;
import com.turborel.jdbc.internal.codec.DatetimeKind;
import com.turborel.jdbc.internal.codec.IntervalField;
import com.turborel.jdbc.internal.codec.Lob;
import com.turborel.jdbc.internal.codec.LobKind;
import com.turborel.jdbc.internal.codec.OctetValue;
import com.turborel.jdbc.internal.codec.Octets;
import com.turborel.jdbc.internal.codec.Qualifier;
import com.turborel.jdbc.internal.codec.Value;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Values read as a getter asks, and Java values sent as a parameter's type ([DESIGN] D2). */
class ValuesTest {
    private static final DataType TEXT = new DataType.CharacterType(true, 10, CharUnits.CHARACTERS);

    private static Value exact(String n) {
        return Value.Exact.of(new BigDecimal(n));
    }

    /**
     * Verifies: [DESIGN] D2 a number is read from an exact number, a DECFLOAT, an approximate one,
     * a truth value or a string; a whole number in its range, its fraction dropped, else SQLSTATE
     * 22003; anything else is no number (SQLSTATE 22018).
     */
    @Test
    void numbersAreRead() throws SQLException {
        assertEquals(new BigDecimal("1.50"), FromValue.decimal(exact("1.50")));
        assertEquals(
                new BigDecimal("12E+3"),
                FromValue.decimal(new Value.Decfloat(BigInteger.valueOf(12), 3)).stripTrailingZeros());
        assertEquals(new BigDecimal("0.5"), FromValue.decimal(new Value.Approximate(0.5)));
        assertEquals(BigDecimal.ONE, FromValue.decimal(new Value.Truth(true)));
        assertEquals(BigDecimal.ZERO, FromValue.decimal(new Value.Truth(false)));
        assertEquals(new BigDecimal("7"), FromValue.decimal(new Value.Text(" 7 ")));
        assertEquals(
                "22018",
                assertThrows(SQLException.class, () -> FromValue.decimal(new Value.Text("x")))
                        .getSQLState());
        assertEquals(
                "22018",
                assertThrows(SQLException.class, () -> FromValue.decimal(new Value.Approximate(Double.NaN)))
                        .getSQLState());
        assertEquals(1, FromValue.whole(exact("1.9"), 0, 10));
        assertEquals(
                "22003",
                assertThrows(SQLException.class, () -> FromValue.whole(exact("300"), -128, 127))
                        .getSQLState());
        assertEquals(
                "22003",
                assertThrows(SQLException.class, () -> FromValue.whole(exact("-300"), -128, 127))
                        .getSQLState());
        assertEquals(0.25, FromValue.approximate(new Value.Approximate(0.25)));
        assertEquals(2.5, FromValue.approximate(new Value.Text("2.5")));
        assertEquals(1.5, FromValue.approximate(exact("1.5")));
        assertEquals(
                "22018",
                assertThrows(SQLException.class, () -> FromValue.approximate(new Value.Text("x")))
                        .getSQLState());
    }

    /**
     * Verifies: [DESIGN] D2 a truth value is read from a truth value, a number not zero, or the
     * words of one in any case; octets from binary, a UUID, a reference or a string; a UUID from
     * its text or 16 octets.
     */
    @Test
    void truthOctetsAndUuidsAreRead() throws SQLException {
        assertTrue(FromValue.truth(new Value.Truth(true)));
        assertTrue(FromValue.truth(exact("2")));
        assertFalse(FromValue.truth(exact("0")));
        for (String word : List.of("true", "T", "yes", "Y", "1")) {
            assertTrue(FromValue.truth(new Value.Text(word)), word);
        }
        for (String word : List.of("false", "F", "no", "N", "0")) {
            assertFalse(FromValue.truth(new Value.Text(word)), word);
        }
        assertEquals(
                "22018",
                assertThrows(SQLException.class, () -> FromValue.truth(new Value.Text("maybe")))
                        .getSQLState());
        UUID u = UUID.randomUUID();
        Value.Uuid uv = new Value.Uuid(u);
        assertArrayEquals(
                new byte[] {1, 2}, FromValue.bytes(new OctetValue.Binary(Octets.of(new byte[] {1, 2})), null));
        assertArrayEquals(uv.octets(), FromValue.bytes(uv, null));
        assertArrayEquals(new byte[] {9}, FromValue.bytes(new OctetValue.Reference(Octets.of(new byte[] {9})), null));
        assertArrayEquals(
                "é".getBytes(java.nio.charset.StandardCharsets.UTF_8), FromValue.bytes(new Value.Text("é"), null));
        assertEquals(
                "22018",
                assertThrows(SQLException.class, () -> FromValue.bytes(exact("1"), null))
                        .getSQLState());
        assertEquals(u, FromValue.uuid(uv));
        assertEquals(u, FromValue.uuid(new Value.Text(u.toString())));
        assertEquals(u, FromValue.uuid(new OctetValue.Binary(Octets.of(uv.octets()))));
        assertEquals(
                "22018",
                assertThrows(SQLException.class, () -> FromValue.uuid(new Value.Text("no")))
                        .getSQLState());
        assertEquals(
                "22018",
                assertThrows(SQLException.class, () -> FromValue.uuid(exact("1")))
                        .getSQLState());
    }

    /**
     * Verifies: [DESIGN] D2 a datetime is read in its displacement, from its value or its text; a
     * datetime sent is of the parameter's kind and precision, a local one of no displacement, a
     * zoned one with its own; each read back as it was sent.
     */
    @Test
    @SuppressWarnings("JavaUtilDate") // The driver takes a java.util.Date, as JDBC programs give one.
    void datetimesGoBothWays() throws SQLException {
        LocalDateTime at = LocalDateTime.of(2026, 11, 20, 10, 11, 12, 500_000_000);
        Value timestamp = ToValue.datetime(at, DatetimeKind.TIMESTAMP, 6);
        assertEquals(at, FromValue.local(timestamp));
        assertEquals(at.toLocalTime(), FromValue.time(ToValue.datetime(at.toLocalTime(), DatetimeKind.TIME, 6)));
        assertEquals(
                at.toLocalDate().atStartOfDay(),
                FromValue.local(ToValue.datetime(at.toLocalDate(), DatetimeKind.DATE, 0)));
        OffsetDateTime zoned = OffsetDateTime.parse("2026-01-02T03:04:05+02:00");
        Value z = ToValue.datetime(zoned, DatetimeKind.TIMESTAMP_ZONED, 6);
        assertEquals(zoned, FromValue.offset(z));
        assertEquals(zoned.toOffsetTime(), FromValue.offsetTime(z));
        assertEquals(LocalDate.of(2026, 1, 2).atStartOfDay(), FromValue.local(new Value.Text("2026-01-02")));
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4), FromValue.local(new Value.Text("2026-01-02 03:04")));
        assertEquals(LocalTime.of(3, 4, 5), FromValue.time(new Value.Text("03:04:05")));
        assertEquals(zoned, FromValue.offset(new Value.Text("2026-01-02 03:04:05+02:00")));
        assertEquals(
                LocalDateTime.of(2026, 1, 2, 0, 0).atOffset(java.time.ZoneOffset.UTC),
                FromValue.offset(new Value.Text("2026-01-02T00:00Z")));
        for (Value bad : List.of(new Value.Text("x"), exact("1"))) {
            assertThrows(SQLException.class, () -> FromValue.local(bad));
        }
        assertThrows(SQLException.class, () -> FromValue.time(new Value.Text("x")));
        assertThrows(SQLException.class, () -> FromValue.offset(new Value.Text("x")));
        DataType stamp = new DataType.DatetimeType(DatetimeKind.TIMESTAMP, 6);
        assertEquals(at, FromValue.local(ToValue.of(java.sql.Timestamp.valueOf(at), stamp)));
        assertEquals(at, FromValue.local(ToValue.of("2026-11-20 10:11:12.5", stamp)));
        assertEquals(
                LocalTime.of(3, 4),
                FromValue.time(ToValue.of("03:04", new DataType.DatetimeType(DatetimeKind.TIME, 0))));
        assertEquals(
                at.toLocalDate(),
                FromValue.local(ToValue.of(java.sql.Date.valueOf(at.toLocalDate()), stamp))
                        .toLocalDate());
        assertEquals(LocalTime.of(1, 2, 3), FromValue.time(ToValue.of(java.sql.Time.valueOf("01:02:03"), stamp)));
        assertInstanceOf(Value.Datetime.class, ToValue.of(new java.util.Date(0), stamp));
        assertInstanceOf(Value.Datetime.class, ToValue.of(zoned.toZonedDateTime(), stamp));
        assertInstanceOf(Value.Datetime.class, ToValue.of(zoned.toInstant(), stamp));
        assertInstanceOf(
                Value.Datetime.class,
                ToValue.of(zoned.toOffsetTime(), new DataType.DatetimeType(DatetimeKind.TIME_ZONED, 0)));
    }

    /**
     * Verifies: [DESIGN] D2 a Java value sent to a parameter is made a value of its type: text of
     * anything, a number of a number or its text, a truth value of its words, octets, a large
     * object held whole, a UUID, XML, JSON, an array of its elements' type, an interval of a
     * Duration or a Period; of no type known, of its own; the null value of none.
     */
    @Test
    void parametersTakeTheirTypes() throws SQLException {
        assertEquals(Value.NULL, ToValue.of(null, TEXT));
        assertEquals(new Value.Text("12.50"), ToValue.of(new BigDecimal("12.50"), TEXT));
        assertEquals(new Value.Text("5"), ToValue.of(5, TEXT));
        assertEquals(new Value.Text("0102"), ToValue.of(new byte[] {1, 2}, TEXT));
        assertEquals(exact("12"), ToValue.of("12", new DataType.ExactType(10, 0)));
        assertEquals(exact("3"), ToValue.of(BigInteger.valueOf(3), new DataType.ExactType(10, 0)));
        assertEquals(exact("1.5"), ToValue.of(1.5, new DataType.ExactType(10, 1)));
        assertEquals(
                "22018",
                assertThrows(SQLException.class, () -> ToValue.of(Double.NaN, new DataType.ExactType(10, 1)))
                        .getSQLState());
        assertInstanceOf(Value.Decfloat.class, ToValue.of("1.25", new DataType.DecfloatType(34)));
        assertEquals(new Value.Approximate(2.0), ToValue.of(2, new DataType.ApproximateType(53)));
        assertEquals(new Value.Approximate(2.5), ToValue.of("2.5", new DataType.ApproximateType(53)));
        assertEquals(new Value.Truth(true), ToValue.of("yes", new DataType.BooleanType()));
        assertEquals(
                new OctetValue.Binary(Octets.of(new byte[] {7})),
                ToValue.of(new byte[] {7}, new DataType.BinaryType(true, 4)));
        assertInstanceOf(
                Lob.Whole.class, ToValue.of("big", new DataType.LobType(LobKind.CHARACTER, 10, CharUnits.CHARACTERS)));
        assertInstanceOf(
                Lob.Whole.class,
                ToValue.of(new byte[] {1}, new DataType.LobType(LobKind.BINARY, 10, CharUnits.OCTETS)));
        assertInstanceOf(
                Lob.Whole.class,
                ToValue.of(new JdbcClob("c"), new DataType.LobType(LobKind.CHARACTER, 10, CharUnits.CHARACTERS)));
        assertInstanceOf(
                Lob.Whole.class,
                ToValue.of(new JdbcBlob(new byte[] {2}), new DataType.LobType(LobKind.BINARY, 10, CharUnits.OCTETS)));
    }

    /**
     * Verifies: [DESIGN] D2 a Java value sent to a parameter of a UUID, XML, JSON, an array or an
     * interval is made a value of its type; one of a type it is not is refused.
     */
    @Test
    void constructedParametersTakeTheirTypes() throws SQLException {
        UUID u = UUID.randomUUID();
        assertEquals(new Value.Uuid(u), ToValue.of(u, new DataType.UuidType()));
        assertEquals(new Value.Uuid(u), ToValue.of(u.toString(), new DataType.UuidType()));
        DataType xml = new DataType.XmlType(DataType.XmlPrimary.CONTENT, DataType.XmlSecondary.UNTYPED, null);
        assertEquals(new Value.Xml("<a/>"), ToValue.of("<a/>", xml));
        JdbcSQLXML made = new JdbcSQLXML();
        made.setString("<b/>");
        assertEquals(new Value.Xml("<b/>"), ToValue.of(made, xml));
        assertInstanceOf(OctetValue.Json.class, ToValue.of("{\"a\": 1}", new DataType.JsonType()));
        Value array = ToValue.of(new Object[] {1, 2}, new DataType.ArrayType(10, new DataType.ExactType(10, 0)));
        assertEquals(new Value.Array(List.of(exact("1"), exact("2"))), array);
        assertEquals(
                new Value.Array(List.of(exact("3"))),
                ToValue.of(List.of(3), new DataType.ArrayType(10, new DataType.ExactType(10, 0))));
        assertEquals(
                new Value.Array(List.of(new Value.Text("x"))),
                ToValue.of(new JdbcArray("VARCHAR", new Object[] {"x"}), null));
        assertThrows(SQLException.class, () -> ToValue.of(5, new DataType.ArrayType(10, TEXT)));
        Qualifier dayToSecond = new Qualifier(IntervalField.DAY, IntervalField.SECOND, 2, 6);
        Qualifier yearToMonth = new Qualifier(IntervalField.YEAR, IntervalField.MONTH, 2, 0);
        assertEquals(
                new Value.Interval(dayToSecond, BigInteger.valueOf(1_500_000)),
                ToValue.of(Duration.ofMillis(1500), new DataType.IntervalType(dayToSecond)));
        assertEquals(
                new Value.Interval(yearToMonth, BigInteger.valueOf(14)),
                ToValue.of(Period.of(1, 2, 0), new DataType.IntervalType(yearToMonth)));
        assertEquals(
                new Value.Interval(yearToMonth, BigInteger.valueOf(24)),
                ToValue.of(2, new DataType.IntervalType(yearToMonth)));
        assertThrows(SQLException.class, () -> ToValue.of("1 day", new DataType.IntervalType(dayToSecond)));
        assertEquals(new Value.Truth(true), ToValue.of(true, new DataType.MultisetType(TEXT)));
        Value given = new Value.Text("as is");
        assertEquals(given, ToValue.of(given, TEXT));
    }

    /**
     * Verifies: [DESIGN] D2 a Java value of no type known is sent as its own: a string, a
     * character, a truth value, a number exact or approximate, octets, a UUID, a datetime of each
     * kind, a large object, XML, an array; an object of no such type is refused (SQLSTATE 22018).
     */
    @Test
    @SuppressWarnings("JavaUtilDate") // The driver takes a java.util.Date, as JDBC programs give one.
    void valuesOfNoTypeAreTheirOwn() throws SQLException {
        assertEquals(new Value.Text("c"), ToValue.of('c', null));
        assertEquals(new Value.Truth(false), ToValue.of(false, null));
        assertEquals(new Value.Approximate(1.5f), ToValue.of(1.5f, null));
        assertEquals(exact("9"), ToValue.of(9L, null));
        assertInstanceOf(OctetValue.Binary.class, ToValue.of(new byte[0], null));
        assertInstanceOf(Value.Uuid.class, ToValue.of(UUID.randomUUID(), null));
        for (Object temporal : List.of(
                LocalDate.of(2026, 1, 2),
                LocalTime.NOON,
                LocalDateTime.of(2026, 1, 2, 3, 4),
                OffsetDateTime.parse("2026-01-02T03:04:05+02:00"),
                java.time.OffsetTime.parse("03:04:05+02:00"),
                java.sql.Date.valueOf("2026-01-01"),
                java.sql.Time.valueOf("01:00:00"),
                new java.util.Date(0),
                java.time.Instant.EPOCH)) {
            assertInstanceOf(Value.Datetime.class, ToValue.of(temporal, null), temporal.toString());
        }
        assertInstanceOf(Lob.Whole.class, ToValue.of(new JdbcClob("x"), null));
        assertInstanceOf(Lob.Whole.class, ToValue.of(new JdbcBlob(new byte[] {1}), null));
        JdbcSQLXML xml = new JdbcSQLXML("<a/>");
        assertEquals(new Value.Xml("<a/>"), ToValue.of(xml, null));
        assertInstanceOf(Value.Array.class, ToValue.of(List.of("a"), null));
        assertInstanceOf(Value.Array.class, ToValue.of(new Object[] {"a"}, null));
        assertEquals(
                "22018",
                assertThrows(SQLException.class, () -> ToValue.of(new Object(), null))
                        .getSQLState());
        assertEquals(new BigDecimal("4"), ToValue.number(new Value.Text("4")));
        assertEquals("plain", ToValue.text("plain"));
    }

    /**
     * Verifies: [DESIGN] D2 the rest of what a parameter takes: a float's number, a zoned
     * datetime's instant, a Clob and a Blob of another driver's, held whole, octets of text, a
     * large object of another kind's text, an interval only of its own kind's Java value; a large
     * object is read only by its connection.
     */
    @Test
    void theRestOfWhatParametersTake() throws SQLException {
        assertEquals(exact("1.5"), ToValue.of(1.5f, new DataType.ExactType(10, 1)));
        assertEquals(new BigDecimal("2.5"), ToValue.number(2.5f));
        assertInstanceOf(
                Value.Datetime.class,
                ToValue.of(java.time.ZonedDateTime.parse("2026-01-02T03:04:05+02:00[Europe/Paris]"), null));
        javax.sql.rowset.serial.SerialClob clob = new javax.sql.rowset.serial.SerialClob("clob".toCharArray());
        javax.sql.rowset.serial.SerialBlob blob = new javax.sql.rowset.serial.SerialBlob(new byte[] {1, 2});
        assertInstanceOf(Lob.Whole.class, ToValue.of(clob, null));
        assertInstanceOf(Lob.Whole.class, ToValue.of(blob, null));
        assertEquals("clob", ToValue.text(clob));
        assertArrayEquals(new byte[] {1, 2}, ToValue.bytes(blob));
        assertArrayEquals("é".getBytes(java.nio.charset.StandardCharsets.UTF_8), ToValue.bytes("é"));
        assertArrayEquals(
                new Value.Uuid(UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")).octets(),
                ToValue.bytes(UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")));
        assertThrows(SQLException.class, () -> ToValue.bytes(new BigDecimal("1")));
        DataType blobType = new DataType.LobType(LobKind.BINARY, 10, CharUnits.OCTETS);
        DataType clobType = new DataType.LobType(LobKind.CHARACTER, 10, CharUnits.CHARACTERS);
        assertInstanceOf(Lob.Whole.class, ToValue.of(new JdbcClob("x"), blobType));
        assertInstanceOf(Lob.Whole.class, ToValue.of(new JdbcBlob(new byte[] {1}), clobType));
        Qualifier dayToSecond = new Qualifier(IntervalField.DAY, IntervalField.SECOND, 2, 6);
        Qualifier yearToMonth = new Qualifier(IntervalField.YEAR, IntervalField.MONTH, 2, 0);
        assertThrows(SQLException.class, () -> ToValue.of(Period.ofDays(1), new DataType.IntervalType(dayToSecond)));
        assertThrows(SQLException.class, () -> ToValue.of(Duration.ofDays(1), new DataType.IntervalType(yearToMonth)));
        assertThrows(SQLException.class, () -> ToValue.of(1, new DataType.IntervalType(dayToSecond)));
        DataType stamp = new DataType.DatetimeType(DatetimeKind.TIMESTAMP, 6);
        assertEquals(LocalDateTime.of(2026, 1, 2, 0, 0), FromValue.local(ToValue.of("2026-01-02", stamp)));
        Lob.Locator locator = new Lob.Locator(LobKind.CHARACTER, 1, 1, 1);
        assertEquals(
                "TB910",
                assertThrows(SQLException.class, () -> FromValue.string(locator, null))
                        .getSQLState());
        assertEquals(
                "TB910",
                assertThrows(SQLException.class, () -> FromValue.string(new Lob.Locator(LobKind.BINARY, 1, 1, 1), null))
                        .getSQLState());
        assertEquals(
                "TB910",
                assertThrows(SQLException.class, () -> FromValue.bytes(locator, null))
                        .getSQLState());
        assertEquals("a", FromValue.string(new Value.Text("a"), null));
        assertEquals("1", FromValue.string(exact("1"), null));
        assertEquals(
                LocalDateTime.of(2026, 1, 2, 0, 0).atOffset(java.time.ZoneOffset.UTC),
                FromValue.offset(new Value.Text("2026-01-02T00:00Z")));
    }
}
