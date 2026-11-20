package com.turborel.jdbc.internal.auth;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Broken;
import com.turborel.jdbc.internal.codec.Octets;

/**
 * The client's side of {@code trust}: nothing to prove, so a server takes it only from a client
 * of its own machine, where SERVER.TRUST_LOCAL is ON ([PROTOCOL Mechanisms]).
 */
public final class Trust implements Mechanism {
    /** The name of the mechanism. */
    public static final String NAME = "trust";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Octets initial() {
        return Octets.EMPTY;
    }

    @Override
    public Octets answer(Octets challenge) throws TurborelException {
        throw Broken.connection("[PROTOCOL Mechanisms] trust has no answer to a challenge");
    }
}
