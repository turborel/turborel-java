package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.internal.codec.Value;
import java.util.List;

/**
 * Rows of a cursor, and whether it has more ([CLIENT Prepared statements]).
 *
 * @param rows the rows
 * @param more whether the cursor has more, which a fetch reads; closed if not
 */
public record Batch(List<List<Value>> rows, boolean more) {
    /**
     * A batch of the fields given.
     *
     * @param rows the rows, copied
     * @param more whether the cursor has more
     */
    public Batch {
        rows = rows.stream().map(List::copyOf).toList();
    }
}
