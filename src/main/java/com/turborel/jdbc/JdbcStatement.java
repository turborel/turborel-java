package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.CharUnits;
import com.turborel.jdbc.internal.codec.Column;
import com.turborel.jdbc.internal.codec.Completed;
import com.turborel.jdbc.internal.codec.DataType;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.internal.session.Batch;
import com.turborel.jdbc.internal.session.Outcome;
import com.turborel.jdbc.internal.session.Pipeline;
import com.turborel.jdbc.internal.session.Statements;
import java.sql.BatchUpdateException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.jspecify.annotations.Nullable;

/**
 * A statement ([DESIGN] D2): SQL run as the low-level client runs it, its result a forward-only,
 * read-only result set or a count of the rows it changed. With a fetch size, a query's rows are read
 * by a cursor that many at a time; else whole. A batch is a pipeline, one round trip. A timeout
 * cancels the statement by the protocol's Cancel.
 */
class JdbcStatement implements Statement {
    /** The name of a generated key's column, of the keys an INSERT generated. */
    static final String GENERATED = "GENERATED";

    /** The update count of a result set, or of no statement run: none. */
    private static final long NO_COUNT = -1;

    /** The type of a generated key, as SQL writes it. */
    private static final DataType KEY_TYPE = new DataType.CharacterType(true, 0, CharUnits.CHARACTERS);

    final JdbcConnection connection;
    private @Nullable JdbcResultSet result;
    private long updateCount = NO_COUNT;
    private List<String> generated = List.of();
    private final List<String> batch = new ArrayList<>();
    private boolean closed;
    private boolean closeOnCompletion;
    private boolean poolable;
    private boolean escapes = true;
    private long maxRows;
    private int fetchSize;
    private int queryTimeout;
    private int maxFieldSize;
    private @Nullable SQLWarning warnings;

    JdbcStatement(JdbcConnection connection) {
        this.connection = connection;
    }

    /** Refuses a statement closed, or of a connection closed. */
    final void open() throws SQLException {
        if (closed) {
            throw Errors.misuse("the statement is closed");
        }
        connection.open();
    }

    /** {@code sql} as it is sent: its escapes translated, unless escape processing is off. */
    final String sql(String sql) throws SQLException {
        return escapes ? Escapes.translate(sql) : sql;
    }

    final Statements statements() {
        return connection.session().statements();
    }

    /** What a statement runs to give its outcome. */
    @FunctionalInterface
    interface Run {
        boolean run() throws SQLException;
    }

    /** Runs {@code run} as a statement of this one: its last result closed, its timeout kept. */
    final boolean running(Run run) throws SQLException {
        open();
        closeResult();
        warnings = null;
        generated = List.of();
        updateCount = NO_COUNT;
        ScheduledFuture<?> timeout = Timeouts.start(connection.session().canceller(), queryTimeout);
        try {
            return run.run();
        } catch (SQLException failure) {
            throw Errors.classed(failure);
        } finally {
            Timeouts.stop(timeout);
            warnings = Errors.warnings(connection.session().told().takeConditions());
        }
    }

    /** Takes {@code outcome} as the statement's: a result set, or a count. */
    final boolean outcome(Outcome outcome) {
        if (outcome instanceof Outcome.Rows rows) {
            result = new JdbcResultSet(this, connection, rows.columns(), Rows.of(rows.rows()))
                    .limited(maxRows)
                    .cut(maxFieldSize);
            return true;
        }
        Completed done = ((Outcome.Done) outcome).completed();
        Long rows = done.fields().rows();
        updateCount = rows == null ? 0 : rows;
        generated = done.fields().generated();
        return false;
    }

    /** Takes a cursor's rows as the statement's result set. */
    final boolean cursor(com.turborel.jdbc.internal.session.Statement prepared, Batch first, boolean owned) {
        Rows rows = Rows.cursor(statements(), prepared, first, fetchSize, owned);
        result = new JdbcResultSet(this, connection, prepared.columns(), rows)
                .limited(maxRows)
                .cut(maxFieldSize);
        return true;
    }

    final int fetchRows() {
        return fetchSize;
    }

