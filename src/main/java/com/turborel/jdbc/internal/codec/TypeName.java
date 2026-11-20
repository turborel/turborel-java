package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;

/**
 * The name of a structured type, or of a table that scopes a reference: its schema's name and
 * its own ([PROTOCOL Data types]).
 *
 * @param schema the schema's name
 * @param name its own name
 */
public record TypeName(String schema, String name) {
    /**
     * A name read, its schema's name then its own.
     *
     * @param r where it is read
     * @return the name
     * @throws TurborelException if it is malformed
     */
    static TypeName read(Reader r) throws TurborelException {
        String schema = r.string();
        return new TypeName(schema, r.string());
    }
}
