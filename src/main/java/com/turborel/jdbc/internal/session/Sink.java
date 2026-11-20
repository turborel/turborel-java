package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.internal.codec.Column;
import com.turborel.jdbc.internal.codec.Value;
import java.util.List;

/** What takes a query's rows as they come, a batch at a time ([CLIENT Running statements]). */
public interface Sink {
    /**
     * The columns of the rows that follow.
     *
     * @param columns the columns
     */
    void columns(List<Column> columns);

    /**
     * A batch of rows.
     *
     * @param rows the rows, each a value for each column
     */
    void rows(List<List<Value>> rows);
}
