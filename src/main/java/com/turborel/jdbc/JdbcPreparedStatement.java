package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.DataType;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.internal.session.Pipeline;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A prepared statement ([DESIGN] D2): prepared once, as it is made, then executed with a value of
 * each parameter, each made a value of the parameter's type ({@link ToValue}). A batch of its
 * executions is one pipeline.
 */
class JdbcPreparedStatement extends JdbcStatement implements PreparedStatement {
    /** The length of a stream read whole. */
    private static final long WHOLE = -1;

    /** The characters read from a reader at once. */
    private static final int BUFFER = 8192;

    final com.turborel.jdbc.internal.session.Statement prepared;
    private final @Nullable Value[] values;
    private final List<List<Value>> batches = new ArrayList<>();

    JdbcPreparedStatement(JdbcConnection connection, String sql) throws SQLException {
        super(connection);
        this.prepared = connection.session().statements().prepare(sql(sql));
        this.values = new Value[prepared.parameters().size()];
    }

    /** The type of the protocol's parameter {@code i}, from 1. */
    final DataType parameterType(int i) throws SQLException {
        if (i < 1 || i > prepared.parameters().size()) {
            throw Errors.misuse("parameter " + i + " is not of 1 to "
                    + prepared.parameters().size());
        }
        return prepared.parameters().get(i - 1).type();
    }

    /**
     * Sets parameter {@code i} to {@code x}, made a value of its type.
     *
     * @param i the parameter, from 1
     * @param x the value, or none for null
     * @throws SQLException SQLSTATE TB910 of no such parameter; 22018 if it is no value of the type
     */
    void set(int i, @Nullable Object x) throws SQLException {
        open();
        values[i - 1] = ToValue.of(x, parameterType(i));
    }

