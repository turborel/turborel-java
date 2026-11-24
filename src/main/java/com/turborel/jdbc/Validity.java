package com.turborel.jdbc;

import com.turborel.jdbc.internal.session.Capability;
import com.turborel.jdbc.internal.session.Connection;
import java.sql.SQLException;
import java.time.Duration;

/**
 * Whether a connection answers, as {@code isValid} asks ([DESIGN] D2): by Ping, granted {@code
 * ping}, else by a query that begins no transaction of the program's, waiting at most the timeout
 * given where the stream is the driver's to bound.
 */
final class Validity {
    /** What a connection checks it answers by, granted no Ping. */
    private static final String CHECK = "VALUES 1";

    private Validity() {}

    /**
     * Whether {@code session} answers within {@code timeout} seconds, none if 0.
     *
     * @param session the connection, open
     * @param timeout the seconds it waits, 0 or more
     * @return whether it answered
     * @throws SQLException if its read timeout cannot be set back
     */
    static boolean answers(Connection session, int timeout) throws SQLException {
        Duration before = session.readTimeout();
        boolean limited = false;
        try {
            limited = timeout > 0 && bounded(session, Duration.ofSeconds(timeout));
            if (session.granted().contains(Capability.PING)) {
                session.controls().ping();
            } else {
                checked(session);
            }
            return true;
        } catch (TurborelException failed) {
            return false;
        } finally {
            if (limited && !session.isLost()) {
                session.setReadTimeout(before);
            }
        }
    }

    /** The check without Ping: a query that, of no transaction active, ends with its statement. */
    private static void checked(Connection session) throws TurborelException {
        boolean on = session.autocommit();
        if (!session.inTransaction()) {
            session.setAutocommit(true);
        }
        try {
            session.statements().query(CHECK);
        } finally {
            session.setAutocommit(on);
        }
    }

    /** Bounds the session's reads by {@code within}, if its stream is the driver's to bound. */
    private static boolean bounded(Connection session, Duration within) {
        try {
            session.setReadTimeout(within);
            return true;
        } catch (TurborelException programs) {
            return false;
        }
    }
}
