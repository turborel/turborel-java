package com.turborel.jdbc.internal.settings;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.auth.Trust;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.internal.session.Connect;
import com.turborel.jdbc.internal.session.Connection;
import com.turborel.jdbc.internal.session.Offer;
import com.turborel.jdbc.internal.session.Outcome;
import com.turborel.jdbc.internal.transport.Target;
import com.turborel.jdbc.internal.transport.Timeouts;
import com.turborel.jdbc.testing.Certificates;
import com.turborel.jdbc.testing.LiveServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Connecting by settings to a live server ([CLIENT Connecting by settings]): several servers, the
 * role asked, TLS by mode, the password file, channel binding, the SCRAM key cache and a client
 * certificate, as Turborel's {@code server/test/client/failover.rs}, {@code tls_modes.rs} and
 * {@code tls_identity.rs} test them.
 */
@EnabledIfEnvironmentVariable(named = LiveServer.VARIABLE, matches = ".+")
class LiveSettingsTest {
    private static final String AT = LiveServer.HOST + ":" + LiveServer.PORT;

    @TempDir
    Path scratch;

    /** The settings of the URI {@code uri}, read with no environment. */
    private static Settings settings(String uri) throws TurborelException {
        return Settings.resolveIn(Options.fromUri(uri), name -> null);
    }

    /** {@code text} percent-encoded, as a URI's value. */
    private static String encoded(Object text) {
        return URLEncoder.encode(text.toString(), StandardCharsets.UTF_8);
    }

    /** A port on loopback no server listens at: one the system gave, then gave up. */
    private static int dead() throws IOException {
        try (ServerSocket gone = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return gone.getLocalPort();
        }
    }

    private static List<List<Value>> rows(Connection c, String sql) throws TurborelException {
        return ((Outcome.Rows) c.statements().query(sql)).rows();
    }

    /** Whether TLS secures the connection {@code settings} make. */
    private static boolean secure(Settings settings) throws TurborelException {
        try (Connection c = settings.connect()) {
            Target target = c.target();
            return target != null && target.tls();
        }
    }

    /**
     * Verifies: [CLIENT Several servers] of a list of servers, the first that can be reached is
     * connected to, past an address no server listens at; the connection remembers where it
     * reached; none reached, the failure (SQLSTATE 08001) names each address and why.
     */
    @Test
    void aDeadAddressIsPassedForTheNext() throws TurborelException, IOException {
        LiveServer.get();
        int first = dead();
        int second = dead();
        try (Connection c = settings("turborel://ann@127.0.0.1:" + first + "," + AT + "/?tls_mode=disable")
                .connect()) {
            assertEquals(new Target.Tcp(LiveServer.address()), c.target());
            assertEquals(LiveServer.OWNER, c.authorization());
            c.terminate();
        }
        TurborelException none = assertThrows(
                TurborelException.class,
                () -> settings("turborel://ann@127.0.0.1:" + first + ",127.0.0.1:" + second
                                + "/?tls_mode=disable&connect_timeout=2")
                        .connect());
        assertEquals("08001", none.getSQLState());
        String message = String.valueOf(none.getMessage());
        assertTrue(
                message.contains("127.0.0.1 (127.0.0.1:" + first + ")")
                        && message.contains("127.0.0.1 (127.0.0.1:" + second + ")"),
                message);
    }

