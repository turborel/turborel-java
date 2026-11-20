package com.turborel.jdbc.internal.transport;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * How long a connection waits on its server; none, no limit ([CLIENT Timeouts]).
 *
 * @param connect to connect, to begin TLS, and for each step of its login
 * @param read for each read of the server once logged in: none by default, as a statement may
 *     run as long as it must
 */
public record Timeouts(@Nullable Duration connect, @Nullable Duration read) {
    /** How long a connection waits by default to connect, and for each step of its login. */
    public static final Duration CONNECT_WITHIN = Duration.ofSeconds(30);

    /** How long terminating waits for the server to close, unless the read timeout says. */
    public static final Duration TERMINATE_WITHIN = Duration.ofSeconds(60);

    /** How long a cancel waits for the server, to connect, to send and to be closed. */
    public static final Duration CANCEL_WITHIN = Duration.ofSeconds(10);

    /** {@link #CONNECT_WITHIN} to connect and log in; no limit after. */
    public static final Timeouts DEFAULT = new Timeouts(CONNECT_WITHIN, null);
}
