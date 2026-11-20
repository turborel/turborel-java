package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;

/**
 * A field of an interval's qualifier, numbered as the protocol numbers them: 0 YEAR, 1 MONTH, 2
 * DAY, 3 HOUR, 4 MINUTE and 5 SECOND ([PROTOCOL Data types]).
 */
public enum IntervalField {
    /** YEAR, 0. */
    YEAR(0),
    /** MONTH, 1. */
    MONTH(1),
    /** DAY, 2. */
    DAY(2),
    /** HOUR, 3. */
    HOUR(3),
    /** MINUTE, 4. */
    MINUTE(4),
    /** SECOND, 5. */
    SECOND(5);

    private final int number;

    IntervalField(int number) {
        this.number = number;
    }

    /**
     * Its number.
     *
     * @return 0 to 5
     */
    public int number() {
        return number;
    }

    /**
     * The field of a number read.
     *
     * @param r where the number is read, a {@code u32}
     * @return the field
     * @throws TurborelException if no field has that number
     */
    static IntervalField read(Reader r) throws TurborelException {
        long read = r.u32();
        for (IntervalField each : values()) {
            if (each.number == read) {
                return each;
            }
        }
        throw r.malformed(Cite.TYPES, "an interval's field is numbered " + read + ", past SECOND's 5");
    }
}
