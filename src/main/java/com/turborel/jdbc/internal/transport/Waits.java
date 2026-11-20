package com.turborel.jdbc.internal.transport;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/** A wait as the system's calls take it, in milliseconds ([CLIENT Timeouts]). */
final class Waits {
    private Waits() {}

    /**
     * A wait in milliseconds, 0 for none: at least 1 of a wait given, as 0 means no limit to the
     * system, and at most what an {@code int} holds, some 24 days.
     *
     * @param within the wait, or none
     * @return its milliseconds, or 0 for none
     */
    static int millis(@Nullable Duration within) {
        if (within == null) {
            return 0;
        }
        long millis = within.toMillis();
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, millis));
    }
}
