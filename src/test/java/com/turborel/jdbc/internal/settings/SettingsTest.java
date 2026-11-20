package com.turborel.jdbc.internal.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.auth.ChannelBinding;
import com.turborel.jdbc.internal.transport.Timeouts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Connection settings: the URI, the service file, the password file, the environment, and the
 * order the layers are laid in, as Turborel's {@code client/src/settings/tests.rs} reads them
 * (RULES.md R-TEST-9).
 */
class SettingsTest {
    @TempDir
    Path scratch;

    /** An environment of {@code pairs}, as {@link Settings#resolveIn} reads one. */
    private static UnaryOperator<@Nullable String> environment(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map::get;
    }

    private Path file(String name, String text) throws IOException {
        Path path = scratch.resolve(name);
        Files.writeString(path, text, StandardCharsets.UTF_8);
        return path;
    }

    private static String refused(TurborelException e) {
        assertEquals(-919, e.getErrorCode());
        return String.valueOf(e.getSQLState());
    }

    /**
     * Verifies: [CLIENT The connection URI] a URI gives its user, password, hosts each of its port,
     * an IPv6 address in brackets, a local socket percent-encoded, and its query's settings;
     * percent-encoding is decoded in each part; the password is never shown.
     */
    @Test
    void aUriGivesItsUserHostsAndSettings() throws TurborelException {
        String uri = "turborel://ann:p%40ss@db1:7000,[::1]:7001,db3,%2Frun%2Fs.sock/"
                + "?tls_mode=verify-full&target=read-write&connect_timeout=5";
        Options options = Options.fromUri(uri);
        assertEquals("ann", options.get(Options.USER));
        assertEquals("p@ss", options.get(Options.PASSWORD));
        Settings settings = Settings.resolveIn(options, environment());
        assertEquals(
                List.of(
                        new Host.Tcp("db1", 7000),
                        new Host.Tcp("::1", 7001),
                        new Host.Tcp("db3", Settings.DEFAULT_PORT),
                        new Host.Local("/run/s.sock")),
                settings.hosts());
        assertEquals(TlsMode.VERIFY_FULL, settings.tlsMode());
        assertEquals(TargetRole.READ_WRITE, settings.target());
        assertEquals(Duration.ofSeconds(5), settings.connectTimeout());
        assertFalse((options + " " + settings).contains("p@ss"), "the password is never shown");
        assertEquals("p@ss", settings.password());
        Settings process = Settings.fromUri("turborel://ann@db9/?" + Options.TLS_MODE + "=disable");
        assertEquals("ann", process.user(), "the program's user over the process's environment");
        assertEquals(
                TlsMode.DISABLE,
                Settings.resolve(Options.fromUri("turborel://ann@db9/?tls_mode=disable"))
                        .tlsMode());
        Settings bare = Settings.resolveIn(Options.fromUri("turborel://ann@/"), environment());
        assertEquals(List.of(new Host.Tcp(Settings.DEFAULT_HOST, Settings.DEFAULT_PORT)), bare.hosts());
        assertEquals(Timeouts.CONNECT_WITHIN, bare.connectTimeout(), "30 seconds by default");
        assertEquals(Path.of(System.getProperty("user.home"), ".turborel_pass"), bare.passfile());
        assertEquals(TlsMode.PREFER, bare.tlsMode(), "prefer by default");
        assertEquals(TargetRole.ANY, bare.target(), "any by default");
        assertEquals(LoadBalance.DISABLE, bare.loadBalance(), "disable by default");
        assertEquals(
                new TlsFiles(null, false, null, null, null),
                bare.tls(),
                "no roots, certificate or key, nor the platform's");
        assertTrue(Options.fromUri("turborel://").isEmpty());
        assertEquals(
                "bob",
                Options.fromUri("turborel://ann@h?&&user=bob&").get(Options.USER),
                "empty pairs skipped, the query's user set after the authority's");
    }