    /**
     * The values of the protocol's parameters, in order.
     *
     * @return the values
     * @throws SQLException SQLSTATE 07001 if one has none
     */
    List<Value> values() throws SQLException {
        List<Value> all = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) {
            Value v = values[i];
            if (v == null) {
                throw new TurborelException(
                        Errors.DYNAMIC_SQLCODE,
                        Errors.NO_VALUE_STATE,
                        "[DESIGN] D2 parameter " + (i + 1) + " has no value");
            }
            all.add(v);
        }
        return all;
    }

    /** Runs the statement with its values: its result set or count taken. */
    boolean runPrepared() throws SQLException {
        List<Value> given = values();
        return running(() -> {
            if (fetchRows() > 0 && !prepared.columns().isEmpty()) {
                return cursor(prepared, statements().openCursor(prepared, given, fetchRows()), false);
            }
            return outcome(statements().execute(prepared, given));
        });
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        if (!runPrepared()) {
            throw Errors.misuse("executeQuery ran a statement that gave no result set");
        }
        return resultOf();
    }

    @Override
    public int executeUpdate() throws SQLException {
        return (int) Math.min(Integer.MAX_VALUE, executeLargeUpdate());
    }

    @Override
    public long executeLargeUpdate() throws SQLException {
        if (runPrepared()) {
            closeResult();
            throw Errors.misuse("executeUpdate ran a query, which gives a result set");
        }
        return getLargeUpdateCount();
    }

    @Override
    public boolean execute() throws SQLException {
        return runPrepared();
    }

    @Override
    public void addBatch() throws SQLException {
        open();
        batches.add(values());
    }

    @Override
    public void clearBatch() throws SQLException {
        super.clearBatch();
        batches.clear();
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        open();
        Pipeline pipeline = connection.session().pipeline();
        for (List<Value> each : batches) {
            pipeline.execute(prepared, each);
        }
        batches.clear();
        return counts(pipeline);
    }

    @Override
    public void clearParameters() throws SQLException {
        open();
        Arrays.fill(values, null);
    }

    @Override
    public @Nullable ResultSetMetaData getMetaData() throws SQLException {
        open();
        return prepared.columns().isEmpty() ? null : new JdbcResultSetMetaData(prepared.columns());
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        open();
        return new JdbcParameterMetaData(prepared.parameters());
    }

    @Override
    public void close() throws SQLException {
        if (isClosed()) {
            return;
        }
        try {
            super.close();
        } finally {
            if (!connection.isClosed()) {
                statements().deallocate(prepared);
            }
        }
    }

    private static SQLException sqlGiven() {
        return Errors.misuse("a prepared statement runs the SQL it was prepared of, and takes none");
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        throw sqlGiven();
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        throw sqlGiven();
    }

    @Override
    public long executeLargeUpdate(String sql) throws SQLException {
        throw sqlGiven();
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        throw sqlGiven();
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        throw sqlGiven();
    }

    /** The octets a stream gives, at most {@code length} of them if it is not negative. */
    static byte[] read(@Nullable InputStream in, long length) throws SQLException {
        if (in == null) {
            return new byte[0];
        }
        try {
            return length < 0 ? in.readAllBytes() : in.readNBytes(Math.toIntExact(length));
        } catch (IOException e) {
            throw Errors.conversion("a stream could not be read: " + e.getMessage());
        }
    }

    /** The characters a reader gives, at most {@code length} of them if it is not negative. */
    static String read(@Nullable Reader in, long length) throws SQLException {
        if (in == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[BUFFER];
        try {
            while (length < 0 || text.length() < length) {
                int want = length < 0 ? buffer.length : (int) Math.min(buffer.length, length - text.length());
                int got = in.read(buffer, 0, want);
                if (got < 0) {
                    break;
                }
                text.append(buffer, 0, got);
            }
        } catch (IOException e) {
            throw Errors.conversion("a reader could not be read: " + e.getMessage());
        }
        return text.toString();
    }

    @Override
    public void setNull(int i, int sqlType) throws SQLException {
        set(i, null);
    }

    @Override
    public void setNull(int i, int sqlType, @Nullable String typeName) throws SQLException {
        set(i, null);
    }

    @Override
    public void setBoolean(int i, boolean x) throws SQLException {
        set(i, x);
    }

    @Override
    public void setByte(int i, byte x) throws SQLException {
        set(i, x);
    }

    @Override
    public void setShort(int i, short x) throws SQLException {
        set(i, x);
    }

    @Override
    public void setInt(int i, int x) throws SQLException {
        set(i, x);
    }

    @Override
    public void setLong(int i, long x) throws SQLException {
        set(i, x);
    }

    @Override
    public void setFloat(int i, float x) throws SQLException {
        set(i, x);
    }

    @Override
    public void setDouble(int i, double x) throws SQLException {
        set(i, x);
    }

    @Override
    public void setBigDecimal(int i, @Nullable BigDecimal x) throws SQLException {
        set(i, x);
    }

    @Override
    public void setString(int i, @Nullable String x) throws SQLException {
        set(i, x);
    }

    @Override
    public void setNString(int i, @Nullable String x) throws SQLException {
        set(i, x);
    }

    @Override
    public void setBytes(int i, byte @Nullable [] x) throws SQLException {
        set(i, x);
    }

    @Override
    public void setDate(int i, @Nullable Date x) throws SQLException {
        set(i, x);
    }

    @Override
    public void setTime(int i, @Nullable Time x) throws SQLException {
        set(i, x);
    }

    @Override
    public void setTimestamp(int i, @Nullable Timestamp x) throws SQLException {
        set(i, x);
    }

    @Override
    public void setDate(int i, @Nullable Date x, @Nullable Calendar cal) throws SQLException {
        set(
                i,
                x == null || cal == null
                        ? x
                        : java.time.Instant.ofEpochMilli(x.getTime())
                                .atZone(cal.getTimeZone().toZoneId())
                                .toLocalDate());
    }

    @Override
    public void setTime(int i, @Nullable Time x, @Nullable Calendar cal) throws SQLException {
        set(
                i,
                x == null || cal == null
                        ? x
                        : java.time.Instant.ofEpochMilli(x.getTime())
                                .atZone(cal.getTimeZone().toZoneId())
                                .toLocalTime());
    }

    @Override
    public void setTimestamp(int i, @Nullable Timestamp x, @Nullable Calendar cal) throws SQLException {
        set(
                i,
                x == null || cal == null
                        ? x
                        : x.toInstant().atZone(cal.getTimeZone().toZoneId()).toLocalDateTime());
    }

    @Override
    public void setAsciiStream(int i, @Nullable InputStream x, int length) throws SQLException {
        setAsciiStream(i, x, (long) length);
    }

    @Override
    public void setAsciiStream(int i, @Nullable InputStream x, long length) throws SQLException {
        set(i, x == null ? null : new String(read(x, length), StandardCharsets.US_ASCII));
    }

    @Override
    public void setAsciiStream(int i, @Nullable InputStream x) throws SQLException {
        setAsciiStream(i, x, WHOLE);
    }

    @Override
    @Deprecated
    public void setUnicodeStream(int i, @Nullable InputStream x, int length) throws SQLException {
        set(i, x == null ? null : new String(read(x, length), StandardCharsets.UTF_16BE));
    }

    @Override
    public void setBinaryStream(int i, @Nullable InputStream x, int length) throws SQLException {
        setBinaryStream(i, x, (long) length);
    }

    @Override
    public void setBinaryStream(int i, @Nullable InputStream x, long length) throws SQLException {
        set(i, x == null ? null : read(x, length));
    }

    @Override
    public void setBinaryStream(int i, @Nullable InputStream x) throws SQLException {
        setBinaryStream(i, x, WHOLE);
    }

    @Override
    public void setCharacterStream(int i, @Nullable Reader x, int length) throws SQLException {
        setCharacterStream(i, x, (long) length);
    }

    @Override
    public void setCharacterStream(int i, @Nullable Reader x, long length) throws SQLException {
        set(i, x == null ? null : read(x, length));
    }

    @Override
    public void setCharacterStream(int i, @Nullable Reader x) throws SQLException {
        setCharacterStream(i, x, WHOLE);
    }

    @Override
    public void setNCharacterStream(int i, @Nullable Reader x, long length) throws SQLException {
        setCharacterStream(i, x, length);
    }

    @Override
    public void setNCharacterStream(int i, @Nullable Reader x) throws SQLException {
        setCharacterStream(i, x, WHOLE);
    }

    @Override
    public void setObject(int i, @Nullable Object x) throws SQLException {
        set(i, x);
    }

    @Override
    public void setObject(int i, @Nullable Object x, int targetSqlType) throws SQLException {
        set(i, x);
    }

    @Override
    public void setObject(int i, @Nullable Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        set(i, x);
    }

    @Override
    public void setObject(int i, @Nullable Object x, @Nullable SQLType targetSqlType) throws SQLException {
        set(i, x);
    }

    @Override
    public void setObject(int i, @Nullable Object x, @Nullable SQLType targetSqlType, int scaleOrLength)
            throws SQLException {
        set(i, x);
    }

    @Override
    public void setBlob(int i, @Nullable Blob x) throws SQLException {
        set(i, x);
    }

    @Override
    public void setBlob(int i, @Nullable InputStream x, long length) throws SQLException {
        set(i, x == null ? null : read(x, length));
    }

    @Override
    public void setBlob(int i, @Nullable InputStream x) throws SQLException {
        setBlob(i, x, WHOLE);
    }

    @Override
    public void setClob(int i, @Nullable Clob x) throws SQLException {
        set(i, x);
    }

    @Override
    public void setClob(int i, @Nullable Reader x, long length) throws SQLException {
        set(i, x == null ? null : read(x, length));
    }

    @Override
    public void setClob(int i, @Nullable Reader x) throws SQLException {
        setClob(i, x, WHOLE);
    }

    @Override
    public void setNClob(int i, @Nullable NClob x) throws SQLException {
        set(i, x);
    }

    @Override
    public void setNClob(int i, @Nullable Reader x, long length) throws SQLException {
        setClob(i, x, length);
    }

    @Override
    public void setNClob(int i, @Nullable Reader x) throws SQLException {
        setClob(i, x, WHOLE);
    }

    @Override
    public void setSQLXML(int i, @Nullable SQLXML x) throws SQLException {
        set(i, x);
    }

    @Override
    public void setArray(int i, @Nullable Array x) throws SQLException {
        set(i, x);
    }

    @Override
    public void setRef(int i, @Nullable Ref x) throws SQLException {
        throw Errors.unsupported("a REF parameter");
    }

    @Override
    public void setURL(int i, @Nullable URL x) throws SQLException {
        throw Errors.unsupported("a DATALINK parameter");
    }

    @Override
    public void setRowId(int i, @Nullable RowId x) throws SQLException {
        throw Errors.unsupported("a row ID");
    }
}
