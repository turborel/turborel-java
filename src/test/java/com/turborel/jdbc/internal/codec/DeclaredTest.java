package com.turborel.jdbc.internal.codec;

import static com.turborel.jdbc.internal.codec.Fixture.cat;
import static com.turborel.jdbc.internal.codec.Fixture.str;
import static com.turborel.jdbc.internal.codec.Fixture.u32;
import static com.turborel.jdbc.internal.codec.Fixture.u8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.turborel.jdbc.TurborelException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Declared types, tail 19 of a Prepared and a Description ([PROTOCOL Declared types]). */
class DeclaredTest {
    private static final DataType INT = new DataType.ExactType(10, 0);

    private static final DataType.StructuredType POINT =
            new DataType.StructuredType(new TypeName("S", "POINT"), List.of());

    /** Each type, its name and its text. */
    private static final Object[][] CASES = {
        {new DataType.CharacterType(true, 10, CharUnits.CHARACTERS), "CHARACTER VARYING", "CHARACTER VARYING(10)"},
        {new DataType.CharacterType(false, 3, CharUnits.OCTETS), "CHARACTER", "CHARACTER(3 OCTETS)"},
        {INT, "NUMERIC", "NUMERIC(10,0)"},
        {new DataType.ApproximateType(53), "FLOAT", "FLOAT(53)"},
        {new DataType.DecfloatType(34), "DECFLOAT", "DECFLOAT(34)"},
        {new DataType.BooleanType(), "BOOLEAN", "BOOLEAN"},
        {new DataType.DatetimeType(DatetimeKind.DATE, 0), "DATE", "DATE"},
        {new DataType.DatetimeType(DatetimeKind.TIME, 3), "TIME", "TIME(3)"},
        {new DataType.DatetimeType(DatetimeKind.TIME_ZONED, 0), "TIME WITH TIME ZONE", "TIME(0) WITH TIME ZONE"},
        {new DataType.DatetimeType(DatetimeKind.TIMESTAMP, 6), "TIMESTAMP", "TIMESTAMP(6)"},
        {
            new DataType.DatetimeType(DatetimeKind.TIMESTAMP_ZONED, 6),
            "TIMESTAMP WITH TIME ZONE",
            "TIMESTAMP(6) WITH TIME ZONE"
        },
        {new DataType.BinaryType(true, 4), "BINARY VARYING", "BINARY VARYING(4)"},
        {new DataType.BinaryType(false, 4), "BINARY", "BINARY(4)"},
        {
            new DataType.LobType(LobKind.CHARACTER, 100, CharUnits.CHARACTERS),
            "CHARACTER LARGE OBJECT",
            "CHARACTER LARGE OBJECT(100)"
        },
        {
            new DataType.LobType(LobKind.CHARACTER, 100, CharUnits.OCTETS),
            "CHARACTER LARGE OBJECT",
            "CHARACTER LARGE OBJECT(100 OCTETS)"
        },
        {new DataType.LobType(LobKind.BINARY, 100, CharUnits.OCTETS), "BINARY LARGE OBJECT", "BINARY LARGE OBJECT(100)"
        },
        {
            new DataType.IntervalType(new Qualifier(IntervalField.DAY, IntervalField.SECOND, 2, 6)),
            "INTERVAL",
            "INTERVAL " + ValueText.qualifier(new Qualifier(IntervalField.DAY, IntervalField.SECOND, 2, 6))
        },
        {new DataType.ArrayType(3, INT), "ARRAY", "NUMERIC(10,0) ARRAY[3]"},
        {new DataType.MultisetType(new DataType.BooleanType()), "MULTISET", "BOOLEAN MULTISET"},
        {
            new DataType.RowType(
                    List.of(new DataType.Field("A", new DataType.BooleanType()), new DataType.Field("B", INT))),
            "ROW",
            "ROW(A BOOLEAN, B NUMERIC(10,0))"
        },
        {
            new DataType.XmlType(DataType.XmlPrimary.CONTENT, DataType.XmlSecondary.UNTYPED, null),
            "XML",
            "XML(CONTENT(UNTYPED))"
        },
        {new DataType.XmlType(DataType.XmlPrimary.SEQUENCE, DataType.XmlSecondary.UNTYPED, null), "XML", "XML(SEQUENCE)"
        },
        {
            new DataType.XmlType(
                    DataType.XmlPrimary.DOCUMENT,
                    DataType.XmlSecondary.XMLSCHEMA,
                    new DataType.XmlSchema("S", "X", "urn:a'b", "e\"f")),
            "XML",
            "XML(DOCUMENT(XMLSCHEMA ID S.X NAMESPACE 'urn:a''b' ELEMENT \"e\"\"f\"))"
        },
        {
            new DataType.XmlType(
                    DataType.XmlPrimary.DOCUMENT,
                    DataType.XmlSecondary.XMLSCHEMA,
                    new DataType.XmlSchema("S", "X", "", null)),
            "XML",
            "XML(DOCUMENT(XMLSCHEMA ID S.X NO NAMESPACE))"
        },
        {
            new DataType.XmlType(
                    DataType.XmlPrimary.DOCUMENT,
                    DataType.XmlSecondary.XMLSCHEMA,
                    new DataType.XmlSchema("S", "X", null, null)),
            "XML",
            "XML(DOCUMENT(XMLSCHEMA ID S.X))"
        },
        {new DataType.JsonType(), "JSON", "JSON"},
        {new DataType.UuidType(), "UUID", "UUID"},
        {POINT, "USER-DEFINED", "S.POINT"},
        {new DataType.ReferenceType(POINT, null), "REF", "REF(S.POINT)"},
        {new DataType.ReferenceType(POINT, new TypeName("S", "T")), "REF", "REF(S.POINT) SCOPE S.T"},
        {new DataType.PluginType("GEOMETRY", List.of()), "GEOMETRY", "GEOMETRY"},
    };

