package com.turborel.jdbc.internal.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Frames;
import com.turborel.jdbc.internal.codec.Writer;
import com.turborel.jdbc.internal.session.Connection;
import com.turborel.jdbc.internal.transport.Target;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Connecting to one of several servers ([CLIENT Several servers], [CLIENT TLS by mode]). */
class FailoverTest {
    /** The orders the reference's shuffle gives 0 to 7 for the seeds 7, 0, all ones and 123456789. */
    private static final long[] SEEDS = {7L, 0L, -1L, 123_456_789L};

    private static final List<List<Integer>> ORDERS = List.of(
            List.of(7, 0, 2, 1, 5, 3, 4, 6),
            List.of(3, 0, 2, 4, 5, 6, 1, 7),
            List.of(6, 5, 4, 7, 2, 1, 3, 0),
            List.of(3, 5, 0, 2, 1, 6, 7, 4));

    private static List<Integer> order(long seed) {
        List<Integer> hosts = IntStream.range(0, 8).boxed().collect(Collectors.toCollection(ArrayList::new));
        Failover.shuffle(hosts, seed);
        return hosts;
    }

    /**
     * Verifies: [CLIENT Several servers] hosts ordered at random are each host once, in an order
     * the seed decides, the same for the same seed and the same as the reference's; different
     * seeds give different orders; one host or none stays as it is.
     */
    @Test
    void hostsAreShuffledByTheirSeed() {
        for (int i = 0; i < SEEDS.length; i++) {
            assertEquals(ORDERS.get(i), order(SEEDS[i]), "seed " + SEEDS[i]);
        }
        Set<List<Integer>> orders = new HashSet<>();
        for (long seed = 0; seed < 16; seed++) {
            orders.add(order(seed));
        }
        assertTrue(orders.size() > 8, orders::toString);
        List<Integer> one = new ArrayList<>(List.of(1));
        Failover.shuffle(one, 3);
        Failover.shuffle(new ArrayList<Integer>(), 3);
        assertEquals(List.of(1), one);
        assertNotEquals(Failover.seed(), Failover.seed(), "each seed the system's own");
    }

    /**
     * Verifies: [CLIENT Several servers] of one server tried, its own failure is given; of
     * several, SQLSTATE 08001 names each and why, or 28000 where the last refused the user, each
     * failure kept as suppressed; of none, 08001.
     */
    @Test
    void noServerKeptSaysWhyEachWasNot() {
        TurborelException lost = new TurborelException(-919, "08006", "cannot connect");
        TurborelException refused = new TurborelException(-919, "28000", "no");
        assertSame(refused, Failover.noneKept(List.of("a"), List.of(refused)));
        TurborelException both = Failover.noneKept(List.of("a", "b"), List.of(refused, lost));
        assertEquals("08001", both.getSQLState());
        assertEquals(-919, both.getErrorCode());
        assertTrue(
                String.valueOf(both.getMessage()).contains("a: no")
                        && String.valueOf(both.getMessage()).contains("b: cannot connect"),
                both.getMessage());
        assertEquals(2, both.getSuppressed().length);
        assertEquals(
                "28000",
                Failover.noneKept(List.of("b", "a"), List.of(lost, refused)).getSQLState());
        TurborelException none = Failover.noneKept(List.of(), List.of());
        assertEquals("08001", none.getSQLState());
        assertTrue(String.valueOf(none.getMessage()).contains("no server is named"));
    }

