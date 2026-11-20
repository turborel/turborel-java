package com.turborel.jdbc.internal.transport;

import com.turborel.jdbc.TurborelException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * A Unix socket as a {@link Duplex} ([CLIENT Connecting]). Java's blocking channels take no
 * timeout, so the channel is non-blocking and each read and write waits on a selector, as long as
 * the bound says.
 */
public final class UnixDuplex implements Duplex {
    private final SocketChannel channel;
    private final Selector selector;
    private final InputStream input = new In();
    private final OutputStream output = new Out();
    private int waitMillis;

    private UnixDuplex(SocketChannel channel, Selector selector) {
        this.channel = channel;
        this.selector = selector;
    }

    /**
     * A connection to the Unix socket at {@code path}.
     *
     * @param path the socket's path
     * @return the duplex
     * @throws TurborelException a connection failure if it cannot be connected to
     */
    public static UnixDuplex connect(Path path) throws TurborelException {
        SocketChannel channel = null;
        try {
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(path));
            channel.configureBlocking(false);
            return new UnixDuplex(channel, Selector.open());
        } catch (IOException | UnsupportedOperationException e) {
            closeQuietly(channel);
            throw Io.failed("connect", e instanceof IOException io ? io : new IOException(e.getMessage(), e));
        }
    }

    @Override
    public InputStream input() {
        return input;
    }

    @Override
    public OutputStream output() {
        return output;
    }

    @Override
    public void bound(@Nullable Duration within) {
        waitMillis = Waits.millis(within);
    }

    @Override
    public void close() {
        closeQuietly(channel);
        try {
            selector.close();
        } catch (IOException ignored) {
            // The selector holds nothing once its channel is closed.
        }
    }

    private static void closeQuietly(@Nullable SocketChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // A channel given up is closed as far as the system lets it.
        }
    }

    /** Waits until the channel is ready for {@code operation}, at most as the bound says. */
    private void await(int operation) throws IOException {
        SelectionKey key = channel.register(selector, operation);
        try {
            if (selector.select(waitMillis) == 0) {
                throw new SocketTimeoutException("no answer within " + waitMillis + " ms");
            }
        } finally {
            key.interestOps(0);
            selector.selectedKeys().clear();
        }
    }

    /** The socket's bytes, read as they come. */
    private final class In extends InputStream {
        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read < 0 ? read : Byte.toUnsignedInt(one[0]);
        }

        @Override
        public int read(byte[] into, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            ByteBuffer buffer = ByteBuffer.wrap(into, offset, length);
            int read = channel.read(buffer);
            while (read == 0) {
                await(SelectionKey.OP_READ);
                read = channel.read(buffer);
            }
            return read;
        }
    }

    /** Bytes to the socket, written whole. */
    private final class Out extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] from, int offset, int length) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(from, offset, length);
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) == 0) {
                    await(SelectionKey.OP_WRITE);
                }
            }
        }
    }
}
