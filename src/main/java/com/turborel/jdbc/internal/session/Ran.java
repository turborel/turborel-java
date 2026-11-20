package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a Query of several statements gave ([CLIENT Controlling the session]).
 *
 * @param outcomes the outcomes of the statements that ran, in order
 * @param failure the failure of the statement that stopped the rest, if one did
 */
public record Ran(List<Outcome> outcomes, @Nullable TurborelException failure) {
    /**
     * What ran, of the fields given.
     *
     * @param outcomes the outcomes, copied
     * @param failure the failure that stopped the rest, if one did
     */
    public Ran {
        outcomes = List.copyOf(outcomes);
    }
}
