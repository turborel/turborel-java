package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Failures;
import com.turborel.jdbc.internal.codec.Value;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Delimited text as a {@link Source} ([CLIENT Importing]): CSV as RFC 4180 §2 defines it, and
 * tab-separated text, each a record a line, fields split at a delimiter, read a line at a time, a
 * record at most {@link Dialect#maxRecord} bytes, so a file of any size is read in bounded memory,
 * and a quote left open refused at the bound. A record ends at CR LF or LF, the last one's
 * optional; a header row may come first; a CSV field may be enclosed in double quotes, and must be
 * to hold the delimiter, a quote or a line break, a quote in it written twice. A quote in a field
 * not enclosed, or anything but the delimiter after a closing quote, is refused. Tab-separated text
 * has no quoting. A field written as the dialect's text for null, not enclosed in quotes, is null:
 * by default an empty one. Text is UTF-8; a byte order mark beginning the input is passed over. As
 * Turborel's {@code client/src/import/delimited.rs}.
 */
public final class Delimited implements Source {
    /** The longest record read by default, in bytes: the protocol's longest body, 16 MiB. */
    public static final int DEFAULT_MAX_RECORD = 16 * 1024 * 1024;

    private static final byte COMMA = ',';
    private static final byte TAB = '\t';
    private static final byte QUOTE = '"';
    private static final byte LF = '\n';
    private static final byte CR = '\r';
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final int ASCII_END = 0x80;

    private final InputStream input;
    private final Dialect dialect;
    private byte[] bytes = new byte[0];
    private long lines;
    private long records;
    private boolean headerPending;
    private boolean started;
    private boolean done;

    /**
     * How delimited text is written.
     *
     * @param delimiter the byte fields are split at
     * @param quoted whether fields may be enclosed in quotes, as CSV's are
     * @param header whether the first record names the fields
     * @param nullText what a field not enclosed in quotes is written as to be null
     * @param maxRecord the longest record, in bytes, its line break counted
     */
    public record Dialect(byte delimiter, boolean quoted, boolean header, String nullText, int maxRecord) {
        /**
         * CSV: fields split at commas, enclosed in quotes as they need; no header; an empty field
         * null.
         *
         * @return the dialect
         */
        public static Dialect csv() {
            return new Dialect(COMMA, true, false, "", DEFAULT_MAX_RECORD);
        }

        /**
         * Tab-separated text: fields split at tabs, never quoted; no header; an empty field null.
         *
         * @return the dialect
         */
        public static Dialect tsv() {
            return new Dialect(TAB, false, false, "", DEFAULT_MAX_RECORD);
        }

        /**
         * The dialect, its fields split at {@code delimiter}.
         *
         * @param delimiter one ASCII character other than a quote, CR or LF
         * @return the dialect
         * @throws TurborelException SQLCODE -910 if it is not one
         */
        public Dialect withDelimiter(char delimiter) throws TurborelException {
            if (delimiter >= ASCII_END || delimiter == QUOTE || delimiter == CR || delimiter == LF) {
                throw Failures.misuse(
                        "[DESIGN] a delimiter is one ASCII character, not a quote or a line break: " + delimiter);
            }
            return new Dialect((byte) delimiter, quoted, header, nullText, maxRecord);
        }

        /**
         * The dialect, its first record naming the fields.
         *
         * @return the dialect
         */
        public Dialect withHeader() {
            return new Dialect(delimiter, quoted, true, nullText, maxRecord);
        }

        /**
         * The dialect, a field written as {@code text}, not in quotes, null.
         *
         * @param text the text for null
         * @return the dialect
         */
        public Dialect withNull(String text) {
            return new Dialect(delimiter, quoted, header, text, maxRecord);
        }

        /**
         * The dialect, a record at most {@code bytes} long, its line break counted.
         *
         * @param bytes the longest record
         * @return the dialect
         */
        public Dialect withMaxRecord(int bytes) {
            return new Dialect(delimiter, quoted, header, nullText, bytes);
        }
    }

    /** Where a scan of a CSV record is: so whether a line break ends it, or is in a quoted field. */
    private enum Scan {
        FIELD_START,
        PLAIN,
        QUOTED,
        QUOTE_IN_QUOTED;

        Scan next(byte b, byte delimiter) {
            boolean quote = b == QUOTE;
            boolean split = b == delimiter;
            return switch (this) {
                case QUOTED -> quote ? QUOTE_IN_QUOTED : QUOTED;
                case FIELD_START, QUOTE_IN_QUOTED -> split ? FIELD_START : quote ? QUOTED : PLAIN;
                case PLAIN -> split ? FIELD_START : PLAIN;
            };
        }
    }

    /** A field as written: its text, and whether it was enclosed in quotes. */
    private record Field(String text, boolean quoted) {}

    /**
     * Delimited text read from {@code input}, written as {@code dialect} says.
     *
     * @param input the text, read as it is needed, buffered by the caller if it should be
     * @param dialect how it is written
     */
    public Delimited(InputStream input, Dialect dialect) {
        this.input = input;
        this.dialect = dialect;
        this.headerPending = dialect.header();
    }

    @Override
    public @Nullable List<Heading> headings() throws Failed {
        return header();
    }

    @Override
    public @Nullable Entry record() throws Failed {
        header();
        if (done) {
            return null;
        }
        long number = records + 1;
        Read read = fields(number);
        if (read == null) {
            return null;
        }
        records = number;
        List<Value> values = new ArrayList<>();
        for (Field field : read.fields()) {
            values.add(
                    !field.quoted() && field.text().equals(dialect.nullText())
                            ? Value.NULL
                            : new Value.Text(field.text()));
        }
        return new Entry(values, new Position(number, read.line()));
    }

    private record Read(List<Field> fields, long line) {}

    private @Nullable List<Heading> header() throws Failed {
        if (!headerPending) {
            return null;
        }
        headerPending = false;
        Read read = fields(0);
        if (read == null) {
            throw new Failed(new Position(0, 1L), "[DESIGN] there is no header");
        }
        List<Heading> headings = new ArrayList<>();
        for (Field field : read.fields()) {
            headings.add(new Heading(field.text(), null));
        }
        return headings;
    }

    private @Nullable Read fields(long record) throws Failed {
        long line = lines + 1;
        try {
            if (!raw()) {
                done = true;
                return null;
            }
            return new Read(split(bytes, dialect), line);
        } catch (Malformed why) {
            done = true;
            throw new Failed(new Position(record, line), String.valueOf(why.getMessage()));
        }
    }

    /** What is wrong with a record, before its position is known. */
    private static final class Malformed extends Exception {
        private static final long serialVersionUID = 1L;

        Malformed(String message) {
            super(message);
        }
    }

    /** Reads the next record's bytes, its line break taken off: false at the end of the input. */
    private boolean raw() throws Malformed {
        ByteArrayOutputStream record = new ByteArrayOutputStream();
        Scan at = Scan.FIELD_START;
        while (true) {
            int start = record.size();
            int n = readLine(record);
            if (record.size() > dialect.maxRecord()) {
                throw new Malformed("[DESIGN] the record is longer than " + dialect.maxRecord() + " bytes");
            }
            if (n == 0) {
                if (at == Scan.QUOTED) {
                    throw new Malformed(unclosed());
                }
                bytes = record.toByteArray();
                return bytes.length > 0;
            }
            byte[] read = record.toByteArray();
            if (dialect.quoted()) {
                for (int i = start; i < read.length; i++) {
                    at = at.next(read[i], dialect.delimiter());
                }
            }
            lines++;
            if (at != Scan.QUOTED) {
                bytes = ended(read);
                return true;
            }
        }
    }

    /** Reads to a line feed, at most one byte past the record's bound: the bytes read. */
    private int readLine(ByteArrayOutputStream record) throws Malformed {
        int n = 0;
        try {
            while (record.size() <= dialect.maxRecord()) {
                int b = input.read();
                if (b < 0) {
                    return n;
                }
                record.write(b);
                n++;
                if (b == LF) {
                    return n;
                }
            }
            return n;
        } catch (IOException e) {
            throw new Malformed("[DESIGN] cannot read: " + e.getMessage());
        }
    }

    /** The record's bytes without its line break, and the first's without a byte order mark. */
    private byte[] ended(byte[] read) {
        int end = read.length;
        if (end > 0 && read[end - 1] == LF) {
            end--;
            if (end > 0 && read[end - 1] == CR) {
                end--;
            }
        }
        int begin = 0;
        if (!started && end >= BOM.length && Arrays.equals(read, 0, BOM.length, BOM, 0, BOM.length)) {
            begin = BOM.length;
        }
        started = true;
        return Arrays.copyOfRange(read, begin, end);
    }

    private static String unclosed() {
        return "[RFC 4180 §2 rule 5] a quoted field is not closed";
    }

    /** The fields of a record, written as the dialect says. */
    private static List<Field> split(byte[] bytes, Dialect dialect) throws Malformed {
        List<Field> fields = new ArrayList<>();
        if (!dialect.quoted()) {
            int from = 0;
            for (int i = 0; i <= bytes.length; i++) {
                if (i == bytes.length || bytes[i] == dialect.delimiter()) {
                    fields.add(new Field(text(Arrays.copyOfRange(bytes, from, i), fields.size()), false));
                    from = i + 1;
                }
            }
            return fields;
        }
        int[] at = {0};
        while (true) {
            int n = fields.size();
            boolean quoted = at[0] < bytes.length && bytes[at[0]] == QUOTE;
            if (quoted) {
                at[0]++;
            }
            byte[] written = quoted ? enclosed(bytes, at, n) : plain(bytes, at, dialect.delimiter(), n);
            fields.add(new Field(text(written, n), quoted));
            if (at[0] >= bytes.length) {
                return fields;
            }
            if (bytes[at[0]++] != dialect.delimiter()) {
                throw new Malformed("[RFC 4180 §2 rule 5] field " + (n + 1)
                        + ": a quoted field's closing quote is followed by more");
            }
        }
    }

    private static byte[] plain(byte[] bytes, int[] at, byte delimiter, int n) throws Malformed {
        int start = at[0];
        while (at[0] < bytes.length && bytes[at[0]] != delimiter) {
            if (bytes[at[0]] == QUOTE) {
                throw new Malformed(
                        "[RFC 4180 §2 rule 5] field " + (n + 1) + ": a quote in a field not enclosed in quotes");
            }
            at[0]++;
        }
        return Arrays.copyOfRange(bytes, start, at[0]);
    }

    private static byte[] enclosed(byte[] bytes, int[] at, int n) throws Malformed {
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        while (true) {
            if (at[0] >= bytes.length) {
                throw new Malformed("field " + (n + 1) + ": " + unclosed());
            }
            byte b = bytes[at[0]++];
            if (b == QUOTE) {
                if (at[0] < bytes.length && bytes[at[0]] == QUOTE) {
                    at[0]++;
                } else {
                    return written.toByteArray();
                }
            }
            written.write(b);
        }
    }

    private static String text(byte[] bytes, int n) throws Malformed {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new Malformed("[DESIGN] field " + (n + 1) + " is not UTF-8");
        }
    }
}