    /**
     * Verifies: [CLIENT Several servers] a name is tried at each address it resolves to, each in
     * turn, so {@code localhost} reaches a server listening at 127.0.0.1 alone; of a name whose
     * addresses all fail, each is named.
     */
    @Test
    void eachAddressANameResolvesToIsTried() throws TurborelException, IOException {
        LiveServer.get();
        try (Connection c = settings("turborel://ann@localhost:" + LiveServer.PORT + "/?tls_mode=disable")
                .connect()) {
            assertEquals(new Target.Tcp(LiveServer.address()), c.target());
            c.terminate();
        }
        int port = dead();
        TurborelException none = assertThrows(
                TurborelException.class,
                () -> settings("turborel://ann@localhost:" + port + "/?tls_mode=disable&connect_timeout=2")
                        .connect());
        InetAddress[] addresses = InetAddress.getAllByName("localhost");
        assertEquals(addresses.length == 1 ? "08006" : "08001", none.getSQLState(), none.getMessage());
        for (InetAddress each : addresses) {
            if (addresses.length > 1) {
                String named = "localhost (" + Failover.text(new InetSocketAddress(each, port)) + ")";
                assertTrue(String.valueOf(none.getMessage()).contains(named), none.getMessage());
            }
        }
    }

    /**
     * Verifies: [CLIENT Several servers] a server alone takes changes, so its role is read-write,
     * told by SERVER_READ_ONLY, or of a connection not told it read from REPLICAS, which a server
     * alone has not, the session's autocommit kept; settings asking for a read-write server keep
     * it, its session in no transaction; asking for a read-only one leave it (SQLSTATE 08001), and
     * of a list of no other are refused.
     */
    @Test
    void aServerOfTheWrongRoleIsLeft() throws TurborelException, IOException {
        LiveServer.get();
        try (Connection told = Connect.tcp(LiveServer.HOST, LiveServer.PORT, "ann", new Trust())) {
            assertEquals("NO", told.setting(Role.SERVER_READ_ONLY));
            assertEquals(Role.READ_WRITE, Role.of(told));
            assertFalse(told.inTransaction());
            told.terminate();
        }
        try (Connection asked =
                Connect.to(new Target.Tcp(LiveServer.address()), "ann", new Trust(), Timeouts.DEFAULT, Offer.none())) {
            assertNull(asked.setting(Role.SERVER_READ_ONLY));
            asked.setAutocommit(false);
            assertEquals(Role.READ_WRITE, Role.of(asked));
            assertFalse(asked.autocommit(), "the session's autocommit kept");
            assertFalse(asked.inTransaction(), "the query its own transaction");
            asked.terminate();
        }
        try (Connection kept = settings("turborel://ann@" + AT + "/?tls_mode=disable&target=read-write")
                .connect()) {
            assertFalse(kept.inTransaction());
            assertEquals(1, rows(kept, "VALUES 1").size());
            kept.terminate();
        }
        TurborelException left = assertThrows(
                TurborelException.class,
                () -> settings("turborel://ann@" + AT + "/?tls_mode=disable&target=read-only")
                        .connect());
        assertTrue(
                String.valueOf(left.getMessage()).contains("the server is read-write, and read-only is asked"),
                left.getMessage());
        assertEquals("08001", left.getSQLState());
        TurborelException both = assertThrows(
                TurborelException.class,
                () -> settings("turborel://ann@" + AT + ",localhost:" + LiveServer.PORT
                                + "/?tls_mode=disable&target=read-only")
                        .connect());
        assertEquals("08001", both.getSQLState());
    }

    /**
     * Verifies: [CLIENT Several servers] servers ordered at random are each connected to as any
     * is; every connection reaches one of them.
     */
    @Test
    void serversOrderedAtRandomAreEachReached() throws TurborelException, IOException {
        LiveServer.get();
        Settings s = settings("turborel://ann@127.0.0.1:" + LiveServer.PORT + ",[::ffff:127.0.0.1]:" + LiveServer.PORT
                + "/?tls_mode=disable&load_balance=random");
        for (int i = 0; i < 8; i++) {
            try (Connection c = s.connect()) {
                assertEquals(new Target.Tcp(LiveServer.address()), c.target());
            }
        }
    }

