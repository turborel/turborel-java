package com.turborel.jdbc;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A result set's getters by a column's label, each that of the column {@code findColumn} finds ([DESIGN] D2). */
abstract class ResultSetByName extends ReadOnlyResultSet {

    @Override
    public @Nullable String getString(String label) throws SQLException {
        return getString(findColumn(label));
    }

    @Override
    public boolean getBoolean(String label) throws SQLException {
        return getBoolean(findColumn(label));
    }

    @Override
    public byte getByte(String label) throws SQLException {
        return getByte(findColumn(label));
    }

    @Override
    public short getShort(String label) throws SQLException {
        return getShort(findColumn(label));
    }

    @Override
    public int getInt(String label) throws SQLException {
        return getInt(findColumn(label));
    }

    @Override
    public long getLong(String label) throws SQLException {
        return getLong(findColumn(label));
    }

    @Override
    public float getFloat(String label) throws SQLException {
        return getFloat(findColumn(label));
    }

    @Override
    public double getDouble(String label) throws SQLException {
        return getDouble(findColumn(label));
    }

    @Override
    @Deprecated
    public @Nullable BigDecimal getBigDecimal(String label, int p1) throws SQLException {
        return getBigDecimal(findColumn(label), p1);
    }

    @Override
    public byte @Nullable [] getBytes(String label) throws SQLException {
        return getBytes(findColumn(label));
    }

    @Override
    public java.sql.@Nullable Date getDate(String label) throws SQLException {
        return getDate(findColumn(label));
    }

    @Override
    public java.sql.@Nullable Time getTime(String label) throws SQLException {
        return getTime(findColumn(label));
    }

    @Override
    public java.sql.@Nullable Timestamp getTimestamp(String label) throws SQLException {
        return getTimestamp(findColumn(label));
    }

    @Override
    public java.io.@Nullable InputStream getAsciiStream(String label) throws SQLException {
        return getAsciiStream(findColumn(label));
    }

    @Override
    @Deprecated
    public java.io.@Nullable InputStream getUnicodeStream(String label) throws SQLException {
        return getUnicodeStream(findColumn(label));
    }

    @Override
    public java.io.@Nullable InputStream getBinaryStream(String label) throws SQLException {
        return getBinaryStream(findColumn(label));
    }

    @Override
    public @Nullable Object getObject(String label) throws SQLException {
        return getObject(findColumn(label));
    }

    @Override
    public java.io.@Nullable Reader getCharacterStream(String label) throws SQLException {
        return getCharacterStream(findColumn(label));
    }

    @Override
    public @Nullable BigDecimal getBigDecimal(String label) throws SQLException {
        return getBigDecimal(findColumn(label));
    }

    @Override
    public @Nullable Object getObject(String label, @Nullable Map<String, Class<?>> p1) throws SQLException {
        return getObject(findColumn(label), p1);
    }

    @Override
    public java.sql.Ref getRef(String label) throws SQLException {
        return getRef(findColumn(label));
    }

    @Override
    public java.sql.@Nullable Blob getBlob(String label) throws SQLException {
        return getBlob(findColumn(label));
    }

    @Override
    public java.sql.@Nullable Clob getClob(String label) throws SQLException {
        return getClob(findColumn(label));
    }

    @Override
    public java.sql.@Nullable Array getArray(String label) throws SQLException {
        return getArray(findColumn(label));
    }

    @Override
    public java.sql.@Nullable Date getDate(String label, java.util.@Nullable Calendar p1) throws SQLException {
        return getDate(findColumn(label), p1);
    }

    @Override
    public java.sql.@Nullable Time getTime(String label, java.util.@Nullable Calendar p1) throws SQLException {
        return getTime(findColumn(label), p1);
    }

    @Override
    public java.sql.@Nullable Timestamp getTimestamp(String label, java.util.@Nullable Calendar p1)
            throws SQLException {
        return getTimestamp(findColumn(label), p1);
    }

    @Override
    public java.net.URL getURL(String label) throws SQLException {
        return getURL(findColumn(label));
    }

    @Override
    public java.sql.RowId getRowId(String label) throws SQLException {
        return getRowId(findColumn(label));
    }

    @Override
    public java.sql.@Nullable NClob getNClob(String label) throws SQLException {
        return getNClob(findColumn(label));
    }

    @Override
    public java.sql.@Nullable SQLXML getSQLXML(String label) throws SQLException {
        return getSQLXML(findColumn(label));
    }

    @Override
    public @Nullable String getNString(String label) throws SQLException {
        return getNString(findColumn(label));
    }

    @Override
    public java.io.@Nullable Reader getNCharacterStream(String label) throws SQLException {
        return getNCharacterStream(findColumn(label));
    }

    @Override
    public <T> @Nullable T getObject(String label, @Nullable Class<T> p1) throws SQLException {
        return getObject(findColumn(label), p1);
    }
}
