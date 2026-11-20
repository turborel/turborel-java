package com.turborel.jdbc.internal.session;

import static com.turborel.jdbc.internal.session.LiveStatementsTest.connected;
import static com.turborel.jdbc.internal.session.LiveStatementsTest.integer;
import static com.turborel.jdbc.internal.session.LiveStatementsTest.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Lob;
import com.turborel.jdbc.internal.codec.Reply;
import com.turborel.jdbc.internal.codec.Timing;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.testing.LiveServer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** What a live server tells beside a statement's replies ([CLIENT What the server tells]). */
@EnabledIfEnvironmentVariable(named = LiveServer.VARIABLE, matches = ".+")
class LiveToldTest {
    /**
     * Verifies: [PROTOCOL Complete 'K'] a Complete tells the rows a statement changed, or a query
     * returned in all (tag 6), each identity value an INSERT generated in the order of its rows (tag
     * 16), the log position of the commit it made (tag 4), and the server's time, total, queued and
     * locks (tag 18) ([CLIENT What the server tells]).
     */
    @Test
    void aCompletionTellsRowsGeneratedPositionAndTime() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            Told told = connection.told();
            statements.query("CREATE TABLE TICKETS (ID INTEGER GENERATED ALWAYS AS IDENTITY, NAME VARCHAR(10))");
            statements.query("COMMIT");
            connection.setAutocommit(true);
            statements.query("INSERT INTO TICKETS (NAME) VALUES ('a'), ('b')");
            assertEquals(2L, told.rowsAffected());
            assertEquals(List.of("1", "2"), told.lastGenerated());
            assertNotNull(told.commitPosition(), "the commit's log position");
            Timing time = told.serverTime();
            assertNotNull(time);
            assertTrue(time.total() >= time.queued() && time.total() >= time.locks(), time.toString());
            statements.query("UPDATE TICKETS SET NAME = 'c' WHERE ID > 0");
            assertEquals(2L, told.rowsAffected());
            assertTrue(told.lastGenerated().isEmpty(), "none of another statement");
            statements.query("SELECT * FROM TICKETS");
            assertEquals(2L, told.rowsAffected(), "a query's rows, once all were read");
        }
    }

    /**
     * Verifies: [PROTOCOL Errors and notices] a statement's conditions are told one way: in its
     * Complete granted {@code conditions} (tag 17), else as Notices granted {@code notices}, else
     * not at all; the last statement's warning given by its SQLSTATE ([CLIENT What the server
     * tells]: conditions taken once, notices handed to a handler or kept).
     */
    @Test
    void conditionsAreToldOneWay() throws TurborelException, IOException {
        String sum = "SELECT SUM(X) FROM NULLS";
        try (Connection connection = connected()) {
            connection.statements().query("CREATE TABLE NULLS (X INTEGER)");
            connection.statements().query("INSERT INTO NULLS VALUES (NULL), (1)");
            connection.statements().query("COMMIT");
            connection.statements().query(sum);
            assertEquals("01003", connection.warning());
            List<TurborelException> conditions = connection.told().takeConditions();
            assertEquals("01003", conditions.get(0).getSQLState());
            assertTrue(connection.takeNotices().isEmpty(), "in the Complete, not as a Notice");
            assertThrows(
                    TurborelException.class,
                    () -> connection
                            .statements()
                            .query(
                                    "BEGIN SIGNAL SQLSTATE '01H01' "
                                            + "SET MESSAGE_TEXT = 'first'; SIGNAL SQLSTATE '75001' SET MESSAGE_TEXT = 'stop'; END"));
            assertEquals(
                    List.of("01H01"),
                    connection.takeNotices().stream()
                            .map(Reply.Notice::sqlstate)
                            .toList(),
                    "a failing statement's conditions as Notices");
        }
        Offer notices = new Offer(
                List.of(Capability.NOTICES, Capability.DIAGNOSTICS),
                List.of(),
                Offer.none().compressFrom());
        try (Connection connection = connected(notices)) {
            List<Reply.Notice> heard = new ArrayList<>();
            connection.setNoticeHandler(heard::add);
            connection.statements().query(sum);
            assertEquals(
                    List.of("01003"), heard.stream().map(Reply.Notice::sqlstate).toList());
            assertEquals(
                    "WARNING",
                    heard.get(0).diagnostics().fields().get(com.turborel.jdbc.internal.codec.DiagnosticField.SEVERITY));
            assertTrue(connection.told().conditions().isEmpty());
            connection.setNoticeHandler(null);
            connection.statements().query(sum);
            assertEquals(1, connection.takeNotices().size(), "kept with no handler");
        }
        try (Connection connection = connected(Offer.none())) {
            connection.statements().query(sum);
            assertTrue(connection.takeNotices().isEmpty(), "not at all");
            assertTrue(connection.told().conditions().isEmpty());
        }
    }

    /**
     * Verifies: [CLIENT What the server tells] a failure granted {@code diagnostics} carries what it
     * is about: a duplicate key names its constraint and table, its severity ERROR ([PROTOCOL
     * Errors and notices]).
     */
    @Test
    void aFailureCarriesItsDiagnostics() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            statements.query("CREATE TABLE KEYED (ID INTEGER CONSTRAINT KEYED_ID PRIMARY KEY)");
            statements.query("INSERT INTO KEYED VALUES (1)");
            TurborelException duplicate =
                    assertThrows(TurborelException.class, () -> statements.query("INSERT INTO KEYED VALUES (1)"));
            assertEquals("23000", duplicate.getSQLState(), duplicate.getMessage());
            assertEquals(
                    "ERROR",
                    duplicate.diagnostics().get("SEVERITY"),
                    duplicate.diagnostics().toString());
            assertEquals(
                    "KEYED_ID",
                    duplicate.diagnostics().get("CONSTRAINT"),
                    duplicate.diagnostics().toString());
            assertEquals(
                    "KEYED",
                    duplicate.diagnostics().get("TABLE"),
                    duplicate.diagnostics().toString());
            statements.query("ROLLBACK");
        }
    }

    /**
     * Verifies: [CLIENT What the server tells] under autocommit, granted {@code fetch-ends}, a
     * cursor's transaction ends as it does: the fetch of its last row commits, close commits, and
     * rows that fail roll it back; not granted, it is left active
     * ([PROTOCOL Execute 'E'], [PROTOCOL Conversations]: a cursor read in parts).
     */
    @Test
    void aCursorsTransactionEndsWithIt() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            statements.query("CREATE TABLE PARTS (N INTEGER)");
            statements.query("INSERT INTO PARTS VALUES (1), (2), (3)");
            statements.query("COMMIT");
            connection.setAutocommit(true);
            Statement select = statements.prepare("SELECT N FROM PARTS ORDER BY N");
            assertTrue(statements.openCursor(select, List.of(), 2).more());
            assertTrue(connection.inTransaction(), "open, the cursor keeps its transaction");
            assertFalse(statements.fetch(select, 0).more());
            assertFalse(connection.inTransaction(), "the fetch of its last row commits");
            Statement failing = statements.prepare("SELECT 10 / (N - 3) FROM PARTS");
            TurborelException divided =
                    assertThrows(TurborelException.class, () -> statements.openCursor(failing, List.of(), 1));
            assertEquals("22012", divided.getSQLState(), divided.getMessage());
            assertFalse(connection.inTransaction(), "rolled back as its rows failed");
            statements.openCursor(select, List.of(), 1);
            assertTrue(connection.inTransaction());
            statements.closeCursor(select);
            assertFalse(connection.inTransaction(), "close commits");
        }
        try (Connection old = connected(Offer.none())) {
            old.setAutocommit(true);
            Statement select = old.statements().prepare("SELECT N FROM PARTS");
            old.statements().openCursor(select, List.of(), 1);
            old.statements().fetch(select, 0);
            assertTrue(old.inTransaction(), "not granted fetch-ends: left active");
            old.setAutocommit(false);
            old.statements().query("ROLLBACK");
        }
    }

    /**
     * Verifies: [PROTOCOL Query 'Q'] a result that sent a large object's locator keeps its
     * transaction, under autocommit too, the locator valid within it.
     */
    @Test
    void aLocatorKeepsItsTransaction() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            statements.query("CREATE TABLE BOOKS (TEXT CLOB(1M))");
            statements.execute(
                    statements.prepare("INSERT INTO BOOKS VALUES (?)"), List.of(new Value.Text("x".repeat(70000))));
            statements.query("COMMIT");
            connection.setAutocommit(true);
            List<List<Value>> read = rows(statements.query("SELECT TEXT FROM BOOKS"));
            assertTrue(
                    read.get(0).get(0) instanceof Lob.Locator,
                    read.get(0).get(0).getClass().toString());
            assertTrue(connection.inTransaction(), "a locator lives in its transaction");
            connection.setAutocommit(false);
            statements.query("ROLLBACK");
            assertEquals(List.of(List.of(integer(1))), rows(statements.query("SELECT COUNT(*) FROM BOOKS")));
            statements.query("ROLLBACK");
        }
    }

    /**
     * Verifies: [CLIENT What the server tells] the server's warning of a statement is given by its
     * SQLSTATE, none of one that completed without one.
     */
    @Test
    void aWarningIsGivenBySqlstate() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            connection.statements().query("VALUES 1");
            assertNull(connection.warning());
        }
    }
}
