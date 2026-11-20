package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.auth.Mechanism;
import com.turborel.jdbc.internal.codec.Broken;
import com.turborel.jdbc.internal.tls.TlsConfig;
import com.turborel.jdbc.internal.transport.Opened;
import com.turborel.jdbc.internal.transport.SocketDuplex;
import com.turborel.jdbc.internal.transport.Sockets;
import com.turborel.jdbc.internal.transport.Target;
import com.turborel.jdbc.internal.transport.Timeouts;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;

/**
 * The ways a connection is made ([CLIENT Connecting]): over TCP, a local socket, TLS, or to a
 * target, each waiting as its timeouts say, and refusing a secret mechanism over plain TCP before
 * anything is sent, as Turborel's {@code connect}, {@code connect_local}, {@code tls::connect} and
 * {@code target::connect_to} do.
 */
public final class Connect {
    /** The SQLSTATE of credentials refused: invalid authorization specification. */
    private static final String INVALID_AUTHORIZATION = "28000";

    /** The port a server takes TCP connections at unless told another ([PROTOCOL The Native Protocol]). */
    public static final int PORT = 7422;

    private Connect() {}

    /**
     * Connects over TCP, waiting as {@link Timeouts#DEFAULT} says, asking for every capability but
     * compression.
     *
     * @param host the server's name or address, each of its addresses tried in turn
     * @param port its port, {@link #PORT} unless told another
     * @param user the user
     * @param mechanism what proves the user
     * @return the connection
     * @throws TurborelException SQLSTATE 28000, before any connection is made, for a secret
     *     mechanism; the server's refusal; a connection failure
     */
    public static Connection tcp(String host, int port, String user, Mechanism mechanism) throws TurborelException {
        refuseInTheClear(mechanism);
        Socket socket = Sockets.connected(host, port, Timeouts.DEFAULT.connect());
        Target reach = new Target.Tcp(new InetSocketAddress(socket.getInetAddress(), port));
        Opened opened = new Opened(new SocketDuplex(socket), null);
        return Connection.openTimed(opened, new Login(user, mechanism), Timeouts.DEFAULT, Offer.standard(), reach);
    }

    /**
     * Connects over the local socket at {@code path}: a Unix socket, or on Windows a named pipe, as
     * {@code \\.\pipe\turborel}.
     *
     * @param path the socket's path or the pipe's name
     * @param user the user
     * @param mechanism what proves the user
     * @return the connection
     * @throws TurborelException the server's refusal; a connection failure
     */
    public static Connection local(Path path, String user, Mechanism mechanism) throws TurborelException {
        return to(new Target.Local(path), user, mechanism, Timeouts.DEFAULT, Offer.standard());
    }

    /**
     * Connects over TLS to the server named {@code name} at {@code host}'s {@code port}, its
     * handshake a step of its login.
     *
     * @param host the server's name or address
     * @param port its port
     * @param name the name its certificate is checked against
     * @param config the TLS
     * @param login the user, and what proves it, bound to the TLS channel if it binds
     * @return the connection
     * @throws TurborelException the server's refusal; a connection failure, the handshake's
     *     among them
     */
    public static Connection tls(String host, int port, String name, TlsConfig config, Login login)
            throws TurborelException {
        Socket socket = Sockets.connected(host, port, Timeouts.DEFAULT.connect());
        InetSocketAddress address = new InetSocketAddress(socket.getInetAddress(), port);
        Sockets.close(socket);
        return to(
                new Target.Tls(address, name, config),
                login.user(),
                login.mechanism(),
                Timeouts.DEFAULT,
                Offer.standard());
    }

    /**
     * Connects to a target, waiting as {@code timeouts} say, asking for what {@code offer} asks.
     *
     * @param target where the server is
     * @param user the user
     * @param mechanism what proves the user
     * @param timeouts how long it waits
     * @param offer what it asks for
     * @return the connection
     * @throws TurborelException SQLSTATE 28000, before any connection is made, for a secret
     *     mechanism to plain TCP; the server's refusal; a connection failure
     */
    public static Connection to(Target target, String user, Mechanism mechanism, Timeouts timeouts, Offer offer)
            throws TurborelException {
        if (target.plainTcp()) {
            refuseInTheClear(mechanism);
        }
        Opened opened = target.opened(timeouts.connect());
        return Connection.openTimed(opened, new Login(user, mechanism), timeouts, offer, target);
    }

    /**
     * Refuses a secret mechanism over plain TCP, before a connection is made, as the server refuses
     * it: SQLSTATE 28000.
     *
     * @param mechanism the mechanism
     * @throws TurborelException SQLCODE -919, SQLSTATE 28000, if it is secret
     */
    static void refuseInTheClear(Mechanism mechanism) throws TurborelException {
        if (mechanism.secret()) {
            throw new TurborelException(
                    Broken.SQLCODE,
                    INVALID_AUTHORIZATION,
                    "[CLIENT Connecting] " + mechanism.name()
                            + " is sent only over TLS, a Unix socket or a named pipe, never over plain TCP");
        }
    }
}
