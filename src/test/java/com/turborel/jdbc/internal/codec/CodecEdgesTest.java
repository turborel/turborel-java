package com.turborel.jdbc.internal.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import org.junit.jupiter.api.Test;

/** The codec's edges: names and texts it reads, and values it tells apart. */
class CodecEdgesTest {
    /**
     * Verifies: [PROTOCOL Values] a UUID's text, braced or not, is read as CAST reads it: a brace
     * alone, or one not closed, writes none.
     */
    @Test
    void aUuidsTextIsBracedWhole() {
        String plain = "00112233-4455-6677-8899-aabbccddeeff";
        Value.Uuid braced = Value.Uuid.parse("{" + plain + "}");
        assertEquals(Value.Uuid.parse(plain), braced);
        assertTrue(braced != null);
        assertNull(Value.Uuid.parse("{"));
        assertNull(Value.Uuid.parse("{" + plain));
    }

    /**
     * Verifies: [PROTOCOL Errors and notices] a diagnostics tag of no field is none; white space
     * is of the code points SQL names, none past the basic plane among them; JSON values of other
     * stored forms are not equal.
     */
    @Test
    void namesAndTextsAreReadAsTheyAre() throws TurborelException {
        assertNull(DiagnosticField.of(99));
        assertEquals(DiagnosticField.SEVERITY, DiagnosticField.of(7));
        assertFalse(WhiteSpace.is(0x1F600));
        assertTrue(WhiteSpace.is(' ') && WhiteSpace.is(0x3000));
        assertNotEquals(OctetValue.Json.ofText("1"), OctetValue.Json.ofText("2"));
    }
}
