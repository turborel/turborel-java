package com.turborel.jdbc.internal.transport;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Broken;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/** TCP connections made within a limit of time ([CLIENT Connecting], [CLIENT Timeouts]). */
public final class Sockets {
    private Sockets() {}

    /**
     * A TCP connection to {@code address}, made, then read, each within {@code within} if given.
     *
     * @param address the address
     * @param within how long it waits, or without limit
     * @return the socket, connected
     * @throws TurborelException a connection failure if it cannot be made, in time
     */
    public static Socket connected(InetSocketAddress address, @Nullable Duration within) throws TurborelException {
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(Waits.millis(within));
            socket.connect(address, Waits.millis(within));
            return socket;
        } catch (IOException e) {
            close(socket);
            throw Io.failed("connect", e);
        }
    }

    /**
     * A TCP connection to a host's port: each of its addresses tried in turn, until one is
     * connected to, each within {@code within}.
     *
     * @param host the server's name or address
     * @param port its port
     * @param within how long each attempt waits, or without limit
     * @return the socket, connected
     * @throws TurborelException a connection failure, the last address's, if none can be made
     */
    public static Socket connected(String host, int port, @Nullable Duration within) throws TurborelException {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw Io.failed("connect", e);
        }
        TurborelException last = Broken.connection("[CLIENT Connecting] no address to connect to");
        for (InetAddress each : addresses) {
            try {
                return connected(new InetSocketAddress(each, port), within);
            } catch (TurborelException failed) {
                last = failed;
            }
        }
        throw last;
    }

    /**
     * Closes a socket given up, of no consequence if it fails.
     *
     * @param socket the socket
     */
    public static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // A socket given up is closed as far as the system lets it.
        }
    }
}
