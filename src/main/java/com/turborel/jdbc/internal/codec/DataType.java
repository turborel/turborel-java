package com.turborel.jdbc.internal.codec;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A data type, as the server writes a column's, a parameter's, or a type inside another: a tag,
 * then what its kind writes after it ([PROTOCOL Data types]). A distinct type comes as its source
 * type.
 *
 * <p>Each {@code u32} the page lists is kept in a {@code long}, 0 to 2^32 - 1, and each {@code
 * u64} in a {@code long}'s bits. {@link TypeCodec} reads them.
 */
public sealed interface DataType {
    /**
     * CHARACTER, tag 1, or CHARACTER VARYING, tag 5.
     *
     * @param varying whether it is CHARACTER VARYING
     * @param length its length, a {@code u32}
     * @param units what its length counts
     */
    record CharacterType(boolean varying, long length, CharUnits units) implements DataType {}

    /**
     * An exact numeric type, tag 2.
     *
     * @param precision its precision, a {@code u32}
     * @param scale its scale, a {@code u32}
     */
    record ExactType(long precision, long scale) implements DataType {}

    /**
     * An approximate numeric type, tag 3.
     *
     * @param precision its precision, a {@code u32}
     */
    record ApproximateType(long precision) implements DataType {}

    /**
     * A plug-in's type, tag 4: a client that does not have it reads it by its name, and shows its
     * values by their text.
     *
     * @param name its name
     * @param parameters its parameters, each a {@code u32}
     */
    record PluginType(String name, List<Long> parameters) implements DataType {
        /**
         * A plug-in's type of the name and parameters given.
         *
         * @param name its name
         * @param parameters its parameters, copied
         */
        public PluginType {
            parameters = List.copyOf(parameters);
        }
    }

    /**
     * BINARY, tag 6, or BINARY VARYING, tag 7.
     *
     * @param varying whether it is BINARY VARYING
     * @param length its length, a {@code u32}
     */
    record BinaryType(boolean varying, long length) implements DataType {}

    /**
     * DATE, TIME, TIME WITH TIME ZONE, TIMESTAMP or TIMESTAMP WITH TIME ZONE, tags 8 to 12.
     *
     * @param kind which of them
     * @param precision its precision, a {@code u32}
     */
    record DatetimeType(DatetimeKind kind, long precision) implements DataType {}

    /**
     * INTERVAL, tag 13.
     *
     * @param qualifier its fields and precisions
     */
    record IntervalType(Qualifier qualifier) implements DataType {}

    /** BOOLEAN, tag 14. */
    record BooleanType() implements DataType {}

    /**
     * DECFLOAT, tag 15.
     *
     * @param precision its precision, a {@code u32}
     */
    record DecfloatType(long precision) implements DataType {}

    /**
     * An array type, tag 16.
     *
     * @param maxCardinality its maximum cardinality, 1 to 65535
     * @param element its element type
     */
    record ArrayType(long maxCardinality, DataType element) implements DataType {}

    /**
     * A row type, tag 17: one field or more.
     *
     * @param fields its fields, in order
     */
    record RowType(List<Field> fields) implements DataType {
        /**
         * A row type of the fields given.
         *
         * @param fields its fields, copied
         */
        public RowType {
            fields = List.copyOf(fields);
        }
    }

    /**
     * A field of a row type.
     *
     * @param name its name
     * @param type its type
     */
    record Field(String name, DataType type) {}

    /**
     * CHARACTER LARGE OBJECT, tag 18, or BINARY LARGE OBJECT, tag 19.
     *
     * @param kind which of them
     * @param length its length, a {@code u64}'s bits
     * @param units what its length counts
     */
    record LobType(LobKind kind, long length, CharUnits units) implements DataType {}

    /**
     * A multiset type, tag 22.
     *
     * @param element its element type
     */
    record MultisetType(DataType element) implements DataType {}

    /**
     * A structured type, tag 23.
     *
     * @param name its schema's name and its own
     * @param supertypes its proper supertypes' names
     */
    record StructuredType(TypeName name, List<TypeName> supertypes) implements DataType {
        /**
         * A structured type of the names given.
         *
         * @param name its name
         * @param supertypes its proper supertypes' names, copied
         */
        public StructuredType {
            supertypes = List.copyOf(supertypes);
        }
    }

    /**
     * A reference type, tag 24.
     *
     * @param referenced the type it references, its names as a structured type's
     * @param scope the table that scopes it, if it is scoped
     */
    record ReferenceType(
            StructuredType referenced, @Nullable TypeName scope) implements DataType {}

    /** JSON, tag 25. */
    record JsonType() implements DataType {}

    /**
     * XML, tag 26.
     *
     * @param primary its primary modifier
     * @param secondary its secondary modifier
     * @param schema the XML Schema of a type of XMLSCHEMA, none otherwise
     */
    record XmlType(
            XmlPrimary primary,
            XmlSecondary secondary,
            @Nullable XmlSchema schema) implements DataType {}

    /**
     * What a type of XMLSCHEMA names: its registered XML Schema's schema and name, then its
     * namespace and its element, each if given.
     *
     * @param schema the schema of the registered XML Schema
     * @param name the registered XML Schema's name
     * @param namespace the namespace, if one is named
     * @param element the element, if one is named
     */
    record XmlSchema(
            String schema,
            String name,
            @Nullable String namespace,
            @Nullable String element) {}

    /** UUID, tag 27, with no parameters (turborel's RULES.md D184). */
    record UuidType() implements DataType {}

    /** An XML type's primary modifier. */
    enum XmlPrimary {
        /** DOCUMENT, 0. */
        DOCUMENT(0),
        /** CONTENT, 1. */
        CONTENT(1),
        /** SEQUENCE, 2. */
        SEQUENCE(2);

        private final int code;

        XmlPrimary(int code) {
            this.code = code;
        }

        /**
         * Its byte.
         *
         * @return the byte, as the type writes it
         */
        public int code() {
            return code;
        }

        /**
         * The modifier of a byte.
         *
         * @param code the byte
         * @return the modifier, or none of another byte
         */
        static @Nullable XmlPrimary of(int code) {
            for (XmlPrimary each : values()) {
                if (each.code == code) {
                    return each;
                }
            }
            return null;
        }
    }

    /** An XML type's secondary modifier. */
    enum XmlSecondary {
        /** UNTYPED, 0. */
        UNTYPED(0),
        /** ANY, 1. */
        ANY(1),
        /** XMLSCHEMA, 2. */
        XMLSCHEMA(2);

        private final int code;

        XmlSecondary(int code) {
            this.code = code;
        }

        /**
         * Its byte.
         *
         * @return the byte, as the type writes it
         */
        public int code() {
            return code;
        }

        /**
         * The modifier of a byte.
         *
         * @param code the byte
         * @return the modifier, or none of another byte
         */
        static @Nullable XmlSecondary of(int code) {
            for (XmlSecondary each : values()) {
                if (each.code == code) {
                    return each;
                }
            }
            return null;
        }
    }
}
