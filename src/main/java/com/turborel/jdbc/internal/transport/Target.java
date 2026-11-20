package com.turborel.jdbc.internal.transport;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.tls.Handshake;
import com.turborel.jdbc.internal.tls.TlsConfig;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import javax.net.ssl.SSLSocket;
import org.jspecify.annotations.Nullable;

/**
 * Where a server is, and how a client reaches it ([CLIENT Connecting]): over TCP, a Unix socket (on
 * Windows, a named pipe), or TLS. A connection made to a target remembers it, so it can be reached
 * again to cancel a statement. As Turborel's {@code client/src/target.rs}.
 */
public sealed interface Target {
    /**
     * A stream to the server, made, then read, each within {@code within} if given.
     *
     * @param within how long it waits to connect and on each read, or without limit
     * @return the stream, and over TLS what its handshake gave
     * @throws TurborelException a connection failure if it cannot be reached, in time, or TLS's
     *     handshake fails
     */
    Opened opened(@Nullable Duration within) throws TurborelException;

    /**
     * Whether it is plain TCP, over which a secret mechanism is refused.
     *
     * @return whether plain TCP
     */
    default boolean plainTcp() {
        return false;
    }

    /**
     * Whether TLS secures it, so a mechanism may bind to its channel.
     *
     * @return whether over TLS
     */
    default boolean tls() {
        return false;
    }

    /**
     * A stream to the server, as {@link #opened}; but of a {@link Tls} whose server is reached and
     * its TLS handshake fails, the same connection made again over plain TCP, and that target, as
     * the TLS mode {@code prefer} asks ([CLIENT TLS by mode]): never twice the wait of a server that
     * cannot be reached.
     *
     * @param within how long it waits to connect and on each read, or without limit
     * @return the target reached, and its stream
     * @throws TurborelException as {@link #opened}'s
     */
    default Preferred openedPreferring(@Nullable Duration within) throws TurborelException {
        return new Preferred(this, opened(within));
    }

    /**
     * What {@link #openedPreferring} reached.
     *
     * @param target the target reached: over TLS, or plain TCP after its handshake failed
     * @param opened its stream
     */
    record Preferred(Target target, Opened opened) {}

    /**
     * Over TCP, at an address.
     *
     * @param address the server's address
     */
    record Tcp(InetSocketAddress address) implements Target {
        @Override
        public Opened opened(@Nullable Duration within) throws TurborelException {
            return new Opened(new SocketDuplex(Sockets.connected(address, within)), null);
        }

        @Override
        public boolean plainTcp() {
            return true;
        }
    }

    /**
     * Over the Unix socket at a path; on Windows, the named pipe of a name, as {@code
     * \\.\pipe\turborel}.
     *
     * @param path the socket's path or the pipe's name
     */
    record Local(Path path) implements Target {
        @Override
        public Opened opened(@Nullable Duration within) throws TurborelException {
            if (Platform.isWindows()) {
                return new Opened(PipeDuplex.open(path, within), null);
            }
            UnixDuplex duplex = UnixDuplex.connect(path);
            duplex.bound(within);
            return new Opened(duplex, null);
        }
    }

    /**
     * Over TLS, at an address, the server of a name, trusted as a configuration says.
     *
     * @param address the server's address
     * @param name the server's name, which its certificate is checked against
     * @param config the TLS
     */
    record Tls(InetSocketAddress address, String name, TlsConfig config) implements Target {
        @Override
        public Opened opened(@Nullable Duration within) throws TurborelException {
            SSLSocket secured = Handshake.secured(Sockets.connected(address, within), name, config);
            return new Opened(new SocketDuplex(secured), Handshake.channel(secured));
        }

        @Override
        public boolean tls() {
            return true;
        }

        @Override
        public Preferred openedPreferring(@Nullable Duration within) throws TurborelException {
            Socket tcp = Sockets.connected(address, within);
            SSLSocket secured;
            try {
                secured = Handshake.secured(tcp, name, config);
            } catch (TurborelException handshake) {
                Tcp plain = new Tcp(address);
                return new Preferred(plain, plain.opened(within));
            }
            return new Preferred(this, new Opened(new SocketDuplex(secured), Handshake.channel(secured)));
        }
    }
}
