package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Writer;
import java.util.List;

/**
 * The session tests' scripted connection, for the JDBC layer's unit tests in another package
 * (RULES.md R-TEST-7): a connection logged in over the frames given, as {@link Script} makes one.
 */
public final class Scripted {
    private Scripted() {}

    /**
     * A connection logged in, granted {@code granted}, its server to send {@code replies}.
     *
     * @param granted the capabilities granted
     * @param replies the server's frames after Ready
     * @return the connection
     * @throws TurborelException if the login's frames are malformed
     */
    public static Connection after(List<String> granted, byte[]... replies) throws TurborelException {
        return Script.after(granted, replies);
    }

    /**
     * A Complete of SQLCODE 0, a transaction active after it if {@code active}.
     *
     * @param active whether a transaction is active after it
     * @return its frame
     * @throws TurborelException never
     */
    public static byte[] complete(boolean active) throws TurborelException {
        return Script.frame('K', new Writer().bits32(0).string("00000").flag(active));
    }

    /**
     * A Notice of {@code sqlstate} and {@code message}.
     *
     * @param sqlstate its SQLSTATE
     * @param message its message
     * @return its frame
     * @throws TurborelException never
     */
    public static byte[] notice(String sqlstate, String message) throws TurborelException {
        return Script.frame('N', new Writer().string(sqlstate).string(message));
    }

    /**
     * A Status of a setting.
     *
     * @param name its name
     * @param value its value
     * @return its frame
     * @throws TurborelException never
     */
    public static byte[] status(String name, String value) throws TurborelException {
        return Script.status(name, value);
    }

    /**
     * An Error of {@code sqlcode} and {@code sqlstate}.
     *
     * @param sqlcode its SQLCODE
     * @param sqlstate its SQLSTATE
     * @return its frame
     * @throws TurborelException never
     */
    public static byte[] error(int sqlcode, String sqlstate) throws TurborelException {
        return Script.error(sqlcode, sqlstate);
    }

    /**
     * Every byte the client wrote to a connection {@link #after} made, its login's first.
     *
     * @param connection the connection
     * @return the bytes
     */
    public static byte[] written(Connection connection) {
        return ((Script) connection.wire().duplex()).written();
    }
}
