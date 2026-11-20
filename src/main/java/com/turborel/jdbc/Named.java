package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.Column;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.internal.codec.ValueText;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A row of a query of the driver's own, its values read by their columns' names ([DESIGN] D2). */
final class Named {
    private final Map<String, Integer> at;
    private final List<Value> row;

    private Named(Map<String, Integer> at, List<Value> row) {
        this.at = at;
        this.row = row;
    }

    /**
     * The rows of a query's columns and rows, each read by name.
     *
     * @param columns the columns
     * @param rows the rows
     * @return the rows read by name
     */
    static List<Named> of(List<Column> columns, List<List<Value>> rows) {
        Map<String, Integer> at = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            String name = columns.get(i).name();
            if (name != null) {
                at.putIfAbsent(name, i);
            }
        }
        return rows.stream().map(row -> new Named(at, row)).toList();
    }

    /**
     * The value of the column named {@code column}.
     *
     * @param column the column's name
     * @return its value
     */
    Value get(String column) {
        Integer i = at.get(column);
        return i == null ? Value.NULL : row.get(i);
    }

    /**
     * The text of the column named {@code column}, none for the null value.
     *
     * @param column the column's name
     * @return its text
     */
    @Nullable
    String text(String column) {
        Value v = get(column);
        return v instanceof Value.Null ? null : v instanceof Value.Text t ? t.text() : ValueText.of(v);
    }

    /**
     * The integer of the column named {@code column}, none for the null value.
     *
     * @param column the column's name
     * @return its integer
     * @throws SQLException if it is no number
     */
    @Nullable
    Long whole(String column) throws SQLException {
        Value v = get(column);
        return v instanceof Value.Null ? null : FromValue.whole(v, Long.MIN_VALUE, Long.MAX_VALUE);
    }
}
