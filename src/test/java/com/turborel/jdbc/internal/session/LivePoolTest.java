package com.turborel.jdbc.internal.session;

import static com.turborel.jdbc.internal.session.LiveStatementsTest.connected;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.auth.Trust;
import com.turborel.jdbc.internal.transport.Target;
import com.turborel.jdbc.internal.transport.Timeouts;
import com.turborel.jdbc.testing.LiveServer;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Pools of connections to a live server ([CLIENT Pools]). */
@EnabledIfEnvironmentVariable(named = LiveServer.VARIABLE, matches = ".+")
class LivePoolTest {
    /** Connections asking for every capability but compression, the owner's schema made. */
    private static Connection standard() throws TurborelException {
        try {
            return connected();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Connections asking for no capability, so neither Ping nor Reset is granted. */
    private static Connection bare() throws TurborelException {
        return Connect.to(new Target.Tcp(LiveServer.address()), "ann", new Trust(), Timeouts.DEFAULT, Offer.none());
    }

    /**
     * Verifies: [CLIENT Pools] get makes a connection while fewer than max are made, a connection
     * given back is handed out again, and with max handed out get waits, failing with SQLSTATE
     * 08001 if none comes back in time; a pooled connection given back is no longer the borrower's.
     */
    @Test
    void aPoolIsBoundedAndHandsOutAgain() throws TurborelException, IOException {
        LiveServer.get();
        Pool pool = new Pool(
                LivePoolTest::standard, PoolConfig.defaults().withMax(2).withMaxWait(Duration.ofMillis(200)));
        try {
            Pooled first = pool.get();
            Connection held = first.connection();
            try (Pooled second = pool.get()) {
                assertEquals(new Pool.Counts(2, 0), pool.counts());
                TurborelException none = assertThrows(TurborelException.class, pool::get);
                assertEquals("08001", none.getSQLState());
                assertTrue(
                        String.valueOf(none.getMessage())
                                .contains("no connection of the pool's 2 came back within 200ms"),
                        none.getMessage());
                second.connection().statements().query("VALUES 1");
            }
            first.close();
            first.close();
            assertEquals(
                    "TB910",
                    assertThrows(TurborelException.class, first::connection).getSQLState());
            assertEquals(new Pool.Counts(2, 2), pool.counts());
            try (Pooled again = pool.get()) {
                assertSame(held, again.connection(), "the last given back, handed out first");
            }
        } finally {
            pool.close();
        }
        assertEquals(new Pool.Counts(0, 0), pool.counts());
    }

    /**
     * Verifies: [CLIENT Pools] a connection is given back reset by the protocol's Reset: its
     * transaction ended, its prepared statements gone, autocommit off, then the program's own
     * statements run; it is checked by Ping as it is handed out.
     */
    @Test
    void givenBackResetByTheProtocol() throws TurborelException, IOException {
        LiveServer.get();
        Pool pool = new Pool(LivePoolTest::standard, PoolConfig.defaults().withReset(List.of("SET TIME ZONE LOCAL")));
        try {
            try (Pooled pooled = pool.get()) {
                Connection c = pooled.connection();
                c.setAutocommit(false);
                c.statements().prepare("VALUES 1");
                c.statements().query("VALUES 1");
                assertTrue(c.inTransaction());
            }
            try (Pooled pooled = pool.get()) {
                Connection c = pooled.connection();
                assertFalse(c.inTransaction());
                assertFalse(c.autocommit());
                assertEquals(0, c.prepared());
            }
            assertEquals(new Pool.Counts(1, 1), pool.counts(), "the program's statement ran, and it was kept");
        } finally {
            pool.close();
        }
        Pool refusing = new Pool(LivePoolTest::standard, PoolConfig.defaults().withReset(List.of("NOT A STATEMENT")));
        try {
            refusing.get().close();
            assertEquals(new Pool.Counts(0, 0), refusing.counts(), "a reset that fails closes the connection");
        } finally {
            refusing.close();
        }
    }

    /**
     * Verifies: [CLIENT Pools] of a server that grants neither Ping nor Reset, a connection is
     * checked by ROLLBACK and reset by statements in one pipeline: its transaction rolled back, the
     * statements it prepared closed and destroyed, its user, schema and zone those of its login;
     * one that left too many statements prepared is closed instead.
     */
    @Test
    void givenBackResetByStatements() throws TurborelException, IOException {
        LiveServer.get();
        try (Connection setUp = connected()) {
            setUp.terminate();
        }
        Pool pool = new Pool(LivePoolTest::bare, PoolConfig.defaults());
        try {
            try (Pooled pooled = pool.get()) {
                Connection c = pooled.connection();
                c.setAutocommit(false);
                c.statements().prepare("VALUES 1");
                c.statements().prepare("VALUES 2");
                c.statements().query("VALUES 1");
            }
            assertEquals(new Pool.Counts(1, 1), pool.counts(), "reset, and kept");
            try (Pooled pooled = pool.get()) {
                Connection c = pooled.connection();
                assertFalse(c.inTransaction());
                assertEquals(0, c.prepared());
                for (int i = 0; i <= PoolReset.RESET_MOST_STATEMENTS; i++) {
                    c.nextStatement();
                }
            }
            assertEquals(new Pool.Counts(0, 0), pool.counts(), "too many left prepared: closed");
        } finally {
            pool.close();
        }
    }

    /**
     * Verifies: [CLIENT Pools] a connection lost is closed, not given back; one idle past
     * idle_timeout, or past max_lifetime, is closed rather than handed out; no more than max_idle
     * are kept idle; a source that cannot make one gives its failure and its place back.
     */
    @Test
    void staleAndLostConnectionsAreClosed() throws TurborelException, IOException, InterruptedException {
        LiveServer.get();
        Pool pool = new Pool(LivePoolTest::standard, PoolConfig.defaults().withIdleTimeout(Duration.ofMillis(1)));
        try {
            Connection first;
            try (Pooled pooled = pool.get()) {
                first = pooled.connection();
            }
            Thread.sleep(20);
            try (Pooled pooled = pool.get()) {
                assertNotSame(first, pooled.connection(), "idle past its timeout");
                pooled.connection().close();
            }
            assertEquals(new Pool.Counts(0, 0), pool.counts(), "a lost one closed");
        } finally {
            pool.close();
        }
        Pool aged = new Pool(
                LivePoolTest::standard,
                PoolConfig.defaults().withMaxLifetime(Duration.ZERO).withMaxIdle(1));
        try {
            aged.get().close();
            assertEquals(new Pool.Counts(0, 0), aged.counts(), "lived its time");
        } finally {
            aged.close();
        }
        Pool none = new Pool(
                LivePoolTest::standard, PoolConfig.defaults().withMaxIdle(0).withCheck(false));
        none.get().close();
        assertEquals(new Pool.Counts(0, 0), none.counts(), "no more than max_idle kept");
        Pool failing = new Pool(
                () -> {
                    throw new TurborelException(-919, "08006", "no server");
                },
                PoolConfig.defaults().withMax(1));
        assertEquals(
                "08006", assertThrows(TurborelException.class, failing::get).getSQLState());
        assertEquals(new Pool.Counts(0, 0), failing.counts(), "its place given back");
    }

    /**
     * Verifies: [CLIENT Pools] a pool made of settings is shared by threads, none started by the
     * pool, each waiting its own turn; each gets a connection that answers, and never more than
     * max are made.
     */
    @Test
    void threadsShareAPoolOfSettings() throws TurborelException, IOException, InterruptedException {
        LiveServer.get();
        com.turborel.jdbc.internal.settings.Settings settings = com.turborel.jdbc.internal.settings.Settings.resolveIn(
                com.turborel.jdbc.internal.settings.Options.fromUri(
                        "turborel://ann@" + LiveServer.HOST + ":" + LiveServer.PORT + "/?tls_mode=disable"),
                name -> null);
        Pool pool = new Pool(settings::connect, PoolConfig.defaults().withMax(2));
        AtomicReference<@Nullable Throwable> failed = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        try {
            for (int t = 0; t < 4; t++) {
                Thread thread = new Thread(() -> {
                    try {
                        for (int i = 0; i < 5; i++) {
                            try (Pooled pooled = pool.get()) {
                                pooled.connection().statements().query("VALUES 1");
                                assertTrue(pool.counts().made() <= 2);
                            }
                        }
                    } catch (TurborelException e) {
                        throw new IllegalStateException(e);
                    }
                });
                thread.setUncaughtExceptionHandler((which, e) -> failed.compareAndSet(null, e));
                thread.start();
                threads.add(thread);
            }
            for (Thread thread : threads) {
                thread.join();
            }
            assertNull(failed.get());
        } finally {
            pool.close();
        }
    }
}