    /**
     * Verifies: [CLIENT The connection URI] a URI of another scheme, of a path, of a key that names
     * no setting, of a pair without {@code =}, of a {@code %} not followed by two hexadecimal
     * digits, or not UTF-8 once decoded, is refused (SQLSTATE 08001); so is a word no setting
     * takes, a port that is no number, and no user.
     */
    @Test
    void aMalformedUriOrSettingIsRefused() {
        for (String uri : List.of(
                "other://a@b",
                "turborel://a@b/db",
                "turborel://a@b/?colour=red",
                "turborel://a@b/?tls_mode",
                "turborel://a%4@b",
                "turborel://a%@b",
                "turborel://a%zz@b",
                "turborel://a%ff@b")) {
            assertEquals("08001", refused(assertThrows(TurborelException.class, () -> Options.fromUri(uri))), uri);
        }
        for (String uri : List.of(
                "turborel://a@b/?tls_mode=always",
                "turborel://a@b:port",
                "turborel://a@b/?connect_timeout=-1",
                "turborel://b")) {
            assertEquals(
                    "08001",
                    refused(assertThrows(
                            TurborelException.class, () -> Settings.resolveIn(Options.fromUri(uri), environment()))),
                    uri);
        }
    }

    /**
     * Verifies: [CLIENT Where settings come from] a setting the program gives prevails over the
     * service's, which prevails over the environment's, which prevails over the default; the
     * environment names the service and its file when the program does not; an empty variable
     * gives nothing.
     */
    @Test
    void settingsAreLaidProgramServiceEnvironmentDefault() throws TurborelException, IOException {
        Path file = file(
                "precedence",
                "# servers\n[prod]\nhost = prod-db\nport = 7500\ntls_mode = require\n\n[test]\nhost = test-db\n");
        UnaryOperator<@Nullable String> env = environment(
                "TURBOREL_SERVICE", "prod",
                "TURBOREL_SERVICE_FILE", file.toString(),
                "TURBOREL_USER", "env-user",
                "TURBOREL_PORT", "7600",
                "TURBOREL_TLS_MODE", "disable",
                "TURBOREL_APPLICATION_NAME", "");
        Options explicit = new Options().set(Options.TLS_MODE, "verify-ca");
        Settings settings = Settings.resolveIn(explicit, env);
        assertEquals(List.of(new Host.Tcp("prod-db", 7500)), settings.hosts(), "the service over the environment");
        assertEquals(TlsMode.VERIFY_CA, settings.tlsMode(), "the program over the service");
        assertEquals("env-user", settings.user(), "the environment where the service says nothing");
        assertNull(settings.applicationName());
        assertEquals(ChannelBinding.PREFER, settings.channelBinding(), "the default");
        explicit.set(Options.SERVICE, "test");
        Settings chosen = Settings.resolveIn(explicit, env);
        assertEquals(List.of(new Host.Tcp("test-db", 7600)), chosen.hosts(), "the program names the service");
    }

    /**
     * Verifies: [CLIENT The service file] a service file's section is its lines' settings, a
     * section given twice its settings together; a section that is not there, a setting before
     * any section, a line without {@code =}, a key that names no setting, and a service or service
     * file set by a service are refused, naming the line.
     */
    @Test
    void aServiceFileIsReadBySection() throws TurborelException {
        Path file = Path.of("services.conf");
        Options options =
                SettingFiles.service("[a]\r\nuser=ann\n[b]\nuser = bob\n  host =  h  \n[a]\n[b]\nport=1\n", "b", file);
        assertEquals("bob", options.get(Options.USER));
        assertEquals("h", options.get(Options.HOST));
        assertEquals("1", options.get(Options.PORT));
        assertTrue(String.valueOf(
                        assertThrows(TurborelException.class, () -> SettingFiles.service("[a]\nuser=ann\n", "b", file))
                                .getMessage())
                .contains("has no service b"));
        Map<String, String> cases = Map.of(
                "user=ann\n", "line 1: a setting comes before any section",
                "[a]\nuser\n", "line 2: a setting is <key> = <value>",
                "[a]\ncolour=red\n", "line 2: colour is no connection setting",
                "[a]\nservice=b\n", "line 2: a service cannot set service",
                "[a]\nservice_file=b\n", "line 2: a service cannot set service_file",
                "[a\n", "line 1: a section's name ends with ]");
        for (Map.Entry<String, String> each : cases.entrySet()) {
            TurborelException e =
                    assertThrows(TurborelException.class, () -> SettingFiles.service(each.getKey(), "a", file));
            assertEquals("08001", refused(e));
            assertTrue(String.valueOf(e.getMessage()).contains(each.getValue()), e.getMessage());
        }
        assertEquals(List.of("a", "b", "", "c\r"), SettingFiles.lines("a\r\nb\n\nc\r"));
    }

