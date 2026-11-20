package com.turborel.jdbc.internal.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** TCP, Unix sockets and named pipes, and how long each waits ([CLIENT Timeouts]). */
class TransportTest {
    private static final Duration WITHIN = Duration.ofSeconds(10);

    /**
     * Verifies: [CLIENT Timeouts] a wait is given to the system in milliseconds, at least 1 of a
     * wait given, as 0 means none; none is 0; the most an int holds bounds it.
     */
    @Test
    void waitsInMilliseconds() {
        assertEquals(0, Waits.millis(null));
        assertEquals(1, Waits.millis(Duration.ZERO));
        assertEquals(1, Waits.millis(Duration.ofNanos(5)));
        assertEquals(1500, Waits.millis(Duration.ofMillis(1500)));
        assertEquals(Integer.MAX_VALUE, Waits.millis(Duration.ofDays(100)));
        assertEquals(Duration.ofSeconds(30), Timeouts.CONNECT_WITHIN);
        assertEquals(Timeouts.CONNECT_WITHIN, Timeouts.DEFAULT.connect());
        assertNull(Timeouts.DEFAULT.read());
    }

    /**
     * Verifies: [CLIENT Timeouts] a read past its bound fails, saying the server did not answer in
     * time; any other failure says what failed (SQLSTATE 08006).
     */
    @Test
    void failuresSayWhat() {
        TurborelException late = Io.failed("receive", new SocketTimeoutException("Read timed out"));
        assertEquals("08006", late.getSQLState());
        assertEquals(-919, late.sqlcode());
        assertTrue(
                String.valueOf(late.getMessage()).contains("cannot receive: the server did not answer in time"),
                late.getMessage());
        TurborelException reset = Io.failed("send", new IOException("Connection reset"));
        assertEquals("[DESIGN] cannot send: Connection reset", reset.getMessage());
        assertTrue(reset.getCause() instanceof IOException);
    }

