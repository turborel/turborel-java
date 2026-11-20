package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Completed;
import com.turborel.jdbc.internal.codec.Request;
import com.turborel.jdbc.internal.codec.RowSize;
import com.turborel.jdbc.internal.codec.Value;
import java.util.ArrayList;
import java.util.List;

/**
 * A copy into a table under way ([CLIENT Copying and exporting], [PROTOCOL CopyIn 'I']): rows sent
 * with no round trip but the copy's end, in chunks of about {@link #CHUNK_BYTES}; {@link #finish}
 * ends it, its reply the rows inserted, or the copy's failure, every row of it undone. The server
 * assigns each value to its column as INSERT does, so a string too long for its column is refused,
 * not cut; a copy is all or nothing, within the transaction, or its own under autocommit. A copy
 * closed before it ends is abandoned, so the connection takes requests again. As Turborel's {@code
 * client/src/copy.rs}.
 */
public final class Copying implements AutoCloseable {
    /** The bytes of rows a CopyData carries at most, about: Turborel's {@code CHUNK_BYTES}, 256 KiB. */
    public static final int CHUNK_BYTES = 256 * 1024;

    private final Connection connection;
    private List<List<Value>> waiting = new ArrayList<>();
    private int bytes;
    private boolean ended;

    private Copying(Connection connection) {
        this.connection = connection;
    }

    /**
     * Begins a copy into {@code table}'s {@code columns}, the transaction ended with it if {@code
     * ends}.
     *
     * @param connection the connection
     * @param table the table, as SQL names it
     * @param columns its columns, in order, all of the table's if none
     * @param ends whether the copy is its transaction's own
     * @return the copy
     * @throws TurborelException a connection failure: {@code copy} not granted, or the server gone
     */
    static Copying begin(Connection connection, String table, List<String> columns, boolean ends)
            throws TurborelException {
        connection.needs(false, Capability.COPY);
        connection.send(new Request.CopyIn(table, columns, ends));
        connection.copying(true);
        return new Copying(connection);
    }

    /**
     * Sends a row, with those before it once they are {@link #CHUNK_BYTES}.
     *
     * @param row a value for each column
     * @throws TurborelException a connection failure; a row the server refuses comes as {@link
     *     #finish}'s failure
     */
    public void send(List<Value> row) throws TurborelException {
        bytes += RowSize.of(row);
        waiting.add(row);
        if (bytes >= CHUNK_BYTES) {
            flush();
        }
    }

    private void flush() throws TurborelException {
        if (waiting.isEmpty()) {
            return;
        }
        bytes = 0;
        List<List<Value>> rows = waiting;
        waiting = new ArrayList<>();
        connection.send(new Request.CopyData(rows));
    }

    /**
     * Ends the copy: the rows inserted.
     *
     * @return the rows inserted
     * @throws TurborelException the copy's failure, every row of it undone: the table or a column
     *     not found, a row of too many or too few values, a value its column refuses, its detail
     *     naming the row, as {@code row 7 of the copy}; a connection failure
     */
    public long finish() throws TurborelException {
        flush();
        return end(false);
    }

    /**
     * Gives the copy up: every row of it undone.
     *
     * @throws TurborelException a connection failure
     */
    public void abandon() throws TurborelException {
        end(true);
    }

    private long end(boolean abandon) throws TurborelException {
        ended = true;
        connection.send(new Request.CopyEnd(abandon));
        connection.copying(false);
        Completed completed = connection.statements().completion();
        Long rows = completed.fields().rows();
        return rows == null ? 0 : rows;
    }

    /**
     * Abandons the copy if it has not ended, so the connection takes requests again. A failure of
     * that has lost the connection, which says so at its next request, as Turborel's client lets
     * a copy dropped go.
     */
    @Override
    public void close() {
        if (!ended && !connection.isLost()) {
            try {
                abandon();
            } catch (TurborelException lost) {
                // The connection is lost, and its next request says so.
            }
        }
    }
}
