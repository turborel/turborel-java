package com.turborel.jdbc.internal.session;

import static com.turborel.jdbc.internal.session.Script.after;
import static com.turborel.jdbc.internal.session.Script.complete;
import static com.turborel.jdbc.internal.session.Script.error;
import static com.turborel.jdbc.internal.session.Script.frame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Writer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A reply holding a value the client cannot take, its frame whole: its request fails alone, the
 * rest of its replies read past, and the connection goes on ([PROTOCOL Values and fields],
 * [CLIENT Running statements]).
 */
class ValueFailureTest {
    /** A Description of one BOOLEAN column. */
    private static byte[] truths() throws TurborelException {
        return frame('D', new Writer().u32(1).flag(true).string("B").u8(14).flag(false));
    }

    /** Rows of one row, its truth value the byte given: 0 or 1 a truth, another none. */
    private static byte[] truth(int b) throws TurborelException {
        return frame('B', new Writer().u32(1).u32(1).u8(14).u8(b));
    }

    /** A Complete, a transaction active after it, of the position given, its conditions damaged. */
    private static byte[] damagedComplete(boolean more) throws TurborelException {
        Writer w = new Writer().bits32(0).string("00000").flag(true).u8(4).u64(9);
        if (more) {
            w.u8(5);
        }
        return frame('K', w.u8(17).u32(1).bytes(new byte[] {1, 2, 3}));
    }

    private static void assertCorrupt(Connection c) {
        TurborelException failed =
                assertThrows(TurborelException.class, () -> c.statements().query("SELECT B FROM T"));
        assertEquals("TB916", failed.getSQLState());
        assertEquals(-916, failed.sqlcode());
        assertFalse(c.isLost(), "the connection goes on");
    }

    /** The next request's reply, an Error of 42000, is its own: nothing of the last was left. */
    private static void assertNextIsItsOwn(Connection c) {
        TurborelException next =
                assertThrows(TurborelException.class, () -> c.statements().query("VALUES 1"));
        assertEquals("42000", next.getSQLState());
    }

    /**
     * Verifies: [PROTOCOL Values and fields] a truth byte of 2 fails its request with SQLCODE -916,
     * SQLSTATE TB916, not of class 08; the rest of its replies are read past to its Complete, a
     * Notice taken and a Status noted on the way, the Complete's state noted; the next request
     * takes its own replies, and the connection goes on ([CLIENT Running statements]).
     */
    @Test
    void aValueNotTakenFailsItsRequestAlone() throws TurborelException {
        Connection c = after(
                List.of(),
                truths(),
                truth(2),
                Script.status("SCHEMA", "S"),
                frame('N', new Writer().string("01000").string("noticed")),
                truth(1),
                Scripted.complete(true),
                error(-103, "42000"));
        assertCorrupt(c);
        assertEquals("S", c.setting("SCHEMA"));
        assertEquals("noticed", c.takeNotices().get(0).message());
        assertTrue(c.inTransaction(), "the Complete read past is noted");
        assertNextIsItsOwn(c);
    }

    /**
     * Verifies: [PROTOCOL Values and fields] a Complete whose condition, tag 17, is damaged fails
     * its request with SQLCODE -916, and its head is noted as the whole would be: whether a
     * transaction is active and the commit's position; one whose head says more replies follow,
     * tag 5, is read past to the last.
     */
    @Test
    void aCompleteDamagedGivesItsHead() throws TurborelException {
        Connection c = after(List.of(), damagedComplete(false), error(-103, "42000"));
        assertCorrupt(c);
        assertTrue(c.inTransaction());
        assertEquals(9L, c.told().commitPosition());
        assertNextIsItsOwn(c);
        Connection more = after(List.of(), damagedComplete(true), complete(), error(-103, "42000"));
        assertCorrupt(more);
        assertFalse(more.inTransaction(), "the last Complete's state");
        assertNextIsItsOwn(more);
    }

