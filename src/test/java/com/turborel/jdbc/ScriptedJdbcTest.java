package com.turborel.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.internal.codec.CharUnits;
import com.turborel.jdbc.internal.codec.Column;
import com.turborel.jdbc.internal.codec.DataType;
import com.turborel.jdbc.internal.codec.DatetimeKind;
import com.turborel.jdbc.internal.codec.IntervalField;
import com.turborel.jdbc.internal.codec.Lob;
import com.turborel.jdbc.internal.codec.LobKind;
import com.turborel.jdbc.internal.codec.OctetValue;
import com.turborel.jdbc.internal.codec.Octets;
import com.turborel.jdbc.internal.codec.Qualifier;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.internal.session.Scripted;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The JDBC layer over a connection the test scripts ([DESIGN] D2). */
class ScriptedJdbcTest {
    private static final DataType TEXT = new DataType.CharacterType(true, 10, CharUnits.CHARACTERS);

    private static final Qualifier DAYS = new Qualifier(IntervalField.DAY, IntervalField.SECOND, 2, 6);

    private static final Qualifier YEARS = new Qualifier(IntervalField.YEAR, IntervalField.MONTH, 2, 0);

    private static final UUID U = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    private static JdbcConnection connection(byte[]... replies) throws TurborelException {
        return new JdbcConnection(Scripted.after(List.of(), replies), "jdbc:turborel://scripted");
    }

    private static Value datetime(Value.Moment moment, boolean zoned) throws SQLException {
        DatetimeKind kind = switch (moment) {
            case DATE -> DatetimeKind.DATE;
            case TIME -> zoned ? DatetimeKind.TIME_ZONED : DatetimeKind.TIME;
            case TIMESTAMP -> zoned ? DatetimeKind.TIMESTAMP_ZONED : DatetimeKind.TIMESTAMP;
        };
        Object at = zoned ? OffsetDateTime.parse("2026-01-02T03:04:05+02:00") : LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        return ToValue.datetime(at, kind, 0);
    }

