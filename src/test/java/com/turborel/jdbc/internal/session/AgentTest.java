package com.turborel.jdbc.internal.session;

import static com.turborel.jdbc.internal.session.Script.after;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** An SQL-agent over connections the test scripts ([CLIENT The SQL-agent]). */
class AgentTest {
    private final List<String> asked = new ArrayList<>();

    private Connection scripted(@Nullable String server, @Nullable String user) throws TurborelException {
        asked.add(server + "/" + user);
        return after(List.of());
    }

    private static String completed(Outcome outcome) {
        return ((Outcome.Done) outcome).completed().sqlstate();
    }

    /**
     * Verifies: [CLIENT The SQL-agent] a name's and a user's spaces are trimmed, a name read as an
     * identifier, so 'c1' and ' C1 ' are one connection; a delimited name keeps its case; a user
     * that is no authorization identifier is refused (SQLSTATE 28000).
     */
    @Test
    void namesAreTrimmedAndReadAsIdentifiers() throws TurborelException {
        Agent agent = new Agent(this::scripted);
        assertEquals("00000", completed(agent.query("CONNECT TO ' srv ' AS ' c1 ' USER ' ann '")));
        assertEquals(List.of("srv/ann"), asked);
        assertEquals("C1", agent.currentName());
        assertEquals(
                "08002",
                assertThrows(TurborelException.class, () -> agent.query("CONNECT TO 's' AS 'C1'"))
                        .getSQLState());
        assertEquals(
                "28000",
                assertThrows(TurborelException.class, () -> agent.query("CONNECT TO 's' AS 'c2' USER '\"x'"))
                        .getSQLState());
        assertEquals(
                "2E000",
                assertThrows(TurborelException.class, () -> agent.query("CONNECT TO 's' AS '1x'"))
                        .getSQLState());
        agent.query("SET CONNECTION ' c1 '");
        assertEquals("C1", agent.currentName());
        assertEquals(
                "08003",
                assertThrows(TurborelException.class, () -> agent.query("SET CONNECTION 'c 2'"))
                        .getSQLState());
        assertEquals(
                "2A000",
                assertThrows(TurborelException.class, () -> agent.query("SET CONNECTION 7"))
                        .getSQLState());
        assertTrue(agent.connectionStatement("VALUES 1") == null);
    }

    /**
     * Verifies: [CLIENT The SQL-agent] a connection that cannot be ended is a warning of DISCONNECT
     * (SQLSTATE 01002), the connection gone still; ending the agent ends every connection, giving
     * the first failure; a connection lost is the current one's state.
     */
    @Test
    void endingAConnectionThatFailsIsAWarning() throws TurborelException {
        Agent agent = new Agent(this::scripted);
        agent.query("CONNECT TO DEFAULT");
        agent.current().close();
        assertTrue(agent.isLost());
        assertEquals("01002", completed(agent.query("DISCONNECT CURRENT")));
        assertNull(agent.currentConnection());
        agent.query("CONNECT TO 'a' AS 'a'");
        agent.query("CONNECT TO 'b' AS 'b'");
        agent.current().close();
        TurborelException first = assertThrows(TurborelException.class, agent::terminate);
        assertEquals("08006", first.getSQLState());
        assertNull(agent.currentConnection());
        assertNull(agent.currentName());
        Agent quiet = Agent.connected(this::scripted, after(List.of()));
        quiet.terminate();
        Agent refused = new Agent((server, user) -> {
            throw new TurborelException(-104, "28000", "no");
        });
        TurborelException rejected = assertThrows(TurborelException.class, () -> refused.query("CONNECT TO DEFAULT"));
        assertEquals("08004", rejected.getSQLState());
        assertEquals(-919, rejected.getErrorCode());
    }
}
