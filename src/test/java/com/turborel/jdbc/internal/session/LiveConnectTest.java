package com.turborel.jdbc.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.auth.Certificate;
import com.turborel.jdbc.internal.auth.ChannelBinding;
import com.turborel.jdbc.internal.auth.KeyCache;
import com.turborel.jdbc.internal.auth.Password;
import com.turborel.jdbc.internal.auth.Scram;
import com.turborel.jdbc.internal.auth.Trust;
import com.turborel.jdbc.internal.tls.Handshake;
import com.turborel.jdbc.internal.tls.TlsConfig;
import com.turborel.jdbc.internal.transport.Opened;
import com.turborel.jdbc.internal.transport.SocketDuplex;
import com.turborel.jdbc.internal.transport.Sockets;
import com.turborel.jdbc.internal.transport.Target;
import com.turborel.jdbc.internal.transport.Timeouts;
import com.turborel.jdbc.testing.LiveServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Logging in to a live server by every transport and mechanism ([CLIENT Connecting]). */
@EnabledIfEnvironmentVariable(named = LiveServer.VARIABLE, matches = ".+")
class LiveConnectTest {
    private static final InetSocketAddress ADDRESS = LiveServer.address();

    private static TlsConfig verifying(LiveServer server) throws TurborelException {
        return TlsConfig.trusting(server.certificates().authority());
    }

    private static Connection overTls(LiveServer server, com.turborel.jdbc.internal.auth.Mechanism mechanism)
            throws TurborelException {
        return Connect.tls(
                LiveServer.HOST,
                LiveServer.PORT,
                "localhost",
                verifying(server),
                new Login(LiveServer.OWNER, mechanism));
    }

    /**
     * Verifies: [CLIENT Connecting] trust over TCP, on the port given, and over a Unix socket, the
     * server's product and the session's authorization identifier as Ready told them; Terminate
     * ends the session ([PROTOCOL Terminate 'X']).
     */
    @Test
    void trustOverTcpAndUnixSocket() throws TurborelException, IOException {
        LiveServer server = LiveServer.get();
        Connection tcp = Connect.tcp(LiveServer.HOST, LiveServer.PORT, "ann", new Trust());
        assertEquals(LiveServer.OWNER, tcp.authorization());
        assertTrue(tcp.server().startsWith("turborel "), tcp.server());
        assertFalse(tcp.isLost());
        tcp.terminate();
        assertTrue(tcp.isLost());
        Connection local = Connect.local(server.socket(), "ann", new Trust());
        assertEquals(LiveServer.OWNER, local.authorization());
        assertTrue(local.target() instanceof Target.Local);
        local.terminate();
    }

    /**
     * Verifies: [CLIENT Connecting] TLS begun on the same port, the server's certificate checked
     * against the authorities trusted and its name; a name it is not of fails the handshake.
     */
    @Test
    void tlsVerifiesTheServer() throws TurborelException, IOException {
        LiveServer server = LiveServer.get();
        Connection tls = overTls(server, new Trust());
        assertTrue(tls.target() instanceof Target.Tls);
        tls.terminate();
        TlsConfig config = verifying(server);
        TurborelException wrongName = assertThrows(
                TurborelException.class,
                () -> Connect.tls(
                        LiveServer.HOST, LiveServer.PORT, "db.example", config, new Login("ann", new Trust())));
        assertEquals("08006", wrongName.getSQLState());
    }

    /**
     * Verifies: [CLIENT Connecting] password, sent whole, over TLS and over a Unix socket; refused
     * over plain TCP before anything is sent (SQLSTATE 28000); a wrong one refused by the server
     * (SQLSTATE 28000), which ends the connection.
     */
    @Test
    void passwordOverTlsAndLocalOnly() throws TurborelException, IOException {
        LiveServer server = LiveServer.get();
        overTls(server, new Password(LiveServer.PASSWORD)).terminate();
        Connect.local(server.socket(), "ann", new Password(LiveServer.PASSWORD)).terminate();
        TurborelException clear = assertThrows(
                TurborelException.class,
                () -> Connect.tcp(LiveServer.HOST, LiveServer.PORT, "ann", new Password(LiveServer.PASSWORD)));
        assertEquals("28000", clear.getSQLState());
        TurborelException wrong = assertThrows(
                TurborelException.class, () -> Connect.local(server.socket(), "ann", new Password("wrong")));
        assertEquals("28000", wrong.getSQLState());
    }

