package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;

/**
 * What a character string's or a CLOB's length counts, as its type's {@code units} byte says
 * ([PROTOCOL Data types]).
 */
public enum CharUnits {
    /** Characters, byte 0. */
    CHARACTERS(0),
    /** Octets, byte 1. */
    OCTETS(1);

    private final int code;

    CharUnits(int code) {
        this.code = code;
    }

    /**
     * Its byte.
     *
     * @return 0 or 1
     */
    public int code() {
        return code;
    }

    /**
     * The units of a type's {@code units} byte.
     *
     * @param r where the byte is read
     * @return the units
     * @throws TurborelException SQLSTATE TB916 if the byte is neither 0 nor 1: a type damaged, its
     *     frame whole, so its request fails alone ([PROTOCOL Values and fields])
     */
    static CharUnits read(Reader r) throws TurborelException {
        int units = r.u8();
        for (CharUnits each : values()) {
            if (each.code == units) {
                return each;
            }
        }
        throw Failures.corrupt(Cite.TYPES + " a length's units are " + units + ", neither 0 nor 1");
    }
}
