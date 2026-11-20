package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Attribute;
import com.turborel.jdbc.internal.codec.Column;
import com.turborel.jdbc.internal.codec.Completed;
import com.turborel.jdbc.internal.codec.Reply;
import com.turborel.jdbc.internal.codec.Request;
import com.turborel.jdbc.internal.codec.Value;
import java.util.ArrayList;
import java.util.List;

/**
 * A connection's statements ([CLIENT Running statements], [CLIENT Prepared statements]): run
 * directly, their rows read whole or given to a {@link Sink} a batch at a time; or prepared once,
 * their parameters' types and columns described, executed or their cursors opened and read a batch
 * at a time, as often as asked. Granted {@code cursors}, a statement has several cursors open at
 * once, each numbered by the program; granted {@code describe}, a statement or an open cursor is
 * described again. As Turborel's {@code client/src/statements.rs} and {@code connection.rs}.
 */
public final class Statements {
    private final Connection connection;

    Statements(Connection connection) {
        this.connection = connection;
    }

    /**
     * Runs {@code sql}, a direct SQL statement, in the connection's session: a query's rows, or
     * another statement's completion; the transaction ended with it under autocommit.
     *
     * @param sql the statement
     * @return its outcome
     * @throws TurborelException the statement's failure, as the server reports it; a connection
     *     failure
     */
    public Outcome query(String sql) throws TurborelException {
        connection.send(queryRequest(sql, false));
        return queryReply();
    }

    /** The outcome of a Query sent: a query's rows, all of them, or another statement's completion. */
    Outcome queryReply() throws TurborelException {
        Reply first = connection.receive();
        if (first instanceof Reply.Complete complete) {
            return new Outcome.Done(complete.completed());
        }
        List<Column> columns = description(first);
        return new Outcome.Rows(columns, rows().rows());
    }

    /**
     * Runs {@code sql} as {@link #query} does, a query's columns and rows given to {@code sink} as
     * they come, so no more than a batch is held.
     *
     * @param sql the statement
     * @param sink what takes the rows
     * @return how many rows went to the sink, or another statement's completion
     * @throws TurborelException as {@link #query}; a query's failure after some rows went to the sink
     */
    public Streamed queryInto(String sql, Sink sink) throws TurborelException {
        connection.send(queryRequest(sql, false));
        Reply first = connection.receive();
        if (first instanceof Reply.Complete complete) {
            return new Streamed.Done(complete.completed());
        }
        sink.columns(description(first));
        long count = 0;
        while (true) {
            Reply reply = connection.receive();
            if (reply instanceof Reply.Rows batch) {
                count += batch.rows().size();
                sink.rows(batch.rows());
            } else if (reply instanceof Reply.Complete) {
                return new Streamed.Rows(count);
            } else {
                throw unexpected(reply, "the server broke off its rows");
            }
        }
    }

    /** A Query of {@code sql}, ending its transaction under autocommit, with the attributes set. */
    Request.Query queryRequest(String sql, boolean multi) {
        return new Request.Query(sql, connection.autocommit(), connection.told().attributes(), multi);
    }

    /** The columns of a query's Description, its first reply; else the failure it is. */
    List<Column> description(Reply first) throws TurborelException {
        if (first instanceof Reply.Description description) {
            return description.columns();
        }
        throw unexpected(first, "the server sent no result");
    }

    /**
     * Prepares {@code sql}, a preparable statement whose dynamic parameters are written {@code ?}.
     *
     * @param sql the statement
     * @return the statement prepared
     * @throws TurborelException the server's: SQLSTATE 37000 if it is not preparable, or a {@code
     *     ?} takes no type where it stands; a connection failure
     */
    public Statement prepare(String sql) throws TurborelException {
        connection.send(new Request.Prepare(connection.nextStatement(), sql));
        return prepared();
    }

    private Statement prepared() throws TurborelException {
        Reply reply = connection.receive();
        if (reply instanceof Reply.Prepared prepared) {
            return new Statement(prepared.statement(), prepared.parameters(), prepared.columns());
        }
        throw unexpected(reply, "the server sent no Prepared");
    }

