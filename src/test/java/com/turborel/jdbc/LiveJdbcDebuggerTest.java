package com.turborel.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.testing.LiveServer;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The routine debugger through JDBC, two connections on a live server ([DESIGN] D15). */
@EnabledIfEnvironmentVariable(named = LiveServer.VARIABLE, matches = ".+")
class LiveJdbcDebuggerTest {
    /** The procedure debugged: line 4 its UPDATE, line 5 its SET. */
    private static final String PROCEDURE = """
            CREATE PROCEDURE DBG_RAISE(IN PCT INTEGER) MODIFIES SQL DATA
            BEGIN
              DECLARE N INTEGER DEFAULT 0;
              UPDATE DBG_STAFF SET SALARY = SALARY * (100 + PCT) / 100;
              SET N = (SELECT COUNT(*) FROM DBG_STAFF);
            END""";

    private static final Duration WAIT = Duration.ofSeconds(10);

    private static TurborelDebugger debugger(Connection c) throws SQLException {
        return c.unwrap(TurborelConnection.class).debugger();
    }

    /** The innermost frame of a pause heard. */
    private static TurborelDebugger.Frame top(TurborelDebugger.Event heard) {
        TurborelDebugger.Pause pause =
                assertInstanceOf(TurborelDebugger.Event.Paused.class, heard).pause();
        return pause.frames().get(pause.frames().size() - 1);
    }

    /**
     * Verifies: [CLIENT Debugging routines] a connection's ID is another's to attach by; a
     * breakpoint set at a line of a procedure, listed; the target's CALL pauses there, the debugger
     * waiting with a time limit hears the pause with its frames, their parameters and variables,
     * and reads it again; a step over pauses at the next line; continue lets the CALL complete; a
     * wait with nothing to hear gives none; detach ends it ([PROTOCOL Debug 'V']).
     */
    @Test
    void aProcedureIsDebugged()
            throws SQLException, IOException, InterruptedException, ExecutionException, TimeoutException {
        ExecutorService runner = Executors.newSingleThreadExecutor();
        try (Connection target = LiveJdbc.connect();
                Connection watching = LiveJdbc.connect();
                Statement s = target.createStatement()) {
            assertTrue(target.unwrap(TurborelConnection.class).granted().contains("debugger"));
            s.execute("CREATE TABLE DBG_STAFF (ID INTEGER PRIMARY KEY, SALARY INTEGER)");
            s.execute("INSERT INTO DBG_STAFF VALUES (1, 50000), (2, 60000)");
            s.execute(PROCEDURE);
            TurborelDebugger d = debugger(watching);
            d.attach(debugger(target).id());
            TurborelDebugger.Breakpoint set = d.setBreakpoint(
                    TurborelDebugger.RoutineKind.PROCEDURE, new TurborelDebugger.Routine("", "DBG_RAISE"), 4, null);
            assertEquals(4, set.line());
            assertTrue(set.routine().name().startsWith("DBG_RAISE"), set.toString());
            assertEquals(List.of(set), d.breakpoints());
            assertNull(d.waitForEvent(Duration.ofMillis(200)), "nothing yet");
            Future<Boolean> call = runner.submit(() -> s.execute("CALL DBG_RAISE(10)"));
            TurborelDebugger.Frame first = top(Objects.requireNonNull(d.waitForEvent(WAIT)));
            assertEquals(4L, first.line());
            assertTrue(first.visible());
            assertEquals("PCT", first.parameters().get(0).name());
            assertEquals(
                    10,
                    ((Number) Objects.requireNonNull(first.parameters().get(0).value())).intValue());
            assertEquals("N", first.variables().get(0).name());
            TurborelDebugger.Pause again = d.stack();
            assertNotNull(again);
            assertEquals(set.number(), again.breakpoint());
            d.resume(TurborelDebugger.Step.OVER);
            TurborelDebugger.Frame second = top(Objects.requireNonNull(d.waitForEvent(WAIT)));
            assertEquals(5L, second.line(), "a step over, to the next line");
            d.resume(TurborelDebugger.Step.CONTINUE);
            call.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            assertNull(d.stack(), "running again");
            d.clearBreakpoint(set.number());
            assertTrue(d.breakpoints().isEmpty());
            d.detach();
        } finally {
            runner.shutdownNow();
        }
    }

