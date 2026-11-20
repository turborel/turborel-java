package com.turborel.jdbc;

import com.turborel.jdbc.testing.LiveServer;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** The JDBC tests' connections to the live server (RULES.md R-TEST-8): by URL, as ANN, by trust, no TLS. */
final class LiveJdbc {
    /** The URL of the live server. */
    static final String URL = "jdbc:turborel://" + LiveServer.HOST + ":" + LiveServer.PORT + "/?tls_mode=disable";

    private LiveJdbc() {}

    /**
     * A connection to the live server as ANN, the owner's schema made.
     *
     * @return the connection
     * @throws SQLException if it cannot be made
     * @throws IOException if the server cannot be started
     */
    static Connection connect() throws SQLException, IOException {
        LiveServer.get();
        Connection connection = DriverManager.getConnection(URL, "ann", null);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA AUTHORIZATION ANN");
        } catch (SQLException made) {
            // The schema is made once for every live test of a run.
        }
        return connection;
    }

    /**
     * Runs each statement, under the connection's autocommit.
     *
     * @param connection the connection
     * @param sqls the statements
     * @throws SQLException a statement's failure
     */
    static void run(Connection connection, String... sqls) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : sqls) {
                statement.execute(sql);
            }
        }
    }
}
