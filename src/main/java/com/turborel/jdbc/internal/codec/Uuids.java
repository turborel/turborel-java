package com.turborel.jdbc.internal.codec;

import java.nio.ByteBuffer;
import java.util.UUID;

/** A UUID's octets, as its value's {@code bytes} carry them, most significant first. */
final class Uuids {
    /** The octets of a UUID. */
    static final int OCTETS = 16;

    private Uuids() {}

    /**
     * The UUID of 16 octets.
     *
     * @param octets the octets, exactly 16
     * @return the UUID
     */
    static UUID of(byte[] octets) {
        ByteBuffer big = ByteBuffer.wrap(octets);
        return new UUID(big.getLong(), big.getLong());
    }
}
