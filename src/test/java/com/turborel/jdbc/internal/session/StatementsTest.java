package com.turborel.jdbc.internal.session;

import static com.turborel.jdbc.internal.session.Script.after;
import static com.turborel.jdbc.internal.session.Script.complete;
import static com.turborel.jdbc.internal.session.Script.error;
import static com.turborel.jdbc.internal.session.Script.frame;
import static com.turborel.jdbc.internal.session.Script.pong;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.auth.Trust;
import com.turborel.jdbc.internal.codec.Attribute;
import com.turborel.jdbc.internal.codec.Timing;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.internal.codec.Writer;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Statements and the session's controls over a stream the test scripts ([PROTOCOL Conversations]). */
class StatementsTest {
    private static final Value ONE = new Value.Exact(BigInteger.ONE, 0);

    /** A Description of one INTEGER column named N, not null. */
    private static byte[] description() throws TurborelException {
        return frame(
                'D',
                new Writer().u32(1).flag(true).string("N").u8(2).u32(10).u32(0).flag(false));
    }

    private static byte[] prepared(long number, int columns) throws TurborelException {
        Writer w = new Writer().u32(number).u32(0).u32(columns);
        for (int i = 0; i < columns; i++) {
            w.flag(true).string("N").u8(2).u32(10).u32(0).flag(false);
        }
        return frame('P', w);
    }

    private static byte[] rows(int count) throws TurborelException {
        Writer w = new Writer().u32(count);
        for (int i = 0; i < count; i++) {
            w.u32(1).u8(2).i128(BigInteger.ONE).u32(0);
        }
        return frame('B', w);
    }

    private static byte[] suspended() throws TurborelException {
        return frame('S', new Writer());
    }

    /** A Complete of a warning, a transaction active, every tail field filled. */
    private static byte[] told() throws TurborelException {
        byte[] condition = new Writer()
                .bits32(0)
                .string("01003")
                .string("a null left out")
                .flag(false)
                .flag(false)
                .flag(true)
                .toByteArray();
        return frame(
                'K',
                new Writer()
                        .bits32(0)
                        .string("01003")
                        .flag(true)
                        .u8(4)
                        .u64(42)
                        .u8(6)
                        .u64(3)
                        .u8(16)
                        .strings(List.of("7", "8"))
                        .u8(17)
                        .u32(1)
                        .bytes(condition)
                        .u8(18)
                        .u64(9)
                        .u64(1)
                        .u64(2));
    }

    /**
     * Verifies: [PROTOCOL Query 'Q'] a query's Description, Rows and Complete give its columns and
     * rows, another statement's Complete its completion; [CLIENT Running statements] the server's
     * Error is the statement's failure and keeps the connection.
     */
    @Test
    void queriesGiveRowsOrACompletion() throws TurborelException {
        Connection connection = after(List.of(), description(), rows(2), complete(), complete(), error(-103, "42000"));
        Statements statements = connection.statements();
        Outcome.Rows rows = (Outcome.Rows) statements.query("SELECT N FROM T");
        assertEquals("N", rows.columns().get(0).name());
        assertEquals(List.of(List.of(ONE), List.of(ONE)), rows.rows());
        assertTrue(statements.query("CREATE TABLE T (N INTEGER)") instanceof Outcome.Done);
        TurborelException failed = assertThrows(TurborelException.class, () -> statements.query("SELECT"));
        assertEquals("42000", failed.getSQLState());
        assertFalse(connection.isLost());
    }