    private boolean run(String sql) throws SQLException {
        String sent = sql(sql);
        return running(() -> {
            if (fetchSize <= 0) {
                return outcome(statements().query(sent));
            }
            com.turborel.jdbc.internal.session.Statement prepared = statements().prepare(sent);
            if (!prepared.columns().isEmpty()) {
                return cursor(prepared, statements().openCursor(prepared, List.of(), fetchSize), true);
            }
            try {
                return outcome(statements().execute(prepared, List.of()));
            } finally {
                statements().deallocate(prepared);
            }
        });
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        if (!run(sql)) {
            throw Errors.misuse("executeQuery ran a statement that gave no result set");
        }
        return resultOf();
    }

    /** The result set of the statement just run. */
    final ResultSet resultOf() throws SQLException {
        JdbcResultSet made = result;
        if (made == null) {
            throw Errors.misuse("the statement gave no result set");
        }
        return made;
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        return (int) Math.min(Integer.MAX_VALUE, executeLargeUpdate(sql));
    }

    @Override
    public long executeLargeUpdate(String sql) throws SQLException {
        if (run(sql)) {
            closeResult();
            throw Errors.misuse("executeUpdate ran a query, which gives a result set");
        }
        return updateCount;
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        return executeUpdate(sql);
    }

    @Override
    public int executeUpdate(String sql, int @Nullable [] columnIndexes) throws SQLException {
        return executeUpdate(sql);
    }

    @Override
    public int executeUpdate(String sql, String @Nullable [] columnNames) throws SQLException {
        return executeUpdate(sql);
    }

