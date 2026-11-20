package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;

/**
 * The failures the codec reports of its own besides a broken connection ({@link Broken}): the
 * program's misuse of the API, a value the protocol cannot carry, one past turborel's limits, and
 * a stored form that is damaged. Their codes are those of the table every Turborel driver shares,
 * turborel's own where it has the case (RULES.md D9).
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

    /** The SQLSTATE of a number past what its field or type holds: numeric value out of range. */
    public static final String NUMERIC_VALUE_OUT_OF_RANGE = "22003";

    /** The SQLSTATE of a character the repertoire does not hold, as a lone surrogate. */
    public static final String CHARACTER_NOT_IN_REPERTOIRE = "22021";

    /** The SQLCODE of a stored form that is damaged, turborel's -916. */
    public static final int CORRUPT_SQLCODE = -916;

    /** The SQLSTATE of a stored form that is damaged, turborel's TB916. */
    public static final String CORRUPT_SQLSTATE = "TB916";

    /** The SQLSTATE of a time zone displacement out of range: 22009. */
    public static final String INVALID_TIME_ZONE_DISPLACEMENT = "22009";

    private Failures() {}

    /**
     * The program's misuse of the API, for which turborel has no case of its own, as a cancel key
     * not of 16 octets: SQLCODE -910, SQLSTATE TB910.
     *
     * @param message what was wrong, beginning with the rule it cites
     * @return the failure, for the caller to throw
     */
    public static TurborelException misuse(String message) {
        return new TurborelException(MISUSE_SQLCODE, MISUSE_SQLSTATE, message);
    }

    /**
     * A number past what its field or type holds, which the protocol cannot carry: SQLCODE -301,
     * SQLSTATE 22003.
     *
     * @param message what was past which bound, beginning with the rule it cites
     * @return the failure, for the caller to throw
     */
    public static TurborelException outOfRange(String message) {
        return new TurborelException(DATA_SQLCODE, NUMERIC_VALUE_OUT_OF_RANGE, message);
    }

    /**
     * A string that is not Unicode, which the protocol cannot carry: SQLCODE -301, SQLSTATE 22021.
     *
     * @param message what was wrong, beginning with the rule it cites
     * @return the failure, for the caller to throw
     */
    public static TurborelException notInRepertoire(String message) {
        return new TurborelException(DATA_SQLCODE, CHARACTER_NOT_IN_REPERTOIRE, message);
    }

    /**
     * A stored form the program gave that is damaged, as turborel reports one: SQLCODE -916,
     * SQLSTATE TB916.
     *
     * @param message what was damaged, beginning with the rule it cites
     * @return the failure, for the caller to throw
     */
    public static TurborelException corrupt(String message) {
        return new TurborelException(CORRUPT_SQLCODE, CORRUPT_SQLSTATE, message);
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
