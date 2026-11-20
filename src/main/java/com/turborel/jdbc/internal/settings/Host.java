package com.turborel.jdbc.internal.settings;

import java.nio.file.Path;

/** Where a server is ([CLIENT Where settings come from]). */
public sealed interface Host {
    /**
     * The host as a password file's line names it ([CLIENT The password file]): its name, or a
     * local socket's path.
     *
     * @return the name
     */
    String passHost();

    /**
     * The port as a password file's line names it: its own, or of a local socket the default.
     *
     * @return the port
     */
    int passPort();

    /**
     * At a host name or address, and port, over TCP.
     *
     * @param name the name or address
     * @param port the port
     */
    record Tcp(String name, int port) implements Host {
        @Override
        public String passHost() {
            return name;
        }

        @Override
        public int passPort() {
            return port;
        }
    }

    /**
     * At the Unix socket of a path; on Windows, the named pipe.
     *
     * @param path the socket's path or the pipe's name
     */
    record Local(Path path) implements Host {
        @Override
        public String passHost() {
            return path.toString();
        }

        @Override
        public int passPort() {
            return Settings.DEFAULT_PORT;
        }
    }
}