    @Override
    public long executeLargeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        return executeLargeUpdate(sql);
    }

    @Override
    public long executeLargeUpdate(String sql, int @Nullable [] columnIndexes) throws SQLException {
        return executeLargeUpdate(sql);
    }

    @Override
    public long executeLargeUpdate(String sql, String @Nullable [] columnNames) throws SQLException {
        return executeLargeUpdate(sql);
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        return run(sql);
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        return run(sql);
    }

    @Override
    public boolean execute(String sql, int @Nullable [] columnIndexes) throws SQLException {
        return run(sql);
    }

    @Override
    public boolean execute(String sql, String @Nullable [] columnNames) throws SQLException {
        return run(sql);
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        open();
        List<List<Value>> rows = new ArrayList<>();
        for (String key : generated) {
            rows.add(List.of(new Value.Text(key)));
        }
        return new JdbcResultSet(this, connection, List.of(new Column(GENERATED, KEY_TYPE, true)), Rows.of(rows));
    }

    @Override
    public @Nullable ResultSet getResultSet() throws SQLException {
        open();
        return result;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        return (int) Math.min(Integer.MAX_VALUE, getLargeUpdateCount());
    }

    @Override
    public long getLargeUpdateCount() throws SQLException {
        open();
        return result == null ? updateCount : NO_COUNT;
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        return getMoreResults(CLOSE_CURRENT_RESULT);
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        open();
        if (current != KEEP_CURRENT_RESULT) {
            closeResult();
        }
        result = null;
        updateCount = NO_COUNT;
        return false;
    }

    /** Closes the statement's result set, if it has one. */
    final void closeResult() throws SQLException {
        JdbcResultSet made = result;
        result = null;
        if (made != null) {
            made.close();
        }
    }

    /** Notes a result set of the statement closed: the statement closed too, under closeOnCompletion. */
    void resultClosed(JdbcResultSet closedSet) throws SQLException {
        if (result == closedSet) {
            result = null;
        }
        if (closeOnCompletion) {
            close();
        }
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        open();
        batch.add(sql(sql));
    }

    @Override
    public void clearBatch() throws SQLException {
        open();
        batch.clear();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        long[] counts = executeLargeBatch();
        int[] small = new int[counts.length];
        for (int i = 0; i < counts.length; i++) {
            small[i] = (int) Math.min(Integer.MAX_VALUE, counts[i]);
        }
        return small;
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        open();
        Pipeline pipeline = connection.session().pipeline();
        batch.forEach(pipeline::query);
        batch.clear();
        return counts(pipeline);
    }

    /**
     * Runs a batch's pipeline: each request's count, or {@link #EXECUTE_FAILED}; a request that
     * failed, or gave a result set, fails the batch with a {@link BatchUpdateException} of every
     * count, the first failure its cause.
     */
    final long[] counts(Pipeline pipeline) throws SQLException {
        long[][] made = new long[1][];
        running(() -> {
            made[0] = countsOf(pipeline);
            return false;
        });
        return made[0];
    }

    private long[] countsOf(Pipeline pipeline) throws SQLException {
        List<Pipeline.Piped> outcomes = pipeline.run();
        long[] counts = new long[outcomes.size()];
        SQLException first = null;
        for (int i = 0; i < counts.length; i++) {
            Pipeline.Piped each = outcomes.get(i);
            counts[i] = EXECUTE_FAILED;
            if (each instanceof Pipeline.Piped.Done done && done.outcome() instanceof Outcome.Done count) {
                Long rows = count.completed().fields().rows();
                counts[i] = rows == null ? SUCCESS_NO_INFO : rows;
            } else if (first == null) {
                first = each instanceof Pipeline.Piped.Failed failed
                        ? failed.failure()
                        : Errors.misuse("request " + (i + 1) + " of a batch gave a result set");
            }
        }
        if (first != null) {
            throw new BatchUpdateException(
                    first.getMessage(), first.getSQLState(), first.getErrorCode(), counts, first);
        }
        return counts;
    }

    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            closeResult();
        } finally {
            connection.statementClosed(this);
        }
    }

    /**
     * Closes the statement as its connection closes: it and its result set closed, nothing sent,
     * as the session that held them ends.
     */
    final void connectionClosed() {
        closed = true;
        JdbcResultSet made = result;
        result = null;
        if (made != null) {
            made.connectionClosed();
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        open();
        closeOnCompletion = true;
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        open();
        return closeOnCompletion;
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        open();
        return maxFieldSize;
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        open();
        if (max < 0) {
            throw Errors.misuse("a field's most bytes are 0 or more");
        }
        maxFieldSize = max;
    }

    @Override
    public int getMaxRows() throws SQLException {
        return (int) Math.min(Integer.MAX_VALUE, getLargeMaxRows());
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        setLargeMaxRows(max);
    }

    @Override
    public long getLargeMaxRows() throws SQLException {
        open();
        return maxRows;
    }

    @Override
    public void setLargeMaxRows(long max) throws SQLException {
        open();
        if (max < 0) {
            throw Errors.misuse("the most rows are 0 or more");
        }
        maxRows = max;
    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        open();
        escapes = enable;
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        open();
        return queryTimeout;
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        open();
        if (seconds < 0) {
            throw Errors.misuse("a query timeout is 0 seconds or more");
        }
        queryTimeout = seconds;
    }

    @Override
    public void cancel() throws SQLException {
        open();
        com.turborel.jdbc.internal.session.Canceller canceller =
                connection.session().canceller();
        if (canceller != null) {
            canceller.cancel();
        }
    }

    @Override
    public @Nullable SQLWarning getWarnings() throws SQLException {
        open();
        return warnings;
    }

    @Override
    public void clearWarnings() throws SQLException {
        open();
        warnings = null;
    }

    @Override
    public void setCursorName(@Nullable String name) throws SQLException {
        throw Errors.unsupported("a named cursor");
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        open();
        if (direction != ResultSet.FETCH_FORWARD) {
            throw Errors.misuse("a forward-only result set fetches forward alone");
        }
    }

    @Override
    public int getFetchDirection() throws SQLException {
        open();
        return ResultSet.FETCH_FORWARD;
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        open();
        if (rows < 0) {
            throw Errors.misuse("a fetch size is 0 or more");
        }
        fetchSize = rows;
    }

    @Override
    public int getFetchSize() throws SQLException {
        open();
        return fetchSize;
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        open();
        return ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public int getResultSetType() throws SQLException {
        open();
        return ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        open();
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public java.sql.Connection getConnection() throws SQLException {
        open();
        return connection;
    }

    @Override
    public void setPoolable(boolean wanted) throws SQLException {
        open();
        poolable = wanted;
    }

    @Override
    public boolean isPoolable() throws SQLException {
        open();
        return poolable;
    }

    @Override
    public <T> T unwrap(@Nullable Class<T> iface) throws SQLException {
        return Wrapping.unwrap(this, iface);
    }

    @Override
    public boolean isWrapperFor(@Nullable Class<?> iface) {
        return iface != null && iface.isInstance(this);
    }
}
