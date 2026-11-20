package com.turborel.jdbc.internal.session;

import static com.turborel.jdbc.internal.session.Script.after;
import static com.turborel.jdbc.internal.session.Script.complete;
import static com.turborel.jdbc.internal.session.Script.error;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A pool of connections the test scripts ([CLIENT Pools]). */
class PoolTest {
    /** A source handing out the connections given, in order. */
    private static Pool.Source of(Connection... connections) {
        Deque<Connection> left = new ArrayDeque<>(List.of(connections));
        return left::pop;
    }

    /**
     * Verifies: [CLIENT Pools] an idle connection that does not answer its check, by Ping, is
     * closed and another made; a connection given back is reset by Reset where granted, waited on
     * as the stream its maker bounds; no limit of idle time or life keeps one as long as it
     * answers.
     */
    @Test
    void anIdleConnectionThatDoesNotAnswerIsReplaced() throws TurborelException {
        Connection silent = after(List.of(Capability.PING, Capability.RESET), complete());
        Connection next = after(List.of());
        Pool pool = new Pool(
                of(silent, next), PoolConfig.defaults().withIdleTimeout(null).withMaxLifetime(null));
        pool.get().close();
        assertEquals(new Pool.Counts(1, 1), pool.counts(), "reset by Reset, and kept");
        try (Pooled pooled = pool.get()) {
            assertSame(next, pooled.connection(), "the silent one failed its Ping");
            assertTrue(silent.isLost());
        }
    }

    /**
     * Verifies: [CLIENT Pools] of a server that grants no Reset, the reset's pipeline closes and
     * destroys the statements a borrower prepared, a failure of those taken as statements already
     * gone; a failure of another of its statements closes the connection; one checked by ROLLBACK
     * that fails is replaced.
     */
    @Test
    void aResetByStatementsKeepsOrClosesTheConnection() throws TurborelException {
        Connection kept =
                after(List.of(), complete(), error(-501, "34000"), complete(), complete(), complete(), complete());
        kept.nextStatement();
        Connection failing = after(List.of(), complete(), error(-103, "2A000"), complete(), complete());
        Connection last = after(List.of());
        Pool pool = new Pool(of(kept, failing, last), PoolConfig.defaults().withCheck(true));
        Pooled first = pool.get();
        Pooled second = pool.get();
        first.close();
        assertEquals(new Pool.Counts(2, 1), pool.counts(), "the release's failure taken as gone");
        assertEquals(0, kept.prepared());
        second.close();
        assertEquals(new Pool.Counts(1, 1), pool.counts(), "a SET's failure closes it");
        try (Pooled again = pool.get()) {
            assertSame(last, again.connection(), "the kept one failed its ROLLBACK, out of replies");
        }
    }

    /**
     * Verifies: [CLIENT Pools] a get waiting for a connection takes the one given back while it
     * waits; a wait interrupted fails (SQLSTATE 08001), the thread's interrupt kept.
     */
    @Test
    void aWaitTakesTheConnectionGivenBack() throws TurborelException, InterruptedException {
        Connection only = after(List.of(Capability.RESET), complete());
        Pool pool = new Pool(
                of(only), PoolConfig.defaults().withMax(1).withCheck(false).withMaxWait(Duration.ofSeconds(30)));
        Pooled held = pool.get();
        Thread.currentThread().interrupt();
        assertEquals("08001", assertThrows(TurborelException.class, pool::get).getSQLState());
        assertTrue(Thread.interrupted(), "the interrupt kept");
        Thread giver = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            held.close();
        });
        giver.start();
        try (Pooled waited = pool.get()) {
            assertSame(only, waited.connection());
        }
        giver.join();
        Pool other = new Pool(of(after(List.of())), PoolConfig.defaults());
        assertNotSame(pool.config(), other.config());
        assertEquals(PoolConfig.defaults(), other.config());
    }
}
