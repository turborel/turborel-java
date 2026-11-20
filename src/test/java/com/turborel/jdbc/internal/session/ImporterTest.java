package com.turborel.jdbc.internal.session;

import static com.turborel.jdbc.internal.session.Script.after;
import static com.turborel.jdbc.internal.session.Script.error;
import static com.turborel.jdbc.internal.session.Script.frame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.internal.codec.Writer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** An import's ways of stopping, over a stream the test scripts ([CLIENT Importing]). */
class ImporterTest {
    /** A source of records given in advance, its headings, and a failure to give at a point. */
    private static final class Given implements Source {
        private final @Nullable List<Heading> headings;
        private final Deque<Object> records = new ArrayDeque<>();
        private long number;

        Given(@Nullable List<Heading> headings, Object... records) {
            this.headings = headings;
            this.records.addAll(List.of(records));
        }

        @Override
        public @Nullable List<Heading> headings() throws Failed {
            if (headings != null && headings.isEmpty()) {
                throw new Failed(new Position(0, 1L), "no header here");
            }
            return headings == null ? null : List.copyOf(headings);
        }

        @Override
        public @Nullable Entry record() throws Failed {
            Object next = records.poll();
            if (next == null) {
                return null;
            }
            number++;
            if (next instanceof String message) {
                throw new Failed(new Position(number, null), message);
            }
            @SuppressWarnings("unchecked")
            List<Value> values = (List<Value>) next;
            return new Entry(values, new Position(number, null));
        }
    }

    private static final List<Source.Heading> ONE = List.of(new Source.Heading("n", null));

    private static List<Value> row(String... texts) {
        List<Value> values = new ArrayList<>();
        for (String text : texts) {
            values.add(new Value.Text(text));
        }
        return values;
    }

    /** A Prepared of statement 0, of one INTEGER parameter, or one BLOB, and no columns. */
    private static byte[] prepared(boolean blob) throws TurborelException {
        Writer w = new Writer().u32(0).u32(1).flag(false);
        if (blob) {
            w.u8(19).u64(1 << 20).u8(1);
        } else {
            w.u8(2).u32(10).u32(0);
        }
        return frame('P', w.u32(0));
    }

    /** A Prepared of a query of one column named N. */
    private static byte[] described() throws TurborelException {
        return frame(
                'P',
                new Writer()
                        .u32(0)
                        .u32(0)
                        .u32(1)
                        .flag(true)
                        .string("N")
                        .u8(2)
                        .u32(10)
                        .u32(0)
                        .flag(false));
    }

    private static byte[] done(boolean active) throws TurborelException {
        return frame('K', new Writer().bits32(0).string("00000").flag(active));
    }

    private static byte[] failed(int sqlcode, String sqlstate, boolean active) throws TurborelException {
        return frame(
                'E',
                new Writer()
                        .bits32(sqlcode)
                        .string(sqlstate)
                        .string("no")
                        .flag(false)
                        .flag(false)
                        .flag(active));
    }

    private static Import.Stopped stopped(Connection connection, Source source, Import how) {
        return assertThrows(Import.Stopped.class, () -> connection.bulk().importRows(source, how, r -> {}));
    }

    /**
     * Verifies: [CLIENT Importing] an import that cannot begin stops before any row: a table's name
     * that is no name, a source whose headings cannot be read, an INSERT the server refuses, and a
     * table described for its columns that is not there; with no headings, the table's columns are
     * taken, as a query of them describes them.
     */
    @Test
    void beforeAnyRow() throws TurborelException {
        Import into = Import.into("T");
        assertEquals(
                "22000",
                stopped(after(List.of()), new Given(ONE), Import.into("a b")).getSQLState());
        Import.Stopped headings = stopped(after(List.of()), new Given(List.of()), into);
        assertEquals(new Source.Position(0, 1L), headings.where());
        assertEquals("no header here", headings.cause().input());
        assertEquals(
                "42000",
                stopped(after(List.of(), error(-103, "42000")), new Given(ONE), into)
                        .getSQLState());
        assertEquals(
                "42000",
                stopped(after(List.of(), error(-103, "42000")), new Given(null), into)
                        .getSQLState());
        Connection described = after(List.of(), described(), done(false), prepared(false), done(false), done(false));
        assertEquals(new Import.Imported(1, 0), described.bulk().importRows(new Given(null, row("1")), into, r -> {}));
    }

