package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.Failures;
import com.turborel.jdbc.internal.settings.Options;
import com.turborel.jdbc.internal.settings.Settings;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The JDBC driver for turborel ([DESIGN] D2), found by {@code ServiceLoader}, and registered with
 * {@code DriverManager} as its class loads. Its URLs are connection URIs with {@code jdbc:} before
 * them:
 *
 * <pre>
 * jdbc:turborel://[user[:password]@][host[:port][,...]][/][?setting=value[&amp;...]]
 * </pre>
 *
 * <p>Each setting is turborel-client's ([CLIENT Where settings come from]); a property given with
 * the URL sets the setting of its name, {@code user} and {@code password} among them, over the
 * URL's, and a property of another name is passed over. The settings are laid over a service's, the
 * environment's and the defaults, as connection settings are.
 */
public final class Driver implements java.sql.Driver {
    /** What a URL of the driver begins with. */
    public static final String PREFIX = "jdbc:turborel:";

    /** What follows {@code jdbc:} in a URL of the driver: a connection URI. */
    private static final String JDBC = "jdbc:";

    static {
        try {
            DriverManager.registerDriver(new Driver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** A driver; {@code DriverManager} and {@code ServiceLoader} make one. */
    public Driver() {
        // Nothing to set up: each connection is made of its own settings.
    }

    /**
     * The settings of {@code url} and {@code info}, as a connection of the driver takes them.
     *
     * @param url the URL
     * @param info its properties, or none
     * @return the settings
     * @throws SQLException SQLSTATE 08001 if a setting cannot be read
     */
    static Settings settings(String url, @Nullable Properties info) throws SQLException {
        Options options = Options.fromUri(url.substring(JDBC.length()));
        if (info != null) {
            for (String name : info.stringPropertyNames()) {
                String key = name.toLowerCase(Locale.ROOT);
                if (Options.KEYS.contains(key)) {
                    options.set(key, info.getProperty(name));
                }
            }
        }
        return Settings.resolve(options);
    }

    @Override
    public @Nullable Connection connect(String url, @Nullable Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        return new JdbcConnection(settings(url, info).connect(), url);
    }

    @Override
    public boolean acceptsURL(@Nullable String url) {
        return url != null && url.startsWith(PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, @Nullable Properties info) {
        List<DriverPropertyInfo> found = new ArrayList<>();
        for (String key : Options.KEYS) {
            DriverPropertyInfo each = new DriverPropertyInfo(key, info == null ? null : info.getProperty(key));
            each.required = key.equals(Options.USER);
            each.description = "the connection setting " + key + ", as turborel-client's settings name it";
            found.add(each);
        }
        return found.toArray(new DriverPropertyInfo[0]);
    }

    @Override
    public int getMajorVersion() {
        return 0;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
        throw new java.sql.SQLFeatureNotSupportedException(
                "[DESIGN] D2 the driver logs nothing", Failures.BEYOND_SQLSTATE, Failures.BEYOND_SQLCODE);
    }
}
