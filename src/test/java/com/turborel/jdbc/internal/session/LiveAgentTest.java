package com.turborel.jdbc.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.auth.Scram;
import com.turborel.jdbc.internal.auth.Trust;
import com.turborel.jdbc.testing.LiveServer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** An SQL-agent's connections to a live server ([CLIENT The SQL-agent]). */
@EnabledIfEnvironmentVariable(named = LiveServer.VARIABLE, matches = ".+")
class LiveAgentTest {
    /** The servers and users each connection was asked of. */
    private final List<String> asked = new ArrayList<>();

    /**
     * Connects to the live server whatever the address, as the user named or ANN, by trust, under
     * autocommit; refused for the user nobody, by a wrong password; unreachable at nowhere.
     */
    private Connection connect(@Nullable String server, @Nullable String user) throws TurborelException {
        asked.add(server + " as " + user);
        if ("nowhere".equals(server)) {
            throw new TurborelException(-919, "08006", "cannot connect");
        }
        if ("nobody".equals(user)) {
            try {
                return Connect.local(LiveServer.get().socket(), "ann", new Scram("wrong"));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        Connection connection = Connect.tcp(LiveServer.HOST, LiveServer.PORT, user == null ? "ann" : user, new Trust());
        connection.setAutocommit(true);
        return connection;
    }

    private static String state(org.junit.jupiter.api.function.Executable call) {
        return String.valueOf(assertThrows(TurborelException.class, call).getSQLState());
    }

    /**
     * Verifies: [CLIENT The SQL-agent] CONNECT, through the connector, to the address and as the
     * user it names, each connection named, the one made current; SET CONNECTION and DISCONNECT;
     * every other statement sent to the current connection; none current after the current is
     * disconnected (SQLSTATE 08003).
     */
    @Test
    void connectSetAndDisconnect() throws TurborelException, IOException {
        LiveServer.get();
        Agent agent = new Agent((server, user) -> connect(server, user));
        assertNull(agent.currentConnection());
        assertEquals("08003", state(agent::current));
        assertEquals(
                "00000",
                ((Outcome.Done) agent.query("CONNECT TO DEFAULT")).completed().sqlstate());
        assertNull(agent.currentName());
        assertEquals(
                "00000",
                ((Outcome.Done) agent.query("connect to 'db1' as 'c1' user 'ann';"))
                        .completed()
                        .sqlstate());
        assertEquals("C1", agent.currentName());
        assertEquals(List.of("null as null", "db1 as ann"), asked);
        assertTrue(agent.query("VALUES 1") instanceof Outcome.Rows);
        Script.Recorded sink = new Script.Recorded();
        assertTrue(agent.queryInto("VALUES 1", sink) instanceof Streamed.Rows);
        assertTrue(agent.queryInto("SET CONNECTION DEFAULT", sink) instanceof Streamed.Done);
        assertNull(agent.currentName());
        assertTrue(agent.canceller() != null && agent.warning() == null && !agent.isLost());
        agent.query("SET CONNECTION 'c1'");
        agent.query("DISCONNECT CURRENT");
        assertNull(agent.currentConnection(), "none current after the current is ended");
        agent.query("SET CONNECTION DEFAULT");
        agent.query("CONNECT TO 'db2' AS '\"lower\"'");
        assertEquals("lower", agent.currentName());
        agent.query("DISCONNECT DEFAULT");
        assertEquals("lower", agent.currentName(), "the current one kept");
        agent.query("DISCONNECT ALL");
        assertNull(agent.currentConnection());
        assertNull(agent.canceller());
        assertNull(agent.warning());
        assertEquals("08003", state(() -> agent.query("VALUES 1")));
        agent.terminate();
    }

    /**
     * Verifies: [CLIENT The SQL-agent] the rules of each connection statement: a name in use
     * (08002), a connection statement in an active transaction (0A001), a user that is no
     * authorization identifier (28000), a server's address that is no identifier and no name given
     * (2E000), a server that cannot be reached (08001) or refuses (08004), no such connection
     * (08003), a disconnect of an active transaction (25000), a dynamic parameter (42000).
     */
    @Test
    void theRulesOfEachStatement() throws TurborelException, IOException {
        LiveServer.get();
        Agent agent = Agent.connected((server, user) -> connect(server, user), connect(null, null));
        try {
            assertEquals("08002", state(() -> agent.query("CONNECT TO DEFAULT")));
            assertEquals("28000", state(() -> agent.query("CONNECT TO 'db' AS 'c' USER 'not one'")));
            assertEquals("2E000", state(() -> agent.query("CONNECT TO 'db.example'")));
            assertEquals("08001", state(() -> agent.query("CONNECT TO 'nowhere' AS 'c'")));
            assertEquals("08004", state(() -> agent.query("CONNECT TO 'db' AS 'c' USER 'nobody'")));
            assertEquals("08003", state(() -> agent.query("SET CONNECTION 'nope'")));
            assertEquals("08003", state(() -> agent.query("DISCONNECT 'nope'")));
            assertEquals("42000", state(() -> agent.query("CONNECT TO ?")));
            agent.current().setAutocommit(false);
            agent.query("VALUES 1");
            assertEquals("0A001", state(() -> agent.query("CONNECT TO 'db' AS 'c'")));
            assertEquals("0A001", state(() -> agent.query("SET CONNECTION DEFAULT")));
            assertEquals("25000", state(() -> agent.query("DISCONNECT ALL")));
            agent.query("ROLLBACK");
        } finally {
            agent.terminate();
        }
        Agent none = new Agent((server, user) -> connect(server, user));
        assertEquals("08003", state(() -> none.query("DISCONNECT CURRENT")));
        assertEquals("08003", state(() -> none.query("DISCONNECT DEFAULT")));
        assertEquals("08003", state(() -> none.query("SET CONNECTION DEFAULT")));
        none.terminate();
    }
}
