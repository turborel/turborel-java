package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.Reply;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.internal.session.Capability;
import com.turborel.jdbc.internal.session.Connection;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;

/**
 * A connection ([DESIGN] D2), over the low-level client's: autocommit on as JDBC begins it, each
 * statement then its own transaction; COMMIT and ROLLBACK, savepoints within a transaction; the
 * isolation level the session setting TRANSACTION.ISOLATION, from the next transaction; {@code
 * isValid} by Ping. What JDBC has no place for is {@link TurborelConnection}'s.
 */
final class JdbcConnection implements TurborelConnection {
    /** The session status of the isolation level, a session setting. */
    static final String ISOLATION = "TRANSACTION.ISOLATION";

    /** The isolation levels by their JDBC numbers, as SQL names them. */
    private static final Map<Integer, String> LEVELS = Map.of(
            TRANSACTION_READ_UNCOMMITTED, "READ UNCOMMITTED",
            TRANSACTION_READ_COMMITTED, "READ COMMITTED",
            TRANSACTION_REPEATABLE_READ, "REPEATABLE READ",
            TRANSACTION_SERIALIZABLE, "SERIALIZABLE");

    /** What a connection checks it answers by, granted no Ping. */
    private static final String CHECK = "VALUES 1";

    /** Milliseconds in a second. */
    private static final long MILLIS = 1000;

    private final Connection session;
    private final String url;
    private final List<JdbcStatement> statements = new ArrayList<>();
    private final Properties clientInfo = new Properties();
    private boolean closed;
    private boolean readOnly;
    private int savepoints;
    private int holdability = ResultSet.CLOSE_CURSORS_AT_COMMIT;
    private @Nullable SQLWarning warnings;

    /**
     * A JDBC connection over {@code session}, autocommit on.
     *
     * @param session the low-level client's connection
     * @param url the URL it was made of, as the metadata gives it
     */
    JdbcConnection(Connection session, String url) {
        this.session = session;
        this.url = url;
        session.setAutocommit(true);
    }

    /** The low-level client's connection. */
    Connection session() {
        return session;
    }

    /** The URL the connection was made of. */
    String url() {
        return url;
    }

    /** Refuses a connection closed, or lost. */
    void open() throws SQLException {
        if (closed) {
            throw Errors.misuse("the connection is closed");
        }
        if (session.isLost()) {
            throw com.turborel.jdbc.internal.codec.Broken.connection("[DESIGN] D2 the connection is lost");
        }
    }

    /** Notes a statement of the connection closed. */
    void statementClosed(JdbcStatement statement) {
        statements.remove(statement);
    }

    private <T extends JdbcStatement> T made(T statement) {
        statements.add(statement);
        return statement;
    }

    /** {@code v} as {@code getObject} reads a value of {@code column}, or of no type known. */
    @Nullable
    Object object(Value v, com.turborel.jdbc.internal.codec.@Nullable Column column) throws SQLException {
        return ReadObject.object(v, column, this);
    }

    /** Runs {@code sql}, a statement of the driver's own, within the session's transaction. */
    void run(String sql) throws SQLException {
        open();
        session.statements().query(sql);
    }

    private static void plain(int resultSetType, int resultSetConcurrency) throws SQLException {
        if (resultSetType != ResultSet.TYPE_FORWARD_ONLY || resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) {
            throw Errors.unsupported("a result set not forward-only and read-only");
        }
    }

    @Override
    public Statement createStatement() throws SQLException {
        open();
        return made(new JdbcStatement(this));
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        plain(resultSetType, resultSetConcurrency);
        return createStatement();
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        plain(resultSetType, resultSetConcurrency);
        return createStatement();
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        open();
        return made(new JdbcPreparedStatement(this, sql));
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
            throws SQLException {
        plain(resultSetType, resultSetConcurrency);
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(
            String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        plain(resultSetType, resultSetConcurrency);
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int @Nullable [] columnIndexes) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String @Nullable [] columnNames) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        open();
        return made(new JdbcCallableStatement(this, sql));
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        plain(resultSetType, resultSetConcurrency);
        return prepareCall(sql);
    }

