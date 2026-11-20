package com.turborel.jdbc.internal.tls;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Broken;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.jspecify.annotations.Nullable;

/**
 * The certificate authorities a client trusts ([CLIENT Connecting]): those of a PEM file, if one
 * is named, and the platform's if asked for; at least one. The platform's are those of the files
 * the environment's SSL_CERT_FILE and SSL_CERT_DIR name, if either is set, as Turborel's client
 * takes them; else the JDK's own trusted authorities, its {@code cacerts} or what
 * {@code javax.net.ssl.trustStore} names.
 */
public final class Roots {
    /** A split's limit that keeps every part, the empty ones too. */
    private static final int EVERY = -1;

    /** The variable naming a PEM file of the platform's authorities. */
    public static final String SSL_CERT_FILE = "SSL_CERT_FILE";

    /** The variable naming a directory of PEM files of the platform's authorities. */
    public static final String SSL_CERT_DIR = "SSL_CERT_DIR";

    /** The password of a store held in memory alone. */
    private static final char[] IN_MEMORY = new char[0];

    private Roots() {}

    /**
     * The authorities trusted.
     *
     * @param file a PEM file of authorities, or none
     * @param platform whether the platform's are trusted too
     * @param environment the environment's variables, by name
     * @return a store of them, at least one
     * @throws TurborelException a connection failure if a file cannot be read, or none is trusted
     */
    public static KeyStore of(@Nullable Path file, boolean platform, UnaryOperator<@Nullable String> environment)
            throws TurborelException {
        List<X509Certificate> trusted = new ArrayList<>();
        if (file != null) {
            trusted.addAll(Pem.certificates(file));
        }
        if (platform) {
            trusted.addAll(platform(environment));
        }
        if (trusted.isEmpty()) {
            throw Broken.connection(
                    "[CLIENT Connecting] no certificate authority is trusted: name a file of them, or the platform's");
        }
        return store(trusted);
    }

    /**
     * The platform's authorities, as turborel-client's rustls-native-certs loads them: of
     * SSL_CERT_FILE and SSL_CERT_DIR, a list of directories apart by the platform's separator, if
     * either is set, each file or directory that cannot be read passed over; else the JDK's
     * trusted ones. None at all fails, saying why.
     */
    private static List<X509Certificate> platform(UnaryOperator<@Nullable String> environment)
            throws TurborelException {
        String file = environment.apply(SSL_CERT_FILE);
        List<Path> dirs = new ArrayList<>();
        String dir = environment.apply(SSL_CERT_DIR);
        if (dir != null) {
            for (String each : dir.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), EVERY)) {
                if (!each.isEmpty()) {
                    dirs.add(Path.of(each));
                }
            }
        }
        if (file == null && dirs.isEmpty()) {
            return jdk();
        }
        List<X509Certificate> found = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (file != null) {
            loaded(Path.of(file), found, errors);
        }
        for (Path each : dirs) {
            directory(each, found, errors);
        }
        if (found.isEmpty()) {
            String why = errors.isEmpty() ? "they hold none" : String.join("; ", errors);
            throw Broken.connection("[CLIENT Connecting] cannot trust the platform's certificate authorities: " + why);
        }
        return found;
    }

    /** The certificates of a file, added to {@code found}; one that cannot be read noted in {@code errors}. */
    private static void loaded(Path file, List<X509Certificate> found, List<String> errors) {
        try {
            found.addAll(Pem.certificates(file));
        } catch (TurborelException failed) {
            errors.add(String.valueOf(failed.getMessage()));
        }
    }

    private static void directory(Path dir, List<X509Certificate> found, List<String> errors) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path each : files) {
                if (Files.isRegularFile(each)) {
                    readable(each, found);
                }
            }
        } catch (IOException e) {
            errors.add("cannot read " + dir + ": " + e.getMessage());
        }
    }

    /** The certificates of a file of a directory of authorities; a file of none is left out. */
    private static void readable(Path file, List<X509Certificate> found) {
        try {
            found.addAll(Pem.certificates(file));
        } catch (TurborelException none) {
            // A directory of authorities holds other files too, as their hashes' links.
        }
    }

    private static List<X509Certificate> jdk() throws TurborelException {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            List<X509Certificate> found = new ArrayList<>();
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager x509) {
                    found.addAll(List.of(x509.getAcceptedIssuers()));
                }
            }
            return found;
        } catch (GeneralSecurityException e) {
            throw Broken.connection("[CLIENT Connecting] cannot trust the platform's authorities: " + e.getMessage());
        }
    }

    private static KeyStore store(List<X509Certificate> trusted) throws TurborelException {
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, IN_MEMORY);
            int at = 0;
            for (X509Certificate each : trusted) {
                store.setCertificateEntry("authority-" + at++, each);
            }
            return store;
        } catch (GeneralSecurityException | IOException e) {
            throw Broken.connection("[CLIENT Connecting] cannot trust the authorities: " + e.getMessage());
        }
    }
}
