package com.turborel.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.testing.LiveServer;
import java.io.IOException;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** JDBC statements on a live server ([DESIGN] D2). */
@EnabledIfEnvironmentVariable(named = LiveServer.VARIABLE, matches = ".+")
class LiveJdbcStatementTest {
    /** A query that runs long enough to be cancelled, over a base table's rows. */
    private static final String LONG = "SELECT COUNT(*) FROM JDBC_LONG A, JDBC_LONG B, JDBC_LONG C, JDBC_LONG D";

    /**
     * Verifies: [DESIGN] D2 a statement runs a query, giving a result set, or another statement,
     * giving the rows it changed, 0 of a definition; the identity values an INSERT generated are
     * its generated keys; executeQuery of no query and executeUpdate of a query are refused
     * (SQLSTATE TB910); getMoreResults ends the result.
     */
    @Test
    void statementsGiveResultsOrCounts() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect();
                Statement s = c.createStatement()) {
            assertFalse(s.execute(
                    "CREATE TABLE JDBC_ST (ID INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY, N VARCHAR(10))"));
            assertEquals(0, s.getUpdateCount());
            assertNull(s.getResultSet());
            assertEquals(1, s.executeUpdate("INSERT INTO JDBC_ST (N) VALUES ('a')", Statement.RETURN_GENERATED_KEYS));
            try (ResultSet keys = s.getGeneratedKeys()) {
                assertTrue(keys.next());
                assertEquals(1, keys.getLong(1));
                assertEquals("1", keys.getString(JdbcStatement.GENERATED));
            }
            assertEquals(2, s.executeLargeUpdate("INSERT INTO JDBC_ST (N) VALUES ('b'), ('c')"));
            assertEquals(1, s.executeUpdate("UPDATE JDBC_ST SET N = 'z' WHERE ID = 1", new int[] {1}));
            assertEquals(1, s.executeUpdate("UPDATE JDBC_ST SET N = 'z' WHERE ID = 1", new String[] {"ID"}));
            assertEquals(
                    1, s.executeLargeUpdate("UPDATE JDBC_ST SET N = 'z' WHERE ID = 1", Statement.NO_GENERATED_KEYS));
            assertEquals(1, s.executeLargeUpdate("UPDATE JDBC_ST SET N = 'z' WHERE ID = 1", new int[] {1}));
            assertEquals(1, s.executeLargeUpdate("UPDATE JDBC_ST SET N = 'z' WHERE ID = 1", new String[] {"ID"}));
            assertFalse(s.execute("UPDATE JDBC_ST SET N = 'z' WHERE ID = 1", Statement.RETURN_GENERATED_KEYS));
            assertFalse(s.execute("UPDATE JDBC_ST SET N = 'z' WHERE ID = 1", new int[] {1}));
            assertFalse(s.execute("UPDATE JDBC_ST SET N = 'z' WHERE ID = 1", new String[] {"ID"}));
            assertTrue(s.execute("SELECT ID FROM JDBC_ST ORDER BY ID"));
            assertEquals(-1, s.getUpdateCount());
            ResultSet rs = s.getResultSet();
            assertSame(s, rs.getStatement());
            assertFalse(s.getMoreResults());
            assertTrue(rs.isClosed());
            assertEquals(-1, s.getLargeUpdateCount());
            assertFalse(s.getMoreResults(Statement.KEEP_CURRENT_RESULT));
            assertEquals(
                    "TB910",
                    assertThrows(SQLException.class, () -> s.executeQuery("DELETE FROM JDBC_ST WHERE 1 = 0"))
                            .getSQLState());
            assertEquals(
                    "TB910",
                    assertThrows(SQLException.class, () -> s.executeUpdate("VALUES 1"))
                            .getSQLState());
            assertEquals(
                    "TB910",
                    assertThrows(SQLException.class, () -> s.executeUpdate("VALUES 1", 1))
                            .getSQLState());
        }
    }

    /**
     * Verifies: [DESIGN] D2 with a fetch size a query's rows are read by a cursor that many at a
     * time; the most rows a statement takes are its max rows; a result set closed under
     * closeOnCompletion closes its statement; the escapes are translated unless escape processing
     * is off.
     */
    @Test
    void fetchSizeMaxRowsAndEscapes() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect();
                Statement s = c.createStatement()) {
            LiveJdbc.run(
                    c,
                    "CREATE TABLE JDBC_ROWS (K INTEGER)",
                    "INSERT INTO JDBC_ROWS VALUES (1), (2), (3), (4), (5), (6), (7)");
            s.setFetchSize(2);
            assertEquals(2, s.getFetchSize());
            int read = 0;
            try (ResultSet rs = s.executeQuery("SELECT K FROM JDBC_ROWS ORDER BY K")) {
                while (rs.next()) {
                    read++;
                }
            }
            assertEquals(7, read);
            try (ResultSet rs = s.executeQuery("SELECT K FROM JDBC_ROWS ORDER BY K")) {
                assertTrue(rs.next());
            }
            assertEquals(0, s.executeUpdate("DELETE FROM JDBC_ROWS WHERE K > 100"));
            s.setFetchSize(0);
            s.setMaxRows(3);
            assertEquals(3, s.getMaxRows());
            try (ResultSet rs = s.executeQuery("SELECT K FROM JDBC_ROWS")) {
                int n = 0;
                while (rs.next()) {
                    n++;
                }
                assertEquals(3, n);
                assertFalse(rs.next());
            }
            s.setLargeMaxRows(0);
            assertEquals(0, s.getLargeMaxRows());
            try (ResultSet rs = s.executeQuery("VALUES {d '2026-01-02'}")) {
                rs.next();
                assertEquals(java.sql.Date.valueOf("2026-01-02"), rs.getDate(1));
            }
            s.setEscapeProcessing(false);
            assertEquals(
                    "2A000",
                    assertThrows(SQLException.class, () -> s.executeQuery("VALUES {d '2026-01-02'}"))
                            .getSQLState());
            Statement once = c.createStatement();
            once.closeOnCompletion();
            assertTrue(once.isCloseOnCompletion());
            once.executeQuery("VALUES 1").close();
            assertTrue(once.isClosed());
        }
    }

    /**
     * Verifies: [DESIGN] D2 a batch is one pipeline, each statement's count in order; a batch of a
     * failure fails with a BatchUpdateException of every count, EXECUTE_FAILED at the failure, its
     * SQLSTATE the failure's, the rest run; a statement of a batch that gives a result set fails it
     * too; a prepared statement's batch executes it with each set of values.
     */
    @Test
    void batchesArePipelines() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect();
                Statement s = c.createStatement()) {
            LiveJdbc.run(c, "CREATE TABLE JDBC_BATCH (K INTEGER PRIMARY KEY)");
            s.addBatch("INSERT INTO JDBC_BATCH VALUES (1)");
            s.addBatch("INSERT INTO JDBC_BATCH VALUES (2), (3)");
            assertArrayEquals(new int[] {1, 2}, s.executeBatch());
            s.addBatch("INSERT INTO JDBC_BATCH VALUES (4)");
            s.addBatch("INSERT INTO JDBC_BATCH VALUES (1)");
            s.addBatch("INSERT INTO JDBC_BATCH VALUES (5)");
            BatchUpdateException failed = assertThrows(BatchUpdateException.class, s::executeBatch);
            assertArrayEquals(new long[] {1, Statement.EXECUTE_FAILED, 1}, failed.getLargeUpdateCounts());
            assertEquals("23000", failed.getSQLState().substring(0, 2) + "000");
            assertInstanceOf(TurborelException.class, failed.getCause());
            s.addBatch("VALUES 1");
            BatchUpdateException query = assertThrows(BatchUpdateException.class, s::executeLargeBatch);
            assertEquals("TB910", query.getSQLState());
            s.addBatch("INSERT INTO JDBC_BATCH VALUES (9)");
            s.clearBatch();
            assertEquals(0, s.executeBatch().length);
            try (PreparedStatement p = c.prepareStatement("INSERT INTO JDBC_BATCH VALUES (?)")) {
                for (int k = 10; k < 13; k++) {
                    p.setInt(1, k);
                    p.addBatch();
                }
                assertArrayEquals(new int[] {1, 1, 1}, p.executeBatch());
                p.setInt(1, 20);
                p.addBatch();
                p.clearBatch();
                assertEquals(0, p.executeLargeBatch().length);
                assertEquals(
                        "TB910",
                        assertThrows(SQLException.class, () -> p.addBatch("VALUES 1"))
                                .getSQLState());
            }
        }
    }

    /**
     * Verifies: [DESIGN] D2 a statement past its query timeout is cancelled by Cancel, and so is one
     * cancelled from another thread; the connection goes on.
     */
    @Test
    void timeoutsAndCancelSendCancel() throws SQLException, IOException, InterruptedException {
        try (Connection c = LiveJdbc.connect();
                Statement s = c.createStatement()) {
            LiveJdbc.run(c, "CREATE TABLE JDBC_LONG (K INTEGER)");
            StringBuilder values = new StringBuilder("INSERT INTO JDBC_LONG VALUES (0)");
            for (int i = 1; i < 300; i++) {
                values.append(", (").append(i).append(')');
            }
            LiveJdbc.run(c, values.toString());
            s.setQueryTimeout(1);
            assertEquals(1, s.getQueryTimeout());
            SQLException timedOut = assertThrows(SQLException.class, () -> s.executeQuery(LONG));
            assertNotNull(timedOut.getSQLState());
            s.setQueryTimeout(0);
            Thread canceller = new Thread(() -> {
                try {
                    Thread.sleep(500);
                    s.cancel();
                } catch (InterruptedException | SQLException e) {
                    Thread.currentThread().interrupt();
                }
            });
            canceller.start();
            assertThrows(SQLException.class, () -> s.executeQuery(LONG));
            canceller.join();
            try (ResultSet rs = s.executeQuery("VALUES 1")) {
                assertTrue(rs.next());
            }
        }
    }

    /**
     * Verifies: [DESIGN] D2 a statement's settings are kept and checked: field size, fetch
     * direction, poolable, cursor name refused; its warnings; the type, concurrency and
     * holdability of its result sets; closed, it refuses work.
     */
    @Test
    void statementSettings() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect()) {
            Statement s = c.createStatement();
            s.setMaxFieldSize(10);
            assertEquals(10, s.getMaxFieldSize());
            assertEquals(
                    "TB910",
                    assertThrows(SQLException.class, () -> s.setMaxFieldSize(-1))
                            .getSQLState());
            assertEquals(
                    "TB910",
                    assertThrows(SQLException.class, () -> s.setMaxRows(-1)).getSQLState());
            assertEquals(
                    "TB910",
                    assertThrows(SQLException.class, () -> s.setFetchSize(-1)).getSQLState());
            assertEquals(
                    "TB910",
                    assertThrows(SQLException.class, () -> s.setQueryTimeout(-1))
                            .getSQLState());
            s.setFetchDirection(ResultSet.FETCH_FORWARD);
            assertEquals(ResultSet.FETCH_FORWARD, s.getFetchDirection());
            assertEquals(
                    "TB910",
                    assertThrows(SQLException.class, () -> s.setFetchDirection(ResultSet.FETCH_REVERSE))
                            .getSQLState());
            s.setPoolable(true);
            assertTrue(s.isPoolable());
            assertEquals(
                    "0A000",
                    assertThrows(SQLException.class, () -> s.setCursorName("C")).getSQLState());
            assertNull(s.getWarnings());
            s.clearWarnings();
            assertEquals(ResultSet.TYPE_FORWARD_ONLY, s.getResultSetType());
            assertEquals(ResultSet.CONCUR_READ_ONLY, s.getResultSetConcurrency());
            assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, s.getResultSetHoldability());
            assertSame(c, s.getConnection());
            assertTrue(s.isWrapperFor(Statement.class));
            assertSame(s, s.unwrap(Statement.class));
            ResultSet open = s.executeQuery("VALUES 1");
            s.close();
            s.close();
            assertTrue(open.isClosed());
            assertEquals(
                    "TB910",
                    assertThrows(SQLException.class, () -> s.execute("VALUES 1"))
                            .getSQLState());
        }
    }

    /**
     * Verifies: [DESIGN] D2 a prepared statement of no columns runs as any other, its fetch size
     * set or not; a parameter of place 0 is refused; closing it twice closes it once, and closing
     * it on a closed connection deallocates nothing; closing a connection closes its statements and
     * their result sets.
     */
    @Test
    void preparedStatementsCloseOnce() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect()) {
            LiveJdbc.run(c, "CREATE TABLE JDBC_ONCE (ID INTEGER)");
            try (PreparedStatement p = c.prepareStatement("DELETE FROM JDBC_ONCE WHERE ID = ?")) {
                p.setFetchSize(5);
                assertEquals(
                        "TB910",
                        assertThrows(SQLException.class, () -> p.setInt(0, 1)).getSQLState());
                p.setInt(1, 1);
                assertEquals(0, p.executeUpdate());
                closeAll(p);
                assertTrue(p.isClosed());
            }
            try (Statement other = c.createStatement();
                    PreparedStatement q = c.prepareStatement("VALUES 1")) {
                ResultSet rs = other.executeQuery("VALUES 1");
                closeAll(c, q);
                assertTrue(q.isClosed() && other.isClosed() && rs.isClosed(), "closed with their connection");
            }
        }
    }

    /** Closes {@code s} before its try closes it again. */
    private static void closeAll(Statement s) throws SQLException {
        s.close();
    }

    /** Closes {@code c}, then {@code s}, before their tries close them again. */
    private static void closeAll(Connection c, Statement s) throws SQLException {
        c.close();
        s.close();
    }

    /**
     * Verifies: [DESIGN] D2 a statement's most field size cuts a character string read to that many
     * octets; 0 reads it whole.
     */
    @Test
    void theMostFieldSizeCuts() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect();
                Statement s = c.createStatement()) {
            s.setMaxFieldSize(2);
            assertEquals(2, s.getMaxFieldSize());
            try (ResultSet rs = s.executeQuery("VALUES 'abc'")) {
                assertTrue(rs.next());
                assertEquals("ab", rs.getString(1));
            }
            s.setMaxFieldSize(0);
            try (ResultSet rs = s.executeQuery("VALUES 'abc'")) {
                assertTrue(rs.next());
                assertEquals("abc", rs.getString(1));
            }
        }
    }

    /**
     * Verifies: [DESIGN] D9 a failure through JDBC is a TurborelException, its vendor code its
     * SQLCODE: the server's, and the driver's own.
     */
    @Test
    void failuresCarryTheirSqlcode() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect();
                Statement s = c.createStatement()) {
            SQLException server = assertThrows(SQLException.class, () -> s.executeQuery("SELECT * FROM NO_SUCH_TABLE"));
            TurborelException told = assertInstanceOf(TurborelException.class, server);
            assertEquals(told.sqlcode(), told.getErrorCode());
            assertTrue(told.getErrorCode() < 0);
            SQLException own = assertThrows(SQLException.class, () -> s.setFetchSize(-1));
            assertEquals(-910, assertInstanceOf(TurborelException.class, own).getErrorCode());
        }
    }
}
