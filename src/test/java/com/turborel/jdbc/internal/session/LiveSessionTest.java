package com.turborel.jdbc.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.auth.Trust;
import com.turborel.jdbc.internal.codec.Reply;
import com.turborel.jdbc.internal.codec.Request;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.internal.transport.Target;
import com.turborel.jdbc.internal.transport.Timeouts;
import com.turborel.jdbc.testing.LiveServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** A live session's status, cancel, terminate and waits ([PROTOCOL Opening a connection]). */
@EnabledIfEnvironmentVariable(named = LiveServer.VARIABLE, matches = ".+")
class LiveSessionTest {
    /**
     * A query that runs far longer than any test waits: a billion rows of a table counted, as
     * Turborel's own cancel test counts a cross join of a table.
     */
    static final String LONG = "SELECT COUNT(*) FROM ANN.SPAN A, ANN.SPAN B, ANN.SPAN C";

    /**
     * A query of a few seconds: long enough to outwait a short read timeout, short enough that the
     * statement a lost connection leaves running ends before the server is stopped.
     */
    static final String SECONDS =
            "WITH RECURSIVE C(N) AS (VALUES CAST(1 AS INTEGER) UNION ALL SELECT N + 1 FROM C WHERE N < 999) "
                    + "SELECT COUNT(*) FROM C A, C B, C D WHERE D.N < 20";

    private static final InetSocketAddress ADDRESS = LiveServer.address();

    /** A statement's replies, read to its last ([PROTOCOL Conversations]). */
    static List<Reply> run(Connection connection, String sql, boolean ends) throws TurborelException {
        connection.send(new Request.Query(sql, ends, List.of(), false));
        List<Reply> replies = new ArrayList<>();
        while (true) {
            Reply reply = connection.receive();
            if (reply instanceof Reply.Error error) {
                throw connection.failed(error);
            }
            replies.add(reply);
            if (reply instanceof Reply.Complete) {
                return replies;
            }
        }
    }

    /** The owner's schema, made once for every live test of a run. */
    static void schema(Connection connection) throws TurborelException {
        try {
            run(connection, "CREATE SCHEMA AUTHORIZATION ANN", true);
        } catch (TurborelException made) {
            if (connection.isLost()) {
                throw made;
            }
        }
    }

    private static Connection trusted(Offer offer) throws TurborelException, IOException {
        LiveServer.get();
        return Connect.to(new Target.Tcp(ADDRESS), "ann", new Trust(), Timeouts.DEFAULT, offer);
    }

    /**
     * Verifies: [PROTOCOL Status 'T'] after a statement the server tells each setting it changed,
     * which the connection then gives with no round trip; a setting reset is told at the
     * database's value.
     */
    @Test
    void aSettingChangedIsToldAgain() throws TurborelException, IOException {
        Connection connection = trusted(Offer.standard());
        String database = connection.setting("SESSION.TIME_ZONE");
        run(connection, "SET SESSION.TIME_ZONE = '+05:00'", true);
        assertEquals("+05:00", connection.setting("SESSION.TIME_ZONE"));
        run(connection, "SET SESSION.TIME_ZONE = DEFAULT", true);
        assertEquals(database, connection.setting("SESSION.TIME_ZONE"), "reset, told at the database's value");
        connection.terminate();
    }

    /**
     * Verifies: [PROTOCOL Hello 'H'] the connection's attributes go in Hello, and the server shows
     * them in INFORMATION_SCHEMA.CONNECTION_ATTRIBUTES, granted or not.
     */
    @Test
    void connectionAttributesAreShown() throws TurborelException, IOException {
        for (Offer offer : List.of(Offer.standard(), Offer.none())) {
            Connection connection = trusted(offer.attribute(Offer.APPLICATION, "turborel-java-live"));
            List<Reply> replies = run(
                    connection,
                    "SELECT ATTRIBUTE_VALUE FROM INFORMATION_SCHEMA.CONNECTION_ATTRIBUTES WHERE ATTRIBUTE_NAME = 'application'",
                    true);
            Reply.Rows rows = (Reply.Rows) replies.get(1);
            assertTrue(
                    rows.rows().stream().anyMatch(r -> r.get(0).equals(new Value.Text("turborel-java-live"))),
                    rows.toString());
            connection.terminate();
        }
    }

