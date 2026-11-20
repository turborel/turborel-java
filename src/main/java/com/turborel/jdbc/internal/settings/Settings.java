package com.turborel.jdbc.internal.settings;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.auth.ChannelBinding;
import com.turborel.jdbc.internal.auth.KeyCache;
import com.turborel.jdbc.internal.codec.WhiteSpace;
import com.turborel.jdbc.internal.session.Connection;
import com.turborel.jdbc.internal.transport.Timeouts;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * How to connect, as connection settings say ([CLIENT Connecting by settings]): read from layers
 * of {@link Options}, the program's own (or a connection URI's), then a service's section of the
 * service file, then the environment, then the defaults, each setting taken from the first layer
 * that has it. A password none of them gives is looked up in the password file. {@link #connect}
 * connects as they say. As Turborel's {@code client/src/settings.rs}.
 */
public final class Settings {
    /** The port of the native protocol, where a server listens unless told otherwise. */
    public static final int DEFAULT_PORT = 7422;

    /** The host connected to when none is named. */
    public static final String DEFAULT_HOST = "localhost";

    /** The service file read when none is named, in the home directory. */
    public static final String DEFAULT_SERVICE_FILE = ".turborel_service.conf";

    /** The password file read when none is named, in the home directory. */
    public static final String DEFAULT_PASSFILE = ".turborel_pass";

    /** What separates the hosts of a list, and their ports. */
    private static final String LIST = ",";

    /** The greatest port. */
    private static final int MOST_PORT = 0xFFFF;

    /** The greatest whole number of seconds a setting may give, as an unsigned 64-bit number. */
    private static final BigInteger MOST_SECONDS =
            BigInteger.ONE.shiftLeft(Long.SIZE).subtract(BigInteger.ONE);

    /** What {@code String.split} is given to keep the empty strings after the last separator. */
    private static final int KEEP_EMPTY = -1;

    private final List<Host> hosts;
    private final String user;
    private final @Nullable String password;
    private final TlsMode tlsMode;
    private final ChannelBinding channelBinding;
    private final @Nullable String applicationName;
    private final @Nullable Duration connectTimeout;
    private final Path passfile;
    private final TargetRole target;
    private final LoadBalance loadBalance;
    private final TlsFiles tls;
    private final KeyCache keyCache = new KeyCache();

    /**
     * The settings {@code options} give, laid already, the defaults for the rest.
     *
     * @throws TurborelException SQLSTATE 08001 if a setting cannot be read, or no user is named
     */
    private Settings(Options options) throws TurborelException {
        String named = options.get(Options.USER);
        if (named == null) {
            throw Invalid.setting(
                    "[CLIENT Where settings come from] no user is named: by the program, a service, or TURBOREL_USER");
        }
        String host = options.get(Options.HOST);
        String port = options.get(Options.PORT);
        String mode = options.get(Options.TLS_MODE);
        String binding = options.get(Options.CHANNEL_BINDING);
        String timeout = options.get(Options.CONNECT_TIMEOUT);
        String role = options.get(Options.TARGET);
        String balance = options.get(Options.LOAD_BALANCE);
        String systemRoots = options.get(Options.TLS_SYSTEM_ROOTS);
        Path file = path(options, Options.PASSFILE);
        hosts = hosts(host == null ? DEFAULT_HOST : host, port == null ? "" : port);
        user = named;
        password = options.get(Options.PASSWORD);
        tlsMode = mode == null ? TlsMode.PREFER : TlsMode.parse(Options.TLS_MODE, mode);
        channelBinding =
                binding == null ? ChannelBinding.PREFER : Words.channelBinding(Options.CHANNEL_BINDING, binding);
        applicationName = options.get(Options.APPLICATION_NAME);
        connectTimeout = timeout == null ? Timeouts.CONNECT_WITHIN : seconds(Options.CONNECT_TIMEOUT, timeout);
        passfile = file == null ? atHome(DEFAULT_PASSFILE) : file;
        target = role == null ? TargetRole.ANY : TargetRole.parse(Options.TARGET, role);
        loadBalance = balance == null ? LoadBalance.DISABLE : LoadBalance.parse(Options.LOAD_BALANCE, balance);
        tls = new TlsFiles(
                path(options, Options.TLS_ROOTS),
                systemRoots != null && Words.bool(Options.TLS_SYSTEM_ROOTS, systemRoots),
                path(options, Options.TLS_CERTIFICATE),
                path(options, Options.TLS_KEY),
                options.get(Options.SERVER_NAME));
    }

    /**
     * The servers, tried in turn.
     *
     * @return the hosts
     */
    public List<Host> hosts() {
        return hosts;
    }

    /**
     * The user to connect as.
     *
     * @return the user
     */
    public String user() {
        return user;
    }

    /**
     * The user's password, if given; else the password file's ({@link #passwordFor}).
     *
     * @return the password, or none
     */
    public @Nullable String password() {
        return password;
    }

    /**
     * How TLS is used.
     *
     * @return the mode
     */
    public TlsMode tlsMode() {
        return tlsMode;
    }

    /**
     * Whether SCRAM binds its channel.
     *
     * @return the binding
     */
    public ChannelBinding channelBinding() {
        return channelBinding;
    }

    /**
     * The program's name, as it tells the server.
     *
     * @return the name, or none
     */
    public @Nullable String applicationName() {
        return applicationName;
    }

    /**
     * How long each step of connecting waits.
     *
     * @return the wait, or none for no limit
     */
    public @Nullable Duration connectTimeout() {
        return connectTimeout;
    }

    /**
     * The password file.
     *
     * @return its path
     */
    public Path passfile() {
        return passfile;
    }

    /**
     * What a server must be.
     *
     * @return the role
     */
    public TargetRole target() {
        return target;
    }

    /**
     * Whether the servers are tried in order.
     *
     * @return the choice
     */
    public LoadBalance loadBalance() {
        return loadBalance;
    }

    /**
     * The files TLS is made of.
     *
     * @return the files
     */
    public TlsFiles tls() {
        return tls;
    }

    /**
     * The SCRAM keys of the connections these settings make, so a reconnection skips their
     * derivation ([CLIENT The SCRAM key cache]): one cache for every connection they make.
     *
     * @return the cache
     */
    public KeyCache keyCache() {
        return keyCache;
    }

    /**
     * The settings {@code explicit} gives, then the service it or the process's environment names,
     * then the environment, then the defaults.
     *
     * @param explicit the program's own settings
     * @return the settings
     * @throws TurborelException SQLSTATE 08001 if a setting cannot be read, a service named cannot
     *     be read, or no user is named
     */
    public static Settings resolve(Options explicit) throws TurborelException {
        return resolveIn(explicit, System::getenv);
    }

    /**
     * The settings of the connection URI {@code uri}, as {@link #resolve} lays them.
     *
     * @param uri the URI
     * @return the settings
     * @throws TurborelException as {@link Options#fromUri}'s and {@link #resolve}'s
     */
    public static Settings fromUri(String uri) throws TurborelException {
        return resolve(Options.fromUri(uri));
    }

    /**
     * The settings as {@link #resolve} lays them, {@code variable} reading the environment's
     * variables.
     *
     * @param explicit the program's own settings
     * @param variable the environment's variables, by name; none if unset
     * @return the settings
     * @throws TurborelException as {@link #resolve}'s
     */
    public static Settings resolveIn(Options explicit, UnaryOperator<@Nullable String> variable)
            throws TurborelException {
        Options environment = Options.environment(variable);
        Options named = explicit.over(environment);
        Options service = new Options();
        String name = named.get(Options.SERVICE);
        if (name != null) {
            Path file = path(named, Options.SERVICE_FILE);
            service = service(file == null ? atHome(DEFAULT_SERVICE_FILE) : file, name);
        }
        return new Settings(explicit.over(service).over(environment));
    }

    /**
     * A path setting's value.
     *
     * @throws TurborelException SQLSTATE 08001 if this platform takes no such path
     */
    private static @Nullable Path path(Options options, String key) throws TurborelException {
        String value = options.get(key);
        if (value == null) {
            return null;
        }
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            throw Invalid.setting(
                    "[CLIENT Where settings come from] " + key + " is " + value + ", no path: " + e.getMessage());
        }
    }

    /** The file {@code name} in the home directory. */
    private static Path atHome(String name) {
        return Path.of(System.getProperty("user.home", ""), name);
    }

    /** The whole number {@code text} is, as Rust reads an unsigned one: a {@code +} if any, then ASCII digits. */
    private static @Nullable BigInteger whole(String text) {
        String digits = text.startsWith("+") ? text.substring(1) : text;
        if (digits.isEmpty() || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        return new BigInteger(digits);
    }

    /**
     * The port {@code text} is, the value of {@code key}.
     *
     * @throws TurborelException SQLSTATE 08001 if it is no port
     */
    static int port(String key, String text) throws TurborelException {
        BigInteger n = whole(WhiteSpace.trim(text));
        if (n == null || n.compareTo(BigInteger.valueOf(MOST_PORT)) > 0) {
            throw Invalid.setting("[CLIENT Where settings come from] " + key + " is " + text + ", no port");
        }
        return n.intValueExact();
    }

    /**
     * The hosts {@code hosts} names, each of its own port, or the port of the same place in {@code
     * ports}, or the one port {@code ports} gives, or the default.
     *
     * @throws TurborelException SQLSTATE 08001 if a host or port cannot be read
     */
    static List<Host> hosts(String hosts, String ports) throws TurborelException {
        List<Integer> numbers = new ArrayList<>();
        for (String p : ports.split(LIST, KEEP_EMPTY)) {
            if (!WhiteSpace.trim(p).isEmpty()) {
                numbers.add(port(Options.PORT, p));
            }
        }
        String[] names = hosts.split(LIST, KEEP_EMPTY);
        List<Host> found = new ArrayList<>(names.length);
        for (int i = 0; i < names.length; i++) {
            int fallback = i < numbers.size() ? numbers.get(i) : numbers.size() == 1 ? numbers.get(0) : DEFAULT_PORT;
            found.add(host(WhiteSpace.trim(names[i]), fallback));
        }
        return found;
    }

    /**
     * The host {@code name} is: a path, of a local socket; {@code [<address>]} or {@code
     * [<address>]:<port>}, of IPv6; {@code <name>:<port>}; or a name, at {@code port}.
     *
     * @throws TurborelException SQLSTATE 08001 if it cannot be read
     */
    static Host host(String name, int port) throws TurborelException {
        if (name.isEmpty()) {
            throw Invalid.setting("[CLIENT Where settings come from] a host of the list is empty");
        }
        if (name.startsWith("/") || name.startsWith("\\")) {
            return new Host.Local(name);
        }
        if (name.startsWith("[")) {
            int close = name.indexOf(']');
            if (close < 0) {
                throw Invalid.setting("[CLIENT Where settings come from] " + name + " has no ]");
            }
            String after = name.substring(close + 1);
            int at = after.startsWith(":") ? port(Options.HOST, after.substring(1)) : port;
            return new Host.Tcp(name.substring(1, close), at);
        }
        // A name of two colons or more is an IPv6 address unbracketed, of no port.
        int colon = name.indexOf(':');
        if (colon >= 0 && name.indexOf(':', colon + 1) < 0) {
            return new Host.Tcp(name.substring(0, colon), port(Options.HOST, name.substring(colon + 1)));
        }
        return new Host.Tcp(name, port);
    }

    /**
     * The time {@code text} is, in whole seconds; 0 for no limit.
     *
     * @throws TurborelException SQLSTATE 08001 if it is not a whole number of seconds
     */
    static @Nullable Duration seconds(String key, String text) throws TurborelException {
        BigInteger n = whole(WhiteSpace.trim(text));
        if (n == null || n.compareTo(MOST_SECONDS) > 0) {
            throw Invalid.setting(
                    "[CLIENT Where settings come from] " + key + " is " + text + ", not a whole number of seconds");
        }
        return n.signum() == 0
                ? null
                : Duration.ofSeconds(n.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue());
    }

    /**
     * The password to prove at {@code host}: the settings', else the password file's for {@code
     * host}, its port and the user, if the file can be read ([CLIENT The password file]).
     *
     * @param host the server
     * @return the password, or none
     */
    public @Nullable String passwordFor(Host host) {
        if (password != null) {
            return password;
        }
        String text = SettingFiles.readRegular(passfile);
        return text == null
                ? null
                : SettingFiles.password(text, host.passHost(), Integer.toString(host.passPort()), user);
    }

    /**
     * The settings of the section {@code name} of the service file {@code file} ([CLIENT The
     * service file]).
     *
     * @param file the service file
     * @param name the section
     * @return its settings
     * @throws TurborelException SQLSTATE 08001 if it cannot be read, a line is malformed, or it has
     *     no section {@code name}
     */
    public static Options service(Path file, String name) throws TurborelException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw Invalid.setting("[CLIENT Where settings come from] cannot read " + file + ": " + e.getMessage());
        }
        return SettingFiles.service(text, name, file);
    }

    /**
     * A connection to the first server, of the hosts in the order the settings say, that can be
     * reached, logs the user in and is of the role asked ([CLIENT Several servers]).
     *
     * @return the connection
     * @throws TurborelException the one server's failure, if there was one; else SQLSTATE 08001,
     *     saying why each was not kept, of SQLSTATE 28000 where the last was a refusal of the user;
     *     SQLSTATE 28000 before any connection, for a channel binding required that settings
     *     cannot give
     */
    public Connection connect() throws TurborelException {
        return Failover.connect(this);
    }

    /** Never the password. */
    @Override
    public String toString() {
        return "Settings[hosts=" + hosts + ", user=" + user + ", tlsMode=" + tlsMode + ", target=" + target + ", ...]";
    }
}