    @Override
    public CallableStatement prepareCall(
            String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        plain(resultSetType, resultSetConcurrency);
        return prepareCall(sql);
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        open();
        return Escapes.translate(sql);
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        open();
        if (autoCommit && !session.autocommit() && session.inTransaction()) {
            run("COMMIT");
        }
        session.setAutocommit(autoCommit);
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        open();
        return session.autocommit();
    }

    @Override
    public void commit() throws SQLException {
        open();
        if (session.autocommit()) {
            throw Errors.misuse("commit under autocommit, where each statement commits itself");
        }
        run("COMMIT");
    }

    @Override
    public void rollback() throws SQLException {
        open();
        if (session.autocommit()) {
            throw Errors.misuse("rollback under autocommit, where each statement commits itself");
        }
        run("ROLLBACK");
    }

    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        statements.clear();
        if (session.isLost()) {
            session.close();
            return;
        }
        try {
            session.terminate();
        } finally {
            session.close();
        }
    }

    @Override
    public boolean isClosed() {
        return closed || session.isLost();
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        open();
        return new JdbcDatabaseMetaData(this);
    }

    @Override
    public void setReadOnly(boolean wanted) throws SQLException {
        open();
        readOnly = wanted;
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        open();
        return readOnly || "YES".equals(session.setting("READ_ONLY"));
    }

    @Override
    public void setCatalog(@Nullable String catalog) throws SQLException {
        open();
        // A server serves one catalog: JDBC asks a driver of one to ignore this.
    }

    @Override
    public @Nullable String getCatalog() throws SQLException {
        open();
        return session.setting("CATALOG");
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        open();
        String name = LEVELS.get(level);
        if (name == null) {
            throw Errors.misuse("isolation level " + level + " is none of JDBC's");
        }
        run("SET " + ISOLATION + " = '" + name + "'");
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        open();
        String told = session.setting(ISOLATION);
        for (Map.Entry<Integer, String> each : LEVELS.entrySet()) {
            if (each.getValue().equals(told == null ? "" : told.toUpperCase(Locale.ROOT))) {
                return each.getKey();
            }
        }
        return TRANSACTION_READ_COMMITTED;
    }

    @Override
    public @Nullable SQLWarning getWarnings() throws SQLException {
        open();
        List<TurborelException> told = new ArrayList<>();
        for (Reply.Notice notice : session.takeNotices()) {
            told.add(new TurborelException(0, notice.sqlstate(), notice.message()));
        }
        SQLWarning noticed = Errors.warnings(told);
        if (noticed != null) {
            if (warnings == null) {
                warnings = noticed;
            } else {
                warnings.setNextWarning(noticed);
            }
        }
        return warnings;
    }

    @Override
    public void clearWarnings() throws SQLException {
        open();
        session.takeNotices();
        warnings = null;
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        open();
        return new HashMap<>();
    }

    @Override
    public void setTypeMap(@Nullable Map<String, Class<?>> map) throws SQLException {
        open();
        if (map != null && !map.isEmpty()) {
            throw Errors.unsupported("a type map");
        }
    }

    @Override
    public void setHoldability(int wanted) throws SQLException {
        open();
        if (wanted != ResultSet.CLOSE_CURSORS_AT_COMMIT) {
            throw Errors.unsupported("a cursor held over a commit");
        }
        holdability = wanted;
    }

    @Override
    public int getHoldability() throws SQLException {
        open();
        return holdability;
    }

    private void inTransaction() throws SQLException {
        open();
        if (session.autocommit()) {
            throw Errors.misuse("a savepoint is of a transaction: turn autocommit off");
        }
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        inTransaction();
        JdbcSavepoint savepoint = new JdbcSavepoint(++savepoints, null);
        run("SAVEPOINT " + savepoint.sqlName());
        return savepoint;
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        inTransaction();
        JdbcSavepoint savepoint = new JdbcSavepoint(++savepoints, name);
        run("SAVEPOINT " + savepoint.sqlName());
        return savepoint;
    }

    private static JdbcSavepoint ours(@Nullable Savepoint savepoint) throws SQLException {
        if (savepoint instanceof JdbcSavepoint mine) {
            return mine;
        }
        throw Errors.misuse("the savepoint is not of this driver");
    }

    @Override
    public void rollback(@Nullable Savepoint savepoint) throws SQLException {
        inTransaction();
        run("ROLLBACK TO SAVEPOINT " + ours(savepoint).sqlName());
    }

    @Override
    public void releaseSavepoint(@Nullable Savepoint savepoint) throws SQLException {
        inTransaction();
        run("RELEASE SAVEPOINT " + ours(savepoint).sqlName());
    }

    @Override
    public Clob createClob() throws SQLException {
        open();
        return new JdbcClob("");
    }

    @Override
    public Blob createBlob() throws SQLException {
        open();
        return new JdbcBlob(new byte[0]);
    }

    @Override
    public NClob createNClob() throws SQLException {
        open();
        return new JdbcClob("");
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        open();
        return new JdbcSQLXML();
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        if (timeout < 0) {
            throw Errors.misuse("isValid's timeout is 0 seconds or more");
        }
        if (isClosed()) {
            return false;
        }
        Duration before = session.readTimeout();
        boolean limited = false;
        try {
            limited = timeout > 0 && bounded(Duration.ofSeconds(timeout));
            if (session.granted().contains(Capability.PING)) {
                session.controls().ping();
            } else {
                session.statements().query(CHECK);
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

    /** Bounds the session's reads by {@code within}, if its stream is the driver's to bound. */
    private boolean bounded(Duration within) {
        try {
            session.setReadTimeout(within);
            return true;
        } catch (TurborelException programs) {
            return false;
        }
    }

    @Override
    public void setClientInfo(String name, @Nullable String value) throws SQLClientInfoException {
        if (value == null) {
            clientInfo.remove(name);
        } else {
            clientInfo.setProperty(name, value);
        }
    }

    @Override
    public void setClientInfo(@Nullable Properties properties) throws SQLClientInfoException {
        clientInfo.clear();
        if (properties != null) {
            clientInfo.putAll(properties);
        }
    }

    @Override
    public @Nullable String getClientInfo(String name) throws SQLException {
        open();
        return clientInfo.getProperty(name);
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        open();
        Properties copy = new Properties();
        copy.putAll(clientInfo);
        return copy;
    }

    @Override
    public Array createArrayOf(String typeName, @Nullable Object[] elements) throws SQLException {
        open();
        return new JdbcArray(typeName, elements);
    }

    @Override
    public Struct createStruct(String typeName, @Nullable Object[] attributes) throws SQLException {
        throw Errors.unsupported("a Struct");
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        run("SET SCHEMA '\"" + schema.replace("\"", "\"\"").replace("'", "''") + "\"'");
    }

    @Override
    public @Nullable String getSchema() throws SQLException {
        open();
        return session.setting("SCHEMA");
    }

    @Override
    public void abort(@Nullable Executor executor) throws SQLException {
        if (executor == null) {
            throw Errors.misuse("abort is given an executor");
        }
        closed = true;
        session.close();
    }

    @Override
    public void setNetworkTimeout(@Nullable Executor executor, int milliseconds) throws SQLException {
        open();
        if (milliseconds < 0) {
            throw Errors.misuse("a network timeout is 0 or more milliseconds");
        }
        session.setReadTimeout(milliseconds == 0 ? null : Duration.ofMillis(milliseconds));
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        open();
        Duration within = session.readTimeout();
        return within == null ? 0 : (int) Math.min(Integer.MAX_VALUE, within.toMillis());
    }

    @Override
    public @Nullable String sessionStatus(String name) throws SQLException {
        open();
        return session.setting(name);
    }

    @Override
    public Map<String, String> sessionStatus() throws SQLException {
        open();
        return Map.copyOf(session.settings());
    }

    @Override
    public @Nullable Long commitPosition() throws SQLException {
        open();
        return session.told().commitPosition();
    }

    @Override
    public Set<String> granted() throws SQLException {
        open();
        return Set.copyOf(session.granted());
    }

    @Override
    public <T> T unwrap(@Nullable Class<T> iface) throws SQLException {
        return Wrapping.unwrap(this, iface);
    }

    @Override
    public boolean isWrapperFor(@Nullable Class<?> iface) {
        return iface != null && iface.isInstance(this);
    }

    /** Milliseconds in a second, for a login timeout. */
    static long millis(int seconds) {
        return seconds * MILLIS;
    }
}
