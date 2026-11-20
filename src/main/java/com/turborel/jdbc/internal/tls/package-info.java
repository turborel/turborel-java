/**
 * TLS for the client, by the JDK's own JSSE ([CLIENT Connecting]): the authorities trusted, the
 * certificate presented, the handshake done before Hello, and what it gives SCRAM to bind to, as
 * Turborel's {@code client/src/tls.rs} does (RULES.md D1).
 */
@NullMarked
package com.turborel.jdbc.internal.tls;

import org.jspecify.annotations.NullMarked;
