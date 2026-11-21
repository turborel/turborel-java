package com.turborel.jdbc.internal.session;

import static com.turborel.jdbc.internal.session.LiveStatementsTest.connected;
import static com.turborel.jdbc.internal.session.LiveStatementsTest.integer;
import static com.turborel.jdbc.internal.session.LiveStatementsTest.rows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Lob;
import com.turborel.jdbc.internal.codec.LobKind;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.testing.LiveServer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Large objects on a live server ([CLIENT Large objects]). */
@EnabledIfEnvironmentVariable(named = LiveServer.VARIABLE, matches = ".+")
class LiveLobsTest {
    /** A text of {@code n} characters, each of two octets in UTF-8. */
    private static String wide(int n) {
        return "é".repeat(n);
    }

    private static Lob.Locator written(Connection connection, LobKind kind, byte[] bytes)
            throws TurborelException, IOException {
        try (Lobs.Writer writer = connection.lobs().create(kind)) {
            int half = bytes.length / 2;
            writer.write(bytes, 0, half);
            writer.write(bytes, half, bytes.length - half);
            return writer.finish();
        }
    }

    /**
     * Verifies: [PROTOCOL Values] a large object of at most 64 KiB is read whole, a longer one by its
     * locator and its lengths, valid until its transaction ends, which it keeps open under
     * autocommit too ([CLIENT Large objects]); [PROTOCOL Lob 'L'] created and appended to, a
     * character split between appends, a value is sent as a parameter by its locator.
     */
    @Test
    void shortWholeAndLongByLocator() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            statements.query("CREATE TABLE NOTES (ID INTEGER, BODY CLOB(1M))");
            Statement insert = statements.prepare("INSERT INTO NOTES VALUES (?, ?)");
            statements.execute(insert, List.of(integer(1), Lob.Whole.ofText("short")));
            String text = wide(40_000);
            Lob.Locator made = written(connection, LobKind.CHARACTER, text.getBytes(StandardCharsets.UTF_8));
            assertEquals(80_000, made.octets());
            assertEquals(40_000, made.characters(), "a character split between writes counted once");
            statements.execute(insert, List.of(integer(2), made));
            statements.query("COMMIT");
            connection.setAutocommit(true);
            List<List<Value>> read = rows(statements.query("SELECT BODY FROM NOTES ORDER BY ID"));
            assertTrue(read.get(0).get(0) instanceof Lob.Whole, "64 KiB or less, whole");
            Lob.Locator locator = (Lob.Locator) read.get(1).get(0);
            assertEquals(80_000, locator.octets());
            assertEquals(40_000, locator.characters());
            assertTrue(connection.inTransaction(), "a locator keeps its transaction under autocommit");
            try (InputStream reader = connection.lobs().reader(locator)) {
                assertEquals(text, new String(reader.readAllBytes(), StandardCharsets.UTF_8));
            }
            connection.setAutocommit(false);
            statements.query("ROLLBACK");
        }
    }

    /**
     * Verifies: [CLIENT Large objects] a range is read by octets, or of a character large object by
     * characters, whole ones, at most 256 KiB a chunk; of one held whole, from its value ([PROTOCOL
     * Lob 'L'] 0 read).
     */
    @Test
    void rangesByOctetsAndCharacters() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            statements.query("CREATE TABLE PAGES (BODY CLOB(2M))");
            String text = "a" + wide(300_000);
            statements.execute(
                    statements.prepare("INSERT INTO PAGES VALUES (?)"),
                    List.of(written(connection, LobKind.CHARACTER, text.getBytes(StandardCharsets.UTF_8))));
            Lob.Locator locator = (Lob.Locator)
                    rows(statements.query("SELECT BODY FROM PAGES")).get(0).get(0);
            Lobs lobs = connection.lobs();
            assertEquals("aé", new String(lobs.read(locator, 0, 3, false), StandardCharsets.UTF_8));
            assertEquals("éé", new String(lobs.read(locator, 1, 2, true), StandardCharsets.UTF_8));
            assertTrue(lobs.read(locator, 0, 1 << 20, false).length <= Lobs.CHUNK_OCTETS, "at most a chunk");
            Lob.Whole held = Lob.Whole.ofText("aéb");
            assertEquals("éb", new String(lobs.read(held, 1, 2, true), StandardCharsets.UTF_8));
            assertArrayEquals(new byte[] {'a'}, lobs.read(held, 0, 1, false));
            statements.query("ROLLBACK");
        }
    }

    /**
     * Verifies: [CLIENT Large objects] a locator held is valid past the commit, until it is freed; one
     * not held is not (SQLSTATE 0F001); a value sent whole has no locator to free or hold (22000)
     * ([PROTOCOL Lob 'L'] 3 free, 4 hold).
     */
    @Test
    void heldAndFreed() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            statements.query("CREATE TABLE BLOBS (DATA BLOB(1M))");
            byte[] data = new byte[100_000];
            Arrays.fill(data, (byte) 7);
            statements.execute(
                    statements.prepare("INSERT INTO BLOBS VALUES (?)"),
                    List.of(written(connection, LobKind.BINARY, data)));
            statements.query("COMMIT");
            Lob.Locator held = (Lob.Locator)
                    rows(statements.query("SELECT DATA FROM BLOBS")).get(0).get(0);
            connection.lobs().hold(held);
            Lob.Locator loose = (Lob.Locator)
                    rows(statements.query("SELECT DATA FROM BLOBS")).get(0).get(0);
            statements.query("COMMIT");
            assertEquals(10, connection.lobs().read(held, 0, 10, false).length, "held past the commit");
            assertEquals(
                    "0F001",
                    assertThrows(
                                    TurborelException.class,
                                    () -> connection.lobs().read(loose, 0, 1, false))
                            .getSQLState());
            connection.lobs().free(held);
            assertEquals(
                    "0F001",
                    assertThrows(
                                    TurborelException.class,
                                    () -> connection.lobs().read(held, 0, 1, false))
                            .getSQLState());
            assertEquals(
                    "22000",
                    assertThrows(
                                    TurborelException.class,
                                    () -> connection.lobs().free(Lob.Whole.ofText("x")))
                            .getSQLState());
            statements.query("ROLLBACK");
        }
    }

    /**
     * Verifies: [PROTOCOL Lob 'L'] a character large object's octets are checked as UTF-8 as each
     * Append comes: one that is not is refused (SQLSTATE 22021), and every Append after; a
     * character split between two Appends, the first sent by a flush, is taken whole; one that ends within a character is refused,
     * 22021, where it is used ([CLIENT Large objects]).
     */
    @Test
    void aClobsOctetsAreUtf8() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            Lobs.Writer whole = connection.lobs().create(LobKind.CHARACTER);
            whole.write(new byte[] {'a', (byte) 0xC3});
            whole.flush();
            whole.write(new byte[] {(byte) 0xA9});
            Lob.Locator written = whole.finish();
            Statement echo = statements.prepare("VALUES CAST(? AS CLOB(1K))");
            Outcome.Rows rows = (Outcome.Rows) statements.execute(echo, List.of(written));
            assertArrayEquals(
                    "a\u00e9".getBytes(StandardCharsets.UTF_8),
                    ((Lob.Whole) rows.rows().get(0).get(0)).octets().toByteArray(),
                    "a, and a character split between two Appends");
            Lobs.Writer writer = connection.lobs().create(LobKind.CHARACTER);
            writer.write(new byte[] {'a', (byte) 0xFF});
            IOException refused = assertThrows(IOException.class, writer::flush);
            assertEquals(
                    "22021",
                    org.junit.jupiter.api.Assertions.assertInstanceOf(TurborelException.class, refused.getCause())
                            .getSQLState(),
                    "refused by its Append");
            writer.write(new byte[] {'b'});
            assertEquals(
                    "22021",
                    assertThrows(TurborelException.class, writer::finish).getSQLState(),
                    "every Append after");
            Lobs.Writer cut = connection.lobs().create(LobKind.CHARACTER);
            cut.write(new byte[] {'a', (byte) 0xC3});
            Lob.Locator split = cut.finish();
            assertEquals(
                    "22021",
                    assertThrows(TurborelException.class, () -> statements.execute(echo, List.of(split)))
                            .getSQLState(),
                    "a character left split, where it is used");
            assertFalse(connection.isLost());
            statements.query("ROLLBACK");
        }
    }

    /**
     * Verifies: [PROTOCOL Lob 'L'] a multi-row INSERT of large-object {@code ?} marks keeps every
     * value whole, each {@code ?} of its object column's type: by a locator, a long one, and held
     * whole in its row, a short one.
     */
    @Test
    void aMultiRowInsertKeepsItsLargeObjects() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            statements.query("CREATE TABLE LOB_ROWS (ID INTEGER, C CLOB(1M), B BLOB(1M))");
            String text = "x".repeat(200_000) + "y";
            Lobs.Writer writer = connection.lobs().create(LobKind.CHARACTER);
            writer.write(text.getBytes(StandardCharsets.UTF_8));
            Lob.Locator big = writer.finish();
            byte[] octets = new byte[150_000];
            octets[octets.length - 1] = 7;
            Statement insert = statements.prepare("INSERT INTO LOB_ROWS VALUES (?, ?, ?), (?, ?, ?)");
            statements.execute(
                    insert,
                    List.of(
                            integer(1),
                            big,
                            new Lob.Whole(LobKind.BINARY, com.turborel.jdbc.internal.codec.Octets.of(octets)),
                            integer(2),
                            Lob.Whole.ofText("short"),
                            new Lob.Whole(LobKind.BINARY, com.turborel.jdbc.internal.codec.Octets.of(new byte[] {1}))));
            List<List<Value>> read = rows(
                    statements.query(
                            "SELECT ID, CHAR_LENGTH(C), OCTET_LENGTH(B), SUBSTRING(C FROM 200001), C FROM LOB_ROWS ORDER BY ID"));
            assertEquals(
                    List.of(integer(1), integer(200_001), integer(150_000), Lob.Whole.ofText("y")),
                    read.get(0).subList(0, 4));
            assertEquals(
                    List.of(integer(2), integer(5), integer(1)), read.get(1).subList(0, 3));
            assertArrayEquals(
                    "short".getBytes(StandardCharsets.UTF_8),
                    ((Lob.Whole) read.get(1).get(4)).octets().toByteArray());
            statements.query("ROLLBACK");
        }
    }

    /**
     * Verifies: [CLIENT Running statements] a {@code ?} value longer than its column, cut to fit,
     * completes its INSERT with the warning 01004, the value kept as cut; a query comparing a
     * column with a {@code ?} cut to its type completes with 01004 too, with ORDER BY or without.
     */
    @Test
    void aValueCutWarns() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            statements.query("CREATE TABLE CUT_ROWS (N VARCHAR(3))");
            Statement insert = statements.prepare("INSERT INTO CUT_ROWS VALUES (?)");
            Outcome.Done done = (Outcome.Done) statements.execute(insert, List.of(new Value.Text("abcdef")));
            assertEquals("01004", done.completed().sqlstate());
            assertEquals("01004", connection.warning());
            assertEquals(List.of(List.of(new Value.Text("abc"))), rows(statements.query("SELECT N FROM CUT_ROWS")));
            for (String order : List.of("", " ORDER BY N")) {
                Statement compared = statements.prepare("SELECT N FROM CUT_ROWS WHERE N = ?" + order);
                Outcome outcome = statements.execute(compared, List.of(new Value.Text("abcdef")));
                assertEquals(List.of(List.of(new Value.Text("abc"))), rows(outcome), order);
                assertEquals(
                        "01004",
                        connection.warning(),
                        "a query's ? cut, with" + (order.isEmpty() ? "out" : "") + " ORDER BY");
            }
            statements.query("ROLLBACK");
        }
    }
}
