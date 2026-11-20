package com.turborel.jdbc;

import java.io.Serial;
import java.sql.SQLException;

/**
 * A failure of turborel or of the driver: its SQLCODE, its SQLSTATE and its message (RULES.md
 * R-ERR-1).
 *
 * <p>The SQLCODE is the exception's vendor code, so {@link #getErrorCode()} gives it as well as
 * {@link #sqlcode()}, and a tool that knows JDBC alone still sees it.
 */
public class TurborelException extends SQLException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * A failure of the code, state and message given.
     *
     * @param sqlcode its SQLCODE, negative for a failure
     * @param sqlstate its SQLSTATE, five characters
     * @param message what went wrong, in plain words, beginning with the rule it cites
     */
    public TurborelException(int sqlcode, String sqlstate, String message) {
        super(message, sqlstate, sqlcode);
    }

    /**
     * The failure's SQLCODE, the older number beside its SQLSTATE.
     *
     * @return its SQLCODE
     */
    public int sqlcode() {
        return getErrorCode();
    }
}
