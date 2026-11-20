package com.turborel.jdbc.testing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Test certificates, made as the tests run by the JDK's own keytool, so no key is ever committed
 * (RULES.md R-TEST-8): an authority, a server's certificate for {@code localhost} and {@code
 * 127.0.0.1}, and a client's for the user ANN, each written as PEM, its key as PKCS #8.
 */
public final class Certificates {
    private static final String PASSWORD = "test-only";
    private static final long KEYTOOL_WITHIN_SECONDS = 60;

    private final Path dir;

    private Certificates(Path dir) {
        this.dir = dir;
    }

    /**
     * Makes the certificates in a directory.
     *
     * @param dir where they go, made if it is not there
     * @return the certificates
     * @throws IOException if keytool fails
     */
    public static Certificates make(Path dir) throws IOException {
        Files.createDirectories(dir);
        Certificates made = new Certificates(dir);
        made.keytool(
                "-genkeypair",
                "-alias",
                "ca",
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-dname",
                "CN=turborel test authority",
                "-ext",
                "bc:c",
                "-validity",
                "2",
                "-keystore",
                "ca.p12");
        made.issued("server", "CN=localhost", "san=dns:localhost,ip:127.0.0.1");
        made.issued("client", "CN=ann", null);
        made.keytool("-exportcert", "-rfc", "-alias", "ca", "-keystore", "ca.p12", "-file", "ca.pem");
        made.pem("server");
        made.pem("client");
        return made;
    }

    /**
     * The authority's certificate, PEM.
     *
     * @return the authority's certificate
     */
    public Path authority() {
        return dir.resolve("ca.pem");
    }

    /**
     * The server's certificate chain, PEM, its own first.
     *
     * @return the server's certificate chain
     */
    public Path serverChain() {
        return dir.resolve("server-chain.pem");
    }

    /**
     * The server's private key, PKCS #8 PEM.
     *
     * @return the server's private key
     */
    public Path serverKey() {
        return dir.resolve("server-key.pem");
    }

    /**
     * The client's certificate chain, PEM, for the user ANN.
     *
     * @return the client's certificate chain
     */
    public Path clientChain() {
        return dir.resolve("client-chain.pem");
    }

    /**
     * The client's private key, PKCS #8 PEM.
     *
     * @return the client's private key
     */
    public Path clientKey() {
        return dir.resolve("client-key.pem");
    }

    private void issued(String name, String subject, String extension) throws IOException {
        keytool(
                "-genkeypair",
                "-alias",
                name,
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-dname",
                subject,
                "-validity",
                "2",
                "-keystore",
                name + ".p12");
        keytool("-certreq", "-alias", name, "-keystore", name + ".p12", "-file", name + ".csr");
        List<String> sign = new ArrayList<>(List.of(
                "-gencert",
                "-rfc",
                "-alias",
                "ca",
                "-keystore",
                "ca.p12",
                "-validity",
                "2",
                "-infile",
                name + ".csr",
                "-outfile",
                name + ".crt"));
        if (extension != null) {
            sign.addAll(List.of("-ext", extension));
        }
        keytool(sign.toArray(new String[0]));
    }

    private void pem(String name) throws IOException {
        String chain = Files.readString(dir.resolve(name + ".crt")) + Files.readString(authority());
        Files.writeString(dir.resolve(name + "-chain.pem"), chain);
        try (InputStream in = Files.newInputStream(dir.resolve(name + ".p12"))) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, PASSWORD.toCharArray());
            Key key = store.getKey(name, PASSWORD.toCharArray());
            String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(key.getEncoded());
            Files.writeString(
                    dir.resolve(name + "-key.pem"),
                    "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n");
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private void keytool(String... arguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(arguments));
        command.addAll(List.of("-storepass", PASSWORD, "-keypass", PASSWORD, "-storetype", "PKCS12", "-noprompt"));
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        try {
            byte[] said = process.getInputStream().readAllBytes();
            if (!process.waitFor(KEYTOOL_WITHIN_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException(
                        "keytool " + arguments[0] + " failed: " + new String(said, StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } finally {
            process.destroy();
        }
    }
}
