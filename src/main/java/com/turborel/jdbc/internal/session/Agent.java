package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Broken;
import com.turborel.jdbc.internal.codec.Completed;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An SQL-agent ([CLIENT The SQL-agent]; ISO/IEC 9075:1992 §15): a client's connections to servers,
 * each named, one current, and the statements that make, choose and end them, CONNECT, SET
 * CONNECTION and DISCONNECT, which it runs itself; every other statement goes to the current
 * connection's session. A {@link Connector} makes a connection to a server an address names, as
 * the user a statement names, proved as the program says. As Turborel's {@code client/src/agent.rs}.
 */
public final class Agent {
    /** The SQLSTATE of no connection of the name: connection does not exist. */
    private static final String CONNECTION_DOES_NOT_EXIST = "08003";

    /** The SQLSTATE of a transaction that would span servers. */
    private static final String MULTIPLE_SERVER_TRANSACTIONS = "0A001";

    /** The SQLSTATE of a user that is no authorization identifier. */
    private static final String INVALID_AUTHORIZATION = "28000";

    /** The SQLSTATE of a connection name that is no identifier. */
    private static final String INVALID_CONNECTION_NAME = "2E000";

    /** The SQLSTATE of a connection name in use. */
    private static final String CONNECTION_NAME_IN_USE = "08002";

    /** The SQLSTATE of a server that cannot be reached. */
    private static final String UNABLE_TO_CONNECT = "08001";

    /** The SQLSTATE of a server that refuses the connection. */
    private static final String CONNECTION_REJECTED = "08004";

    /** The SQLSTATE of a connection that failed. */
    private static final String CONNECTION_FAILURE = "08006";

    /** The SQLSTATE of a warning: an error ending a connection. */
    private static final String DISCONNECT_ERROR = "01002";

    /** The SQLSTATE of success. */
    private static final String SUCCESSFUL_COMPLETION = "00000";

    /** The SQLCODE and SQLSTATE of an invalid transaction state. */
    private static final int TRANSACTION_STATE = -917;

    private static final String INVALID_TRANSACTION_STATE = "25000";

    /** Where no connection is: no current one, or none of a name. */
    private static final int NONE = -1;

    /** The SQLCODEs of an access rule violation, and of a feature not supported. */
    private static final int PRIVILEGE = -104;

    private static final int UNSUPPORTED = -900;

    /** What makes an agent's connections. */
    @FunctionalInterface
    public interface Connector {
        /**
         * A connection to the server {@code server} names, an address, or for none the default
         * server; as {@code user}, or for none the user the connector takes (ISO/IEC 9075:1992
         * §15.1 GR8, SR1).
         *
         * @param server the address, or none for the default server
         * @param user the user, or none
         * @return the connection
         * @throws TurborelException the server cannot be reached, or refuses the connection
         */
        Connection connect(@Nullable String server, @Nullable String user) throws TurborelException;
    }

    /** A connection of an agent: its name, none for the default connection. */
    private record Held(@Nullable String name, Connection connection) {}

    private final Connector connector;
    private final List<Held> held = new ArrayList<>();
    private int current = NONE;

    /**
     * An agent with no connection.
     *
     * @param connector what makes its connections
     */
    public Agent(Connector connector) {
        this.connector = connector;
    }

    /**
     * An agent whose default connection, current, is {@code connection}: as a client begins,
     * connected.
     *
     * @param connector what makes its connections
     * @param connection its default connection
     * @return the agent
     */
    public static Agent connected(Connector connector, Connection connection) {
        Agent agent = new Agent(connector);
        agent.held.add(new Held(null, connection));
        agent.current = 0;
        return agent;
    }

    /** {@code state}'s connection exception, of {@code message}. */
    private static TurborelException exception(String state, String message) {
        int code = state.startsWith("28") ? PRIVILEGE : state.startsWith("0A") ? UNSUPPORTED : Broken.SQLCODE;
        return new TurborelException(code, state, message);
    }

    /** The character string {@code value} is, its spaces trimmed (ISO/IEC 9075:1992 §15.1 GR2, GR6). */
    private static String text(ConnectionStatement.Value value) throws TurborelException {
        if (value instanceof ConnectionStatement.Value.Text text) {
            String s = text.text();
            int start = 0;
            int end = s.length();
            while (start < end && s.charAt(start) == ' ') {
                start++;
            }
            while (end > start && s.charAt(end - 1) == ' ') {
                end--;
            }
            return s.substring(start, end);
        }
        throw new TurborelException(
                ConnectionStatement.SYNTAX_RULE,
                ConnectionStatement.SYNTAX_ERROR,
                "[CLIENT The SQL-agent] a client's connection statement names by character string literals");
    }

    private static @Nullable String textOf(ConnectionStatement.@Nullable Value value) throws TurborelException {
        return value == null ? null : text(value);
    }

