package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.security.MessageDigest;

/**
 * The key that cancels a session's statements from another connection, 16 bytes, which Ready
 * gives the client alone ([PROTOCOL Ready 'R']). It is the client's secret: it is never shown, so
 * no log or failure discloses it (RULES.md R-SAFE-4).
 */
public final class CancelKey {
    /** Its bytes, as the field {@code key} carries them, with no length before them. */
    public static final int BYTES = 16;

    private final Octets key;

    private CancelKey(Octets key) {
        this.key = key;
    }

    /**
     * A key read: 16 bytes with no length.
     *
     * @param r where it is read
     * @return the key
     * @throws TurborelException if fewer than 16 bytes are left
     */
    static CancelKey read(Reader r) throws TurborelException {
        return new CancelKey(Octets.owning(r.take(BYTES)));
    }

    /**
     * A key of 16 bytes.
     *
     * @param bytes the bytes, copied
     * @return the key
     * @throws TurborelException if there are not 16
     */
    public static CancelKey of(byte[] bytes) throws TurborelException {
        if (bytes.length != BYTES) {
            throw Failures.misuse(Cite.FIELDS + " a key is " + BYTES + " bytes, not " + bytes.length);
        }
        return new CancelKey(Octets.of(bytes));
    }

    /**
     * Writes it, with no length.
     *
     * @param w where it is written
     * @throws TurborelException if the body would be too long
     */
    void encode(Writer w) throws TurborelException {
        w.raw(key.shared());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CancelKey k && MessageDigest.isEqual(k.key.shared(), key.shared());
    }

    @Override
    public int hashCode() {
        return 0;
    }

    /** Never the key. */
    @Override
    public String toString() {
        return "CancelKey(..)";
    }
}
