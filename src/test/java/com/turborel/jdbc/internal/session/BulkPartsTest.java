package com.turborel.jdbc.internal.session;

import static com.turborel.jdbc.internal.session.Script.after;
import static com.turborel.jdbc.internal.session.Script.complete;
import static com.turborel.jdbc.internal.session.Script.frame;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.CharUnits;
import com.turborel.jdbc.internal.codec.DataType;
import com.turborel.jdbc.internal.codec.Lob;
import com.turborel.jdbc.internal.codec.LobKind;
import com.turborel.jdbc.internal.codec.Parameter;
import com.turborel.jdbc.internal.codec.ReplyWriter;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.internal.codec.Writer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The parts of large objects, copy, export and import that need no server. */
class BulkPartsTest {
    private static Parameter of(DataType type) {
        return new Parameter(false, type);
    }

    /**
     * Verifies: [CLIENT Importing] a table's name is read as Turborel's lexer reads one: regular
     * identifiers in upper case, delimited ones as written, at most three parts; a key word, an
     * empty or unclosed delimited identifier, one of more than 128 letters, or anything else is no
     * table's name; a heading names the column it is as an identifier, a key word included, else as
     * written.
     */
    @Test
    void namesAsTheLexerReadsThem() {
        assertEquals("\"PEOPLE\"", SqlNames.tableName("people"));
        assertEquals("\"ANN\".\"Mixed Case\"", SqlNames.tableName(" ann . \"Mixed Case\" "));
        assertEquals("\"C\".\"S\".\"T\"", SqlNames.tableName("c.s.t"));
        assertEquals("\"SS\"", SqlNames.tableName("ß"));
        assertEquals("\"A\"\"B\"", SqlNames.tableName("\"A\"\"B\""));
        for (String wrong : List.of(
                "",
                "a.b.c.d",
                "table",
                "a.",
                ".a",
                "a b",
                "\"\"",
                "\"open",
                "\"a\nb\"",
                "1a",
                "a-b",
                "x".repeat(129),
                "a\tb")) {
            assertNull(SqlNames.tableName(wrong), wrong);
        }
        assertEquals("\"" + "X".repeat(128) + "\"", SqlNames.tableName("x".repeat(128)));
        assertEquals("NAME", SqlNames.columnName("name"));
        assertEquals("VALUE", SqlNames.columnName("value"), "a key word, as it would be but for being reserved");
        assertEquals("Full Name", SqlNames.columnName("\"Full Name\""));
        assertEquals("Full Name", SqlNames.columnName("Full Name"));
        assertEquals("a.b", SqlNames.columnName("a.b"));
        assertEquals("1x", SqlNames.columnName("1x"));
        assertEquals(".", SqlNames.columnName("."));
        assertEquals("+", SqlNames.columnName("+"));
        assertEquals("\"open", SqlNames.columnName("\"open"));
        assertEquals("A_B1", SqlNames.columnName("a_b1\r\n"));
        assertNull(SqlNames.tableName("\"a\rb\""));
        assertNull(SqlNames.tableName("\"" + "x".repeat(129) + "\""));
    }