    private static Completed done(String state) {
        return new Completed(0, state, false, Completed.Fields.EMPTY);
    }

    /**
     * The current connection.
     *
     * @return the connection
     * @throws TurborelException SQLSTATE 08003 if there is none
     */
    public Connection current() throws TurborelException {
        if (current < 0) {
            throw exception(CONNECTION_DOES_NOT_EXIST, "[ISO/IEC 9075:1992 §15.3 GR9] there is no current connection");
        }
        return held.get(current).connection();
    }

    /**
     * The current connection, if there is one, to read what its server last told it.
     *
     * @return the connection, or none
     */
    public @Nullable Connection currentConnection() {
        return current < 0 ? null : held.get(current).connection();
    }

    /**
     * What cancels the current connection's statement, if any.
     *
     * @return the canceller, or none
     */
    public @Nullable Canceller canceller() {
        Connection connection = currentConnection();
        return connection == null ? null : connection.canceller();
    }

    /**
     * The SQLSTATE of the warning the current connection's last statement completed with, if it
     * did.
     *
     * @return the SQLSTATE, or none
     */
    public @Nullable String warning() {
        Connection connection = currentConnection();
        return connection == null ? null : connection.warning();
    }

    /**
     * Whether the current connection is lost: none is not.
     *
     * @return whether lost
     */
    public boolean isLost() {
        Connection connection = currentConnection();
        return connection != null && connection.isLost();
    }

    /**
     * The current connection's name, none for the default one or for no connection.
     *
     * @return the name, or none
     */
    public @Nullable String currentName() {
        return current < 0 ? null : held.get(current).name();
    }

    /**
     * Runs {@code sql}: a connection statement itself, its completion; any other in the current
     * connection's session, as {@link Statements#query}.
     *
     * @param sql the statement
     * @return its outcome
     * @throws TurborelException a connection statement's, or the statement's
     */
    public Outcome query(String sql) throws TurborelException {
        Completed done = connectionStatement(sql);
        if (done != null) {
            return new Outcome.Done(done);
        }
        return current().statements().query(sql);
    }

    /**
     * Runs {@code sql} as {@link #query} does, a query's rows given to {@code sink}, as {@link
     * Statements#queryInto}.
     *
     * @param sql the statement
     * @param sink what takes the rows
     * @return how many rows went to the sink, or a completion
     * @throws TurborelException as {@link #query}'s
     */
    public Streamed queryInto(String sql, Sink sink) throws TurborelException {
        Completed done = connectionStatement(sql);
        if (done != null) {
            return new Streamed.Done(done);
        }
        return current().statements().queryInto(sql, sink);
    }

    /**
     * {@code sql}, if a connection statement, run: its completion; none if it is another.
     *
     * @param sql the statement
     * @return the completion, or none
     * @throws TurborelException the statement's, as each rule says
     */
    public @Nullable Completed connectionStatement(String sql) throws TurborelException {
        ConnectionStatement statement = ConnectionStatement.of(sql);
        if (statement instanceof ConnectionStatement.Connect connect) {
            return connect(connect.server(), connect.name(), connect.user());
        }
        if (statement instanceof ConnectionStatement.ConnectDefault) {
            return connect(null, null, null);
        }
        if (statement instanceof ConnectionStatement.Set set) {
            return setConnection(set.name());
        }
        if (statement instanceof ConnectionStatement.Disconnect disconnect) {
            return disconnect(disconnect);
        }
        return null;
    }

    /** Refuses to leave the current connection's active transaction: there are no transactions of more than one server. */
    private void notInTransaction() throws TurborelException {
        Connection connection = currentConnection();
        if (connection != null && connection.inTransaction()) {
            throw exception(
                    MULTIPLE_SERVER_TRANSACTIONS,
                    "[ISO/IEC 9075:1992 §15.1 GR1, §15.2 GR1] the current connection's transaction is active, and none"
                            + " spans servers");
        }
    }

    /** The connection named {@code name}, none for the default one, if held. */
    private int find(@Nullable String name) {
        for (int i = 0; i < held.size(); i++) {
            if (Objects.equals(held.get(i).name(), name)) {
                return i;
            }
        }
        return NONE;
    }

