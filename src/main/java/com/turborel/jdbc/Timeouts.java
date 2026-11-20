package com.turborel.jdbc;

import com.turborel.jdbc.internal.session.Canceller;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Query timeouts ([DESIGN] D2): a statement past its timeout cancelled by the protocol's Cancel, as
 * {@code Statement.cancel} cancels it. One daemon thread of the driver's, named
 * turborel-jdbc-timeouts, made as the first timeout is set, sends every Cancel due.
 */
final class Timeouts {
    /** The thread's name. */
    static final String THREAD = "turborel-jdbc-timeouts";

    private static @Nullable ScheduledThreadPoolExecutor timer;

    private Timeouts() {}

    private static synchronized ScheduledThreadPoolExecutor timer() {
        ScheduledThreadPoolExecutor made = timer;
        if (made == null) {
            made = new ScheduledThreadPoolExecutor(1, run -> {
                Thread thread = new Thread(run, THREAD);
                thread.setDaemon(true);
                return thread;
            });
            made.setRemoveOnCancelPolicy(true);
            timer = made;
        }
        return made;
    }

    /**
     * A Cancel by {@code canceller} in {@code seconds}, unless the statement ends first.
     *
     * @param canceller what cancels the statement, or none
     * @param seconds the timeout, 0 for none
     * @return what stops it, or none
     */
    static @Nullable ScheduledFuture<?> start(@Nullable Canceller canceller, int seconds) {
        if (canceller == null || seconds <= 0) {
            return null;
        }
        return timer().schedule(
                        () -> {
                            try {
                                canceller.cancel();
                            } catch (com.turborel.jdbc.TurborelException late) {
                                // The statement ended as the Cancel was sent: nothing is left to cancel.
                            }
                        },
                        seconds,
                        TimeUnit.SECONDS);
    }

    /**
     * Stops a timeout, as its statement ends.
     *
     * @param timeout what {@link #start} gave
     */
    static void stop(@Nullable ScheduledFuture<?> timeout) {
        if (timeout != null) {
            timeout.cancel(false);
        }
    }
}