    /**
     * Executes {@code statement} with {@code values}: a query's rows, all of them; a CALL's output
     * parameters' values, a row of its columns; or another statement's completion; the transaction
     * ended with it under autocommit.
     *
     * @param statement the statement
     * @param values a value for each parameter
     * @return its outcome
     * @throws TurborelException the server's: SQLSTATE 07001 if the values are not one for each
     *     parameter; the statement's; a connection failure
     */
    public Outcome execute(Statement statement, List<Value> values) throws TurborelException {
        boolean ends = connection.autocommit();
        connection.send(
                executeRequest(statement, values, ends, connection.told().attributes()));
        return executeReply(statement);
    }

    /** An Execute of {@code statement} with {@code values}, all its rows, the transaction ended with it if {@code ends}. */
    static Request.Execute executeRequest(
            Statement statement, List<Value> values, boolean ends, List<Attribute> attributes) {
        return new Request.Execute(statement.number(), values, 0, ends, attributes, 0);
    }

    /** The outcome of an Execute of {@code statement} sent: its rows, all of them, or its completion. */
    Outcome executeReply(Statement statement) throws TurborelException {
        if (statement.columns().isEmpty()) {
            return new Outcome.Done(completion());
        }
        return new Outcome.Rows(statement.columns(), rows().rows());
    }

    /**
     * Opens the cursor of {@code statement}, a query, with {@code values}: at most {@code rows} of
     * its rows, all if 0. The cursor is read within its transaction, which autocommit leaves; but
     * under autocommit, the server granting {@code fetch-ends}, the transaction ends as the cursor
     * does: committed once its last row is read or it is closed, rolled back if a fetch fails.
     *
     * @param statement the statement
     * @param values a value for each parameter
     * @param rows the most rows taken now, 0 for all
     * @return the rows, and whether the cursor has more
     * @throws TurborelException the server's: SQLSTATE 24000 if its cursor is open; those of
     *     {@link #execute}
     */
    public Batch openCursor(Statement statement, List<Value> values, long rows) throws TurborelException {
        return openCursor(new Cursor(statement, 0), values, rows);
    }

    /**
     * Opens the cursor {@code cursor} names, as {@link #openCursor(Statement, List, long)} opens a
     * statement's one: granted the capability {@code "cursors"}, a statement has several open at once, each of its
     * own number; 0 is the one that method opens.
     *
     * @param cursor the statement and the cursor's number
     * @param values a value for each parameter
     * @param rows the most rows taken now, 0 for all
     * @return the rows, and whether the cursor has more
     * @throws TurborelException as {@link #openCursor(Statement, List, long)}'s; a connection
     *     failure for a number other than 0 if the capability {@code "cursors"} was not granted
     */
    public Batch openCursor(Cursor cursor, List<Value> values, long rows) throws TurborelException {
        connection.needs(cursor.number() == 0, Capability.CURSORS);
        boolean ends = connection.autocommit() && connection.granted().contains(Capability.FETCH_ENDS);
        long statement = cursor.statement().number();
        connection.send(new Request.Execute(
                statement, values, rows, ends, connection.told().attributes(), cursor.number()));
        return rows();
    }

    /**
     * At most {@code rows} more rows of {@code statement}'s cursor, all if 0.
     *
     * @param statement the statement
     * @param rows the most rows taken, 0 for all
     * @return the rows, and whether the cursor has more
     * @throws TurborelException the server's: SQLSTATE 24000 if its cursor is not open; a
     *     connection failure
     */
    public Batch fetch(Statement statement, long rows) throws TurborelException {
        return fetch(new Cursor(statement, 0), rows);
    }