    /**
     * Verifies: [PROTOCOL Cancel 'Z'] a statement running is cancelled from another thread, by a
     * connection of its own carrying the key Ready gave, and fails with SQLCODE -920, SQLSTATE
     * TB920; the cancel gives up after its limit ([CLIENT Running statements]).
     */
    @Test
    void aRunningStatementIsCancelled() throws TurborelException, IOException, InterruptedException {
        Connection connection = trusted(Offer.standard());
        schema(connection);
        run(connection, "CREATE TABLE SPAN (N INTEGER PRIMARY KEY)", true);
        run(
                connection,
                "INSERT INTO SPAN WITH RECURSIVE C(N) AS (VALUES CAST(1 AS INTEGER) "
                        + "UNION ALL SELECT N + 1 FROM C WHERE N < 1000) SELECT N FROM C",
                true);
        connection.setReadTimeout(Duration.ofSeconds(60));
        Canceller canceller = connection.canceller();
        assertNotNull(canceller);
        assertEquals("Canceller(..)", canceller.toString());
        AtomicReference<TurborelException> outcome = new AtomicReference<>();
        Thread running = new Thread(() -> {
            try {
                run(connection, LONG, false);
            } catch (TurborelException e) {
                outcome.set(e);
            }
        });
        running.start();
        // Cancelled once it runs: a cancel before is of nothing, as Turborel's own test cancels.
        while (running.isAlive()) {
            canceller.cancel();
            running.join(20);
        }
        TurborelException cancelled = outcome.get();
        assertNotNull(cancelled, "the statement was cancelled");
        assertEquals(-920, cancelled.sqlcode(), cancelled.getMessage());
        assertEquals("TB920", cancelled.getSQLState());
        run(connection, "VALUES 1", true);
        connection.terminate();
    }

    /**
     * Verifies: [CLIENT Timeouts] a read that waits past the read timeout loses the connection
     * (SQLSTATE 08006), saying the server did not answer in time; the read timeout is changed on
     * the connection.
     */
    @Test
    void aReadPastItsTimeoutLosesTheConnection() throws TurborelException, IOException {
        Connection connection = trusted(Offer.standard());
        assertNull(connection.readTimeout());
        connection.setReadTimeout(Duration.ofMillis(200));
        assertEquals(Duration.ofMillis(200), connection.readTimeout());
        TurborelException late = assertThrows(TurborelException.class, () -> run(connection, SECONDS, false));
        assertEquals("08006", late.getSQLState());
        assertTrue(String.valueOf(late.getMessage()).contains("did not answer in time"), late.getMessage());
        assertTrue(connection.isLost());
        assertThrows(TurborelException.class, () -> run(connection, "VALUES 1", true));
    }

    /**
     * Verifies: [CLIENT Timeouts] replies are taken up to as many bytes as the program says; a
     * longer one loses the connection (SQLSTATE 08006).
     */
    @Test
    void aReplyPastTheLimitLosesTheConnection() throws TurborelException, IOException {
        Connection connection = trusted(Offer.standard());
        connection.setMaxBody(16);
        TurborelException longer = assertThrows(
                TurborelException.class,
                () -> run(connection, "VALUES 'a string far longer than sixteen bytes'", true));
        assertEquals("08006", longer.getSQLState());
        assertTrue(connection.isLost());
    }

    /**
     * Verifies: [PROTOCOL Terminate 'X'] Terminate ends the session, its transaction as
     * SESSION.COMMIT_AT_END says, and the server closes; a commit that fails is given as its
     * failure ([CLIENT Connecting]).
     */
    @Test
    void terminateGivesAFailingCommit() throws TurborelException, IOException {
        try (Connection setUp = trusted(Offer.standard())) {
            schema(setUp);
            run(
                    setUp,
                    "CREATE TABLE TERMINATED (ID INTEGER PRIMARY KEY, P INTEGER, "
                            + "CONSTRAINT LATER CHECK (P > 0) INITIALLY DEFERRED)",
                    true);
            setUp.terminate();
        }
        Connection connection = trusted(Offer.standard());
        run(connection, "SET SESSION.COMMIT_AT_END = 'ON'", true);
        run(connection, "INSERT INTO TERMINATED VALUES (1, -1)", false);
        assertTrue(connection.inTransaction());
        TurborelException commit = assertThrows(TurborelException.class, connection::terminate);
        assertEquals("40002", commit.getSQLState(), commit.getMessage());
        assertTrue(connection.isLost());
    }
}
