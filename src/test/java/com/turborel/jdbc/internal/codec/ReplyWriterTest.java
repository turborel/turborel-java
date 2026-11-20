package com.turborel.jdbc.internal.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.turborel.jdbc.TurborelException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Replies written as the server writes them, and the bytes a row takes. */
class ReplyWriterTest {
    private static Reply decoded(byte[] frame) throws TurborelException {
        FrameHeader header = FrameHeader.decode(Arrays.copyOf(frame, FrameHeader.BYTES), FrameHeader.MAX_BODY);
        return ReplyCodec.decode(header.kind(), Arrays.copyOfRange(frame, FrameHeader.BYTES, frame.length));
    }

    /**
     * Verifies: [PROTOCOL Description 'D'] a Description written of every data type reads back as it
     * was, each type as the codec reads it ([PROTOCOL Data types]), so native rows exported read back.
     */
    @Test
    void everyTypeReadsBack() throws TurborelException {
        TypeName name = new TypeName("S", "T");
        DataType.StructuredType structured = new DataType.StructuredType(name, List.of(new TypeName("S", "U")));
        List<DataType> types = List.of(
                new DataType.CharacterType(false, 5, CharUnits.CHARACTERS),
                new DataType.CharacterType(true, 20, CharUnits.OCTETS),
                new DataType.ExactType(8, 2),
                new DataType.ApproximateType(53),
                new DataType.PluginType("POINT", List.of(2L, 3L)),
                new DataType.BinaryType(false, 4),
                new DataType.BinaryType(true, 9),
                new DataType.DatetimeType(DatetimeKind.TIMESTAMP_ZONED, 6),
                new DataType.IntervalType(new Qualifier(IntervalField.DAY, IntervalField.SECOND, 2, 3)),
                new DataType.BooleanType(),
                new DataType.DecfloatType(34),
                new DataType.ArrayType(10, new DataType.BooleanType()),
                new DataType.RowType(List.of(new DataType.Field("A", new DataType.DecfloatType(16)))),
                new DataType.LobType(LobKind.CHARACTER, 1 << 20, CharUnits.CHARACTERS),
                new DataType.LobType(LobKind.BINARY, 99, CharUnits.OCTETS),
                new DataType.MultisetType(new DataType.ExactType(10, 0)),
                structured,
                new DataType.ReferenceType(structured, name),
                new DataType.ReferenceType(structured, null),
                new DataType.JsonType(),
                new DataType.XmlType(DataType.XmlPrimary.CONTENT, DataType.XmlSecondary.ANY, null),
                new DataType.XmlType(
                        DataType.XmlPrimary.DOCUMENT,
                        DataType.XmlSecondary.XMLSCHEMA,
                        new DataType.XmlSchema("S", "X", "urn:x", null)),
                new DataType.XmlType(
                        DataType.XmlPrimary.DOCUMENT,
                        DataType.XmlSecondary.XMLSCHEMA,
                        new DataType.XmlSchema("S", "X", null, "order")),
                new DataType.UuidType());
        List<Column> columns = new java.util.ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            columns.add(new Column(i % 2 == 0 ? "C" + i : null, types.get(i), i % 3 == 0));
        }
        assertEquals(new Reply.Description(columns), decoded(ReplyWriter.description(columns)));
    }

    /**
     * Verifies: [PROTOCOL Rows 'B'] rows written read back as they were; [PROTOCOL Complete 'K'] a
     * Complete written of a code and a state, no transaction active, its tail empty.
     */
    @Test
    void rowsAndACompletion() throws TurborelException {
        List<List<Value>> rows = List.of(
                List.of(new Value.Exact(BigInteger.ONE, 0), new Value.Text("a")), List.of(Value.NULL, Value.NULL));
        assertEquals(new Reply.Rows(rows), decoded(ReplyWriter.rows(rows)));
        Reply.Complete complete = (Reply.Complete) decoded(ReplyWriter.complete(0, "00000"));
        assertEquals(new Completed(0, "00000", false, Completed.Fields.EMPTY), complete.completed());
    }

    /**
     * Verifies: [PROTOCOL Rows 'B'] the bytes a row takes, as Turborel's {@code row_size} reckons
     * them: its count, then each value's tag and body, a string's UTF-8 counted by its octets.
     */
    @Test
    void theBytesARowTakes() throws TurborelException {
        assertEquals(4, RowSize.of(List.of()));
        assertEquals(
                4 + 1 + 21 + 21 + 9 + 2,
                RowSize.of(List.of(
                        Value.NULL,
                        new Value.Exact(BigInteger.ONE, 0),
                        new Value.Decfloat(BigInteger.ONE, 0),
                        new Value.Approximate(1),
                        new Value.Truth(true))));
        String text = "aé€" + new String(Character.toChars(0x1F600));
        assertEquals(4 + 10 + 5, RowSize.of(List.of(new Value.Text(text))));
        assertEquals(
                4 + 3 + 5 + 3 + 6 + 22 + 15 + 33 + 2 + 5,
                RowSize.of(List.of(
                        new OctetValue.Binary(Octets.of(new byte[3])),
                        Lob.Whole.ofText("abc"),
                        new Lob.Locator(LobKind.BINARY, 1, 2, 2),
                        new Value.Datetime(Value.Moment.DATE, false, 0, (short) 0, 0),
                        new Value.Interval(new Qualifier(IntervalField.YEAR, IntervalField.YEAR, 2, 0), BigInteger.ONE),
                        new OctetValue.Reference(Octets.of(new byte[2])))));
        OctetValue.Json json = OctetValue.Json.ofText("1");
        assertEquals(4 + json.stored().length + 5, RowSize.of(List.of(json)));
        Value xml = new Value.Xml("<a/>");
        Writer w = new Writer();
        xml.encode(w);
        assertEquals(4 + w.toByteArray().length, RowSize.of(List.of(xml)));
    }
}
