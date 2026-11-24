package com.turborel.jdbc.internal.session;

import static com.turborel.jdbc.internal.session.LiveStatementsTest.connected;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.auth.Password;
import com.turborel.jdbc.internal.codec.Debugging;
import com.turborel.jdbc.internal.codec.TypeName;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.testing.LiveServer;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The routine debugger over the session, two connections on a live server ([CLIENT Debugging routines]). */
@EnabledIfEnvironmentVariable(named = LiveServer.VARIABLE, matches = ".+")
class LiveDebuggerTest {
    private static final Duration WAIT = Duration.ofSeconds(10);

    /** A procedure that runs a while, a loop of many statements. */
    private static final String LONG = """
            CREATE PROCEDURE DBG_LONG(IN TIMES INTEGER) CONTAINS SQL
            BEGIN
              DECLARE I INTEGER DEFAULT 0;
              WHILE I < TIMES DO
                SET I = I + 1;
              END WHILE;
            END""";

    /**
     * Verifies: [CLIENT Debugging routines] a running target is interrupted, pausing at its next
     * statement at the end of a step; a cancel of its statement while it is paused ends the pause,
     * the debugger told so, and the statement fails (SQLSTATE TB920).
     */
    @Test
    void aRunningTargetIsInterruptedAndCancelled() throws TurborelException, IOException, InterruptedException {
        ExecutorService runner = Executors.newSingleThreadExecutor();
        try (Connection target = connected();
                Connection watching = connected()) {
            target.setAutocommit(true);
            target.statements().query(LONG);
            Debugger d = watching.debugger();
            d.attach(target.debugger().id());
            Future<Outcome> call = runner.submit(() -> target.statements().query("CALL DBG_LONG(100000000)"));
            Thread.sleep(300);
            d.interrupt();
            Debugging.Paused paused = assertInstanceOf(Debugging.Event.Pause.class, d.waitFor(WAIT))
                    .paused();
            assertNull(paused.breakpoint(), "a step");
            Debugging.Frame top = paused.frames().get(paused.frames().size() - 1);
            assertEquals("I", top.variables().get(0).name());
            Canceller canceller = target.canceller();
            assertTrue(canceller != null);
            canceller.cancel();
            assertInstanceOf(Debugging.Event.Cancelled.class, d.waitFor(WAIT));
            ExecutionException failed =
                    assertThrows(ExecutionException.class, () -> call.get(WAIT.toSeconds(), TimeUnit.SECONDS));
            assertEquals("TB920", ((TurborelException) Objects.requireNonNull(failed.getCause())).getSQLState());
            d.detach();
        } finally {
            runner.shutdownNow();
        }
    }

    /**
     * Verifies: [CLIENT Debugging routines] a breakpoint whose condition is false pauses nothing, one
     * whose condition is true pauses where it is true; a pause longer than
     * DEBUGGER.PAUSE_TIMEOUT_MS, set for the database a while, goes on by itself, the debugger told
     * it timed out; a target's session that ends detaches its debugger, which is told so.
     */
    @Test
    void conditionsAndPausesThatEndByThemselves()
            throws TurborelException, IOException, InterruptedException, ExecutionException, TimeoutException {
        ExecutorService runner = Executors.newSingleThreadExecutor();
        try (Connection watching = connected()) {
            Connection target = connected();
            target.setAutocommit(true);
            target.statements().query(LONG.replace("DBG_LONG", "DBG_SHORT"));
            Debugger d = watching.debugger();
            d.attach(target.debugger().id());
            TypeName routine = new TypeName("", "DBG_SHORT");
            Debugging.Breakpoint never = d.breakAt(Debugging.Kind.PROCEDURE, routine, 5, "I < 0");
            runner.submit(() -> target.statements().query("CALL DBG_SHORT(10)"))
                    .get(WAIT.toSeconds(), TimeUnit.SECONDS);
            assertNull(d.waitFor(Duration.ofMillis(200)), "the condition false");
            d.clear(never.number());
            Debugging.Breakpoint third = d.breakAt(Debugging.Kind.PROCEDURE, routine, 5, "I = 3");
            Future<Outcome> call = runner.submit(() -> target.statements().query("CALL DBG_SHORT(10)"));
            Debugging.Paused paused = assertInstanceOf(Debugging.Event.Pause.class, d.waitFor(WAIT))
                    .paused();
            assertEquals(third.number(), paused.breakpoint());
            Debugging.Frame top = paused.frames().get(paused.frames().size() - 1);
            assertEquals(
                    new Value.Exact(BigInteger.valueOf(3), 0),
                    top.variables().get(0).value());
            d.resume(Debugging.Action.CONTINUE);
            call.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            d.clear(third.number());
            target.statements().query("SET DATABASE DEBUGGER.PAUSE_TIMEOUT_MS = 300");
            d.breakAt(Debugging.Kind.PROCEDURE, routine, 5, null);
            call = runner.submit(() -> target.statements().query("CALL DBG_SHORT(1)"));
            assertInstanceOf(Debugging.Event.Pause.class, d.waitFor(WAIT));
            assertInstanceOf(Debugging.Event.TimedOut.class, d.waitFor(WAIT));
            call.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            target.terminate();
            assertInstanceOf(Debugging.Event.Ended.class, d.waitFor(WAIT));
            assertEquals(
                    "TBD01", assertThrows(TurborelException.class, d::detach).getSQLState(), "detached");
        } finally {
            runner.shutdownNow();
            try (Connection owner = connected()) {
                owner.setAutocommit(true);
                owner.statements().query("SET DATABASE DEBUGGER.PAUSE_TIMEOUT_MS TO DEFAULT");
            }
        }
    }

