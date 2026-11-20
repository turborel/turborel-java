package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.Column;
import com.turborel.jdbc.internal.codec.Lob;
import com.turborel.jdbc.internal.codec.LobKind;
import com.turborel.jdbc.internal.codec.Value;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A forward-only, read-only result set ([DESIGN] D2): of rows held, or of a cursor read a batch at
 * a time, each value read as the getter asks ({@link FromValue}, {@link ReadObject}).
 */
final class JdbcResultSet extends ResultSetByName {
    private final @Nullable JdbcStatement statement;
    private final JdbcConnection connection;
    private final List<Column> columns;
    private final Rows rows;
    private @Nullable List<Value> row;
    private long read;
    private long limit;
    private boolean closed;
    private boolean ended;
    private boolean wasNull;
    private int fetchSize;
    private @Nullable SQLWarning warnings;

    /**
     * A result set of {@code columns}, its rows from {@code rows}.
     *
     * @param statement the statement that made it, or none
     * @param connection its connection
     * @param columns its columns
     * @param rows its rows
     */
    JdbcResultSet(@Nullable JdbcStatement statement, JdbcConnection connection, List<Column> columns, Rows rows) {
        this.statement = statement;
        this.connection = connection;
        this.columns = List.copyOf(columns);
        this.rows = rows;
    }

    /** Takes at most {@code most} rows, all if 0. */
    JdbcResultSet limited(long most) {
        limit = most;
        return this;
    }

    /** Sets the warnings of the statement that made it. */
    JdbcResultSet warned(@Nullable SQLWarning warning) {
        warnings = warning;
        return this;
    }

    private void open() throws SQLException {
        if (closed) {
            throw Errors.misuse("the result set is closed");
        }
    }

