package com.turborel.jdbc.internal.settings;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Broken;

/** The failures of settings no connection can be made by ([CLIENT Where settings come from]). */
final class Invalid {
    /** The SQLSTATE of a client unable to connect, class 08. */
    static final String UNABLE_TO_CONNECT = "08001";

    /** The SQLSTATE of credentials refused: invalid authorization specification. */
    static final String INVALID_AUTHORIZATION = "28000";

    private Invalid() {}

    /**
     * A setting that cannot be read, or settings no connection can be made by: SQLCODE -919,
     * SQLSTATE 08001, the client unable to connect.
     *
     * @param message what was wrong, beginning with the rule it cites
     * @return the failure, for the caller to throw
     */
    static TurborelException setting(String message) {
        return new TurborelException(Broken.SQLCODE, UNABLE_TO_CONNECT, message);
    }
}
