package com.turborel.jdbc.internal.auth;

import com.turborel.jdbc.internal.codec.Octets;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The channel binding of a SCRAM exchange [RFC 5802 §6, §7]: the flag of its GS2 header, and the
 * data it binds ([PROTOCOL Mechanisms]).
 */
sealed interface Binding {
    /**
     * Its GS2 header.
     *
     * @return the header
     */
    String header();

    /**
     * Whether it binds a channel, of SCRAM-SHA-256-PLUS.
     *
     * @return whether it is bound
     */
    boolean bound();

    /**
     * cbind-input: its GS2 header, then the data it binds [RFC 5802 §7], whose base 64 is the
     * {@code c=} of client-final.
     *
     * @return the bytes
     */
    default byte[] input() {
        return header().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * A header binding nothing.
     *
     * @param header the GS2 header
     */
    record Flag(String header) implements Binding {
        /** {@code n}: the client binds no channel, as over a Unix socket. */
        static final Flag UNBOUND = new Flag("n,,");

        /** {@code y}: the client could bind one, but the server offers none on this connection. */
        static final Flag UNOFFERED = new Flag("y,,");

        @Override
        public boolean bound() {
            return false;
        }
    }

    /**
     * {@code p=tls-server-end-point}: bound to the server's certificate, of this hash [RFC 5929 §4].
     *
     * @param data the hash
     */
    record EndPoint(Octets data) implements Binding {
        @Override
        public String header() {
            return "p=tls-server-end-point,,";
        }

        @Override
        public boolean bound() {
            return true;
        }

        @Override
        public byte[] input() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(Binding.super.input());
            out.writeBytes(data.toByteArray());
            return out.toByteArray();
        }
    }

    /**
     * {@code p=tls-exporter}: bound to the TLS connection itself, by the keying material it exports
     * [RFC 9266 §2].
     *
     * @param data the keying material
     */
    record Exporter(Octets data) implements Binding {
        @Override
        public String header() {
            return "p=tls-exporter,,";
        }

        @Override
        public boolean bound() {
            return true;
        }

        @Override
        public byte[] input() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(Binding.super.input());
            out.writeBytes(data.toByteArray());
            return out.toByteArray();
        }
    }
}
