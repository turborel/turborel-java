package com.turborel.jdbc.internal.session;

import static com.turborel.jdbc.internal.session.LiveStatementsTest.connected;
import static com.turborel.jdbc.internal.session.LiveStatementsTest.integer;
import static com.turborel.jdbc.internal.session.LiveStatementsTest.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Lob;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.testing.LiveServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Copy, export and import on a live server ([CLIENT Copying and exporting], [CLIENT Importing]). */
@EnabledIfEnvironmentVariable(named = LiveServer.VARIABLE, matches = ".+")
class LiveBulkTest {
    private static List<List<Value>> numbered(int from, int to) {
        List<List<Value>> rows = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            rows.add(List.of(integer(i), new Value.Text("n" + i)));
        }
        return rows;
    }

    private static long count(Connection connection, String table) throws TurborelException {
        Value.Exact n = (Value.Exact) rows(connection.statements().query("SELECT COUNT(*) FROM " + table))
                .get(0)
                .get(0);
        return n.units().longValueExact();
    }

    private static Delimited csv(String text) {
        return new Delimited(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                Delimited.Dialect.csv().withHeader());
    }

    /**
     * Verifies: [CLIENT Copying and exporting] a copy into a table of the columns named, the rows an
     * iterator gives sent many to a message with no reply each, finished giving the rows inserted
     * ([PROTOCOL CopyEnd 'N']: tag 6 counts them; [PROTOCOL Conversations]: a copy); until it ends,
     * only its rows, its end and a ping are sent ([PROTOCOL CopyIn 'I']).
     */
    @Test
    void aCopyInsertsItsRows() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            connection.statements().query("CREATE TABLE COPIED_ROWS (ID INTEGER, NAME VARCHAR(10))");
            assertEquals(20_000, connection.bulk().copyRows("COPIED_ROWS", List.of("ID", "NAME"), numbered(1, 20_000)));
            assertEquals(20_000, count(connection, "COPIED_ROWS"));
            try (Copying copying = connection.bulk().copyIn("COPIED_ROWS", List.of())) {
                copying.send(List.of(integer(0), new Value.Text("zero")));
                connection.controls().ping();
                TurborelException misuse = assertThrows(
                        TurborelException.class, () -> connection.statements().query("VALUES 1"));
                assertEquals("TB910", misuse.getSQLState());
                assertEquals(1, copying.finish());
            }
            connection.statements().query("ROLLBACK");
        }
    }

    /**
     * Verifies: [PROTOCOL CopyEnd 'N'] a copy is all or nothing: a row refused undoes every row, the
     * failure kept until CopyEnd, its detail naming the row; abandoned, every row is undone, the
     * Complete counting none; a copy closed before it finishes is abandoned; a copy whose ends is set
     * is committed as it succeeds, another runs under a savepoint of its own, so a failing one keeps
     * what its transaction did before ([CLIENT Copying and exporting]; [PROTOCOL Conversations]: a copy
     * whose row breaks a constraint).
     */
    @Test
    void aCopyIsAllOrNothing() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            statements.query("CREATE TABLE KEYED_ROWS (ID INTEGER PRIMARY KEY, NAME VARCHAR(10))");
            statements.query("COMMIT");
            statements.query("INSERT INTO KEYED_ROWS VALUES (100, 'kept')");
            List<List<Value>> rows = numbered(1, 10);
            rows.set(6, List.of(integer(100), new Value.Text("twice")));
            TurborelException refused = assertThrows(
                    TurborelException.class, () -> connection.bulk().copyRows("KEYED_ROWS", List.of(), rows));
            assertEquals(
                    "row 7 of the copy",
                    refused.diagnostics().get("DETAIL"),
                    refused.diagnostics().toString());
            assertEquals(1, count(connection, "KEYED_ROWS"), "every row undone, what was before kept");
            Copying abandoned = connection.bulk().copyIn("KEYED_ROWS", List.of());
            abandoned.send(List.of(integer(1), new Value.Text("a")));
            abandoned.abandon();
            try (Copying dropped = connection.bulk().copyIn("KEYED_ROWS", List.of())) {
                dropped.send(List.of(integer(2), new Value.Text("b")));
            }
            assertEquals(1, count(connection, "KEYED_ROWS"));
            Copying sent = connection.bulk().copyIn("KEYED_ROWS", List.of());
            for (List<Value> row : numbered(1000, 20_000)) {
                sent.send(row);
            }
            sent.abandon();
            assertEquals(0L, connection.told().rowsAffected(), "the Complete counts none");
            assertEquals(1, count(connection, "KEYED_ROWS"), "rows that reached the server undone");
            statements.query("ROLLBACK");
            connection.setAutocommit(true);
            assertEquals(3, connection.bulk().copyRows("KEYED_ROWS", List.of(), numbered(1, 3)));
            assertFalse(connection.inTransaction(), "committed as it succeeded");
            connection.setAutocommit(false);
            assertEquals(3, count(connection, "KEYED_ROWS"));
            statements.query("ROLLBACK");
        }
    }

    /**
     * Verifies: [CLIENT Copying and exporting] an export as CSV is a header of the columns' names,
     * then a record for each row as it comes, a field quoted where it must be, a null empty; as the
     * protocol's own frames, read back with every value as it was.
     */
    @Test
    void exportsAsCsvAndNative() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            statements.query("CREATE TABLE EXPORTED (ID INTEGER, NAME VARCHAR(20), PRICE DECIMAL(6,2), SOLD DATE)");
            statements.query("INSERT INTO EXPORTED VALUES (1, 'plain', 1.50, DATE '2026-11-20'), "
                    + "(2, 'say \"hi\", ok', NULL, NULL), (3, '', 0.05, NULL)");
            ByteArrayOutputStream csv = new ByteArrayOutputStream();
            assertEquals(3, connection.bulk().export("SELECT * FROM EXPORTED ORDER BY ID", csv, Bulk.Format.CSV));
            assertEquals(
                    "ID,NAME,PRICE,SOLD\r\n1,plain,1.50,2026-11-20\r\n2,\"say \"\"hi\"\", ok\",,\r\n3,\"\",0.05,\r\n",
                    csv.toString(StandardCharsets.UTF_8));
            ByteArrayOutputStream frames = new ByteArrayOutputStream();
            connection.bulk().export("SELECT * FROM EXPORTED ORDER BY ID", frames, Bulk.Format.NATIVE);
            Outcome.Rows back = Bulk.readNative(new ByteArrayInputStream(frames.toByteArray()));
            Outcome.Rows direct = (Outcome.Rows) statements.query("SELECT * FROM EXPORTED ORDER BY ID");
            assertEquals(direct.columns(), back.columns());
            assertEquals(direct.rows(), back.rows());
            assertThrows(
                    TurborelException.class,
                    () -> connection.bulk().export("CREATE TABLE NOT_A_QUERY (N INTEGER)", csv, Bulk.Format.CSV));
            statements.query("ROLLBACK");
        }
    }

    /**
     * Verifies: [CLIENT Importing] a source's records are imported into a table over a connection, by
     * copy where the server grants it and no column is a large object; CSV with a header names the
     * columns, a text for null; rows committed a batch at a time; the session must have no
     * transaction active ([CLIENT Copying and exporting]: importing by copy).
     */
    @Test
    void anImportByCopy() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            statements.query("CREATE TABLE PEOPLE (ID INTEGER, \"Full Name\" VARCHAR(20))");
            statements.query("COMMIT");
            Import.Imported imported = connection
                    .bulk()
                    .importRows(csv("id,Full Name\n1,Ada\n2,\n3,Grace\n"), new Import("PEOPLE", 2, false), r -> {});
            assertEquals(new Import.Imported(3, 0), imported);
            assertFalse(connection.inTransaction(), "each batch committed");
            assertEquals(
                    List.of(List.of(integer(2), Value.NULL)),
                    rows(statements.query("SELECT ID, \"Full Name\" FROM PEOPLE WHERE ID = 2")));
            statements.query("VALUES 1");
            Import.Stopped active = assertThrows(
                    Import.Stopped.class,
                    () -> connection.bulk().importRows(csv("id\n1\n"), Import.into("PEOPLE"), r -> {}));
            assertEquals("22000", active.getSQLState());
            statements.query("ROLLBACK");
        }
    }

    /**
     * Verifies: [CLIENT Importing] by INSERT, a failing row is skipped and given to the program if
     * asked; else the import stops, saying where its record began, why, the input's or SQL's, and the
     * rows committed and rolled back; a string too long for its column is refused, not cut; a field
     * longer than 64 KiB for a large object column is sent by creating a large object ([CLIENT Large
     * objects]).
     */
    @Test
    void anImportByInsert() throws TurborelException, IOException {
        try (Connection connection = connected()) {
            Statements statements = connection.statements();
            statements.query("CREATE TABLE ITEMS_IN (ID INTEGER PRIMARY KEY, NAME VARCHAR(3), BODY CLOB(1M))");
            statements.query("COMMIT");
            String big = "x".repeat(70_000);
            List<Import.Rejected> skipped = new ArrayList<>();
            Import.Imported imported = connection
                    .bulk()
                    .importRows(
                            csv("ID,NAME,BODY\n1,abc," + big + "\n2,abcd,\n3,c\n4,d,\n"),
                            new Import("ITEMS_IN", 2, true),
                            skipped::add);
            assertEquals(new Import.Imported(2, 2), imported);
            assertEquals(2, skipped.size());
            TurborelException tooLong = skipped.get(0).cause().sql();
            assertNotNull(tooLong);
            assertEquals("22001", tooLong.getSQLState(), "refused, not cut");
            assertEquals(new Source.Position(2, 3L), skipped.get(0).position());
            assertEquals(
                    "[DESIGN] 2 fields, for 3 columns", skipped.get(1).cause().input());
            Value body = rows(statements.query("SELECT BODY FROM ITEMS_IN WHERE ID = 1"))
                    .get(0)
                    .get(0);
            assertEquals(70_000, ((Lob.Locator) body).characters());
            statements.query("ROLLBACK");
            Import.Stopped stopped = assertThrows(
                    Import.Stopped.class,
                    () -> connection
                            .bulk()
                            .importRows(
                                    csv("ID,NAME,BODY\n5,e,\n6,f,\n7,g,\n1,dup,\n"),
                                    new Import("ITEMS_IN", 2, false),
                                    r -> {}));
            assertEquals(new Source.Position(4, 5L), stopped.where());
            assertEquals(2, stopped.committed());
            assertEquals(1, stopped.rolledBack());
            assertNotNull(stopped.cause().sql());
            assertEquals(4, count(connection, "ITEMS_IN"));
            statements.query("ROLLBACK");
        }
    }
}
