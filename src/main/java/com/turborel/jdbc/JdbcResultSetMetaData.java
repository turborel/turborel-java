package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.Column;
import com.turborel.jdbc.internal.codec.DataType;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A result set's columns ([DESIGN] D2): each one's name and type as the server described it, its
 * declared type where told, mapped as {@link JdbcTypes} maps it. The protocol tells no column's table, so its table,
 * schema and catalog are empty.
 */
final class JdbcResultSetMetaData implements ResultSetMetaData {
    private final List<Column> columns;

    JdbcResultSetMetaData(List<Column> columns) {
        this.columns = List.copyOf(columns);
    }

    private Column column(int i) throws SQLException {
        if (i < 1 || i > columns.size()) {
            throw Errors.misuse("column " + i + " is not of 1 to " + columns.size());
        }
        return columns.get(i - 1);
    }

    private DataType type(int i) throws SQLException {
        return column(i).type();
    }

    @Override
    public int getColumnCount() {
        return columns.size();
    }

    @Override
    public boolean isAutoIncrement(int i) throws SQLException {
        column(i);
        return false;
    }

    @Override
    public boolean isCaseSensitive(int i) throws SQLException {
        return JdbcTypes.caseSensitive(type(i));
    }

    @Override
    public boolean isSearchable(int i) throws SQLException {
        column(i);
        return true;
    }

    @Override
    public boolean isCurrency(int i) throws SQLException {
        column(i);
        return false;
    }

    @Override
    public int isNullable(int i) throws SQLException {
        return column(i).notNull() ? columnNoNulls : columnNullable;
    }

    @Override
    public boolean isSigned(int i) throws SQLException {
        return JdbcTypes.signed(type(i));
    }

    @Override
    public int getColumnDisplaySize(int i) throws SQLException {
        return JdbcTypes.displaySize(type(i));
    }

    @Override
    public String getColumnLabel(int i) throws SQLException {
        return getColumnName(i);
    }

    @Override
    public String getColumnName(int i) throws SQLException {
        String name = column(i).name();
        return name == null ? "" : name;
    }

    @Override
    public String getSchemaName(int i) throws SQLException {
        column(i);
        return "";
    }

    @Override
    public int getPrecision(int i) throws SQLException {
        return JdbcTypes.precision(type(i));
    }

    @Override
    public int getScale(int i) throws SQLException {
        return JdbcTypes.scale(type(i));
    }

    @Override
    public String getTableName(int i) throws SQLException {
        column(i);
        return "";
    }

    @Override
    public String getCatalogName(int i) throws SQLException {
        column(i);
        return "";
    }

    @Override
    public int getColumnType(int i) throws SQLException {
        return JdbcTypes.of(column(i));
    }

    @Override
    public String getColumnTypeName(int i) throws SQLException {
        return JdbcTypes.name(column(i));
    }

    @Override
    public boolean isReadOnly(int i) throws SQLException {
        column(i);
        return false;
    }

    @Override
    public boolean isWritable(int i) throws SQLException {
        column(i);
        return false;
    }

    @Override
    public boolean isDefinitelyWritable(int i) throws SQLException {
        column(i);
        return false;
    }

    @Override
    public String getColumnClassName(int i) throws SQLException {
        return JdbcTypes.className(column(i));
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