    /**
     * Verifies: [PROTOCOL Conversations] a reply out of its statement's order loses the connection
     * (SQLSTATE 08006): a Pong in place of a result, or among a query's rows.
     */
    @Test
    void aReplyOutOfOrderLosesTheConnection() throws TurborelException {
        Connection first = after(List.of(), pong());
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, () -> first.statements().query("X"))
                        .getSQLState());
        assertTrue(first.isLost());
        Connection rows = after(List.of(), description(), pong());
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, () -> rows.statements().query("X"))
                        .getSQLState());
        Connection sunk = after(List.of(), description(), pong());
        Script.Recorded nothing = new Script.Recorded();
        assertThrows(TurborelException.class, () -> sunk.statements().queryInto("X", nothing));
        Connection done = after(List.of(), pong());
        assertThrows(TurborelException.class, () -> done.statements().queryInto("X", nothing));
        Connection prepare = after(List.of(), complete());
        assertThrows(TurborelException.class, () -> prepare.statements().prepare("X"));
        Connection close = after(List.of(), pong());
        Statement none = new Statement(0, List.of(), List.of());
        assertThrows(TurborelException.class, () -> close.statements().closeCursor(none));
    }

    /**
     * Verifies: [CLIENT Running statements] a query's rows go to a sink a batch at a time as they
     * come, the count of them given; another statement's completion given as it is.
     */
    @Test
    void aSinkTakesBatches() throws TurborelException {
        Connection connection = after(List.of(), description(), rows(2), rows(3), complete(), complete());
        Script.Recorded sink = new Script.Recorded();
        assertEquals(new Streamed.Rows(5), connection.statements().queryInto("SELECT", sink));
        assertEquals(1, sink.columns.size());
        assertEquals(List.of(2, 3), sink.batches);
        assertTrue(connection.statements().queryInto("CREATE", sink) instanceof Streamed.Done);
    }

    /**
     * Verifies: [PROTOCOL Prepare 'P'] statements are numbered by the client from 0, each Prepare
     * answered by Prepared; [PROTOCOL Execute 'E'] a query executed gives its rows, another its
     * completion; a cursor opened gives its rows and Suspended if more, a fetch the next, close and
     * deallocate a Complete ([CLIENT Prepared statements]).
     */
    @Test
    void preparedStatementsAndCursors() throws TurborelException {
        Connection connection = after(
                List.of(),
                prepared(0, 1),
                prepared(1, 0),
                rows(1),
                complete(),
                complete(),
                rows(2),
                suspended(),
                rows(1),
                complete(),
                complete(),
                complete());
        Statements statements = connection.statements();
        Statement select = statements.prepare("SELECT N FROM T");
        assertEquals(0, select.number());
        Statement insert = statements.prepare("INSERT INTO T VALUES (1)");
        assertEquals(1, insert.number());
        assertEquals(List.of(List.of(ONE)), ((Outcome.Rows) statements.execute(select, List.of())).rows());
        assertTrue(statements.execute(insert, List.of()) instanceof Outcome.Done);
        Batch first = statements.openCursor(select, List.of(), 2);
        assertTrue(first.more());
        assertTrue(connection.inTransaction(), "rows read keep a transaction");
        assertFalse(statements.fetch(select, 0).more());
        statements.closeCursor(select);
        statements.deallocate(select);
    }

    /**
     * Verifies: [CLIENT Controlling the session] a cursor numbered other than 0, or a describe, is
     * refused before anything is sent unless the server granted {@code cursors} or {@code describe}
     * (SQLSTATE 08006, the connection kept); granted, numbered cursors are opened, fetched, closed
     * and described, and a statement described again.
     */
    @Test
    void numberedCursorsAndDescribeNeedTheirCapabilities() throws TurborelException {
        Connection plain = after(List.of());
        Statement statement = new Statement(5, List.of(), List.of());
        Statements.Cursor second = new Statements.Cursor(statement, 2);
        for (org.junit.jupiter.api.function.Executable refused : List.<org.junit.jupiter.api.function.Executable>of(
                () -> plain.statements().openCursor(second, List.of(), 0),
                () -> plain.statements().fetch(second, 0),
                () -> plain.statements().closeCursor(second),
                () -> plain.statements().describe(second),
                () -> plain.statements().describe(statement))) {
            assertEquals("08006", assertThrows(TurborelException.class, refused).getSQLState());
        }
        assertFalse(plain.isLost(), "refused before anything is sent");
        Connection granted = after(
                List.of(Capability.CURSORS, Capability.DESCRIBE),
                rows(1),
                complete(),
                rows(1),
                suspended(),
                complete(),
                description(),
                prepared(5, 1));
        Statements statements = granted.statements();
        assertFalse(statements.openCursor(second, List.of(), 0).more());
        assertTrue(statements.fetch(second, 1).more());
        statements.closeCursor(second);
        assertEquals("N", statements.describe(second).get(0).name());
        assertEquals(1, statements.describe(statement).columns().size());
    }

    /**
     * Verifies: [PROTOCOL Complete 'K'] the last completion's tail is told: the commit's log
     * position, kept until another comes; the rows a statement changed; the identity values it
     * generated; its conditions, taken once; the server's time, total, queued and locks ([CLIENT
     * What the server tells]); and its warning, by SQLSTATE class 01.
     */
    @Test
    void theCompletionsTailIsTold() throws TurborelException {
        Connection connection = after(List.of(), told(), complete());
        Told told = connection.told();
        assertTrue(told.takeConditions().isEmpty());
        assertNull(told.commitPosition());
        assertNull(told.rowsAffected());
        assertNull(told.serverTime());
        assertTrue(told.lastGenerated().isEmpty());
        assertTrue(told.conditions().isEmpty());
        connection.statements().query("INSERT");
        assertEquals("01003", connection.warning());
        assertTrue(connection.inTransaction());
        assertEquals(42L, told.commitPosition());
        assertEquals(3L, told.rowsAffected());
        assertEquals(List.of("7", "8"), told.lastGenerated());
        assertEquals(new Timing(9, 1, 2), told.serverTime());
        assertEquals("01003", told.takeConditions().get(0).getSQLState());
        assertTrue(told.conditions().isEmpty(), "taken");
        connection.statements().query("COMMIT");
        assertNull(connection.warning());
        assertEquals(42L, told.commitPosition(), "kept until another comes");
        assertNull(told.rowsAffected());
    }

    /**
     * Verifies: [PROTOCOL Ping 'G'] a ping is answered by Pong; [CLIENT Controlling the session] it,
     * a reset and a change of user each need their capability, refused before anything is sent; a
     * reply other than Pong loses the connection.
     */
    @Test
    void pingResetAndChangeUserNeedTheirCapabilities() throws TurborelException {
        Connection plain = after(List.of());
        Controls controls = plain.controls();
        assertThrows(TurborelException.class, controls::ping);
        assertThrows(TurborelException.class, controls::reset);
        assertThrows(TurborelException.class, () -> controls.changeUser("bob", new Trust()));
        assertThrows(TurborelException.class, () -> controls.queryMany("X"));
        assertFalse(plain.isLost());
        Connection granted = after(List.of(Capability.PING, Capability.RESET), pong(), complete(), complete());
        granted.controls().ping();
        granted.controls().reset();
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, granted.controls()::ping).getSQLState());
        assertTrue(granted.isLost());
    }

    /**
     * Verifies: [PROTOCOL ChangeUser 'U'] a change of user answers each Challenge as Hello's do,
     * and its Ready renews the session: its authorization identifier and cancel key, no transaction
     * active; refused, the server's Error is given ([CLIENT Controlling the session]).
     */
    @Test
    void aChangeOfUserRenewsTheSession() throws TurborelException {
        byte[] active = frame('K', new Writer().bits32(0).string("00000").flag(true));
        byte[] challenge = frame('C', new Writer().bytes(new byte[0]));
        byte[] bob = frame(
                'R',
                new Writer().string("BOB").raw(new byte[16]).strings(List.of()).string("turborel"));
        Connection connection = after(List.of(Capability.CHANGE_USER), active, bob, error("28000"));
        connection.statements().query("INSERT");
        assertTrue(connection.inTransaction());
        connection.controls().changeUser("bob", new Trust());
        assertEquals("BOB", connection.authorization());
        assertFalse(connection.inTransaction(), "no transaction active after ChangeUser");
        assertEquals(
                "28000",
                assertThrows(
                                TurborelException.class,
                                () -> connection.controls().changeUser("eve", new Trust()))
                        .getSQLState());
        Connection answered = after(List.of(Capability.CHANGE_USER), challenge);
        assertThrows(TurborelException.class, () -> answered.controls().changeUser("bob", new Trust()));
    }

    /**
     * Verifies: [CLIENT Controlling the session] query attributes are carried by each later
     * statement once set, refused unless {@code query-attributes} was granted, none always allowed.
     */
    @Test
    void queryAttributesNeedTheirCapability() throws TurborelException {
        List<Attribute> trace = List.of(new Attribute("traceparent", "00-1-2-01"));
        Connection plain = after(List.of());
        plain.controls().setQueryAttributes(List.of());
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, () -> plain.controls().setQueryAttributes(trace))
                        .getSQLState());
        Connection granted = after(List.of(Capability.QUERY_ATTRIBUTES), complete());
        granted.controls().setQueryAttributes(trace);
        assertEquals(trace, granted.told().attributes());
    }

    /**
     * Verifies: [PROTOCOL Query 'Q'] several statements as one request: each Complete but the last
     * says more follow (tag 5), a query's rows among them; the first failure stops the rest, given
     * beside the outcomes before it ([CLIENT Controlling the session]).
     */
    @Test
    void severalStatementsGiveEachOutcome() throws TurborelException {
        byte[] more =
                frame('K', new Writer().bits32(0).string("00000").flag(false).u8(5));
        Connection connection = after(
                List.of(Capability.MULTI_STATEMENT),
                more,
                description(),
                rows(1),
                more,
                complete(),
                more,
                error(-103, "42000"));
        Ran ran = connection.controls().queryMany("A; B; C");
        assertEquals(3, ran.outcomes().size());
        assertNull(ran.failure());
        Ran stopped = connection.controls().queryMany("A; B");
        assertEquals(1, stopped.outcomes().size());
        TurborelException failure = stopped.failure();
        assertEquals("42000", failure == null ? null : failure.getSQLState());
        Connection broken = after(List.of(Capability.MULTI_STATEMENT), description(), pong());
        assertThrows(TurborelException.class, () -> broken.controls().queryMany("A"));
        Connection lost = after(List.of(Capability.MULTI_STATEMENT));
        assertThrows(TurborelException.class, () -> lost.controls().queryMany("A"));
    }

    /**
     * Verifies: [PROTOCOL Errors and notices] SQLSTATE TBR01, SQLCODE -920, a replica that had not
     * applied REPLICATION.READ_AFTER's position in time, is the statement's failure and keeps the
     * connection, so the program may try again; [PROTOCOL Requests] every request but Hello,
     * Answer and Cancel is sent only after Ready, the connection made only by its login.
     */
    @Test
    void aReplicaBehindFailsTheStatementAlone() throws TurborelException {
        Connection connection = after(List.of(), error(-920, "TBR01"), complete());
        TurborelException behind = assertThrows(
                TurborelException.class, () -> connection.statements().query("SELECT"));
        assertEquals(-920, behind.sqlcode());
        assertEquals("TBR01", behind.getSQLState());
        assertFalse(connection.isLost());
        assertTrue(connection.statements().query("SELECT") instanceof Outcome.Done);
    }
}
