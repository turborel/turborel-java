package com.turborel.jdbc.internal.codec;

import java.util.List;

/**
 * An SQL/JSON item, what a JSON value holds: the SQL/JSON null, a scalar, an array or an object
 * (turborel's RULES.md D89). {@link JsonBinary} reads one from a value's stored form, {@link
 * JsonBinaryWriter} writes one, and {@link JsonText} gives its JSON text.
 */
public sealed interface JsonItem {
    /** The SQL/JSON null. */
    JsonItem NULL = new NullItem();

    /** The SQL/JSON null, node 0. */
    record NullItem() implements JsonItem {}

    /**
     * An SQL/JSON scalar: a character string ({@link Value.Text}), an exact number ({@link
     * Value.Exact}), a decimal floating point number ({@link Value.Decfloat}), an approximate
     * number ({@link Value.Approximate}), a truth value ({@link Value.Truth}) or a datetime
     * ({@link Value.Datetime}). {@link JsonBinaryWriter} refuses one of another kind.
     *
     * @param value the scalar's value
     */
    record ScalarItem(Value value) implements JsonItem {}

    /**
     * An SQL/JSON array, node 10.
     *
     * @param elements its elements, in order
     */
    record ArrayItem(List<JsonItem> elements) implements JsonItem {
        /**
         * An array of the elements given.
         *
         * @param elements its elements, copied
         */
        public ArrayItem {
            elements = List.copyOf(elements);
        }
    }

    /**
     * An SQL/JSON object, node 11: its members in the order written, those of one key all.
     *
     * @param members its members, in order
     */
    record ObjectItem(List<Member> members) implements JsonItem {
        /**
         * An object of the members given.
         *
         * @param members its members, copied
         */
        public ObjectItem {
            members = List.copyOf(members);
        }
    }

    /**
     * A member of an object.
     *
     * @param key its key
     * @param value its value
     */
    record Member(String key, JsonItem value) {}
}
