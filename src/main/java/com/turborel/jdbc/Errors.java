package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.Failures;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLWarning;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The JDBC layer's own failures ([DESIGN] D2, D9): each a {@link TurborelException}. */
final class Errors {
    /** The SQLCODE and SQLSTATE of a parameter with no value: a dynamic SQL error, using clause. */
    static final int DYNAMIC_SQLCODE = -921;

    static final String NO_VALUE_STATE = "07001";

    /** The characters of a SQLSTATE's class. */
    private static final int CLASS_LENGTH = 2;

    /** The SQLSTATE classes JDBC throws as a {@link SQLSyntaxErrorException}: class 42, and 2A and 37 of old. */
    private static final java.util.Set<String> SYNTAX_CLASSES = java.util.Set.of("42", "2A", "37");

    /** The SQLSTATE of a value that cannot be converted: invalid character value for cast. */
    static final String INVALID_CAST = "22018";

    private Errors() {}

    /**
     * What JDBC has and the driver does not: SQLCODE -900, SQLSTATE 0A000.
     *
     * @param what what is not supported
     * @return the failure
     */
    static TurborelException unsupported(String what) {
        return Failures.beyond("[DESIGN] D2 " + what + " is not supported");
    }

    /**
     * The program's misuse of the JDBC interface: SQLCODE -910, SQLSTATE TB910.
     *
     * @param why what was wrong
     * @return the failure
     */
    static TurborelException misuse(String why) {
        return Failures.misuse("[DESIGN] D2 " + why);
    }

    /**
     * A value that cannot be read or sent as asked: SQLCODE -301, SQLSTATE 22018.
     *
     * @param why what was wrong
     * @return the failure
     */
    static TurborelException conversion(String why) {
        return new TurborelException(Failures.DATA_SQLCODE, INVALID_CAST, "[DESIGN] D2 " + why);
    }

    /**
     * {@code failure} as JDBC classes it: a syntax error or access rule violation, SQLSTATE class
     * 42 (and the older 2A and 37), a {@link SQLSyntaxErrorException} of its SQLSTATE, vendor code
     * and message, its cause the failure itself, which carries all of it (RULES.md D9); another as
     * it is.
     *
     * @param failure the failure
     * @return the failure to throw
     */
    static SQLException classed(SQLException failure) {
        String state = String.valueOf(failure.getSQLState());
        if (failure instanceof TurborelException
                && SYNTAX_CLASSES.contains(state.substring(0, Math.min(CLASS_LENGTH, state.length())))) {
            return new SQLSyntaxErrorException(failure.getMessage(), state, failure.getErrorCode(), failure);
        }
        return failure;
    }

    /**
     * The chain of warnings {@code conditions} are, each a {@link SQLWarning} of its SQLSTATE, its
     * SQLCODE the vendor code.
     *
     * @param conditions the conditions, in order
     * @return the first warning, the rest chained to it, or none
     */
    static @Nullable SQLWarning warnings(List<TurborelException> conditions) {
        SQLWarning first = null;
        for (TurborelException each : conditions) {
            SQLWarning warning = new SQLWarning(each.getMessage(), each.getSQLState(), each.getErrorCode());
            if (first == null) {
                first = warning;
            } else {
                first.setNextWarning(warning);
            }
        }
        return first;
    }
}