    /**
     * Verifies: [CLIENT The password file] the password file gives the password of its first line
     * matching host, port and user, {@code *} matching any, {@code \:} and {@code \\} taken as they
     * are, an escaped {@code \*} matching only {@code *}; comments and short lines are skipped; the
     * settings' own password prevails over it; a file that cannot be read gives none.
     */
    @Test
    void aPasswordFileMatchesByHostPortAndUser() throws TurborelException, IOException {
        String text =
                "# passwords\nshort:line\ndb1:7422:ann:first\n*:*:ann:any\\:host\ndb\\:2:*:bob:c:\\\\d\n\\*:1:carl:star\n";
        assertEquals("first", SettingFiles.password(text, "db1", "7422", "ann"));
        assertEquals("any:host", SettingFiles.password(text, "db9", "1", "ann"));
        assertEquals("c:\\d", SettingFiles.password(text, "db:2", "7", "bob"));
        assertNull(SettingFiles.password(text, "x", "1", "carl"), "an escaped * is a star");
        assertEquals("star", SettingFiles.password(text, "*", "1", "carl"));
        assertNull(SettingFiles.password(text, "db1", "7422", "nobody"));
        assertEquals("é", SettingFiles.password("  # comment\n*:*:*:é", "h", "1", "u"));
        assertNull(SettingFiles.password("h:1:u:p", "h", "2", "u"), "of another port");
        Files.write(scratch.resolve("latin1"), new byte[] {'*', ':', '*', ':', '*', ':', (byte) 0xE9});
        assertNull(SettingFiles.readRegular(scratch.resolve("latin1")), "a file not UTF-8 gives none");
        Path file = file("passfile", text);
        Options explicit = new Options()
                .set(Options.USER, "ann")
                .set(Options.PASSFILE, file.toString())
                .set(Options.HOST, "db1");
        Settings settings = Settings.resolveIn(explicit, environment());
        assertEquals("first", settings.passwordFor(new Host.Tcp("db1", 7422)));
        explicit.set(Options.PASSWORD, "given");
        Settings given = Settings.resolveIn(explicit, environment());
        assertEquals("given", given.passwordFor(new Host.Tcp("db1", 7422)));
        Files.delete(file);
        assertNull(settings.passwordFor(new Host.Tcp("db1", 7422)), "a file that cannot be read gives none");
        Files.createDirectory(file);
        assertNull(settings.passwordFor(new Host.Tcp("db1", 7422)), "a directory is no regular file");
    }

