<p align="center">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="artwork/turborel-logo-white.svg">
      <img src="artwork/turborel-logo.svg" alt="turborel" width="360">
    </picture>
</p>

> turborel-java is in alpha, meaning, version 0.0.0. Its version is the
> number of the Turborel release it is made for, and the alpha is made
> for Turborel 0.0.0, which speaks protocol version 1. Anything may
> change from one build to the next, API and behaviour alike. See
> [VERSIONING.md](VERSIONING.md). The latest Turborel alpha is built for
> Linux, macOS and Windows as the pre-release
> [nightly](https://github.com/turborel/turborel/releases/tag/nightly),
> and as the image `ghcr.io/turborel/turborel:nightly`.

turborel-java is the Java driver for
[Turborel](https://github.com/turborel/turborel), a relational database
system. It connects a Java program to a Turborel server,
`turboreld`, over Turborel's [native
protocol](https://github.com/turborel/turborel/blob/main/doc/client/05-protocol.md),
version 1, which it speaks itself, in Java alone, with no native code and no library beyond the JDK.

Its standard interface is JDBC 4.3, as the JPMS module `com.turborel.jdbc`. Beneath it is a low-level
client built to do all that Turborel's own client library,
turborel-client, does: TCP, Unix sockets and Windows named pipes, TLS,
SCRAM-SHA-256 and SCRAM-SHA-256-PLUS, prepared statements and cursors,
large objects, bulk copy, import and export, settings from a URI, the
environment and service and password files, several servers, pipelines
and pools.

The driver is built in milestones, M0 to M8, and does not connect to a
server yet. [CONFORMANCE.md](CONFORMANCE.md) shows what is done, row by
row, and [TODO.md](TODO.md) what is open.

## Requirements

- Java 17 or later, on Linux, macOS or Windows ([RULES.md](RULES.md) D3).
- Java 17, 21 and 25 are tested; the jar runs on the class path too.
- A Turborel server of the same release, which speaks protocol version 1
  ([running the server](https://github.com/turborel/turborel/blob/main/doc/operator/02-turboreld.md)).

## Installing

No package is published yet. The driver's package, `com.turborel:turborel-jdbc`, goes to
Maven Central only once every Turborel driver is done, with the others
([VERSIONING.md](VERSIONING.md#publishing)). Until then, build the
driver from source:

```
git clone https://github.com/turborel/turborel-java.git
cd turborel-java
./gradlew build
```

The jar is `build/libs/turborel-jdbc-0.0.0.jar`, for the class path or
the module path. The build needs JDK 17 and JDK 21 ([RULES.md](RULES.md)
D10); if Gradle does not find them, name their homes with
`-Dorg.gradle.java.installations.paths=<jdk17>,<jdk21>`.

## A first query

This is what the standard API will be once milestone M7 builds it and
M8's audit checks it. None of it runs yet, and its names may change
until then.

```java
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

try (Connection conn = DriverManager.getConnection(
                "jdbc:turborel://db.example:7422/?tls_mode=verify-full", "ANN", "ann's");
        PreparedStatement stmt = conn.prepareStatement(
                "SELECT ID, NAME FROM STAFF WHERE ID < ? ORDER BY ID")) {
    stmt.setInt(1, 3);
    try (ResultSet rows = stmt.executeQuery()) {
        while (rows.next()) {
            System.out.println(rows.getInt("ID") + " " + rows.getString("NAME"));
        }
    }
}
```

## The manual

[doc/](doc/README.md) is the manual: a User's Guide, an API Reference
and Appendices, published at
https://turborel.com/docs/drivers/java/. Each
chapter is written as the milestone that fills it lands. The SQL a
program sends, and the server, are described in Turborel's own manual,
[The Turborel Documentation Set](https://github.com/turborel/turborel/blob/main/doc/README.md).
[VERSIONING.md](VERSIONING.md) tells how the driver's releases are
numbered and published.

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) before making a change: how a
change is made, its commit message, pull requests and issues. Every
change passes `./gradlew check`. [RULES.md](RULES.md) says how the
driver is designed, written and verified,
[CONFORMANCE.md](CONFORMANCE.md) is the parity matrix every Turborel
driver keeps, and [TODO.md](TODO.md) lists open work.

## Security

Report a vulnerability privately, never in a public issue, as
[SECURITY.md](SECURITY.md) says.

## License and trademark

The code is under the BSD-2-Clause Plus Patent License, in
[LICENSE](LICENSE). The name Turborel and its logos are not:
[TRADEMARK.md](TRADEMARK.md) says how they may be used.
