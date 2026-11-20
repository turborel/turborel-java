package com.turborel.jdbc.internal.settings;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Broken;
import java.nio.file.InvalidPathException;
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
     * At the Unix socket of a path; on Windows, the named pipe. The path is kept as written, as
     * the password file matches it and failures name it.
     *
     * @param name the socket's path or the pipe's name, as written
     */
    record Local(String name) implements Host {
        @Override
        public String passHost() {
            return name;
        }

        @Override
        public int passPort() {
            return Settings.DEFAULT_PORT;
        }

        /**
         * The path it names, as this platform reads it.
         *
         * @return the path
         * @throws TurborelException a connection failure if this platform takes no such path
         */
        public Path path() throws TurborelException {
            try {
                return Path.of(name);
            } catch (InvalidPathException e) {
                throw Broken.connection(
                        "[CLIENT Several servers] " + name + " names no local socket here: " + e.getMessage());
            }
        }
    }
}
