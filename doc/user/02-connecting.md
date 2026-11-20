# Connecting

[Contents](README.md)

A program talks to a Turborel server over a connection, and a connection
is one of the server's sessions. This chapter tells how a connection is
made: where to, over TCP, a Unix socket or a Windows named pipe; as which
user, and how that user is proved; over TLS; within which timeouts; what
it asks of the server and what the server tells it; and how it ends, or
its statement is cancelled.

The standard API's way of connecting, by a `jdbc:turborel:` URL, comes
at milestone M7. Until then this chapter shows the driver's low-level
client, which JDBC's classes rest on. It lives in packages the module
does not export (RULES.md D2), so its names may change; the examples run
with the driver's classes on the class path. The server of the examples
listens at 127.0.0.1, port 17434, and at the Unix socket
`turborel.sock`, with TLS, its owner ANN, whose password is `ann's`.

## Where to

`Connect.tcp` connects over TCP to a host and port, trying each of the
host's addresses in turn. A server listens at port 7422 unless told
another (`Connect.PORT`). `Connect.local` connects over the Unix socket
at a path, or on Windows over a named pipe, as `\\.\pipe\turborel`.
`Connect.tls` connects over TCP and begins TLS on the same port.

```java
Connection conn = Connect.tcp("127.0.0.1", 17434, "ann", new Trust());
System.out.println(conn.server() + ", as " + conn.authorization());
conn.terminate();
```

```
turborel 0.0.0 (alpha), as ANN
```

`server` gives the server's product and version, and `authorization` the
session's authorization identifier, as the server told them as the
session began. A user's name is an identifier, so `ann` is the user ANN.

## Who connects

Each way of connecting takes the user and a mechanism that proves it:

- `Scram`, of a password, by SCRAM-SHA-256: what a password should be
  proved by. The password never travels, and the connection is refused
  (SQLSTATE 08006) unless the server proves it knows the user's
  verifier. An exchange overheard lets the password be guessed at
  leisure, so it is used only over TLS, a Unix socket or a named pipe,
  unless `inTheClear()` asks for it over plain TCP too, for a server
  whose SERVER.SCRAM_WITHOUT_TLS is ON. Over TLS it is
  SCRAM-SHA-256-PLUS, bound to the connection, so a server in the
  middle cannot relay it.
- `Password`, of a password, sent whole, only over TLS, a Unix socket or
  a named pipe.
- `Certificate`, which proves the user by the certificate the client
  presents over TLS.
- `Trust`, which asks the server to trust the user as it names itself,
  as a server does only of a client of its own machine, and only if its
  setting SERVER.TRUST_LOCAL is ON, not its default.

To a server over plain TCP, `Password`, and `Scram` not in the clear,
are refused before anything is connected (SQLCODE -919, SQLSTATE
28000). A server refuses credentials it does not take with the same
SQLSTATE, and closes the connection.

```java
try {
    Connect.tcp("127.0.0.1", 17434, "ann", new Password("ann's"));
} catch (TurborelException e) {
    System.out.println(e.sqlcode() + " " + e.getSQLState() + ": " + e.getMessage());
}
try {
    Connect.local(Path.of("turborel.sock"), "ann", new Scram("not ann's"));
} catch (TurborelException e) {
    System.out.println(e.sqlcode() + " " + e.getSQLState() + ": " + e.getMessage());
}
```

```
-919 28000: [CLIENT Connecting] password is sent only over TLS, a Unix socket or a named pipe, never over plain TCP
-919 28000: the user or the password is wrong
```

## Over TLS

A `TlsConfig` says what a client's TLS trusts, and what it presents.
`TlsConfig.trusting` trusts the certificate authorities of a PEM file;
`TlsConfig.presenting` trusts them too, and presents the certificate
chain and private key of two more, the key as PKCS #8, PKCS #1 or SEC 1.
`Connect.tls` takes the name the server's certificate must be of, which
it also sends as the server's name.

```java
TlsConfig config = TlsConfig.trusting(Path.of("certs/ca.pem"));
Connection conn = Connect.tls("127.0.0.1", 17434, "localhost", config, new Login("ann", new Scram("ann's")));
System.out.println(conn.authorization() + " over TLS");
conn.terminate();
```

```
ANN over TLS
```

```java
TlsConfig config = TlsConfig.presenting(
        Path.of("certs/ca.pem"), Path.of("certs/client-chain.pem"), Path.of("certs/client-key.pem"));
Connection conn = Connect.tls("127.0.0.1", 17434, "localhost", config, new Login("ann", new Certificate()));
System.out.println(conn.authorization() + ", by its certificate");
conn.terminate();
```

```
ANN, by its certificate
```

The authorities trusted may be a file's, the platform's, or both:
`Roots.of` makes a store of them, for `TlsConfig.of`. The platform's are
those of the files the environment's SSL_CERT_FILE and SSL_CERT_DIR
name, if either is set, else the JDK's own trusted authorities (RULES.md
D13). A certificate not of the name, or of an authority not trusted,
fails the handshake (SQLSTATE 08006).

