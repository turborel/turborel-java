package com.turborel.jdbc.internal.auth;

import com.turborel.jdbc.internal.codec.Octets;
import org.jspecify.annotations.Nullable;

/**
 * What a TLS connection's handshake gives a mechanism to bind itself to ([PROTOCOL Mechanisms]):
 * the certificate the server presented, and over TLS 1.3, where the JDK exports it, the keying
 * material of {@code tls-exporter} [RFC 9266 §2].
 *
 * @param certificate the server's certificate, its chain's first, as DER, if it presented one
 * @param exporter the 32 bytes of {@code tls-exporter}, over TLS 1.3 where the JDK gives them
 */
public record TlsChannel(
        @Nullable Octets certificate, @Nullable Octets exporter) {}
