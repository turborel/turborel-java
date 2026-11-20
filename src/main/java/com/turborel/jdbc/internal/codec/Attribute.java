package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.util.List;

/**
 * An attribute of a connection or a request, a name and a value, tag 1 of the tail of Hello,
 * Query and Execute ([PROTOCOL The tail]).
 *
 * @param name its name, 1 to 64 bytes of UTF-8
 * @param value its value, at most 1024 bytes of UTF-8
 */
public record Attribute(String name, String value) {
    /** The most attributes a message takes. */
    public static final int MAX_ATTRIBUTES = 32;

    /** The longest name, in bytes. */
    public static final int MAX_NAME = 64;

    /** The longest value, in bytes. */
    public static final int MAX_VALUE = 1024;

    /**
     * Writes tag 1 and the attributes, unless there are none, refusing more than the server takes
     * before anything is sent. The server fails the connection over them, so they are refused
     * with its code, SQLCODE -919 and SQLSTATE 08006, and a program sees one behaviour either way.
     *
     * @param w where they are written
     * @param attributes the attributes
     * @throws TurborelException a connection failure if there are more than 32, or a name or a
     *     value is out of bounds
     */
    static void write(Writer w, List<Attribute> attributes) throws TurborelException {
        if (attributes.isEmpty()) {
            return;
        }
        if (attributes.size() > MAX_ATTRIBUTES) {
            throw Broken.connection(Cite.TAIL + " " + attributes.size() + " attributes are more than the "
                    + MAX_ATTRIBUTES + " a message takes");
        }
        w.u8(Tail.ATTRIBUTES).bits32(attributes.size());
        for (Attribute each : attributes) {
            byte[] name = Writer.utf8(each.name);
            byte[] value = Writer.utf8(each.value);
            if (name.length == 0 || name.length > MAX_NAME || value.length > MAX_VALUE) {
                throw Broken.connection(Cite.TAIL + " an attribute's name is 1 to " + MAX_NAME
                        + " bytes and its value at most " + MAX_VALUE);
            }
            w.bytes(name).bytes(value);
        }
    }
}
