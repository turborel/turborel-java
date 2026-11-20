package com.turborel.jdbc.internal.auth;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Broken;
import com.turborel.jdbc.internal.codec.Octets;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * The client's side of {@code scram-sha-256} and {@code scram-sha-256-plus} ([PROTOCOL
 * Mechanisms], [CLIENT Connecting]): the client proves it knows the password, never sending it, and
 * takes the server only once it has proved it knows the user's verifier. As Turborel's {@code
 * client/src/scram.rs}.
 *
 * <p>Over TLS ({@link #bind}) it is {@code scram-sha-256-plus}: bound by {@code tls-exporter} over
 * TLS 1.3 where the JDK exports it, else by {@code tls-server-end-point}, to the certificate the
 * server presented; of a certificate that defines no binding it is {@code scram-sha-256} with the
 * header {@code y}. Over a Unix socket, a named pipe or plain TCP it binds nothing. Its exchange
 * overheard lets the eavesdropper guess the password at leisure, so over plain TCP it is refused
 * before anything is sent unless the program asks for it in the clear. It is never shown.
 */
public final class Scram implements Mechanism {
    /** The name of the mechanism, as a client asks for it. */
    public static final String NAME = "scram-sha-256";

    /** The name of the mechanism bound to its channel. */
    public static final String NAME_PLUS = "scram-sha-256-plus";

    /** How the server's final message begins: its signature. */
    private static final String VERIFIER = "v=";

    /** How the server's final message begins if it refuses. */
    private static final String ERROR = "e=";

    /** The characters that begin an attribute: its name and {@code =}. */
    private static final int PREFIX = 2;

    private final String password;
    private final boolean clear;
    private final ChannelBinding binding;
    private final @Nullable KeyCache cache;
    private Binding channel = Binding.Flag.UNBOUND;
    private @Nullable ScramClient exchange;
    private ScramClient.@Nullable ServerFirst used;

    private Scram(String password, boolean clear, ChannelBinding binding, @Nullable KeyCache cache) {
        this.password = password;
        this.clear = clear;
        this.binding = binding;
        this.cache = cache;
    }

    /**
     * The proof of a password, binding its channel where it can, its keys derived at each login.
     *
     * @param password the password
     */
    public Scram(String password) {
        this(password, false, ChannelBinding.PREFER, null);
    }

    /**
     * It, sent over plain TCP too: for a server whose SERVER.SCRAM_WITHOUT_TLS is ON.
     *
     * @return a mechanism like it, in the clear
     */
    public Scram inTheClear() {
        return new Scram(password, true, binding, cache);
    }

    /**
     * It, binding its channel as {@code how} says ([CLIENT TLS by mode]).
     *
     * @param how whether it binds
     * @return a mechanism like it, binding so
     */
    public Scram channelBinding(ChannelBinding how) {
        return new Scram(password, clear, how, cache);
    }

    /**
     * It, its keys kept in {@code keys} and taken from it ([CLIENT The SCRAM key cache]).
     *
     * @param keys the cache, which its copies share
     * @return a mechanism like it, with the cache
     */
    public Scram withCache(KeyCache keys) {
        return new Scram(password, clear, binding, keys);
    }

    @Override
    public String name() {
        return channel.bound() ? NAME_PLUS : NAME;
    }

    /** client-first, of a new nonce, bound as {@link #bind} said; the user is Hello's. */
    @Override
    public Octets initial() {
        ScramClient begun = new ScramClient("", channel);
        channel = Binding.Flag.UNBOUND;
        used = null;
        exchange = begun;
        return Octets.of(begun.first(Crypto.nonce()));
    }

    /**
     * client-final to server-first; then, to server-final, nothing, once the server's signature is
     * right. A failure forgets the keys the cache gave.
     */
    @Override
    public Octets answer(Octets challenge) throws TurborelException {
        ScramClient begun = exchange;
        if (begun == null) {
            throw Broken.connection("[PROTOCOL Mechanisms] SCRAM was not begun");
        }
        byte[] bytes = challenge.toByteArray();
        String start = new String(bytes, 0, Math.min(PREFIX, bytes.length), StandardCharsets.US_ASCII);
        try {
            if (start.equals(VERIFIER) || start.equals(ERROR)) {
                begun.verify(bytes);
                return Octets.EMPTY;
            }
            return prove(begun, bytes);
        } catch (TurborelException failed) {
            forget();
            throw failed;
        }
    }

    private Octets prove(ScramClient begun, byte[] serverFirst) throws TurborelException {
        if (binding == ChannelBinding.REQUIRE && !begun.bound()) {
            throw ScramClient.refused("channel binding is required, and this connection is not bound");
        }
        ScramClient.ServerFirst first = begun.read(serverFirst);
        Derived keys = cache == null
                ? Derived.of(password, first.salt().toByteArray(), first.iterations())
                : cache.derived(password, first.salt(), first.iterations());
        used = first;
        return Octets.of(begun.prove(first, keys));
    }

    private void forget() {
        ScramClient.ServerFirst first = used;
        if (cache != null && first != null) {
            cache.forget(password, first.salt(), first.iterations());
        }
        used = null;
    }

    @Override
    public boolean secret() {
        return !clear;
    }

    /**
     * Bound to the channel's {@code tls-exporter}, over TLS 1.3; else to its certificate's {@code
     * tls-server-end-point}; else able to bind but offered none. Under {@link
     * ChannelBinding#DISABLE}, nothing.
     */
    @Override
    public void bind(TlsChannel tls) {
        if (binding == ChannelBinding.DISABLE) {
            return;
        }
        Octets exporter = tls.exporter();
        Octets certificate = tls.certificate();
        Octets endPoint = certificate == null ? null : EndPoint.of(certificate.toByteArray());
        if (exporter != null) {
            channel = new Binding.Exporter(exporter);
        } else if (endPoint != null) {
            channel = new Binding.EndPoint(endPoint);
        } else {
            channel = Binding.Flag.UNOFFERED;
        }
    }

    @Override
    public void ready() throws TurborelException {
        ScramClient begun = exchange;
        if (begun == null || !begun.verified()) {
            throw Broken.connection("[PROTOCOL Mechanisms] the server did not prove it knows the user's verifier");
        }
    }

    @Override
    public void refused() {
        forget();
    }

    /** Never the password. */
    @Override
    public String toString() {
        return "Scram(..)";
    }
}