    /**
     * CONNECT TO a server [AS a name] [USER a user], or for no server TO DEFAULT: the connection
     * made current, the one current before dormant (ISO/IEC 9075:1992 §15.1).
     */
    private Completed connect(
            ConnectionStatement.@Nullable Value server,
            ConnectionStatement.@Nullable Value name,
            ConnectionStatement.@Nullable Value user)
            throws TurborelException {
        notInTransaction();
        String who = textOf(user);
        if (who != null && SqlNames.authorizationIdentifier(who) == null) {
            throw exception(
                    INVALID_AUTHORIZATION,
                    "[ISO/IEC 9075:1992 §15.1 GR3] '" + who + "' is not an <authorization identifier>");
        }
        String address = textOf(server);
        String named = name != null ? text(name) : address;
        String identified = null;
        if (named != null) {
            identified = SqlNames.authorizationIdentifier(named);
            if (identified == null) {
                throw exception(
                        INVALID_CONNECTION_NAME,
                        "[ISO/IEC 9075:1992 §15.1 GR6] '" + named + "' is not an <identifier>");
            }
        }
        if (find(identified) >= 0) {
            String what = identified == null ? "the default connection" : "the connection " + identified;
            throw exception(CONNECTION_NAME_IN_USE, "[ISO/IEC 9075:1992 §15.1 GR7] " + what + " is established");
        }
        Connection connection;
        try {
            connection = connector.connect(address, who);
        } catch (TurborelException failure) {
            String state = CONNECTION_FAILURE.equals(failure.getSQLState()) ? UNABLE_TO_CONNECT : CONNECTION_REJECTED;
            TurborelException refused =
                    exception(state, "[ISO/IEC 9075:1992 §15.1 GR10, GR11] " + failure.getMessage());
            refused.initCause(failure);
            throw refused;
        }
        held.add(new Held(identified, connection));
        current = held.size() - 1;
        return done(SUCCESSFUL_COMPLETION);
    }

    /**
     * SET CONNECTION a name, or DEFAULT for none: that connection current, the one current before
     * dormant (ISO/IEC 9075:1992 §15.2).
     */
    private Completed setConnection(ConnectionStatement.@Nullable Value value) throws TurborelException {
        notInTransaction();
        String name = name(value);
        int index = find(name);
        if (index < 0) {
            String what = name == null ? "no default connection" : "no connection " + name;
            throw exception(CONNECTION_DOES_NOT_EXIST, "[ISO/IEC 9075:1992 §15.2 GR2] there is " + what);
        }
        current = index;
        return done(SUCCESSFUL_COMPLETION);
    }

    /** The name a connection object gives, as an identifier if it is one; none for DEFAULT. */
    private static @Nullable String name(ConnectionStatement.@Nullable Value value) throws TurborelException {
        String n = textOf(value);
        if (n == null) {
            return null;
        }
        String identified = SqlNames.authorizationIdentifier(n);
        return identified == null ? n : identified;
    }

    /**
     * DISCONNECT an object: each connection it names ended; none current after, if the current one
     * was (ISO/IEC 9075:1992 §15.3).
     */
    private Completed disconnect(ConnectionStatement.Disconnect statement) throws TurborelException {
        List<Integer> ended = new ArrayList<>();
        ConnectionStatement.Disconnected object = statement.object();
        if (object == ConnectionStatement.Disconnected.ALL) {
            for (int i = 0; i < held.size(); i++) {
                ended.add(i);
            }
        } else if (object == ConnectionStatement.Disconnected.CURRENT) {
            if (current < 0) {
                throw missing("no current connection");
            }
            ended.add(current);
        } else if (object == ConnectionStatement.Disconnected.DEFAULT) {
            ended.add(found(null, "no default connection"));
        } else {
            String name = name(statement.name());
            ended.add(found(name, "no connection " + name));
        }
        for (int i : ended) {
            if (held.get(i).connection().inTransaction()) {
                throw new TurborelException(
                        TRANSACTION_STATE,
                        INVALID_TRANSACTION_STATE,
                        "[ISO/IEC 9075:1992 §15.3 GR6] a connection's transaction is active");
            }
        }
        String currentName = current < 0 ? null : held.get(current).name();
        boolean hadCurrent = current >= 0;
        boolean warned = false;
        List<Held> kept = new ArrayList<>();
        for (int i = 0; i < held.size(); i++) {
            if (ended.contains(i)) {
                try {
                    held.get(i).connection().terminate();
                } catch (TurborelException failed) {
                    warned = true;
                }
            } else {
                kept.add(held.get(i));
            }
        }
        held.clear();
        held.addAll(kept);
        current = hadCurrent ? find(currentName) : NONE;
        return done(warned ? DISCONNECT_ERROR : SUCCESSFUL_COMPLETION);
    }

    private int found(@Nullable String name, String what) throws TurborelException {
        int index = find(name);
        if (index < 0) {
            throw missing(what);
        }
        return index;
    }

    private static TurborelException missing(String what) {
        return exception(CONNECTION_DOES_NOT_EXIST, "[ISO/IEC 9075:1992 §15.3 GR1-GR3] there is " + what);
    }

    /**
     * Ends every connection, as the client ends.
     *
     * @throws TurborelException the first failure ending one; every one is ended still
     */
    public void terminate() throws TurborelException {
        TurborelException first = null;
        for (Held each : held) {
            try {
                each.connection().terminate();
            } catch (TurborelException failed) {
                if (first == null) {
                    first = failed;
                }
            }
        }
        held.clear();
        current = NONE;
        if (first != null) {
            throw first;
        }
    }
}
