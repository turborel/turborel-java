package com.turborel.jdbc.internal.codec;

/**
 * The type a column was declared with ([PROTOCOL Declared types]), beside the tag its values are
 * written by, told granted {@code declared-types}: SMALLINT, INTEGER, BIGINT, DECIMAL and NUMERIC
 * are all one tag, so are REAL, DOUBLE PRECISION and FLOAT, and a distinct type is its source's.
 *
 * @param name its name, as the Information Schema's DATA_TYPE gives it: INTEGER, DECIMAL,
 *     USER-DEFINED of a distinct type, or the name of any other type
 * @param written the whole type as SQL writes it: INTEGER, DECIMAL(9,2), a distinct type's schema
 *     and name, or INTEGER ARRAY[3]
 */
public record Declared(String name, String written) {
    /**
     * The declared type of a column of {@code type} whose declaration was not told: its type as its
     * tag and parameters say, as Turborel's writer gives a column without one.
     *
     * @param type the column's type
     * @return its declared type
     */
    public static Declared of(DataType type) {
        return new Declared(TypeText.name(type), TypeText.written(type));
    }
}
