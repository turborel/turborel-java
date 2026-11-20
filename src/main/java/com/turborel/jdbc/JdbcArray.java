package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.Column;
import com.turborel.jdbc.internal.codec.DataType;
import com.turborel.jdbc.internal.codec.Value;
import java.math.BigInteger;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An array ([DESIGN] D2): of a row, its elements read as {@code getObject} reads each; or one the
 * program made by {@code createArrayOf}, sent with its elements as a parameter of its type.
 */
final class JdbcArray implements Array {
    /** The type of an array's index, as its result set gives it. */
    private static final DataType INDEX = new DataType.ExactType(10, 0);

    private final @Nullable Column element;
    private final String baseName;
    private final List<Value> elements;
    private final @Nullable Object @Nullable [] made;
    private final @Nullable JdbcConnection owner;
    private boolean freed;

    /** An array of a row, of elements of the column {@code element}, its type and declared type. */
    JdbcArray(@Nullable Column element, List<Value> elements, JdbcConnection owner) {
        this.element = element;
        this.baseName = element == null ? "NULL" : JdbcTypes.name(element);
        this.elements = List.copyOf(elements);
        this.made = null;
        this.owner = owner;
    }

    /** An array the program made, of {@code items}, its elements of type {@code baseName}. */
    JdbcArray(String baseName, @Nullable Object[] items) {
        this.element = null;
        this.baseName = baseName;
        this.elements = List.of();
        this.made = items.clone();
        this.owner = null;
    }

    private void live() throws SQLException {
        if (freed) {
            throw Errors.misuse("the Array is freed");
        }
    }

    @Override
    public String getBaseTypeName() throws SQLException {
        live();
        return baseName;
    }

    @Override
    public int getBaseType() throws SQLException {
        live();
        return element == null ? JdbcTypes.named(baseName) : JdbcTypes.of(element);
    }

    @Override
    public Object getArray() throws SQLException {
        return slice(1, Integer.MAX_VALUE);
    }

    @Override
    public Object getArray(@Nullable Map<String, Class<?>> map) throws SQLException {
        return getArray();
    }

    @Override
    public Object getArray(long index, int count) throws SQLException {
        return slice(index, count);
    }

    @Override
    public Object getArray(long index, int count, @Nullable Map<String, Class<?>> map) throws SQLException {
        return slice(index, count);
    }

    private @Nullable Object[] slice(long index, int count) throws SQLException {
        live();
        @Nullable Object @Nullable [] items = made;
        int size = items != null ? items.length : elements.size();
        int from = (int) Math.min(Math.max(0, index - 1), size);
        int to = (int) Math.min(size, from + (long) count);
        if (items != null) {
            return java.util.Arrays.copyOfRange(items, from, to);
        }
        JdbcConnection connection = owner;
        @Nullable Object[] read = new Object[to - from];
        for (int i = from; i < to; i++) {
            read[i - from] = connection == null ? null : connection.object(elements.get(i), element);
        }
        return read;
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        return getResultSet(1, Integer.MAX_VALUE);
    }

    @Override
    public ResultSet getResultSet(@Nullable Map<String, Class<?>> map) throws SQLException {
        return getResultSet();
    }

    @Override
    public ResultSet getResultSet(long index, int count) throws SQLException {
        live();
        JdbcConnection connection = owner;
        if (connection == null) {
            throw Errors.unsupported("the result set of an array the program made");
        }
        int from = (int) Math.min(Math.max(0, index - 1), elements.size());
        int to = (int) Math.min(elements.size(), from + (long) count);
        List<List<Value>> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            rows.add(List.of(new Value.Exact(BigInteger.valueOf(i + 1L), 0), elements.get(i)));
        }
        Column value = element == null
                ? new Column(
                        "VALUE",
                        new DataType.CharacterType(true, 0, com.turborel.jdbc.internal.codec.CharUnits.CHARACTERS),
                        false)
                : new Column("VALUE", element.type(), false, element.declared());
        List<Column> columns = List.of(new Column("INDEX", INDEX, true), value);
        return new JdbcResultSet(null, connection, columns, Rows.of(rows));
    }

    @Override
    public ResultSet getResultSet(long index, int count, @Nullable Map<String, Class<?>> map) throws SQLException {
        return getResultSet(index, count);
    }

    /** The value it is sent as, of no type known: its elements of their own. */
    @Nullable
    Object[] items() throws SQLException {
        live();
        @Nullable Object @Nullable [] items = made;
        return items != null ? items.clone() : elements.toArray();
    }

    @Override
    public void free() {
        freed = true;
    }

    /**
     * The column of an array {@code column}'s elements: its element type, and its declared type, of
     * the array's written without its cardinality, if told.
     *
     * @param column the array's column
     * @return the elements' column, or none if the column is of no array type
     */
    static @Nullable Column element(Column column) {
        if (!(column.type() instanceof DataType.ArrayType array)) {
            return null;
        }
        com.turborel.jdbc.internal.codec.Declared told = column.declared();
        if (told == null) {
            return new Column(null, array.element(), false);
        }
        String written = told.written().replaceFirst(" ARRAY\\[\\d+\\]$", "");
        String name = written.replaceAll("\\([^)]*\\)", "");
        return new Column(null, array.element(), false, new com.turborel.jdbc.internal.codec.Declared(name, written));
    }
}
