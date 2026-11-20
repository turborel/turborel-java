package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;

/**
 * A large object's value: whole, tag 20, or by its locator, tag 21 ([PROTOCOL Values]). One of at
 * most SERVER.LOB_INLINE_BYTES comes whole; a longer one by a locator the client reads with Lob
 * requests, which lasts until its transaction ends. A client sends a large object whole, or as a
 * locator it made.
 */
public sealed interface Lob extends Value {
    /**
     * Its kind.
     *
     * @return CLOB or BLOB
     */
    LobKind kind();

    /**
     * A large object whole, tag 20: its kind, then its octets, a CLOB's UTF-8.
     *
     * @param kind its kind
     * @param octets its octets
     */
    record Whole(LobKind kind, Octets octets) implements Lob {
        /**
         * A CLOB of a string.
         *
         * @param text the string
         * @return the large object
         * @throws TurborelException if the string is not Unicode
         */
        public static Whole ofText(String text) throws TurborelException {
            return new Whole(LobKind.CHARACTER, Octets.owning(Writer.utf8(text)));
        }

        @Override
        public void encode(Writer w) throws TurborelException {
            w.u8(Tags.LOB_WHOLE);
            kind.encode(w);
            w.bytes(octets);
        }
    }

    /**
     * A large object by its locator, tag 21: its kind, the locator, and its lengths in octets and
     * in characters.
     *
     * @param kind its kind
     * @param locator its locator, a {@code u32}
     * @param octets its length in octets, a {@code u64}'s bits
     * @param characters its length in characters, a {@code u64}'s bits
     */
    record Locator(LobKind kind, long locator, long octets, long characters) implements Lob {
        @Override
        public void encode(Writer w) throws TurborelException {
            w.u8(Tags.LOB_LOCATOR);
            kind.encode(w);
            w.u32(locator).u64(octets).u64(characters);
        }
    }
}
