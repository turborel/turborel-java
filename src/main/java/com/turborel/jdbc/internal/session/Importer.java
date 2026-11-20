package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Column;
import com.turborel.jdbc.internal.codec.DataType;
import com.turborel.jdbc.internal.codec.Failures;
import com.turborel.jdbc.internal.codec.Lob;
import com.turborel.jdbc.internal.codec.LobKind;
import com.turborel.jdbc.internal.codec.OctetValue;
import com.turborel.jdbc.internal.codec.Parameter;
import com.turborel.jdbc.internal.codec.Value;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * A source's records inserted into a table ([CLIENT Importing]), as Turborel's {@code
 * client/src/import/insert.rs} and {@code copied.rs}: through one prepared INSERT of the source's
 * headings, or else the table's columns; each string for a character column assigned as INSERT
 * assigns it ({@link Assign}); a batch of rows committed at a time; a failure rolling the batch back
 * and stopping, or, if asked, a bad row skipped and reported. By bulk copy where the server grants
 * it, no bad row is to be skipped and no column is a large object's, each copy committing its
 * batch.
 */
final class Importer {
    private static final String COMMIT = "COMMIT";
    private static final String ROLLBACK = "ROLLBACK";

    /** What the server's detail of a row a copy refused begins with, and ends. */
    private static final String ROW = "row ";

    private static final String OF_THE_COPY = " of the copy";

    /** The diagnostic that names the row a copy refused. */
    private static final String DETAIL = "DETAIL";

    /** The SQLCODE of a statement cancelled, as Turborel's {@code cancelled} reads it. */
    private static final int CANCELLED = -920;

    private final Connection connection;
    private final Import how;
    private Statement insert;
    private long committed;
    private long inBatch;
    private long rejected;

    private Importer(Connection connection, Import how, Statement insert) {
        this.connection = connection;
        this.how = how;
        this.insert = insert;
    }

    /**
     * Inserts the source's records into the table {@code how} names; each row skipped is given to
     * {@code rejected}. The session must have no transaction active, as an import commits its
     * batches.
     */
    static Import.Imported run(Connection connection, Source source, Import how, Consumer<Import.Rejected> rejected)
            throws TurborelException {
        String table = SqlNames.tableName(how.table());
        if (connection.inTransaction()) {
            throw before(Import.Cause.input(
                    "[DESIGN] an import begins with no transaction active, as it commits its batches"));
        }
        if (table == null) {
            throw before(Import.Cause.input("[ISO/IEC 9075:1992 §5.4] " + how.table() + " is not a <table name>"));
        }
        List<String> names = columns(connection, source, table);
        Statements statements = connection.statements();
        Statement insert;
        try {
            insert = statements.prepare("INSERT INTO " + table + " (" + String.join(", ", names) + ") VALUES ("
                    + String.join(", ", java.util.Collections.nCopies(names.size(), "?")) + ")");
        } catch (TurborelException failure) {
            throw before(Import.Cause.sql(failure));
        }
        Importer run = new Importer(connection, how, insert);
        if (copies(connection, insert, how)) {
            try {
                statements.deallocate(insert);
            } catch (TurborelException failure) {
                throw before(Import.Cause.sql(failure));
            }
            return run.copied(source, table, names);
        }
        return run.inserted(source, rejected);
    }

    private static Import.Stopped before(Import.Cause cause) {
        return new Import.Stopped(null, cause, 0, 0);
    }

    /** The source's columns, as delimited identifiers: its headings', else the table's. */
    private static List<String> columns(Connection connection, Source source, String table) throws TurborelException {
        List<Source.Heading> headings;
        try {
            headings = source.headings();
        } catch (Source.Failed failed) {
            throw new Import.Stopped(failed.where(), Import.Cause.input(String.valueOf(failed.getMessage())), 0, 0);
        }
        List<String> names = new ArrayList<>();
        if (headings != null) {
            for (Source.Heading heading : headings) {
                names.add(SqlNames.delimited(SqlNames.columnName(heading.name())));
            }
            return names;
        }
        try {
            Statement query = connection.statements().prepare("SELECT * FROM " + table);
            for (Column column : query.columns()) {
                names.add(SqlNames.delimited(String.valueOf(column.name())));
            }
            connection.statements().deallocate(query);
            return names;
        } catch (TurborelException failure) {
            throw before(Import.Cause.sql(failure));
        }
    }

