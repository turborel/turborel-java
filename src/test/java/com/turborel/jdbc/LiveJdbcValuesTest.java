package com.turborel.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.testing.LiveServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Values sent and read through JDBC on a live server ([DESIGN] D2, D14). */
@EnabledIfEnvironmentVariable(named = LiveServer.VARIABLE, matches = ".+")
class LiveJdbcValuesTest {
    private static final String TABLE = "CREATE TABLE JDBC_VALUES (ID INTEGER PRIMARY KEY, NAME VARCHAR(20),"
            + " PRICE DECIMAL(9,2), STAMP TIMESTAMP, D DATE, TM TIME, TZ TIMESTAMP WITH TIME ZONE, F DOUBLE PRECISION,"
            + " R REAL, OK BOOLEAN, BIN VARBINARY(10), U UUID, X XML, B BLOB(1M), C CLOB(1M), SI SMALLINT, BI BIGINT,"
            + " IV INTERVAL DAY TO SECOND)";

    /** A large object past what a row holds whole, so it comes by its locator. */
    private static final int BIG = 100_000;

    private static final UUID U = UUID.randomUUID();

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 20, 10, 11, 12, 500_000_000);

    private static final OffsetDateTime ZONED = OffsetDateTime.parse("2026-01-02T03:04:05+02:00");

    /** The query of two rows, the second's last column null, the result sets' tests read. */
    private static final String TWO_ROWS = "VALUES (1, 'a', CAST(NULL AS INTEGER)), (2, 'b', 3)";

    private static void insert(Connection c) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO JDBC_VALUES VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            p.setInt(1, 1);
            p.setString(2, "ann");
            p.setBigDecimal(3, new BigDecimal("12.50"));
            p.setTimestamp(4, Timestamp.valueOf(AT));
            p.setDate(5, java.sql.Date.valueOf("2026-11-20"));
            p.setTime(6, java.sql.Time.valueOf("10:11:12"));
            p.setObject(7, ZONED);
            p.setDouble(8, 0.25);
            p.setFloat(9, 1.5f);
            p.setBoolean(10, true);
            p.setBytes(11, new byte[] {1, 2, 3});
            p.setObject(12, U);
            SQLXML xml = c.createSQLXML();
            xml.setString("<a>1</a>");
            p.setSQLXML(13, xml);
            p.setBinaryStream(14, new ByteArrayInputStream(new byte[BIG]));
            p.setCharacterStream(15, new StringReader("c".repeat(BIG)));
            p.setShort(16, (short) 7);
            p.setLong(17, 1L << 40);
            p.setObject(18, Duration.ofSeconds(90));
            assertEquals(1, p.executeUpdate());
            p.setInt(1, 2);
            for (int i = 2; i <= 18; i++) {
                p.setNull(i, Types.NULL);
            }
            assertEquals(1, p.executeUpdate());
        }
    }

    private static void scalars(ResultSet rs) throws SQLException {
        assertEquals(1, rs.getInt("ID"));
        assertEquals(1, rs.getByte("ID"));
        assertEquals("ann", rs.getString("NAME"));
        assertEquals(new BigDecimal("12.50"), rs.getBigDecimal("PRICE"));
        assertEquals(Timestamp.valueOf(AT), rs.getTimestamp("STAMP"));
        assertEquals(AT, rs.getObject("STAMP", LocalDateTime.class));
        assertEquals(java.sql.Date.valueOf("2026-11-20"), rs.getDate("D"));
        assertEquals(LocalDate.of(2026, 11, 20), rs.getObject("D", LocalDate.class));
        assertEquals(java.sql.Time.valueOf("10:11:12"), rs.getTime("TM"));
        assertEquals(LocalTime.of(10, 11, 12), rs.getObject("TM", LocalTime.class));
        assertEquals(ZONED, rs.getObject("TZ"));
        assertEquals(ZONED.toInstant(), rs.getTimestamp("TZ").toInstant());
        assertEquals(0.25, rs.getDouble("F"));
        assertEquals(1.5f, rs.getFloat("R"));
        assertInstanceOf(Float.class, rs.getObject("R"));
        assertTrue(rs.getBoolean("OK"));
        assertArrayEquals(new byte[] {1, 2, 3}, rs.getBytes("BIN"));
        assertEquals(U, rs.getObject("U"));
        assertEquals(U, rs.getObject("U", UUID.class));
        assertEquals(7, rs.getShort("SI"));
        assertEquals(1L << 40, rs.getLong("BI"));
        assertEquals(Duration.ofSeconds(90), rs.getObject("IV"));
    }

    private static void objects(ResultSet rs) throws SQLException, IOException {
        assertEquals("<a>1</a>", rs.getSQLXML("X").getString());
        Blob blob = rs.getBlob("B");
        assertEquals(BIG, blob.length());
        assertEquals(10, blob.getBytes(BIG - 9, 20).length);
        assertEquals(BIG, rs.getBinaryStream("B").readAllBytes().length);
        Clob clob = rs.getClob("C");
        assertEquals(BIG, clob.length());
        assertEquals("ccc", clob.getSubString(BIG - 2, 10));
        assertEquals('c', clob.getCharacterStream().read());
        assertEquals(BIG, rs.getString("C").length());
        assertEquals('c', rs.getCharacterStream("C").read());
    }

    private static void nulls(ResultSet rs) throws SQLException {
        assertNull(rs.getString("NAME"));
        assertTrue(rs.wasNull());
        assertEquals(0, rs.getInt("SI"));
        assertFalse(rs.getBoolean("OK"));
        assertNull(rs.getObject("U"));
        assertNull(rs.getObject("U", UUID.class));
        assertNull(rs.getBlob("B"));
        assertNull(rs.getClob("C"));
        assertNull(rs.getBytes("BIN"));
        assertNull(rs.getTimestamp("STAMP"));
    }

    /**
     * Verifies: [DESIGN] D2 every type round-trips through a prepared statement's setters and a
     * result set's getters, by label: strings, exact and approximate numbers, truth values,
     * datetimes in their displacement, octets, UUIDs, XML, large objects by their locators, an
     * interval; a null is read as null, 0 or false, wasNull telling it.
     */
    @Test
    void everyTypeRoundTrips() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect()) {
            LiveJdbc.run(c, TABLE);
            insert(c);
            try (Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery("SELECT * FROM JDBC_VALUES ORDER BY ID")) {
                assertTrue(rs.next());
                scalars(rs);
                objects(rs);
                assertTrue(rs.next());
                nulls(rs);
                assertFalse(rs.next());
            }
        }
    }

    /**
     * Verifies: [DESIGN] D2 a statement of no result has no metadata; a parameter with no value is
     * refused (SQLSTATE 07001), and one of no place (TB910); a prepared statement takes no SQL; a
     * query's rows are read by a cursor of its fetch size.
     */
    @Test
    void preparedStatementsRefuseMisuse() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect()) {
            LiveJdbc.run(c, "CREATE TABLE JDBC_MISUSE (ID INTEGER PRIMARY KEY)");
            try (PreparedStatement p = c.prepareStatement("DELETE FROM JDBC_MISUSE WHERE ID = ?")) {
                assertNull(p.getMetaData());
                assertEquals(
                        "07001",
                        assertThrows(SQLException.class, p::executeUpdate).getSQLState());
                p.setInt(1, 1);
                p.clearParameters();
                assertEquals(
                        "07001", assertThrows(SQLException.class, p::execute).getSQLState());
                assertEquals(
                        "TB910",
                        assertThrows(SQLException.class, () -> p.setInt(2, 1)).getSQLState());
                p.setInt(1, 1);
                assertEquals(0, p.executeLargeUpdate());
                assertEquals(
                        "TB910",
                        assertThrows(SQLException.class, p::executeQuery).getSQLState());
                assertEquals(
                        "TB910",
                        assertThrows(SQLException.class, () -> p.executeQuery("VALUES 1"))
                                .getSQLState());
                assertEquals(
                        "TB910",
                        assertThrows(SQLException.class, () -> p.executeUpdate("VALUES 1"))
                                .getSQLState());
                assertEquals(
                        "TB910",
                        assertThrows(SQLException.class, () -> p.executeLargeUpdate("VALUES 1"))
                                .getSQLState());
                assertEquals(
                        "TB910",
                        assertThrows(SQLException.class, () -> p.execute("VALUES 1"))
                                .getSQLState());
            }
            try (PreparedStatement p = c.prepareStatement("VALUES CAST(? AS INTEGER)")) {
                p.setInt(1, 1);
                assertEquals(
                        "TB910",
                        assertThrows(SQLException.class, p::executeUpdate).getSQLState());
                p.setFetchSize(1);
                try (ResultSet rs = p.executeQuery()) {
                    assertTrue(rs.next());
                }
            }
        }
    }

    /**
     * Verifies: [DESIGN] D2 each setter makes the value of its parameter's type: streams, readers,
     * Blobs and Clobs, a time of a calendar, an object of a target type; the setters of a REF, a
     * DATALINK and a row ID are refused.
     */
    @Test
    @SuppressWarnings("deprecation") // JDBC's deprecated setUnicodeStream, which a driver still gives.
    void everySetterSends() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect()) {
            LiveJdbc.run(
                    c,
                    "CREATE TABLE JDBC_SET (N VARCHAR(40), B BLOB(1K), C CLOB(1K), STAMP TIMESTAMP, D DATE, TM TIME)");
            try (PreparedStatement p = c.prepareStatement("INSERT INTO JDBC_SET VALUES (?, ?, ?, ?, ?, ?)")) {
                Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                p.setTimestamp(4, Timestamp.valueOf("2026-01-02 03:04:05"), utc);
                p.setDate(5, java.sql.Date.valueOf("2026-01-02"), utc);
                p.setTime(6, java.sql.Time.valueOf("03:04:05"), utc);
                p.setAsciiStream(1, new ByteArrayInputStream("ascii".getBytes(StandardCharsets.US_ASCII)), 5);
                p.setBlob(2, new ByteArrayInputStream(new byte[] {1}), 1);
                p.setClob(3, new StringReader("clob"), 4);
                assertEquals(1, p.executeUpdate());
                strings(p);
                objects(c, p);
                nulls(p, utc);
                assertEquals(
                        "0A000",
                        assertThrows(SQLException.class, () -> p.setRef(1, null))
                                .getSQLState());
                assertEquals(
                        "0A000",
                        assertThrows(SQLException.class, () -> p.setURL(1, null))
                                .getSQLState());
                assertEquals(
                        "0A000",
                        assertThrows(SQLException.class, () -> p.setRowId(1, null))
                                .getSQLState());
            }
        }
    }

    /** Sends a null by each setter that takes a null, and a datetime of no calendar given. */
    @SuppressWarnings("deprecation") // JDBC's deprecated setUnicodeStream, which a driver still gives.
    private static void nulls(PreparedStatement p, Calendar utc) throws SQLException {
        p.setTimestamp(4, null, utc);
        p.setDate(5, null, utc);
        p.setTime(6, null, null);
        assertEquals(1, p.executeUpdate());
        p.setTimestamp(4, Timestamp.valueOf("2026-01-02 03:04:05"), null);
        p.setDate(5, java.sql.Date.valueOf("2026-01-02"), null);
        p.setTime(6, java.sql.Time.valueOf("03:04:05"), null);
        p.setAsciiStream(1, null, 0L);
        p.setBinaryStream(2, null, 0L);
        p.setCharacterStream(3, null, 0L);
        assertEquals(1, p.executeUpdate());
        p.setUnicodeStream(1, null, 0);
        p.setBinaryStream(2, null, 0);
        p.setCharacterStream(3, null, 0);
        assertEquals(1, p.executeUpdate());
    }

    @SuppressWarnings("deprecation") // JDBC's deprecated setUnicodeStream, which a driver still gives.
    private static void strings(PreparedStatement p) throws SQLException {
        p.setAsciiStream(1, new ByteArrayInputStream("a2".getBytes(StandardCharsets.US_ASCII)));
        p.setAsciiStream(1, new ByteArrayInputStream("a3".getBytes(StandardCharsets.US_ASCII)), 2L);
        p.setUnicodeStream(1, new ByteArrayInputStream("u".getBytes(StandardCharsets.UTF_16BE)), 2);
        p.setNString(1, "n");
        p.setCharacterStream(1, new StringReader("cs"), 2);
        p.setCharacterStream(1, new StringReader("cs"), 2L);
        p.setCharacterStream(1, new StringReader("cs"));
        p.setNCharacterStream(1, new StringReader("ncs"), 3L);
        p.setNCharacterStream(1, new StringReader("ncs"));
        p.setObject(1, "o", Types.VARCHAR);
        p.setObject(1, "o", Types.VARCHAR, 0);
        p.setObject(1, "o", java.sql.JDBCType.VARCHAR);
        p.setObject(1, "o", java.sql.JDBCType.VARCHAR, 0);
        p.setNull(1, Types.VARCHAR, "VARCHAR");
    }

    private static void objects(Connection c, PreparedStatement p) throws SQLException {
        p.setBinaryStream(2, new ByteArrayInputStream(new byte[] {2}), 1);
        p.setBinaryStream(2, new ByteArrayInputStream(new byte[] {2}), 1L);
        p.setBlob(2, c.createBlob());
        p.setBlob(2, new ByteArrayInputStream(new byte[] {3}));
        p.setBlob(2, (InputStream) null);
        p.setClob(3, c.createClob());
        p.setClob(3, new StringReader("x"));
        p.setNClob(3, c.createNClob());
        p.setNClob(3, new StringReader("y"), 1);
        p.setNClob(3, new StringReader("z"));
        p.setClob(3, (Reader) null);
    }

    /**
     * Verifies: [DESIGN] D2 an array is sent as the parameter of an array type, its elements of the
     * element's, and read back as a java.sql.Array of the column's declared element type, its
     * elements whole, by range or as a result set of INDEX and VALUE.
     */
    @Test
    void arraysAreSentAndRead() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect()) {
            LiveJdbc.run(c, "CREATE TABLE JDBC_ARRAYS (A INTEGER ARRAY[3])");
            try (PreparedStatement p = c.prepareStatement("INSERT INTO JDBC_ARRAYS VALUES (?)")) {
                p.setArray(1, c.createArrayOf("INTEGER", new Object[] {1, 2, 3}));
                p.executeUpdate();
            }
            try (Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery("SELECT A FROM JDBC_ARRAYS")) {
                assertTrue(rs.next());
                Array a = rs.getArray(1);
                assertEquals(3, ((Object[]) a.getArray()).length);
                assertEquals(Types.INTEGER, a.getBaseType());
                assertEquals("INTEGER", a.getBaseTypeName());
                assertEquals(1, ((Object[]) a.getArray())[0]);
                try (ResultSet elements = a.getResultSet()) {
                    assertTrue(elements.next());
                    assertEquals(1, elements.getInt("INDEX"));
                    assertEquals(1, elements.getInt("VALUE"));
                }
                assertNotNull(a.getResultSet(2, 1));
                assertNotNull(a.getResultSet(java.util.Map.of()));
                assertNotNull(a.getResultSet(1, 1, java.util.Map.of()));
                assertInstanceOf(Array.class, rs.getObject(1));
            }
        }
    }

    /**
     * Verifies: [DESIGN] D2 a result set moves forward alone, its other moves and updates refused;
     * it says where it is; read off a row or past its columns, or as a type it is not, it is
     * refused; closed twice without a failure, closed it refuses work.
     */
    @Test
    void resultSetsMoveForward() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect();
                Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery(TWO_ROWS);
            assertTrue(rs.isBeforeFirst());
            assertEquals(0, rs.getRow());
            assertEquals(
                    "TB910",
                    assertThrows(SQLException.class, () -> rs.getInt(1)).getSQLState());
            assertTrue(rs.next());
            assertTrue(rs.isFirst());
            assertEquals(1, rs.getRow());
            assertEquals(
                    "TB910",
                    assertThrows(SQLException.class, () -> rs.getInt(9)).getSQLState());
            assertEquals(
                    "TB910",
                    assertThrows(SQLException.class, () -> rs.findColumn("NONE"))
                            .getSQLState());
            assertEquals(
                    "22018",
                    assertThrows(SQLException.class, () -> rs.getInt(2)).getSQLState());
            assertEquals(
                    "22018",
                    assertThrows(SQLException.class, () -> rs.getArray(2)).getSQLState());
            assertEquals(
                    "22018",
                    assertThrows(SQLException.class, () -> rs.getObject(2, UUID.class))
                            .getSQLState());
            assertEquals(
                    "TB910",
                    assertThrows(SQLException.class, () -> rs.getObject(1, (Class<?>) null))
                            .getSQLState());
            refused(rs);
            assertTrue(rs.next());
            assertEquals(3, rs.getObject(3, Integer.class));
            assertFalse(rs.next());
            assertTrue(rs.isAfterLast());
            assertFalse(rs.next());
            rs.close();
            rs.close();
            assertEquals("TB910", assertThrows(SQLException.class, rs::next).getSQLState());
        }
    }

    private static void refused(ResultSet rs) throws SQLException {
        assertEquals("TB910", assertThrows(SQLException.class, rs::previous).getSQLState());
        assertEquals(
                "TB910", assertThrows(SQLException.class, () -> rs.absolute(1)).getSQLState());
        assertEquals(
                "0A000",
                assertThrows(SQLException.class, () -> rs.updateInt(1, 2)).getSQLState());
        assertEquals("0A000", assertThrows(SQLException.class, rs::isLast).getSQLState());
        assertEquals(
                "0A000", assertThrows(SQLException.class, () -> rs.getRef(1)).getSQLState());
        assertEquals(
                "0A000", assertThrows(SQLException.class, () -> rs.getURL(1)).getSQLState());
        assertFalse(rs.rowUpdated() || rs.rowInserted() || rs.rowDeleted());
    }

    /**
     * Verifies: [DESIGN] D2 a value is read as each class getObject is asked for; the getters of
     * streams, large objects, XML and arrays give none of a null; a result set's fetch settings,
     * type, concurrency and holdability are kept and checked.
     */
    @Test
    @SuppressWarnings("deprecation") // JDBC's deprecated getters, which a driver still gives.
    void resultSetsReadAsAsked() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(TWO_ROWS)) {
            assertTrue(rs.next());
            assertEquals(1, rs.getObject(1, Integer.class));
            assertEquals(1L, rs.getObject(1, Long.class));
            assertEquals((short) 1, rs.getObject(1, Short.class));
            assertEquals((byte) 1, rs.getObject(1, Byte.class));
            assertEquals(1.0, rs.getObject(1, Double.class));
            assertEquals(1.0f, rs.getObject(1, Float.class));
            assertEquals(true, rs.getObject(1, Boolean.class));
            assertEquals(java.math.BigInteger.ONE, rs.getObject(1, java.math.BigInteger.class));
            assertEquals("a", rs.getObject(2, String.class));
            assertArrayEquals("a".getBytes(StandardCharsets.UTF_8), rs.getObject(2, byte[].class));
            assertEquals(new BigDecimal("1"), rs.getObject(1, BigDecimal.class));
            assertEquals(new BigDecimal("1"), rs.getObject(1, Object.class));
            assertNull(rs.getObject(3, java.util.Map.of()));
            assertEquals("a", rs.getNString(2));
            assertEquals('a', rs.getNCharacterStream(2).read());
            assertEquals('a', rs.getAsciiStream(2).read());
            assertNotNull(rs.getUnicodeStream(2));
            assertNull(rs.getUnicodeStream(3));
            assertNull(rs.getBigDecimal(3, 1));
            assertEquals(new BigDecimal("1.0"), rs.getBigDecimal(1, 1));
            assertNull(rs.getDate(3, null));
            assertNull(rs.getTime(3, null));
            assertNull(rs.getSQLXML(3));
            assertNull(rs.getArray(3));
            assertNull(rs.getCharacterStream(3));
            assertNull(rs.getBinaryStream(3));
            assertNull(rs.getNClob(3));
            assertNotNull(rs.getClob(2));
            assertEquals(1, rs.getBlob(2).length());
            settings(rs);
        }
    }

    private static void settings(ResultSet rs) throws SQLException {
        rs.setFetchSize(5);
        assertEquals(5, rs.getFetchSize());
        rs.setFetchDirection(ResultSet.FETCH_FORWARD);
        assertEquals(ResultSet.FETCH_FORWARD, rs.getFetchDirection());
        assertEquals(
                "TB910",
                assertThrows(SQLException.class, () -> rs.setFetchDirection(ResultSet.FETCH_REVERSE))
                        .getSQLState());
        assertEquals(
                "TB910",
                assertThrows(SQLException.class, () -> rs.setFetchSize(-1)).getSQLState());
        assertEquals(ResultSet.TYPE_FORWARD_ONLY, rs.getType());
        assertEquals(ResultSet.CONCUR_READ_ONLY, rs.getConcurrency());
        assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, rs.getHoldability());
        assertNull(rs.getWarnings());
        rs.clearWarnings();
        assertTrue(rs.isWrapperFor(ResultSet.class));
        assertEquals(rs, rs.unwrap(ResultSet.class));
        assertEquals(
                "TB910",
                assertThrows(SQLException.class, () -> rs.unwrap(String.class)).getSQLState());
    }

    /**
     * Verifies: [DESIGN] D2 every getter by label reads its column as the getter by index does, and
     * every update of a read-only result set is refused, each with the driver's failure.
     */
    @Test
    void everyGetterAndUpdateAnswers() throws SQLException, IOException, IllegalAccessException {
        try (Connection c = LiveJdbc.connect();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(TWO_ROWS)) {
            assertTrue(rs.next());
            for (Method m : ResultSet.class.getMethods()) {
                if (m.getName().startsWith("get")
                        && m.getParameterCount() >= 1
                        && m.getParameterTypes()[0] == String.class) {
                    @Nullable Object[] args = Reflection.arguments(m);
                    args[0] = "C2";
                    if (m.getParameterCount() == 2 && m.getParameterTypes()[1] == Class.class) {
                        args[1] = String.class;
                    }
                    Reflection.invoke(m, rs, args);
                }
            }
            for (Method m : ReadOnlyResultSet.class.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                    @Nullable Object[] args = Reflection.arguments(m);
                    if (args.length > 0 && m.getParameterTypes()[0] == String.class) {
                        args[0] = "C1";
                    }
                    Reflection.invoke(m, rs, args);
                }
            }
        }
    }

    /** Reads a Blob whole by its locator, and sends it and a Clob back by theirs. */
    private static void sentBack(Connection c, Blob blob, Clob clob) throws SQLException, IOException {
        assertEquals(BIG, blob.getBinaryStream().readAllBytes().length, "read by its locator");
        try (PreparedStatement again = c.prepareStatement("INSERT INTO JDBC_LOCATORS VALUES (?, ?)")) {
            again.setBlob(1, blob);
            again.setClob(2, clob);
            assertEquals(1, again.executeUpdate(), "sent back by their locators");
        }
    }

    /**
     * Verifies: [DESIGN] D2 a large object of a row is read through its locator by range, by
     * stream and by search, past its end giving what is there, whole by its stream; it is
     * changed by no setter (SQLSTATE 0A000), and sent back as a parameter by its locator; freed
     * it is refused.
     */
    @Test
    void largeObjectsByLocator() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect()) {
            LiveJdbc.run(c, "CREATE TABLE JDBC_LOCATORS (B BLOB(1M), C CLOB(1M))");
            try (PreparedStatement p = c.prepareStatement("INSERT INTO JDBC_LOCATORS VALUES (?, ?)")) {
                byte[] octets = new byte[BIG];
                octets[BIG - 1] = 7;
                p.setBytes(1, octets);
                p.setString(2, "x".repeat(BIG - 1) + "y");
                p.executeUpdate();
            }
            c.setAutoCommit(false);
            try (Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery("SELECT B, C FROM JDBC_LOCATORS")) {
                assertTrue(rs.next());
                assertInstanceOf(JdbcBlob.class, rs.getObject(1));
                assertInstanceOf(JdbcClob.class, rs.getObject(2));
                Blob blob = rs.getBlob(1);
                assertEquals(BIG, blob.position(new byte[] {7}, 1));
                assertArrayEquals(new byte[] {7}, blob.getBinaryStream(BIG, 1).readAllBytes());
                assertEquals(1, blob.getBytes(BIG, 10).length);
                assertEquals(
                        "0A000",
                        assertThrows(SQLException.class, () -> blob.setBytes(1, new byte[1]))
                                .getSQLState());
                assertEquals(
                        "0A000",
                        assertThrows(SQLException.class, () -> blob.truncate(1)).getSQLState());
                Clob clob = rs.getClob(2);
                assertEquals(BIG, clob.position("y", 1));
                assertEquals('x', clob.getAsciiStream().read());
                assertEquals("y", clob.getSubString(BIG, 10));
                assertEquals(
                        "0A000",
                        assertThrows(SQLException.class, () -> clob.setString(1, "z"))
                                .getSQLState());
                sentBack(c, blob, clob);
                blob.free();
                clob.free();
                assertEquals(
                        "TB910",
                        assertThrows(SQLException.class, blob::getBinaryStream).getSQLState());
                assertEquals(
                        "TB910",
                        assertThrows(SQLException.class, clob::getCharacterStream)
                                .getSQLState());
            }
            c.commit();
        }
    }
}
