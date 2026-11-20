package com.turborel.jdbc.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.auth.Trust;
import com.turborel.jdbc.internal.codec.Column;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.internal.transport.Target;
import com.turborel.jdbc.internal.transport.Timeouts;
import com.turborel.jdbc.testing.LiveServer;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Statements run, prepared and read by cursor on a live server ([CLIENT Running statements]). */
@EnabledIfEnvironmentVariable(named = LiveServer.VARIABLE, matches = ".+")
class LiveStatementsTest {
    static Connection connected(Offer offer) throws TurborelException, IOException {
        LiveServer.get();
        Connection connection =
                Connect.to(new Target.Tcp(LiveServer.address()), "ann", new Trust(), Timeouts.DEFAULT, offer);
        LiveSessionTest.schema(connection);
        return connection;
    }

    static Connection connected() throws TurborelException, IOException {
        return connected(Offer.standard());
    }

    static Value integer(long n) {
        return new Value.Exact(BigInteger.valueOf(n), 0);
    }

    /** The rows of a query's outcome. */
    static List<List<Value>> rows(Outcome outcome) {
        return ((Outcome.Rows) outcome).rows();
    }

    static Outcome.Done done(Outcome outcome) {
        return (Outcome.Done) outcome;
    }

    /**
     * Verifies: [PROTOCOL Query 'Q'] a query is answered by Description, Rows and Complete, its
     * columns and rows given; another statement by Complete, its SQLCODE and SQLSTATE given; a
     * failure by Error, its SQLCODE, SQLSTATE, message, rule apart and position ([CLIENT Running
     * statements]).
     */
    @Test
    void aQueryGivesColumnsAndRows() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            Outcome.Done created = done(statements.query("CREATE TABLE STAFF (ID INTEGER, NAME VARCHAR(20))"));
            assertEquals(0, created.completed().sqlcode());
            assertEquals("00000", created.completed().sqlstate());
            statements.query("INSERT INTO STAFF VALUES (1, 'Ada'), (2, 'Grace')");
            Outcome.Rows rows = (Outcome.Rows) statements.query("SELECT ID, NAME FROM STAFF ORDER BY ID");
            assertEquals(
                    List.of("ID", "NAME"),
                    rows.columns().stream().map(Column::name).toList());
            assertEquals(
                    List.of(List.of(integer(1), new Value.Text("Ada")), List.of(integer(2), new Value.Text("Grace"))),
                    rows.rows());
            TurborelException failure =
                    assertThrows(TurborelException.class, () -> statements.query("SELECT * FROM NOPE"));
            assertEquals(-103, failure.sqlcode(), failure.getMessage());
            assertEquals("2A000", failure.getSQLState(), failure.getMessage());
            assertNotNull(failure.rule(), "the rule it cites, apart");
            assertTrue(failure.position().isPresent(), "its position in the statement");
            assertFalse(connection.isLost(), "a statement's failure keeps the connection");
            statements.query("ROLLBACK");
            connection.terminate();
        }
    }

    /**
     * Verifies: [PROTOCOL Query 'Q'] a query that returns no row completes with SQLCODE 100,
     * SQLSTATE 02000, its outcome no rows; [PROTOCOL Conversations] a statement's replies come in
     * their order, Rows before the Complete.
     */
    @Test
    void aQueryOfNoRowCompletesWith100() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            connection.statements().query("CREATE TABLE EMPTY_ONE (ID INTEGER)");
            List<com.turborel.jdbc.internal.codec.Reply> replies =
                    LiveSessionTest.run(connection, "SELECT ID FROM EMPTY_ONE", false);
            assertTrue(replies.get(0) instanceof com.turborel.jdbc.internal.codec.Reply.Description);
            com.turborel.jdbc.internal.codec.Reply.Complete last =
                    (com.turborel.jdbc.internal.codec.Reply.Complete) replies.get(replies.size() - 1);
            assertEquals(100, last.completed().sqlcode());
            assertEquals("02000", last.completed().sqlstate());
            assertTrue(rows(connection.statements().query("SELECT ID FROM EMPTY_ONE"))
                    .isEmpty());
            connection.terminate();
        }
    }

    /**
     * Verifies: [CLIENT Running statements] a transaction begins with the first statement and lasts
     * until COMMIT or ROLLBACK, the connection telling whether one is active as the server said;
     * under autocommit each statement is its own, by the request's {@code ends}, no COMMIT sent,
     * committed as it succeeds and rolled back as it fails ([PROTOCOL Query 'Q'], [PROTOCOL
     * Conversations]); a failing commit's Error comes in place of the Complete.
     */
    @Test
    void autocommitEndsEachStatement() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            statements.query("CREATE TABLE LEDGER (ID INTEGER PRIMARY KEY, P INTEGER, "
                    + "CONSTRAINT POSITIVE CHECK (P > 0) INITIALLY DEFERRED)");
            statements.query("COMMIT");
            assertFalse(connection.inTransaction());
            statements.query("INSERT INTO LEDGER VALUES (1, 1)");
            assertTrue(connection.inTransaction(), "begun by the first statement");
            statements.query("ROLLBACK");
            assertFalse(connection.inTransaction());
            connection.setAutocommit(true);
            assertTrue(connection.autocommit());
            statements.query("INSERT INTO LEDGER VALUES (2, 2)");
            assertFalse(connection.inTransaction(), "committed with the statement");
            assertThrows(TurborelException.class, () -> statements.query("INSERT INTO LEDGER VALUES (2, 3)"));
            assertFalse(connection.inTransaction(), "a failure rolls back");
            TurborelException commit =
                    assertThrows(TurborelException.class, () -> statements.query("INSERT INTO LEDGER VALUES (3, -1)"));
            assertEquals("40002", commit.getSQLState(), commit.getMessage());
            assertFalse(connection.inTransaction());
            connection.setAutocommit(false);
            assertEquals(List.of(List.of(integer(1))), rows(statements.query("SELECT COUNT(*) FROM LEDGER")));
            connection.terminate();
        }
    }

    /**
     * Verifies: [CLIENT Controlling the session] several statements as one request, each in order,
     * each ending its transaction under autocommit, each outcome given, the first failure stopping
     * the rest ([PROTOCOL Query 'Q']: tag 2, tag 5 on each Complete but the last); a text of no
     * statement gives one Complete.
     */
    @Test
    void severalStatementsAsOneRequest() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Controls controls = connection.controls();
            Ran ran = controls.queryMany(
                    "CREATE TABLE MANY (N INTEGER); INSERT INTO MANY VALUES (1), (2); SELECT N FROM MANY ORDER BY N");
            assertNull(ran.failure());
            assertEquals(3, ran.outcomes().size());
            assertEquals(
                    List.of(List.of(integer(1)), List.of(integer(2))),
                    rows(ran.outcomes().get(2)));
            Ran stopped =
                    controls.queryMany("INSERT INTO MANY VALUES (3); SELECT * FROM NOPE; INSERT INTO MANY VALUES (4)");
            assertEquals(1, stopped.outcomes().size());
            assertNotNull(stopped.failure());
            assertFalse(connection.isLost());
            Ran nothing = controls.queryMany("");
            assertEquals(1, nothing.outcomes().size());
            assertTrue(nothing.outcomes().get(0) instanceof Outcome.Done);
            connection.statements().query("ROLLBACK");
            connection.terminate();
        }
    }

    /**
     * Verifies: [CLIENT Prepared statements] a statement is prepared, its dynamic parameters written
     * {@code ?}, the type each takes and a query's columns given; executed with values as many times
     * as wanted, valid across transactions; deallocated, after which it is none (SQLSTATE 26000)
     * ([PROTOCOL Prepare 'P'], [PROTOCOL Execute 'E'], [PROTOCOL Deallocate 'D']).
     */
    @Test
    void aPreparedStatementRunsAgain() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            statements.query("CREATE TABLE ITEMS (ID INTEGER, NAME VARCHAR(10))");
            Statement insert = statements.prepare("INSERT INTO ITEMS VALUES (?, ?)");
            assertEquals(2, insert.parameters().size());
            assertTrue(insert.columns().isEmpty());
            for (int i = 1; i <= 3; i++) {
                Outcome.Done inserted = done(statements.execute(insert, List.of(integer(i), new Value.Text("n" + i))));
                assertEquals(0, inserted.completed().sqlcode());
                statements.query("COMMIT");
            }
            Statement select = statements.prepare("SELECT NAME FROM ITEMS WHERE ID > ? ORDER BY ID");
            assertEquals(
                    List.of("NAME"), select.columns().stream().map(Column::name).toList());
            assertEquals(List.of(List.of(new Value.Text("n3"))), rows(statements.execute(select, List.of(integer(2)))));
            TurborelException count =
                    assertThrows(TurborelException.class, () -> statements.execute(select, List.of()));
            assertEquals("07001", count.getSQLState(), count.getMessage());
            statements.deallocate(select);
            TurborelException gone =
                    assertThrows(TurborelException.class, () -> statements.execute(select, List.of(integer(0))));
            assertEquals("26000", gone.getSQLState(), gone.getMessage());
            statements.query("ROLLBACK");
            connection.terminate();
        }
    }

    /**
     * Verifies: [CLIENT Prepared statements] a cursor opened takes its first rows, fetches take the
     * next, a batch saying whether more follow, and close ends it ([PROTOCOL Execute 'E']: Rows
     * then Suspended; [PROTOCOL Fetch 'F'], [PROTOCOL Close 'C']); a cursor already open fails, as
     * one not open fetched or closed does (SQLSTATE 24000); open, it keeps its transaction, and a
     * statement of an open cursor is neither replaced nor deallocated (24000).
     */
    @Test
    void aCursorIsReadInBatches() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            statements.query("CREATE TABLE NUMBERS (N INTEGER)");
            statements.query("INSERT INTO NUMBERS VALUES (1), (2), (3), (4), (5)");
            statements.query("COMMIT");
            Statement select = statements.prepare("SELECT N FROM NUMBERS ORDER BY N");
            Batch first = statements.openCursor(select, List.of(), 2);
            assertEquals(List.of(List.of(integer(1)), List.of(integer(2))), first.rows());
            assertTrue(first.more());
            assertTrue(connection.inTransaction(), "an open cursor keeps its transaction");
            assertEquals(
                    "24000",
                    assertThrows(TurborelException.class, () -> statements.openCursor(select, List.of(), 1))
                            .getSQLState());
            assertEquals(
                    "24000",
                    assertThrows(TurborelException.class, () -> statements.deallocate(select))
                            .getSQLState());
            Batch next = statements.fetch(select, 2);
            assertEquals(2, next.rows().size());
            assertTrue(next.more());
            Batch rest = statements.fetch(select, 0);
            assertEquals(List.of(List.of(integer(5))), rest.rows());
            assertFalse(rest.more(), "the last batch closes the cursor");
            assertEquals(
                    "24000",
                    assertThrows(TurborelException.class, () -> statements.fetch(select, 1))
                            .getSQLState());
            statements.openCursor(select, List.of(), 1);
            statements.closeCursor(select);
            assertEquals(
                    "24000",
                    assertThrows(TurborelException.class, () -> statements.closeCursor(select))
                            .getSQLState());
            statements.query("ROLLBACK");
            connection.terminate();
        }
    }

    /**
     * Verifies: [PROTOCOL Prepared 'P'] a CALL's parameters are its inputs alone, its columns one
     * for each {@code ?} of an OUT or INOUT parameter, in the procedure's order, named as each, an
     * INOUT one both; [PROTOCOL Execute 'E'] executed, it sends one Rows reply of one row of those
     * parameters' values before its Complete, which is its outcome.
     */
    @Test
    void aCallGivesItsOutputParameters() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            statements.query("CREATE PROCEDURE SPLIT (IN A INTEGER, OUT HALF INTEGER, INOUT TWICE INTEGER) "
                    + "CONTAINS SQL BEGIN SET HALF = A / 2; SET TWICE = TWICE * 2; END");
            statements.query("COMMIT");
            Statement call = statements.prepare("CALL SPLIT(?, ?, ?)");
            assertEquals(2, call.parameters().size(), "A and TWICE, the inputs");
            assertEquals(
                    List.of("HALF", "TWICE"),
                    call.columns().stream().map(Column::name).toList());
            Outcome.Rows out = (Outcome.Rows) statements.execute(call, List.of(integer(9), integer(5)));
            assertEquals(List.of(List.of(integer(4), integer(10))), out.rows());
            statements.query("ROLLBACK");
            connection.terminate();
        }
    }

    /**
     * Verifies: [CLIENT Running statements] a query's rows are given a batch at a time, as they
     * come, to a sink, its columns first; another statement's completion is given as it is.
     */
    @Test
    void rowsGoToASinkAsTheyCome() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            Script.Recorded sink = new Script.Recorded();
            Streamed streamed = statements.queryInto(
                    "WITH RECURSIVE C(N) AS (VALUES CAST(1 AS INTEGER) UNION ALL SELECT N + 1 FROM C WHERE N < 5000) "
                            + "SELECT N FROM C",
                    sink);
            assertEquals(new Streamed.Rows(5000), streamed);
            assertEquals(1, sink.columns.size());
            assertEquals(5000, sink.rows());
            assertTrue(statements.queryInto("CREATE TABLE SUNK (N INTEGER)", sink) instanceof Streamed.Done);
            statements.query("ROLLBACK");
            connection.terminate();
        }
    }

    /**
     * Verifies: [PROTOCOL Query 'Q'] several statements as one Query under autocommit, {@code ends}
     * applying to each: each ends its own transaction, so the first two are kept though the third
     * fails, and none is active after.
     */
    @Test
    void endsAppliesToEachStatement() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            connection.setAutocommit(true);
            Ran ran = connection
                    .controls()
                    .queryMany(
                            "CREATE TABLE MANY_ENDS (N INTEGER); INSERT INTO MANY_ENDS VALUES (1); SELECT * FROM NOPE");
            assertNotNull(ran.failure());
            assertEquals(2, ran.outcomes().size());
            assertFalse(connection.inTransaction());
            assertEquals(
                    List.of(List.of(integer(1))), rows(connection.statements().query("SELECT N FROM MANY_ENDS")));
        }
    }

    /**
     * Verifies: [PROTOCOL Prepare 'P'] a Prepare of a number a statement has replaces that one
     * ([PROTOCOL Deallocate 'D']); a Deallocate of a number no statement has fails (SQLSTATE
     * 26000), the connection kept.
     */
    @Test
    void aNumberIsReplacedOrNone() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            Statement one = statements.prepare("VALUES 1");
            connection.send(new com.turborel.jdbc.internal.codec.Request.Prepare(one.number(), "VALUES 2"));
            assertTrue(connection.receive() instanceof com.turborel.jdbc.internal.codec.Reply.Prepared);
            assertEquals(List.of(List.of(integer(2))), rows(statements.execute(one, List.of())), "replaced");
            Statement none = new Statement(4_000_000, List.of(), List.of());
            assertEquals(
                    "26000",
                    assertThrows(TurborelException.class, () -> statements.deallocate(none))
                            .getSQLState());
            assertFalse(connection.isLost());
            statements.query("ROLLBACK");
        }
    }
}
