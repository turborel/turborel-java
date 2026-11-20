package com.turborel.jdbc;

import java.io.Serial;
import java.sql.SQLException;
import java.util.Map;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;

/**
 * A failure of Turborel or of the driver: its SQLCODE, its SQLSTATE and its message, and of one the
 * server sent the rest of it, the rule it cites, its position, whether a transaction is active
 * after it, and its diagnostics (RULES.md D9, R-ERR-2).
 *
 * <p>The SQLCODE is the exception's vendor code, so {@link #getErrorCode()} gives it as well as
 * {@link #sqlcode()}, and a tool that knows JDBC alone still sees it.
 */
public class TurborelException extends SQLException {
    @Serial
    private static final long serialVersionUID = 1L;

    /** The rest of a failure the driver raises itself: nothing cited or placed. */
    private static final Details NONE = new Details(null, null, false, Map.of());

    /** The rest of the failure; transient, as a failure is not sent anywhere whole. */
    private final transient @Nullable Details details;

    /**
     * A failure of the code, state and message given.
     *
     * @param sqlcode its SQLCODE, negative for a failure
     * @param sqlstate its SQLSTATE, five characters
     * @param message what went wrong, in plain words, beginning with the rule it cites
     */
    public TurborelException(int sqlcode, String sqlstate, String message) {
        this(sqlcode, sqlstate, message, NONE);
    }

    /**
     * A failure the server sent, with the rest of it.
     *
     * @param sqlcode its SQLCODE
     * @param sqlstate its SQLSTATE, five characters
     * @param message what went wrong, as the server said it
     * @param details the rule it cites, its position, whether a transaction is active after it,
     *     and its diagnostics
     */
    public TurborelException(int sqlcode, String sqlstate, String message, Details details) {
        super(message, sqlstate, sqlcode);
        this.details = details;
    }

    /**
     * The failure's SQLCODE, the older number beside its SQLSTATE.
     *
     * @return its SQLCODE
     */
    public int sqlcode() {
        return getErrorCode();
    }

    /**
     * The clause of a standard the failure cites, as the server's messages cite rules.
     *
     * @return the rule, or none
     */
    public @Nullable String rule() {
        return details == null ? null : details.rule();
    }

    /**
     * Where in the statement the failure is, in characters, if the server placed it.
     *
     * @return the position, or empty
     */
    public OptionalLong position() {
        Long position = details == null ? null : details.position();
        return position == null ? OptionalLong.empty() : OptionalLong.of(position);
    }

    /**
     * Whether the session's transaction is active after the failure, as the server said.
     *
     * @return whether a transaction is active
     */
    public boolean transactionActive() {
        return details != null && details.active();
    }

    /**
     * What the failure says beyond its message, by the protocol's names of its fields: SEVERITY,
     * DETAIL, HINT, CONTEXT, SCHEMA, TABLE, COLUMN, DATA_TYPE and CONSTRAINT, each if known.
     *
     * @return the diagnostics, unmodifiable
     */
    public Map<String, String> diagnostics() {
        return details == null ? Map.of() : details.diagnostics();
    }

    /**
     * The rest of a failure the server sent.
     *
     * @param rule the clause it cites, or none
     * @param position where in the statement, in characters, or none
     * @param active whether a transaction is active after it
     * @param diagnostics what else it says, by field name
     */
    public record Details(
            @Nullable String rule, @Nullable Long position, boolean active, Map<String, String> diagnostics) {
        /**
         * Details of the parts given.
         *
         * @param rule the clause it cites
         * @param position where in the statement
         * @param active whether a transaction is active after it
         * @param diagnostics what else it says, copied
         */
        public Details {
            diagnostics = Map.copyOf(diagnostics);
        }
    }
}
