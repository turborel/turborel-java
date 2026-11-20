package com.turborel.jdbc.internal.tls;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.auth.TlsChannel;
import com.turborel.jdbc.internal.transport.Sockets;
import com.turborel.jdbc.testing.Certificates;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** PEM files, trusted authorities and TLS's handshake ([CLIENT Connecting]). */
class TlsTest {
    /** secp256r1's object identifier, 1.2.840.10045.3.1.7. */
    private static final byte[] SECP256R1 = {0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 3, 1, 7};

    private static final Duration WITHIN = Duration.ofSeconds(10);

    @TempDir
    static Path dir;

    private static Certificates certificates;

    @BeforeAll
    static void make() throws IOException {
        certificates = Certificates.make(dir.resolve("certs"));
    }

    private static Path pem(String name, String label, byte[] der) throws IOException {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der);
        return Files.writeString(
                dir.resolve(name),
                "junk before\n-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n");
    }

    private static Der.Read read(byte[] der, int tag) {
        Der.Read read = Der.expect(der, tag);
        assertNotNull(read);
        return read;
    }

    /** The body of a PKCS #8 PrivateKeyInfo's privateKey octet string [RFC 5208 §5]. */
    private static byte[] inner(byte[] pkcs8) {
        Der.Read info = Der.expect(pkcs8, Der.SEQUENCE);
        assertNotNull(info);
        Der.Read version = Der.expect(info.body(), 0x02);
        assertNotNull(version);
        Der.Read algorithm = Der.expect(version.rest(), Der.SEQUENCE);
        assertNotNull(algorithm);
        Der.Read key = Der.expect(algorithm.rest(), 0x04);
        assertNotNull(key);
        return key.body();
    }

    /**
     * Verifies: [CLIENT Connecting] a PEM file's certificates are read in order; a file that holds
     * none, or is not there, fails as a connection failure (SQLSTATE 08006).
     */
    @Test
    void certificatesAreRead() throws TurborelException, IOException {
        assertEquals(2, Pem.certificates(certificates.serverChain()).size());
        assertEquals(
                "CN=localhost",
                Pem.certificates(certificates.serverChain())
                        .get(0)
                        .getSubjectX500Principal()
                        .getName());
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, () -> Pem.certificates(certificates.serverKey()))
                        .getSQLState());
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, () -> Pem.certificates(dir.resolve("absent.pem")))
                        .getSQLState());
        Path bad = pem("bad.pem", "CERTIFICATE", new byte[] {1, 2, 3});
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, () -> Pem.certificates(bad))
                        .getSQLState());
    }

    /**
     * Verifies: [CLIENT Connecting] a private key is read as PKCS #8, as PKCS #1 RSA, or as SEC 1
     * EC of a named curve; a file of none, an EC key of no curve, or a key of no algorithm the JDK
     * reads fails.
     */
    @Test
    void privateKeysOfEachForm() throws TurborelException, IOException, GeneralSecurityException {
        assertEquals("EC", Pem.privateKey(certificates.clientKey()).getAlgorithm());

        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        PrivateKey rsaKey = rsa.generateKeyPair().getPrivate();
        PrivateKey readRsa = Pem.privateKey(pem("rsa.pem", "RSA PRIVATE KEY", inner(rsaKey.getEncoded())));
        assertEquals(
                ((RSAPrivateCrtKey) rsaKey).getPrivateExponent(), ((RSAPrivateCrtKey) readRsa).getPrivateExponent());

        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(new ECGenParameterSpec("secp256r1"));
        ECPrivateKey ecKey = (ECPrivateKey) ec.generateKeyPair().getPrivate();
        byte[] secret = read(
                        read(read(inner(ecKey.getEncoded()), Der.SEQUENCE).body(), 0x02)
                                .rest(),
                        0x04)
                .body();
        byte[] sec1 = Der.tlv(
                Der.SEQUENCE,
                concat(
                        Der.tlv(0x02, new byte[] {1}),
                        Der.tlv(0x04, secret),
                        Der.tlv(0xA0, Der.tlv(Der.OID, SECP256R1))));
        assertEquals(ecKey.getS(), ((ECPrivateKey) Pem.privateKey(pem("ec.pem", "EC PRIVATE KEY", sec1))).getS());

        byte[] noCurve = Der.tlv(Der.SEQUENCE, concat(Der.tlv(0x02, new byte[] {1}), Der.tlv(0x04, secret)));
        for (byte[] cut : new byte[][] {
            Der.tlv(0x04, secret),
            Der.tlv(Der.SEQUENCE, Der.tlv(0x04, secret)),
            Der.tlv(Der.SEQUENCE, Der.tlv(0x02, new byte[] {1})),
        }) {
            Path broken = pem("broken.pem", "EC PRIVATE KEY", cut);
            assertEquals(
                    "08006",
                    assertThrows(TurborelException.class, () -> Pem.privateKey(broken))
                            .getSQLState());
        }
        Path curveless = pem("curveless.pem", "EC PRIVATE KEY", noCurve);
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, () -> Pem.privateKey(curveless))
                        .getSQLState());
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, () -> Pem.privateKey(certificates.authority()))
                        .getSQLState());
        Path junk = pem("junk.pem", "PRIVATE KEY", Der.tlv(Der.SEQUENCE, new byte[] {1}));
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, () -> Pem.privateKey(junk))
                        .getSQLState());
    }

    private static byte[] concat(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /**
     * Verifies: [CLIENT Connecting] DER is read by tag and length, short or long form; a wrong
     * tag, a cut body or a length of too many bytes is none; a long body is written in the long
     * form [X.690 §8.1.3].
     */
    @Test
    void derTagsAndLengths() {
        byte[] body = new byte[300];
        byte[] tlv = Der.tlv(Der.SEQUENCE, body);
        assertArrayEquals(new byte[] {0x30, (byte) 0x82, 1, 0x2c}, java.util.Arrays.copyOf(tlv, 4));
        Der.Read read = Der.expect(concat(tlv, new byte[] {9}), Der.SEQUENCE);
        assertNotNull(read);
        assertEquals(300, read.body().length);
        assertArrayEquals(new byte[] {9}, read.rest());
        assertNull(Der.expect(tlv, Der.OID));
        assertNull(Der.expect(new byte[] {0x30}, Der.SEQUENCE));
        assertNull(Der.expect(new byte[] {0x30, 5, 1}, Der.SEQUENCE));
        assertNull(Der.expect(new byte[] {0x30, (byte) 0x80}, Der.SEQUENCE));
        assertNull(Der.expect(new byte[] {0x30, (byte) 0x84, 0, 0, 0, 1, 0}, Der.SEQUENCE));
        assertNull(Der.expect(new byte[] {0x30, (byte) 0x82, 1}, Der.SEQUENCE));
    }

    /**
     * Verifies: [CLIENT Connecting] the authorities trusted are a file's, the platform's, or both;
     * the platform's are SSL_CERT_FILE's and SSL_CERT_DIR's where they are set, else the JDK's own
     * (RULES.md); none trusted fails.
     */
    @Test
    void rootsOfFileAndPlatform() throws TurborelException, IOException, GeneralSecurityException {
        Map<String, String> none = Map.of();
        assertEquals(
                1,
                Collections.list(Roots.of(certificates.authority(), false, none::get)
                                .aliases())
                        .size());
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, () -> Roots.of(null, false, none::get))
                        .getSQLState());
        KeyStore jdk = Roots.of(null, true, none::get);
        assertTrue(jdk.size() > 10, "the JDK's own authorities");
        KeyStore empty = Roots.of(null, true, Map.of(Roots.SSL_CERT_FILE, "", Roots.SSL_CERT_DIR, "")::get);
        assertEquals(jdk.size(), empty.size(), "an empty variable is unset");
        Map<String, String> file =
                Map.of(Roots.SSL_CERT_FILE, certificates.serverChain().toString());
        assertEquals(3, Roots.of(certificates.authority(), true, file::get).size());
        Path certs = Files.createDirectories(dir.resolve("certdir"));
        Files.copy(certificates.authority(), certs.resolve("ca.pem"));
        Files.writeString(certs.resolve("README"), "not a certificate");
        Files.createDirectories(certs.resolve("nested"));
        Map<String, String> directory = Map.of(Roots.SSL_CERT_DIR, certs.toString());
        assertEquals(1, Roots.of(null, true, directory::get).size());
        Map<String, String> absent =
                Map.of(Roots.SSL_CERT_DIR, dir.resolve("absent").toString());
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, () -> Roots.of(null, true, absent::get))
                        .getSQLState());
    }

    /** A TLS server on loopback, presenting the server's certificate, and what it was given. */
    private static SSLServerSocket server(boolean wantClient) throws TurborelException, IOException {
        TlsConfig config =
                TlsConfig.presenting(certificates.authority(), certificates.serverChain(), certificates.serverKey());
        java.net.ServerSocket made =
                config.context().getServerSocketFactory().createServerSocket(0, 1, InetAddress.getLoopbackAddress());
        if (!(made instanceof SSLServerSocket server)) {
            made.close();
            throw new IOException("the JDK made no TLS server socket");
        }
        server.setNeedClientAuth(wantClient);
        return server;
    }

    private static Thread accepting(SSLServerSocket server, AtomicReference<String> peer) {
        Thread thread = new Thread(() -> {
            try (SSLSocket socket = (SSLSocket) server.accept()) {
                socket.setSoTimeout(10_000);
                socket.startHandshake();
                if (socket.getNeedClientAuth()) {
                    peer.set(socket.getSession().getPeerPrincipal().getName());
                }
                socket.getOutputStream().write(7);
                socket.getOutputStream().flush();
                socket.getInputStream().read();
            } catch (IOException failed) {
                peer.set("failed: " + failed.getMessage());
            }
        });
        thread.start();
        return thread;
    }

    /**
     * Verifies: [CLIENT Connecting] TLS checks the server's certificate against the authorities
     * trusted and against its name, unless the name check is off; the handshake gives the
     * server's certificate to bind to, and tls-exporter's keying material where the JDK exports
     * it; a client certificate is presented as configured.
     */
    @Test
    void theHandshakeChecksAndBinds() throws TurborelException, IOException, InterruptedException {
        try (SSLServerSocket server = server(true)) {
            InetSocketAddress at = new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort());
            AtomicReference<String> peer = new AtomicReference<>();
            Thread thread = accepting(server, peer);
            TlsConfig presenting = TlsConfig.presenting(
                    certificates.authority(), certificates.clientChain(), certificates.clientKey());
            assertTrue(presenting.checksName());
            try (SSLSocket socket = Handshake.secured(Sockets.connected(at, WITHIN), "localhost", presenting)) {
                assertEquals(7, socket.getInputStream().read());
                TlsChannel channel = Handshake.channel(socket);
                assertNotNull(channel.certificate());
                assertEquals(
                        Runtime.version().feature() >= 25,
                        channel.exporter() != null,
                        "the JDK exports keying material");
                socket.getOutputStream().write(0);
            }
            thread.join();
            assertEquals("CN=ann", peer.get());
        }
    }

    /**
     * Verifies: [CLIENT Connecting] a server certificate not of the name, or of an authority not
     * trusted, fails the handshake (SQLSTATE 08006); with the name check off, a certificate of
     * another name is taken.
     */
    @Test
    void aWrongNameOrAuthorityFails()
            throws TurborelException, IOException, InterruptedException, GeneralSecurityException {
        try (SSLServerSocket server = server(false)) {
            InetSocketAddress at = new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort());
            TlsConfig trusting = TlsConfig.trusting(certificates.authority());
            Thread first = accepting(server, new AtomicReference<>());
            TurborelException wrong = assertThrows(
                    TurborelException.class,
                    () -> Handshake.secured(Sockets.connected(at, WITHIN), "db.example", trusting));
            assertEquals("08006", wrong.getSQLState());
            first.join();

            Thread second = accepting(server, new AtomicReference<>());
            TlsConfig other = TlsConfig.trusting(certificates.clientChain().resolveSibling("client.crt"));
            assertThrows(
                    TurborelException.class,
                    () -> Handshake.secured(Sockets.connected(at, WITHIN), "localhost", other));
            second.join();

            Thread third = accepting(server, new AtomicReference<>());
            javax.net.ssl.TrustManagerFactory factory = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            factory.init(Roots.of(certificates.authority(), false, Map.<String, String>of()::get));
            TrustManager[] managers = factory.getTrustManagers();
            assertTrue(managers[0] instanceof X509TrustManager);
            TlsConfig unchecked = TlsConfig.of(managers, null, false);
            assertFalse(unchecked.checksName());
            try (SSLSocket socket = Handshake.secured(Sockets.connected(at, WITHIN), "db.example", unchecked)) {
                assertEquals(7, socket.getInputStream().read());
                socket.getOutputStream().write(0);
            }
            third.join();
        }
    }
}
