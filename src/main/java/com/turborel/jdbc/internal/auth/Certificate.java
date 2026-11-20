package com.turborel.jdbc.internal.auth;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Broken;
import com.turborel.jdbc.internal.codec.Octets;

/**
 * The client's side of {@code certificate}: nothing sent, the certificate presented over TLS
 * proving the user its common name names ([PROTOCOL Mechanisms]).
 */
public final class Certificate implements Mechanism {
    /** The name of the mechanism. */
    public static final String NAME = "certificate";

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
        throw Broken.connection("[PROTOCOL Mechanisms] certificate has no answer to a challenge");
    }
}
