package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.internal.session.Batch;
import com.turborel.jdbc.internal.session.Statement;
import com.turborel.jdbc.internal.session.Statements;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Where a result set's rows come from ([DESIGN] D2): rows held, or a cursor read a batch at a time. */
interface Rows {
    /**
     * The next row, or none after the last.
     *
     * @return the row
     * @throws SQLException a fetch's failure
     */
    @Nullable
    List<Value> next() throws SQLException;

    /**
     * Ends the rows: a cursor closed, its statement destroyed.
     *
     * @throws SQLException a close's failure
     */
    void close() throws SQLException;

    /**
     * Rows held.
     *
     * @param rows the rows
     * @return their source
     */
    static Rows of(List<List<Value>> rows) {
        Iterator<List<Value>> each = rows.iterator();
        return new Rows() {
            @Override
            public @Nullable List<Value> next() {
                return each.hasNext() ? each.next() : null;
            }

            @Override
            public void close() {
                // Nothing to end.
            }
        };
    }

    /**
     * The rows of a cursor of {@code statement}, its first batch read, the rest fetched {@code
     * fetch} at a time; the statement destroyed as the rows end if {@code owned}.
     *
     * @param statements what runs the statement
     * @param statement the statement
     * @param first its first batch
     * @param fetch how many rows each fetch takes
     * @param owned whether the rows destroy the statement as they end
     * @return their source
     */
    static Rows cursor(Statements statements, Statement statement, Batch first, long fetch, boolean owned) {
        return new Cursor(statements, statement, first, fetch, owned);
    }

    /** The rows of a cursor. */
    final class Cursor implements Rows {
        private final Statements statements;
        private final Statement statement;
        private final long fetch;
        private final boolean owned;
        private Iterator<List<Value>> batch;
        private boolean more;
        private boolean open = true;

        Cursor(Statements statements, Statement statement, Batch first, long fetch, boolean owned) {
            this.statements = statements;
            this.statement = statement;
            this.fetch = fetch;
            this.owned = owned;
            this.batch = first.rows().iterator();
            this.more = first.more();
        }

        @Override
        public @Nullable List<Value> next() throws SQLException {
            while (!batch.hasNext() && more) {
                Batch next = statements.fetch(statement, fetch);
                batch = next.rows().iterator();
                more = next.more();
            }
            return batch.hasNext() ? batch.next() : null;
        }

        @Override
        public void close() throws SQLException {
            if (!open) {
                return;
            }
            open = false;
            try {
                if (more) {
                    statements.closeCursor(statement);
                }
            } finally {
                if (owned) {
                    statements.deallocate(statement);
                }
            }
        }
    }
}
