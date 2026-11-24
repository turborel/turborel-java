package com.turborel.jdbc.internal.codec;

import static com.turborel.jdbc.internal.codec.Fixture.assertBroken;
import static com.turborel.jdbc.internal.codec.Fixture.cat;
import static com.turborel.jdbc.internal.codec.Fixture.str;
import static com.turborel.jdbc.internal.codec.Fixture.u32;
import static com.turborel.jdbc.internal.codec.Fixture.u64;
import static com.turborel.jdbc.internal.codec.Fixture.u8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.turborel.jdbc.TurborelException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The debugger's messages read ([PROTOCOL Debug 'V']). */
class DebuggingTest {
    /** A frame of no origin, visible, of no place nor line, empty lists. */
    private static final byte[] BARE = cat(u8(0), u8(1), u8(0), u8(0), u32(0), u32(0), u32(0));

    /**
     * Verifies: [PROTOCOL Debug 'V'] a frame read whole: none of an origin, a place, a line given
     * where its flags say none; a pause at the end of a step names no breakpoint; a condition
     * given with a breakpoint; an Event never ends a request's replies.
     */
    @Test
    void framesAndPausesReadWhole() throws TurborelException {
        byte[] stepped = cat(u8(1), u32(1), BARE);
        Reply.Event event = (Reply.Event) ReplyCodec.decode(Reply.EVENT, cat(u8(0), stepped));
        Debugging.Frame bare = new Debugging.Frame(null, true, null, null, null, List.of(), List.of(), List.of());
        assertEquals(new Debugging.Event.Pause(new Debugging.Paused(null, List.of(bare))), event.event());
        byte[] conditioned = cat(u8(1), u32(2), str("S"), str("F"), u32(9), u8(1), str("N > 1"));
        Reply.Debugged set = (Reply.Debugged) ReplyCodec.decode(Reply.DEBUGGED, conditioned);
        assertEquals(
                new Debugging.Answer.Set(new Debugging.Breakpoint(2, new TypeName("S", "F"), 9, "N > 1")),
                set.answer());
        assertFalse(ReplyCodec.endsRequest(Reply.EVENT, cat(u8(3))));
        byte[] trigger = cat(
                u8(1), u8(2), str("S"), str("T"), str("S"), str("T"), u8(0), u8(1), u64(5), u8(1), u32(2), u32(1),
                u32(0), u32(0), u32(0));
        Reply.Event invisible = (Reply.Event) ReplyCodec.decode(Reply.EVENT, cat(u8(0), u8(1), u32(1), trigger));
        Debugging.Frame frame =
                ((Debugging.Event.Pause) invisible.event()).paused().frames().get(0);
        assertEquals(
                Debugging.Kind.TRIGGER,
                frame.origin() == null ? null : frame.origin().kind());
        assertFalse(frame.visible());
        assertEquals(5L, frame.at());
    }

    /**
     * Verifies: [PROTOCOL Debug 'V'] a Debugged, an Event, a pause, or a routine's kind of a code
     * none has fails the connection (SQLSTATE 08006).
     */
    @Test
    void aKindOfNoneFailsTheConnection() {
        assertBroken(() -> ReplyCodec.decode(Reply.DEBUGGED, cat(u8(9))));
        assertBroken(() -> ReplyCodec.decode(Reply.EVENT, cat(u8(7))));
        assertBroken(() -> ReplyCodec.decode(Reply.EVENT, cat(u8(0), u8(5))));
        assertBroken(() -> ReplyCodec.decode(Reply.EVENT, cat(u8(0), u8(1), u32(1), u8(1), u8(3))));
    }
}
