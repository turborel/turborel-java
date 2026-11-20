# Settings

[Contents](README.md)

## NAME

`Settings`, `Options`, `Host`, `Role`: connecting by settings from
outside the program, as turborel-client gives them

## SYNOPSIS

```java
package com.turborel.jdbc.internal.settings;

public final class Settings {
    public static final int DEFAULT_PORT = 7422;
    public static final String DEFAULT_HOST = "localhost";
    public static final String DEFAULT_SERVICE_FILE = ".turborel_service.conf";
    public static final String DEFAULT_PASSFILE = ".turborel_pass";
    public static Settings resolve(Options explicit) throws TurborelException;
    public static Settings resolveIn(Options explicit, UnaryOperator<@Nullable String> variable)
            throws TurborelException;
    public static Settings fromUri(String uri) throws TurborelException;
    public static Options service(Path file, String name) throws TurborelException;
    public Connection connect() throws TurborelException;
    public @Nullable String passwordFor(Host host);
    public List<Host> hosts();
    public String user();
    public @Nullable String password();
    public TlsMode tlsMode();
    public ChannelBinding channelBinding();
    public @Nullable String applicationName();
    public @Nullable Duration connectTimeout();
    public Path passfile();
    public TargetRole target();
    public LoadBalance loadBalance();
    public TlsFiles tls();
    public KeyCache keyCache();
}

public final class Options {
    public static final String HOST = "host", PORT = "port", USER = "user", PASSWORD = "password",
            TLS_MODE = "tls_mode", CHANNEL_BINDING = "channel_binding",
            APPLICATION_NAME = "application_name", CONNECT_TIMEOUT = "connect_timeout",
            SERVICE = "service", SERVICE_FILE = "service_file", PASSFILE = "passfile",
            TARGET = "target", LOAD_BALANCE = "load_balance", TLS_ROOTS = "tls_roots",
            TLS_SYSTEM_ROOTS = "tls_system_roots", TLS_CERTIFICATE = "tls_certificate",
            TLS_KEY = "tls_key", SERVER_NAME = "server_name";
    public static final List<String> KEYS;
    public static final List<Map.Entry<String, String>> ENVIRONMENT;
    public Options();
    public static Options fromUri(String uri) throws TurborelException;
    public static Options environment(UnaryOperator<@Nullable String> variable);
    public Options set(String key, String value) throws TurborelException;
    public @Nullable String get(String key);
    public boolean isEmpty();
    public Options over(Options lower);
}

public final class Uri { public static final String SCHEME = "turborel://"; }

public sealed interface Host {
    record Tcp(String name, int port) implements Host {}
    record Local(Path path) implements Host {}
    String passHost();
    int passPort();
}

public record TlsFiles(@Nullable Path roots, boolean systemRoots, @Nullable Path certificate,
        @Nullable Path key, @Nullable String serverName) {}

public enum TlsMode { DISABLE, PREFER, REQUIRE, VERIFY_CA, VERIFY_FULL;
    public static TlsMode parse(String key, String text) throws TurborelException;
    public boolean verifies(); }
public enum TargetRole { ANY, READ_WRITE, READ_ONLY;
    public static TargetRole parse(String key, String text) throws TurborelException; }
public enum LoadBalance { DISABLE, RANDOM;
    public static LoadBalance parse(String key, String text) throws TurborelException; }

public enum Role { READ_WRITE, READ_ONLY, UNKNOWN;
    public static final String ROLE_QUERY = "SELECT ROLE FROM INFORMATION_SCHEMA.REPLICAS WHERE IS_SELF = 'YES'";
    public static final String SERVER_READ_ONLY = "SERVER_READ_ONLY";
    public static Role of(Connection connection) throws TurborelException;
    public String describe(); }
```

`ChannelBinding` and `KeyCache` are of `com.turborel.jdbc.internal.auth`.
These packages are not exported by the module (RULES.md D2).

## DESCRIPTION

**Layers.** `resolveIn` lays the program's `Options` over the section
of the service file they, or the environment, name; that over the
environment, read by `variable` from each of `ENVIRONMENT`'s names, an
empty one giving nothing; and that over the defaults. `resolve` reads
the process's environment, and `fromUri` resolves a URI's options. A
password none gives is the password file's, looked up by `passwordFor`
for each server tried. `over` lays one layer over another, each
setting taken from the first that has it.

**The URI.** `Options.fromUri` reads
`turborel://[user[:password]@][host[:port][,...]][/][?setting=value[&...]]`,
each part percent-encoded, an IPv6 address in brackets. A URI names no
database, so its path is empty or `/` alone.