    /**
     * Verifies: [CLIENT Connecting] SCRAM-SHA-256-PLUS over TLS, bound by tls-exporter where the
     * JDK exports it, else by the server's certificate; SCRAM-SHA-256 over a Unix socket, and over
     * plain TCP only when the program asks for it in the clear.
     */
    @Test
    void scramOverEveryTransport() throws TurborelException, IOException {
        LiveServer server = LiveServer.get();
        overTls(server, new Scram(LiveServer.PASSWORD).channelBinding(ChannelBinding.REQUIRE))
                .terminate();
        Connect.local(server.socket(), "ann", new Scram(LiveServer.PASSWORD)).terminate();
        Connect.tcp(LiveServer.HOST, LiveServer.PORT, "ann", new Scram(LiveServer.PASSWORD).inTheClear())
                .terminate();
        TurborelException clear = assertThrows(
                TurborelException.class,
                () -> Connect.tcp(LiveServer.HOST, LiveServer.PORT, "ann", new Scram(LiveServer.PASSWORD)));
        assertEquals("28000", clear.getSQLState());
        TurborelException wrong = assertThrows(TurborelException.class, () -> overTls(server, new Scram("wrong")));
        assertEquals("28000", wrong.getSQLState(), wrong.getMessage());
    }

    /**
     * Verifies: [CLIENT Connecting] a TLS stream the program opened itself, bound before
     * SCRAM-SHA-256-PLUS by what its handshake gave.
     */
    @Test
    void aTlsStreamTheProgramOpenedIsBound() throws TurborelException, IOException {
        LiveServer server = LiveServer.get();
        SSLSocket socket =
                Handshake.secured(Sockets.connected(ADDRESS, Timeouts.CONNECT_WITHIN), "localhost", verifying(server));
        Scram scram = new Scram(LiveServer.PASSWORD).channelBinding(ChannelBinding.REQUIRE);
        scram.bind(Handshake.channel(socket));
        Connection opened = Connection.open(new SocketDuplex(socket), "ann", scram, Offer.standard());
        assertEquals(LiveServer.OWNER, opened.authorization());
        TurborelException unbounded = assertThrows(TurborelException.class, () -> opened.setReadTimeout(null));
        assertEquals("08006", unbounded.getSQLState());
        opened.terminate();
    }

    /**
     * Verifies: [CLIENT Connecting] certificate: TLS trusting a file of authorities, presenting a
     * certificate chain and key, logs in as the user its common name names.
     */
    @Test
    void certificateLogsInAsItsCommonName() throws TurborelException, IOException {
        LiveServer server = LiveServer.get();
        TlsConfig presenting = TlsConfig.presenting(
                server.certificates().authority(),
                server.certificates().clientChain(),
                server.certificates().clientKey());
        Connection connection = Connect.tls(
                LiveServer.HOST, LiveServer.PORT, "localhost", presenting, new Login("ann", new Certificate()));
        assertEquals(LiveServer.OWNER, connection.authorization());
        connection.terminate();
    }

    /**
     * Verifies: [CLIENT The SCRAM key cache] a second login of the same salt and iterations finds
     * its keys in the cache, skipping the derivation; a login that fails drops the entry it used.
     */
    @Test
    void theKeyCacheSkipsTheDerivation() throws TurborelException, IOException {
        LiveServer server = LiveServer.get();
        KeyCache cache = new KeyCache();
        Connect.local(server.socket(), "ann", new Scram(LiveServer.PASSWORD).withCache(cache))
                .terminate();
        Connect.local(server.socket(), "ann", new Scram(LiveServer.PASSWORD).withCache(cache))
                .terminate();
        assertEquals(1, cache.lookups()[0], "the second login's keys came from the cache");
        assertEquals(1, cache.size());
        assertThrows(
                TurborelException.class,
                () -> Connect.local(server.socket(), "ann", new Scram("wrong").withCache(cache)));
        assertEquals(1, cache.size(), "the failed login's entry is dropped, the good one kept");
    }

    /**
     * Verifies: [PROTOCOL Status 'T'] at login the server tells AUTHORIZATION, SCHEMA, READ_ONLY,
     * SERVER_VERSION, SERVER_READ_ONLY, CATALOG, OWNER and every setting a session may set, which
     * the connection gives with no round trip ([CLIENT What the server tells]).
     */
    @Test
    void theSessionsStatusIsToldAtLogin() throws TurborelException, IOException {
        LiveServer.get();
        Connection connection = Connect.tcp(LiveServer.HOST, LiveServer.PORT, "ann", new Trust());
        assertEquals(LiveServer.OWNER, connection.setting("AUTHORIZATION"));
        assertEquals("NO", connection.setting("READ_ONLY"));
        assertEquals("NO", connection.setting("SERVER_READ_ONLY"));
        assertEquals("YES", connection.setting("OWNER"));
        assertNotNull(connection.setting("SCHEMA"));
        assertNotNull(connection.setting("CATALOG"));
        assertTrue(String.valueOf(connection.setting("SERVER_VERSION")).startsWith("turborel "));
        assertNotNull(connection.setting("SESSION.TIME_ZONE"));
        assertNotNull(connection.setting("TRANSACTION.ISOLATION"));
        assertTrue(connection.settings().size() > 7);
        connection.terminate();
    }

