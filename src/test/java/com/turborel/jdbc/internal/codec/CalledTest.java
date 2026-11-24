package com.turborel.jdbc.internal.codec;

import static com.turborel.jdbc.internal.codec.Fixture.assertBroken;
import static com.turborel.jdbc.internal.codec.Fixture.cat;
import static com.turborel.jdbc.internal.codec.Fixture.str;
import static com.turborel.jdbc.internal.codec.Fixture.u32;
import static com.turborel.jdbc.internal.codec.Fixture.u8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Parameter modes, tail 20 of a Prepared of a CALL ([PROTOCOL Parameter modes]). */
class CalledTest {
    /** A parameter field of an exact number of precision 10. */
    private static final byte[] EXACT = cat(u8(0), u8(2), u32(10), u32(0));

    /** A Prepared of statement 1, one input parameter and one column G, then {@code tail}. */
    private static Reply.Prepared prepared(byte[] tail) throws TurborelException {
        byte[] column = cat(u8(1), str("G"), u8(2), u32(10), u32(0), u8(0));
        byte[] body = cat(u32(1), u32(1), EXACT, u32(1), column, tail);
        return (Reply.Prepared) ReplyCodec.decode(Reply.PREPARED, body);
    }

    /** Tail 20 of S.P and the marks given, each a mode, an ordinal and an exact number. */
    private static byte[] called(int... modesAndOrdinals) {
        byte[] marks = u32(modesAndOrdinals.length / 2);
        for (int i = 0; i < modesAndOrdinals.length; i += 2) {
            marks = cat(marks, u8(modesAndOrdinals[i]), u32(modesAndOrdinals[i + 1]), EXACT);
        }
        return cat(u8(Tail.CALLED), str("S"), str("P"), marks);
    }

    /**
     * Verifies: [PROTOCOL Parameter modes] a Prepared of a CALL ends with tail 20: the procedure's
     * specific schema and name, then each {@code ?} in the order written, OUT ones too, each its
     * parameter's mode (1 IN, 2 INOUT, 4 OUT), ordinal position from 1, and type; after the
     * declared types, tail 19, if both come; several marks of one IN argument each its mode and
     * position.
     */
    @Test
    void aCallTellsEachMarksMode() throws TurborelException {
        Reply.Prepared told = prepared(called(1, 1, 1, 1, 4, 2, 2, 3));
        Called called = java.util.Objects.requireNonNull(told.called());
        assertEquals(new TypeName("S", "P"), called.procedure());
        Parameter exact = new Parameter(false, new DataType.ExactType(10, 0));
        assertEquals(
                List.of(
                        new Called.Mark(ParameterMode.IN, 1, exact),
                        new Called.Mark(ParameterMode.IN, 1, exact),
                        new Called.Mark(ParameterMode.OUT, 2, exact),
                        new Called.Mark(ParameterMode.INOUT, 3, exact)),
                called.marks());
        byte[] declared = cat(u8(Tail.DECLARED), u32(1), str("INTEGER"), str("INTEGER"));
        Reply.Prepared both = prepared(cat(declared, called(4, 1)));
        assertEquals(new Declared("INTEGER", "INTEGER"), both.columns().get(0).declared());
        assertEquals(
                ParameterMode.OUT,
                java.util.Objects.requireNonNull(both.called()).marks().get(0).mode());
        assertTrue(ParameterMode.INOUT.takesValue() && ParameterMode.INOUT.givesValue());
        assertFalse(ParameterMode.OUT.takesValue() || ParameterMode.IN.givesValue());
        assertEquals(4, ParameterMode.OUT.code());
    }

    /**
     * Verifies: [PROTOCOL Parameter modes] a Prepared of another statement has no tail 20, so none
     * is told and each parameter is IN; a mode of another code, or a position of 0, fails the
     * connection (SQLSTATE 08006); tail 20 before tail 19 is out of order.
     */
    @Test
    void aBadModeOrPositionFailsTheConnection() throws TurborelException {
        assertNull(prepared(new byte[0]).called());
        assertBroken(() -> prepared(called(3, 1)));
        assertBroken(() -> prepared(called(1, 0)));
        byte[] declared = cat(u8(Tail.DECLARED), u32(1), str("INTEGER"), str("INTEGER"));
        assertBroken(() -> prepared(cat(called(1, 1), declared)));
    }
}
