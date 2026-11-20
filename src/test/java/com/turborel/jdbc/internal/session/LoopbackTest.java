package com.turborel.jdbc.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.auth.Trust;
import com.turborel.jdbc.internal.codec.Frames;
import com.turborel.jdbc.internal.codec.Writer;
import com.turborel.jdbc.internal.transport.Target;
import com.turborel.jdbc.internal.transport.Timeouts;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** A connection's waits, against a server on loopback that stops answering ([CLIENT Timeouts]). */
class LoopbackTest {
    /** Longer than any wait a test bounds; a test that passes ends well within it. */
    private static final long HELD_SECONDS = 30;

    /**
     * A server that takes one connection, sends Ready if asked, then reads and never answers,
     * until the client closes or the test ends.
     */
    private static Thread silent(ServerSocket server, boolean ready, CountDownLatch done) {
        Thread thread = new Thread(() -> {
            try (Socket accepted = server.accept()) {
                if (ready) {
                    accepted.getOutputStream()
                            .write(Frames.frame(
                                    (byte) 'R',
                                    new Writer()
                                            .string("ANN")
                                            .raw(new byte[16])
                                            .strings(List.of())
                                            .string("turborel 0.0.0 (alpha)")
                                            .toByteArray()));
                }
                InputStream in = accepted.getInputStream();
                while (in.read() >= 0 && done.getCount() > 0) {
                    // Read and say nothing.
                }
            } catch (IOException | TurborelException ended) {
                // The client closed, as the test expects.
            }
        });
        thread.start();
        return thread;
    }

    private static InetSocketAddress at(ServerSocket server) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort());
    }

    /**
     * Verifies: [CLIENT Timeouts] the connect timeout bounds each step of the login: a server that
     * never answers Hello loses the connection (SQLSTATE 08006), saying it did not answer in time.
     */
    @Test
    void theConnectTimeoutBoundsTheLogin() throws IOException, InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread thread = silent(server, false, done);
            long began = System.nanoTime();
            TurborelException late = assertThrows(
                    TurborelException.class,
                    () -> Connect.to(
                            new Target.Tcp(at(server)),
                            "ann",
                            new Trust(),
                            new Timeouts(Duration.ofMillis(200), null),
                            Offer.none()));
            assertEquals("08006", late.getSQLState());
            assertTrue(String.valueOf(late.getMessage()).contains("did not answer in time"), late.getMessage());
            assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(HELD_SECONDS));
            done.countDown();
            thread.join();
        }
    }

    /**
     * Verifies: [CLIENT Connecting] Terminate waits for the server to close at most the read
     * timeout, then loses the connection (SQLSTATE 08006); [PROTOCOL The Native Protocol] the port
     * is 7422 unless told another.
     */
    @Test
    void terminateWaitsAtMostTheReadTimeout() throws TurborelException, IOException, InterruptedException {
        assertEquals(7422, Connect.PORT);
        assertEquals(Duration.ofSeconds(60), Timeouts.TERMINATE_WITHIN);
        CountDownLatch done = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread thread = silent(server, true, done);
            Connection connection = Connect.tcp(
                    InetAddress.getLoopbackAddress().getHostAddress(), server.getLocalPort(), "ann", new Trust());
            connection.setReadTimeout(Duration.ofMillis(200));
            long began = System.nanoTime();
            TurborelException late = assertThrows(TurborelException.class, connection::terminate);
            assertEquals("08006", late.getSQLState());
            assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(HELD_SECONDS));
            assertTrue(connection.isLost());
            done.countDown();
            thread.join();
        }
    }
}