    private static boolean copies(Connection connection, Statement insert, Import how) {
        boolean lob = insert.parameters().stream().anyMatch(p -> p.type() instanceof DataType.LobType);
        return connection.granted().contains(Capability.COPY) && !how.skip() && !lob;
    }

    private Import.Imported inserted(Source source, Consumer<Import.Rejected> skipped) throws TurborelException {
        Import.Imported done;
        try {
            done = rows(source, skipped);
        } catch (Import.Stopped stopped) {
            if (!connection.isLost()) {
                deallocateQuietly();
            }
            throw stopped;
        }
        try {
            connection.statements().deallocate(insert);
        } catch (TurborelException failure) {
            throw new Import.Stopped(null, Import.Cause.sql(failure), done.rows(), 0);
        }
        return done;
    }

    private void deallocateQuietly() {
        try {
            connection.statements().deallocate(insert);
        } catch (TurborelException ignored) {
            // The import has failed already; that failure is the one given.
        }
    }

    private Import.Imported rows(Source source, Consumer<Import.Rejected> skipped) throws TurborelException {
        while (true) {
            Source.Entry record;
            try {
                record = source.record();
            } catch (Source.Failed failed) {
                throw stop(failed.where(), Import.Cause.input(String.valueOf(failed.getMessage())));
            }
            if (record == null) {
                break;
            }
            row(record, skipped);
        }
        commit();
        return new Import.Imported(committed, rejected);
    }

    private void row(Source.Entry record, Consumer<Import.Rejected> skipped) throws TurborelException {
        int wanted = insert.parameters().size();
        if (record.values().size() != wanted) {
            String message = "[DESIGN] " + record.values().size() + " fields, for " + wanted + " columns";
            reject(new Import.Rejected(record.position(), Import.Cause.input(message)), skipped);
            return;
        }
        try {
            List<Value> values = located(Assign.assigned(record.values(), insert.parameters()));
            connection.statements().execute(insert, values);
        } catch (TurborelException failure) {
            boolean alone = inBatch == 0 || connection.inTransaction();
            if (!alone || connection.isLost() || failure.sqlcode() == CANCELLED) {
                throw stop(record.position(), Import.Cause.sql(failure));
            }
            reject(new Import.Rejected(record.position(), Import.Cause.sql(failure)), skipped);
            return;
        }
        inBatch++;
        if (inBatch == how.batch()) {
            commit();
        }
    }

    /** The values, each long string for a large object parameter sent ahead by its locator. */
    private List<Value> located(List<Value> values) throws TurborelException {
        List<Value> sent = new ArrayList<>();
        List<Parameter> parameters = insert.parameters();
        for (int i = 0; i < values.size(); i++) {
            Value v = values.get(i);
            byte[] bytes = parameters.get(i).type() instanceof DataType.LobType ? longBytes(v) : new byte[0];
            if (bytes.length == 0) {
                sent.add(v);
                continue;
            }
            LobKind kind = ((DataType.LobType) parameters.get(i).type()).kind();
            Lobs.Writer writer = connection.lobs().create(kind);
            try {
                writer.write(bytes);
            } catch (IOException e) {
                throw e.getCause() instanceof TurborelException failure
                        ? failure
                        : new TurborelException(
                                Failures.DATA_SQLCODE, Source.Failed.DATA_EXCEPTION, String.valueOf(e.getMessage()));
            }
            sent.add(writer.finish());
        }
        return sent;
    }

    /** A value's octets if it is longer than a large object is sent whole; else none, empty. */
    private static byte[] longBytes(Value v) {
        byte[] bytes;
        if (v instanceof Value.Text t) {
            bytes = t.text().getBytes(StandardCharsets.UTF_8);
        } else if (v instanceof OctetValue.Binary b) {
            bytes = b.octets().toByteArray();
        } else if (v instanceof Lob.Whole w) {
            bytes = w.octets().toByteArray();
        } else {
            return new byte[0];
        }
        return bytes.length > Lobs.INLINE_OCTETS ? bytes : new byte[0];
    }

    private void reject(Import.Rejected row, Consumer<Import.Rejected> skipped) throws TurborelException {
        if (!how.skip()) {
            throw stop(row.position(), row.cause());
        }
        rejected++;
        skipped.accept(row);
    }

