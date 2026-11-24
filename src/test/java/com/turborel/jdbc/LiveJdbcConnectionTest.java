package com.turborel.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.testing.LiveServer;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** JDBC connections to a live server ([DESIGN] D2). */
@EnabledIfEnvironmentVariable(named = LiveServer.VARIABLE, matches = ".+")
class LiveJdbcConnectionTest {
    private static long count(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Verifies: [DESIGN] D2 the driver is found by ServiceLoader and by DriverManager for its URLs;
     * a data source connects by its URL and settings; a connection begins under autocommit, of the
     * session's schema and catalog, read committed; it is valid until closed, closed twice without
     * a failure, and closed it refuses work (SQLSTATE TB910).
     */
    @Test
    void theDriverIsFoundAndConnects() throws SQLException, IOException {
        LiveServer.get();
        assertTrue(ServiceLoader.load(java.sql.Driver.class).stream().anyMatch(p -> p.type() == Driver.class));
        assertInstanceOf(Driver.class, DriverManager.getDriver(LiveJdbc.URL));
        TurborelDataSource source = new TurborelDataSource();
        source.setUrl(LiveJdbc.URL);
        source.setUser("ann");
        TurborelDataSource secured = new TurborelDataSource();
        secured.setUrl(LiveJdbc.URL.replace("disable", "require"));
        try (Connection c = source.getConnection();
                Connection d = secured.getConnection("ann", LiveServer.PASSWORD)) {
            assertTrue(c.getAutoCommit());
            assertEquals("ANN", c.getSchema());
            assertEquals("TURBOREL", c.getCatalog());
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, c.getTransactionIsolation());
            assertTrue(c.isValid(0) && c.isValid(2));
            assertFalse(d.isClosed());
        }
        Properties info = new Properties();
        info.setProperty("user", "ann");
        Connection c = DriverManager.getConnection(LiveJdbc.URL, info);
        c.close();
        c.close();
        assertTrue(c.isClosed());
        assertFalse(c.isValid(1));
        assertEquals(
                "TB910", assertThrows(SQLException.class, c::createStatement).getSQLState());
        assertEquals(
                "TB910", assertThrows(SQLException.class, () -> c.isValid(-1)).getSQLState());
    }