**Hosts.** `host` is a comma-separated list, each `name`, `name:port`,
`[address]` or `[address]:port`; a name of two colons or more is an
IPv6 address of no port; a path, beginning `/` or `\`, names a Unix
socket, or on Windows a named pipe. A host of no port takes `port`'s of
its place in the list, or `port`'s one, or 7422.

**Files.** A service file's sections are `[name]` lines, each followed
by `setting = value` lines; `#` begins a comment line. A password
file's lines are `host:port:user:password`, `*` matching any, `\`
escaping the next character, the first match taken, a local socket
matched by its path and port 7422; it is read only if a regular file.

**Connecting.** `connect` tries each host in order, or in an order
drawn at random for each connection under `load_balance=random`, at
each address its name resolves to, each step within `connect_timeout`.
It refuses, before connecting, a channel binding required that the
settings cannot give: no password for SCRAM, or a connection not over
TLS. It opens the connection over TLS as `tls_mode` says, `prefer`
connecting again over plain TCP after a handshake that fails, and a
local socket with none; proves the user by SCRAM-SHA-256 with the
password, binding its channel as `channel_binding` says and keeping its
keys in `keyCache`; else by the certificate `tls_certificate` names,
presented with `tls_key`; else by trust. A secret mechanism over plain
TCP is refused before anything is sent. It asks for every capability
but compression, and sends `application_name` as the attribute
`application`. Under `target`, it asks the server's role, as `Role.of`
does, and leaves a server of another. A server not kept, it tries the
next.

**Roles.** `Role.of` reads SERVER_READ_ONLY, as the server told it,
`YES` being read-only; of a server that does not tell it, it runs
`ROLE_QUERY` as its own transaction, the session's autocommit kept: a
server alone, which has no REPLICAS, is read-write; a row PRIMARY is
read-write, another read-only; no row, as a user not the owner sees,
is unknown.

**The key cache.** Each `Settings` keeps one `KeyCache` for every
connection it makes, so a reconnection that meets the same salt and
iterations skips the SCRAM derivation.

## ERRORS

| SQLCODE | SQLSTATE | When |
|---|---|---|
| -919 | 08001 | A URI of another scheme, of a path, of a setting not in the table, of a pair without `=`, or of bad percent-encoding; a value no setting takes; a service file that cannot be read, of a malformed line, naming its file and line, or without the section; no user named; no server kept, naming each address and why; a server not of the role asked |
| -919 | 28000 | A channel binding required that the settings cannot give, before any connection; a secret mechanism over plain TCP, before anything is sent; no server kept, where the last refused the user |
| -919 | 08006 | The one server tried: unreachable, its name unresolved, its TLS handshake failed; `verify-ca` or `verify-full` with no authority trusted; a TLS file that cannot be read, or a certificate named without its key |
| | the server's | The one server tried refused the user |

## EXAMPLES

Settings of a URI, connecting over TLS checked against an authority:

```java
Settings settings = Settings.fromUri(
        "turborel://ann@127.0.0.1:17434/?tls_mode=verify-full&tls_roots=ca.pem&server_name=localhost");
try (Connection conn = settings.connect()) {
    System.out.println(conn.server() + ", as " + conn.authorization() + ", over TLS: " + conn.target().tls());
    conn.terminate();
}
```

```
turborel 0.0.0 (alpha), as ANN, over TLS: true
```

Layers, the program's over the environment's over the defaults:

```java
Options options = new Options().set(Options.TLS_MODE, "verify-full");
Map<String, String> environment = Map.of(
        "TURBOREL_HOST", "db1.example,db2.example:7500",
        "TURBOREL_USER", "ann",
        "TURBOREL_TLS_MODE", "disable");
Settings settings = Settings.resolveIn(options, environment::get);
System.out.println(settings.hosts());
System.out.println(settings.user() + ", " + settings.tlsMode() + ", " + settings.connectTimeout());
```

```
[Tcp[name=db1.example, port=7422], Tcp[name=db2.example, port=7500]]
ann, VERIFY_FULL, PT30S
```

The User's Guide's chapter [Settings](../user/09-settings.md) has more,
and [Appendix C](../appendix/c-settings.md) lists every setting.

## STANDARD

[CLIENT Connecting by settings], [CLIENT Where settings come from],
[CLIENT The connection URI], [CLIENT The service file],
[CLIENT The password file], [CLIENT Several servers],
[CLIENT TLS by mode], [CLIENT The SCRAM key cache].

## SEE ALSO

[Connections](21-connections.md), [Errors](30-errors.md)
