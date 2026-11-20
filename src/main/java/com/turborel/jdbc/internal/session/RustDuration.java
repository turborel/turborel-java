package com.turborel.jdbc.internal.session;

import java.math.BigDecimal;
import java.time.Duration;

/** A duration as Rust's {@code Debug} writes one, as {@code 30s}, {@code 1.5s} or {@code 100ms}, so failures read as the reference's. */
final class RustDuration {
    /** Nanoseconds in a millisecond, and in a microsecond. */
    private static final long MILLI = 1_000_000L;

    private static final long MICRO = 1_000L;

    /** The decimal places of the seconds of a duration's nanoseconds, of its milliseconds and of its microseconds. */
    private static final int NANO_PLACES = 9;

    private static final int MILLI_PLACES = 6;

    private static final int MICRO_PLACES = 3;

    private RustDuration() {}

    /**
     * {@code d} as Rust writes it.
     *
     * @param d the duration
     * @return its text
     */
    static String of(Duration d) {
        long nanos = d.toNanosPart();
        if (d.toSeconds() > 0) {
            return plain(BigDecimal.valueOf(d.toSeconds()).add(BigDecimal.valueOf(nanos, NANO_PLACES))) + "s";
        }
        if (nanos >= MILLI) {
            return plain(BigDecimal.valueOf(nanos, MILLI_PLACES)) + "ms";
        }
        if (nanos >= MICRO) {
            return plain(BigDecimal.valueOf(nanos, MICRO_PLACES)) + "µs";
        }
        return nanos + "ns";
    }

    private static String plain(BigDecimal n) {
        return n.stripTrailingZeros().toPlainString();
    }
}
