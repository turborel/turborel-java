package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;

/**
 * Lob, the request: work on a large object by its locator ([PROTOCOL Lob 'L'], under Requests),
 * an operation's byte, then its fields.
 */
public sealed interface LobRequest extends Request {
    // [PROTOCOL Lob 'L'], the request: the operations.
    /** Read. */
    int READ = 0;
    /** Create. */
    int CREATE = 1;
    /** Append. */
    int APPEND = 2;
    /** Free. */
    int FREE = 3;
    /** Hold. */
    int HOLD = 4;

    /**
     * Read: at most {@code count} octets from octet {@code from}, or characters, answered by a
     * Lob reply of data.
     *
     * @param locator the locator, a {@code u32}
     * @param from the first octet or character, from 0, a {@code u64}'s bits
     * @param count the most read, a {@code u64}'s bits
     * @param characters whether it counts characters, not octets
     */
    record Read(long locator, long from, long count, boolean characters) implements LobRequest {
        @Override
        public byte[] encode() throws TurborelException {
            Writer w = new Writer().u8(READ).u32(locator).u64(from).u64(count).flag(characters);
            return Frames.frame(LOB, w.toByteArray());
        }
    }

    /**
     * Create: an empty large object, answered by a Lob reply of its locator.
     *
     * @param kind its kind
     */
    record Create(LobKind kind) implements LobRequest {
        @Override
        public byte[] encode() throws TurborelException {
            Writer w = new Writer().u8(CREATE);
            kind.encode(w);
            return Frames.frame(LOB, w.toByteArray());
        }
    }

    /**
     * Append: octets added to one Create made; a character may be split between two.
     *
     * @param locator the locator, a {@code u32}
     * @param bytes the octets
     */
    record Append(long locator, Octets bytes) implements LobRequest {
        @Override
        public byte[] encode() throws TurborelException {
            return Frames.frame(
                    LOB, new Writer().u8(APPEND).u32(locator).bytes(bytes).toByteArray());
        }
    }

    /**
     * Free: a locator ended.
     *
     * @param locator the locator, a {@code u32}
     */
    record Free(long locator) implements LobRequest {
        @Override
        public byte[] encode() throws TurborelException {
            return Frames.frame(LOB, new Writer().u8(FREE).u32(locator).toByteArray());
        }
    }

    /**
     * Hold: a locator kept valid past its transaction's commit.
     *
     * @param locator the locator, a {@code u32}
     */
    record Hold(long locator) implements LobRequest {
        @Override
        public byte[] encode() throws TurborelException {
            return Frames.frame(LOB, new Writer().u8(HOLD).u32(locator).toByteArray());
        }
    }
}