    /**
     * Verifies: [PROTOCOL Debug 'V'] refusals keep the session: an ID not served is SQLSTATE 08003;
     * an action the state does not allow, as resuming while attached to none, TBD01 (SQLCODE -910);
     * a breakpoint in a routine there is none of, the session's own name of none, is refused too.
     */
    @Test
    void refusalsKeepTheSession() throws SQLException, IOException {
        try (Connection watching = LiveJdbc.connect()) {
            TurborelDebugger d = debugger(watching);
            SQLException none = assertThrows(SQLException.class, () -> d.attach(4_000_000_000L));
            assertEquals("08003", none.getSQLState());
            SQLException state = assertThrows(SQLException.class, () -> d.resume(TurborelDebugger.Step.INTO));
            assertEquals("TBD01", state.getSQLState());
            assertEquals(-910, state.getErrorCode());
            assertTrue(watching.isValid(0), "the session goes on");
        }
    }

    /**
     * Verifies: [CLIENT Debugging routines] a procedure a compound statement run directly calls is
     * interrupted, the outer frame of no routine; a pause longer than DEBUGGER.PAUSE_TIMEOUT_MS is heard to time out; a
     * cancel of the paused statement is heard, the statement failing (SQLSTATE TB920); the
     * target's close is heard as its end.
     */
    @Test
    void everyEventIsHeard() throws SQLException, IOException, InterruptedException {
        ExecutorService runner = Executors.newSingleThreadExecutor();
        try (Connection watching = LiveJdbc.connect()) {
            Connection target = LiveJdbc.connect();
            Statement s = target.createStatement();
            s.execute("""
                    CREATE PROCEDURE DBG_SPIN(IN TIMES INTEGER) CONTAINS SQL
                    BEGIN
                      DECLARE I INTEGER DEFAULT 0;
                      WHILE I < TIMES DO
                        SET I = I + 1;
                      END WHILE;
                    END""");
            s.execute("SET DATABASE DEBUGGER.PAUSE_TIMEOUT_MS = 2000");
            TurborelDebugger d = debugger(watching);
            d.attach(debugger(target).id());
            Future<Boolean> run = runner.submit(() -> s.execute("BEGIN CALL DBG_SPIN(100000000); END"));
            Thread.sleep(300);
            d.interrupt();
            TurborelDebugger.Event heard = Objects.requireNonNull(d.waitForEvent(WAIT));
            TurborelDebugger.Frame direct = assertInstanceOf(TurborelDebugger.Event.Paused.class, heard)
                    .pause()
                    .frames()
                    .get(0);
            assertNull(direct.origin(), "a statement run directly");
            assertEquals(
                    "DBG_SPIN",
                    Objects.requireNonNull(top(heard).origin()).name().name());
            assertInstanceOf(TurborelDebugger.Event.TimedOut.class, d.waitForEvent(WAIT));
            d.interrupt();
            top(Objects.requireNonNull(d.waitForEvent(WAIT)));
            s.cancel();
            assertInstanceOf(TurborelDebugger.Event.Cancelled.class, d.waitForEvent(WAIT));
            ExecutionException failed =
                    assertThrows(ExecutionException.class, () -> run.get(WAIT.toSeconds(), TimeUnit.SECONDS));
            assertEquals("TB920", ((SQLException) Objects.requireNonNull(failed.getCause())).getSQLState());
            target.close();
            assertInstanceOf(TurborelDebugger.Event.Ended.class, d.waitForEvent(WAIT));
        } finally {
            runner.shutdownNow();
            try (Connection owner = LiveJdbc.connect();
                    Statement reset = owner.createStatement()) {
                reset.execute("SET DATABASE DEBUGGER.PAUSE_TIMEOUT_MS TO DEFAULT");
            }
        }
    }
}
