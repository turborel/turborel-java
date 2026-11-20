package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Broken;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * A pool of connections ([CLIENT Pools]): a bounded set a program's threads share. {@link #get}
 * hands out an idle connection that answers, or makes one while fewer than {@link PoolConfig#max}
 * are made, or waits for one to come back, at most {@link PoolConfig#maxWait}. A connection handed out
 * comes back as its {@link Pooled} is closed, reset to the state it logged in with; one lost, or
 * whose reset fails, is closed instead, so no borrower sees another's session. Idle beyond {@link
 * PoolConfig#idleTimeout}, or older than {@link PoolConfig#maxLifetime}, a connection is closed
 * rather than handed out, and no more than {@link PoolConfig#maxIdle} are kept idle. It is
 * thread-safe through a lock and its condition alone, and starts no thread, each wait being the
 * waiting borrower's. As Turborel's {@code client/src/pool.rs}.
 */
public final class Pool {
    /** The SQLSTATE of a client unable to connect, class 08. */
    private static final String UNABLE_TO_CONNECT = "08001";

    /** What makes a pool's connections: connection settings, or anything that makes one. */
    @FunctionalInterface
    public interface Source {
        /**
         * A new connection, logged in.
         *
         * @return the connection
         * @throws TurborelException why none can be made
         */
        Connection connect() throws TurborelException;
    }

    /**
     * How many connections are made, handed out and idle, and how many of them are idle.
     *
     * @param made the connections made
     * @param idle those idle
     */
    public record Counts(int made, int idle) {}

    /** A connection idle in the pool: when it was made, and since when idle, in nanoseconds. */
    private record Idle(Connection connection, long made, long since) {}

    private final Source source;
    private final PoolConfig config;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition returned = lock.newCondition();
    private final Deque<Idle> idle = new ArrayDeque<>();
    private int made;

    /**
     * A pool of connections {@code source} makes, kept as {@code config} says.
     *
     * @param source what makes its connections
     * @param config how it keeps them
     */
    public Pool(Source source, PoolConfig config) {
        this.source = source;
        this.config = config;
    }

    /**
     * How the pool keeps its connections.
     *
     * @return its configuration
     */
    public PoolConfig config() {
        return config;
    }

    /** Whether {@code idle} may still be handed out at {@code now}. */
    private boolean fresh(Idle each, long now) {
        Duration lifetime = config.maxLifetime();
        Duration timeout = config.idleTimeout();
        boolean lived = lifetime == null || now - each.made() < lifetime.toNanos();
        boolean rested = timeout == null || now - each.since() < timeout.toNanos();
        return lived && rested && !each.connection().isLost();
    }

    /** One fewer connection made, so a waiter may make one. */
    private void unmade() {
        lock.lock();
        try {
            made--;
            returned.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * A connection: an idle one that answers, else a new one while the pool has room, else the
     * first to come back within {@link PoolConfig#maxWait}.
     *
     * @return the connection, given back as it is closed
     * @throws TurborelException SQLSTATE 08001 if none comes back in time; the source's failure to
     *     make one
     */
    public Pooled get() throws TurborelException {
        long deadline = System.nanoTime() + config.maxWait().toNanos();
        while (true) {
            Idle next = next(deadline);
            if (next == null) {
                return make();
            }
            Pooled pooled = checked(next);
            if (pooled != null) {
                return pooled;
            }
        }
    }

    /**
     * An idle connection, or none if a place to make one is taken, waiting for one to come back
     * until {@code deadline}; connections gone stale closed on the way.
     */
    private @Nullable Idle next(long deadline) throws TurborelException {
        lock.lock();
        try {
            while (true) {
                long now = System.nanoTime();
                while (!idle.isEmpty()) {
                    Idle each = idle.pop();
                    if (fresh(each, now)) {
                        return each;
                    }
                    each.connection().close();
                    made--;
                }
                if (made < config.max()) {
                    made++;
                    return null;
                }
                long left = deadline - now;
                if (left <= 0) {
                    throw timedOut();
                }
                try {
                    if (returned.awaitNanos(left) <= 0 && idle.isEmpty() && made >= config.max()) {
                        throw timedOut();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    TurborelException stopped = new TurborelException(
                            Broken.SQLCODE,
                            UNABLE_TO_CONNECT,
                            "[CLIENT Pools] the wait for a connection was interrupted");
                    stopped.initCause(e);
                    throw stopped;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** No connection came back in time: SQLSTATE 08001. */
    private TurborelException timedOut() {
        return new TurborelException(
                Broken.SQLCODE,
                UNABLE_TO_CONNECT,
                "[CLIENT Pools] no connection of the pool's " + config.max() + " came back within "
                        + RustDuration.of(config.maxWait()));
    }

    /** {@code each}, handed out if it answers or need not be checked; else closed, its place given up. */
    private @Nullable Pooled checked(Idle each) {
        if (config.check()) {
            try {
                PoolReset.answers(each.connection());
            } catch (TurborelException dead) {
                each.connection().close();
                unmade();
                return null;
            }
        }
        return new Pooled(this, each.connection(), each.made());
    }

    /** A new connection, in the place {@link #next} took for it. */
    private Pooled make() throws TurborelException {
        boolean madeOne = false;
        try {
            Pooled pooled = new Pooled(this, source.connect(), System.nanoTime());
            madeOne = true;
            return pooled;
        } finally {
            if (!madeOne) {
                unmade();
            }
        }
    }

    /**
     * Gives a connection back, reset; or closes it, if it is lost, its reset fails, it has lived its
     * time, or enough are idle.
     */
    void giveBack(Connection connection, long madeAt) {
        Duration lifetime = config.maxLifetime();
        boolean lived = lifetime != null && System.nanoTime() - madeAt >= lifetime.toNanos();
        boolean kept = !lived;
        if (kept) {
            try {
                PoolReset.reset(connection, config.reset());
            } catch (TurborelException failed) {
                kept = false;
            }
        }
        lock.lock();
        try {
            if (kept && idle.size() < config.maxIdle()) {
                idle.push(new Idle(connection, madeAt, System.nanoTime()));
            } else {
                connection.close();
                made--;
            }
            returned.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * How many connections are made, handed out and idle, and how many of them are idle.
     *
     * @return the counts
     */
    public Counts counts() {
        lock.lock();
        try {
            return new Counts(made, idle.size());
        } finally {
            lock.unlock();
        }
    }

    /** Closes every idle connection, as a program ends; those handed out are closed as they come back. */
    public void close() {
        lock.lock();
        try {
            while (!idle.isEmpty()) {
                idle.pop().connection().close();
                made--;
            }
        } finally {
            lock.unlock();
        }
    }
}
