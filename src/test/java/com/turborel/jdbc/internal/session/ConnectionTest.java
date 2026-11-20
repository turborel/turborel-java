package com.turborel.jdbc.internal.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.auth.Password;
import com.turborel.jdbc.internal.auth.Trust;
import com.turborel.jdbc.internal.codec.Compression;
import com.turborel.jdbc.internal.codec.Octets;
import com.turborel.jdbc.internal.codec.Reply;
import com.turborel.jdbc.internal.codec.Request;
import com.turborel.jdbc.internal.codec.Writer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A connection's login and session over a stream the test scripts ([PROTOCOL Opening a connection]). */
class ConnectionTest {
    /**
     * Verifies: [PROTOCOL Opening a connection] the client sends Hello, takes each Status, then
     * Ready: its authorization identifier, cancel key, capabilities granted and server; its first
     * message, a Hello asking for every capability but compression ([CLIENT What a connection asks
     * for]).
     */
    @Test
    void helloThenStatusThenReady() throws TurborelException {
        Script stream =
                new Script(Script.cat(Script.status("AUTHORIZATION", "ANN"), Script.ready(List.of("status", "ping"))));
        Connection connection = Connection.open(stream, "ann", new Trust(), Offer.standard());
        assertEquals("ANN", connection.authorization());
        assertEquals("turborel 0.0.0 (alpha)", connection.server());
        assertEquals(java.util.Set.of("status", "ping"), connection.granted());
        assertEquals("ANN", connection.setting("AUTHORIZATION"));
        assertNull(connection.target());
        assertNull(connection.canceller(), "a stream the program gave cannot be reached again");
        byte[] hello =
                new Request.Hello("ann", "trust", Octets.EMPTY, Offer.standard().capabilities(), List.of()).encode();
        assertArrayEquals(hello, stream.written());
        assertFalse(Offer.standard().capabilities().contains(Capability.COMPRESSION));
        assertEquals(Capability.ALL.size() - 1, Offer.standard().capabilities().size());
    }

    /**
     * Verifies: [PROTOCOL Opening a connection] a refusal is an Error: credentials refused, class
     * 28, end the connection ([PROTOCOL Error 'E']), the failure carrying its SQLSTATE.
     */
    @Test
    void aRefusalIsAnError() throws TurborelException {
        Script stream = new Script(Script.error("28000"));
        TurborelException refused = assertThrows(
                TurborelException.class, () -> Connection.open(stream, "ann", new Password("x"), Offer.standard()));
        assertEquals("28000", refused.getSQLState());
        assertTrue(stream.closed());
    }

    /**
     * Verifies: [PROTOCOL Opening a connection] a reply other than Challenge, Ready or Error during
     * the login, or a stream that ends, fails the connection (SQLSTATE 08006).
     */
    @Test
    void aLoginOutOfTurnFails() throws TurborelException {
        TurborelException pong = assertThrows(
                TurborelException.class,
                () -> Connection.open(new Script(Script.frame('G', new Writer())), "ann", new Trust(), Offer.none()));
        assertEquals("08006", pong.getSQLState());
        TurborelException ended = assertThrows(
                TurborelException.class,
                () -> Connection.open(new Script(new byte[0]), "ann", new Trust(), Offer.none()));
        assertEquals("08006", ended.getSQLState());
        TurborelException cut = assertThrows(
                TurborelException.class,
                () -> Connection.open(new Script(new byte[] {'R', 9, 0, 0, 0, 1}), "ann", new Trust(), Offer.none()));
        assertEquals("08006", cut.getSQLState());
        TurborelException challenged = assertThrows(
                TurborelException.class,
                () -> Connection.open(
                        new Script(Script.frame('C', new Writer().bytes(new byte[0]))),
                        "ann",
                        new Trust(),
                        Offer.none()));
        assertEquals("08006", challenged.getSQLState(), "trust has no answer to a challenge");
    }

