package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.internal.codec.Column;
import com.turborel.jdbc.internal.codec.Completed;
import com.turborel.jdbc.internal.codec.Value;
import java.util.List;

/**
 * What a statement gave ([CLIENT Running statements]): a query's columns and rows, or another
 * statement's completion. As Turborel's {@code Outcome}.
 */
public sealed interface Outcome {
    /**
     * A query's columns and rows; of a CALL with output parameters, a row of their values.
     *
     * @param columns the columns
     * @param rows the rows, each a value for each column
     */
    record Rows(List<Column> columns, List<List<Value>> rows) implements Outcome {
        /**
         * A query's outcome, of the fields given.
         *
         * @param columns the columns, copied
         * @param rows the rows, copied
         */
        public Rows {
            columns = List.copyOf(columns);
            rows = rows.stream().map(List::copyOf).toList();
        }
    }

    /**
     * Any other statement's completion: its SQLCODE, 0 or 100, and its SQLSTATE, a warning's among
     * them.
     *
     * @param completed the completion
     */
    record Done(Completed completed) implements Outcome {}
}
