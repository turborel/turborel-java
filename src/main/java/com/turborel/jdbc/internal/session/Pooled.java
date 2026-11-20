package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Failures;
import org.jspecify.annotations.Nullable;

/**
 * A connection handed out by a pool, given back as it is closed ([CLIENT Pools]): reset, or closed
 * if it is lost, its reset fails, it has lived its time, or enough are idle.
 */
public final class Pooled implements AutoCloseable {
    private final Pool pool;
    private final long made;
    private @Nullable Connection connection;

    Pooled(Pool pool, Connection connection, long made) {
        this.pool = pool;
        this.connection = connection;
        this.made = made;
    }

    /**
     * The connection, until it is given back.
     *
     * @return the connection
     * @throws TurborelException SQLSTATE TB910 once it is given back
     */
    public Connection connection() throws TurborelException {
        Connection held = connection;
        if (held == null) {
            throw Failures.misuse("[CLIENT Pools] the connection is given back to its pool");
        }
        return held;
    }

    /** Gives the connection back to its pool; a second close does nothing. */
    @Override
    public void close() {
        Connection held = connection;
        connection = null;
        if (held != null) {
            pool.giveBack(held, made);
        }
    }
}