    /** The import stopped at a record, the batch under way rolled back. */
    private Import.Stopped stop(Source.@Nullable Position position, Import.Cause cause) {
        if (!connection.isLost() && connection.inTransaction()) {
            try {
                connection.statements().query(ROLLBACK);
            } catch (TurborelException lost) {
                // The import has failed already: a rollback that fails too loses the connection,
                // which ends the session's transaction.
            }
        }
        return new Import.Stopped(position, cause, committed, inBatch);
    }

    private void commit() throws TurborelException {
        if (connection.inTransaction()) {
            try {
                connection.statements().query(COMMIT);
            } catch (TurborelException failure) {
                throw new Import.Stopped(null, Import.Cause.sql(failure), committed, inBatch);
            }
        }
        committed += inBatch;
        inBatch = 0;
    }

    // An import by bulk copy, as Turborel's import/copied.rs.

    private Import.Imported copied(Source source, String table, List<String> columns) throws TurborelException {
        List<Source.Position> batch = new ArrayList<>();
        while (copiedBatch(source, table, columns, batch)) {
            batch.clear();
        }
        return new Import.Imported(committed, 0);
    }

    /** Copies a batch of records, its copy begun at its first and ended at its last: whether more may follow. */
    private boolean copiedBatch(Source source, String table, List<String> columns, List<Source.Position> batch)
            throws TurborelException {
        Source.Entry first = checked(source, batch);
        if (first == null) {
            return false;
        }
        Copying copy;
        try {
            copy = Copying.begin(connection, table, columns, true);
        } catch (TurborelException failure) {
            throw new Import.Stopped(null, Import.Cause.sql(failure), committed, 0);
        }
        Source.Entry next = first;
        while (next != null) {
            try {
                copy.send(Assign.assigned(next.values(), types()));
            } catch (TurborelException failure) {
                copy.close();
                throw new Import.Stopped(next.position(), Import.Cause.sql(failure), committed, batch.size());
            }
            batch.add(next.position());
            if (batch.size() == how.batch()) {
                ended(copy, batch);
                return true;
            }
            next = checked(source, batch, copy);
        }
        ended(copy, batch);
        return false;
    }

    private List<Parameter> types() {
        return insert.parameters();
    }

    private Source.@Nullable Entry checked(Source source, List<Source.Position> batch) throws TurborelException {
        return checked(source, batch, null);
    }

    /** The next record, its field count checked; a copy under way abandoned if it fails. */
    private Source.@Nullable Entry checked(Source source, List<Source.Position> batch, @Nullable Copying copy)
            throws TurborelException {
        try {
            Source.Entry record = source.record();
            if (record != null && record.values().size() != types().size()) {
                String message = "[DESIGN] " + record.values().size() + " fields, for " + types().size() + " columns";
                throw new Import.Stopped(record.position(), Import.Cause.input(message), committed, batch.size());
            }
            return record;
        } catch (Source.Failed failed) {
            throw new Import.Stopped(
                    failed.where(), Import.Cause.input(String.valueOf(failed.getMessage())), committed, batch.size());
        } catch (Import.Stopped stopped) {
            if (copy != null) {
                copy.close();
            }
            throw stopped;
        }
    }

    /** Ends the batch's copy: its rows committed, or why it failed, at the row it names. */
    private void ended(Copying copy, List<Source.Position> batch) throws TurborelException {
        try {
            committed += copy.finish();
        } catch (TurborelException failure) {
            int row = rowOf(failure);
            boolean named = row > 0 && row <= batch.size();
            Source.Position position = named ? batch.get(row - 1) : null;
            throw new Import.Stopped(position, Import.Cause.sql(failure), committed, named ? row - 1 : batch.size());
        }
    }

    /** The row of a copy a failure's detail names, from 1; 0 if it names none. */
    private static int rowOf(TurborelException failure) {
        String detail = failure.diagnostics().get(DETAIL);
        if (detail == null || !detail.startsWith(ROW) || !detail.endsWith(OF_THE_COPY)) {
            return 0;
        }
        try {
            return Integer.parseInt(detail.substring(ROW.length(), detail.length() - OF_THE_COPY.length()));
        } catch (NumberFormatException none) {
            return 0;
        }
    }
}
