package com.turborel.jdbc.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.session.ConnectionStatement.Value;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The connection statements, as Turborel's parser reads them ([CLIENT The SQL-agent]). */
class ConnectionStatementTest {
    /**
     * Verifies: [CLIENT The SQL-agent] CONNECT TO a server AS a name USER a user, or DEFAULT; SET
     * CONNECTION a name or DEFAULT; DISCONNECT a name, DEFAULT, ALL or CURRENT; key words in any
     * case, comments and a final semicolon passed; a literal continued past a new line one literal;
     * another statement none.
     */
    @Test
    void connectionStatementsAreRead() throws TurborelException {
        assertEquals(
                new ConnectionStatement.Connect(new Value.Text("db"), new Value.Text("c"), new Value.Text("ann")),
                ConnectionStatement.of("connect /* to */ TO 'db' AS 'c' USER 'ann'; -- done"));
        assertEquals(
                new ConnectionStatement.Connect(new Value.Text("it's"), null, null),
                ConnectionStatement.of("CONNECT TO 'it''s'"));
        assertEquals(
                new ConnectionStatement.Connect(new Value.Text("ab"), null, null),
                ConnectionStatement.of("CONNECT TO 'a'\n  'b'"));
        assertEquals(new ConnectionStatement.ConnectDefault(), ConnectionStatement.of("CONNECT TO DEFAULT"));
        assertEquals(new ConnectionStatement.Set(null), ConnectionStatement.of("SET CONNECTION DEFAULT"));
        assertEquals(new ConnectionStatement.Set(new Value.Text("c")), ConnectionStatement.of("set connection 'c'"));
        for (ConnectionStatement.Disconnected object : List.of(
                ConnectionStatement.Disconnected.DEFAULT,
                ConnectionStatement.Disconnected.ALL,
                ConnectionStatement.Disconnected.CURRENT)) {
            assertEquals(
                    new ConnectionStatement.Disconnect(object, null),
                    ConnectionStatement.of("DISCONNECT " + object.name()));
        }
        assertEquals(
                new ConnectionStatement.Disconnect(ConnectionStatement.Disconnected.NAMED, new Value.Other("N'c'")),
                ConnectionStatement.of("DISCONNECT N'c'"));
        assertEquals(
                new ConnectionStatement.Connect(new Value.Other("7422"), null, new Value.Parameter()),
                ConnectionStatement.of("CONNECT TO 7422 USER ?"));
        for (String other : List.of("SELECT 1", "SET SCHEMA 'A'", "SET", "", "  'x", "/* unclosed", "\"CONNECT\" TO")) {
            assertNull(ConnectionStatement.of(other), other);
        }
    }

    /**
     * Verifies: [CLIENT The SQL-agent] a statement that begins as a connection statement and is
     * not one: a syntax error (SQLCODE -102, SQLSTATE 2A000), a lexical one (-101), or an embedded
     * variable, not supported (-900, 0A000).
     */
    @Test
    void malformedConnectionStatementsAreRefused() {
        Map<String, Integer> cases = Map.of(
                "CONNECT 'db'", -102,
                "CONNECT TO", -102,
                "CONNECT TO 'db' AS", -102,
                "CONNECT TO 'db' 'more'", -102,
                "DISCONNECT c1", -102,
                "SET CONNECTION ;", -102,
                "CONNECT TO 'db", -101,
                "CONNECT TO \"db", -101,
                "CONNECT TO 'db' /* ", -101,
                "CONNECT TO :db", -900);
        for (Map.Entry<String, Integer> each : cases.entrySet()) {
            TurborelException e =
                    assertThrows(TurborelException.class, () -> ConnectionStatement.of(each.getKey()), each.getKey());
            assertEquals(each.getValue(), e.getErrorCode(), each.getKey());
            assertEquals(each.getValue() == -900 ? "0A000" : "2A000", e.getSQLState(), each.getKey());
        }
        for (String more : List.of("CONNECT TO \"a\"\"b\"", "DISCONNECT 1.5e3 ALL")) {
            assertEquals(
                    -102,
                    assertThrows(TurborelException.class, () -> ConnectionStatement.of(more))
                            .getErrorCode());
        }
    }

    /**
     * Verifies: [CLIENT Pools] a duration in a pool's failure is written as Rust writes it; a
     * name in a reset's SET statement is a literal of a delimited identifier ([CLIENT Pools]).
     */
    @Test
    void durationsAndNamesAreWrittenAsTheReferenceWritesThem() {
        assertEquals("30s", RustDuration.of(java.time.Duration.ofSeconds(30)));
        assertEquals("1.5s", RustDuration.of(java.time.Duration.ofMillis(1500)));
        assertEquals("200ms", RustDuration.of(java.time.Duration.ofMillis(200)));
        assertEquals("1.5ms", RustDuration.of(java.time.Duration.ofNanos(1_500_000)));
        assertEquals("7µs", RustDuration.of(java.time.Duration.ofNanos(7_000)));
        assertEquals("0ns", RustDuration.of(java.time.Duration.ZERO));
        assertEquals("'\"A\"\"B''C\"'", PoolReset.delimited("A\"B'C"));
    }
}
