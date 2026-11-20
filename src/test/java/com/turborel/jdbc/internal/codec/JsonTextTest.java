package com.turborel.jdbc.internal.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.turborel.jdbc.TurborelException;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A JSON value's text, as Turborel writes it ([PROTOCOL Values]). */
class JsonTextTest {
    private static String text(Value value) throws TurborelException {
        return JsonText.of(new JsonItem.ScalarItem(value));
    }

    /**
     * Verifies: [PROTOCOL Values] a JSON value reads to its text: no white space, members in the
     * order kept, true, false and null by name.
     */
    @Test
    void containersAndNames() throws TurborelException {
        JsonItem item = new JsonItem.ObjectItem(List.of(
                new JsonItem.Member(
                        "b",
                        new JsonItem.ArrayItem(List.of(JsonItem.NULL, new JsonItem.ScalarItem(new Value.Truth(true))))),
                new JsonItem.Member("a", new JsonItem.ScalarItem(new Value.Truth(false)))));
        assertEquals("{\"b\":[null,true],\"a\":false}", JsonText.of(item));
        assertEquals("[]", JsonText.of(new JsonItem.ArrayItem(List.of())));
        assertEquals("{}", JsonText.of(new JsonItem.ObjectItem(List.of())));
    }

    /**
     * Verifies: [PROTOCOL Values] a string escapes its quotation marks, reverse solidi and control
     * characters, by RFC 8259's two-character escapes where it has one, and nothing else.
     */
    @Test
    void stringsEscapeWhatRfc8259Escapes() throws TurborelException {
        assertEquals(
                "\"q\\\" s\\\\ \\b\\f\\n\\r\\t \\u0000\\u001f / é\"",
                text(new Value.Text("q\" s\\ \b\f\n\r\t \u0000\u001F / é")));
    }

    /**
     * Verifies: [PROTOCOL Values] numbers are written as Turborel's CAST writes them: an exact
     * number by its digits and scale, a decimal floating point number plain, an approximate one
     * with a point and an exponent.
     */
    @Test
    void numbersAsCastWritesThem() throws TurborelException {
        assertEquals("1.50", text(new Value.Exact(BigInteger.valueOf(150), 2)));
        assertEquals("-7", text(new Value.Exact(BigInteger.valueOf(-7), 0)));
        assertEquals("15000", text(new Value.Decfloat(BigInteger.valueOf(15), 3)));
        assertEquals("0.015", text(new Value.Decfloat(BigInteger.valueOf(15), -3)));
        assertEquals("1.5E0", text(new Value.Approximate(1.5)));
        assertEquals("1.0E2", text(new Value.Approximate(100)));
        assertEquals("1.0E-3", text(new Value.Approximate(0.001)));
        assertEquals("-2.5E10", text(new Value.Approximate(-2.5e10)));
        assertEquals("0E0", text(new Value.Approximate(0)));
    }

    /**
     * Verifies: [PROTOCOL Values] no JSON text parses to a datetime, an approximate number not
     * finite, or a value of no scalar's kind: SQLSTATE 22032.
     */
    @Test
    void whatHasNoTextIsRefused() {
        for (Value v : List.of(
                new Value.Datetime(Value.Moment.DATE, false, 0, (short) 0, 0),
                new Value.Approximate(Double.NaN),
                new Value.Approximate(Double.NEGATIVE_INFINITY),
                Value.NULL)) {
            TurborelException e = assertThrows(TurborelException.class, () -> text(v));
            assertEquals(JsonText.INVALID_JSON_TEXT, e.getSQLState());
            assertEquals(JsonText.DATA_SQLCODE, e.sqlcode());
        }
    }
}
