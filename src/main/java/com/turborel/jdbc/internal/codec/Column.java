package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import org.jspecify.annotations.Nullable;

/**
 * A column of a result ([PROTOCOL Composite fields]): its name, if it has one, its data type, and
 * whether it is NOT NULL.
 *
 * @param name its name, if named
 * @param type its data type
 * @param notNull whether it is constrained NOT NULL
 */
public record Column(@Nullable String name, DataType type, boolean notNull) {
    /**
     * A column read.
     *
     * @param r where it is read
     * @return the column
     * @throws TurborelException if it is malformed
     */
    static Column read(Reader r) throws TurborelException {
        String name = r.flag() ? r.string() : null;
        DataType type = TypeCodec.read(r);
        return new Column(name, type, r.flag());
    }
}
