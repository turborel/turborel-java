package com.turborel.jdbc.internal.session;

import static com.turborel.jdbc.internal.session.LiveStatementsTest.connected;
import static com.turborel.jdbc.internal.session.LiveStatementsTest.integer;
import static com.turborel.jdbc.internal.session.LiveStatementsTest.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.testing.LiveServer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** A copy's rows sent by column on a live server ([PROTOCOL CopyColumns 'O']). */
@EnabledIfEnvironmentVariable(named = LiveServer.VARIABLE, matches = ".+")
class LiveCopyColumnsTest {
    private static final List<String> COLUMNS = List.of("ID", "NAME", "NOTE");

    private static List<Value> text(@Nullable String... cells) {
        return Arrays.stream(cells)
                .map(c -> c == null ? Value.NULL : (Value) new Value.Text(c))
                .toList();
    }

    private static List<List<Value>> table(Connection c, String name) throws TurborelException {
        return rows(c.statements().query("SELECT ID, NAME, NOTE FROM " + name + " ORDER BY ID"));
    }

    /**
     * Verifies: [PROTOCOL CopyColumns 'O'] granted {@code columns}, rows of text go by column, each
     * cell cast to its column's type as its text would be in CopyData: a null cell null, an empty
     * one the empty string, a number's text its number, across chunks of many rows; the Complete
     * counts them ([CLIENT Copying and exporting]).
     */
    @Test
    void nullsAndEmptyStringsGoByColumn() throws TurborelException, IOException {
        try (Connection c = connected()) {
            assertTrue(c.granted().contains(Capability.COLUMNS), c.granted().toString());
            c.statements().query("CREATE TABLE BY_COLUMN (ID INTEGER, NAME VARCHAR(10), NOTE VARCHAR(10))");
            List<List<Value>> sent =
                    new ArrayList<>(List.of(text("1", "", null), text("2", null, ""), text(null, "c", "x")));
            for (int i = 4; i <= 20_000; i++) {
                sent.add(text(Integer.toString(i), "n" + i, null));
            }
            assertEquals(20_000, c.bulk().copyRows("BY_COLUMN", COLUMNS, sent));
            List<List<Value>> back = table(c, "BY_COLUMN");
            assertEquals(20_000, back.size());
            assertTrue(back.contains(List.of(integer(1), new Value.Text(""), Value.NULL)), "an empty string, a null");
            assertTrue(back.contains(List.of(integer(2), Value.NULL, new Value.Text(""))));
            assertTrue(back.contains(List.of(Value.NULL, new Value.Text("c"), new Value.Text("x"))), "a null number");
            assertTrue(back.contains(List.of(integer(20_000), new Value.Text("n20000"), Value.NULL)));
            c.statements().query("ROLLBACK");
        }
    }

    /**
     * Verifies: [PROTOCOL CopyColumns 'O'] a cell too long for its column fails rather than being
     * cut, the failure kept until CopyEnd, its detail naming its row, counted across CopyData and
     * CopyColumns mixed in one copy, the rows keeping their order; every row undone
     * ([PROTOCOL CopyEnd 'N']).
     */
    @Test
    void aCellTooLongFailsAtTheEndNamingItsRow() throws TurborelException, IOException {
        try (Connection c = connected()) {
            c.statements().query("CREATE TABLE SHORT_CELLS (ID INTEGER, NAME VARCHAR(3), NOTE VARCHAR(3))");
            List<List<Value>> mixed = List.of(
                    text("1", "a", "b"),
                    List.of(integer(2), new Value.Text("bb"), Value.NULL),
                    text("3", "ccc", null),
                    List.of(integer(4), new Value.Text("d"), new Value.Text("e")));
            assertEquals(4, c.bulk().copyRows("SHORT_CELLS", COLUMNS, mixed), "CopyData and CopyColumns mixed");
            assertEquals(
                    List.of(
                            List.of(integer(1), new Value.Text("a"), new Value.Text("b")),
                            List.of(integer(2), new Value.Text("bb"), Value.NULL),
                            List.of(integer(3), new Value.Text("ccc"), Value.NULL),
                            List.of(integer(4), new Value.Text("d"), new Value.Text("e"))),
                    table(c, "SHORT_CELLS"));
            List<List<Value>> tooLong = List.of(
                    text("5", "e", null),
                    List.of(integer(6), new Value.Text("f"), Value.NULL),
                    text("7", "gggg", null));
            TurborelException refused =
                    assertThrows(TurborelException.class, () -> c.bulk().copyRows("SHORT_CELLS", COLUMNS, tooLong));
            assertEquals("22001", refused.getSQLState(), refused.getMessage());
            assertEquals(
                    "row 3 of the copy",
                    refused.diagnostics().get("DETAIL"),
                    refused.diagnostics().toString());
            assertEquals(4, table(c, "SHORT_CELLS").size(), "the failed copy's rows undone");
            c.statements().query("ROLLBACK");
        }
    }

    /**
     * Verifies: [PROTOCOL CopyColumns 'O'] a copy of rows sent by column, abandoned, undoes them,
     * the Complete counting none, and the connection takes requests again ([PROTOCOL CopyEnd 'N']).
     */
    @Test
    void anAbandonedCopyUndoesItsColumns() throws TurborelException, IOException {
        try (Connection c = connected()) {
            c.statements().query("CREATE TABLE GIVEN_UP (ID INTEGER, NAME VARCHAR(10), NOTE VARCHAR(10))");
            Copying copying = c.bulk().copyIn("GIVEN_UP", COLUMNS);
            for (int i = 1; i <= 20_000; i++) {
                copying.send(text(Integer.toString(i), "n", null));
            }
            copying.abandon();
            assertEquals(0L, c.told().rowsAffected(), "the Complete counts none");
            assertEquals(List.of(), table(c, "GIVEN_UP"));
            c.statements().query("ROLLBACK");
        }
    }
}
