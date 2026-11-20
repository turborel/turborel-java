package com.turborel.jdbc.internal.auth;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Broken;
import com.turborel.jdbc.internal.codec.Octets;
import java.nio.charset.StandardCharsets;

/**
 * The client's side of {@code password}: the password's UTF-8, sent whole, so only over TLS, a
 * Unix socket or a named pipe ([PROTOCOL Mechanisms]). It is never shown (RULES.md R-SAFE-4).
 */
public final class Password implements Mechanism {
    /** The name of the mechanism. */
    public static final String NAME = "password";

    private final String password;

    /**
     * The mechanism of a password.
     *
     * @param password the password
     */
    public Password(String password) {
        this.password = password;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Octets initial() {
        return Octets.of(password.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Octets answer(Octets challenge) throws TurborelException {
        throw Broken.connection("[PROTOCOL Mechanisms] password has no answer to a challenge");
    }

    @Override
    public boolean secret() {
        return true;
    }

    /** Never the password. */
    @Override
    public String toString() {
        return "Password(..)";
    }
}