    /**
     * Verifies: [CLIENT TLS by mode] to a server offering TLS, {@code prefer} and {@code require}
     * connect over it, its certificate unchecked; {@code verify-full} over it once the certificate
     * is of an authority trusted and of the name asked, the host's by default, refusing another
     * name; {@code verify-ca} takes another name, but not no authority; {@code disable} connects in
     * the clear.
     */
    @Test
    void eachTlsModeChecksAsItSays() throws TurborelException, IOException {
        LiveServer server = LiveServer.get();
        String roots = encoded(server.certificates().authority());
        assertTrue(secure(settings("turborel://ann@" + AT + "/?tls_mode=prefer")));
        assertTrue(secure(settings("turborel://ann@" + AT + "/?tls_mode=require")));
        assertFalse(secure(settings("turborel://ann@" + AT + "/?tls_mode=disable")));
        String full = "turborel://ann@" + AT + "/?tls_mode=verify-full&tls_roots=" + roots;
        assertTrue(secure(settings(full + "&server_name=localhost")));
        assertTrue(secure(settings(full)), "the host's name, 127.0.0.1, which the certificate names");
        TurborelException named = assertThrows(
                TurborelException.class,
                () -> settings(full + "&server_name=elsewhere").connect());
        assertTrue(String.valueOf(named.getMessage()).contains("handshake"), named.getMessage());
        assertTrue(secure(settings(
                "turborel://ann@" + AT + "/?tls_mode=verify-ca&tls_roots=" + roots + "&server_name=elsewhere")));
        TurborelException none = assertThrows(
                TurborelException.class,
                () -> settings("turborel://ann@" + AT + "/?tls_mode=verify-ca").connect());
        assertTrue(
                String.valueOf(none.getMessage()).contains("no certificate authority is trusted"), none.getMessage());
    }

    /**
     * Verifies: [CLIENT TLS by mode] a password from the password file proves the user by SCRAM over
     * TLS, its channel bound where {@code channel_binding=require} asks; a reconnection by the same
     * settings takes its keys from their cache ([CLIENT The SCRAM key cache]); binding required in
     * the clear, or of trust, is refused before any connection (SQLSTATE 28000).
     */
    @Test
    void aPasswordFileAndBoundScramAndTheKeyCache() throws TurborelException, IOException {
        LiveServer server = LiveServer.get();
        Path passfile = scratch.resolve("pass");
        Files.writeString(passfile, "# servers\n" + AT + ":ann:ann\\'s\n", StandardCharsets.UTF_8);
        Settings s = settings("turborel://ann@" + AT + "/?tls_mode=verify-full&tls_roots="
                + encoded(server.certificates().authority())
                + "&server_name=localhost&channel_binding=require&passfile=" + encoded(passfile));
        try (Connection c = s.connect()) {
            assertEquals(LiveServer.OWNER, c.authorization());
            c.terminate();
        }
        assertArrayEquals(new long[] {0, 1}, s.keyCache().lookups());
        try (Connection c = s.connect()) {
            assertEquals(LiveServer.OWNER, c.authorization());
            c.terminate();
        }
        assertArrayEquals(new long[] {1, 1}, s.keyCache().lookups(), "the keys were the cache's");
        TurborelException clear = assertThrows(
                TurborelException.class,
                () -> settings("turborel://ann:x@" + AT + "/?tls_mode=disable&channel_binding=require")
                        .connect());
        assertEquals("28000", clear.getSQLState());
        TurborelException trusted = assertThrows(
                TurborelException.class,
                () -> settings("turborel://ann@" + AT + "/?tls_mode=require&channel_binding=require")
                        .connect());
        assertEquals("28000", trusted.getSQLState());
        TurborelException inTheClear = assertThrows(
                TurborelException.class,
                () -> settings("turborel://ann:x@" + AT + "/?tls_mode=disable").connect());
        assertEquals("28000", inTheClear.getSQLState(), "a secret mechanism over plain TCP");
    }

