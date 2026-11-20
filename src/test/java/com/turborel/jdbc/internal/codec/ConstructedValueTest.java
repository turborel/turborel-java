package com.turborel.jdbc.internal.codec;

import static com.turborel.jdbc.internal.codec.Fixture.assertBroken;
import static com.turborel.jdbc.internal.codec.Fixture.assertCorrupt;
import static com.turborel.jdbc.internal.codec.Fixture.assertNotInRepertoire;
import static com.turborel.jdbc.internal.codec.Fixture.assertOutOfRange;
import static com.turborel.jdbc.internal.codec.Fixture.bytes;
import static com.turborel.jdbc.internal.codec.Fixture.cat;
import static com.turborel.jdbc.internal.codec.Fixture.decoded;
import static com.turborel.jdbc.internal.codec.Fixture.encoded;
import static com.turborel.jdbc.internal.codec.Fixture.hex;
import static com.turborel.jdbc.internal.codec.Fixture.str;
import static com.turborel.jdbc.internal.codec.Fixture.u32;
import static com.turborel.jdbc.internal.codec.Fixture.u64;
import static com.turborel.jdbc.internal.codec.Fixture.u8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.turborel.jdbc.TurborelException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The constructed values, large objects and the values of octets ([PROTOCOL Values]). */
class ConstructedValueTest {
    private static final Value ONE = new Value.Text("1");

    /**
     * Verifies: [PROTOCOL Values] tags 16, 17 and 22: an array, a row and a multiset, each a list
     * of values, nested in each other and empty.
     */
    @Test
    void arraysRowsAndMultisetsNest() throws TurborelException {
        Value nested = new Value.Array(List.of(new Value.Row(List.of(ONE, Value.NULL)), new Value.Multiset(List.of())));
        byte[] bytes = cat(u8(16), u32(2), u8(17), u32(2), u8(1), str("1"), u8(0), u8(22), u32(0));
        assertArrayEquals(bytes, encoded(nested));
        assertEquals(nested, decoded(bytes));
        byte[] deep = new byte[0];
        for (int i = 0; i <= Reader.MAX_NESTING; i++) {
            deep = cat(deep, u8(16), u32(1));
        }
        byte[] tooDeep = cat(deep, u8(0));
        assertBroken(() -> decoded(tooDeep));
    }

    /**
     * Verifies: [PROTOCOL Values] a multiset holds at most 65535 elements, Turborel's maximum
     * cardinality; more fails the connection.
     */
    @Test
    void aMultisetIsBounded() throws TurborelException {
        byte[] most = cat(u8(22), u32(ValueCodec.MAX_MULTISET_CARDINALITY), new byte[65_535]);
        assertEquals(65_535, ((Value.Multiset) decoded(most)).elements().size());
        byte[] more = cat(u8(22), u32(ValueCodec.MAX_MULTISET_CARDINALITY + 1), new byte[65_536]);
        assertBroken(() -> decoded(more));
    }

    /**
     * Verifies: [PROTOCOL Values] tag 23, a structured value: its most specific type's schema and
     * name, then its attributes.
     */
    @Test
    void structuredValuesNameTheirType() throws TurborelException {
        Value value = new Value.Structured(new TypeName("S", "POINT"), List.of(ONE, ONE));
        byte[] bytes = cat(u8(23), str("S"), str("POINT"), u32(2), u8(1), str("1"), u8(1), str("1"));
        assertArrayEquals(bytes, encoded(value));
        assertEquals(value, decoded(bytes));
    }

    /**
     * Verifies: [PROTOCOL Values] tag 20, a large object whole: its kind, 0 character or 1
     * binary, then its octets, a CLOB's UTF-8; one not UTF-8 or of another kind fails.
     */
    @Test
    void largeObjectsWhole() throws TurborelException {
        Lob.Whole clob = Lob.Whole.ofText("é");
        assertArrayEquals(cat(u8(20), u8(0), bytes(hex("C3A9"))), encoded(clob));
        assertEquals(clob, decoded(encoded(clob)));
        assertEquals(LobKind.CHARACTER, clob.kind());
        Lob.Whole blob = new Lob.Whole(LobKind.BINARY, Octets.of(hex("FF00")));
        assertEquals(blob, decoded(cat(u8(20), u8(1), bytes(hex("FF00")))));
        assertBroken(() -> decoded(cat(u8(20), u8(0), bytes(hex("FF00")))));
        assertBroken(() -> decoded(cat(u8(20), u8(2), bytes(hex("00")))));
        assertNotInRepertoire(() -> Lob.Whole.ofText("\uDC00"));
        assertEquals(0, LobKind.CHARACTER.code());
    }

