package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import org.jspecify.annotations.Nullable;

/**
 * A failure, as an Error or a condition carries it ([PROTOCOL Composite fields]): its SQLCODE,
 * SQLSTATE and message, the rule it cites, where in the statement, whether a transaction is
 * active after it, and its diagnostics, tags 7 to 15 of its tail.
 *
 * @param sqlcode its SQLCODE
 * @param sqlstate its SQLSTATE, five characters
 * @param message what went wrong
 * @param rule the clause of a standard it cites, if it cites one
 * @param position where in the statement, in characters, a {@code u64}'s bits, if placed
 * @param active whether the session's transaction is active after it
 * @param diagnostics what else it says, if anything
 */
public record Failure(
        int sqlcode,
        String sqlstate,
        String message,
        @Nullable String rule,
        @Nullable Long position,
        boolean active,
        @Nullable Diagnostics diagnostics) {
    /** The SQLSTATE class of a connection exception. */
    private static final String CONNECTION_CLASS = "08";

    /** Connection does not exist, which names another connection and leaves this one. */
    private static final String NO_CONNECTION = "08003";

    /** The SQLSTATE class of invalid authorization. */
    private static final String AUTHORIZATION_CLASS = "28";

    /**
     * Whether the connection it came on is lost ([PROTOCOL Error 'E']): a connection exception,
     * SQLSTATE class 08 but 08003, or credentials refused as it is made, class 28 of a connection
     * exception's SQLCODE; after either the server closes it.
     *
     * @return whether it ends the connection
     */
    public boolean endsConnection() {
        boolean connecting = sqlcode == Broken.SQLCODE;
        return (sqlstate.startsWith(CONNECTION_CLASS) && !sqlstate.equals(NO_CONNECTION))
                || (connecting && sqlstate.startsWith(AUTHORIZATION_CLASS));
    }

    /**
     * The failure as the driver's error, carrying all of it (RULES.md D9).
     *
     * @return the error
     */
    public TurborelException toException() {
        java.util.Map<String, String> told = new java.util.LinkedHashMap<>();
        if (diagnostics != null) {
            diagnostics.fields().forEach((field, text) -> told.put(field.name(), text));
        }
        return new TurborelException(
                sqlcode, sqlstate, message, new TurborelException.Details(rule, position, active, told));
    }

    /**
     * A failure read, its tail to the end of what the reader reads.
     *
     * @param r where it is read
     * @return the failure
     * @throws TurborelException if it is malformed
     */
    static Failure read(Reader r) throws TurborelException {
        int sqlcode = r.bits32();
        String sqlstate = r.string();
        String message = r.string();
        String rule = r.flag() ? r.string() : null;
        Long position = r.flag() ? r.u64() : null;
        boolean active = r.flag();
        return new Failure(sqlcode, sqlstate, message, rule, position, active, Diagnostics.read(r));
    }
}
