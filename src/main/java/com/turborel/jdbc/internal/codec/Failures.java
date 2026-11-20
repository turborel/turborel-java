package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;

/**
 * The failures the codec reports of its own besides a broken connection ({@link Broken}): a value
 * the program gave that the protocol cannot carry, and one past turborel's limits. Their codes are
 * turborel's own for the same failures (RULES.md D7).
 */
public final class Failures {
    /** The SQLCODE of a program's misuse of the API, turborel's -910. */
    public static final int MISUSE_SQLCODE = -910;

    /** The SQLSTATE of a program's misuse of the API, turborel's TB910. */
    public static final String MISUSE_SQLSTATE = "TB910";

    /** The SQLCODE of what is valid but past turborel's limits, -900. */
    public static final int BEYOND_SQLCODE = -900;

    /** The SQLSTATE of what is past turborel's limits: feature not supported, 0A000. */
    public static final String BEYOND_SQLSTATE = "0A000";

    /** The SQLCODE of a data exception, Turborel's -301. */
    public static final int DATA_SQLCODE = -301;

    /** The SQLSTATE of a time zone displacement out of range: 22009. */
    public static final String INVALID_TIME_ZONE_DISPLACEMENT = "22009";

    private Failures() {}

    /**
     * A value the program gave that the protocol cannot carry, such as a number past a field's
     * width: SQLCODE -910, SQLSTATE TB910.
     *
     * @param message what was wrong, beginning with the rule it cites
     * @return the failure, for the caller to throw
     */
    public static TurborelException misuse(String message) {
        return new TurborelException(MISUSE_SQLCODE, MISUSE_SQLSTATE, message);
    }

    /**
     * Something valid but past turborel's limits: SQLCODE -900, SQLSTATE 0A000.
     *
     * @param message what was past which limit, beginning with the rule it cites
     * @return the failure, for the caller to throw
     */
    public static TurborelException beyond(String message) {
        return new TurborelException(BEYOND_SQLCODE, BEYOND_SQLSTATE, message);
    }
}
