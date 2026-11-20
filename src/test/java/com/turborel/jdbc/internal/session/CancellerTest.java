package com.turborel.jdbc.internal.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.CancelKey;
import com.turborel.jdbc.internal.codec.Request;
import com.turborel.jdbc.internal.transport.Target;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** A cancel, over a connection of its own ([PROTOCOL Cancel 'Z']). */
class CancellerTest {
    private static CancelKey key() throws TurborelException {
        return CancelKey.of(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
    }

    /**
     * Verifies: [PROTOCOL Cancel 'Z'] a cancel is a connection's first and only message, its key
     * the one Ready gave; the server closes, saying nothing.
     */
    @Test
    void aCancelSendsItsKeyAlone() throws TurborelException, IOException, InterruptedException {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            AtomicReference<byte[]> sent = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    accepted.getOutputStream().write(new byte[] {1, 2});
                    accepted.shutdownOutput();
                    sent.set(accepted.getInputStream().readAllBytes());
                } catch (IOException e) {
                    sent.set(new byte[0]);
                }
            });
            thread.start();
            new Canceller(new Target.Tcp(at(server)), key()).cancel();
            thread.join();
            assertArrayEquals(new Request.Cancel(key()).encode(), sent.get());
        }
    }

    /**
     * Verifies: [CLIENT Running statements] a cancel gives up after its limit, a server that never
     * closes failing it as a connection failure (SQLSTATE 08006); one that cannot be reached fails
     * too.
     */
    @Test
    void aCancelGivesUpInTime() throws TurborelException, IOException {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Canceller canceller = new Canceller(new Target.Tcp(at(server)), key());
            long began = System.nanoTime();
            TurborelException late =
                    assertThrows(TurborelException.class, () -> canceller.cancelWithin(Duration.ofMillis(200)));
            assertEquals("08006", late.getSQLState());
            assertTrue(Duration.ofNanos(System.nanoTime() - began).compareTo(Duration.ofSeconds(5)) < 0);
        }
        InetSocketAddress nobody;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            nobody = at(closed);
        }
        Canceller unreachable = new Canceller(new Target.Tcp(nobody), key());
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, unreachable::cancel).getSQLState());
    }

    private static InetSocketAddress at(ServerSocket server) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort());
    }
}