    /**
     * Verifies: [PROTOCOL Values] tag 21, a large object by locator: kind, locator u32, its
     * octets u64 and its characters u64.
     */
    @Test
    void largeObjectsByLocator() throws TurborelException {
        Lob.Locator locator = new Lob.Locator(LobKind.CHARACTER, 0xFFFF_FFFFL, 5_000_000_000L, -1);
        byte[] bytes = cat(u8(21), u8(0), u32(-1), u64(5_000_000_000L), u64(-1));
        assertArrayEquals(bytes, encoded(locator));
        assertEquals(locator, decoded(bytes));
        assertOutOfRange(() -> encoded(new Lob.Locator(LobKind.BINARY, -1, 0, 0)));
    }

    /**
     * Verifies: [PROTOCOL Values] tags 6 and 24: a binary string and a reference, each bytes.
     */
    @Test
    void binaryStringsAndReferences() throws TurborelException {
        Value binary = new OctetValue.Binary(Octets.of(hex("AB00C0")));
        assertArrayEquals(cat(u8(6), bytes(hex("AB00C0"))), encoded(binary));
        assertEquals(binary, decoded(encoded(binary)));
        Value reference = new OctetValue.Reference(Octets.of(hex("0102")));
        assertArrayEquals(cat(u8(24), bytes(hex("0102"))), encoded(reference));
        assertEquals(reference, decoded(encoded(reference)));
    }

    /**
     * Verifies: [PROTOCOL Data types] a plug-in's value the client does not have is read by its
     * type's name, its text and its bytes, and sent back unchanged.
     */
    @Test
    void aPluginValueIsKeptAsItCame() throws TurborelException {
        byte[] bytes = cat(u8(4), str("GEO"), str("POINT(1 2)"), bytes(hex("010203")));
        Value value = decoded(bytes);
        assertEquals(new OctetValue.Plugin("GEO", "POINT(1 2)", Octets.of(hex("010203"))), value);
        assertArrayEquals(bytes, encoded(value));
    }

    /**
     * Verifies: [PROTOCOL Values] tag 26, an XML value, its serialization as CONTENT, a string.
     */
    @Test
    void xmlValuesAreTheirSerialization() throws TurborelException {
        Value xml = new Value.Xml("<order no=\"18\"/>");
        assertArrayEquals(cat(u8(26), str("<order no=\"18\"/>")), encoded(xml));
        assertEquals(xml, decoded(encoded(xml)));
    }

    /**
     * Verifies: [PROTOCOL Values] tag 27, a UUID: bytes, a count of 16, then the 16 octets most
     * significant first (Turborel's RULES.md D184); another count fails.
     */
    @Test
    void uuidsAreSixteenOctets() throws TurborelException {
        UUID id = UUID.fromString("919108f7-52d1-4320-9bac-f847db4148a8");
        byte[] bytes = cat(u8(27), bytes(hex("919108f752d143209bacf847db4148a8")));
        assertArrayEquals(bytes, encoded(new Value.Uuid(id)));
        assertEquals(new Value.Uuid(id), decoded(bytes));
        assertBroken(() -> decoded(cat(u8(27), bytes(new byte[15]))));
        assertBroken(() -> decoded(cat(u8(27), bytes(new byte[17]))));
    }

    /**
     * Verifies: [PROTOCOL Values] tag 25, a JSON value, its stored form as bytes, checked whole
     * as it is read; a damaged one fails the connection.
     */
    @Test
    void jsonValuesAreTheirStoredForm() throws TurborelException {
        byte[] stored = JsonBinaryWriter.encode(new JsonItem.ScalarItem(new Value.Truth(true)));
        assertArrayEquals(hex("0102"), stored);
        OctetValue.Json json = OctetValue.Json.ofStored(stored);
        assertArrayEquals(cat(u8(25), bytes(stored)), encoded(json));
        assertEquals(json, decoded(encoded(json)));
        assertEquals(new JsonItem.ScalarItem(new Value.Truth(true)), json.item());
        assertArrayEquals(stored, json.stored());
        assertEquals(json.hashCode(), OctetValue.Json.of(json.item()).hashCode());
        assertEquals("Json[0102]", json.toString());
        assertNotEquals(json, ONE);
        assertBroken(() -> decoded(cat(u8(25), bytes(hex("0202")))));
        assertCorrupt(() -> OctetValue.Json.ofStored(hex("01")));
    }

    /**
     * Verifies: [PROTOCOL Composite fields] a row is a list of values, as Rows and Execute carry
     * it.
     */
    @Test
    void aRowIsAListOfValues() throws TurborelException {
        List<Value> row = new ArrayList<>(List.of(ONE, Value.NULL));
        Writer w = new Writer();
        ValueCodec.writeRow(w, row);
        assertArrayEquals(cat(u32(2), u8(1), str("1"), u8(0)), w.toByteArray());
        assertEquals(row, ValueCodec.readRow(Fixture.reader(w.toByteArray())));
    }
}
