package com.turborel.jdbc.internal.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.turborel.jdbc.TurborelException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The server's sample frames of each data type and value tag, decoded to what they mean. */
class SampleTypesTest {
    /** The instant 0001-01-01 00:00:00 that a datetime's microseconds count from. */
    private static final LocalDateTime EPOCH = LocalDateTime.of(1, 1, 1, 0, 0);

    private static final TypeName SHAPE = new TypeName("S", "SHAPE");

    private static final DataType.StructuredType SHAPES =
            new DataType.StructuredType(SHAPE, List.of(new TypeName("S", "FIGURE")));

    /**
     * Whether a server sample is of a data type, a Description of one column C.
     *
     * @param name the sample's name
     * @return whether it is one of a data type
     */
    static boolean isType(String name) {
        return name.startsWith("description-")
                && !name.equals("description-empty")
                && !name.equals("description-declared");
    }

    private static DataType chars(boolean varying, long length, CharUnits units) {
        return new DataType.CharacterType(varying, length, units);
    }

    private static DataType datetime(DatetimeKind kind, long precision) {
        return new DataType.DatetimeType(kind, precision);
    }

    /** The type of each type sample's column, by name. */
    private static Map<String, DataType> types() {
        Map<String, DataType> t = new LinkedHashMap<>();
        t.put("character", chars(false, 4, CharUnits.CHARACTERS));
        t.put("character-octets", chars(false, 16, CharUnits.OCTETS));
        t.put("exact", new DataType.ExactType(9, 2));
        t.put("approximate", new DataType.ApproximateType(53));
        t.put("plugin-type", new DataType.PluginType("ST_GEOMETRY", List.of()));
        t.put("varying", chars(true, 20, CharUnits.CHARACTERS));
        t.put("binary", new DataType.BinaryType(false, 16));
        t.put("binary-varying", new DataType.BinaryType(true, 255));
        t.put("date", datetime(DatetimeKind.DATE, 0));
        t.put("time", datetime(DatetimeKind.TIME, 3));
        t.put("time-zoned", datetime(DatetimeKind.TIME_ZONED, 0));
        t.put("timestamp", datetime(DatetimeKind.TIMESTAMP, 6));
        t.put("timestamp-zoned", datetime(DatetimeKind.TIMESTAMP_ZONED, 6));
        t.put(
                "interval-year-month",
                new DataType.IntervalType(new Qualifier(IntervalField.YEAR, IntervalField.MONTH, 4, 0)));
        t.put(
                "interval-day-second",
                new DataType.IntervalType(new Qualifier(IntervalField.DAY, IntervalField.SECOND, 2, 6)));
        t.put("boolean", new DataType.BooleanType());
        t.put("decfloat", new DataType.DecfloatType(34));
        t.put("clob", new DataType.LobType(LobKind.CHARACTER, 1_048_576, CharUnits.CHARACTERS));
        t.put("blob", new DataType.LobType(LobKind.BINARY, 65_536, CharUnits.OCTETS));
        t.put("json", new DataType.JsonType());
        t.put("uuid", new DataType.UuidType());
        t.put("array", new DataType.ArrayType(3, new DataType.BooleanType()));
        t.put(
                "row",
                new DataType.RowType(List.of(
                        new DataType.Field("X", chars(true, 3, CharUnits.CHARACTERS)),
                        new DataType.Field("Y", new DataType.BooleanType()))));
        t.put("multiset", new DataType.MultisetType(chars(false, 2, CharUnits.CHARACTERS)));
        t.put("structured", SHAPES);
        t.put("reference", new DataType.ReferenceType(SHAPES, null));
        t.put("reference-scoped", new DataType.ReferenceType(SHAPES, new TypeName("S", "SHAPES")));
        t.put("xml-document", new DataType.XmlType(DataType.XmlPrimary.DOCUMENT, DataType.XmlSecondary.UNTYPED, null));
        t.put("xml-content", new DataType.XmlType(DataType.XmlPrimary.CONTENT, DataType.XmlSecondary.ANY, null));
        t.put("xml-sequence", new DataType.XmlType(DataType.XmlPrimary.SEQUENCE, DataType.XmlSecondary.UNTYPED, null));
        t.put(
                "xml-schema",
                new DataType.XmlType(
                        DataType.XmlPrimary.DOCUMENT,
                        DataType.XmlSecondary.XMLSCHEMA,
                        new DataType.XmlSchema("S", "PO", "urn:po", null)));
        t.put("distinct", chars(true, 4, CharUnits.CHARACTERS));
        return t;
    }