    /**
     * Verifies: [CLIENT Debugging routines] a step into pauses in the function a statement invokes,
     * its frame the innermost of two, a function's; a step out pauses at the next statement of the
     * frame it returns to; a frame gives its cursors, open or not; continue runs to the next
     * breakpoint, one in a function.
     */
    @Test
    void stepsGoIntoAndOutOfAFunction()
            throws TurborelException, IOException, InterruptedException, ExecutionException, TimeoutException {
        ExecutorService runner = Executors.newSingleThreadExecutor();
        try (Connection target = connected();
                Connection watching = connected()) {
            target.setAutocommit(true);
            nested(target);
            Debugger d = watching.debugger();
            d.attach(target.debugger().id());
            d.breakAt(Debugging.Kind.PROCEDURE, new TypeName("", "DBG_OUTER"), 6, null);
            Future<Outcome> call = runner.submit(() -> target.statements().query("CALL DBG_OUTER(21)"));
            Debugging.Frame outer = innermost(d.waitFor(WAIT));
            assertEquals(6L, outer.line());
            assertEquals(
                    List.of(new Debugging.Cursor("C", true, "SELECT 1 FROM (VALUES (1)) AS T (A)")), outer.cursors());
            d.resume(Debugging.Action.INTO);
            Debugging.Paused into = assertInstanceOf(Debugging.Event.Pause.class, d.waitFor(WAIT))
                    .paused();
            assertEquals(2, into.frames().size());
            Debugging.Frame inner = into.frames().get(1);
            assertEquals(
                    Debugging.Kind.FUNCTION,
                    Objects.requireNonNull(inner.origin()).kind());
            assertEquals(4L, inner.line());
            assertEquals("X", inner.parameters().get(0).name());
            d.resume(Debugging.Action.OUT);
            assertEquals(7L, innermost(d.waitFor(WAIT)).line());
            d.resume(Debugging.Action.CONTINUE);
            call.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            d.breakAt(Debugging.Kind.FUNCTION, new TypeName("", "DBG_TWICE"), 5, null);
            call = runner.submit(() -> target.statements().query("CALL DBG_OUTER(4)"));
            assertEquals(6L, innermost(d.waitFor(WAIT)).line(), "the procedure's breakpoint first");
            d.resume(Debugging.Action.CONTINUE);
            Debugging.Paused returning = assertInstanceOf(Debugging.Event.Pause.class, d.waitFor(WAIT))
                    .paused();
            assertEquals(2, returning.frames().size());
            Debugging.Frame twice = returning.frames().get(1);
            assertEquals(
                    new Value.Exact(BigInteger.valueOf(8), 0),
                    twice.variables().get(0).value());
            d.detach();
            call.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        } finally {
            runner.shutdownNow();
        }
    }

    /**
     * Makes a function, and a procedure of a cursor whose line 6 invokes it.
     *
     * @param target the connection to make them by
     */
    private static void nested(Connection target) throws TurborelException {
        target.statements().query("""
                            CREATE FUNCTION DBG_TWICE(X INTEGER) RETURNS INTEGER CONTAINS SQL
                            BEGIN
                              DECLARE Y INTEGER;
                              SET Y = X * 2;
                              RETURN Y;
                            END""");
        target.statements().query("""
                            CREATE PROCEDURE DBG_OUTER(IN K INTEGER) READS SQL DATA
                            BEGIN
                              DECLARE V INTEGER DEFAULT 0;
                              DECLARE C CURSOR FOR SELECT 1 FROM (VALUES (1)) AS T (A);
                              OPEN C;
                              SET V = DBG_TWICE(K);
                              CLOSE C;
                            END""");
    }

    /** The innermost frame of a pause heard. */
    private static Debugging.Frame innermost(Debugging.@Nullable Event heard) {
        Debugging.Paused paused =
                assertInstanceOf(Debugging.Event.Pause.class, heard).paused();
        return paused.frames().get(paused.frames().size() - 1);
    }

    /**
     * Verifies: [PROTOCOL Debug 'V'] a breakpoint in a routine the user may not debug, another's,
     * is refused with SQLSTATE 42000 and SQLCODE -104, the session kept; attached to its own
     * user's connection it debugs that one.
     */
    @Test
    void anotherUsersRoutineIsRefused() throws TurborelException, IOException {
        try (Connection owner = connected()) {
            owner.setAutocommit(true);
            try {
                owner.statements().query("CREATE USER BOB WITH PASSWORD 'bob''s'");
            } catch (TurborelException made) {
                // Made by another test of the run.
            }
            owner.statements().query("CREATE PROCEDURE DBG_OWNED() CONTAINS SQL BEGIN END");
        }
        LiveServer server = LiveServer.get();
        try (Connection bob = Connect.local(server.socket(), "bob", new Password("bob's"));
                Connection watching = Connect.local(server.socket(), "bob", new Password("bob's"))) {
            Debugger d = watching.debugger();
            d.attach(bob.debugger().id());
            TurborelException refused = assertThrows(
                    TurborelException.class,
                    () -> d.breakAt(Debugging.Kind.PROCEDURE, new TypeName("ANN", "DBG_OWNED"), 1, null));
            assertEquals("42000", refused.getSQLState(), refused.getMessage());
            assertEquals(-104, refused.sqlcode());
            assertTrue(d.breakpoints().isEmpty());
            d.detach();
        }
    }
}
