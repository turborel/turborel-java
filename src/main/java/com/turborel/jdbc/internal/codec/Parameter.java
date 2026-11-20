package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;

/**
 * A dynamic parameter of a prepared statement ([PROTOCOL Composite fields]): its varying flag, 1
 * where the server gives it CHARACTER VARYING of the longest length, as it does of one whose type
 * the statement does not decide, and its data type, written all the same.
 *
 * @param varying whether the server gave it CHARACTER VARYING of the longest length
 * @param type its data type
 */
public record Parameter(boolean varying, DataType type) {
    /**
     * A parameter read.
     *
     * @param r where it is read
     * @return the parameter
     * @throws TurborelException if it is malformed
     */
    static Parameter read(Reader r) throws TurborelException {
        boolean varying = r.flag();
        return new Parameter(varying, TypeCodec.read(r));
    }
}
