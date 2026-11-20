package com.turborel.jdbc.internal.auth;

import com.turborel.jdbc.internal.codec.Octets;
import java.nio.charset.StandardCharsets;

/**
 * What a password derives under a salt and iterations: ClientKey and ServerKey [RFC 5802 §3],
 * never the password nor SaltedPassword ([PROTOCOL Mechanisms]).
 *
 * @param client ClientKey
 * @param server ServerKey
 */
record Derived(Octets client, Octets server) {
    /** What ClientKey is the HMAC of [RFC 5802 §3]. */
    private static final byte[] CLIENT_KEY = "Client Key".getBytes(StandardCharsets.US_ASCII);

    /** What ServerKey is the HMAC of [RFC 5802 §3]. */
    private static final byte[] SERVER_KEY = "Server Key".getBytes(StandardCharsets.US_ASCII);

    /**
     * The keys a password derives under a salt and iterations: the slow step.
     *
     * @param password the password
     * @param salt the salt
     * @param iterations the iterations
     * @return the keys
     */
    static Derived of(String password, byte[] salt, long iterations) {
        byte[] salted = Crypto.salted(password.getBytes(StandardCharsets.UTF_8), salt, iterations);
        return new Derived(Octets.of(Crypto.hmac(salted, CLIENT_KEY)), Octets.of(Crypto.hmac(salted, SERVER_KEY)));
    }

    /** Never the keys. */
    @Override
    public String toString() {
        return "Derived(..)";
    }
}
