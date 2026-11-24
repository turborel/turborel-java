# Connections

[Contents](README.md)

## NAME

`Connect`, `Connection`, `Controls`, the mechanisms, `TlsConfig`,
`Timeouts`, `Offer`, `KeyCache`, `Canceller`: the low-level client's
connections, as turborel-client gives them

## SYNOPSIS

```java
package com.turborel.jdbc.internal.session;

public final class Connect {
    public static final int PORT = 7422;
    public static Connection tcp(String host, int port, String user, Mechanism mechanism) throws TurborelException;
    public static Connection local(Path path, String user, Mechanism mechanism) throws TurborelException;
    public static Connection tls(String host, int port, String name, TlsConfig config, Login login)
            throws TurborelException;
    public static Connection to(Target target, String user, Mechanism mechanism, Timeouts timeouts, Offer offer)
            throws TurborelException;
}

public record Login(String user, Mechanism mechanism) {}

public final class Connection implements AutoCloseable {
    public static Connection open(Duplex duplex, String user, Mechanism mechanism, Offer offer)
            throws TurborelException;
    public String server();
    public String authorization();
    public boolean inTransaction();
    public Set<String> granted();
    public @Nullable String setting(String name);
    public Map<String, String> settings();
    public void setNoticeHandler(@Nullable Consumer<Reply.Notice> handler);
    public List<Reply.Notice> takeNotices();
    public void setReadTimeout(@Nullable Duration within) throws TurborelException;
    public @Nullable Duration readTimeout();
    public void setMaxBody(int bytes);
    public @Nullable Target target();
    public @Nullable Canceller canceller();
    public Statements statements();
    public Controls controls();
    public Debugger debugger();
    public Told told();
    public void terminate() throws TurborelException;
    public boolean isLost();
    public void close();
}

public final class Controls {
    public void ping() throws TurborelException;
    public void reset() throws TurborelException;
    public void changeUser(String user, Mechanism mechanism) throws TurborelException;
}

public final class Canceller {
    public void cancel() throws TurborelException;
    public void cancelWithin(Duration limit) throws TurborelException;
}

public record Offer(List<String> capabilities, List<Attribute> attributes, int compressFrom) {
    public static final String APPLICATION = "application";
    public static Offer standard();
    public static Offer none();
    public Offer compressed();
    public Offer attribute(String name, String value);
}

package com.turborel.jdbc.internal.auth;

public final class Trust implements Mechanism {}
public final class Password implements Mechanism { public Password(String password); }
public final class Certificate implements Mechanism {}
public final class Scram implements Mechanism {
    public Scram(String password);
    public Scram inTheClear();
    public Scram channelBinding(ChannelBinding how);
    public Scram withCache(KeyCache keys);
}
public enum ChannelBinding { DISABLE, PREFER, REQUIRE }
public final class KeyCache {
    public static final int ENTRIES = 64;
    public KeyCache();
    public long[] lookups();
    public int size();
    public void clear();
}

package com.turborel.jdbc.internal.tls;

public final class TlsConfig {
    public static TlsConfig trusting(Path roots) throws TurborelException;
    public static TlsConfig presenting(Path roots, Path chain, Path key) throws TurborelException;
    public static TlsConfig of(KeyStore trusted, @Nullable Identity identity) throws TurborelException;
    public static TlsConfig of(TrustManager[] trust, @Nullable Identity identity, boolean checkName)
            throws TurborelException;
    public record Identity(List<X509Certificate> chain, PrivateKey key) {}
}
public final class Roots {
    public static KeyStore of(@Nullable Path file, boolean platform, UnaryOperator<@Nullable String> environment)
            throws TurborelException;
}

package com.turborel.jdbc.internal.transport;

public sealed interface Target {
    record Tcp(InetSocketAddress address) implements Target {}
    record Local(Path path) implements Target {}
    record Tls(InetSocketAddress address, String name, TlsConfig config) implements Target {}
}
public record Timeouts(@Nullable Duration connect, @Nullable Duration read) {
    public static final Duration CONNECT_WITHIN;   // 30 seconds
    public static final Duration TERMINATE_WITHIN; // 60 seconds
    public static final Duration CANCEL_WITHIN;    // 10 seconds
    public static final Timeouts DEFAULT;          // CONNECT_WITHIN, and no read timeout
}
```

These packages are not exported by the module (RULES.md D2): JDBC's
classes rest on them, and a program reaches what the server told the
session by `unwrap(TurborelConnection.class)`
([Connection](02-api-connection.md)). A connection's `statements()` are told in
[Statements and Cursors](22-statements.md), with what `told()` gives of
a completion, and `controls()` several statements in one request and
query attributes too; `debugger()`, the routine debugger *(extension)*, in
[Debugging Routines](31-debugging.md).

## DESCRIPTION

**Connecting.** `Connect.tcp` connects over TCP, trying each of the
host's addresses in turn; `Connect.local` over the Unix socket at a
path, or on Windows over a named pipe, as `\\.\pipe\turborel`;
`Connect.tls` over TCP, then TLS on the same port, the server's
certificate checked to be of `name`, which is sent as the server's name
too. Each waits as `Timeouts.DEFAULT` says and asks for
`Offer.standard()`. `Connect.to` connects to a `Target`, waiting as its
timeouts say and asking for its offer. `Connection.open` logs in over a
stream the program opened itself, which it bounds itself: its read
timeout cannot be set, and it has no canceller. A program that opens a
TLS stream of its own binds it before SCRAM-SHA-256-PLUS, by the
mechanism's `bind` with `Handshake.channel`.