    /**
     * Verifies: [DESIGN] D2 with autocommit off a transaction is committed or rolled back, and
     * turning autocommit on commits it; under autocommit commit and rollback are refused; a
     * savepoint, named or not, is rolled back to and released within a transaction, and refused
     * under autocommit or of another driver.
     */
    @Test
    void transactionsAndSavepoints() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect()) {
            LiveJdbc.run(c, "CREATE TABLE JDBC_TX (K INTEGER PRIMARY KEY)");
            assertEquals("TB910", assertThrows(SQLException.class, c::commit).getSQLState());
            assertEquals("TB910", assertThrows(SQLException.class, c::rollback).getSQLState());
            assertEquals(
                    "TB910", assertThrows(SQLException.class, c::setSavepoint).getSQLState());
            c.setAutoCommit(false);
            assertFalse(c.getAutoCommit());
            LiveJdbc.run(c, "INSERT INTO JDBC_TX VALUES (1)");
            c.rollback();
            assertEquals(0, count(c, "JDBC_TX"));
            LiveJdbc.run(c, "INSERT INTO JDBC_TX VALUES (1)");
            Savepoint unnamed = c.setSavepoint();
            LiveJdbc.run(c, "INSERT INTO JDBC_TX VALUES (2)");
            c.rollback(unnamed);
            Savepoint named = c.setSavepoint("keep it");
            LiveJdbc.run(c, "INSERT INTO JDBC_TX VALUES (3)");
            c.releaseSavepoint(named);
            assertEquals(
                    "TB910",
                    assertThrows(SQLException.class, () -> c.rollback((Savepoint) null))
                            .getSQLState());
            c.commit();
            assertEquals(2, count(c, "JDBC_TX"));
            LiveJdbc.run(c, "INSERT INTO JDBC_TX VALUES (4)");
            c.setAutoCommit(true);
            c.setAutoCommit(true);
            assertEquals(3, count(c, "JDBC_TX"));
        }
    }

    /**
     * Verifies: [DESIGN] D2 the isolation level is the session setting TRANSACTION.ISOLATION, each
     * of JDBC's four set and read back, another refused; read-only is a hint, and the session's;
     * the catalog is the one served, its setting passed over; the schema is set by SET SCHEMA;
     * holdability is CLOSE_CURSORS_AT_COMMIT alone; a type map is empty.
     */
    @Test
    void theSessionsSettings() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect()) {
            for (int level : new int[] {
                Connection.TRANSACTION_READ_UNCOMMITTED,
                Connection.TRANSACTION_REPEATABLE_READ,
                Connection.TRANSACTION_SERIALIZABLE,
                Connection.TRANSACTION_READ_COMMITTED
            }) {
                c.setTransactionIsolation(level);
                assertEquals(level, c.getTransactionIsolation());
            }
            assertEquals(
                    "TB910",
                    assertThrows(SQLException.class, () -> c.setTransactionIsolation(Connection.TRANSACTION_NONE))
                            .getSQLState());
            assertFalse(c.isReadOnly());
            c.setReadOnly(true);
            assertTrue(c.isReadOnly());
            c.setReadOnly(false);
            c.setCatalog("ELSEWHERE");
            assertEquals("TURBOREL", c.getCatalog());
            c.setSchema("ANN");
            assertEquals("ANN", c.getSchema());
            assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, c.getHoldability());
            c.setHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT);
            assertEquals(
                    "0A000",
                    assertThrows(SQLException.class, () -> c.setHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT))
                            .getSQLState());
            assertTrue(c.getTypeMap().isEmpty());
            c.setTypeMap(Map.of());
            c.setTypeMap(null);
            assertEquals(
                    "0A000",
                    assertThrows(SQLException.class, () -> c.setTypeMap(Map.of("T", String.class)))
                            .getSQLState());
            c.setNetworkTimeout(Runnable::run, 5000);
            assertEquals(5000, c.getNetworkTimeout());
            c.setNetworkTimeout(Runnable::run, 0);
            assertEquals(0, c.getNetworkTimeout());
            assertEquals(
                    "TB910",
                    assertThrows(SQLException.class, () -> c.setNetworkTimeout(Runnable::run, -1))
                            .getSQLState());
            assertEquals("VALUES DATE '2026-01-01'", c.nativeSQL("VALUES {d '2026-01-01'}"));
        }
    }

    /**
     * Verifies: [DESIGN] D2 a connection's extensions are reached by unwrapping it to
     * TurborelConnection: the session's status, the last commit's position and the capabilities
     * granted; client info is kept.
     */
    @Test
    void extensionsAndTheRest() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect()) {
            assertTrue(c.isWrapperFor(TurborelConnection.class));
            TurborelConnection t = c.unwrap(TurborelConnection.class);
            assertEquals("ANN", t.sessionStatus("AUTHORIZATION"));
            assertEquals("ANN", t.sessionStatus().get("SCHEMA"));
            assertTrue(t.granted().contains("ping"));
            LiveJdbc.run(c, "CREATE TABLE JDBC_POS (K INTEGER)", "INSERT INTO JDBC_POS VALUES (1)");
            assertNotNull(t.commitPosition());
            assertEquals(
                    "TB910",
                    assertThrows(SQLException.class, () -> c.unwrap(String.class))
                            .getSQLState());
            c.setClientInfo("ApplicationName", "tests");
            assertEquals("tests", c.getClientInfo("ApplicationName"));
            c.setClientInfo("ApplicationName", null);
            assertNull(c.getClientInfo("ApplicationName"));
            Properties info = new Properties();
            info.setProperty("ClientUser", "me");
            c.setClientInfo(info);
            assertEquals("me", c.getClientInfo().getProperty("ClientUser"));
            c.setClientInfo((Properties) null);
            assertTrue(c.getClientInfo().isEmpty());
        }
    }

    /**
     * Verifies: [DESIGN] D2 statements of another type or concurrency than forward-only, read-only
     * are refused, a Struct too; the objects a connection makes are made; abort closes it.
     */
    @Test
    void statementsObjectsAndAbort() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect()) {
            int forward = ResultSet.TYPE_FORWARD_ONLY;
            int readOnly = ResultSet.CONCUR_READ_ONLY;
            int scroll = ResultSet.TYPE_SCROLL_INSENSITIVE;
            int hold = ResultSet.CLOSE_CURSORS_AT_COMMIT;
            c.createStatement(forward, readOnly).close();
            c.createStatement(forward, readOnly, hold).close();
            c.prepareStatement("VALUES 1", forward, readOnly).close();
            c.prepareStatement("VALUES 1", forward, readOnly, hold).close();
            c.prepareStatement("VALUES 1", Statement.RETURN_GENERATED_KEYS).close();
            c.prepareStatement("VALUES 1", new int[] {1}).close();
            c.prepareStatement("VALUES 1", new String[] {"A"}).close();
            c.prepareCall("VALUES 1", forward, readOnly).close();
            c.prepareCall("VALUES 1", forward, readOnly, hold).close();
            assertEquals(
                    "0A000",
                    assertThrows(SQLException.class, () -> c.createStatement(scroll, readOnly))
                            .getSQLState());
            assertEquals(
                    "0A000",
                    assertThrows(
                                    SQLException.class,
                                    () -> c.prepareStatement("VALUES 1", forward, ResultSet.CONCUR_UPDATABLE))
                            .getSQLState());
            assertEquals(
                    "0A000",
                    assertThrows(SQLException.class, () -> c.createStruct("T", new Object[0]))
                            .getSQLState());
            assertNotNull(c.createClob());
            assertNotNull(c.createNClob());
            assertNotNull(c.createBlob());
            assertNotNull(c.createSQLXML());
            assertEquals("INTEGER", c.createArrayOf("INTEGER", new Object[] {1}).getBaseTypeName());
            assertNull(c.getWarnings());
            c.clearWarnings();
            assertEquals(
                    "TB910",
                    assertThrows(SQLException.class, () -> c.abort(null)).getSQLState());
            c.abort(Runnable::run);
            assertTrue(c.isClosed());
        }
    }

    /**
     * Verifies: [DESIGN] D2 a prepared statement is prepared as it is made: a statement the server
     * refuses fails there, the connection kept.
     */
    @Test
    void aStatementIsPreparedAsItIsMade() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect()) {
            SQLException refused = assertThrows(SQLException.class, () -> c.prepareStatement("SELEKT 1"));
            assertEquals("42000", refused.getSQLState());
            org.junit.jupiter.api.Assertions.assertInstanceOf(java.sql.SQLSyntaxErrorException.class, refused);
            try (PreparedStatement ok = c.prepareStatement("VALUES 1")) {
                assertNotNull(ok.getMetaData());
            }
        }
    }
}