    /**
     * Verifies: [CLIENT The password file] a Unix socket named in a URI, percent-encoded, is
     * reached with no TLS, and its password looked up by its path and the default port, proved by
     * SCRAM; settings named by a service file's section connect as it says ([CLIENT The service
     * file]); the program's name is sent as the connection attribute {@code application} ([CLIENT
     * Where settings come from]).
     */
    @Test
    void aUnixSocketAServiceAndTheApplicationName() throws TurborelException, IOException {
        LiveServer server = LiveServer.get();
        Path socket = server.socket();
        Path passfile = scratch.resolve("pass");
        Files.writeString(passfile, socket + ":" + Settings.DEFAULT_PORT + ":ann:ann\\'s\n", StandardCharsets.UTF_8);
        Settings local =
                settings("turborel://ann@" + encoded(socket) + "/?tls_mode=require&passfile=" + encoded(passfile));
        try (Connection c = local.connect()) {
            assertInstanceOf(Target.Local.class, c.target());
            assertEquals(LiveServer.OWNER, c.authorization());
            c.terminate();
        }
        assertArrayEquals(new long[] {0, 1}, local.keyCache().lookups(), "proved by SCRAM");
        Path services = scratch.resolve("services.conf");
        String name = "settings-test-" + ProcessHandle.current().pid();
        Files.writeString(
                services,
                "[live]\nhost = " + AT + "\nuser = ann\ntls_mode = disable\napplication_name = " + name + "\n",
                StandardCharsets.UTF_8);
        Settings named = Settings.resolveIn(
                new Options().set(Options.SERVICE, "live").set(Options.SERVICE_FILE, services.toString()), v -> null);
        try (Connection c = named.connect()) {
            List<List<Value>> found = rows(
                    c,
                    "SELECT ATTRIBUTE_VALUE FROM INFORMATION_SCHEMA.CONNECTION_ATTRIBUTES"
                            + " WHERE ATTRIBUTE_NAME = 'application' AND ATTRIBUTE_VALUE = '" + name + "'");
            assertEquals(1, found.size(), found::toString);
            c.statements().query("COMMIT");
            c.terminate();
        }
    }

    /**
     * Verifies: [CLIENT TLS by mode] settings naming a certificate and its key present them, and
     * the server proves the user its common name names; a certificate named without its key, or a
     * file that cannot be read, is a connection failure that says so; {@code verify-ca} refuses a
     * server whose authority is not trusted, whatever its name.
     */
    @Test
    void aClientCertificateIsPresentedBySettings() throws TurborelException, IOException {
        LiveServer server = LiveServer.get();
        Certificates ann = server.certificates();
        String base = "turborel://ann@" + AT + "/?tls_mode=require&tls_certificate=" + encoded(ann.clientChain());
        try (Connection proved =
                settings(base + "&tls_key=" + encoded(ann.clientKey())).connect()) {
            assertEquals(LiveServer.OWNER, proved.authorization());
            proved.terminate();
        }
        TurborelException keyless =
                assertThrows(TurborelException.class, () -> settings(base).connect());
        assertTrue(
                String.valueOf(keyless.getMessage()).contains("presented with its key, and none is named"),
                keyless.getMessage());
        String missing = encoded(scratch.resolve("none.pem"));
        assertThrows(
                TurborelException.class,
                () -> settings("turborel://ann@" + AT + "/?tls_mode=require&tls_certificate=" + missing + "&tls_key="
                                + encoded(ann.clientKey()))
                        .connect());
        assertThrows(
                TurborelException.class,
                () -> settings(base + "&tls_key=" + missing).connect());
        Certificates other = Certificates.make(scratch.resolve("other"));
        TurborelException untrusted = assertThrows(
                TurborelException.class,
                () -> settings("turborel://ann@" + AT + "/?tls_mode=verify-ca&tls_roots=" + encoded(other.authority())
                                + "&server_name=localhost")
                        .connect());
        assertTrue(String.valueOf(untrusted.getMessage()).contains("handshake"), untrusted.getMessage());
    }
}