**Mechanisms.** `Trust` asks the server to trust the user as it names
itself. `Password` sends the password whole. `Certificate` proves the
user by the certificate TLS presents. `Scram` proves a password by
SCRAM-SHA-256, the server proving it knows the user's verifier before
the login ends; over TLS it is SCRAM-SHA-256-PLUS, bound by
`tls-exporter` where the JDK exports keying material, else by the
server's certificate, as `channelBinding` allows, `PREFER` by default.
`inTheClear` lets it go over plain TCP; `withCache` gives it a key
cache.

**TLS.** `TlsConfig.trusting` trusts the authorities of a PEM file;
`presenting` also presents a certificate chain and key, the key as
PKCS #8, PKCS #1 or SEC 1. `TlsConfig.of` takes a store of authorities,
as `Roots.of` makes of a file's, the platform's, or both, and an
identity or none. The platform's are those of the files SSL_CERT_FILE
and SSL_CERT_DIR name, if either is set, SSL_CERT_DIR a list of
directories apart by the platform's path separator, each that cannot be
read passed over; else the JDK's own (RULES.md D13).

**Timeouts.** `connect` bounds connecting, TLS and each step of the
login; `read` each read or write after. A named pipe is bounded only as
it is opened. `setReadTimeout` changes a connection's read timeout;
`setMaxBody` the longest reply it takes, 16 MiB at most.

**What the server tells.** `server` and `authorization` are as the
server's Ready said. `granted` is the capabilities granted, those the
offer asked for that the server serves. Granted `status`, `setting` and
`settings` give the session's settings as the server last told them,
with AUTHORIZATION, SCHEMA, READ_ONLY, SERVER_VERSION, SERVER_READ_ONLY,
CATALOG and OWNER. Notices go to the handler set, or with none the last
64 are kept for `takeNotices`. `inTransaction` says whether a
transaction is active, as the server last said.

**The key cache.** A `KeyCache` keeps the keys a SCRAM login derived,
after a login the server proved, for a later login of the same salt and
iterations. An entry is found by the salt, the iterations and a tag of
the password under a random secret of the cache's own; it holds
`ENTRIES`, 64, at most, the oldest dropped first, and drops an entry
when a login that used it fails. `lookups` gives its hits and misses, in
that order.

**Controlling the session.** Granted `ping`, `ping` asks whether the
server and the connection are alive, with no statement run. Granted
`reset`, `reset` puts the session back as it was at login: its
transaction rolled back, told by notice 01T01, its prepared statements,
cursors, temporary tables and settings gone, every setting told again,
its user and cancel key kept. Granted `change-user`, `changeUser`
authenticates the connection again as another user, answering each
challenge as a login does, the session renewed as by a reset under a
new cancel key, no transaction active after it.

**Ending.** `terminate` sends Terminate and waits for the server to
close, at most the read timeout, else `TERMINATE_WITHIN`; the server
ends the session as SESSION.COMMIT_AT_END says. `close` closes at once.
`canceller` gives what cancels the statement running, over a connection
of its own, carrying the session's cancel key, which it never shows;
`cancel` gives up after `CANCEL_WITHIN`. A connection lost takes no
more, `isLost` saying so.

## ERRORS

| SQLCODE | SQLSTATE | When |
|---|---|---|
| -919 | 28000 | `Password`, or `Scram` not in the clear, over plain TCP, before connecting; the server refuses the user, the mechanism or the credentials, and closes |
| -919 | 08004 | The server refuses the connection, as of a version it does not speak |
| -919 | 08006 | The server cannot be reached; TLS's handshake fails; a wait runs out, the message saying the server did not answer in time; the server does not prove it knows the SCRAM verifier; a reply is longer than the connection takes; a file of certificates or a key cannot be read; the stream fails; `setReadTimeout` on a connection the program opened itself |
| -920 | TB920 | The statement a `Canceller` cancelled |
| -919 | 08006 | `ping`, `reset` or `changeUser` of a capability not granted, refused before anything is sent, the connection kept |
| -919 | 28000 | `changeUser`: the server refuses the user or the credentials, and the connection fails |
| -913 | 40002 | `terminate`: the commit at the session's end fails, and the transaction is rolled back |

## EXAMPLES

Trust, over TCP:

```java
Connection conn = Connect.tcp("127.0.0.1", 17434, "ann", new Trust());
System.out.println(conn.server() + ", as " + conn.authorization());
conn.terminate();
```

```
turborel 0.0.0 (alpha), as ANN
```

SCRAM-SHA-256-PLUS, over TLS:

```java
TlsConfig config = TlsConfig.trusting(Path.of("certs/ca.pem"));
Connection conn = Connect.tls("127.0.0.1", 17434, "localhost", config, new Login("ann", new Scram("ann's")));
System.out.println(conn.authorization() + " over TLS");
conn.terminate();
```

```
ANN over TLS
```

The User's Guide's chapter [Connecting](../user/02-connecting.md) has
more: refusals, a certificate, timeouts and an offer, and the key cache.

## STANDARD

[PROTOCOL The Native Protocol], [PROTOCOL Opening a connection],
[PROTOCOL Mechanisms], [PROTOCOL Capabilities], [PROTOCOL Status 'T'],
[PROTOCOL Cancel 'Z'], [PROTOCOL Terminate 'X'], [PROTOCOL Ping 'G'],
[PROTOCOL Reset 'W'], [PROTOCOL ChangeUser 'U'];
[CLIENT Connecting], [CLIENT Timeouts],
[CLIENT What a connection asks for],
[CLIENT What the server tells], [CLIENT Running statements],
[CLIENT The SCRAM key cache], [CLIENT Controlling the session].

## SEE ALSO

[Connecting](../user/02-connecting.md),
[Errors](30-errors.md),
[Settings](26-settings.md)