    /**
     * Verifies: [CLIENT Importing] a string is assigned to its character column as INSERT assigns
     * it: CHARACTER padded with spaces, CHARACTER VARYING as it is, a CHARACTER LARGE OBJECT held
     * whole; spaces beyond the length dropped; anything else beyond refused (SQLSTATE 22001), in
     * characters or in octets as the type counts; other values left for the server.
     */
    @Test
    void stringsAssignedAsInsertDoes() throws TurborelException {
        DataType fixed = new DataType.CharacterType(false, 3, CharUnits.CHARACTERS);
        DataType varying = new DataType.CharacterType(true, 3, CharUnits.OCTETS);
        DataType clob = new DataType.LobType(LobKind.CHARACTER, 3, CharUnits.CHARACTERS);
        List<Parameter> types = List.of(of(fixed), of(varying), of(clob), of(new DataType.ExactType(5, 0)));
        Value one = new Value.Exact(java.math.BigInteger.ONE, 0);
        assertEquals(
                List.of(new Value.Text("a  "), new Value.Text("é"), Lob.Whole.ofText("ab"), one),
                Assign.assigned(List.of(new Value.Text("a"), new Value.Text("é"), new Value.Text("ab"), one), types));
        assertEquals(
                List.of(new Value.Text("abc"), new Value.Text("ab "), Lob.Whole.ofText("abc"), Value.NULL),
                Assign.assigned(
                        List.of(new Value.Text("abc  "), new Value.Text("ab "), new Value.Text("abc "), Value.NULL),
                        types));
        TurborelException fixedLong = assertThrows(
                TurborelException.class, () -> Assign.assigned(List.of(new Value.Text("abcd")), List.of(of(fixed))));
        assertEquals("22001", fixedLong.getSQLState());
        assertEquals(
                "[ISO/IEC 9075:1992 §13.8 GR4b iii, §9.2 GR3b ii] a value of 4 characters, not spaces beyond 3, "
                        + "does not fit CHARACTER(3)",
                fixedLong.getMessage());
        TurborelException octets = assertThrows(
                TurborelException.class, () -> Assign.assigned(List.of(new Value.Text("éé")), List.of(of(varying))));
        assertEquals(
                "[ISO/IEC 9075:1992 §13.8 GR4b iii, §9.2 GR3e ii] a value of 4 octets, not spaces beyond 3, "
                        + "does not fit CHARACTER VARYING(3 OCTETS)",
                octets.getMessage());
        TurborelException large = assertThrows(
                TurborelException.class, () -> Assign.assigned(List.of(new Value.Text("abcd")), List.of(of(clob))));
        assertEquals("22001", large.getSQLState());
        assertTrue(String.valueOf(large.getMessage())
                .endsWith("longer than CHARACTER LARGE OBJECT(3), not padding beyond it, " + "does not fit it"));
    }

    /**
     * Verifies: [CLIENT Large objects] a CLOB's chunk is cut where a character ends, as Turborel's
     * {@code char_cut} cuts it, a character the end of the bytes splits left out; a range of a value
     * held whole is read from it, by octets or by characters.
     */
    @Test
    void chunksAndRangesOfLargeObjects() throws TurborelException {
        byte[] text = "aé€".getBytes(StandardCharsets.UTF_8);
        assertEquals(1, Lobs.charCut(text, text.length, 2), "é is not cut");
        assertEquals(3, Lobs.charCut(text, text.length, 4));
        assertEquals(6, Lobs.charCut(text, text.length, 9));
        assertEquals(3, Lobs.charCut(text, 5, 5), "€ split by the end, left out");
        assertEquals(1, Lobs.charCut(text, 2, 2));
        byte[] continuations = {(byte) 0x80, (byte) 0x80};
        assertEquals(0, Lobs.charCut(continuations, 2, 1));
        assertEquals(2, Lobs.charCut(continuations, 2, 2), "no lead octet, all kept");
        Lob.Whole whole = Lob.Whole.ofText("aé€b");
        assertArrayEquals("é€".getBytes(StandardCharsets.UTF_8), Lobs.heldRange(whole, 1, 2, true));
        assertArrayEquals(new byte[0], Lobs.heldRange(whole, 99, 2, false));
        Lob.Whole binary =
                new Lob.Whole(LobKind.BINARY, com.turborel.jdbc.internal.codec.Octets.of(new byte[] {1, 2, 3}));
        assertArrayEquals(new byte[] {2, 3}, Lobs.heldRange(binary, 1, 9, true), "a binary one by octets always");
    }