    /**
     * Verifies: [PROTOCOL Opening a connection] Ready at login is never compressed; once
     * compression is granted, a compressed reply is inflated, and before it a compressed frame
     * fails ([PROTOCOL Compressed frames]).
     */
    @Test
    void compressionBeginsAfterReady() throws TurborelException {
        byte[] status = Script.status("SCHEMA", "A".repeat(4000));
        byte[] compressed = Compression.frame(status[0], java.util.Arrays.copyOfRange(status, 5, status.length), 0);
        Connection connection = Connection.open(
                new Script(
                        Script.cat(Script.ready(List.of("compression")), compressed, Script.frame('G', new Writer()))),
                "ann",
                new Trust(),
                Offer.none().compressed());
        connection.send(new Request.Ping());
        assertTrue(connection.receive() instanceof Reply.Pong);
        assertEquals("A".repeat(4000), connection.setting("SCHEMA"));
        assertThrows(
                TurborelException.class,
                () -> Connection.open(
                        new Script(Script.cat(compressed, Script.ready(List.of()))), "ann", new Trust(), Offer.none()));
    }

    /**
     * Verifies: [PROTOCOL Capabilities] a request of a capability not granted is refused before
     * anything is sent ([CLIENT What a connection asks for]).
     */
    @Test
    void aCapabilityNotGrantedIsRefusedBeforeSending() throws TurborelException {
        Script stream = new Script(Script.ready(List.of("ping")));
        Connection connection = Connection.open(stream, "ann", new Trust(), Offer.standard());
        connection.needs(false, Capability.PING);
        connection.needs(true, Capability.RESET);
        int sent = stream.writtenSize();
        TurborelException refused =
                assertThrows(TurborelException.class, () -> connection.needs(false, Capability.RESET));
        assertEquals("08006", refused.getSQLState());
        assertEquals(sent, stream.writtenSize());
    }

    /**
     * Verifies: [CLIENT What the server tells] a notice with no handler is kept, the last 64; with
     * one, handed to it; the settings told are given with no round trip.
     */
    @Test
    void noticesAreKeptOrHanded() throws TurborelException {
        List<byte[]> replies = new ArrayList<>();
        replies.add(Script.ready(List.of()));
        for (int i = 0; i <= Told.KEPT_NOTICES; i++) {
            replies.add(Script.frame('N', new Writer().string("01000").string(Integer.toString(i))));
        }
        replies.add(Script.frame('G', new Writer()));
        replies.add(Script.frame('N', new Writer().string("01000").string("handed")));
        replies.add(Script.frame('G', new Writer()));
        Connection connection = Connection.open(
                new Script(Script.cat(replies.toArray(new byte[0][]))), "ann", new Trust(), Offer.none());
        connection.receive();
        List<Reply.Notice> kept = connection.takeNotices();
        assertEquals(Told.KEPT_NOTICES, kept.size());
        assertEquals("1", kept.get(0).message(), "the oldest dropped");
        assertTrue(connection.takeNotices().isEmpty());
        List<String> heard = new ArrayList<>();
        connection.setNoticeHandler(n -> heard.add(n.message()));
        connection.receive();
        assertEquals(List.of("handed"), heard);
        assertTrue(connection.settings().isEmpty());
    }

    /**
     * Verifies: [PROTOCOL Terminate 'X'] Terminate is sent and what the server says as it closes is
     * read: nothing, a notice, or the commit's Error, which is given; the connection then takes no
     * more.
     */
    @Test
    void terminateReadsWhatTheServerSays() throws TurborelException {
        Script quiet = new Script(Script.ready(List.of()));
        Connection connection = Connection.open(quiet, "ann", new Trust(), Offer.none());
        connection.terminate();
        assertTrue(quiet.closed());
        assertTrue(connection.isLost());
        assertThrows(TurborelException.class, () -> connection.send(new Request.Ping()));
        Connection noticed = Connection.open(
                new Script(Script.cat(
                        Script.ready(List.of()),
                        Script.frame('N', new Writer().string("01000").string("bye")))),
                "ann",
                new Trust(),
                Offer.none());
        noticed.terminate();
        Connection failing = Connection.open(
                new Script(Script.cat(Script.ready(List.of()), Script.error("40002"))),
                "ann",
                new Trust(),
                Offer.none());
        assertEquals(
                "40002",
                assertThrows(TurborelException.class, failing::terminate).getSQLState());
        Connection odd = Connection.open(
                new Script(Script.cat(Script.ready(List.of()), Script.frame('S', new Writer()))),
                "ann",
                new Trust(),
                Offer.none());
        assertEquals(
                "08006", assertThrows(TurborelException.class, odd::terminate).getSQLState());
    }

