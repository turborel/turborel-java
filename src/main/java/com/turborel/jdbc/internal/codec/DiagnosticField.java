package com.turborel.jdbc.internal.codec;

import org.jspecify.annotations.Nullable;

/**
 * What a failure or a notice says beyond its SQLSTATE and message, each field a string under its
 * tail tag, 7 to 15 ([PROTOCOL The tail], [PROTOCOL Errors and notices]).
 */
public enum DiagnosticField {
    /** Tag 7: ERROR, FATAL, WARNING or NOTICE. */
    SEVERITY(7),
    /** Tag 8: more than the message says, as the key a constraint refused. */
    DETAIL(8),
    /** Tag 9: what may be done about it. */
    HINT(9),
    /** Tag 10: the trigger or routine running, as {@code trigger S.T}. */
    CONTEXT(10),
    /** Tag 11: the schema of what it is about. */
    SCHEMA(11),
    /** Tag 12: the table or view it is about. */
    TABLE(12),
    /** Tag 13: the column it is about. */
    COLUMN(13),
    /** Tag 14: the data type of the value it is about. */
    DATA_TYPE(14),
    /** Tag 15: the constraint broken. */
    CONSTRAINT(15);

    private final int tag;

    DiagnosticField(int tag) {
        this.tag = tag;
    }

    /**
     * Its tail tag.
     *
     * @return 7 to 15
     */
    public int tag() {
        return tag;
    }

    /**
     * The field of a tail tag.
     *
     * @param tag the tag, 7 to 15
     * @return the field, or none of another tag
     */
    static @Nullable DiagnosticField of(int tag) {
        for (DiagnosticField each : values()) {
            if (each.tag == tag) {
                return each;
            }
        }
        return null;
    }
}
