package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.auth.Mechanism;
import com.turborel.jdbc.internal.codec.Attribute;
import com.turborel.jdbc.internal.codec.Column;
import com.turborel.jdbc.internal.codec.Reply;
import com.turborel.jdbc.internal.codec.Request;
import com.turborel.jdbc.internal.codec.Value;
import java.util.ArrayList;
import java.util.List;

/**
 * What a connection asks of its session beyond statements, as the server granted it ([CLIENT
 * Controlling the session]): a ping, a reset, a change of user, the attributes each later
 * statement carries, and a Query of several statements. As Turborel's {@code client/src/session.rs}.
 */
public final class Controls {
    private final Connection connection;
    private final Statements statements;

    Controls(Connection connection, Statements statements) {
        this.connection = connection;
        this.statements = statements;
    }

    /**
     * Whether the server and the connection are alive, with no statement run and no worker of the
     * server's taken ([PROTOCOL Ping 'G']).
     *
     * @throws TurborelException a connection failure: {@code ping} not granted, or the server gone
     */
    public void ping() throws TurborelException {
        connection.needs(false, Capability.PING);
        connection.send(new Request.Ping());
        Reply reply = connection.receive();
        if (!(reply instanceof Reply.Pong)) {
            throw statements.unexpected(reply, "the server sent no Pong");
        }
    }

    /**
     * The session back to its state at login ([PROTOCOL Reset 'W']): its transaction rolled back,
     * told by notice 01T01, its prepared statements, cursors, temporary tables and settings gone,
     * its user and cancel key kept. Statements prepared before are of no use after.
     *
     * @throws TurborelException a connection failure: {@code reset} not granted, or the server gone
     */
    public void reset() throws TurborelException {
        connection.needs(false, Capability.RESET);
        connection.send(new Request.Reset());
        statements.completion();
    }

    /**
     * The connection authenticated again as {@code user} by {@code mechanism}, its session renewed
     * as that user's, as {@link #reset} renews it, under a new cancel key, no transaction active
     * after it ([PROTOCOL ChangeUser 'U']).
     *
     * @param user the user, read as an identifier
     * @param mechanism what proves the user
     * @throws TurborelException the server's refusal, which closes the connection; a connection
     *     failure: {@code change-user} not granted, or the server gone
     */
    public void changeUser(String user, Mechanism mechanism) throws TurborelException {
        connection.needs(false, Capability.CHANGE_USER);
        connection.send(new Request.ChangeUser(user, mechanism.name(), mechanism.initial()));
        while (true) {
            Reply reply = connection.receive();
            if (reply instanceof Reply.Challenge challenge) {
                connection.send(new Request.Answer(mechanism.answer(challenge.challenge())));
            } else if (reply instanceof Reply.Ready ready) {
                mechanism.ready();
                connection.renewed(ready);
                return;
            } else {
                throw statements.unexpected(reply, "the server sent no Ready");
            }
        }
    }

    /**
     * Attributes carried by each later Query and Execute, until set again, read in SQL by
     * QUERY_ATTRIBUTE(name); {@code traceparent} is the request's trace context, which the server's
     * record of a slow statement carries. None, for none.
     *
     * @param attributes the attributes, by name and value
     * @throws TurborelException a connection failure if {@code query-attributes} was not granted,
     *     for attributes not none
     */
    public void setQueryAttributes(List<Attribute> attributes) throws TurborelException {
        connection.needs(attributes.isEmpty(), Capability.QUERY_ATTRIBUTES);
        connection.told().attributes(attributes);
    }

    /**
     * Runs {@code sql}, several statements, in one request ([PROTOCOL Query 'Q']): the server runs
     * each in order, each ending its transaction under autocommit; the first that fails stops the
     * rest.
     *
     * @param sql the statements
     * @return each statement's outcome, and the failure that stopped the rest
     * @throws TurborelException a connection failure: {@code multi-statement} not granted, or the
     *     server gone
     */
    public Ran queryMany(String sql) throws TurborelException {
        connection.needs(false, Capability.MULTI_STATEMENT);
        connection.send(statements.queryRequest(sql, true));
        List<Outcome> outcomes = new ArrayList<>();
        while (true) {
            Reply first;
            try {
                first = connection.receive();
                if (first instanceof Reply.Complete complete) {
                    outcomes.add(new Outcome.Done(complete.completed()));
                    if (!complete.completed().fields().more()) {
                        return new Ran(outcomes, null);
                    }
                } else if (rowsOf(statements.description(first), outcomes)) {
                    return new Ran(outcomes, null);
                }
            } catch (TurborelException failure) {
                if (connection.isLost()) {
                    throw failure;
                }
                return new Ran(outcomes, failure);
            }
        }
    }

    /** A query's rows of a Query of several, its outcome added: whether no statement follows. */
    private boolean rowsOf(List<Column> columns, List<Outcome> outcomes) throws TurborelException {
        List<List<Value>> rows = new ArrayList<>();
        while (true) {
            Reply reply = connection.receive();
            if (reply instanceof Reply.Rows batch) {
                rows.addAll(batch.rows());
            } else if (reply instanceof Reply.Complete complete) {
                outcomes.add(new Outcome.Rows(columns, rows));
                return !complete.completed().fields().more();
            } else {
                throw statements.unexpected(reply, "the server broke off its rows");
            }
        }
    }
}
