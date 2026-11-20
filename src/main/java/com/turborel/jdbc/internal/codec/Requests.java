package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;

/** What the requests share in writing their bodies ([PROTOCOL Requests]). */
final class Requests {
    private Requests() {}

    /**
     * Writes tag 3 and a cursor's number, unless it is 0, the cursor a request names when tag 3
     * is absent ([PROTOCOL Execute 'E']).
     *
     * @param w where it is written
     * @param cursor the cursor's number, a {@code u32}
     * @throws TurborelException if the number is past a {@code u32}
     */
    static void cursor(Writer w, long cursor) throws TurborelException {
        if (cursor != 0) {
            w.u8(Tail.CURSOR).u32(cursor);
        }
    }
}