    /**
     * Verifies: [PROTOCOL Values and fields] a reply read past that itself holds a value the
     * client cannot take is skipped as the first was; one whose frame is malformed loses the
     * connection (SQLSTATE 08006).
     */
    @Test
    void repliesReadPastAreTakenOrSkipped() throws TurborelException {
        Connection c = after(List.of(), truths(), truth(2), truth(3), complete(), error(-103, "42000"));
        assertCorrupt(c);
        assertNextIsItsOwn(c);
        Connection broken = after(List.of(), truths(), truth(2), frame('z', new Writer()));
        TurborelException lost =
                assertThrows(TurborelException.class, () -> broken.statements().query("SELECT B FROM T"));
        assertEquals("08006", lost.getSQLState());
        assertTrue(broken.isLost());
        Connection malformed =
                after(List.of(), frame('B', new Writer().u32(1).u32(1).u8(14)));
        assertThrows(TurborelException.class, () -> malformed.statements().query("SELECT B FROM T"));
        assertTrue(malformed.isLost(), "a frame cut short");
    }

    /**
     * Verifies: [PROTOCOL Values and fields] a Describe of a cursor is answered by a Description
     * alone: one whose column's type is damaged, its units 2, fails the request, nothing more read
     * past, and the next request takes its own replies.
     */
    @Test
    void aDescriptionAloneIsReadAlone() throws TurborelException {
        byte[] damaged = frame(
                'D',
                new Writer().u32(1).flag(true).string("C").u8(1).u32(4).u8(2).flag(false));
        Connection c = after(List.of(Capability.DESCRIBE), damaged, error(-103, "42000"));
        Statements.Cursor cursor = new Statements.Cursor(new Statement(0, List.of(), List.of()), 0);
        TurborelException failed =
                assertThrows(TurborelException.class, () -> c.statements().describe(cursor));
        assertEquals("TB916", failed.getSQLState());
        assertNextIsItsOwn(c);
    }

    /**
     * Verifies: [CLIENT Connecting] Terminate reads past a Notice or a Status to the Error that
     * ends it, and gives that failure; a Notice alone is no failure.
     */
    @Test
    void terminateReadsPastToItsError() throws TurborelException {
        byte[] notice = frame('N', new Writer().string("01000").string("closing"));
        Connection failing = after(List.of(), notice, Script.status("SCHEMA", "S"), error(-913, "40002"));
        assertEquals(
                "40002",
                assertThrows(TurborelException.class, failing::terminate).getSQLState());
        assertTrue(failing.isLost());
        Connection quiet = after(List.of(), notice);
        quiet.terminate();
        assertTrue(quiet.isLost());
    }

    /**
     * Verifies: [PROTOCOL The tail] attributes past their bounds, 33 of them or a value past 1024
     * bytes, are refused before anything is sent, with the connection failure the server would
     * give (SQLSTATE 08006), and the connection kept: the next request runs (RULES.md D8).
     */
    @Test
    void attributesPastTheirBoundsAreRefusedBeforeSending() throws TurborelException {
        Connection c = after(List.of(Capability.QUERY_ATTRIBUTES), complete());
        List<com.turborel.jdbc.internal.codec.Attribute> many = new java.util.ArrayList<>();
        for (int i = 0; i <= com.turborel.jdbc.internal.codec.Attribute.MAX_ATTRIBUTES; i++) {
            many.add(new com.turborel.jdbc.internal.codec.Attribute("a" + i, "v"));
        }
        c.controls().setQueryAttributes(many);
        TurborelException refused =
                assertThrows(TurborelException.class, () -> c.statements().query("VALUES 1"));
        assertEquals("08006", refused.getSQLState());
        assertFalse(c.isLost(), "refused before anything is sent");
        c.controls().setQueryAttributes(List.of(new com.turborel.jdbc.internal.codec.Attribute("a", "v".repeat(1025))));
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, () -> c.statements().query("VALUES 1"))
                        .getSQLState());
        c.controls().setQueryAttributes(List.of());
        assertTrue(c.statements().query("VALUES 1") instanceof Outcome.Done);
    }
}
