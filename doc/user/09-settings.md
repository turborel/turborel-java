# Settings

[Contents](README.md)

A program that runs in more than one place should not carry its
servers, its user and its TLS in its code. Connection settings say them
from outside: a `turborel://` URI or options the program fills, a
section of a service file, the environment's TURBOREL_ variables, and a
password file. This chapter tells where settings come from, how several
servers are tried and a primary or a replica chosen, and how TLS modes
and channel binding protect a connection. The settings are
turborel-client's own, read as it reads them, so a program in another
language finds a service file or a password file the same.

## Connecting by settings

`Settings` says where the servers are, as whom to connect and how;
`connect` connects as it says, and gives a connection like `Connect`'s.

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

A password the settings give is proved by SCRAM-SHA-256. With no
password, the user is proved by the client's certificate if the
settings name one, else trusted. A secret mechanism is refused over
plain TCP before anything is sent, as `Connect.tcp` refuses it
(SQLSTATE 28000).

## Where settings come from

Each setting has one name, the same in options, a URI and a service
file. [Appendix C](../appendix/c-settings.md) lists them all, with
their defaults. Settings come in layers, and each setting is taken from
the first layer that has it:

1. the program's own, an `Options` it fills or a connection URI;
2. the section of the service file the program or the environment names;
3. the environment's variables;
4. the defaults.

`Settings.resolve` reads the process's environment;
`Settings.resolveIn` reads it through a function the program gives, as
a test does to give one of its own:

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

The program's `tls_mode` prevails over the environment's; the hosts
and the user come from the environment; the connect timeout is the
default. A variable set but empty gives nothing.

## The connection URI

```text
turborel://[<user>[:<password>]@][<host>[:<port>][,<host>[:<port>]]...][/][?<setting>=<value>[&<setting>=<value>]...]
```

Its parts are percent-encoded, so `p%40ss` is the password `p@ss` and
`%2Frun%2Fturborel.sock` a Unix socket; an IPv6 address is written in
brackets. A server serves one database, so the URI names none and has
no path.

```text
turborel://ann@db1.example,db2.example:7500/?target=read-write&connect_timeout=5
turborel://ann@%2Frun%2Fturborel.sock
turborel://ann@[::1]:7422/?tls_mode=disable
```

## The service file

A service file names sets of settings, so a program, or a person at
the command line, names a service rather than repeating them. It holds
sections, each `[name]` on a line of its own, then lines
`setting = value`; a line beginning with `#` is a comment, and blank
lines are skipped. A section cannot set `service` or `service_file`.
The file read is `service_file`, else `.turborel_service.conf` in the
home directory.

```text
# services for the examples
[live]
host = 127.0.0.1:9,127.0.0.1:17434
user = ann
tls_mode = require
application_name = inventory
```

## The password file

A password the settings do not give is looked up in the password file,
`passfile`, else `.turborel_pass` in the home directory, by the server
and the user each connection is made to. Each line is
`host:port:user:password`; a field `*` matches anything; `\` takes the
character after it as it is, so `\:` is a colon in a field and `\*` a
star; a line beginning with `#` is a comment. The first line that
matches is taken. A Unix socket is matched by its path and the default
port, 7422.

```text
127.0.0.1:17434:ann:ann\'s
```

The driver reads the file only if it is a regular file, or a link to
one. It cannot check that only its owner can read it, as turborel-client
cannot, and the owner answers for keeping it private.

With the service file and the password file above, a program names the
service alone. Its first host has no server, so the second is taken;
the password is the file's, proved by SCRAM over TLS; the program's
name is the connection attribute `application`, which the database's
owner sees in INFORMATION_SCHEMA.CONNECTION_ATTRIBUTES; and the second
connection takes its SCRAM keys from the settings' cache, skipping
their derivation:

```java
Options options = new Options()
        .set(Options.SERVICE, "live")
        .set(Options.SERVICE_FILE, "services.conf")
        .set(Options.PASSFILE, "pass");
Settings settings = Settings.resolve(options);
for (int i = 0; i < 2; i++) {
    try (Connection conn = settings.connect()) {
        List<List<Value>> rows = ((Outcome.Rows) conn.statements().query(
                "SELECT ATTRIBUTE_VALUE FROM INFORMATION_SCHEMA.CONNECTION_ATTRIBUTES"
                        + " WHERE ATTRIBUTE_NAME = 'application'")).rows();
        System.out.println(conn.authorization() + " over TLS: " + conn.target().tls() + ", application " + rows);
        conn.statements().query("COMMIT");
        conn.terminate();
    }
    System.out.println("keys found, derived: " + Arrays.toString(settings.keyCache().lookups()));
}
```

```
ANN over TLS: true, application [[Text[text=inventory]]]
keys found, derived: [0, 1]
ANN over TLS: true, application [[Text[text=inventory]]]
keys found, derived: [1, 1]
```

## Several servers

`host` may name several servers. They are tried in the order given, or,
with `load_balance=random`, in an order drawn at random for each
connection, so a program's connections spread over them. A name is
tried at each address it resolves to, each step within the connect
timeout. A server that cannot be reached, refuses the user, or is not
of the role asked is passed for the next. With none kept, the failure
names each address and why (SQLSTATE 08001; 28000 if the last refused
the user).

