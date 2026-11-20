package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.util.List;

/**
 * The frames of a query's replies as the server writes them ([PROTOCOL Description 'D'], [PROTOCOL
 * Rows 'B'], [PROTOCOL Complete 'K']), each type as {@link TypeCodec} reads it: so an export writes
 * native rows, the protocol's own frames, that read back every value as it was.
 */
public final class ReplyWriter {
    private ReplyWriter() {}

    /**
     * A Description of columns, their declared types in its tail if any has one.
     *
     * @param columns the columns
     * @return its frame
     * @throws TurborelException if a field is past what it holds
     */
    public static byte[] description(List<Column> columns) throws TurborelException {
        Writer w = new Writer().u32(columns.size());
        for (Column column : columns) {
            String name = column.name();
            w.flag(name != null);
            if (name != null) {
                w.string(name);
            }
            type(w, column.type());
            w.flag(column.notNull());
        }
        declared(w, columns);
        return Frames.frame(Reply.DESCRIPTION, w.toByteArray());
    }

    /**
     * Writes the declared type of each column, tail 19, if any column has one; one without is
     * written as its type says ([PROTOCOL Declared types]).
     */
    private static void declared(Writer w, List<Column> columns) throws TurborelException {
        if (columns.stream().allMatch(c -> c.declared() == null)) {
            return;
        }
        w.u8(Tail.DECLARED).u32(columns.size());
        for (Column column : columns) {
            Declared told = column.declared();
            Declared each = told == null ? Declared.of(column.type()) : told;
            w.string(each.name()).string(each.written());
        }
    }

    /**
     * A Rows reply of rows.
     *
     * @param rows the rows
     * @return its frame
     * @throws TurborelException if a value is past what its field holds
     */
    public static byte[] rows(List<List<Value>> rows) throws TurborelException {
        Writer w = new Writer().u32(rows.size());
        for (List<Value> row : rows) {
            ValueCodec.writeRow(w, row);
        }
        return Frames.frame(Reply.ROWS, w.toByteArray());
    }

    /**
     * A Complete of a code and state, no transaction active, its tail empty.
     *
     * @param sqlcode its SQLCODE
     * @param sqlstate its SQLSTATE
     * @return its frame
     * @throws TurborelException if the state is not Unicode
     */
    public static byte[] complete(int sqlcode, String sqlstate) throws TurborelException {
        return Frames.frame(
                Reply.COMPLETE,
                new Writer().bits32(sqlcode).string(sqlstate).flag(false).toByteArray());
    }

    /** Writes a data type as {@link TypeCodec#read} reads it. */
    static void type(Writer w, DataType type) throws TurborelException {
        if (type instanceof DataType.CharacterType c) {
            w.u8(c.varying() ? Tags.VARYING : Tags.CHARACTER)
                    .u32(c.length())
                    .u8(c.units().code());
        } else if (type instanceof DataType.ExactType e) {
            w.u8(Tags.EXACT).u32(e.precision()).u32(e.scale());
        } else if (type instanceof DataType.ApproximateType a) {
            w.u8(Tags.APPROXIMATE).u32(a.precision());
        } else if (type instanceof DataType.PluginType p) {
            w.u8(Tags.PLUGIN).string(p.name()).u32(p.parameters().size());
            for (long parameter : p.parameters()) {
                w.u32(parameter);
            }
        } else if (type instanceof DataType.BinaryType b) {
            w.u8(b.varying() ? Tags.BINARY_VARYING : Tags.BINARY).u32(b.length());
        } else if (type instanceof DataType.DatetimeType d) {
            w.u8(d.kind().tag()).u32(d.precision());
        } else if (type instanceof DataType.IntervalType i) {
            w.u8(Tags.INTERVAL);
            i.qualifier().encode(w);
        } else if (type instanceof DataType.BooleanType) {
            w.u8(Tags.BOOLEAN);
        } else if (type instanceof DataType.DecfloatType d) {
            w.u8(Tags.DECFLOAT).u32(d.precision());
        } else {
            constructed(w, type);
        }
    }

    private static void constructed(Writer w, DataType type) throws TurborelException {
        if (type instanceof DataType.ArrayType a) {
            w.u8(Tags.ARRAY).u32(a.maxCardinality());
            type(w, a.element());
        } else if (type instanceof DataType.RowType r) {
            w.u8(Tags.ROW).u32(r.fields().size());
            for (DataType.Field field : r.fields()) {
                w.string(field.name());
                type(w, field.type());
            }
        } else if (type instanceof DataType.LobType l) {
            w.u8(l.kind() == LobKind.CHARACTER ? Tags.CLOB : Tags.BLOB)
                    .u64(l.length())
                    .u8(l.units().code());
        } else if (type instanceof DataType.MultisetType m) {
            w.u8(Tags.MULTISET);
            type(w, m.element());
        } else if (type instanceof DataType.StructuredType s) {
            w.u8(Tags.STRUCTURED);
            structured(w, s);
        } else if (type instanceof DataType.ReferenceType r) {
            w.u8(Tags.REFERENCE);
            structured(w, r.referenced());
            TypeName scope = r.scope();
            w.flag(scope != null);
            if (scope != null) {
                name(w, scope);
            }
        } else if (type instanceof DataType.JsonType) {
            w.u8(Tags.JSON);
        } else if (type instanceof DataType.XmlType x) {
            xml(w, x);
        } else {
            w.u8(Tags.UUID);
        }
    }

    private static void structured(Writer w, DataType.StructuredType s) throws TurborelException {
        name(w, s.name());
        w.u32(s.supertypes().size());
        for (TypeName supertype : s.supertypes()) {
            name(w, supertype);
        }
    }

    private static void name(Writer w, TypeName name) throws TurborelException {
        w.string(name.schema()).string(name.name());
    }

    private static void xml(Writer w, DataType.XmlType x) throws TurborelException {
        w.u8(Tags.XML).u8(x.primary().code()).u8(x.secondary().code());
        DataType.XmlSchema schema = x.schema();
        if (schema != null) {
            w.string(schema.schema()).string(schema.name());
            named(w, schema.namespace());
            named(w, schema.element());
        }
    }

    private static void named(Writer w, @org.jspecify.annotations.Nullable String name) throws TurborelException {
        w.u8(name == null ? 0 : 1);
        if (name != null) {
            w.string(name);
        }
    }
}