    /**
     * Verifies: [CLIENT Where settings come from] every variable of the environment gives its
     * setting, and the words of each kind are read as named.
     */
    @Test
    void theEnvironmentAndWordsAreRead() throws TurborelException {
        String[] pairs = new String[Options.ENVIRONMENT.size() * 2];
        for (int i = 0; i < Options.ENVIRONMENT.size(); i++) {
            pairs[2 * i] = Options.ENVIRONMENT.get(i).getKey();
            pairs[2 * i + 1] = "v";
        }
        Options options = Options.environment(environment(pairs));
        for (Map.Entry<String, String> each : Options.ENVIRONMENT) {
            assertEquals("v", options.get(each.getValue()), each.getKey());
        }
        assertEquals(TlsMode.PREFER, TlsMode.parse(Options.TLS_MODE, "prefer"));
        assertTrue(TlsMode.VERIFY_CA.verifies() && TlsMode.VERIFY_FULL.verifies());
        assertFalse(TlsMode.REQUIRE.verifies());
        assertEquals(ChannelBinding.REQUIRE, Words.channelBinding(Options.CHANNEL_BINDING, "require"));
        assertEquals(LoadBalance.RANDOM, LoadBalance.parse(Options.LOAD_BALANCE, "random"));
        assertTrue(String.valueOf(
                        assertThrows(TurborelException.class, () -> TargetRole.parse(Options.TARGET, "primary"))
                                .getMessage())
                .contains("any, read-write, read-only"));
        Options both = new Options()
                .set(Options.HOST, "a,b")
                .set(Options.PORT, "1,2")
                .set(Options.USER, "u")
                .set(Options.TLS_SYSTEM_ROOTS, "true")
                .set(Options.APPLICATION_NAME, "app")
                .set(Options.CONNECT_TIMEOUT, "0")
                .set(Options.LOAD_BALANCE, "random")
                .set(Options.CHANNEL_BINDING, "disable")
                .set(Options.TLS_ROOTS, "ca.pem")
                .set(Options.TLS_CERTIFICATE, "c.pem")
                .set(Options.TLS_KEY, "k.pem")
                .set(Options.SERVER_NAME, "db");
        Settings settings = Settings.resolveIn(both, environment());
        assertEquals(List.of(new Host.Tcp("a", 1), new Host.Tcp("b", 2)), settings.hosts());
        assertEquals(new TlsFiles(Path.of("ca.pem"), true, Path.of("c.pem"), Path.of("k.pem"), "db"), settings.tls());
        assertEquals("app", settings.applicationName());
        assertNull(settings.connectTimeout(), "0 is no limit");
        assertEquals(LoadBalance.RANDOM, settings.loadBalance());
        assertEquals(ChannelBinding.DISABLE, settings.channelBinding());
        assertFalse(Settings.resolveIn(both.set(Options.TLS_SYSTEM_ROOTS, "false"), environment())
                .tls()
                .systemRoots());
        assertEquals(options, options.over(new Options()));
        assertEquals(options.hashCode(), options.over(new Options()).hashCode());
        assertFalse(options.equals(both));
        assertTrue(options.toString().contains("password=...")
                && !options.toString().contains("password=v"));
    }

    /**
     * Verifies: [CLIENT Where settings come from] a list of hosts with an empty one, an IPv6
     * address whose bracket is not closed or whose port is no number, a list of ports with one that
     * is no number or past 65535, and a timeout past 64 bits are each refused (SQLSTATE 08001); a
     * timeout is read as Rust reads an unsigned number, trimmed, a {@code +} allowed.
     */
    @Test
    void malformedHostsAndPortsAreRefused() throws TurborelException {
        for (String host : List.of("a,,b", "[::1", "[::1]:x", "", "a:")) {
            assertEquals(
                    "08001", refused(assertThrows(TurborelException.class, () -> resolved(Options.HOST, host))), host);
        }
        assertEquals(
                "08001",
                refused(assertThrows(TurborelException.class, () -> resolved(Options.HOST, "a", Options.PORT, "1,x"))));
        assertEquals(
                "08001",
                refused(assertThrows(
                        TurborelException.class, () -> resolved(Options.HOST, "a", Options.PORT, "65536"))));
        assertEquals(
                "08001",
                refused(assertThrows(
                        TurborelException.class, () -> resolved(Options.CONNECT_TIMEOUT, "18446744073709551616"))));
        assertEquals(
                Duration.ofSeconds(Long.MAX_VALUE),
                Settings.seconds(Options.CONNECT_TIMEOUT, "18446744073709551615"),
                "a 64-bit number of seconds, as long as Java keeps");
        assertEquals(Duration.ofSeconds(9), Settings.seconds(Options.CONNECT_TIMEOUT, " +9 "));
    }

