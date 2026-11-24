package com.turborel.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a connection of the driver has beyond JDBC's ([DESIGN] D2), reached by {@code
 * connection.unwrap(TurborelConnection.class)}: what the server told the session, and what it
 * granted.
 */
public interface TurborelConnection extends Connection {
    /**
     * A setting of the session, as the server last told it: AUTHORIZATION, SCHEMA, CATALOG,
     * READ_ONLY, SERVER_VERSION, SERVER_READ_ONLY, OWNER, or a setting a session may set.
     *
     * @param name the setting's name
     * @return its value, or none if the server told none
     * @throws SQLException SQLSTATE TB910 if the connection is closed
     */
    @Nullable
    String sessionStatus(String name) throws SQLException;

    /**
     * Every setting of the session the server told, by name.
     *
     * @return the settings
     * @throws SQLException SQLSTATE TB910 if the connection is closed
     */
    Map<String, String> sessionStatus() throws SQLException;

    /**
     * The log position of the last commit a statement of the connection made, if the server told
     * one.
     *
     * @return the position, or none
     * @throws SQLException SQLSTATE TB910 if the connection is closed
     */
    @Nullable
    Long commitPosition() throws SQLException;

    /**
     * The capabilities the server granted the connection, by their protocol names.
     *
     * @return the capabilities
     * @throws SQLException SQLSTATE TB910 if the connection is closed
     */
    Set<String> granted() throws SQLException;

    /**
     * This connection's routine debugger *(extension)*, granted {@code debugger} ([DESIGN] D15).
     *
     * @return the debugger
     * @throws SQLException SQLSTATE TB910 if the connection is closed
     */
    TurborelDebugger debugger() throws SQLException;
}
