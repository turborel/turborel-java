package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.Failures;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * A data source of turborel connections ([DESIGN] D2): a URL, as the {@link Driver} takes it, and
 * the settings set beside it, each a connection setting by its name; {@code user} and {@code
 * password} among them. Its login timeout is the setting {@code connect_timeout}.
 */
public class TurborelDataSource implements DataSource {
    private String url = "jdbc:turborel://";
    private final Properties settings = new Properties();
    private @Nullable PrintWriter logWriter;

    /** A data source of the URL {@code jdbc:turborel://}, to be set. */
    public TurborelDataSource() {
        // Its URL and settings are set by their setters.
    }

    /**
     * The URL its connections are made of.
     *
     * @return the URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * Sets the URL its connections are made of.
     *
     * @param url the URL, {@code jdbc:turborel://...}
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * Sets a connection setting, over the URL's.
     *
     * @param name the setting's name, as {@code tls_mode}
     * @param value its value
     */
    public void setSetting(String name, String value) {
        settings.setProperty(name, value);
    }

    /**
     * Sets the user to connect as.
     *
     * @param user the user
     */
    public void setUser(String user) {
        settings.setProperty("user", user);
    }

    /**
     * The user it connects as, if set.
     *
     * @return the user, or none
     */
    public @Nullable String getUser() {
        return settings.getProperty("user");
    }

    /**
     * Sets the user's password.
     *
     * @param password the password
     */
    public void setPassword(String password) {
        settings.setProperty("password", password);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection made = new Driver().connect(url, settings);
        if (made == null) {
            throw Errors.misuse(url + " is no URL of the driver: it begins " + Driver.PREFIX);
        }
        return made;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Properties given = new Properties();
        given.putAll(settings);
        given.setProperty("user", username);
        given.setProperty("password", password);
        Connection made = new Driver().connect(url, given);
        if (made == null) {
            throw Errors.misuse(url + " is no URL of the driver: it begins " + Driver.PREFIX);
        }
        return made;
    }

    @Override
    public @Nullable PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(@Nullable PrintWriter out) {
        logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) {
        settings.setProperty("connect_timeout", Integer.toString(seconds));
    }

    @Override
    public int getLoginTimeout() {
        return Integer.parseInt(settings.getProperty("connect_timeout", "0"));
    }

    @Override
    public Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
        throw new java.sql.SQLFeatureNotSupportedException(
                "[DESIGN] D2 the driver logs nothing", Failures.BEYOND_SQLSTATE, Failures.BEYOND_SQLCODE);
    }

    @Override
    public <T> T unwrap(@Nullable Class<T> iface) throws SQLException {
        return Wrapping.unwrap(this, iface);
    }

    @Override
    public boolean isWrapperFor(@Nullable Class<?> iface) {
        return iface != null && iface.isInstance(this);
    }
}
