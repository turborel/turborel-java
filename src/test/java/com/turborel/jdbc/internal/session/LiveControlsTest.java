package com.turborel.jdbc.internal.session;

import static com.turborel.jdbc.internal.session.LiveStatementsTest.connected;
import static com.turborel.jdbc.internal.session.LiveStatementsTest.integer;
import static com.turborel.jdbc.internal.session.LiveStatementsTest.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.auth.Password;
import com.turborel.jdbc.internal.auth.Trust;
import com.turborel.jdbc.internal.codec.Attribute;
import com.turborel.jdbc.internal.codec.Column;
import com.turborel.jdbc.internal.codec.DiagnosticField;
import com.turborel.jdbc.internal.codec.Reply;
import com.turborel.jdbc.internal.codec.Request;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.testing.LiveServer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** A live session controlled beyond its statements ([CLIENT Controlling the session]). */
@EnabledIfEnvironmentVariable(named = LiveServer.VARIABLE, matches = ".+")
class LiveControlsTest {
    /** The names of the Statuses until the reply that ends a request, read off the wire. */
    private static List<String> statusesUntilTheEnd(Connection connection) throws TurborelException {
        List<String> names = new ArrayList<>();
        Wire wire = connection.wire();
        while (true) {
            Reply reply = wire.receive(com.turborel.jdbc.internal.codec.FrameHeader.MAX_BODY, false);
            if (reply instanceof Reply.Status status) {
                names.add(status.name());
            } else if (!(reply instanceof Reply.Notice)) {
                return names;
            }
        }
    }

    private static final List<String> TABLE =
            List.of("SERVER_VERSION", "AUTHORIZATION", "SCHEMA", "READ_ONLY", "SERVER_READ_ONLY", "CATALOG", "OWNER");

