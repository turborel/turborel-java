package com.turborel.jdbc.internal.auth;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Octets;

/**
 * A client's side of a mechanism of authentication ([PROTOCOL Mechanisms]): its name, its first
 * bytes, and its answer to each challenge, as Turborel's {@code client/src/mechanism.rs} has it.
 */
public interface Mechanism {
    /**
     * The mechanism's name, as the server knows it.
     *
     * @return the name
     */
    String name();

    /**
     * The bytes sent with Hello.
     *
     * @return the first bytes
     */
    Octets initial();

    /**
     * The answer to a challenge of the server's.
     *
     * @param challenge the challenge
     * @return the answer
     * @throws TurborelException if it has none: the connection is then given up
     */
    Octets answer(Octets challenge) throws TurborelException;

    /**
     * Whether what it sends gives away what proves the user, as a password does, or lets one who
     * overhears it guess that at leisure, as SCRAM's proof does: if so, a connection over plain
     * TCP refuses it before anything is sent.
     *
     * @return whether it is secret
     */
    default boolean secret() {
        return false;
    }

    /**
     * Whether the exchange may end as the server sends Ready: a mechanism by which the server
     * proves itself too, as SCRAM's, refuses one that did not.
     *
     * @throws TurborelException if the server has not proved itself
     */
    default void ready() throws TurborelException {
        // Most mechanisms ask nothing of the server.
    }

    /**
     * The connection about to carry it is secured by TLS, of {@code channel}: a mechanism that
     * binds itself to its channel, as SCRAM-SHA-256-PLUS, takes it, for this connection alone.
     *
     * @param channel what the handshake gave
     */
    default void bind(TlsChannel channel) {
        // Most mechanisms bind nothing.
    }

    /** The server refused the login: a mechanism that keeps what it learnt, as SCRAM's cache, forgets it. */
    default void refused() {
        // Most mechanisms keep nothing.
    }
}