    private static long micros(LocalDateTime at) {
        return ChronoUnit.MICROS.between(EPOCH, at);
    }

    private static Value moment(Value.Moment moment, boolean zoned, int precision, int displacement, long micros) {
        return new Value.Datetime(moment, zoned, precision, (short) displacement, micros);
    }

    private static Octets octets(String hex) {
        return Octets.of(Fixture.hex(hex));
    }

    private static Value whole(LobKind kind, byte[] octets) {
        return new Lob.Whole(kind, Octets.of(octets));
    }

    /** The value of each value sample's one row, by name. */
    private static Map<String, Value> values() throws TurborelException {
        Map<String, Value> v = new LinkedHashMap<>();
        v.put("null", Value.NULL);
        v.put("character", new Value.Text("abc"));
        v.put("character-empty", new Value.Text(""));
        v.put("character-utf8", new Value.Text("é€"));
        v.put("exact", new Value.Exact(BigInteger.valueOf(12_345), 2));
        v.put("exact-negative", new Value.Exact(BigInteger.ONE.negate(), 0));
        v.put("exact-largest", new Value.Exact(BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE), 0));
        v.put("approximate", new Value.Approximate(1.5));
        v.put("approximate-negative-zero", new Value.Approximate(-0.0));
        v.put(
                "plugin",
                new OctetValue.Plugin(
                        "ST_GEOMETRY",
                        "POINT (1 2)",
                        octets("1A00000001E61000000101000000000000000000F03F0000000000000040")));
        v.put("binary", new OctetValue.Binary(octets("DEADBEEF")));
        v.put("binary-empty", new OctetValue.Binary(Octets.EMPTY));
        v.put("date", moment(Value.Moment.DATE, false, 0, 0, micros(LocalDateTime.of(2024, 2, 29, 0, 0))));
        v.put("time", moment(Value.Moment.TIME, false, 1, 0, 45_296_500_000L));
        v.put("time-zoned", moment(Value.Moment.TIME, true, 0, 330, 25_496_000_000L));
        v.put(
                "timestamp",
                moment(
                        Value.Moment.TIMESTAMP,
                        false,
                        6,
                        0,
                        micros(LocalDateTime.of(2024, 2, 29, 12, 34, 56, 123_456_000))));
        v.put(
                "timestamp-zoned",
                moment(Value.Moment.TIMESTAMP, true, 0, -480, micros(LocalDateTime.of(2024, 2, 29, 20, 34, 56))));
        v.put("timestamp-earliest", moment(Value.Moment.TIMESTAMP, false, 0, 0, 0));
        v.put(
                "interval-year-month",
                new Value.Interval(
                        new Qualifier(IntervalField.YEAR, IntervalField.MONTH, 2, 0), BigInteger.valueOf(-14)));
        v.put(
                "interval-day-second",
                new Value.Interval(
                        new Qualifier(IntervalField.DAY, IntervalField.SECOND, 2, 6),
                        BigInteger.valueOf(93_784_500_000L)));
        v.putAll(constructed());
        return v;
    }

    /** The values of tag 14 and later, by name. */
    private static Map<String, Value> constructed() throws TurborelException {
        Map<String, Value> v = new LinkedHashMap<>();
        v.put("true", new Value.Truth(true));
        v.put("false", new Value.Truth(false));
        v.put("decfloat", new Value.Decfloat(BigInteger.valueOf(-15), -1));
        v.put("array", new Value.Array(List.of(new Value.Exact(BigInteger.ONE, 0), Value.NULL)));
        v.put("array-empty", new Value.Array(List.of()));
        v.put("row", new Value.Row(List.of(new Value.Text("x"), new Value.Truth(true))));
        v.put("lob-character", whole(LobKind.CHARACTER, "aé".getBytes(StandardCharsets.UTF_8)));
        v.put("lob-binary", whole(LobKind.BINARY, new byte[] {0, (byte) 0xFF}));
        v.put("lob-locator", new Lob.Locator(LobKind.CHARACTER, 7, 300_000, 200_000));
        v.put("multiset", new Value.Multiset(List.of(new Value.Text("a"), new Value.Text("a"))));
        v.put(
                "structured",
                new Value.Structured(SHAPE, List.of(new Value.Exact(BigInteger.valueOf(3), 0), Value.NULL)));
        v.put("reference", new OctetValue.Reference(octets("0102030405060708")));
        v.put("json", OctetValue.Json.ofText("{\"a\":[1,\"x\"]}"));
        v.put("json-scalars", OctetValue.Json.ofText("[null,true,false,-2,1.50,\"\",{}]"));
        v.put("xml", new Value.Xml("<a>&lt;</a>"));
        v.put("uuid", new Value.Uuid(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")));
        return v;
    }

    /**
     * Verifies: [PROTOCOL Sample frames] every data type's sample, a Description of one column C,
     * decodes to that type: each tag 1 to 27, a distinct type as its source, and of the column
     * whose declared type is told, tail 19, its declared type; none is left unchecked.
     */
    @Test
    void everyDataTypeDecodesAsItsMeaningSays() throws TurborelException {
        Map<String, DataType> expected = types();
        Map<String, SampleFrames.Sample> samples = SampleFrames.of("server", "description-");
        samples.keySet().removeIf(name -> !isType(name));
        samples.remove("description-declared-array");
        TreeSet<String> named = new TreeSet<>();
        expected.keySet().forEach(name -> named.add("description-" + name));
        assertEquals(samples.keySet(), named, "an expectation for each sample");
        for (Map.Entry<String, DataType> each : expected.entrySet()) {
            SampleFrames.Sample sample = SampleFrames.in(samples, "description-" + each.getKey());
            Reply want = new Reply.Description(List.of(new Column("C", each.getValue(), false)));
            assertEquals(want, ReplyCodec.decode(sample.kind(), sample.body()), each.getKey());
        }
        SampleFrames.Sample array = SampleFrames.named("description-declared-array");
        Column told = new Column(
                "C",
                new DataType.ArrayType(3, new DataType.ExactType(10, 0)),
                false,
                new Declared("ARRAY", "INTEGER ARRAY[3]"));
        assertEquals(new Reply.Description(List.of(told)), ReplyCodec.decode(array.kind(), array.body()));
    }

    /**
     * Verifies: [PROTOCOL Sample frames] every value tag's sample, Rows of one row of one value,
     * decodes to that value: each tag 0 to 27 a value has, and the edges, the empty string, the
     * largest units, -0, the earliest instant, the empty array; none is left unchecked.
     */
    @Test
    void everyValueDecodesAsItsMeaningSays() throws TurborelException {
        Map<String, Value> expected = values();
        Map<String, SampleFrames.Sample> samples = SampleFrames.of("server", "value-");
        TreeSet<String> named = new TreeSet<>();
        expected.keySet().forEach(name -> named.add("value-" + name));
        assertEquals(samples.keySet(), named, "an expectation for each sample");
        for (Map.Entry<String, Value> each : expected.entrySet()) {
            SampleFrames.Sample sample = SampleFrames.in(samples, "value-" + each.getKey());
            Reply want = new Reply.Rows(List.of(List.of(each.getValue())));
            assertEquals(want, ReplyCodec.decode(sample.kind(), sample.body()), each.getKey());
        }
    }
}
