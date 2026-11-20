package com.turborel.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.Properties;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import org.junit.jupiter.api.Test;

/** The JDBC objects a program makes, held in memory ([DESIGN] D2). */
class ObjectsTest {
    /**
     * Verifies: [DESIGN] D2 a Blob the program makes is written by octets and streams, read by
     * range and stream, searched, truncated; a freed one is the program's misuse (SQLSTATE TB910).
     */
    @Test
    void aBlobIsHeldInMemory() throws SQLException, IOException {
        JdbcBlob blob = new JdbcBlob(new byte[] {1, 2, 3});
        assertEquals(3, blob.length());
        assertArrayEquals(new byte[] {2, 3}, blob.getBytes(2, 5));
        assertEquals(2, blob.setBytes(4, new byte[] {4, 5}));
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, blob.getBinaryStream().readAllBytes());
        assertArrayEquals(new byte[] {3, 4}, blob.getBinaryStream(3, 2).readAllBytes());
        assertEquals(3, blob.position(new byte[] {3, 4}, 1));
        assertEquals(-1, blob.position(new byte[] {9}, 1));
        assertEquals(4, blob.position(new JdbcBlob(new byte[] {4}), 2));
        try (OutputStream out = blob.setBinaryStream(6)) {
            out.write(new byte[] {6, 7});
        }
        assertEquals(7, blob.length());
        blob.truncate(2);
        assertArrayEquals(new byte[] {1, 2}, blob.getBytes(1, 10));
        assertEquals(
                "TB910",
                assertThrows(SQLException.class, () -> blob.getBytes(0, 1)).getSQLState());
        assertEquals(
                "TB910",
                assertThrows(SQLException.class, () -> blob.setBytes(9, new byte[1]))
                        .getSQLState());
        assertThrows(IOException.class, () -> {
            try (OutputStream out = blob.setBinaryStream(9)) {
                out.write(1);
            }
        });
        blob.free();
        assertEquals("TB910", assertThrows(SQLException.class, blob::length).getSQLState());
    }

    /**
     * Verifies: [DESIGN] D2 a Clob the program makes is written by strings, streams and writers,
     * read by range and stream, its characters counted by code point, searched, truncated; a
     * freed one is the program's misuse.
     */
    @Test
    void aClobIsHeldInMemory() throws SQLException, IOException {
        JdbcClob clob = new JdbcClob("a😀b");
        assertEquals(3, clob.length());
        assertEquals("😀b", clob.getSubString(2, 5));
        assertEquals(3, clob.position("b", 1));
        assertEquals(-1, clob.position("z", 1));
        assertEquals(1, clob.position(new JdbcClob("a"), 1));
        clob.setString(4, "cd");
        assertEquals("a😀bcd", clob.getSubString(1, 10));
        clob.setString(1, "xyz", 1, 1);
        assertEquals("y😀bcd", clob.getSubString(1, 10));
        try (Reader in = clob.getCharacterStream()) {
            assertEquals('y', in.read());
        }
        try (Reader in = clob.getCharacterStream(2, 1)) {
            assertEquals(0xD83D, in.read());
        }
        try (InputStream in = clob.getAsciiStream()) {
            assertEquals('y', in.read());
        }
        try (Writer out = clob.setCharacterStream(6)) {
            out.write("e");
        }
        try (OutputStream out = clob.setAsciiStream(7)) {
            out.write("f".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("y😀bcdef", clob.getSubString(1, 10));
        clob.truncate(2);
        assertEquals("y😀", clob.getSubString(1, 10));
        assertEquals(
                "TB910",
                assertThrows(SQLException.class, () -> clob.getSubString(0, 1)).getSQLState());
        assertEquals(
                "TB910",
                assertThrows(SQLException.class, () -> clob.setString(9, "x")).getSQLState());
        assertThrows(IOException.class, () -> {
            try (Writer out = clob.setCharacterStream(9)) {
                out.write("x");
            }
        });
        assertThrows(IOException.class, () -> {
            try (OutputStream out = clob.setAsciiStream(9)) {
                out.write(1);
            }
        });
        clob.free();
        assertEquals("TB910", assertThrows(SQLException.class, clob::length).getSQLState());
    }

    /**
     * Verifies: [DESIGN] D2 an SQLXML is written once, as text, a stream or a result, then read as
     * text, a stream, a reader or a source of a DOM; read before it is written, written twice, of a
     * source or result of another kind, or freed, it is refused.
     */
    @Test
    void anSqlxmlIsWrittenOnceThenRead() throws SQLException, IOException {
        JdbcSQLXML written = new JdbcSQLXML();
        assertEquals(
                "TB910", assertThrows(SQLException.class, written::getString).getSQLState());
        try (Writer out = written.setCharacterStream()) {
            out.write("<a>1</a>");
        }
        assertEquals(
                "TB910",
                assertThrows(SQLException.class, () -> written.setString("x")).getSQLState());
        assertEquals("<a>1</a>", written.getString());
        assertEquals('<', written.getBinaryStream().read());
        assertEquals('<', written.getCharacterStream().read());
        assertInstanceOf(StreamSource.class, written.getSource(null));
        assertInstanceOf(StreamSource.class, written.getSource(StreamSource.class));
        DOMSource dom = written.getSource(DOMSource.class);
        assertEquals("a", dom.getNode().getFirstChild().getNodeName());
        assertEquals(
                "0A000",
                assertThrows(SQLException.class, () -> written.getSource(javax.xml.transform.sax.SAXSource.class))
                        .getSQLState());
        JdbcSQLXML streamed = new JdbcSQLXML();
        try (OutputStream out = streamed.setBinaryStream()) {
            out.write("<b/>".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("<b/>", streamed.getString());
        JdbcSQLXML result = new JdbcSQLXML();
        result.setResult(StreamResult.class).getWriter().write("<c/>");
        assertEquals("<c/>", result.getString());
        assertInstanceOf(StreamResult.class, new JdbcSQLXML().setResult(null));
        assertEquals(
                "0A000",
                assertThrows(
                                SQLException.class,
                                () -> new JdbcSQLXML().setResult(javax.xml.transform.dom.DOMResult.class))
                        .getSQLState());
        result.free();
        assertEquals(
                "TB910", assertThrows(SQLException.class, result::getString).getSQLState());
    }

    /**
     * Verifies: [DESIGN] D2 an array the program makes gives its elements whole or by range, its
     * base type by name; it has no result set; a freed one is refused. A savepoint is named by
     * the program, its name given and no ID, or by the driver, its ID given and no name.
     */
    @Test
    void arraysAndSavepoints() throws SQLException {
        JdbcArray array = new JdbcArray("INTEGER", new Object[] {1, 2, 3});
        assertEquals("INTEGER", array.getBaseTypeName());
        assertEquals(Types.INTEGER, array.getBaseType());
        assertArrayEquals(new Object[] {1, 2, 3}, (Object[]) array.getArray());
        assertArrayEquals(new Object[] {2, 3}, (Object[]) array.getArray(2, 5));
        assertArrayEquals(new Object[] {1, 2, 3}, (Object[]) array.getArray(java.util.Map.of()));
        assertArrayEquals(new Object[] {1}, (Object[]) array.getArray(1, 1, null));
        assertArrayEquals(new Object[] {1, 2, 3}, array.items());
        assertEquals(
                "0A000", assertThrows(SQLException.class, array::getResultSet).getSQLState());
        assertEquals(
                "0A000",
                assertThrows(SQLException.class, () -> array.getResultSet(java.util.Map.of()))
                        .getSQLState());
        assertEquals(
                "0A000",
                assertThrows(SQLException.class, () -> array.getResultSet(1, 1, null))
                        .getSQLState());
        array.free();
        assertEquals(
                "TB910",
                assertThrows(SQLException.class, array::getBaseTypeName).getSQLState());
        JdbcSavepoint named = new JdbcSavepoint(1, "it's \"x\"");
        assertEquals("it's \"x\"", named.getSavepointName());
        assertEquals("\"it's \"\"x\"\"\"", named.sqlName());
        assertEquals(
                "TB910", assertThrows(SQLException.class, named::getSavepointId).getSQLState());
        JdbcSavepoint unnamed = new JdbcSavepoint(2, null);
        assertEquals(2, unnamed.getSavepointId());
        assertEquals("JDBC_SAVEPOINT_2", unnamed.sqlName());
        assertEquals(
                "TB910",
                assertThrows(SQLException.class, unnamed::getSavepointName).getSQLState());
    }

    /**
     * Verifies: [DESIGN] D2 the driver accepts URLs of {@code jdbc:turborel:} alone, giving no
     * connection of another; lists every connection setting as a property, the user required; is
     * version 0.0, not JDBC compliant, and logs nothing; a URL of a setting not in the table is
     * refused (SQLSTATE 08001); a property names its setting in any case and one of another name is
     * passed over.
     */
    @Test
    void theDriverTakesItsUrls() throws SQLException {
        Driver driver = new Driver();
        assertTrue(driver.acceptsURL("jdbc:turborel://ann@db"));
        assertFalse(driver.acceptsURL("jdbc:postgresql://db"));
        assertFalse(driver.acceptsURL(null));
        assertNull(driver.connect("jdbc:postgresql://db", new Properties()));
        Properties given = new Properties();
        given.setProperty("tls_mode", "verify-full");
        DriverPropertyInfo[] info = driver.getPropertyInfo("jdbc:turborel://", given);
        assertEquals(com.turborel.jdbc.internal.settings.Options.KEYS.size(), info.length);
        assertTrue(java.util.Arrays.stream(info).anyMatch(p -> p.name.equals("user") && p.required));
        assertTrue(java.util.Arrays.stream(info).anyMatch(p -> "verify-full".equals(p.value)));
        assertEquals(info.length, driver.getPropertyInfo("jdbc:turborel://", null).length);
        assertEquals(0, driver.getMajorVersion() + driver.getMinorVersion());
        assertFalse(driver.jdbcCompliant());
        assertThrows(SQLFeatureNotSupportedException.class, driver::getParentLogger);
        assertEquals(
                "08001",
                assertThrows(SQLException.class, () -> driver.connect("jdbc:turborel://a@b/?colour=red", null))
                        .getSQLState());
        Properties props = new Properties();
        props.setProperty("USER", "bob");
        props.setProperty("Password", "pw");
        props.setProperty("ApplicationName", "passed over");
        com.turborel.jdbc.internal.settings.Settings settings = Driver.settings("jdbc:turborel://ann@db1/", props);
        assertEquals("bob", settings.user());
        assertEquals("pw", settings.password());
        assertEquals("ann", Driver.settings("jdbc:turborel://ann@db1/", null).user());
    }

    /**
     * Verifies: [DESIGN] D2 a data source keeps its URL and the settings set beside it, its login
     * timeout the setting connect_timeout, and logs nothing; a URL not of the driver is refused.
     */
    @Test
    void aDataSourceKeepsItsSettings() throws SQLException {
        TurborelDataSource source = new TurborelDataSource();
        assertEquals("jdbc:turborel://", source.getUrl());
        source.setUrl("jdbc:other://");
        source.setUser("ann");
        source.setPassword("pw");
        source.setSetting("tls_mode", "disable");
        source.setLoginTimeout(5);
        assertEquals("ann", source.getUser());
        assertEquals(5, source.getLoginTimeout());
        assertNull(source.getLogWriter());
        java.io.PrintWriter log = new java.io.PrintWriter(java.io.Writer.nullWriter());
        source.setLogWriter(log);
        assertEquals(log, source.getLogWriter());
        assertEquals(
                "TB910", assertThrows(SQLException.class, source::getConnection).getSQLState());
        assertEquals(
                "TB910",
                assertThrows(SQLException.class, () -> source.getConnection("a", "b"))
                        .getSQLState());
        assertThrows(SQLFeatureNotSupportedException.class, source::getParentLogger);
        assertTrue(source.isWrapperFor(javax.sql.DataSource.class));
        assertEquals(source, source.unwrap(TurborelDataSource.class));
        assertEquals(
                "TB910",
                assertThrows(SQLException.class, () -> source.unwrap(String.class))
                        .getSQLState());
        assertEquals(0, new TurborelDataSource().getLoginTimeout());
    }
}