    /**
     * Verifies: [CLIENT Connecting] TCP connects by address or by host and port; plain TCP is the
     * transport a secret mechanism is refused over; a read past its bound fails in time; a port no
     * one listens at fails (SQLSTATE 08006).
     */
    @Test
    void tcpConnectsAndBounds() throws TurborelException, IOException {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            InetSocketAddress at = new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort());
            Target tcp = new Target.Tcp(at);
            assertTrue(tcp.plainTcp());
            Opened opened = tcp.opened(Duration.ofMillis(100));
            assertNull(opened.channel());
            try (Socket accepted = server.accept()) {
                accepted.getOutputStream().write(5);
                assertEquals(5, opened.duplex().input().read());
                opened.duplex().output().write(6);
                opened.duplex().output().flush();
                assertEquals(6, accepted.getInputStream().read());
                TurborelException late = assertThrows(TurborelException.class, () -> read(opened.duplex()));
                assertTrue(String.valueOf(late.getMessage()).contains("did not answer in time"), late.getMessage());
                opened.duplex().bound(null);
                assertEquals(0, ((SocketDuplex) opened.duplex()).socket().getSoTimeout());
            } finally {
                opened.duplex().close();
            }
            Socket byName = Sockets.connected(at.getHostString(), at.getPort(), WITHIN);
            server.accept().close();
            Sockets.close(byName);
            assertTrue(byName.isClosed());
        }
        int free;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            free = closed.getLocalPort();
        }
        InetSocketAddress nobody = new InetSocketAddress(InetAddress.getLoopbackAddress(), free);
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, () -> Sockets.connected(nobody, WITHIN))
                        .getSQLState());
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, () -> Sockets.connected("no-such-host.invalid", free, WITHIN))
                        .getSQLState());
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, () -> Sockets.connected("127.0.0.1", free, WITHIN))
                        .getSQLState(),
                "each address refused");
    }

    private static int read(Duplex duplex) throws TurborelException {
        try {
            return duplex.input().read();
        } catch (IOException e) {
            throw Io.failed("receive", e);
        }
    }

    /**
     * Verifies: [CLIENT Connecting] a Unix socket is connected by its path, read and written, a
     * read past its bound failing in time, and the end of its stream seen; a path no server is at
     * fails (SQLSTATE 08006).
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the local transport of Windows is its named pipe")
    void unixSocketConnectsAndBounds() throws TurborelException, IOException {
        Path dir = Files.createTempDirectory(Path.of("/tmp"), "tj");
        Path path = dir.resolve("s");
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(path));
            Target local = new Target.Local(path);
            assertFalse(local.plainTcp());
            Opened opened = local.opened(Duration.ofMillis(100));
            Duplex duplex = opened.duplex();
            try (SocketChannel accepted = server.accept()) {
                accepted.write(ByteBuffer.wrap(new byte[] {1, 2, 3}));
                InputStream in = duplex.input();
                assertEquals(1, in.read());
                byte[] rest = new byte[2];
                assertEquals(2, in.readNBytes(rest, 0, 2));
                assertEquals(0, in.read(rest, 0, 0));
                byte[] big = new byte[1 << 20];
                Thread draining = new Thread(() -> drain(accepted, big.length));
                draining.start();
                duplex.output().write(big);
                duplex.output().write(9);
                draining.join();
                TurborelException late = assertThrows(TurborelException.class, () -> read(duplex));
                assertTrue(String.valueOf(late.getMessage()).contains("did not answer in time"), late.getMessage());
                duplex.bound(null);
                accepted.shutdownOutput();
                assertEquals(-1, in.read());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                duplex.close();
                duplex.close();
            }
        } finally {
            Files.deleteIfExists(path);
            Files.delete(dir);
        }
        assertEquals(
                "08006",
                assertThrows(TurborelException.class, () -> UnixDuplex.connect(path))
                        .getSQLState());
    }

    private static void drain(SocketChannel channel, int bytes) {
        ByteBuffer buffer = ByteBuffer.allocate(bytes + 1);
        try {
            while (buffer.hasRemaining()) {
                channel.read(buffer);
            }
        } catch (IOException ended) {
            // The test reads what it wrote; an end here fails its assertions.
        }
    }

    /**
     * Verifies: [CLIENT Connecting] a named pipe is refused off Windows, before anything is tried
     * (SQLSTATE 08006).
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Windows has named pipes")
    void aNamedPipeIsWindowsOnly() {
        assertFalse(Platform.isWindows());
        TurborelException refused =
                assertThrows(TurborelException.class, () -> PipeDuplex.open(Path.of("\\\\.\\pipe\\turborel"), WITHIN));
        assertEquals("08006", refused.getSQLState());
        assertTrue(String.valueOf(refused.getMessage()).contains("only Windows has"), refused.getMessage());
    }

    /**
     * Verifies: [CLIENT Connecting] a named pipe busy in every instance is tried again until it
     * opens, or until the time to connect runs out, failing as the server not answering in time;
     * any other failure to open it fails at once (SQLSTATE 08006); its reads and writes go to the
     * pipe, and are not bounded.
     */
    @Test
    void aBusyPipeIsTriedAgain() throws TurborelException, IOException {
        Path file = Files.createTempFile("tj-pipe", ".bin");
        try {
            int[] busy = {2};
            PipeDuplex.Opener opener = name -> {
                if (busy[0]-- > 0) {
                    throw new java.io.FileNotFoundException(name + " (All pipe instances are busy)");
                }
                return new java.io.RandomAccessFile(name.toFile(), "rw");
            };
            PipeDuplex pipe = PipeDuplex.open(file, WITHIN, true, opener);
            assertEquals(-1, busy[0], "opened on the third try");
            pipe.bound(Duration.ofMillis(1));
            pipe.output().write(new byte[] {4, 5});
            pipe.output().flush();
            pipe.close();
            pipe.close();
            assertEquals(2, Files.size(file));
            try (PipeDuplex again = PipeDuplex.open(file, null, true, opener)) {
                assertEquals(4, again.input().read());
            }

            PipeDuplex.Opener alwaysBusy = name -> {
                throw new java.io.FileNotFoundException("All pipe instances are busy");
            };
            TurborelException late = assertThrows(
                    TurborelException.class, () -> PipeDuplex.open(file, Duration.ofMillis(30), true, alwaysBusy));
            assertTrue(String.valueOf(late.getMessage()).contains("did not answer in time"), late.getMessage());
            PipeDuplex.Opener absent = name -> {
                throw new java.io.FileNotFoundException("The system cannot find the file specified");
            };
            TurborelException missing =
                    assertThrows(TurborelException.class, () -> PipeDuplex.open(file, WITHIN, true, absent));
            assertEquals("[DESIGN] cannot connect: The system cannot find the file specified", missing.getMessage());
            Thread.currentThread().interrupt();
            TurborelException interrupted =
                    assertThrows(TurborelException.class, () -> PipeDuplex.open(file, null, true, alwaysBusy));
            assertTrue(Thread.interrupted(), "the interrupt is kept");
            assertTrue(String.valueOf(interrupted.getMessage()).contains("interrupted"), interrupted.getMessage());
        } finally {
            Files.delete(file);
        }
    }
}