SCRAM-SHA-256-PLUS binds by `tls-exporter` where the JDK exports TLS
1.3's keying material, JDK 25 and later, and by the server's certificate
before it. `channelBinding(ChannelBinding.REQUIRE)` refuses to go on
unbound; `DISABLE` binds nothing; `PREFER`, the default, binds where it
can.

## Timeouts

`Timeouts` says how long a connection waits on its server: `connect`,
to connect, begin TLS, and for each step of the login, 30 seconds by
default; `read`, for each read or write after, no limit by default, as a
statement may run as long as it must. `Connect.tcp`, `local` and `tls`
wait as `Timeouts.DEFAULT` says; `Connect.to` takes timeouts of its own,
and `setReadTimeout` changes a connection's read timeout. A wait that
runs out loses the connection (SQLSTATE 08006), saying the server did
not answer in time. A named pipe is bounded only as it is opened, while
every instance of it is busy: its reads and writes wait as long as they
must.

A reply is taken up to 16 MiB, the protocol's longest; `setMaxBody`
takes replies of at most as many bytes as it says, a longer one losing
the connection.

## What a connection asks for, and what the server tells

A connection asks the server for capabilities as it opens, and the
server grants those it serves. `Offer.standard()` asks for every one but
compression, as the ways of connecting above do. `Connect.to` takes an
offer of its own: `Offer.none()` asks for none, `compressed()` adds
compression, and `attribute(name, value)` adds a connection attribute,
which the server shows in INFORMATION_SCHEMA.CONNECTION_ATTRIBUTES.
`granted` gives what was granted.

Granted `status`, `setting(name)` gives one of the session's settings as
the server last told it, with no round trip, and `settings` all of them.
The server tells them as the session begins, and again as a statement
changes one. Besides the settings it tells AUTHORIZATION, SCHEMA,
READ_ONLY, SERVER_VERSION, SERVER_READ_ONLY, CATALOG and OWNER.

```java
Timeouts within = new Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(60));
Offer offer = Offer.standard().attribute(Offer.APPLICATION, "payroll");
Target target = new Target.Tcp(new InetSocketAddress("127.0.0.1", 17434));
Connection conn = Connect.to(target, "ann", new Trust(), within, offer);
System.out.println("read timeout " + conn.readTimeout());
System.out.println("compression granted: " + conn.granted().contains(Capability.COMPRESSION));
System.out.println("SCHEMA " + conn.setting("SCHEMA") + ", READ_ONLY " + conn.setting("READ_ONLY"));
conn.terminate();
```

```
read timeout PT1M
compression granted: false
SCHEMA ANN, READ_ONLY NO
```

## The SCRAM key cache

A SCRAM login derives its keys from the password by PBKDF2, made slow so
a stolen exchange is slow to guess from. A server sends a user the same
salt and iterations at each login, so a `KeyCache` keeps the keys a
password derived after a login the server proved, and a later login of
the same salt and iterations skips the derivation. `Scram.withCache`
gives a mechanism one.

```java
KeyCache cache = new KeyCache();
for (int i = 0; i < 2; i++) {
    Connect.local(Path.of("turborel.sock"), "ann", new Scram("ann's").withCache(cache)).terminate();
    long[] lookups = cache.lookups();
    System.out.println("hits " + lookups[0] + ", misses " + lookups[1]);
}
```

```
hits 0, misses 1
hits 1, misses 1
```

An entry is found by the salt, the iterations and a tag of the password
made under a random secret of the cache's own, so a wrong password finds
nothing, and the cache holds neither the password nor anything it could
be guessed from offline. It holds 64 entries at most, the oldest dropped
first, drops an entry when a login that used it fails, and is held in
the process's memory alone.

## Ending a connection, and cancelling a statement

`terminate` ends the session, its transaction as the setting
SESSION.COMMIT_AT_END says. A commit that fails then, as one of a
deferred constraint not satisfied (SQLSTATE 40002), rolls the
transaction back, and `terminate` gives its failure. It waits for the
server to close at most the read timeout, else 60 seconds. `close`
closes the connection at once, without Terminate, and the server then
ends the session.

`canceller` gives what cancels the statement the connection runs, from
another thread: it opens a connection of its own, carrying the key the
server gave as the session began, and the statement fails with SQLCODE
-920, SQLSTATE TB920. `cancel` gives up after 10 seconds if the server
does not answer, `cancelWithin` after a limit of its own. A connection
over a stream the program opened itself has no canceller, since the
driver cannot reach its server again.

A connection lost, by a failure of its stream, an Error that ends it, or
a wait that runs out, takes no more: `isLost` says so, and each later
request fails with the failure that lost it.

The low-level client's classes are told in full in the API Reference,
[Connections](../client/21-connections.md).
