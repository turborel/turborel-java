package com.turborel.jdbc.internal.transport;

import com.turborel.jdbc.TurborelException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * A TCP connection, or a TLS one over it, as a {@link Duplex}: its reads bounded by the socket's
 * own timeout. Java gives a socket no timeout on its writes, so a write waits as the system's
 * send buffer lets it.
 */
public final class SocketDuplex implements Duplex {
    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;

    /**
     * The duplex of a connected socket.
     *
     * @param socket the socket, connected
     * @throws TurborelException a connection failure if its streams cannot be had
     */
    public SocketDuplex(Socket socket) throws TurborelException {
        this.socket = socket;
        try {
            this.input = socket.getInputStream();
            this.output = socket.getOutputStream();
        } catch (IOException e) {
            close();
            throw Io.failed("set the connection up", e);
        }
    }

    /**
     * The socket it reads and writes.
     *
     * @return the socket
     */
    public Socket socket() {
        return socket;
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
    public void bound(@Nullable Duration within) throws TurborelException {
        try {
            socket.setSoTimeout(Waits.millis(within));
        } catch (SocketException e) {
            throw Io.failed("bound how long the connection waits", e);
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // A socket given up is closed as far as the system lets it; nothing waits on it.
        }
    }
}
