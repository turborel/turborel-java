package com.turborel.jdbc.internal.codec;

/**
 * The tags of data types and of values, as the tables of [PROTOCOL Data types] and [PROTOCOL
 * Values] number them. A value's tag says how it is written, not always its column type's tag: a
 * CHARACTER VARYING value is tag 1, as a CHARACTER value is.
 */
final class Tags {
    // [PROTOCOL Values] alone.
    /** The null value. */
    static final int NULL = 0;

    // [PROTOCOL Data types] and [PROTOCOL Values].
    /** CHARACTER; a character string. */
    static final int CHARACTER = 1;
    /** An exact numeric type; an exact number. */
    static final int EXACT = 2;
    /** An approximate numeric type; an approximate number. */
    static final int APPROXIMATE = 3;
    /** A plug-in's type; a plug-in's value. */
    static final int PLUGIN = 4;
    /** CHARACTER VARYING, a type alone. */
    static final int VARYING = 5;
    /** BINARY; a binary string of either binary type. */
    static final int BINARY = 6;
    /** BINARY VARYING, a type alone. */
    static final int BINARY_VARYING = 7;
    /** DATE; a date. */
    static final int DATE = 8;
    /** TIME; a time of either time type. */
    static final int TIME = 9;
    /** TIME WITH TIME ZONE, a type alone. */
    static final int TIME_ZONED = 10;
    /** TIMESTAMP; a timestamp of either timestamp type. */
    static final int TIMESTAMP = 11;
    /** TIMESTAMP WITH TIME ZONE, a type alone. */
    static final int TIMESTAMP_ZONED = 12;
    /** INTERVAL; an interval. */
    static final int INTERVAL = 13;
    /** BOOLEAN; a truth value. */
    static final int BOOLEAN = 14;
    /** DECFLOAT; a decimal floating point number. */
    static final int DECFLOAT = 15;
    /** An array type; an array. */
    static final int ARRAY = 16;
    /** A row type; a row. */
    static final int ROW = 17;
    /** CHARACTER LARGE OBJECT, a type alone. */
    static final int CLOB = 18;
    /** BINARY LARGE OBJECT, a type alone. */
    static final int BLOB = 19;
    /** A large object whole, a value alone. */
    static final int LOB_WHOLE = 20;
    /** A large object by locator, a value alone. */
    static final int LOB_LOCATOR = 21;
    /** A multiset type; a multiset. */
    static final int MULTISET = 22;
    /** A structured type; a structured value. */
    static final int STRUCTURED = 23;
    /** A reference type; a reference. */
    static final int REFERENCE = 24;
    /** JSON; a JSON value. */
    static final int JSON = 25;
    /** XML; an XML value. */
    static final int XML = 26;
    /** UUID; a UUID (turborel's RULES.md D184; not yet on the page). */
    static final int UUID = 27;

    private Tags() {}
}
