package com.turborel.jdbc.internal.codec;

import org.jspecify.annotations.Nullable;

/**
 * A procedure's parameter's mode, as a Prepared of a CALL tells it ([PROTOCOL Parameter modes]),
 * by the code ISO/IEC 9075-2:2023's Table 33 gives it.
 */
public enum ParameterMode {
    /** An input parameter, 1. */
    IN(1),
    /** An input and output parameter, 2. */
    INOUT(2),
    /** An output parameter, 4. */
    OUT(4);

    private final int code;

    ParameterMode(int code) {
        this.code = code;
    }

    /**
     * Its code.
     *
     * @return the code
     */
    public int code() {
        return code;
    }

    /**
     * Whether a value is given for it: of IN and INOUT.
     *
     * @return whether it takes a value
     */
    public boolean takesValue() {
        return this != OUT;
    }

    /**
     * Whether its value comes back: of INOUT and OUT.
     *
     * @return whether it gives a value
     */
    public boolean givesValue() {
        return this != IN;
    }

    /** The mode of {@code code}, or none of another. */
    static @Nullable ParameterMode of(int code) {
        for (ParameterMode each : values()) {
            if (each.code == code) {
                return each;
            }
        }
        return null;
    }
}
