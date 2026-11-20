package com.turborel.jdbc.internal.tls;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Broken;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PEM files ([CLIENT Connecting]): the certificates of a file of authorities or of a chain, and a
 * private key, PKCS #8, PKCS #1 of RSA or SEC 1 of EC, as Turborel's client reads them.
 */
public final class Pem {
    /** A PEM block: its label and its base 64. */
    private static final Pattern BLOCK =
            Pattern.compile("-----BEGIN ([A-Z0-9 ]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----");

    /** The group of a block's label. */
    private static final int LABEL = 1;

    /** The group of a block's base 64. */
    private static final int BODY = 2;

    /** The label of a certificate. */
    private static final String CERTIFICATE = "CERTIFICATE";

    /** The label of a PKCS #8 private key. */
    private static final String PKCS8 = "PRIVATE KEY";

    /** The label of a PKCS #1 RSA private key. */
    private static final String RSA = "RSA PRIVATE KEY";

    /** The label of a SEC 1 EC private key. */
    private static final String EC = "EC PRIVATE KEY";

    /** The algorithms a PKCS #8 key may be of, tried in turn. */
    private static final String[] ALGORITHMS = {"RSA", "EC", "Ed25519", "Ed448", "RSASSA-PSS"};

    private Pem() {}

    /**
     * The certificates of a PEM file.
     *
     * @param path the file
     * @return its certificates, in order; at least one
     * @throws TurborelException a connection failure if it cannot be read or holds none
     */
    public static List<X509Certificate> certificates(Path path) throws TurborelException {
        List<X509Certificate> found = new ArrayList<>();
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            for (byte[] der : blocks(path, CERTIFICATE)) {
                found.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der)));
            }
        } catch (GeneralSecurityException e) {
            throw unreadable(path, String.valueOf(e.getMessage()));
        }
        if (found.isEmpty()) {
            throw Broken.connection("[CLIENT Connecting] " + path + " holds no certificate");
        }
        return found;
    }

    /**
     * The private key of a PEM file.
     *
     * @param path the file
     * @return the key
     * @throws TurborelException a connection failure if it cannot be read or holds no key
     */
    public static PrivateKey privateKey(Path path) throws TurborelException {
        String text = text(path);
        Matcher m = BLOCK.matcher(text);
        while (m.find()) {
            byte[] der = Base64.getMimeDecoder().decode(m.group(BODY));
            String label = m.group(LABEL);
            if (label.equals(PKCS8)) {
                return pkcs8(path, der);
            }
            if (label.equals(RSA)) {
                return pkcs8(path, Der.rsaToPkcs8(der));
            }
            if (label.equals(EC)) {
                return pkcs8(path, Der.ecToPkcs8(der));
            }
        }
        throw Broken.connection("[CLIENT Connecting] " + path + " holds no private key");
    }

    private static PrivateKey pkcs8(Path path, byte[] der) throws TurborelException {
        for (String algorithm : ALGORITHMS) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (GeneralSecurityException notThisOne) {
                // Tried as the next algorithm; the file's key is of one of them.
            }
        }
        throw unreadable(path, "its private key is of no algorithm the JDK reads");
    }

    private static List<byte[]> blocks(Path path, String label) throws TurborelException {
        List<byte[]> found = new ArrayList<>();
        Matcher m = BLOCK.matcher(text(path));
        while (m.find()) {
            if (m.group(LABEL).equals(label)) {
                found.add(Base64.getMimeDecoder().decode(m.group(BODY)));
            }
        }
        return found;
    }

    private static String text(Path path) throws TurborelException {
        try {
            return Files.readString(path, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw unreadable(path, String.valueOf(e.getMessage()));
        }
    }

    private static TurborelException unreadable(Path path, String why) {
        return Broken.connection("[CLIENT Connecting] cannot read " + path + ": " + why);
    }
}
