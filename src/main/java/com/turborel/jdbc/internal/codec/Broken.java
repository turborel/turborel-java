package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;

/**
 * The failure of a connection whose peer broke the protocol: a malformed message, a frame too
 * long, a request out of turn ([PROTOCOL Errors and notices]). The connection cannot go on after
 * one, so the client closes it.
 */
public final class Broken {
    /** The SQLCODE of a connection failure ([PROTOCOL Errors and notices]). */
    public static final int SQLCODE = -919;

    /** The SQLSTATE of a connection failure, class 08 ([PROTOCOL Errors and notices]). */
    public static final String SQLSTATE = "08006";

    private Broken() {}

    /**
     * A connection failure, SQLCODE -919 and SQLSTATE 08006.
     *
     * @param message what was wrong, beginning with the rule it cites
     * @return the failure, for the caller to throw
     */
    public static TurborelException connection(String message) {
        return new TurborelException(SQLCODE, SQLSTATE, message);
    }

    /**
     * Whether {@code failure} ends the connection it came on: a connection exception, class 08, but
     * 08003, which names another connection; or credentials refused as it is made, SQLCODE -919 of
     * class 28. A value the client cannot take, as a truth byte of 2, fails its request alone.
     *
     * @param failure the failure
     * @return whether it ends the connection
     */
    public static boolean endsConnection(TurborelException failure) {
        return Failure.endsConnection(failure.getErrorCode(), failure.getSQLState());
    }
}
