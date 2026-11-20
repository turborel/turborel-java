package com.turborel.jdbc.internal.codec;

import static com.turborel.jdbc.internal.codec.Fixture.assertBroken;
import static com.turborel.jdbc.internal.codec.Fixture.cat;
import static com.turborel.jdbc.internal.codec.Fixture.str;
import static com.turborel.jdbc.internal.codec.Fixture.type;
import static com.turborel.jdbc.internal.codec.Fixture.u32;
import static com.turborel.jdbc.internal.codec.Fixture.u64;
import static com.turborel.jdbc.internal.codec.Fixture.u8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.DataType.XmlPrimary;
import com.turborel.jdbc.internal.codec.DataType.XmlSecondary;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Data types, as the server writes columns' and parameters' ([PROTOCOL Data types]). */
class TypeCodecTest {
    private static final byte[] INTEGER = cat(u8(2), u32(10), u32(0));

    /**
     * Verifies: [PROTOCOL Data types] tags 1 to 7: CHARACTER and CHARACTER VARYING of a length
     * and units, exact and approximate numeric, a plug-in's type by its name and parameters, and
     * BINARY and BINARY VARYING of a length.
     */
    @Test
    void theScalarTypesReadTheirParameters() throws TurborelException {
        assertEquals(new DataType.CharacterType(false, 4, CharUnits.CHARACTERS), type(cat(u8(1), u32(4), u8(0))));
        assertEquals(
                new DataType.CharacterType(true, 0xFFFF_FFFFL, CharUnits.OCTETS), type(cat(u8(5), u32(-1), u8(1))));
        assertEquals(new DataType.ExactType(10, 0), type(INTEGER));
        assertEquals(new DataType.ApproximateType(53), type(cat(u8(3), u32(53))));
        assertEquals(
                new DataType.PluginType("GEO", List.of(2L, 7L)), type(cat(u8(4), str("GEO"), u32(2), u32(2), u32(7))));
        assertEquals(new DataType.BinaryType(false, 16), type(cat(u8(6), u32(16))));
        assertEquals(new DataType.BinaryType(true, 3), type(cat(u8(7), u32(3))));
        assertBroken(() -> type(cat(u8(1), u32(4), u8(2))));
    }

    /**
     * Verifies: [PROTOCOL Data types] tags 8 to 12, the datetime types, each with its precision.
     */
    @Test
    void theDatetimeTypesReadTheirPrecision() throws TurborelException {
        int tag = 8;
        for (DatetimeKind kind : DatetimeKind.values()) {
            assertEquals(tag, kind.tag());
            assertEquals(new DataType.DatetimeType(kind, 6), type(cat(u8(tag), u32(6))));
            tag++;
        }
    }

    /**
     * Verifies: [PROTOCOL Data types] tag 13, INTERVAL: start field, end field, leading and
     * fractional precision, the fields numbered 0 YEAR to 5 SECOND; a field past 5 fails.
     */
    @Test
    void anIntervalReadsItsQualifier() throws TurborelException {
        int number = 0;
        for (IntervalField field : IntervalField.values()) {
            assertEquals(number++, field.number());
        }
        Qualifier daySecond = new Qualifier(IntervalField.DAY, IntervalField.SECOND, 9, 2);
        assertEquals(new DataType.IntervalType(daySecond), type(cat(u8(13), u32(2), u32(5), u32(9), u32(2))));
        Qualifier yearMonth = new Qualifier(IntervalField.YEAR, IntervalField.MONTH, 2, 0);
        assertEquals(new DataType.IntervalType(yearMonth), type(cat(u8(13), u32(0), u32(1), u32(2), u32(0))));
        assertTrue(yearMonth.yearMonth());
        assertTrue(new Qualifier(IntervalField.MONTH, IntervalField.MONTH, 2, 0).yearMonth());
        assertFalse(daySecond.yearMonth());
        assertBroken(() -> type(cat(u8(13), u32(6), u32(5), u32(9), u32(2))));
    }

    /**
     * Verifies: [PROTOCOL Data types] tags 14, 15, 25 and 27: BOOLEAN, DECFLOAT of a precision,
     * JSON, and UUID, which has no parameters (Turborel's RULES.md D184).
     */
    @Test
    void theTypesOfFewParameters() throws TurborelException {
        assertEquals(new DataType.BooleanType(), type(u8(14)));
        assertEquals(new DataType.DecfloatType(34), type(cat(u8(15), u32(34))));
        assertEquals(new DataType.JsonType(), type(u8(25)));
        assertEquals(new DataType.UuidType(), type(u8(27)));
    }