    /**
     * Verifies: [CLIENT Where settings come from] a word no channel binding, target, load balance
     * or truth takes is refused (SQLSTATE 08001); several hosts of one port each take it; a host's
     * own port, of a name or a bracketed address, prevails; an address of two colons or more is
     * IPv6 of no port; a path names a local socket or named pipe; a service file that cannot be
     * read is refused, named or the home directory's.
     */
    @Test
    void wordsHostsAndServiceFilesAreReadOrRefused() throws TurborelException {
        for (String key :
                List.of(Options.CHANNEL_BINDING, Options.TARGET, Options.LOAD_BALANCE, Options.TLS_SYSTEM_ROOTS)) {
            assertEquals(
                    "08001", refused(assertThrows(TurborelException.class, () -> resolved(key, "sometimes"))), key);
        }
        assertEquals(
                List.of(new Host.Tcp("a", 9), new Host.Tcp("b", 9), new Host.Tcp("::1", 9)),
                resolved(Options.HOST, "a,b,[::1]", Options.PORT, "9").hosts());
        assertEquals(
                List.of(new Host.Tcp("a", 1), new Host.Tcp("b", 2), new Host.Tcp("c", Settings.DEFAULT_PORT)),
                resolved(Options.HOST, "a:1, [b]:2 ,c", Options.PORT, "5,6").hosts(),
                "a host's own port prevails; of two ports, a third host takes the default");
        assertEquals(new Host.Tcp("fe80::1:2", 4), Settings.host("fe80::1:2", 4));
        assertEquals(new Host.Tcp("::1", 4), Settings.host("[::1]junk", 4));
        assertEquals(new Host.Local("\\\\.\\pipe\\turborel"), Settings.host("\\\\.\\pipe\\turborel", 4));
        Path missing = Path.of(
                System.getProperty("java.io.tmpdir"),
                "turborel-java-no-service-" + ProcessHandle.current().pid());
        assertEquals("08001", refused(assertThrows(TurborelException.class, () -> Settings.service(missing, "s"))));
        assertEquals(
                "08001",
                refused(assertThrows(
                        TurborelException.class,
                        () -> Settings.resolveIn(
                                new Options().set(Options.USER, "u").set(Options.SERVICE, "s"),
                                environment("TURBOREL_SERVICE_FILE", missing.toString())))));
        assertEquals(
                "08001",
                refused(assertThrows(
                        TurborelException.class,
                        () -> Settings.resolveIn(new Options().set(Options.SERVICE, "s"), environment()))),
                "no file named: the home directory's, absent or without the section");
    }

    /** The settings of user u and the settings {@code pairs} give, with no environment. */
    private static Settings resolved(String... pairs) throws TurborelException {
        Options options = new Options().set(Options.USER, "u");
        for (int i = 0; i < pairs.length; i += 2) {
            options.set(pairs[i], pairs[i + 1]);
        }
        return Settings.resolveIn(options, environment());
    }

    /**
     * Verifies: [CLIENT The password file] the password file is matched for a local socket by its
     * path and the default port.
     */
    @Test
    void aPasswordFileMatchesALocalSocketByItsPath() throws TurborelException, IOException {
        Path file = file("passfile-local", "/run/s.sock:" + Settings.DEFAULT_PORT + ":ann:local\n");
        Options explicit = new Options().set(Options.USER, "ann").set(Options.PASSFILE, file.toString());
        Settings settings = Settings.resolveIn(explicit, environment());
        assertEquals("local", settings.passwordFor(new Host.Local("/run/s.sock")));
        assertEquals(Path.of("/run/s.sock"), new Host.Local("/run/s.sock").path());
        TurborelException nul = assertThrows(TurborelException.class, () -> new Host.Local("/run/\0").path());
        assertEquals("08006", nul.getSQLState());
        assertEquals(
                "08001",
                refused(assertThrows(TurborelException.class, () -> resolved(Options.PASSFILE, "pass\0"))),
                "a path no platform takes");
    }

    /**
     * Verifies: [CLIENT Where settings come from] Rust's white space is trimmed from a setting, a
     * no-break space among it, as the reference trims it; a setting of a name not in the table is
     * refused.
     */
    @Test
    void settingsAreTrimmedAsTheReferenceTrims() throws TurborelException {
        assertEquals(
                List.of(new Host.Tcp("h", 7)),
                resolved(Options.HOST, "\u00a0h\u00a0", Options.PORT, "\u20077").hosts());
        assertEquals("08001", refused(assertThrows(TurborelException.class, () -> new Options().set("colour", "red"))));
    }
}