    /** A result set of one row of the values given, each column its type. */
    private static ResultSet row(JdbcConnection c, List<DataType> types, List<Value> values) {
        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            columns.add(new Column("C" + (i + 1), types.get(i), false));
        }
        return new JdbcResultSet(null, c, columns, Rows.of(List.of(values)));
    }

    /**
     * Verifies: [DESIGN] D2 getObject gives each type's class: a string, a BigDecimal of an exact
     * number or a DECFLOAT, a Float of a REAL and a Double of another approximate number, a
     * Boolean, a date, a time and a timestamp as java.sql's, zoned ones as OffsetTime and
     * OffsetDateTime, an interval a Period or a Duration, octets, a UUID, XML, a large object held
     * whole, an array, and the text of another.
     */
    @Test
    void getObjectGivesEachTypesClass() throws SQLException {
        JdbcConnection c = connection();
        List<DataType> types = new ArrayList<>(List.of(
                TEXT,
                new DataType.ExactType(10, 0),
                new DataType.DecfloatType(34),
                new DataType.ApproximateType(24),
                new DataType.ApproximateType(53),
                new DataType.BooleanType()));
        List<Value> values = new ArrayList<>(List.of(
                new Value.Text("a"),
                Value.Exact.of(BigDecimal.ONE),
                new Value.Decfloat(BigInteger.TWO, 0),
                new Value.Approximate(1.5),
                new Value.Approximate(2.5),
                new Value.Truth(true)));
        List<Class<?>> classes = new ArrayList<>(
                List.of(String.class, BigDecimal.class, BigDecimal.class, Float.class, Double.class, Boolean.class));
        Object[][] more = {
            {datetime(Value.Moment.DATE, false), java.sql.Date.class},
            {datetime(Value.Moment.TIME, false), java.sql.Time.class},
            {datetime(Value.Moment.TIME, true), OffsetTime.class},
            {datetime(Value.Moment.TIMESTAMP, false), java.sql.Timestamp.class},
            {datetime(Value.Moment.TIMESTAMP, true), OffsetDateTime.class},
            {new Value.Interval(YEARS, BigInteger.valueOf(14)), Period.class},
            {new Value.Interval(DAYS, BigInteger.valueOf(1_500_000)), Duration.class},
            {new OctetValue.Binary(Octets.of(new byte[] {1})), byte[].class},
            {new OctetValue.Reference(Octets.of(new byte[] {2})), byte[].class},
            {new Value.Uuid(U), UUID.class},
            {new Value.Xml("<a/>"), JdbcSQLXML.class},
            {Lob.Whole.ofText("t"), JdbcClob.class},
            {new Lob.Whole(LobKind.BINARY, Octets.of(new byte[] {3})), JdbcBlob.class},
            {new Value.Array(List.of(new Value.Text("x"))), JdbcArray.class},
            {new Value.Multiset(List.of(new Value.Text("x"))), String.class},
            {Value.NULL, null},
        };
        for (Object[] each : more) {
            types.add(TEXT);
            values.add((Value) each[0]);
            classes.add((Class<?>) each[1]);
        }
        types.set(19, new DataType.ArrayType(10, TEXT));
        try (ResultSet rs = row(c, types, values)) {
            assertClasses(rs, classes);
            assertEquals(Period.of(1, 2, 0), rs.getObject(12));
            assertEquals(Duration.ofMillis(1500), rs.getObject(13));
        }
    }

    /**
     * Verifies: [DESIGN] D2 getObject of a class reads each datetime as each java.time class asks;
     * the getters of a date, time and timestamp read each moment, a calendar's zone taken for a
     * local one and a zoned one's instant kept.
     */
    @Test
    void datetimesAreReadAsAsked() throws SQLException {
        JdbcConnection c = connection();
        List<Value> moments = List.of(
                datetime(Value.Moment.DATE, false),
                datetime(Value.Moment.TIME, true),
                datetime(Value.Moment.TIMESTAMP, false),
                datetime(Value.Moment.TIMESTAMP, true));
        List<DataType> types = List.of(TEXT, TEXT, TEXT, TEXT);
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        try (ResultSet rs = row(c, types, moments)) {
            assertTrue(rs.next());
            for (int i = 1; i <= moments.size(); i++) {
                for (Class<?> type : List.of(
                        LocalDate.class,
                        LocalTime.class,
                        LocalDateTime.class,
                        OffsetDateTime.class,
                        OffsetTime.class,
                        ZonedDateTime.class,
                        Instant.class)) {
                    assertNotNull(rs.getObject(i, type));
                }
                assertNotNull(rs.getDate(i));
                assertNotNull(rs.getTime(i));
                assertNotNull(rs.getTimestamp(i));
                assertNotNull(rs.getDate(i, utc));
                assertNotNull(rs.getTime(i, utc));
                assertNotNull(rs.getTimestamp(i, utc));
            }
            assertEquals(
                    Instant.parse("2026-01-02T01:04:05Z"),
                    rs.getTimestamp(4, utc).toInstant());
            assertEquals(
                    Instant.parse("2026-01-02T03:04:05Z"),
                    rs.getTimestamp(3, utc).toInstant());
        }
    }

    /**
     * Verifies: [DESIGN] D2 the getters of truths, numbers, octets, streams, large objects held
     * whole, XML and arrays read each value they take, a null as none; an array of an array
     * column's elements is of the element's type.
     */
    @Test
    void gettersReadEachValue() throws SQLException, IOException {
        JdbcConnection c = connection();
        Value blob = new Lob.Whole(LobKind.BINARY, Octets.of(new byte[] {1, 2}));
        List<Value> values = List.of(
                new Value.Truth(true),
                new Value.Approximate(0.5),
                blob,
                Lob.Whole.ofText("clob"),
                new Value.Text("<x/>"),
                new Value.Array(List.of(Value.Exact.of(BigDecimal.TEN))),
                Value.NULL);
        List<DataType> types =
                List.of(TEXT, TEXT, TEXT, TEXT, TEXT, new DataType.ArrayType(1, new DataType.ExactType(2, 0)), TEXT);
        try (ResultSet rs = row(c, types, values)) {
            assertTrue(rs.next());
            assertTrue(rs.getBoolean(1));
            assertEquals(0.5f, rs.getFloat(2));
            assertArrayEquals(new byte[] {1, 2}, rs.getBinaryStream(3).readAllBytes());
            Blob b = rs.getBlob(3);
            assertEquals(2, b.length());
            assertEquals("clob", rs.getClob(4).getSubString(1, 4));
            assertEquals('c', rs.getCharacterStream(4).read());
            assertEquals("<x/>", rs.getSQLXML(5).getString());
            Array a = rs.getArray(6);
            assertEquals("DECIMAL", a.getBaseTypeName());
            assertEquals(BigDecimal.TEN, ((Object[]) a.getArray())[0]);
            assertFalse(rs.getBoolean(7));
            assertEquals(0, rs.getDouble(7));
            assertNull(rs.getDate(7));
            assertNull(rs.getTime(7));
            assertNull(rs.getTimestamp(7, null));
            assertNull(rs.getString(7));
            assertNull(rs.getBigDecimal(7));
            assertNull(rs.getObject(7, String.class));
        }
    }

    /**
     * Verifies: [DESIGN] D2 a result set of no row is before its first and after its last at once
     * only once read; a limit ends it; warnings given it are its own.
     */
    @Test
    void positionsAndLimits() throws SQLException {
        JdbcConnection c = connection();
        JdbcResultSet empty = new JdbcResultSet(null, c, List.of(new Column(null, TEXT, false)), Rows.of(List.of()));
        assertTrue(empty.isBeforeFirst());
        assertFalse(empty.isAfterLast());
        assertFalse(empty.isFirst());
        assertFalse(empty.next());
        assertFalse(empty.isBeforeFirst());
        assertFalse(empty.isAfterLast());
        assertEquals("", empty.getMetaData().getColumnName(1));
        assertEquals(
                "TB910",
                assertThrows(SQLException.class, () -> empty.findColumn("A")).getSQLState());
        SQLWarning warning = new SQLWarning("w");
        List<List<Value>> two = List.of(List.of(new Value.Text("a")), List.of(new Value.Text("b")));
        JdbcResultSet limited = new JdbcResultSet(null, c, List.of(new Column("A", TEXT, false)), Rows.of(two))
                .limited(1)
                .warned(warning);
        assertEquals(warning, limited.getWarnings());
        assertTrue(limited.next());
        assertFalse(limited.isFirst() && limited.next());
        assertNull(limited.getStatement());
    }

    /**
     * Verifies: [DESIGN] D2 a connection's notices are its warnings, chained; a server that grants
     * no Ping is checked by a query, and one that does not answer is not valid; the session's
     * status READ_ONLY makes it read-only; an isolation level the server names otherwise is read
     * committed; turning autocommit on in a transaction commits it.
     */
    @Test
    void aConnectionReadsWhatTheServerTold() throws SQLException {
        JdbcConnection c = connection(
                Scripted.notice("01000", "first"),
                Scripted.notice("01000", "second"),
                Scripted.status("READ_ONLY", "YES"),
                Scripted.status(JdbcConnection.ISOLATION, "SOMETHING"),
                Scripted.complete(true),
                Scripted.notice("01000", "third"),
                Scripted.complete(false),
                Scripted.complete(false));
        c.setAutoCommit(false);
        c.run("VALUES 1");
        assertEquals(List.of("first", "second"), messages(c.getWarnings()));
        assertTrue(c.isReadOnly());
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, c.getTransactionIsolation());
        assertTrue(c.session().inTransaction());
        c.setAutoCommit(true);
        assertEquals(List.of("first", "second", "third"), messages(c.getWarnings()));
        assertTrue(c.isValid(0));
        assertFalse(c.isValid(1), "no reply: not valid");
        assertTrue(c.isClosed(), "lost");
        assertEquals(
                "08006", assertThrows(SQLException.class, c::createStatement).getSQLState());
        c.close();
        assertFalse(c.isWrapperFor(null));
        assertEquals(
                "TB910", assertThrows(SQLException.class, () -> c.unwrap(null)).getSQLState());
    }

    /** Asserts the row of {@code rs} is of the classes given, none the null value. */
    private static void assertClasses(ResultSet rs, List<Class<?>> classes) throws SQLException {
        assertTrue(rs.next());
        for (int i = 0; i < classes.size(); i++) {
            Object read = rs.getObject(i + 1);
            if (classes.get(i) == null) {
                assertNull(read);
            } else {
                assertInstanceOf(classes.get(i), read, "column " + (i + 1));
            }
        }
    }

    /** The messages of a chain of warnings, in its order. */
    private static List<String> messages(@Nullable SQLWarning first) {
        List<String> messages = new ArrayList<>();
        for (SQLWarning w = first; w != null; w = w.getNextWarning()) {
            messages.add(String.valueOf(w.getMessage()));
        }
        return messages;
    }
}
