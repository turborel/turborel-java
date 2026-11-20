package com.turborel.jdbc.internal.codec;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * Octets that never change: a copy of what it was made of, compared by content, so a value or
 * a message that holds octets is a record like any other and no caller changes what another
 * holds.
 */
public final class Octets {
    /** No octets. */
    public static final Octets EMPTY = new Octets(new byte[0]);

    private final byte[] octets;

    private Octets(byte[] octets) {
        this.octets = octets;
    }

    /**
     * Octets of a copy of the bytes given.
     *
     * @param bytes the bytes
     * @return the octets
     */
    public static Octets of(byte[] bytes) {
        return new Octets(bytes.clone());
    }

    /**
     * Octets of bytes no one else holds, taken without a copy.
     *
     * @param bytes the bytes, which the caller gives up
     * @return the octets
     */
    static Octets owning(byte[] bytes) {
        return new Octets(bytes);
    }

    /**
     * A copy of the octets.
     *
     * @return the bytes
     */
    public byte[] toByteArray() {
        return octets.clone();
    }

    /**
     * How many octets there are.
     *
     * @return the count
     */
    public int length() {
        return octets.length;
    }

    /** The octets themselves, for the codec's writer, which never changes them. */
    byte[] shared() {
        return octets;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Octets o && Arrays.equals(o.octets, octets);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(octets);
    }

    @Override
    public String toString() {
        return HexFormat.of().formatHex(octets);
    }
}