    /**
     * At most {@code rows} more rows of the cursor {@code cursor} names, all if 0.
     *
     * @param cursor the statement and the cursor's number
     * @param rows the most rows taken, 0 for all
     * @return the rows, and whether the cursor has more
     * @throws TurborelException as {@link #fetch(Statement, long)}'s; a connection failure for a
     *     number other than 0 if the capability {@code "cursors"} was not granted
     */
    public Batch fetch(Cursor cursor, long rows) throws TurborelException {
        connection.needs(cursor.number() == 0, Capability.CURSORS);
        connection.send(new Request.Fetch(cursor.statement().number(), rows, cursor.number()));
        return rows();
    }

    /**
     * Closes {@code statement}'s cursor.
     *
     * @param statement the statement
     * @throws TurborelException the server's: SQLSTATE 24000 if it is not open; a connection failure
     */
    public void closeCursor(Statement statement) throws TurborelException {
        closeCursor(new Cursor(statement, 0));
    }

    /**
     * Closes the cursor {@code cursor} names.
     *
     * @param cursor the statement and the cursor's number
     * @throws TurborelException as {@link #closeCursor(Statement)}'s
     */
    public void closeCursor(Cursor cursor) throws TurborelException {
        connection.needs(cursor.number() == 0, Capability.CURSORS);
        connection.send(new Request.Close(cursor.statement().number(), cursor.number()));
        completion();
    }

    /**
     * {@code statement} described again by the server: its parameters' types and its columns, as
     * they are now ([CLIENT Controlling the session]).
     *
     * @param statement the statement
     * @return the statement, as described now
     * @throws TurborelException the server's: SQLSTATE 26000 if the statement is no more; a
     *     connection failure if {@code describe} was not granted
     */
    public Statement describe(Statement statement) throws TurborelException {
        connection.needs(false, Capability.DESCRIBE);
        connection.send(new Request.Describe(false, statement.number(), 0));
        return prepared();
    }

    /**
     * The columns of the open cursor {@code cursor} names ([CLIENT Controlling the session]).
     *
     * @param cursor the statement and the cursor's number
     * @return its columns
     * @throws TurborelException the server's: SQLSTATE 24000 if it is not open; a connection
     *     failure if {@code describe} was not granted
     */
    public List<Column> describe(Cursor cursor) throws TurborelException {
        connection.needs(false, Capability.DESCRIBE);
        connection.send(new Request.Describe(true, cursor.statement().number(), cursor.number()));
        return description(connection.receive());
    }

    /**
     * Destroys {@code statement}; of no use after.
     *
     * @param statement the statement
     * @throws TurborelException the server's: SQLSTATE 24000 if its cursor is open; a connection
     *     failure
     */
    public void deallocate(Statement statement) throws TurborelException {
        connection.send(new Request.Deallocate(statement.number()));
        completion();
    }

    /** The server's Complete, of a request sent. */
    Completed completion() throws TurborelException {
        Reply reply = connection.receive();
        if (reply instanceof Reply.Complete complete) {
            return complete.completed();
        }
        throw unexpected(reply, "the server sent no completion");
    }

    /** The rows the server sends, until it says whether the cursor has more. */
    Batch rows() throws TurborelException {
        List<List<Value>> rows = new ArrayList<>();
        while (true) {
            Reply reply = connection.receive();
            if (reply instanceof Reply.Rows batch) {
                rows.addAll(batch.rows());
            } else if (reply instanceof Reply.Complete) {
                return new Batch(rows, false);
            } else if (reply instanceof Reply.Suspended) {
                return new Batch(rows, true);
            } else {
                throw unexpected(reply, "the server broke off its rows");
            }
        }
    }

    /** The failure a reply not expected is: an Error's, else the connection lost. */
    TurborelException unexpected(Reply reply, String what) {
        if (reply instanceof Reply.Error error) {
            return connection.failed(error);
        }
        return connection.lose("[PROTOCOL Conversations] " + what);
    }

    /**
     * One of a statement's cursors, by the number the program gave it ([CLIENT Controlling the
     * session]).
     *
     * @param statement the statement
     * @param number the cursor's number, a {@code u32}; 0 is the one a statement has without {@code
     *     cursors}
     */
    public record Cursor(Statement statement, long number) {}
}
