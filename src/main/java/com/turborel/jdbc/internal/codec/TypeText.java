package com.turborel.jdbc.internal.codec;

import java.util.ArrayList;
import java.util.List;

/**
 * A data type as SQL writes it, and its name as the Information Schema's DATA_TYPE gives it, of the
 * type its tag and parameters say ([PROTOCOL Declared types]), as Turborel's type kinds write them
 * ({@code src/value/types}): so an exact number is NUMERIC(10,0), whether declared INTEGER or not,
 * and an approximate one FLOAT of its precision.
 */
public final class TypeText {
    private TypeText() {}

    /**
     * {@code t} as SQL writes it.
     *
     * @param t the type
     * @return its text
     */
    public static String written(DataType t) {
        if (t instanceof DataType.CharacterType c) {
            return lengthOf(name(t), c.length(), c.units());
        } else if (t instanceof DataType.ExactType e) {
            return "NUMERIC(" + e.precision() + "," + e.scale() + ")";
        } else if (t instanceof DataType.ApproximateType a) {
            return "FLOAT(" + a.precision() + ")";
        } else if (t instanceof DataType.DecfloatType d) {
            return "DECFLOAT(" + d.precision() + ")";
        } else if (t instanceof DataType.DatetimeType d) {
            return datetime(d);
        } else if (t instanceof DataType.BinaryType b) {
            return name(t) + "(" + b.length() + ")";
        } else if (t instanceof DataType.LobType l) {
            return l.kind() == LobKind.CHARACTER
                    ? lengthOf(name(t), l.length(), l.units())
                    : name(t) + "(" + l.length() + ")";
        } else if (t instanceof DataType.IntervalType i) {
            return "INTERVAL " + ValueText.qualifier(i.qualifier());
        }
        return constructed(t);
    }

    private static String constructed(DataType t) {
        if (t instanceof DataType.ArrayType a) {
            return written(a.element()) + " ARRAY[" + a.maxCardinality() + "]";
        } else if (t instanceof DataType.MultisetType m) {
            return written(m.element()) + " MULTISET";
        } else if (t instanceof DataType.RowType r) {
            List<String> fields = new ArrayList<>();
            for (DataType.Field f : r.fields()) {
                fields.add(f.name() + " " + written(f.type()));
            }
            return "ROW(" + String.join(", ", fields) + ")";
        } else if (t instanceof DataType.XmlType x) {
            return xml(x);
        } else if (t instanceof DataType.StructuredType s) {
            return s.name().schema() + "." + s.name().name();
        } else if (t instanceof DataType.ReferenceType r) {
            TypeName to = r.referenced().name();
            String referenced = "REF(" + to.schema() + "." + to.name() + ")";
            TypeName scope = r.scope();
            return scope == null ? referenced : referenced + " SCOPE " + scope.schema() + "." + scope.name();
        }
        return name(t);
    }

    private static String lengthOf(String name, long length, CharUnits units) {
        return units == CharUnits.OCTETS ? name + "(" + length + " OCTETS)" : name + "(" + length + ")";
    }

    private static String datetime(DataType.DatetimeType d) {
        return switch (d.kind()) {
            case DATE -> "DATE";
            case TIME -> "TIME(" + d.precision() + ")";
            case TIME_ZONED -> "TIME(" + d.precision() + ") WITH TIME ZONE";
            case TIMESTAMP -> "TIMESTAMP(" + d.precision() + ")";
            case TIMESTAMP_ZONED -> "TIMESTAMP(" + d.precision() + ") WITH TIME ZONE";
        };
    }

    private static String xml(DataType.XmlType x) {
        DataType.XmlSchema schema = x.schema();
        if (schema != null) {
            StringBuilder according = new StringBuilder("ID " + schema.schema() + "." + schema.name());
            String namespace = schema.namespace();
            if (namespace != null) {
                according.append(
                        namespace.isEmpty() ? " NO NAMESPACE" : " NAMESPACE '" + namespace.replace("'", "''") + "'");
            }
            String element = schema.element();
            if (element != null) {
                according
                        .append(" ELEMENT \"")
                        .append(element.replace("\"", "\"\""))
                        .append('"');
            }
            return "XML(" + x.primary().name() + "(XMLSCHEMA " + according + "))";
        }
        if (x.primary() == DataType.XmlPrimary.SEQUENCE) {
            return "XML(SEQUENCE)";
        }
        return "XML(" + x.primary().name() + "(" + x.secondary().name() + "))";
    }

    /**
     * {@code t}'s name, as the Information Schema's DATA_TYPE gives it.
     *
     * @param t the type
     * @return its name
     */
    public static String name(DataType t) {
        if (t instanceof DataType.CharacterType c) {
            return c.varying() ? "CHARACTER VARYING" : "CHARACTER";
        } else if (t instanceof DataType.ExactType) {
            return "NUMERIC";
        } else if (t instanceof DataType.ApproximateType) {
            return "FLOAT";
        } else if (t instanceof DataType.DecfloatType) {
            return "DECFLOAT";
        } else if (t instanceof DataType.BooleanType) {
            return "BOOLEAN";
        } else if (t instanceof DataType.DatetimeType d) {
            return d.kind().name().replace("_ZONED", " WITH TIME ZONE");
        } else if (t instanceof DataType.BinaryType b) {
            return b.varying() ? "BINARY VARYING" : "BINARY";
        } else if (t instanceof DataType.LobType l) {
            return l.kind() == LobKind.CHARACTER ? "CHARACTER LARGE OBJECT" : "BINARY LARGE OBJECT";
        }
        return constructedName(t);
    }

    private static String constructedName(DataType t) {
        if (t instanceof DataType.IntervalType) {
            return "INTERVAL";
        } else if (t instanceof DataType.ArrayType) {
            return "ARRAY";
        } else if (t instanceof DataType.MultisetType) {
            return "MULTISET";
        } else if (t instanceof DataType.RowType) {
            return "ROW";
        } else if (t instanceof DataType.XmlType) {
            return "XML";
        } else if (t instanceof DataType.JsonType) {
            return "JSON";
        } else if (t instanceof DataType.UuidType) {
            return "UUID";
        } else if (t instanceof DataType.ReferenceType) {
            return "REF";
        } else if (t instanceof DataType.PluginType p) {
            return p.name();
        }
        return "USER-DEFINED";
    }
}
