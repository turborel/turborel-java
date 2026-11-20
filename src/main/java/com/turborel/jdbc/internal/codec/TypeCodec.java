package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.DataType.XmlPrimary;
import com.turborel.jdbc.internal.codec.DataType.XmlSecondary;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Reads data types: a tag, then what its kind writes after it ([PROTOCOL Data types]). The client
 * reads types, of columns and parameters, and never sends one.
 */
public final class TypeCodec {
    /** The greatest maximum cardinality of an array type, turborel's {@code MAX_ARRAY_CARDINALITY}. */
    public static final long MAX_ARRAY_CARDINALITY = 65_535;

    /** The byte of an XML type's namespace or element that is not named. */
    private static final int ABSENT = 0;

    /** The byte of an XML type's namespace or element that is named, its string after it. */
    private static final int PRESENT = 1;

    private TypeCodec() {}

    /**
     * A data type read.
     *
     * @param r where it is read
     * @return the type
     * @throws TurborelException if it is malformed: a tag of no type, a parameter out of range,
     *     types nested too deep, or the body cut short
     */
    public static DataType read(Reader r) throws TurborelException {
        int tag = r.u8();
        return switch (tag) {
            case Tags.CHARACTER, Tags.VARYING ->
                new DataType.CharacterType(tag == Tags.VARYING, r.u32(), CharUnits.read(r));
            case Tags.EXACT -> new DataType.ExactType(r.u32(), r.u32());
            case Tags.APPROXIMATE -> new DataType.ApproximateType(r.u32());
            case Tags.PLUGIN -> new DataType.PluginType(r.string(), r.list(Reader::u32));
            case Tags.BINARY, Tags.BINARY_VARYING -> new DataType.BinaryType(tag == Tags.BINARY_VARYING, r.u32());
            case Tags.DATE, Tags.TIME, Tags.TIME_ZONED, Tags.TIMESTAMP, Tags.TIMESTAMP_ZONED ->
                new DataType.DatetimeType(datetimeKind(tag, r), r.u32());
            case Tags.INTERVAL -> new DataType.IntervalType(Qualifier.read(r));
            case Tags.BOOLEAN -> new DataType.BooleanType();
            case Tags.DECFLOAT -> new DataType.DecfloatType(r.u32());
            default -> constructed(tag, r);
        };
    }

    /** The types of tags 16 and later: constructed, large object, user-defined and the rest. */
    private static DataType constructed(int tag, Reader r) throws TurborelException {
        return switch (tag) {
            case Tags.ARRAY -> r.nested(TypeCodec::array);
            case Tags.ROW -> r.nested(TypeCodec::row);
            case Tags.CLOB, Tags.BLOB -> {
                LobKind kind = tag == Tags.CLOB ? LobKind.CHARACTER : LobKind.BINARY;
                yield new DataType.LobType(kind, r.u64(), CharUnits.read(r));
            }
            case Tags.MULTISET -> r.nested(nested -> new DataType.MultisetType(read(nested)));
            case Tags.STRUCTURED -> structured(r);
            case Tags.REFERENCE -> new DataType.ReferenceType(structured(r), scope(r));
            case Tags.JSON -> new DataType.JsonType();
            case Tags.XML -> xml(r);
            case Tags.UUID -> new DataType.UuidType();
            default -> throw r.malformed(Cite.TYPES, "no data type has tag " + tag);
        };
    }

    private static DatetimeKind datetimeKind(int tag, Reader r) throws TurborelException {
        for (DatetimeKind each : DatetimeKind.values()) {
            if (each.tag() == tag) {
                return each;
            }
        }
        throw r.malformed(Cite.TYPES, "no datetime type has tag " + tag);
    }

    private static DataType array(Reader r) throws TurborelException {
        long max = r.u32();
        if (max < 1 || max > MAX_ARRAY_CARDINALITY) {
            throw r.malformed(Cite.TYPES, "an array type's maximum cardinality " + max + " is out of range");
        }
        return new DataType.ArrayType(max, read(r));
    }

    private static DataType row(Reader r) throws TurborelException {
        List<DataType.Field> fields = r.list(f -> new DataType.Field(f.string(), read(f)));
        if (fields.isEmpty()) {
            throw r.malformed(Cite.TYPES, "a row type has no fields");
        }
        return new DataType.RowType(fields);
    }

    private static DataType.StructuredType structured(Reader r) throws TurborelException {
        return new DataType.StructuredType(TypeName.read(r), r.list(TypeName::read));
    }

    private static @Nullable TypeName scope(Reader r) throws TurborelException {
        return r.flag() ? TypeName.read(r) : null;
    }

    private static DataType xml(Reader r) throws TurborelException {
        int primary = r.u8();
        int secondary = r.u8();
        XmlPrimary p = XmlPrimary.of(primary);
        XmlSecondary s = XmlSecondary.of(secondary);
        if (p == null) {
            throw r.malformed(Cite.TYPES, "an XML type's primary modifier is " + primary);
        }
        if (s == null) {
            throw r.malformed(Cite.TYPES, "an XML type's secondary modifier is " + secondary);
        }
        if (p == XmlPrimary.SEQUENCE && s != XmlSecondary.UNTYPED) {
            throw r.malformed(Cite.TYPES, "an XML type of SEQUENCE is UNTYPED");
        }
        DataType.XmlSchema schema = s == XmlSecondary.XMLSCHEMA ? schema(r) : null;
        return new DataType.XmlType(p, s, schema);
    }

    private static DataType.XmlSchema schema(Reader r) throws TurborelException {
        String schema = r.string();
        String name = r.string();
        String namespace = named(r);
        return new DataType.XmlSchema(schema, name, namespace, named(r));
    }

    private static @Nullable String named(Reader r) throws TurborelException {
        int marked = r.u8();
        if (marked == ABSENT) {
            return null;
        }
        if (marked != PRESENT) {
            throw r.malformed(Cite.TYPES, "an XML type's namespace or element is marked " + marked);
        }
        return r.string();
    }
}