    /**
     * Verifies: [PROTOCOL Ping 'G'] a ping is answered by Pong, during a copy too ([CLIENT
     * Controlling the session]: whether the server and the connection are alive).
     */
    @Test
    void aPingIsAnsweredDuringACopyToo() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            connection.controls().ping();
            connection.statements().query("CREATE TABLE COPIED (N INTEGER)");
            connection.send(new Request.CopyIn("COPIED", List.of(), false));
            connection.controls().ping();
            connection.send(new Request.CopyEnd(true));
            assertTrue(connection.receive() instanceof Reply.Complete);
            connection.statements().query("ROLLBACK");
        }
    }

    /**
     * Verifies: [PROTOCOL Reset 'W'] a reset rolls the transaction back, told by Notice 01T01 of
     * severity NOTICE, the server's own notice, sent whatever was granted; prepared statements and
     * settings are gone, the user kept; its replies are a Status of each setting, then a Complete
     * saying no transaction is active ([PROTOCOL Conversations]: a reset of a session in a
     * transaction; [PROTOCOL Errors and notices]).
     */
    @Test
    void aResetRollsBackAndForgets() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            statements.query("CREATE TABLE RESET_ONE (N INTEGER)");
            statements.query("COMMIT");
            String zone = connection.setting("SESSION.TIME_ZONE");
            statements.query("SET SESSION.TIME_ZONE = '+05:00'");
            Statement prepared = statements.prepare("SELECT N FROM RESET_ONE");
            statements.query("INSERT INTO RESET_ONE VALUES (1)");
            assertTrue(connection.inTransaction());
            List<Reply.Notice> heard = new ArrayList<>();
            connection.setNoticeHandler(heard::add);
            connection.controls().reset();
            assertFalse(connection.inTransaction());
            assertEquals(
                    List.of("01T01"), heard.stream().map(Reply.Notice::sqlstate).toList());
            assertEquals("NOTICE", heard.get(0).diagnostics().get(DiagnosticField.SEVERITY));
            assertEquals(zone, connection.setting("SESSION.TIME_ZONE"), "settings gone, told again");
            assertEquals(LiveServer.OWNER, connection.authorization(), "the user kept");
            assertEquals(
                    "26000",
                    assertThrows(TurborelException.class, () -> statements.execute(prepared, List.of()))
                            .getSQLState());
            assertEquals(List.of(List.of(integer(0))), rows(statements.query("SELECT COUNT(*) FROM RESET_ONE")));
            statements.query("ROLLBACK");
        }
    }

    /**
     * Verifies: [PROTOCOL Status 'T'] after Reset, before the reply that ends it, every name of the
     * page's table is told again in its order, changed or not, then the session's own settings.
     */
    @Test
    void aResetTellsEveryStatusInOrder() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            connection.send(new Request.Reset());
            List<String> names = statusesUntilTheEnd(connection);
            assertEquals(TABLE, names.subList(0, TABLE.size()));
            assertTrue(names.contains("SESSION.TIME_ZONE"), names.toString());
        }
    }

    /**
     * Verifies: [PROTOCOL ChangeUser 'U'] a change of user authenticates the connection again by the
     * same steps as Hello's, its session renewed as that user's under a new cancel key, every Status
     * told again with AUTHORIZATION the user's, no transaction active after it; refused, the
     * connection fails ([CLIENT Controlling the session]).
     */
    @Test
    void aChangeOfUserRenewsTheSession() throws TurborelException, IOException {
        try (Connection owner = connected()) {
            try {
                owner.statements().query("CREATE USER BOB WITH PASSWORD 'bob''s'");
                owner.statements().query("COMMIT");
            } catch (TurborelException made) {
                owner.statements().query("ROLLBACK");
            }
        }
        LiveServer server = LiveServer.get();
        try (Connection connection = Connect.local(server.socket(), "ann", new Trust())) {
            Canceller before = connection.canceller();
            assertNotNull(before);
            connection.statements().query("VALUES 1");
            connection.controls().changeUser("bob", new Password("bob's"));
            assertEquals("BOB", connection.authorization());
            assertEquals("BOB", connection.setting("AUTHORIZATION"));
            assertEquals("NO", connection.setting("OWNER"));
            assertFalse(connection.inTransaction());
            TurborelException refused = assertThrows(
                    TurborelException.class, () -> connection.controls().changeUser("bob", new Password("wrong")));
            assertEquals("28000", refused.getSQLState(), refused.getMessage());
            assertTrue(connection.isLost(), "a refusal fails the connection");
        }
        try (Connection raw = Connect.local(server.socket(), "ann", new Trust())) {
            raw.send(new Request.ChangeUser(
                    "bob",
                    "password",
                    com.turborel.jdbc.internal.codec.Octets.of(
                            "bob's".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            List<String> names = statusesUntilTheEnd(raw);
            assertEquals(TABLE, names.subList(0, TABLE.size()), "every Status again, in the table's order");
        }
    }

    /**
     * Verifies: [CLIENT Controlling the session] query attributes are carried by every later
     * statement, read in SQL by QUERY_ATTRIBUTE, until set again ([PROTOCOL Query 'Q'] tail 1).
     */
    @Test
    void queryAttributesAreCarried() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            String read = "VALUES QUERY_ATTRIBUTE('ticket')";
            connection.controls().setQueryAttributes(List.of(new Attribute("ticket", "T-17")));
            assertEquals(
                    List.of(List.of(new Value.Text("T-17"))),
                    rows(connection.statements().query(read)));
            Statement prepared = connection.statements().prepare(read);
            assertEquals(
                    List.of(List.of(new Value.Text("T-17"))),
                    rows(connection.statements().execute(prepared, List.of())));
            connection.controls().setQueryAttributes(List.of());
            assertEquals(
                    List.of(List.of(Value.NULL)), rows(connection.statements().query(read)));
        }
    }

    /**
     * Verifies: [CLIENT Controlling the session] several numbered cursors of one statement are open
     * at once, an open one's columns described, the statement described again ([PROTOCOL Describe
     * 'Y'], [PROTOCOL Execute 'E'] tail 3); [PROTOCOL Prepare 'P'] a statement whose cursor is open
     * is not replaced (SQLSTATE 24000).
     */
    @Test
    void numberedCursorsAndDescriptions() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            statements.query("CREATE TABLE TWICE (N INTEGER)");
            statements.query("INSERT INTO TWICE VALUES (1), (2), (3)");
            Statement select = statements.prepare("SELECT N FROM TWICE ORDER BY N");
            Statements.Cursor one = new Statements.Cursor(select, 1);
            Statements.Cursor two = new Statements.Cursor(select, 2);
            assertEquals(
                    List.of(List.of(integer(1))),
                    statements.openCursor(one, List.of(), 1).rows());
            assertEquals(
                    List.of(List.of(integer(1)), List.of(integer(2))),
                    statements.openCursor(two, List.of(), 2).rows());
            assertEquals(List.of(List.of(integer(2))), statements.fetch(one, 1).rows());
            assertEquals(
                    List.of("N"),
                    statements.describe(two).stream().map(Column::name).toList());
            assertEquals(1, statements.describe(select).columns().size());
            connection.send(new Request.Prepare(select.number(), "VALUES 1"));
            Reply replaced = connection.receive();
            assertTrue(replaced instanceof Reply.Error, replaced.toString());
            assertEquals("24000", ((Reply.Error) replaced).failure().sqlstate());
            statements.closeCursor(one);
            statements.closeCursor(two);
            statements.query("ROLLBACK");
        }
    }

    /**
     * Verifies: [CLIENT XML values] an XML parameter is made of its text, an XML value read as its
     * serialization, its nodes parsed from it; a value the server cannot serialize fails the fetch
     * (SQLSTATE 2200W).
     */
    @Test
    void xmlValues() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            statements.query("CREATE TABLE ORDERS (ID INTEGER, BODY XML)");
            Statement insert = statements.prepare("INSERT INTO ORDERS VALUES (?, ?)");
            String order = "<order no=\"18\"><item>jam</item></order>";
            statements.execute(insert, List.of(integer(18), new Value.Xml(order)));
            Value.Xml body = (Value.Xml) rows(statements.query("SELECT BODY FROM ORDERS WHERE ID = 18"))
                    .get(0)
                    .get(0);
            assertEquals(order, body.serialization());
            assertEquals("jam", body.nodes().getFirstChild().getTextContent());
            TurborelException unserializable = assertThrows(
                    TurborelException.class,
                    () -> statements.query(
                            "VALUES XMLQUERY('attribute a {1}' RETURNING SEQUENCE BY REF EMPTY ON EMPTY)"));
            assertEquals("2200W", unserializable.getSQLState(), unserializable.getMessage());
            statements.query("ROLLBACK");
        }
    }

    /**
     * Verifies: [CLIENT UUIDs] a UUID parameter is made of its octets or of its text, and a UUID
     * read gives its 16 octets and its text, whatever types the program registered.
     */
    @Test
    void uuidValues() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            statements.query("CREATE TABLE TICKET_IDS (ID UUID, NAME VARCHAR(5))");
            Statement insert = statements.prepare("INSERT INTO TICKET_IDS VALUES (?, ?)");
            Value.Uuid text = Value.Uuid.parse("919108f7-52d1-4320-9bac-f847db4148a8");
            assertNotNull(text);
            statements.execute(insert, List.of(text, new Value.Text("12A")));
            statements.execute(insert, List.of(Value.Uuid.ofOctets(text.octets()), new Value.Text("12B")));
            List<List<Value>> read = rows(statements.query("SELECT ID FROM TICKET_IDS ORDER BY NAME"));
            Value.Uuid first = (Value.Uuid) read.get(0).get(0);
            assertEquals("919108f7-52d1-4320-9bac-f847db4148a8", first.text());
            assertEquals(first, read.get(1).get(0));
            statements.query("ROLLBACK");
        }
    }

    /**
     * Verifies: [PROTOCOL Errors and notices] the server's own notices always come as a Notice, not
     * among a statement's conditions though {@code conditions} is granted: Reset's 01T01 of a
     * transaction it rolled back.
     */
    @Test
    void theServersOwnNoticeIsANotice() throws TurborelException, IOException {
        List<String> asked = List.of(Capability.RESET, Capability.NOTICES, Capability.CONDITIONS);
        try (Connection connection =
                connected(new Offer(asked, List.of(), Offer.none().compressFrom()))) {
            connection.statements().query("VALUES 1");
            assertTrue(connection.inTransaction());
            connection.controls().reset();
            assertEquals(
                    List.of("01T01"),
                    connection.takeNotices().stream()
                            .map(Reply.Notice::sqlstate)
                            .toList());
            assertTrue(connection.told().conditions().isEmpty(), "not among the conditions");
        }
    }
}
