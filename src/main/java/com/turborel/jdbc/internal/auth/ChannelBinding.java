package com.turborel.jdbc.internal.auth;

/**
 * Whether SCRAM binds its exchange to the TLS connection carrying it ([CLIENT TLS by mode]), as
 * Turborel's settings name it.
 */
public enum ChannelBinding {
    /** Binds nothing. */
    DISABLE,
    /** Binds where the connection has a binding. */
    PREFER,
    /** Refuses to go on unbound. */
    REQUIRE
}
