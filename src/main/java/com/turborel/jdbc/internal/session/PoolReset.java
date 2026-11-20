package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Broken;
import com.turborel.jdbc.internal.transport.Timeouts;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A pooled connection's check and reset ([CLIENT Pools]). Granted {@code ping}, {@link #answers}
 * sends Ping, which the server answers with no worker and no statement. Granted {@code reset},
 * {@link #reset} sends Reset, which ends all the session holds in one request, its transaction,
 * prepared statements, cursors, temporary tables and settings, and keeps its user; then the
 * program's own statements run.
 *
 * <p>A server that grants neither is checked and reset by statements: {@link #answers} sends
 * ROLLBACK, which a session outside a transaction completes doing nothing; {@link #reset} gives the
 * session back as it logged in, in one pipeline: its transaction rolled back, the statements it
 * prepared closed and destroyed, its user, schema and zone those of its login, then the program's
 * own statements. A setting a borrower SET stays, as no statement sets every setting back. Either
 * way autocommit is off after. As Turborel's {@code client/src/pool/reset.rs}.
 */
final class PoolReset {
    /**
     * How long a pooled connection's check and reset wait on its server, at most: as long as a step
     * of connecting, so a server that hangs holds the thread giving a connection back no longer.
     */
    static final Duration RESET_WITHIN = Timeouts.CONNECT_WITHIN;

    /** What a pooled connection is checked by, as it is handed out. */
    private static final String CHECK = "ROLLBACK";

    /**
     * The most statements a borrower may leave prepared for the connection to be reset rather than
     * closed, as past it a new one costs less than destroying them.
     */
    static final long RESET_MOST_STATEMENTS = 256;

    /** The requests of each statement released: Close, then Deallocate. */
    private static final int RELEASE_REQUESTS = 2;

    /** A step of a check or a reset. */
    @FunctionalInterface
    private interface Step {
        void run(Connection connection) throws TurborelException;
    }

    private PoolReset() {}

    /**
     * Whether {@code connection} answers.
     *
     * @throws TurborelException a connection failure, if it is lost or does not answer
     */
    static void answers(Connection connection) throws TurborelException {
        if (connection.granted().contains(Capability.PING)) {
            bounded(connection, c -> c.controls().ping());
            return;
        }
        bounded(connection, c -> c.statements().query(CHECK));
    }

    /**
     * {@code run} of {@code connection}, each of its waits at most {@link #RESET_WITHIN}, or the
     * connection's own limit if shorter; the limit put back after. A connection over a stream its
     * maker bounds is run as it is bounded.
     */
    private static void bounded(Connection connection, Step run) throws TurborelException {
        @Nullable Duration before = connection.readTimeout();
        Duration within = before == null || before.compareTo(RESET_WITHIN) > 0 ? RESET_WITHIN : before;
        boolean limited = true;
        try {
            connection.setReadTimeout(within);
        } catch (TurborelException unbounded) {
            limited = false;
        }
        run.run(connection);
        if (limited) {
            connection.setReadTimeout(before);
        }
    }

    /** {@code name} as a character string literal of a delimited identifier, as SET SESSION AUTHORIZATION and SET SCHEMA take one. */
    static String delimited(String name) {
        return "'\"" + name.replace("\"", "\"\"").replace("'", "''") + "\"'";
    }

    /**
     * Gives {@code connection}'s session back as it logged in, and runs {@code more}.
     *
     * @throws TurborelException a connection failure; any statement's failure but those destroying
     *     statements already gone; too many statements left prepared
     */
    static void reset(Connection connection, List<String> more) throws TurborelException {
        bounded(connection, c -> resetNow(c, more));
    }

    private static void resetNow(Connection connection, List<String> more) throws TurborelException {
        if (connection.isLost()) {
            throw Broken.connection("[CLIENT Pools] a lost connection is not given back");
        }
        if (connection.granted().contains(Capability.RESET)) {
            connection.setAutocommit(false);
            connection.controls().reset();
            // Every statement it prepared went with the session it had.
            connection.statementsGone();
            for (String sql : more) {
                connection.statements().query(sql);
            }
            return;
        }
        long prepared = connection.prepared();
        if (prepared > RESET_MOST_STATEMENTS) {
            throw Broken.connection("[CLIENT Pools] too many statements were left prepared to destroy");
        }
        connection.setAutocommit(false);
        String login = connection.authorization();
        Pipeline pipeline = connection.pipeline().query("ROLLBACK");
        for (long number = 0; number < prepared; number++) {
            pipeline.release(number);
        }
        pipeline.query("SET SESSION AUTHORIZATION " + delimited(login));
        pipeline.query("SET SCHEMA " + delimited(login));
        pipeline.query("SET TIME ZONE LOCAL");
        more.forEach(pipeline::query);
        List<Pipeline.Piped> outcomes = pipeline.run();
        int releases = (int) prepared * RELEASE_REQUESTS;
        for (int i = 0; i < outcomes.size(); i++) {
            boolean release = i >= 1 && i <= releases;
            if (!release && outcomes.get(i) instanceof Pipeline.Piped.Failed failed) {
                throw failed.failure();
            }
        }
        connection.statementsGone();
    }
}
