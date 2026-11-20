package com.turborel.jdbc.internal.settings;

import com.turborel.jdbc.TurborelException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * A layer of connection settings, each named by one of {@link #KEYS} ([CLIENT Where settings come
 * from]), as a program, a URI, a service's section or the environment gives them. The layers are
 * laid one over another, each setting taken from the first that has it, then read into {@link
 * Settings}. As Turborel's {@code client/src/settings/options.rs}.
 */
public final class Options {
    /** The settings' names, as the program, a URI's query and a service file write them. */
    public static final String HOST = "host";
    /** See {@link #HOST}. */
    public static final String PORT = "port";
    /** See {@link #HOST}. */
    public static final String USER = "user";
    /** See {@link #HOST}. */
    public static final String PASSWORD = "password";
    /** See {@link #HOST}. */
    public static final String TLS_MODE = "tls_mode";
    /** See {@link #HOST}. */
    public static final String CHANNEL_BINDING = "channel_binding";
    /** See {@link #HOST}. */
    public static final String APPLICATION_NAME = "application_name";
    /** See {@link #HOST}. */
    public static final String CONNECT_TIMEOUT = "connect_timeout";
    /** See {@link #HOST}. */
    public static final String SERVICE = "service";
    /** See {@link #HOST}. */
    public static final String SERVICE_FILE = "service_file";
    /** See {@link #HOST}. */
    public static final String PASSFILE = "passfile";
    /** See {@link #HOST}. */
    public static final String TARGET = "target";
    /** See {@link #HOST}. */
    public static final String LOAD_BALANCE = "load_balance";
    /** See {@link #HOST}. */
    public static final String TLS_ROOTS = "tls_roots";
    /** See {@link #HOST}. */
    public static final String TLS_SYSTEM_ROOTS = "tls_system_roots";
    /** See {@link #HOST}. */
    public static final String TLS_CERTIFICATE = "tls_certificate";
    /** See {@link #HOST}. */
    public static final String TLS_KEY = "tls_key";
    /** See {@link #HOST}. */
    public static final String SERVER_NAME = "server_name";

    /** Every setting's name. */
    public static final List<String> KEYS = List.of(
            HOST,
            PORT,
            USER,
            PASSWORD,
            TLS_MODE,
            CHANNEL_BINDING,
            APPLICATION_NAME,
            CONNECT_TIMEOUT,
            SERVICE,
            SERVICE_FILE,
            PASSFILE,
            TARGET,
            LOAD_BALANCE,
            TLS_ROOTS,
            TLS_SYSTEM_ROOTS,
            TLS_CERTIFICATE,
            TLS_KEY,
            SERVER_NAME);

    /** The environment's variables, each the setting it gives, in order. */
    public static final List<Map.Entry<String, String>> ENVIRONMENT = List.of(
            Map.entry("TURBOREL_HOST", HOST),
            Map.entry("TURBOREL_PORT", PORT),
            Map.entry("TURBOREL_USER", USER),
            Map.entry("TURBOREL_PASSWORD", PASSWORD),
            Map.entry("TURBOREL_TLS_MODE", TLS_MODE),
            Map.entry("TURBOREL_APPLICATION_NAME", APPLICATION_NAME),
            Map.entry("TURBOREL_CONNECT_TIMEOUT", CONNECT_TIMEOUT),
            Map.entry("TURBOREL_SERVICE", SERVICE),
            Map.entry("TURBOREL_SERVICE_FILE", SERVICE_FILE),
            Map.entry("TURBOREL_PASSFILE", PASSFILE));

    private final Map<String, String> values;

    /** No settings. */
    public Options() {
        this(new TreeMap<>());
    }

    private Options(Map<String, String> values) {
        this.values = values;
    }

    /**
     * The settings the connection URI {@code uri} gives ([CLIENT The connection URI]).
     *
     * @param uri the URI, {@code turborel://...}
     * @return its settings
     * @throws TurborelException SQLSTATE 08001 if it is not one: another scheme, a path, a key
     *     that names no setting, a value without {@code =}, or bad percent-encoding
     */
    public static Options fromUri(String uri) throws TurborelException {
        return Uri.options(uri);
    }

    /**
     * Sets {@code key} to {@code value}.
     *
     * @param key the setting's name, one of {@link #KEYS}
     * @param value its value
     * @return these settings, to set more
     * @throws TurborelException SQLSTATE 08001 if {@code key} names no setting
     */
    public Options set(String key, String value) throws TurborelException {
        if (!KEYS.contains(key)) {
            throw Invalid.setting("[CLIENT Where settings come from] " + key + " is no connection setting");
        }
        values.put(key, value);
        return this;
    }

    /**
     * The value of {@code key}, if set.
     *
     * @param key the setting's name
     * @return its value, or none
     */
    public @Nullable String get(String key) {
        return values.get(key);
    }

    /**
     * Whether none is set.
     *
     * @return whether empty
     */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * These settings, laid over {@code lower}: each of {@code lower}'s kept where these have none.
     *
     * @param lower the settings beneath
     * @return the settings of both
     */
    public Options over(Options lower) {
        Map<String, String> both = new TreeMap<>(lower.values);
        both.putAll(values);
        return new Options(both);
    }

    /**
     * The settings the environment gives, {@code variable} reading each of {@link #ENVIRONMENT}'s
     * variables: an empty one gives none.
     *
     * @param variable the environment's variables, by name; none if unset
     * @return the settings
     */
    public static Options environment(UnaryOperator<@Nullable String> variable) {
        Map<String, String> found = new TreeMap<>();
        for (Map.Entry<String, String> each : ENVIRONMENT) {
            String value = variable.apply(each.getKey());
            if (value != null && !value.isEmpty()) {
                found.put(each.getValue(), value);
            }
        }
        return new Options(found);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof Options that && values.equals(that.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    /** Never the password. */
    @Override
    public String toString() {
        StringBuilder shown = new StringBuilder("{");
        for (Map.Entry<String, String> each : values.entrySet()) {
            if (shown.length() > 1) {
                shown.append(", ");
            }
            shown.append(each.getKey()).append('=').append(PASSWORD.equals(each.getKey()) ? "..." : each.getValue());
        }
        return shown.append('}').toString();
    }
}