    /**
     * Verifies: [CLIENT Several servers] an address is named as the reference names it: an IPv6
     * address in brackets in its shortest form, as Rust writes it, its scope after {@code %}.
     */
    @Test
    void addressesAreNamedAsTheReferenceNamesThem() throws IOException {
        String[][] cases = {
            {"::1", "::1"},
            {"fe80::1", "fe80::1"},
            {"2001:db8::ff00:42:8329", "2001:db8::ff00:42:8329"},
            {"::", "::"},
            {"1::", "1::"},
            {"::1.2.3.4", "::102:304"},
            {"1:0:0:2:0:0:0:3", "1:0:0:2::3"},
            {"1:0:0:2:0:0:3:4", "1::2:0:0:3:4"},
            {"1:0:2:3:4:5:6:7", "1:0:2:3:4:5:6:7"},
            {"ABCD:0:0:0:0:0:0:0", "abcd::"}
        };
        for (String[] each : cases) {
            assertEquals(each[1], Ipv6Text.of(InetAddress.getAllByName(each[0])[0].getAddress()), each[0]);
        }
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xFF;
        mapped[11] = (byte) 0xFF;
        mapped[12] = 1;
        mapped[13] = 2;
        mapped[14] = 3;
        mapped[15] = 4;
        assertEquals("::ffff:1.2.3.4", Ipv6Text.of(mapped));
        mapped[10] = 0;
        assertEquals("::ff:102:304", Ipv6Text.of(mapped), "only ffff before it maps an IPv4 address");
        mapped[10] = (byte) 0xFF;
        mapped[0] = 1;
        assertEquals("100::ffff:102:304", Ipv6Text.of(mapped));
        InetAddress scoped =
                java.net.Inet6Address.getByAddress(null, InetAddress.getAllByName("fe80::1")[0].getAddress(), 3);
        assertEquals("[fe80::1%3]:7422", Failover.text(new InetSocketAddress(scoped, 7422)));
        assertEquals("[::1]:7422", Failover.text(new InetSocketAddress(InetAddress.getAllByName("::1")[0], 7422)));
        assertEquals("127.0.0.1:1", Failover.text(new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], 1)));
    }

    /** A port on loopback no server listens at: one the system gave, then gave up. */
    private static int dead() throws IOException {
        try (ServerSocket gone = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return gone.getLocalPort();
        }
    }

    private static Settings settings(String uri) throws TurborelException {
        return Settings.resolveIn(Options.fromUri(uri), name -> null);
    }

    /**
     * Verifies: [CLIENT TLS by mode] channel binding required is refused before any connection
     * (SQLSTATE 28000) by settings that cannot bind one: no password for SCRAM, or a connection
     * not over TLS; settings that can bind try the server, and an address no server listens at is
     * a connection failure; a name that does not resolve is named with its port.
     */
    @Test
    void bindingRequiredIsRefusedBeforeAnyConnection() throws IOException, TurborelException {
        int port = dead();
        for (String uri : List.of(
                "turborel://ann@127.0.0.1:" + port + "/?tls_mode=require&channel_binding=require",
                "turborel://ann:x@127.0.0.1:" + port + "/?tls_mode=disable&channel_binding=require")) {
            TurborelException e =
                    assertThrows(TurborelException.class, () -> settings(uri).connect());
            assertEquals("28000", e.getSQLState(), uri);
            assertTrue(String.valueOf(e.getMessage()).contains("channel binding is required"), e.getMessage());
        }
        String bound = "turborel://ann:x@127.0.0.1:" + port + "/?tls_mode=require&channel_binding=require";
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, () -> settings(bound).connect())
                        .getSQLState());
        TurborelException unknown = assertThrows(
                TurborelException.class,
                () -> settings("turborel://ann@no-such-host.invalid:5/?tls_mode=disable")
                        .connect());
        assertEquals("08006", unknown.getSQLState());
        assertTrue(String.valueOf(unknown.getMessage()).contains("cannot resolve the name"), unknown.getMessage());
    }

    /**
     * A server on loopback that drops its first connection, as a server that takes no TLS ends a
     * handshake, then, if asked, answers the next with Ready and reads until the client closes.
     */
    private static Thread dropsThenReady(ServerSocket server, boolean then) {
        Thread thread = new Thread(() -> {
            try {
                server.accept().close();
                if (!then) {
                    return;
                }
                try (Socket accepted = server.accept()) {
                    accepted.getOutputStream()
                            .write(Frames.frame(
                                    (byte) 'R',
                                    new Writer()
                                            .string("ANN")
                                            .raw(new byte[16])
                                            .strings(List.of())
                                            .string("turborel 0.0.0 (alpha)")
                                            .toByteArray()));
                    InputStream in = accepted.getInputStream();
                    while (in.read() >= 0) {
                        // Read until the client closes.
                    }
                }
            } catch (IOException | TurborelException ended) {
                // The client closed, or the test ended the server.
            }
        });
        thread.start();
        return thread;
    }

    /**
     * Verifies: [CLIENT TLS by mode] under {@code prefer}, a server reached whose TLS handshake
     * fails is connected to again over plain TCP, but refused there (SQLSTATE 28000) where channel
     * binding is required; under {@code require}, its handshake failing, there is no connection.
     */
    @Test
    void preferFallsBackAndRequireDoesNot() throws IOException, TurborelException, InterruptedException {
        try (ServerSocket server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress())) {
            Thread thread = dropsThenReady(server, true);
            String at = "127.0.0.1:" + server.getLocalPort();
            try (Connection plain =
                    settings("turborel://ann@" + at + "/?tls_mode=prefer").connect()) {
                assertInstanceOf(Target.Tcp.class, plain.target());
                assertEquals("ANN", plain.authorization());
            }
            thread.join();
            Thread again = dropsThenReady(server, false);
            TurborelException required = assertThrows(
                    TurborelException.class,
                    () -> settings("turborel://ann@" + at + "/?tls_mode=require")
                            .connect());
            assertTrue(String.valueOf(required.getMessage()).contains("handshake"), required.getMessage());
            again.join();
            Thread bound = dropsThenReady(server, false);
            TurborelException unbound = assertThrows(
                    TurborelException.class,
                    () -> settings("turborel://ann:x@" + at + "/?tls_mode=prefer&channel_binding=require")
                            .connect());
            assertEquals("28000", unbound.getSQLState(), "binding required, and TLS fell back to plain TCP");
            bound.join();
        }
    }
}