    /**
     * Verifies: [PROTOCOL Data types] tags 16, 17 and 22: an array of a maximum cardinality, 1 to
     * 65535, then its element type; a row of one field or more; a multiset of its element type,
     * nested in each other.
     */
    @Test
    void theConstructedTypesNest() throws TurborelException {
        assertEquals(
                new DataType.ArrayType(65_535, new DataType.ExactType(10, 0)), type(cat(u8(16), u32(65_535), INTEGER)));
        DataType row = new DataType.RowType(List.of(new DataType.Field("A", new DataType.ExactType(10, 0))));
        assertEquals(row, type(cat(u8(17), u32(1), str("A"), INTEGER)));
        DataType nested = new DataType.MultisetType(new DataType.MultisetType(new DataType.BooleanType()));
        assertEquals(nested, type(cat(u8(22), u8(22), u8(14))));
        assertBroken(() -> type(cat(u8(16), u32(0), INTEGER)));
        assertBroken(() -> type(cat(u8(16), u32(65_536), INTEGER)));
        assertBroken(() -> type(cat(u8(17), u32(0))));
        byte[] deep = new byte[Reader.MAX_NESTING + 1];
        java.util.Arrays.fill(deep, (byte) 22);
        assertBroken(() -> type(cat(deep, u8(14))));
    }

    /**
     * Verifies: [PROTOCOL Data types] tags 18 and 19: the large object types, a length u64 and
     * units.
     */
    @Test
    void theLargeObjectTypes() throws TurborelException {
        assertEquals(
                new DataType.LobType(LobKind.CHARACTER, 1L << 32, CharUnits.CHARACTERS),
                type(cat(u8(18), u64(1L << 32), u8(0))));
        assertEquals(new DataType.LobType(LobKind.BINARY, -1, CharUnits.OCTETS), type(cat(u8(19), u64(-1), u8(1))));
    }

    /**
     * Verifies: [PROTOCOL Data types] tags 23 and 24: a structured type's schema and name and its
     * proper supertypes; a reference type's referenced names, then scoped, and the scope's names.
     */
    @Test
    void theUserDefinedTypes() throws TurborelException {
        byte[] names = cat(str("S"), str("T"), u32(1), str("S"), str("BASE"));
        DataType.StructuredType structured =
                new DataType.StructuredType(new TypeName("S", "T"), List.of(new TypeName("S", "BASE")));
        assertEquals(structured, type(cat(u8(23), names)));
        assertEquals(new DataType.ReferenceType(structured, null), type(cat(u8(24), names, u8(0))));
        DataType scoped = new DataType.ReferenceType(structured, new TypeName("S", "TAB"));
        assertEquals(scoped, type(cat(u8(24), names, u8(1), str("S"), str("TAB"))));
        assertBroken(() -> type(cat(u8(24), names, u8(2))));
    }

    /**
     * Verifies: [PROTOCOL Data types] tag 26, XML: its primary and secondary modifiers; of
     * XMLSCHEMA, the schema's schema and name, then its namespace and element, each 0 for none or
     * 1 and a string; SEQUENCE is UNTYPED, and a modifier of no meaning fails.
     */
    @Test
    void anXmlTypeReadsItsModifiers() throws TurborelException {
        assertEquals(new DataType.XmlType(XmlPrimary.CONTENT, XmlSecondary.ANY, null), type(cat(u8(26), u8(1), u8(1))));
        assertEquals(
                new DataType.XmlType(XmlPrimary.SEQUENCE, XmlSecondary.UNTYPED, null), type(cat(u8(26), u8(2), u8(0))));
        byte[] schema = cat(u8(26), u8(0), u8(2), str("S"), str("PO"), u8(1), str("urn:po"), u8(0));
        DataType.XmlSchema po = new DataType.XmlSchema("S", "PO", "urn:po", null);
        assertEquals(new DataType.XmlType(XmlPrimary.DOCUMENT, XmlSecondary.XMLSCHEMA, po), type(schema));
        assertEquals(0, XmlPrimary.DOCUMENT.code());
        assertEquals(2, XmlSecondary.XMLSCHEMA.code());
        assertBroken(() -> type(cat(u8(26), u8(2), u8(1))));
        assertBroken(() -> type(cat(u8(26), u8(3), u8(0))));
        assertBroken(() -> type(cat(u8(26), u8(0), u8(3))));
        assertBroken(() -> type(cat(u8(26), u8(0), u8(2), str("S"), str("PO"), u8(2))));
    }

    /**
     * Verifies: [PROTOCOL Frames] a data type of a tag no type has, 0, 20, 21 or 28, fails the
     * connection.
     */
    @Test
    void aTagOfNoTypeFails() {
        for (int tag : new int[] {0, 20, 21, 28, 255}) {
            assertBroken(() -> type(u8(tag)));
        }
    }
}