    /**
     * Verifies: [PROTOCOL Lob 'L'] a reply other than a Lob reply loses the connection, as one of the
     * wrong kind does; a large object read as a stream reads its octets a chunk at a time, and fails
     * if the server sends less than its length.
     */
    @Test
    void largeObjectRepliesOverAScript() throws TurborelException, IOException {
        byte[] data = frame('L', new Writer().u8(0).bytes(new byte[] {7, 8}));
        byte[] locator = frame('L', new Writer().u8(1).u32(9));
        Lob.Locator lob = new Lob.Locator(LobKind.BINARY, 9, 4, 4);
        Connection read = after(List.of(), data, frame('L', new Writer().u8(0).bytes(new byte[0])));
        try (InputStream in = read.lobs().reader(lob)) {
            assertEquals(7, in.read());
            assertEquals(8, in.read());
            assertEquals(0, in.read(new byte[1], 0, 0));
            assertThrows(IOException.class, () -> assertEquals(-1, in.read(new byte[4])));
        }
        Connection wrong = after(List.of(), locator);
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, () -> wrong.lobs().read(lob, 0, 1, false))
                        .getSQLState());
        Connection create = after(List.of(), data);
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, () -> create.lobs().create(LobKind.BINARY))
                        .getSQLState());
        Connection none = after(List.of(), complete());
        assertThrows(TurborelException.class, () -> none.lobs().read(lob, 0, 1, false));
        Connection appended = after(List.of(), locator, complete(), complete());
        Lobs.Writer writer = appended.lobs().create(LobKind.BINARY);
        writer.write(5);
        writer.flush();
        writer.flush();
        assertEquals(new Lob.Locator(LobKind.BINARY, 9, 1, 0), writer.finish());
        Connection whole = after(List.of());
        try (InputStream in = whole.lobs().reader(Lob.Whole.ofText("hi"))) {
            assertEquals("hi", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Verifies: [CLIENT Copying and exporting] a CSV field is quoted where it holds a comma, a quote,
     * a line break or white space at an end, its quotes doubled; an empty string quoted; a record
     * ended by CR LF.
     */
    @Test
    void csvFields() {
        assertEquals("plain", Bulk.Csv.field("plain"));
        assertEquals("\"a,b\"", Bulk.Csv.field("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", Bulk.Csv.field("say \"hi\""));
        assertEquals("\" pad\"", Bulk.Csv.field(" pad"));
        assertEquals("\"pad　\"", Bulk.Csv.field("pad　"));
        assertEquals("\"two\nlines\"", Bulk.Csv.field("two\nlines"));
        assertEquals("\"cr\r\"", Bulk.Csv.field("cr\r"));
        assertEquals("\"\"", Bulk.Csv.field(""));
        assertArrayEquals("1,\r\n".getBytes(StandardCharsets.UTF_8), Bulk.Csv.record(List.of("1", "")));
    }

    /**
     * Verifies: [CLIENT Copying and exporting] native rows read back are a Description, Rows and a
     * Complete; anything else, or input cut short, is refused (SQLSTATE 08006).
     */
    @Test
    void nativeRowsReadBack() throws TurborelException, IOException {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frames.write(ReplyWriter.description(List.of()));
        frames.write(ReplyWriter.rows(List.of(List.of(), List.of())));
        frames.write(ReplyWriter.complete(0, "00000"));
        assertEquals(
                2,
                Bulk.readNative(new ByteArrayInputStream(frames.toByteArray()))
                        .rows()
                        .size());
        byte[] all = frames.toByteArray();
        for (byte[] wrong : List.of(
                new byte[0],
                ReplyWriter.complete(0, "00000"),
                java.util.Arrays.copyOf(all, 7),
                java.util.Arrays.copyOf(all, all.length - 1),
                Script.cat(ReplyWriter.description(List.of()), ReplyWriter.description(List.of())))) {
            assertEquals(
                    "08006",
                    assertThrows(TurborelException.class, () -> Bulk.readNative(new ByteArrayInputStream(wrong)))
                            .getSQLState());
        }
    }
}
