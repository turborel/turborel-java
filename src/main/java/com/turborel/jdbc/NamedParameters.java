package com.turborel.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A callable statement's parameters by name ([DESIGN] D2): an OUT parameter read by its column's
 * name, as the server names it; none set by name, as the protocol's parameters have none.
 */
abstract class NamedParameters extends JdbcPreparedStatement implements CallableStatement {
    NamedParameters(JdbcConnection connection, String sql) throws SQLException {
        super(connection, sql);
    }

    /**
     * The OUT parameters' values, read by name.
     *
     * @return their row
     * @throws SQLException SQLSTATE TB910 before the statement is executed
     */
    abstract JdbcResultSet byName() throws SQLException;

    @Override
    public void setURL(String name, @Nullable URL p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setNull(String name, int p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setBoolean(String name, boolean p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setByte(String name, byte p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setShort(String name, short p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setInt(String name, int p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setLong(String name, long p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setFloat(String name, float p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setDouble(String name, double p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setBigDecimal(String name, @Nullable BigDecimal p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setString(String name, @Nullable String p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setBytes(String name, byte @Nullable [] p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setDate(String name, @Nullable Date p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setTime(String name, @Nullable Time p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setTimestamp(String name, @Nullable Timestamp p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setAsciiStream(String name, @Nullable InputStream p1, int p2) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setBinaryStream(String name, @Nullable InputStream p1, int p2) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setObject(String name, @Nullable Object p1, int p2, int p3) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setObject(String name, @Nullable Object p1, int p2) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setObject(String name, @Nullable Object p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setCharacterStream(String name, @Nullable Reader p1, int p2) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setDate(String name, @Nullable Date p1, @Nullable Calendar p2) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setTime(String name, @Nullable Time p1, @Nullable Calendar p2) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setTimestamp(String name, @Nullable Timestamp p1, @Nullable Calendar p2) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setNull(String name, int p1, @Nullable String p2) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public @Nullable String getString(String name) throws SQLException {
        return byName().getString(name);
    }

    @Override
    public boolean getBoolean(String name) throws SQLException {
        return byName().getBoolean(name);
    }

    @Override
    public byte getByte(String name) throws SQLException {
        return byName().getByte(name);
    }

    @Override
    public short getShort(String name) throws SQLException {
        return byName().getShort(name);
    }

    @Override
    public int getInt(String name) throws SQLException {
        return byName().getInt(name);
    }

    @Override
    public long getLong(String name) throws SQLException {
        return byName().getLong(name);
    }

    @Override
    public float getFloat(String name) throws SQLException {
        return byName().getFloat(name);
    }

    @Override
    public double getDouble(String name) throws SQLException {
        return byName().getDouble(name);
    }

    @Override
    public byte @Nullable [] getBytes(String name) throws SQLException {
        return byName().getBytes(name);
    }

    @Override
    public @Nullable Date getDate(String name) throws SQLException {
        return byName().getDate(name);
    }

    @Override
    public @Nullable Time getTime(String name) throws SQLException {
        return byName().getTime(name);
    }

    @Override
    public @Nullable Timestamp getTimestamp(String name) throws SQLException {
        return byName().getTimestamp(name);
    }

    @Override
    public @Nullable Object getObject(String name) throws SQLException {
        return byName().getObject(name);
    }

    @Override
    public @Nullable BigDecimal getBigDecimal(String name) throws SQLException {
        return byName().getBigDecimal(name);
    }

    @Override
    public @Nullable Object getObject(String name, @Nullable Map<String, Class<?>> p1) throws SQLException {
        return byName().getObject(name, p1);
    }

    @Override
    public Ref getRef(String name) throws SQLException {
        throw Errors.unsupported("getRef");
    }

    @Override
    public @Nullable Blob getBlob(String name) throws SQLException {
        return byName().getBlob(name);
    }

    @Override
    public @Nullable Clob getClob(String name) throws SQLException {
        return byName().getClob(name);
    }

    @Override
    public @Nullable Array getArray(String name) throws SQLException {
        return byName().getArray(name);
    }

    @Override
    public @Nullable Date getDate(String name, @Nullable Calendar p1) throws SQLException {
        return byName().getDate(name, p1);
    }

    @Override
    public @Nullable Time getTime(String name, @Nullable Calendar p1) throws SQLException {
        return byName().getTime(name, p1);
    }

    @Override
    public @Nullable Timestamp getTimestamp(String name, @Nullable Calendar p1) throws SQLException {
        return byName().getTimestamp(name, p1);
    }

    @Override
    public URL getURL(String name) throws SQLException {
        throw Errors.unsupported("getURL");
    }

    @Override
    public RowId getRowId(String name) throws SQLException {
        throw Errors.unsupported("getRowId");
    }

    @Override
    public void setRowId(String name, @Nullable RowId p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setNString(String name, @Nullable String p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setNCharacterStream(String name, @Nullable Reader p1, long p2) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setNClob(String name, @Nullable NClob p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setClob(String name, @Nullable Reader p1, long p2) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setBlob(String name, @Nullable InputStream p1, long p2) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setNClob(String name, @Nullable Reader p1, long p2) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public @Nullable NClob getNClob(String name) throws SQLException {
        return byName().getNClob(name);
    }

    @Override
    public void setSQLXML(String name, @Nullable SQLXML p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public @Nullable SQLXML getSQLXML(String name) throws SQLException {
        return byName().getSQLXML(name);
    }

    @Override
    public @Nullable String getNString(String name) throws SQLException {
        return byName().getNString(name);
    }

    @Override
    public @Nullable Reader getNCharacterStream(String name) throws SQLException {
        return byName().getNCharacterStream(name);
    }

    @Override
    public @Nullable Reader getCharacterStream(String name) throws SQLException {
        return byName().getCharacterStream(name);
    }

    @Override
    public void setBlob(String name, @Nullable Blob p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setClob(String name, @Nullable Clob p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setAsciiStream(String name, @Nullable InputStream p1, long p2) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setBinaryStream(String name, @Nullable InputStream p1, long p2) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setCharacterStream(String name, @Nullable Reader p1, long p2) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setAsciiStream(String name, @Nullable InputStream p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setBinaryStream(String name, @Nullable InputStream p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setCharacterStream(String name, @Nullable Reader p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setNCharacterStream(String name, @Nullable Reader p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setClob(String name, @Nullable Reader p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setBlob(String name, @Nullable InputStream p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setNClob(String name, @Nullable Reader p1) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public <T> @Nullable T getObject(String name, @Nullable Class<T> p1) throws SQLException {
        return byName().getObject(name, p1);
    }

    @Override
    public void setObject(String name, @Nullable Object p1, @Nullable SQLType p2, int p3) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }

    @Override
    public void setObject(String name, @Nullable Object p1, @Nullable SQLType p2) throws SQLException {
        throw Errors.unsupported("a parameter set by its name");
    }
}