`target` asks for a role. `read-write` keeps a server that takes
changes: a server alone, or its replicated group's primary. `read-only`
keeps a replica that follows its group. The server tells each session
whether it takes changes, SERVER_READ_ONLY, so any user's connection
finds its role with no query; of a server that does not tell it, the
role is read from INFORMATION_SCHEMA.REPLICAS, which shows it to the
database's owner alone. `Role.of` gives the role a connection's session
sees.

```java
try (Connection conn = Settings.fromUri("turborel://ann@127.0.0.1:17434/?target=read-write").connect()) {
    System.out.println(Role.of(conn).describe());
    conn.terminate();
}
try {
    Settings.fromUri("turborel://ann@127.0.0.1:17434,localhost:17434/?target=read-only").connect();
} catch (TurborelException e) {
    System.out.println(e.getSQLState() + ": " + e.getMessage());
}
```

```
read-write
08001: [CLIENT Several servers] no server was kept: 127.0.0.1 (127.0.0.1:17434): [CLIENT Several servers] the server is read-write, and read-only is asked; localhost (127.0.0.1:17434): [CLIENT Several servers] the server is read-write, and read-only is asked; localhost ([::1]:17434): [DESIGN] cannot connect: Connection refused
```

The server alone takes changes, so a read-only one is asked for in
vain: `localhost` is tried at each of its addresses, and at `::1` no
server listens.

## TLS by mode

`tls_mode` says how a connection over TCP uses TLS. A Unix socket or a
named pipe, which only its machine reaches, uses none.

| Mode | TLS | The server's certificate |
|---|---|---|
| `disable` | Never | |
| `prefer` | If the server takes it, else plain TCP | Not checked |
| `require` | Always | Not checked |
| `verify-ca` | Always | Signed by an authority trusted |
| `verify-full` | Always | Signed by an authority trusted, and of the server's name |

`prefer`, the default, and `require` keep a connection from being
overheard, not from a server in the middle; `verify-full` keeps both.
`verify-ca` and `verify-full` need the authorities trusted:
`tls_roots`, a PEM file of them, or `tls_system_roots=true`, the
platform's. The name checked is `server_name`, else the host's. Under
`prefer`, a server reached whose TLS handshake fails is connected to
again over plain TCP; one that cannot be reached is not tried twice.
`tls_certificate` and `tls_key` name the client's certificate and key,
presented in every mode that uses TLS.

`channel_binding` says whether SCRAM binds its exchange to the TLS
connection carrying it, so a server in the middle cannot relay it.
`prefer` binds where the connection has a binding; `disable` binds
nothing; `require` refuses, before any connection, settings whose
mechanism binds nothing or whose connection is not over TLS
(SQLSTATE 28000).

```java
for (String uri : List.of(
        "turborel://ann@127.0.0.1:17434/?tls_mode=verify-full&tls_roots=ca.pem&server_name=elsewhere",
        "turborel://ann@127.0.0.1:17434/?tls_mode=verify-ca",
        "turborel://ann@127.0.0.1:17434/?tls_mode=require&channel_binding=require")) {
    try {
        Settings.fromUri(uri).connect();
    } catch (TurborelException e) {
        System.out.println(e.getSQLState() + ": " + e.getMessage());
    }
}
try (Connection conn = Settings.fromUri("turborel://ann@127.0.0.1:17434/?tls_mode=verify-ca&tls_roots=ca.pem"
        + "&tls_certificate=ann-chain.pem&tls_key=ann-key.pem").connect()) {
    System.out.println("proved by its certificate: " + conn.authorization());
    conn.terminate();
}
```

```
08006: [CLIENT Connecting] the TLS handshake failed: (certificate_unknown) No subject alternative DNS name matching elsewhere found.
08006: [CLIENT Connecting] no certificate authority is trusted: name a file of them, or the platform's
28000: [CLIENT TLS by mode] channel binding is required: SCRAM over TLS alone binds one
proved by its certificate: ANN
```

## Settings refused

A setting that cannot be read, a URI of another scheme, of a path, of a
setting not in the table or of a pair without `=`, a service file that
cannot be read or has no section of the name, and settings that name
no user are refused with SQLSTATE 08001, before any connection:

```java
for (String uri : List.of(
        "turborel://ann@db1.example/inventory",
        "turborel://ann@db1.example/?colour=red",
        "turborel://db1.example",
        "turborel://ann@db1.example:port")) {
    try {
        Settings.resolveIn(Options.fromUri(uri), name -> null);
    } catch (TurborelException e) {
        System.out.println(e.getSQLState() + ": " + e.getMessage());
    }
}
```

```
08001: [CLIENT The connection URI] a connection URI names no database, so has no path, not /inventory
08001: [CLIENT Where settings come from] colour is no connection setting
08001: [CLIENT Where settings come from] no user is named: by the program, a service, or TURBOREL_USER
08001: [CLIENT Where settings come from] host is port, no port
```

The API Reference's page [Settings](../client/26-settings.md) gives the
classes, and [Appendix C](../appendix/c-settings.md) every setting.
