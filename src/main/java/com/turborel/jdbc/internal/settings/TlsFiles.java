package com.turborel.jdbc.internal.settings;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The files a connection's TLS is made of ([CLIENT TLS by mode]).
 *
 * @param roots a PEM file of the authorities trusted, or none
 * @param systemRoots whether the platform's authorities are trusted too
 * @param certificate a PEM file of the client's certificate chain, to present, or none
 * @param key a PEM file of its private key, or none
 * @param serverName the name the server's certificate is checked against, if not the host's
 */
public record TlsFiles(
        @Nullable Path roots,
        boolean systemRoots,
        @Nullable Path certificate,
        @Nullable Path key,
        @Nullable String serverName) {}
