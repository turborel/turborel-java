package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;

/**
 * The kind of a large object, as its {@code kind} byte says ([PROTOCOL Values], [PROTOCOL Lob
 * 'L']).
 */
public enum LobKind {
    /** A character large object, CLOB, byte 0. */
    CHARACTER(0),
    /** A binary large object, BLOB, byte 1. */
    BINARY(1);

    private final int code;

    LobKind(int code) {
        this.code = code;
    }

    /**
     * Its byte.
     *
     * @return 0 or 1
     */
    public int code() {
        return code;
    }

    /**
     * The kind of a {@code kind} byte.
     *
     * @param r where the byte is read
     * @return the kind
     * @throws TurborelException if the byte is neither 0 nor 1
     */
    static LobKind read(Reader r) throws TurborelException {
        int kind = r.u8();
        for (LobKind each : values()) {
            if (each.code == kind) {
                return each;
            }
        }
        throw r.malformed(Cite.VALUES, "a large object's kind is " + kind + ", neither 0 nor 1");
    }

    /**
     * Writes its {@code kind} byte.
     *
     * @param w where it is written
     * @throws TurborelException if the body would be too long
     */
    void encode(Writer w) throws TurborelException {
        w.u8(code);
    }
}