    @Override
    public boolean next() throws SQLException {
        open();
        if (ended || (limit > 0 && read >= limit)) {
            row = null;
            ended = true;
            return false;
        }
        row = rows.next();
        if (row == null) {
            ended = true;
            return false;
        }
        read++;
        return true;
    }

    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        row = null;
        rows.close();
        JdbcStatement owner = statement;
        if (owner != null) {
            owner.resultClosed(this);
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    /** The value of column {@code i}, of the current row; whether it was null noted. */
    private Value value(int i) throws SQLException {
        open();
        List<Value> current = row;
        if (current == null) {
            throw Errors.misuse("the result set is on no row: call next first");
        }
        if (i < 1 || i > current.size()) {
            throw Errors.misuse("column " + i + " is not of 1 to " + current.size());
        }
        Value v = current.get(i - 1);
        wasNull = v instanceof Value.Null;
        return v;
    }

    @Override
    public boolean wasNull() throws SQLException {
        open();
        return wasNull;
    }

    @Override
    public @Nullable String getString(int i) throws SQLException {
        Value v = value(i);
        return wasNull ? null : FromValue.string(v, connection.session().lobs());
    }

    @Override
    public @Nullable String getNString(int i) throws SQLException {
        return getString(i);
    }

    @Override
    public boolean getBoolean(int i) throws SQLException {
        Value v = value(i);
        return !wasNull && FromValue.truth(v);
    }

    @Override
    public byte getByte(int i) throws SQLException {
        return (byte) whole(i, Byte.MIN_VALUE, Byte.MAX_VALUE);
    }

    @Override
    public short getShort(int i) throws SQLException {
        return (short) whole(i, Short.MIN_VALUE, Short.MAX_VALUE);
    }

    @Override
    public int getInt(int i) throws SQLException {
        return (int) whole(i, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    @Override
    public long getLong(int i) throws SQLException {
        return whole(i, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    private long whole(int i, long least, long most) throws SQLException {
        Value v = value(i);
        return wasNull ? 0 : FromValue.whole(v, least, most);
    }

    @Override
    public float getFloat(int i) throws SQLException {
        return (float) getDouble(i);
    }

    @Override
    public double getDouble(int i) throws SQLException {
        Value v = value(i);
        return wasNull ? 0 : FromValue.approximate(v);
    }

    @Override
    public @Nullable BigDecimal getBigDecimal(int i) throws SQLException {
        Value v = value(i);
        return wasNull ? null : FromValue.decimal(v);
    }

    @Override
    @Deprecated
    public @Nullable BigDecimal getBigDecimal(int i, int scale) throws SQLException {
        BigDecimal n = getBigDecimal(i);
        return n == null ? null : n.setScale(scale, RoundingMode.HALF_UP);
    }

    @Override
    public byte @Nullable [] getBytes(int i) throws SQLException {
        Value v = value(i);
        return wasNull ? null : FromValue.bytes(v, connection.session().lobs());
    }

    @Override
    public @Nullable Date getDate(int i) throws SQLException {
        Value v = value(i);
        return wasNull ? null : Date.valueOf(FromValue.local(v).toLocalDate());
    }

    @Override
    public @Nullable Time getTime(int i) throws SQLException {
        Value v = value(i);
        return wasNull ? null : Time.valueOf(FromValue.time(v));
    }

    @Override
    public @Nullable Timestamp getTimestamp(int i) throws SQLException {
        return getTimestamp(i, null);
    }

    @Override
    public @Nullable Date getDate(int i, @Nullable Calendar cal) throws SQLException {
        Timestamp at = getTimestamp(i, cal);
        return at == null ? null : new Date(at.getTime());
    }

    @Override
    public @Nullable Time getTime(int i, @Nullable Calendar cal) throws SQLException {
        Timestamp at = getTimestamp(i, cal);
        return at == null ? null : new Time(at.getTime());
    }

    @Override
    public @Nullable Timestamp getTimestamp(int i, @Nullable Calendar cal) throws SQLException {
        Value v = value(i);
        if (wasNull) {
            return null;
        }
        if (cal == null || (v instanceof Value.Datetime d && d.zoned())) {
            return v instanceof Value.Datetime d && d.zoned()
                    ? Timestamp.from(FromValue.offset(v).toInstant())
                    : Timestamp.valueOf(FromValue.local(v));
        }
        return Timestamp.from(
                FromValue.local(v).atZone(cal.getTimeZone().toZoneId()).toInstant());
    }

    @Override
    public @Nullable InputStream getAsciiStream(int i) throws SQLException {
        return getBinaryStream(i);
    }

    @Override
    @Deprecated
    public @Nullable InputStream getUnicodeStream(int i) throws SQLException {
        String text = getString(i);
        return text == null ? null : new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_16BE));
    }

    @Override
    public @Nullable InputStream getBinaryStream(int i) throws SQLException {
        Value v = value(i);
        if (wasNull) {
            return null;
        }
        if (v instanceof Lob lob) {
            return connection.session().lobs().reader(lob);
        }
        return new ByteArrayInputStream(
                v instanceof Value.Text t
                        ? t.text().getBytes(StandardCharsets.UTF_8)
                        : FromValue.bytes(v, connection.session().lobs()));
    }

    @Override
    public @Nullable Reader getCharacterStream(int i) throws SQLException {
        Value v = value(i);
        if (wasNull) {
            return null;
        }
        if (v instanceof Lob.Locator l && l.kind() == LobKind.CHARACTER) {
            return new JdbcClob(l, connection.session().lobs()).getCharacterStream();
        }
        return new StringReader(FromValue.string(v, connection.session().lobs()));
    }

    @Override
    public @Nullable Reader getNCharacterStream(int i) throws SQLException {
        return getCharacterStream(i);
    }

    @Override
    public @Nullable Object getObject(int i) throws SQLException {
        Value v = value(i);
        return ReadObject.object(v, columns.get(i - 1), connection);
    }

    @Override
    public @Nullable Object getObject(int i, @Nullable Map<String, Class<?>> map) throws SQLException {
        return getObject(i);
    }

    @Override
    public <T> @Nullable T getObject(int i, @Nullable Class<T> type) throws SQLException {
        if (type == null) {
            throw Errors.misuse("getObject is given the class to read as");
        }
        Value v = value(i);
        return ReadObject.as(v, type, columns.get(i - 1), connection);
    }

    @Override
    public @Nullable Blob getBlob(int i) throws SQLException {
        Value v = value(i);
        if (wasNull) {
            return null;
        }
        return v instanceof Lob.Locator l
                ? new JdbcBlob(l, connection.session().lobs())
                : new JdbcBlob(FromValue.bytes(v, connection.session().lobs()));
    }

    @Override
    public @Nullable Clob getClob(int i) throws SQLException {
        return getNClob(i);
    }

    @Override
    public @Nullable NClob getNClob(int i) throws SQLException {
        Value v = value(i);
        if (wasNull) {
            return null;
        }
        return v instanceof Lob.Locator l && l.kind() == LobKind.CHARACTER
                ? new JdbcClob(l, connection.session().lobs())
                : new JdbcClob(FromValue.string(v, connection.session().lobs()));
    }

    @Override
    public @Nullable SQLXML getSQLXML(int i) throws SQLException {
        Value v = value(i);
        return wasNull
                ? null
                : new JdbcSQLXML(FromValue.string(v, connection.session().lobs()));
    }

    @Override
    public @Nullable Array getArray(int i) throws SQLException {
        Value v = value(i);
        if (wasNull) {
            return null;
        }
        if (!(v instanceof Value.Array a)) {
            throw Errors.conversion("column " + i + " is no array");
        }
        return new JdbcArray(JdbcArray.element(columns.get(i - 1)), a.elements(), connection);
    }

    @Override
    public Ref getRef(int i) throws SQLException {
        throw Errors.unsupported("a REF of a result set");
    }

    @Override
    public URL getURL(int i) throws SQLException {
        throw Errors.unsupported("a DATALINK");
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        open();
        return new JdbcResultSetMetaData(columns);
    }

    @Override
    public int findColumn(String label) throws SQLException {
        open();
        for (int i = 0; i < columns.size(); i++) {
            String name = columns.get(i).name();
            if (name != null && name.equalsIgnoreCase(label)) {
                return i + 1;
            }
        }
        throw Errors.misuse("the result set has no column " + label.toUpperCase(Locale.ROOT));
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
    public boolean isBeforeFirst() throws SQLException {
        open();
        return read == 0 && !ended;
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        open();
        return ended && read > 0;
    }

    @Override
    public boolean isFirst() throws SQLException {
        open();
        return read == 1 && row != null;
    }

    @Override
    public boolean isLast() throws SQLException {
        throw Errors.unsupported("isLast of a forward-only result set");
    }

    @Override
    public int getRow() throws SQLException {
        open();
        return row == null ? 0 : (int) Math.min(Integer.MAX_VALUE, read);
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        open();
        if (direction != FETCH_FORWARD) {
            throw Errors.misuse("a forward-only result set fetches forward alone");
        }
    }

    @Override
    public int getFetchDirection() throws SQLException {
        open();
        return FETCH_FORWARD;
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
    public int getType() throws SQLException {
        open();
        return TYPE_FORWARD_ONLY;
    }

    @Override
    public int getConcurrency() throws SQLException {
        open();
        return CONCUR_READ_ONLY;
    }

    @Override
    public int getHoldability() throws SQLException {
        open();
        return CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public java.sql.@Nullable Statement getStatement() throws SQLException {
        open();
        return statement;
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
