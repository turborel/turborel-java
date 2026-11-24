package com.turborel.jdbc.internal.session;

import static com.turborel.jdbc.internal.session.Script.after;
import static com.turborel.jdbc.internal.session.Script.complete;
import static com.turborel.jdbc.internal.session.Script.frame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Debugging;
import com.turborel.jdbc.internal.codec.TypeName;
import com.turborel.jdbc.internal.codec.Writer;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The routine debugger over a connection the test scripts ([CLIENT Debugging routines]). */
class DebuggerTest {
    private static byte[] event(int what) throws TurborelException {
        return frame('W', new Writer().u8(what));
    }

    private static byte[] debugged(Writer body) throws TurborelException {
        return frame('V', body);
    }

    /** A breakpoint's fields: number 1, ANN.P, line 4, no condition. */
    private static Writer breakpoint(Writer w) throws TurborelException {
        return w.u32(1).string("ANN").string("P").u32(4).flag(false);
    }

    /**
     * Verifies: [PROTOCOL Debug 'V'] an Event is read wherever a reply is read, between a
     * request's replies too, and never ends one: kept, it is the next the debugger waits for; a
     * Notice or a Status that comes in its place is taken, no event given.
     */
    @Test
    void anEventIsKeptWhereverItComes() throws TurborelException {
        Connection c = after(
                List.of(Capability.DEBUGGER),
                event(1),
                complete(),
                frame('N', new Writer().string("01000").string("heard")),
                event(3));
        assertTrue(c.statements().query("VALUES 1") instanceof Outcome.Done, "the Event ended nothing");
        Debugger d = c.debugger();
        assertInstanceOf(Debugging.Event.TimedOut.class, d.waitFor(null));
        assertNull(d.waitFor(null), "a Notice in its place");
        assertEquals("heard", c.takeNotices().get(0).message());
        assertInstanceOf(Debugging.Event.Ended.class, d.waitFor(null));
    }

    /**
     * Verifies: [CLIENT Debugging routines] identify gives the connection's ID; break the
     * breakpoint set and breakpoints each set; stack the pause, none of a target running; attach,
     * detach, clear, resume and interrupt are answered by a Complete; a refusal keeps the
     * connection.
     */
    @Test
    void eachRequestIsAnswered() throws TurborelException {
        Connection c = after(
                List.of(Capability.DEBUGGER),
                debugged(new Writer().u8(0).u64(7)),
                complete(),
                debugged(breakpoint(new Writer().u8(1))),
                debugged(breakpoint(new Writer().u8(2).u32(1))),
                debugged(new Writer().u8(3).flag(false)),
                complete(),
                complete(),
                complete(),
                Script.error(-910, "TBD01"),
                complete());
        Debugger d = c.debugger();
        assertEquals(7, d.id());
        d.attach(7);
        Debugging.Breakpoint set = d.breakAt(Debugging.Kind.PROCEDURE, new TypeName("", "P"), 4, null);
        assertEquals(new Debugging.Breakpoint(1, new TypeName("ANN", "P"), 4, null), set);
        assertEquals(List.of(set), d.breakpoints());
        assertNull(d.stack());
        d.clear(1);
        d.resume(Debugging.Action.OVER);
        d.interrupt();
        TurborelException refused = assertThrows(TurborelException.class, () -> d.resume(Debugging.Action.INTO));
        assertEquals("TBD01", refused.getSQLState());
        d.detach();
        assertTrue(!c.isLost());
    }

    /**
     * Verifies: [CLIENT Debugging routines] the debugger needs {@code debugger} granted, refused
     * before anything is sent; a reply other than Debugged where one is due loses the connection;
     * a wait bounded on a stream the program gave is refused, as it bounds the stream itself.
     */
    @Test
    void misuseIsRefused() throws TurborelException {
        Connection plain = after(List.of());
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, () -> plain.debugger().id())
                        .getSQLState());
        assertTrue(!plain.isLost());
        Connection pong = after(List.of(Capability.DEBUGGER), Script.pong(), Script.pong());
        assertThrows(TurborelException.class, () -> pong.debugger().id());
        assertTrue(pong.isLost());
        Connection own = after(List.of(Capability.DEBUGGER));
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, () -> own.debugger().waitFor(Duration.ofMillis(10)))
                        .getSQLState());
        Connection closed = after(List.of(Capability.DEBUGGER));
        assertThrows(TurborelException.class, () -> closed.debugger().waitFor(null));
        assertTrue(closed.isLost(), "the server gone");
        Connection answered = after(List.of(Capability.DEBUGGER), Script.pong());
        assertThrows(TurborelException.class, () -> answered.debugger().waitFor(null));
        assertTrue(answered.isLost(), "a reply where an event was waited for");
    }

    /**
     * Verifies: [PROTOCOL Debug 'V'] a Debugged whose answer is not the one its request is due,
     * an ID where a breakpoint, a list, a stack or an ID was asked for, loses the connection.
     */
    @Test
    void anAnswerOfAnotherKindLosesTheConnection() throws TurborelException {
        byte[] id = debugged(new Writer().u8(0).u64(7));
        byte[] stack = debugged(new Writer().u8(3).flag(false));
        List<ThrowingConsumer<Debugger>> asks = List.of(
                Debugger::id,
                d -> d.breakAt(Debugging.Kind.FUNCTION, new TypeName("", "F"), 1, "X > 0"),
                Debugger::breakpoints,
                Debugger::stack);
        for (int k = 0; k < asks.size(); k++) {
            Connection c = after(List.of(Capability.DEBUGGER), k == 0 ? stack : id);
            ThrowingConsumer<Debugger> ask = asks.get(k);
            assertThrows(TurborelException.class, () -> ask.accept(c.debugger()));
            assertTrue(c.isLost(), "answer " + k);
        }
    }

    /** A request of the debugger's, failing as it does. */
    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T t) throws TurborelException;
    }
}
