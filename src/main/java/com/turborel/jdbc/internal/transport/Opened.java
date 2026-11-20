package com.turborel.jdbc.internal.transport;

import com.turborel.jdbc.internal.auth.TlsChannel;
import org.jspecify.annotations.Nullable;

/**
 * A stream to a server, and over TLS what its handshake gives a mechanism to bind to ([CLIENT
 * Connecting]).
 *
 * @param duplex the stream
 * @param channel what TLS's handshake gave, or none of a stream not TLS's
 */
public record Opened(Duplex duplex, @Nullable TlsChannel channel) {}
