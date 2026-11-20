package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Broken;
import com.turborel.jdbc.internal.codec.Column;
import com.turborel.jdbc.internal.codec.FrameHeader;
import com.turborel.jdbc.internal.codec.Reply;
import com.turborel.jdbc.internal.codec.ReplyCodec;
import com.turborel.jdbc.internal.codec.ReplyWriter;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.internal.codec.ValueText;
import com.turborel.jdbc.internal.codec.WhiteSpace;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * A connection's bulk transfers ([CLIENT Copying and exporting], [CLIENT Importing]): a copy into a
 * table, rows sent with no round trip but its end; an export of a query's rows as they come, as CSV
 * or as the protocol's own frames; and an import of a source's records. As Turborel's {@code
 * client/src/copy.rs}, {@code export.rs} and {@code import.rs}.
 */
public final class Bulk {
    private final Connection connection;

    Bulk(Connection connection) {
        this.connection = connection;
    }

    /** How an export writes rows. */
    public enum Format {
        /** CSV, RFC 4180, with a header of the columns' names. */
        CSV,
        /** The protocol's frames: a Description, Rows, a Complete. */
        NATIVE
    }

    /**
     * Begins a copy into {@code table}, as SQL names it, of {@code columns}, in order, all of the
     * table's if none; the copy its transaction's own under autocommit.
     *
     * @param table the table
     * @param columns its columns, or none for all
     * @return the copy
     * @throws TurborelException a connection failure: {@code copy} not granted, or the server gone;
     *     the table not found, a column not of it, comes as the copy's {@code finish}'s failure
     */
    public Copying copyIn(String table, List<String> columns) throws TurborelException {
        return Copying.begin(connection, table, columns, connection.autocommit());
    }

    /**
     * Copies rows into {@code table}'s {@code columns}, as {@link #copyIn}: the rows inserted.
     *
     * @param table the table
     * @param columns its columns, or none for all
     * @param rows the rows, a value for each column
     * @return the rows inserted
     * @throws TurborelException as {@link #copyIn}'s and the copy's {@code finish}'s
     */
    public long copyRows(String table, List<String> columns, Iterable<List<Value>> rows) throws TurborelException {
        try (Copying copying = copyIn(table, columns)) {
            for (List<Value> row : rows) {
                copying.send(row);
            }
            return copying.finish();
        }
    }

    /**
     * Imports a source's records into the table {@code how} names, as it says; each row skipped is
     * given to {@code rejected}. The session must have no transaction active.
     *
     * @param source where the records come from
     * @param how what to do
     * @param rejected what takes each row skipped
     * @return the rows committed and skipped
     * @throws Import.Stopped why it stopped, where, and how many rows were committed and rolled back
     * @throws TurborelException a failure before the import began
     */
    public Import.Imported importRows(Source source, Import how, Consumer<Import.Rejected> rejected)
            throws TurborelException {
        return Importer.run(connection, source, how, rejected);
    }

    /**
     * Runs the query {@code sql} and writes its rows to {@code out} as {@code format} says, as they
     * come: how many.
     *
     * @param sql the query
     * @param out where they are written
     * @param format how
     * @return the rows written
     * @throws TurborelException the query's; a statement that is no query; a write that failed
     *     (SQLSTATE 08006, the export's, which ends, not the connection's)
     */
    public long export(String sql, OutputStream out, Format format) throws TurborelException {
        Exporter writer = new Exporter(out, format);
        Streamed streamed = connection.statements().queryInto(sql, writer);
        if (!(streamed instanceof Streamed.Rows rows)) {
            throw Broken.connection("[CLIENT Copying and exporting] an export runs a query");
        }
        if (format == Format.NATIVE) {
            writer.write(ReplyWriter.complete(0, "00000"));
        }
        IOException failed = writer.failed;
        try {
            if (failed != null) {
                throw failed;
            }
            out.flush();
        } catch (IOException e) {
            throw Broken.connection("[CLIENT Copying and exporting] cannot write the export: " + e.getMessage());
        }
        return rows.count();
    }

