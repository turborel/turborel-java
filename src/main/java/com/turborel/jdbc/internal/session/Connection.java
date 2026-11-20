package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.auth.Mechanism;
import com.turborel.jdbc.internal.auth.TlsChannel;
import com.turborel.jdbc.internal.codec.Broken;
import com.turborel.jdbc.internal.codec.CancelKey;
import com.turborel.jdbc.internal.codec.Completed;
import com.turborel.jdbc.internal.codec.FrameHeader;
import com.turborel.jdbc.internal.codec.Reply;
import com.turborel.jdbc.internal.codec.ReplyCodec;
import com.turborel.jdbc.internal.codec.Request;
import com.turborel.jdbc.internal.transport.Duplex;
import com.turborel.jdbc.internal.transport.Opened;
import com.turborel.jdbc.internal.transport.Target;
import com.turborel.jdbc.internal.transport.Timeouts;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * A connection to a server, one request at a time ([PROTOCOL Opening a connection], [CLIENT
 * Connecting]): opened by Hello and logged in by its mechanism, its session's settings and notices
 * taken as they come, ended by Terminate. Its {@link #statements} run statements; its {@link
 * #controls} ping, reset and change its session; {@link #told} gives what the last statement's
 * completion told. Every failure is a {@link TurborelException}: the server's own, or one of the
 * connection, SQLCODE -919 and SQLSTATE class 08, after which the connection takes no more. As
 * Turborel's {@code client/src/connection.rs}.
 */
public final class Connection implements AutoCloseable {
    /** A {@code u32}'s bits: a statement's number wraps past them. */
    private static final long U32_MASK = 0xFFFF_FFFFL;

    /** The class of a warning's SQLSTATE. */
    private static final String WARNING_CLASS = "01";

    private final Wire wire;
    private final boolean bounded;
    private final Told told = new Told();
    private Timeouts timeouts;
    private int maxBody = FrameHeader.MAX_BODY;
    private @Nullable TurborelException lost;
    private @Nullable CancelKey key;
    private @Nullable Target reach;
    private String authorization = "";
    private String server = "";
    private boolean active;
    private boolean autocommit;
    private @Nullable String warning;
    private long nextStatement;
    private final Statements statements = new Statements(this);
    private final Controls controls = new Controls(this, statements);

    private Connection(Duplex duplex, boolean bounded, Timeouts timeouts) {
        this.wire = new Wire(duplex);
        this.bounded = bounded;
        this.timeouts = timeouts;
    }

    /**
     * Opens a connection over a stream the program made, as {@code user}, authenticated by {@code
     * mechanism}, asking for what {@code offer} asks. The program answers for whether the stream
     * keeps a secret mechanism's bytes private, and bounds its waits as it made it.
     *
     * @param duplex the stream
     * @param user the user, read as an identifier
     * @param mechanism what proves the user
     * @param offer what the connection asks for
     * @return the connection, logged in
     * @throws TurborelException the server's refusal, SQLSTATE 28000 for credentials refused or
     *     08004 for a connection refused; a connection failure
     */
    public static Connection open(Duplex duplex, String user, Mechanism mechanism, Offer offer)
            throws TurborelException {
        Connection connection = new Connection(duplex, false, new Timeouts(null, null));
        try {
            connection.logIn(user, mechanism, offer);
            return connection;
        } catch (TurborelException failed) {
            duplex.close();
            throw failed;
        }
    }

    /**
     * Opens a connection over a stream made to a target, its waits bounded as {@code timeouts}
     * say: each step of the login by {@code connect}, each read after by {@code read}; a mechanism
     * that binds itself to its TLS channel bound to it first.
     *
     * @param opened the stream, and its TLS channel if any
     * @param login the user and what proves it
     * @param timeouts how long it waits
     * @param offer what it asks for
     * @param reach where the server is, to reach it again to cancel
     * @return the connection, logged in
     * @throws TurborelException as {@link #open}; a connection failure if a wait runs out
     */
    static Connection openTimed(Opened opened, Login login, Timeouts timeouts, Offer offer, Target reach)
            throws TurborelException {
        TlsChannel channel = opened.channel();
        if (channel != null) {
            login.mechanism().bind(channel);
        }
        Connection connection = new Connection(opened.duplex(), true, timeouts);
        connection.reach = reach;
        try {
            opened.duplex().bound(timeouts.connect());
            connection.logIn(login.user(), login.mechanism(), offer);
            opened.duplex().bound(timeouts.read());
            return connection;
        } catch (TurborelException failed) {
            opened.duplex().close();
            throw failed;
        }
    }

    /** Sends Hello, then answers each Challenge until Ready, as [PROTOCOL Opening a connection]. */
    private void logIn(String user, Mechanism mechanism, Offer offer) throws TurborelException {
        String name = mechanism.name();
        send(new Request.Hello(user, name, mechanism.initial(), offer.capabilities(), offer.attributes()));
        while (true) {
            Reply reply = receive();
            if (reply instanceof Reply.Challenge challenge) {
                send(new Request.Answer(mechanism.answer(challenge.challenge())));
            } else if (reply instanceof Reply.Ready ready) {
                mechanism.ready();
                authorization = ready.authorization();
                server = ready.server();
                key = ready.key();
                told.grant(ready.capabilities(), offer.compressFrom());
                return;
            } else if (reply instanceof Reply.Error error) {
                mechanism.refused();
                throw failed(error);
            } else {
                throw lose("[PROTOCOL Opening a connection] the server sent no Ready");
            }
        }
    }

    /**
     * How long each read of the server waits from now on; none, no limit ([CLIENT Timeouts]).
     *
     * @param within the wait, or none
     * @throws TurborelException a connection failure if the system cannot set it, or the
     *     connection was made over a stream the program gave, which it bounds
     */
    public void setReadTimeout(@Nullable Duration within) throws TurborelException {
        if (!bounded) {
            throw Broken.connection("[CLIENT Timeouts] a stream the program made is bounded by the program");
        }
        wire.duplex().bound(within);
        timeouts = new Timeouts(timeouts.connect(), within);
    }

    /**
     * How long each read of the server waits; none, no limit.
     *
     * @return the read timeout, or none
     */
    public @Nullable Duration readTimeout() {
        return timeouts.read();
    }

    /**
     * Takes replies of at most this many bytes of body from now on: 16 MiB, the protocol's
     * longest, unless this says. A longer one loses the connection ([CLIENT Timeouts]).
     *
     * @param bytes the longest body taken
     */
    public void setMaxBody(int bytes) {
        maxBody = bytes;
    }

    /**
     * The session's authorization identifier, as Ready told it.
     *
     * @return the authorization identifier
     */
    public String authorization() {
        return authorization;
    }

    /**
     * The server's product, version and codename, as {@code turborel 0.0.0 (alpha)}.
     *
     * @return the server
     */
    public String server() {
        return server;
    }

    /**
     * Whether the session's transaction is active, as the server said after the last statement.
     *
     * @return whether a transaction is active
     */
    public boolean inTransaction() {
        return active;
    }

    /**
     * Whether each statement run, executed or queried is its own transaction from now on, if
     * {@code on} ([CLIENT Running statements]): the server commits it once the statement succeeds,
     * or rolls it back if it fails, within the request, so no COMMIT is sent. Off as a connection
     * opens: a transaction begins with the first statement and lasts until COMMIT or ROLLBACK. A
     * cursor is read within its transaction, which ends as the cursor does if the server granted
     * {@code fetch-ends}.
     *
     * @param on whether each statement is its own transaction
     */
    public void setAutocommit(boolean on) {
        autocommit = on;
    }

    /**
     * Whether each statement is its own transaction.
     *
     * @return whether autocommit is on
     */
    public boolean autocommit() {
        return autocommit;
    }

    /**
     * The SQLSTATE of the warning, of class 01, the last statement completed with, as its Complete
     * said: of a query too, once all its rows were read.
     *
     * @return the SQLSTATE, or none
     */
    public @Nullable String warning() {
        return warning;
    }

    /**
     * What runs the connection's statements.
     *
     * @return its statements
     */
    public Statements statements() {
        return statements;
    }

    /**
     * What pings, resets and changes the connection's session.
     *
     * @return its controls
     */
    public Controls controls() {
        return controls;
    }

    /**
     * What the server has told the connection beside its replies ([CLIENT What the server tells]).
     *
     * @return what it told
     */
    public Told told() {
        return told;
    }

    /** The number the next statement prepared takes, a {@code u32}, counted from 0. */
    long nextStatement() {
        long number = nextStatement;
        nextStatement = (nextStatement + 1) & U32_MASK;
        return number;
    }

    /** The connection's frames, for a request the low-level client has no method of, as a copy's. */
    Wire wire() {
        return wire;
    }

    /** Takes a ChangeUser's Ready: the session renewed, its user, server and cancel key. */
    void renewed(Reply.Ready ready) {
        authorization = ready.authorization();
        server = ready.server();
        key = ready.key();
    }

    /**
     * Where the connection reached its server: none for a stream the program gave.
     *
     * @return the target, or none
     */
    public @Nullable Target target() {
        return reach;
    }

    /**
     * What cancels the statement this connection runs, from another thread ([PROTOCOL Cancel
     * 'Z']): none for a connection over a stream the program gave, whose server it cannot reach
     * again.
     *
     * @return the canceller, or none
     */
    public @Nullable Canceller canceller() {
        Target to = reach;
        CancelKey cancels = key;
        return to == null || cancels == null ? null : new Canceller(to, cancels);
    }

    /**
     * What the server granted of what the connection asked for ([CLIENT What a connection asks
     * for]).
     *
     * @return the capabilities granted
     */
    public Set<String> granted() {
        return told.granted();
    }

    /**
     * Fails before anything is sent unless {@code ok} or the server granted a capability
     * ([PROTOCOL Capabilities]).
     *
     * @param ok whether the request needs no capability as it is
     * @param capability the capability it needs
     * @throws TurborelException a connection failure if the capability was not granted
     */
    void needs(boolean ok, String capability) throws TurborelException {
        if (!ok && !told.has(capability)) {
            throw Broken.connection("[PROTOCOL Capabilities] the server did not grant " + capability);
        }
    }

    /**
     * The value of one of the session's settings, as the server last told it, with no round trip
     * ([CLIENT What the server tells]): AUTHORIZATION, SCHEMA, READ_ONLY, SERVER_READ_ONLY,
     * CATALOG, OWNER, SERVER_VERSION and every setting a session may set.
     *
     * @param name the setting's name
     * @return its value, as SQL writes it, or none if the server told none of that name
     */
    public @Nullable String setting(String name) {
        return told.setting(name);
    }

    /**
     * Every setting the server told, by name.
     *
     * @return the settings, a copy
     */
    public Map<String, String> settings() {
        return told.settings();
    }

    /**
     * Who takes each notice the server sends from now on; none, they are kept, the last 64.
     *
     * @param handler the handler, or none
     */
    public void setNoticeHandler(@Nullable Consumer<Reply.Notice> handler) {
        told.handler(handler);
    }

    /**
     * The notices kept, with no handler set, taken.
     *
     * @return the notices, oldest first
     */
    public List<Reply.Notice> takeNotices() {
        return told.takeNotices();
    }

    /**
     * Ends the connection once the server has ended its session as SESSION.COMMIT_AT_END says and
     * closed it ([PROTOCOL Terminate 'X']): waiting at most the read timeout, else 60 seconds.
     *
     * @throws TurborelException a connection failure if Terminate cannot be sent or the server does
     *     not close in time; the server's error, if a commit at the session's end fails
     */
    public void terminate() throws TurborelException {
        try {
            send(new Request.Terminate());
            if (bounded) {
                Duration read = timeouts.read();
                wire.duplex().bound(read == null ? Timeouts.TERMINATE_WITHIN : read);
            }
            byte[] rest = wire.rest(maxBody + FrameHeader.BYTES);
            if (rest.length >= FrameHeader.BYTES) {
                answered(rest);
            }
        } finally {
            close();
        }
    }

    /** What the server sent as it closed: an Error is the commit's failure, a notice or status nothing. */
    private void answered(byte[] rest) throws TurborelException {
        FrameHeader header = FrameHeader.decode(Arrays.copyOf(rest, FrameHeader.BYTES), maxBody);
        int end = Math.min(rest.length, FrameHeader.BYTES + header.length());
        Reply reply = ReplyCodec.decode(header.kind(), Arrays.copyOfRange(rest, FrameHeader.BYTES, end));
        if (reply instanceof Reply.Error error) {
            throw error.failure().toException();
        }
        if (!(reply instanceof Reply.Notice || reply instanceof Reply.Status || reply instanceof Reply.Pong)) {
            throw Broken.connection("[PROTOCOL Terminate 'X'] the server answered Terminate");
        }
    }

    /**
     * Whether the connection is lost: it takes no more.
     *
     * @return whether lost
     */
    public boolean isLost() {
        return lost != null;
    }

    /** Closes the connection's stream at once, without Terminate: the server then ends its session. */
    @Override
    public void close() {
        if (lost == null) {
            lost = Broken.connection("[DESIGN] D9 the connection is closed");
        }
        wire.duplex().close();
    }

    /**
     * Sends a request, compressed once compression is granted. A request the codec refuses, as one
     * of more attributes than a message takes, is refused before anything is sent, and the
     * connection kept.
     *
     * @param request the request
     * @throws TurborelException the codec's refusal; the failure that lost the connection, before
     *     or as it is written
     */
    void send(Request request) throws TurborelException {
        TurborelException was = lost;
        if (was != null) {
            throw was;
        }
        byte[] frames = request.encode();
        try {
            wire.write(frames, told.compress());
        } catch (TurborelException failure) {
            throw lostBy(failure);
        }
    }

    /**
     * The server's next reply, each Notice and Status before it taken ([PROTOCOL Conversations]).
     *
     * @return the reply
     * @throws TurborelException a connection failure, which loses the connection
     */
    Reply receive() throws TurborelException {
        while (true) {
            Reply reply;
            try {
                reply = wire.receive(maxBody, told.compress() != null);
            } catch (TurborelException failure) {
                throw lostBy(failure);
            }
            if (reply instanceof Reply.Notice notice) {
                told.notice(notice);
            } else if (reply instanceof Reply.Status status) {
                told.status(status.name(), status.value());
            } else {
                return noted(reply);
            }
        }
    }

    /** The reply, its statement's end noted: whether a transaction goes on, and what it completed with. */
    private Reply noted(Reply reply) {
        if (reply instanceof Reply.Complete complete) {
            Completed c = complete.completed();
            active = c.active();
            warning = c.sqlstate().startsWith(WARNING_CLASS) ? c.sqlstate() : null;
            told.completed(c.fields());
        } else if (reply instanceof Reply.Error error) {
            active = error.failure().active();
            warning = null;
        } else if (reply instanceof Reply.Ready) {
            // Ready ends a login or a ChangeUser, which renews the session as Reset does.
            active = false;
            warning = null;
        } else if (opensTransaction(reply)) {
            active = true;
            if (reply instanceof Reply.Description) {
                warning = null;
            }
        }
        return reply;
    }

    /** Whether a reply means a transaction is active: a query's rows are read in one, a locator lives in one. */
    private static boolean opensTransaction(Reply reply) {
        boolean rows =
                reply instanceof Reply.Description || reply instanceof Reply.Rows || reply instanceof Reply.Suspended;
        return rows || reply instanceof Reply.LobData || reply instanceof Reply.LobLocator;
    }

    /**
     * The failure an Error carries, the connection lost if it ends it ([PROTOCOL Error 'E']).
     *
     * @param error the Error
     * @return the failure, for the caller to throw
     */
    TurborelException failed(Reply.Error error) {
        TurborelException failure = error.failure().toException();
        if (error.failure().endsConnection()) {
            lost = failure;
            wire.duplex().close();
        }
        return failure;
    }

    /**
     * Loses the connection, for a connection failure.
     *
     * @param message what went wrong, beginning with the rule it cites
     * @return the failure, for the caller to throw
     */
    TurborelException lose(String message) {
        return lostBy(Broken.connection(message));
    }

    private TurborelException lostBy(TurborelException failure) {
        lost = failure;
        wire.duplex().close();
        return failure;
    }
}