    /** A column named {@code name}, of an exact number of precision 10, nullable. */
    private static byte[] column(String name) throws TurborelException {
        return cat(u8(1), str(name), u8(2), u32(10), u32(0), u8(0));
    }

    /**
     * Verifies: [PROTOCOL Declared types] a Description ends with tail 19, the count of its columns,
     * then each one's name and type as SQL writes it, in the columns' order; one of no tail is the
     * columns alone, no declared type told; a count not of its columns is malformed.
     */
    @Test
    void aDescriptionCarriesItsDeclaredTypes() throws TurborelException {
        byte[] columns = cat(u32(2), column("A"), column("B"));
        byte[] tail =
                cat(u8(Tail.DECLARED), u32(2), str("INTEGER"), str("INTEGER"), str("DECIMAL"), str("DECIMAL(10,0)"));
        Reply.Description told = (Reply.Description) ReplyCodec.decode(Reply.DESCRIPTION, cat(columns, tail));
        assertEquals(new Declared("INTEGER", "INTEGER"), told.columns().get(0).declared());
        assertEquals("DECIMAL(10,0)", told.columns().get(1).typeWritten());
        assertEquals(INT, told.columns().get(0).type(), "the values' tag as ever");
        Reply.Description alone = (Reply.Description) ReplyCodec.decode(Reply.DESCRIPTION, columns);
        assertNull(alone.columns().get(0).declared());
        assertEquals("NUMERIC(10,0)", alone.columns().get(0).typeWritten(), "the type its tag says");
        byte[] short1 = cat(u8(Tail.DECLARED), u32(1), str("INTEGER"), str("INTEGER"));
        Fixture.assertBroken(() -> ReplyCodec.decode(Reply.DESCRIPTION, cat(columns, short1)));
        Fixture.assertBroken(() -> ReplyCodec.decode(Reply.DESCRIPTION, cat(columns, u8(Tail.GENERATED))));
    }

    /**
     * Verifies: [PROTOCOL Declared types] a Prepared's columns carry their declared types as a
     * Description's do; the page's table of them is read as it is written.
     */
    @Test
    void thePagesTableIsRead() throws TurborelException {
        String[][] table = {
            {"SMALLINT", "SMALLINT"},
            {"INTEGER", "INTEGER"},
            {"BIGINT", "BIGINT"},
            {"DECIMAL", "DECIMAL(9,2)"},
            {"NUMERIC", "NUMERIC(5,0)"},
            {"REAL", "REAL"},
            {"DOUBLE PRECISION", "DOUBLE PRECISION"},
            {"FLOAT", "FLOAT(24)"},
            {"USER-DEFINED", "S.MONEY"},
            {"ARRAY", "INTEGER ARRAY[3]"},
            {"TIME", "TIME(3)"}
        };
        List<byte[]> parts = new ArrayList<>(List.of(u32(7), u32(0), u32(table.length)));
        for (int i = 0; i < table.length; i++) {
            parts.add(column("C" + i));
        }
        parts.add(cat(u8(Tail.DECLARED), u32(table.length)));
        for (String[] row : table) {
            parts.add(cat(str(row[0]), str(row[1])));
        }
        Reply.Prepared prepared = (Reply.Prepared) ReplyCodec.decode(Reply.PREPARED, cat(parts.toArray(new byte[0][])));
        for (int i = 0; i < table.length; i++) {
            assertEquals(
                    new Declared(table[i][0], table[i][1]),
                    prepared.columns().get(i).declared());
        }
    }

    /**
     * Verifies: [PROTOCOL Declared types] a Description written with declared types reads back
     * with them, a column of none written as its type says; one of none at all is written with no
     * tail, byte for byte as before.
     */
    @Test
    void aWrittenDescriptionReadsBack() throws TurborelException {
        List<Column> told =
                List.of(new Column("A", INT, true, new Declared("INTEGER", "INTEGER")), new Column("B", INT, false));
        Reply.Description back =
                (Reply.Description) ReplyCodec.decode(Reply.DESCRIPTION, body(ReplyWriter.description(told)));
        assertEquals(new Declared("INTEGER", "INTEGER"), back.columns().get(0).declared());
        assertEquals(
                new Declared("NUMERIC", "NUMERIC(10,0)"), back.columns().get(1).declared());
        List<Column> none = List.of(new Column("A", INT, true));
        Reply.Description plain =
                (Reply.Description) ReplyCodec.decode(Reply.DESCRIPTION, body(ReplyWriter.description(none)));
        assertNull(plain.columns().get(0).declared());
        assertEquals(
                new Column("A", INT, true).declaredAs(new Declared("X", "Y")).declared(), new Declared("X", "Y"));
    }

    private static byte[] body(byte[] frame) {
        return java.util.Arrays.copyOfRange(frame, FrameHeader.BYTES, frame.length);
    }

    /**
     * Verifies: [CLIENT Prepared statements] a type is written as SQL writes it, and named as the
     * Information Schema names it, of the type its tag and parameters say, as Turborel's types
     * write themselves.
     */
    @Test
    void typesAreWrittenAsSqlWritesThem() {
        for (Object[] c : CASES) {
            DataType type = (DataType) c[0];
            assertEquals(c[1], TypeText.name(type), c[2].toString());
            assertEquals(c[2], TypeText.written(type));
            assertEquals(new Declared((String) c[1], (String) c[2]), Declared.of(type));
        }
    }
}
