package com.turborel.jdbc.internal.tls;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Broken;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.jspecify.annotations.Nullable;

/**
 * A client's TLS ([CLIENT Connecting]): the authorities it trusts, the certificate it presents if
 * any, and whether it checks the server's certificate is of the name connected to. The JDK's own
 * JSSE does the work (RULES.md D1).
 */
public final class TlsConfig {
    /** The TLS protocol the context is made for, which negotiates TLS 1.3 or 1.2. */
    private static final String PROTOCOL = "TLS";

    /** The identification JSSE checks a server's certificate's name by. */
    static final String CHECK_NAME = "HTTPS";

    /** The alias of the client's key in its key store. */
    private static final String IDENTITY = "identity";

    /** The password of the key store held in memory alone. */
    private static final char[] IN_MEMORY = new char[0];

    private final SSLContext context;
    private final boolean checkName;

    private TlsConfig(SSLContext context, boolean checkName) {
        this.context = context;
        this.checkName = checkName;
    }

    /**
     * A TLS that trusts the certificates of a PEM file, and only them, and checks the server's
     * name, as Turborel's {@code tls::trusting} does.
     *
     * @param roots the PEM file of authorities
     * @return the configuration
     * @throws TurborelException a connection failure if the file cannot be read or holds none
     */
    public static TlsConfig trusting(Path roots) throws TurborelException {
        return of(Roots.of(roots, false, System::getenv), null);
    }

    /**
     * A TLS that trusts the certificates of a PEM file and presents a certificate chain and key,
     * its own first, for the mechanism {@code certificate}, as Turborel's {@code tls::presenting}.
     *
     * @param roots the PEM file of authorities
     * @param chain the PEM file of the client's certificate chain
     * @param key the PEM file of its private key
     * @return the configuration
     * @throws TurborelException a connection failure if a file cannot be read, or the key does not
     *     fit
     */
    public static TlsConfig presenting(Path roots, Path chain, Path key) throws TurborelException {
        return of(Roots.of(roots, false, System::getenv), new Identity(Pem.certificates(chain), Pem.privateKey(key)));
    }

    /**
     * A TLS that trusts the authorities of a store, presents an identity if one is given, and
     * checks the server's name.
     *
     * @param trusted the authorities trusted
     * @param identity the client's certificate chain and key, or none
     * @return the configuration
     * @throws TurborelException a connection failure if the JDK cannot make it
     */
    public static TlsConfig of(KeyStore trusted, @Nullable Identity identity) throws TurborelException {
        try {
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(trusted);
            return of(trust.getTrustManagers(), identity, true);
        } catch (GeneralSecurityException e) {
            throw Broken.connection("[CLIENT Connecting] cannot make TLS: " + e.getMessage());
        }
    }

    /**
     * A TLS of the trust managers given.
     *
     * @param trust what checks the server's certificate
     * @param identity the client's certificate chain and key, or none
     * @param checkName whether the server's certificate is checked to be of its name
     * @return the configuration
     * @throws TurborelException a connection failure if the JDK cannot make it
     */
    public static TlsConfig of(TrustManager[] trust, @Nullable Identity identity, boolean checkName)
            throws TurborelException {
        try {
            SSLContext context = SSLContext.getInstance(PROTOCOL);
            context.init(identity == null ? null : identity.managers(), trust.clone(), null);
            return new TlsConfig(context, checkName);
        } catch (GeneralSecurityException e) {
            throw Broken.connection("[CLIENT Connecting] cannot make TLS: " + e.getMessage());
        }
    }

    /**
     * What makes its sockets.
     *
     * @return the context
     */
    SSLContext context() {
        return context;
    }

    /**
     * Whether it checks the server's certificate is of the name connected to.
     *
     * @return whether it checks the name
     */
    public boolean checksName() {
        return checkName;
    }

    /**
     * A client's certificate chain, its own first, and its private key.
     *
     * @param chain the chain
     * @param key the private key
     */
    public record Identity(List<X509Certificate> chain, PrivateKey key) {
        /**
         * An identity of the parts given.
         *
         * @param chain the chain, copied
         * @param key the private key
         */
        public Identity {
            chain = List.copyOf(chain);
        }

        javax.net.ssl.KeyManager[] managers() throws GeneralSecurityException {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            try {
                store.load(null, IN_MEMORY);
            } catch (java.io.IOException e) {
                throw new GeneralSecurityException(e.getMessage(), e);
            }
            store.setKeyEntry(IDENTITY, key, IN_MEMORY, chain.toArray(new Certificate[0]));
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, IN_MEMORY);
            return keys.getKeyManagers();
        }
    }
}
