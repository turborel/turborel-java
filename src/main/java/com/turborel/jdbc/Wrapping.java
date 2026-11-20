package com.turborel.jdbc;

import java.sql.SQLException;
import org.jspecify.annotations.Nullable;

/** JDBC's {@code Wrapper} of each of the driver's objects ([DESIGN] D2): unwrapped to an interface it implements. */
final class Wrapping {
    private Wrapping() {}

    /**
     * {@code wrapper} as {@code iface}, if it is one.
     *
     * @param <T> the interface
     * @param wrapper the object
     * @param iface the interface
     * @return the object as the interface
     * @throws SQLException SQLSTATE TB910 if it is none
     */
    static <T> T unwrap(Object wrapper, @Nullable Class<T> iface) throws SQLException {
        if (iface == null) {
            throw Errors.misuse("unwrap is given the interface to unwrap to");
        }
        if (iface.isInstance(wrapper)) {
            return iface.cast(wrapper);
        }
        throw Errors.misuse("a " + wrapper.getClass().getSimpleName() + " wraps no " + iface.getName());
    }
}