    /**
     * Verifies: [CLIENT What a connection asks for] every capability but compression is asked for
     * by default, and granted in the table's order; an offer of none asks for none, the first form;
     * one with compression has it granted, Ready itself never compressed.
     */
    @Test
    void theCapabilitiesAskedAreGranted() throws TurborelException, IOException {
        LiveServer.get();
        Target tcp = new Target.Tcp(ADDRESS);
        Connection standard = Connect.to(tcp, "ann", new Trust(), Timeouts.DEFAULT, Offer.standard());
        assertEquals(Set_of(Offer.standard().capabilities()), standard.granted());
        standard.terminate();
        Connection none = Connect.to(tcp, "ann", new Trust(), Timeouts.DEFAULT, Offer.none());
        assertTrue(none.granted().isEmpty());
        assertTrue(none.settings().isEmpty(), "no status without the capability");
        none.terminate();
        Connection compressed = Connect.to(
                tcp,
                "ann",
                new Trust(),
                Timeouts.DEFAULT,
                Offer.none().compressed().attribute(Offer.APPLICATION, "live"));
        assertEquals(java.util.Set.of(Capability.COMPRESSION), compressed.granted());
        compressed.terminate();
    }

    private static java.util.Set<String> Set_of(List<String> names) {
        return java.util.Set.copyOf(names);
    }

    /**
     * Verifies: [PROTOCOL Opening a connection] a user name that is not an identifier, or a
     * mechanism the server does not serve, is refused before any exchange (SQLSTATE 28000).
     */
    @Test
    void aBadUserOrMechanismIsRefused() throws TurborelException, IOException {
        LiveServer.get();
        Target tcp = new Target.Tcp(ADDRESS);
        TurborelException user = assertThrows(
                TurborelException.class,
                () -> Connect.to(tcp, "not an identifier", new Trust(), Timeouts.DEFAULT, Offer.standard()));
        assertEquals("28000", user.getSQLState(), user.getMessage());
        com.turborel.jdbc.internal.auth.Mechanism unknown = new Unknown();
        TurborelException mechanism = assertThrows(
                TurborelException.class, () -> Connect.to(tcp, "ann", unknown, Timeouts.DEFAULT, Offer.standard()));
        assertEquals("28000", mechanism.getSQLState(), mechanism.getMessage());
    }

    /**
     * Verifies: [PROTOCOL Opening a connection] a Hello of a version other than 1 is refused
     * (SQLSTATE 08004), the refusal an Error, and the server closes.
     */
    @Test
    void anotherVersionIsRefused() throws TurborelException, IOException {
        LiveServer.get();
        Opened opened = new Target.Tcp(ADDRESS).opened(Timeouts.CONNECT_WITHIN);
        byte[] hello = new com.turborel.jdbc.internal.codec.Request.Hello(
                        "ann", "trust", com.turborel.jdbc.internal.codec.Octets.EMPTY, List.of(), List.of())
                .encode();
        hello[5] = 2;
        try {
            opened.duplex().output().write(hello);
            opened.duplex().output().flush();
            com.turborel.jdbc.internal.codec.Reply reply =
                    new Wire(opened.duplex()).receive(com.turborel.jdbc.internal.codec.FrameHeader.MAX_BODY, false);
            assertTrue(reply instanceof com.turborel.jdbc.internal.codec.Reply.Error, reply.toString());
            assertEquals(
                    "08004",
                    ((com.turborel.jdbc.internal.codec.Reply.Error) reply)
                            .failure()
                            .sqlstate());
        } finally {
            opened.duplex().close();
        }
    }

    /** A mechanism the server does not serve. */
    private static final class Unknown implements com.turborel.jdbc.internal.auth.Mechanism {
        @Override
        public String name() {
            return "no-such-mechanism";
        }

        @Override
        public com.turborel.jdbc.internal.codec.Octets initial() {
            return com.turborel.jdbc.internal.codec.Octets.EMPTY;
        }

        @Override
        public com.turborel.jdbc.internal.codec.Octets answer(com.turborel.jdbc.internal.codec.Octets challenge) {
            return com.turborel.jdbc.internal.codec.Octets.EMPTY;
        }
    }
}