    /**
     * Verifies: [CLIENT Importing] a record that cannot be read stops the import where it began,
     * the batch under way rolled back, the rows before committed counted; so does a record of too
     * many fields unless rows are skipped, then given to the program.
     */
    @Test
    void aRecordThatCannotBeRead() throws TurborelException {
        Import two = new Import("T", 2, false);
        Connection broken = after(
                List.of(), prepared(false), done(true), done(true), done(false), done(true), done(false), done(false));
        Import.Stopped stopped = stopped(broken, new Given(ONE, row("1"), row("2"), row("3"), "torn"), two);
        assertEquals(new Source.Position(4, null), stopped.where());
        assertEquals(2, stopped.committed());
        assertEquals(1, stopped.rolledBack());
        assertEquals("torn", stopped.cause().input());
        Connection wide = after(List.of(), prepared(false), done(false));
        Import.Stopped fields = stopped(wide, new Given(ONE, row("1", "2")), two);
        assertEquals("[DESIGN] 2 fields, for 1 columns", fields.cause().input());
        Connection skipping = after(List.of(), prepared(false), done(true), done(false), done(false));
        List<Import.Rejected> rejected = new ArrayList<>();
        assertEquals(
                new Import.Imported(1, 1),
                skipping.bulk()
                        .importRows(new Given(ONE, row("1", "2"), row("3")), new Import("T", 0, true), rejected::add));
        assertEquals(
                "record 1: [DESIGN] 2 fields, for 1 columns; skipped",
                rejected.get(0).toString());
    }

    /**
     * Verifies: [CLIENT Importing] a row the server refuses is skipped if asked only if it alone was
     * undone; a cancelled statement, a failure that rolled the batch back, or one with no skipping
     * stops the import; a commit that fails stops it, as does a deallocation that fails at the end.
     */
    @Test
    void aRowTheServerRefuses() throws TurborelException {
        Import skip = new Import("T", 0, true);
        Connection alone =
                after(List.of(), prepared(false), failed(-301, "22003", false), done(true), done(false), done(false));
        assertEquals(
                new Import.Imported(1, 1), alone.bulk().importRows(new Given(ONE, row("x"), row("1")), skip, r -> {}));
        Connection undone = after(List.of(), prepared(false), done(true), failed(-301, "22003", false), done(false));
        assertEquals(
                "22003",
                stopped(undone, new Given(ONE, row("1"), row("x")), skip).getSQLState());
        Connection cancelled = after(List.of(), prepared(false), failed(-920, "TB920", false), done(false));
        assertEquals("TB920", stopped(cancelled, new Given(ONE, row("1")), skip).getSQLState());
        Connection commit = after(List.of(), prepared(false), done(true), failed(-913, "40002", false), done(false));
        Import.Stopped notCommitted = stopped(commit, new Given(ONE, row("1")), Import.into("T"));
        assertEquals("40002", notCommitted.getSQLState());
        assertNull(notCommitted.where());
        assertEquals(1, notCommitted.rolledBack());
        Connection deallocate = after(List.of(), prepared(false), done(false), failed(-103, "26000", false));
        assertEquals(
                "26000",
                stopped(deallocate, new Given(ONE, row("1")), Import.into("T")).getSQLState());
        Connection rollback = after(
                List.of(), prepared(false), done(true), failed(-301, "22003", true), failed(-919, "08006", false));
        assertEquals(
                "22003",
                stopped(rollback, new Given(ONE, row("1"), row("x")), Import.into("T"))
                        .getSQLState());
    }

