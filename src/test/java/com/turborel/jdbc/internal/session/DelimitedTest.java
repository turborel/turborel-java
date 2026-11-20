package com.turborel.jdbc.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Value;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Delimited text as a source, its tests ported from {@code client/src/import/delimited/tests.rs}. */
class DelimitedTest {
    /** A field read as null, as these tests write it. */
    private static final String NULL = "<null>";

    private static InputStream in(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static InputStream in(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    /** The fields of each record, null as null. */
    private static List<List<String>> fields(String text, Delimited.Dialect dialect) throws Source.Failed {
        return fields(in(text), dialect);
    }

    private static List<List<String>> fields(InputStream input, Delimited.Dialect dialect) throws Source.Failed {
        Delimited source = new Delimited(input, dialect);
        List<List<String>> all = new ArrayList<>();
        for (Source.Entry record = source.record(); record != null; record = source.record()) {
            List<String> got = new ArrayList<>();
            for (Value v : record.values()) {
                got.add(v instanceof Value.Text t ? t.text() : NULL);
            }
            all.add(got);
        }
        return all;
    }

    private static List<String> row(String... fields) {
        return Arrays.asList(fields);
    }

    private static Source.Failed error(byte[] text, Delimited.Dialect dialect) {
        return assertThrows(Source.Failed.class, () -> fields(in(text), dialect));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Verifies: [CLIENT Importing] CSV's records end at CR LF or LF, the last's optional; fields
     * split at commas, spaces kept; a field in quotes holds commas, line breaks and quotes written
     * twice; an empty field is null, one of empty quotes the empty string; an empty line is a record
     * of one null field.
     */
    @Test
    void csvRecordsAndFields() throws Source.Failed {
        Delimited.Dialect csv = Delimited.Dialect.csv();
        assertEquals(List.of(row("a", "b"), row("c", "d")), fields("a,b\r\nc,d\n", csv));
        assertEquals(List.of(row("a", "b"), row("c", "d")), fields("a,b\nc,d", csv));
        assertEquals(List.of(row(" a ", " b ")), fields(" a , b ", csv));
        assertEquals(List.of(row("a,b", "say \"hi\"")), fields("\"a,b\",\"say \"\"hi\"\"\"", csv));
        assertEquals(List.of(row("one\r\ntwo\nthree", "x")), fields("\"one\r\ntwo\nthree\",x\n", csv));
        assertEquals(List.of(row(NULL, "", NULL)), fields(",\"\",", csv));
        assertEquals(List.of(row("a"), row(NULL), row("b")), fields("a\n\nb\n", csv));
        assertEquals(List.of(row("\"")), fields("\"\"\"\"", csv));
        assertEquals(List.of(), fields("", csv));
        String face = new String(Character.toChars(0x1F600));
        assertEquals(List.of(row("é", face)), fields("é," + face, csv));
    }

    /**
     * Verifies: [CLIENT Importing] the text for null is null only not in quotes, an empty field then
     * the empty string; a byte order mark beginning the input is passed over, and nowhere else.
     */
    @Test
    void csvNullTextAndByteOrderMark() throws Source.Failed {
        Delimited.Dialect nulls = Delimited.Dialect.csv().withNull("NULL");
        assertEquals(List.of(row(NULL, "NULL", "", "null")), fields("NULL,\"NULL\",,null", nulls));
        assertEquals(List.of(row("a", "b"), row("﻿c")), fields("﻿a,b\n﻿c", Delimited.Dialect.csv()));
    }

    /**
     * Verifies: [CLIENT Importing] a header names the fields, read once, whether or not asked for;
     * records are numbered from the first after it, each saying the line it began on, past the line
     * breaks of quoted fields; a header missing is refused.
     */
    @Test
    void csvHeaderAndPositions() throws Source.Failed {
        String text = "ID,\"FULL NAME\"\n1,\"Ada\nLovelace\"\n2,Grace\n";
        Delimited source = new Delimited(in(text), Delimited.Dialect.csv().withHeader());
        List<Source.Heading> headings = source.headings();
        assertNotNull(headings);
        assertEquals(
                List.of("ID", "FULL NAME"),
                headings.stream().map(Source.Heading::name).toList());
        assertTrue(headings.stream().allMatch(h -> h.type() == null));
        assertNull(source.headings(), "read once");
        List<Source.Position> positions = new ArrayList<>();
        for (Source.Entry record = source.record(); record != null; record = source.record()) {
            positions.add(record.position());
        }
        assertEquals(List.of(new Source.Position(1, 2L), new Source.Position(2, 4L)), positions);
        assertNull(source.record(), "none again after the end");
        assertEquals(
                List.of(row("1", "Ada\nLovelace"), row("2", "Grace")),
                fields(text, Delimited.Dialect.csv().withHeader()));
        Source.Failed missing = assertThrows(
                Source.Failed.class,
                () -> new Delimited(in(""), Delimited.Dialect.csv().withHeader()).headings());
        assertEquals(new Source.Position(0, 1L), missing.where());
        assertEquals("[DESIGN] there is no header", missing.getMessage());
        assertEquals("22000", missing.getSQLState(), "input that cannot be read");
        assertEquals("record 0, line 1", missing.where().toString());
        assertNull(new Delimited(in("a"), Delimited.Dialect.csv()).headings(), "no header unless told");
    }

    /**
     * Verifies: [CLIENT Importing] a quote in a field not quoted, anything but the delimiter after a
     * closing quote, a quote left open at the end, and a field not UTF-8 are refused, saying the
     * record, the line it began on, and the field; the source then gives nothing more.
     */
    @Test
    void csvErrorsSayWhere() throws Source.Failed {
        Delimited.Dialect csv = Delimited.Dialect.csv();
        Source.Failed quote = error(bytes("a\nb,c\"d\n"), csv);
        assertEquals(new Source.Position(2, 2L), quote.where());
        assertEquals("[RFC 4180 §2 rule 5] field 2: a quote in a field not enclosed in quotes", quote.getMessage());
        assertEquals(
                "[RFC 4180 §2 rule 5] field 1: a quoted field's closing quote is followed by more",
                error(bytes("\"a\"b,c"), csv).getMessage());
        Source.Failed open = error(bytes("a\nb\n\"c\nd\ne\n"), csv);
        assertEquals(new Source.Position(3, 3L), open.where(), "where the record began");
        assertEquals("[RFC 4180 §2 rule 5] a quoted field is not closed", open.getMessage());
        assertEquals(
                "[RFC 4180 §2 rule 5] a quoted field is not closed",
                error(bytes("\"c"), csv).getMessage());
        Source.Failed notUtf8 = error(new byte[] {'o', 'k', ',', (byte) 0xFF, (byte) 0xFE}, csv);
        assertEquals("record 1, line 1: [DESIGN] field 2 is not UTF-8", notUtf8.where() + ": " + notUtf8.getMessage());
        assertEquals(
                "[DESIGN] field 2 is not UTF-8",
                error(new byte[] {'o', 'k', '\t', (byte) 0xFF}, Delimited.Dialect.tsv())
                        .getMessage());
        Delimited source = new Delimited(in("\"a\"b\nc\n"), csv);
        assertThrows(Source.Failed.class, source::record);
        assertNull(source.record(), "nothing more after an error");
    }

    /**
     * Verifies: [CLIENT Importing] a record longer than the bound is refused once the bound is read,
     * so an endless input, or a quote never closed, is read in bounded memory; a record of the bound
     * is read, its line break counted; the default bound is the protocol's longest body; a reader's
     * failure is told.
     */
    @Test
    void recordsAreBounded() throws Source.Failed, IOException {
        try (InputStream endless = new Endless()) {
            assertEquals(
                    "[DESIGN] the record is longer than 1024 bytes",
                    assertThrows(
                                    Source.Failed.class,
                                    () -> new Delimited(
                                                    endless,
                                                    Delimited.Dialect.csv().withMaxRecord(1024))
                                            .record())
                            .getMessage());
        }
        byte[] quotes = new byte[10_000];
        Arrays.fill(quotes, (byte) '"');
        assertEquals(
                "[DESIGN] the record is longer than 4096 bytes",
                error(quotes, Delimited.Dialect.csv().withMaxRecord(4096)).getMessage());
        assertEquals(
                List.of(row("abcd")), fields("abcd\n", Delimited.Dialect.csv().withMaxRecord(5)));
        error(bytes("abcde\n"), Delimited.Dialect.csv().withMaxRecord(5));
        assertEquals(com.turborel.jdbc.internal.codec.FrameHeader.MAX_BODY, Delimited.DEFAULT_MAX_RECORD);
        try (InputStream failing = new Failing()) {
            assertEquals(
                    "[DESIGN] cannot read: the disk is gone",
                    assertThrows(Source.Failed.class, () -> new Delimited(failing, Delimited.Dialect.csv()).record())
                            .getMessage());
        }
    }

    /**
     * Verifies: [CLIENT Importing] tab-separated text splits at tabs, with no quoting, its records at
     * line breaks; an empty field is null.
     */
    @Test
    void tabSeparatedRecords() throws Source.Failed {
        Delimited.Dialect tsv = Delimited.Dialect.tsv();
        assertEquals(List.of(row("a", "b"), row("\"c", "\"d\"")), fields("a\tb\r\n\"c\t\"d\"\n", tsv));
        assertEquals(List.of(row("a", NULL, "b,c")), fields("a\t\tb,c", tsv));
        assertEquals(List.of(row("a", NULL)), fields("a\t\\N", tsv.withNull("\\N")));
    }

    /**
     * Verifies: [CLIENT Importing] a delimiter is one ASCII character, not a quote, CR or LF, refused
     * otherwise as the program's misuse (SQLCODE -910); fields then split at it.
     */
    @Test
    void delimiters() throws TurborelException {
        assertEquals(
                List.of(row("a", "b;c", "d,e")),
                fields("a;\"b;c\";d,e", Delimited.Dialect.csv().withDelimiter(';')));
        assertEquals(
                List.of(row("a", "b\tc")),
                fields("a|b\tc", Delimited.Dialect.tsv().withDelimiter('|')));
        for (char wrong : new char[] {'"', '\n', '\r', 'é'}) {
            TurborelException refused = assertThrows(
                    TurborelException.class,
                    () -> assertNotNull(Delimited.Dialect.csv().withDelimiter(wrong)));
            assertTrue(String.valueOf(refused.getMessage()).startsWith("[DESIGN] a delimiter is one ASCII character"));
            assertEquals(-910, refused.sqlcode());
        }
    }

    /** An input of endless letters. */
    private static final class Endless extends InputStream {
        @Override
        public int read() {
            return 'a';
        }

        @Override
        public int read(byte[] into, int offset, int length) {
            Arrays.fill(into, offset, offset + length, (byte) 'a');
            return length;
        }
    }

    /** An input whose disk is gone. */
    private static final class Failing extends InputStream {
        @Override
        public int read() throws IOException {
            throw new IOException("the disk is gone");
        }

        @Override
        public int read(byte[] into, int offset, int length) throws IOException {
            throw new IOException("the disk is gone");
        }
    }
}