    /**
     * Verifies: [CLIENT Timeouts] replies are taken up to 16 MiB, or as many bytes as the program
     * says; a longer one loses the connection.
     */
    @Test
    void aReplyPastTheLimitIsRefused() throws TurborelException {
        Connection connection = Connection.open(
                new Script(Script.cat(
                        Script.ready(List.of()),
                        Script.status("SCHEMA", "LONGER THAN EIGHT"),
                        Script.frame('G', new Writer()))),
                "ann",
                new Trust(),
                Offer.none());
        connection.setMaxBody(8);
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, connection::receive).getSQLState());
        assertTrue(connection.isLost());
    }

    /**
     * Verifies: [PROTOCOL Error 'E'] an Error of class 08 but 08003 ends the connection, one of
     * 08003 or of a statement does not; the failure carries its rule, position, active flag and
     * diagnostics (RULES.md D9).
     */
    @Test
    void anErrorEndsTheConnectionByItsClass() throws TurborelException {
        byte[] statement = Script.frame(
                'E',
                new Writer()
                        .bits32(-103)
                        .string("2A000")
                        .string("no such table")
                        .flag(true)
                        .string("§5.7")
                        .flag(true)
                        .u64(14)
                        .flag(true)
                        .u8(7)
                        .string("ERROR"));
        Connection connection = Connection.open(
                new Script(
                        Script.cat(Script.ready(List.of()), statement, Script.error("08003"), Script.error("08001"))),
                "ann",
                new Trust(),
                Offer.none());
        for (int i = 0; i < 2; i++) {
            Reply.Error reply = (Reply.Error) connection.receive();
            TurborelException failure = connection.failed(reply);
            assertFalse(connection.isLost(), failure.getSQLState());
            if (i == 0) {
                assertEquals("§5.7", failure.rule());
                assertEquals(14, failure.position().getAsLong());
                assertTrue(failure.transactionActive());
                assertEquals(java.util.Map.of("SEVERITY", "ERROR"), failure.diagnostics());
                assertTrue(connection.inTransaction(), "the first says a transaction stays active");
            }
        }
        connection.failed((Reply.Error) connection.receive());
        assertTrue(connection.isLost());
        assertFalse(connection.inTransaction(), "the last says none is");
    }

    /**
     * Verifies: [CLIENT Timeouts] a connection over a stream the program gave is bounded by the
     * program: its read timeout cannot be set; [CLIENT Connecting] a secret mechanism is refused
     * over plain TCP before any connection is made (SQLSTATE 28000).
     */
    @Test
    void aGivenStreamAndASecretInTheClear() throws TurborelException {
        Connection connection = Connection.open(new Script(Script.ready(List.of())), "ann", new Trust(), Offer.none());
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, () -> connection.setReadTimeout(Duration.ofSeconds(1)))
                        .getSQLState());
        assertNull(connection.readTimeout());
        assertEquals(
                "28000",
                assertThrows(TurborelException.class, () -> Connect.refuseInTheClear(new Password("x")))
                        .getSQLState());
        Connect.refuseInTheClear(new Trust());
        TurborelException tcp = assertThrows(
                TurborelException.class,
                () -> Connect.to(
                        new com.turborel.jdbc.internal.transport.Target.Tcp(
                                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 1)),
                        "ann",
                        new Password("x"),
                        com.turborel.jdbc.internal.transport.Timeouts.DEFAULT,
                        Offer.standard()));
        assertEquals("28000", tcp.getSQLState());
    }

    /**
     * Verifies: [CLIENT What a connection asks for] an offer of none asks for nothing; compressed
     * adds compression once; attributes are added, the program's name among them.
     */
    @Test
    void offers() {
        assertTrue(Offer.none().capabilities().isEmpty());
        Offer compressed = Offer.none().compressed();
        assertEquals(List.of(Capability.COMPRESSION), compressed.capabilities());
        assertEquals(compressed, compressed.compressed());
        Offer named = Offer.standard().attribute(Offer.APPLICATION, "tool");
        assertEquals(
                new com.turborel.jdbc.internal.codec.Attribute("application", "tool"),
                named.attributes().get(0));
        assertEquals(Compression.DEFAULT_THRESHOLD, named.compressFrom());
    }

    /**
     * Verifies: [PROTOCOL Conversations] a query's Description, its rows suspended, or a large
     * object's data or locator, each means a transaction is active; a Complete then says whether
     * one goes on.
     */
    @Test
    void repliesThatOpenATransaction() throws TurborelException {
        byte[] complete =
                Script.frame('K', new Writer().bits32(0).string("00000").flag(false));
        byte[][] opening = {
            Script.frame('D', new Writer().u32(0)),
            Script.frame('S', new Writer()),
            Script.frame('L', new Writer().u8(0).bytes(new byte[] {1})),
            Script.frame('L', new Writer().u8(1).u32(5)),
        };
        for (byte[] reply : opening) {
            Connection connection = Connection.open(
                    new Script(Script.cat(Script.ready(List.of()), reply, complete)), "ann", new Trust(), Offer.none());
            assertFalse(connection.inTransaction());
            connection.receive();
            assertTrue(connection.inTransaction(), Integer.toString(reply[0]));
            assertTrue(connection.receive() instanceof Reply.Complete);
            assertFalse(connection.inTransaction());
        }
    }

    /**
     * Verifies: [PROTOCOL Terminate 'X'] a Status or a Pong as the server closes is nothing; a
     * connection closed twice stays closed.
     */
    @Test
    void terminateTakesAStatusOrAPong() throws TurborelException {
        for (byte[] last : new byte[][] {Script.status("SCHEMA", "A"), Script.frame('G', new Writer())}) {
            Connection connection = Connection.open(
                    new Script(Script.cat(Script.ready(List.of()), last)), "ann", new Trust(), Offer.none());
            connection.terminate();
            assertTrue(connection.isLost());
        }
        Connection closed = Connection.open(new Script(Script.ready(List.of())), "ann", new Trust(), Offer.none());
        closed.close();
        closed.close();
        assertTrue(closed.isLost());
    }

    /** A scripted stream that records each bound set on it. */
    private static final class Bounded implements com.turborel.jdbc.internal.transport.Duplex {
        private final Script script;
        final List<@Nullable Duration> bounds = new ArrayList<>();

        Bounded(byte[] server) {
            this.script = new Script(server);
        }

        @Override
        public java.io.InputStream input() {
            return script.input();
        }

        @Override
        public java.io.OutputStream output() {
            return script.output();
        }

        @Override
        public void bound(@Nullable Duration within) {
            bounds.add(within);
        }

        @Override
        public void close() {
            script.close();
        }
    }

    /**
     * Verifies: [CLIENT Timeouts] a stream of the program's own that bounds itself, opened within
     * timeouts, is bounded as they say: the login by the connect timeout, each read after by the
     * read timeout, Terminate's answer by it too; its read timeout is set as a connection's.
     */
    @Test
    void aStreamOfTheProgramsOwnIsBoundedWithinTimeouts() throws TurborelException {
        Bounded stream = new Bounded(Script.ready(List.of()));
        com.turborel.jdbc.internal.transport.Timeouts within =
                new com.turborel.jdbc.internal.transport.Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(7));
        Connection c = Connection.openWithin(stream, new Login("ann", new Trust()), within, Offer.none());
        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(7)), stream.bounds);
        assertEquals(Duration.ofSeconds(7), c.readTimeout());
        c.setReadTimeout(Duration.ofSeconds(9));
        assertEquals(Duration.ofSeconds(9), c.readTimeout());
        c.terminate();
        assertEquals(Duration.ofSeconds(9), stream.bounds.get(stream.bounds.size() - 1));
        Bounded refused = new Bounded(Script.error("28000"));
        assertThrows(
                TurborelException.class,
                () -> Connection.openWithin(refused, new Login("ann", new Trust()), within, Offer.none()));
    }
}