    /**
     * Native rows read back, as an export of {@link Format#NATIVE} wrote them: the columns, and the
     * rows, every value as it was.
     *
     * @param input the frames
     * @return the columns and the rows
     * @throws TurborelException SQLSTATE 08006 for input that is not native rows, or cut short
     */
    public static Outcome.Rows readNative(InputStream input) throws TurborelException {
        Reply first = next(input);
        if (!(first instanceof Reply.Description description)) {
            throw notNative("no Description first");
        }
        List<List<Value>> rows = new ArrayList<>();
        while (true) {
            Reply reply = next(input);
            if (reply instanceof Reply.Rows batch) {
                rows.addAll(batch.rows());
            } else if (reply instanceof Reply.Complete) {
                return new Outcome.Rows(description.columns(), rows);
            } else {
                throw notNative("a frame other than Rows before the Complete");
            }
        }
    }

    private static Reply next(InputStream input) throws TurborelException {
        try {
            byte[] head = input.readNBytes(FrameHeader.BYTES);
            if (head.length < FrameHeader.BYTES) {
                throw notNative("the input ended");
            }
            FrameHeader header = FrameHeader.decode(head, FrameHeader.MAX_BODY);
            byte[] body = input.readNBytes(header.length());
            if (body.length < header.length()) {
                throw notNative("the input ended within a frame");
            }
            return ReplyCodec.decode(header.kind(), body);
        } catch (IOException e) {
            throw notNative(String.valueOf(e.getMessage()));
        }
    }

    private static TurborelException notNative(String why) {
        return Broken.connection("[CLIENT Copying and exporting] not native rows: " + why);
    }

    /** A query's rows written as they come; the first write that fails ends the export. */
    private static final class Exporter implements Sink {
        private final OutputStream out;
        private final Format format;
        private @Nullable IOException failed;

        Exporter(OutputStream out, Format format) {
            this.out = out;
            this.format = format;
        }

        void write(byte[] bytes) {
            if (failed == null) {
                try {
                    out.write(bytes);
                } catch (IOException e) {
                    failed = e;
                }
            }
        }

        @Override
        public void columns(List<Column> columns) {
            if (format == Format.CSV) {
                List<String> names = new ArrayList<>();
                for (Column column : columns) {
                    String name = column.name();
                    names.add(Csv.field(name == null ? "" : name));
                }
                write(Csv.record(names));
                return;
            }
            try {
                write(ReplyWriter.description(columns));
            } catch (TurborelException e) {
                failed = new IOException(e);
            }
        }

        @Override
        public void rows(List<List<Value>> rows) {
            if (format == Format.CSV) {
                for (List<Value> row : rows) {
                    List<String> fields = new ArrayList<>();
                    for (Value v : row) {
                        fields.add(v instanceof Value.Null ? "" : Csv.field(ValueText.of(v)));
                    }
                    write(Csv.record(fields));
                }
                return;
            }
            try {
                write(ReplyWriter.rows(rows));
            } catch (TurborelException e) {
                failed = new IOException(e);
            }
        }
    }

    /** CSV as RFC 4180 writes it, as Turborel's export does. */
    static final class Csv {
        private Csv() {}

        /** A field quoted where it holds a comma, a quote, a line break or white space at an end. */
        static String field(String field) {
            boolean quoted = field.isEmpty() || special(field) || whiteAtAnEnd(field);
            return quoted ? "\"" + field.replace("\"", "\"\"") + "\"" : field;
        }

        private static boolean special(String field) {
            return field.chars().anyMatch(c -> c == ',' || c == '"' || c == '\n' || c == '\r');
        }

        private static boolean whiteAtAnEnd(String field) {
            return WhiteSpace.is(field.codePointAt(0)) || WhiteSpace.is(field.codePointBefore(field.length()));
        }

        /** A record of fields, ended by CR LF. */
        static byte[] record(List<String> fields) {
            return (String.join(",", fields) + "\r\n").getBytes(StandardCharsets.UTF_8);
        }
    }
}