    /**
     * Verifies: [CLIENT Copying and exporting] an import by copy, granted, stops if its copy cannot
     * begin, if a string is too long for its column, or if a record cannot be read or is of too many
     * fields, the copy under way abandoned; a copy's failure naming no row of the batch stops at no
     * record; a deallocation of the INSERT that fails stops it first.
     */
    @Test
    void anImportByCopyStops() throws TurborelException {
        List<String> copy = List.of(Capability.COPY);
        Import into = Import.into("T");
        Connection dealloc = after(copy, prepared(false), failed(-103, "26000", false));
        assertEquals("26000", stopped(dealloc, new Given(ONE, row("1")), into).getSQLState());
        Connection empty = after(copy, prepared(false), done(false));
        assertEquals(new Import.Imported(0, 0), empty.bulk().importRows(new Given(ONE), into, r -> {}));
        Connection torn = after(copy, prepared(false), done(false), done(false));
        assertEquals(
                "torn",
                stopped(torn, new Given(ONE, row("1"), "torn"), into).cause().input());
        Connection wide = after(copy, prepared(false), done(false), done(false));
        assertEquals(
                "[DESIGN] 2 fields, for 1 columns",
                stopped(wide, new Given(ONE, row("1"), row("1", "2")), into)
                        .cause()
                        .input());
        Connection first = after(copy, prepared(false), done(false));
        assertEquals(
                "[DESIGN] 2 fields, for 1 columns",
                stopped(first, new Given(ONE, row("1", "2")), into).cause().input());
        Connection unnamed = after(copy, prepared(false), done(false), failed(-201, "23000", false));
        Import.Stopped refused = stopped(unnamed, new Given(ONE, row("1")), into);
        assertNull(refused.where());
        assertEquals(1, refused.rolledBack());
        Connection noCopy = after(List.of(), prepared(true), done(false));
        assertEquals(new Import.Imported(0, 0), noCopy.bulk().importRows(new Given(ONE), into, r -> {}));
    }

    private static byte[] refusedWith(String detail) throws TurborelException {
        return frame(
                'E',
                new Writer()
                        .bits32(-201)
                        .string("23000")
                        .string("no")
                        .flag(false)
                        .flag(false)
                        .flag(false)
                        .u8(8)
                        .string(detail));
    }

    /**
     * Verifies: [PROTOCOL CopyEnd 'N'] a copy's failure whose detail names a row of the batch, as
     * {@code row 1 of the copy}, stops the import at that row's record, the rows before it rolled
     * back; a detail of another form, or of a row past the batch, names none.
     */
    @Test
    void theRowACopyNames() throws TurborelException {
        List<String> copy = List.of(Capability.COPY);
        Import into = Import.into("T");
        Connection named = after(copy, prepared(false), done(false), refusedWith("row 2 of the copy"));
        Import.Stopped second = stopped(named, new Given(ONE, row("1"), row("2")), into);
        assertEquals(new Source.Position(2, null), second.where());
        assertEquals(1, second.rolledBack());
        for (String detail : List.of(
                "row 9 of the copy", "row x of the copy", "rows 1 of the copy", "row 1 of it", "row 0 of the copy")) {
            Connection other = after(copy, prepared(false), done(false), refusedWith(detail));
            assertNull(stopped(other, new Given(ONE, row("1")), into).where(), detail);
        }
    }

    /**
     * Verifies: [CLIENT Large objects] by INSERT, a binary value longer than 64 KiB for a large object
     * column is sent ahead by creating a large object, one shorter as it is; a row whose failure
     * loses the connection stops the import, skipping or not, with no rollback tried.
     */
    @Test
    void longValuesAndALostConnection() throws TurborelException {
        byte[] locator = frame('L', new Writer().u8(1).u32(5));
        Value big = new com.turborel.jdbc.internal.codec.OctetValue.Binary(
                com.turborel.jdbc.internal.codec.Octets.of(new byte[70_000]));
        Value small = new com.turborel.jdbc.internal.codec.OctetValue.Binary(
                com.turborel.jdbc.internal.codec.Octets.of(new byte[3]));
        Connection lob = after(
                List.of(), prepared(true), locator, done(false), done(true), done(true), done(false), done(false));
        assertEquals(
                new Import.Imported(2, 0),
                lob.bulk().importRows(new Given(ONE, List.of(big), List.of(small)), Import.into("T"), r -> {}));
        Connection lost = after(List.of(), prepared(false), failed(-919, "08001", false));
        Import.Stopped stopped = stopped(lost, new Given(ONE, row("1")), new Import("T", 0, true));
        assertEquals("08001", stopped.getSQLState());
        assertEquals(true, lost.isLost());
    }
}
