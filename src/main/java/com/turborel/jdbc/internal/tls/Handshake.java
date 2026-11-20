package com.turborel.jdbc.internal.tls;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.auth.TlsChannel;
import com.turborel.jdbc.internal.codec.Broken;
import com.turborel.jdbc.internal.codec.Octets;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import org.jspecify.annotations.Nullable;

/**
 * A TCP connection secured by TLS, its handshake done before Hello, so a server not trusted is a
 * connection failure that says why ([CLIENT Connecting]); and what the handshake gives SCRAM to
 * bind to ([PROTOCOL Mechanisms]).
 */
public final class Handshake {
    /** The label of the keying material {@code tls-exporter} binds [RFC 9266 §2]. */
    static final String EXPORTER_LABEL = "EXPORTER-Channel-Binding";

    /** The bytes of {@code tls-exporter}'s keying material: 32 [RFC 9266 §2]. */
    static final int EXPORTER_BYTES = 32;

    /** TLS 1.3, the version {@code tls-exporter} is defined of. */
    static final String TLS13 = "TLSv1.3";

    /**
     * The JDK's export of keying material, {@code ExtendedSSLSession.exportKeyingMaterialData},
     * which JDK 25 and later have and JDK 17 and 21 do not: found by name, as the library is
     * compiled for release 17.
     */
    private static final @Nullable Method EXPORT = export();

    private Handshake() {}

    private static @Nullable Method export() {
        try {
            return ExtendedSSLSession.class.getMethod(
                    "exportKeyingMaterialData", String.class, byte[].class, int.class);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    /**
     * The connection {@code tcp} secured by TLS as {@code config} says, of the server named
     * {@code name}, its handshake done.
     *
     * @param tcp the TCP connection, connected
     * @param name the server's name, for SNI and the name check
     * @param config the TLS
     * @return the TLS socket
     * @throws TurborelException a connection failure if the handshake fails: the server's
     *     certificate not trusted, or not of the name
     */
    public static SSLSocket secured(Socket tcp, String name, TlsConfig config) throws TurborelException {
        try {
            Socket created = config.context().getSocketFactory().createSocket(tcp, name, tcp.getPort(), true);
            if (!(created instanceof SSLSocket socket)) {
                throw new IOException("the JDK made no TLS socket");
            }
            if (config.checksName()) {
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm(TlsConfig.CHECK_NAME);
                socket.setSSLParameters(parameters);
            }
            socket.startHandshake();
            return socket;
        } catch (IOException e) {
            closeQuietly(tcp);
            TurborelException failure =
                    Broken.connection("[CLIENT Connecting] the TLS handshake failed: " + e.getMessage());
            failure.initCause(e);
            throw failure;
        }
    }

    /**
     * What the handshake of {@code socket} gives a mechanism to bind to: the server's
     * certificate, and over TLS 1.3 the keying material of {@code tls-exporter} where the JDK
     * exports it, of the label RFC 9266 §2 gives, a zero-length context, 32 bytes.
     *
     * @param socket the TLS socket, its handshake done
     * @return the channel
     */
    public static TlsChannel channel(SSLSocket socket) {
        SSLSession session = socket.getSession();
        return new TlsChannel(certificate(session), exporter(session));
    }

    private static @Nullable Octets certificate(SSLSession session) {
        try {
            Certificate[] chain = session.getPeerCertificates();
            return chain.length == 0 ? null : Octets.of(chain[0].getEncoded());
        } catch (SSLPeerUnverifiedException | CertificateEncodingException none) {
            return null;
        }
    }

    private static @Nullable Octets exporter(SSLSession session) {
        if (EXPORT == null || !TLS13.equals(session.getProtocol()) || !(session instanceof ExtendedSSLSession)) {
            return null;
        }
        try {
            Object data = EXPORT.invoke(session, EXPORTER_LABEL, new byte[0], EXPORTER_BYTES);
            return data instanceof byte[] bytes ? Octets.of(bytes) : null;
        } catch (IllegalAccessException | InvocationTargetException none) {
            return null;
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // A socket given up is closed as far as the system lets it.
        }
    }
}
