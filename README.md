<p align="center">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="artwork/turborel-logo-white.svg">
      <img src="artwork/turborel-logo.svg" alt="turborel" width="360">
    </picture>
</p>

> turborel-java is in alpha, meaning, version 0.0.0, as turborel is.
> Anything may change from one build to the next, API and behaviour
> alike. See [VERSIONING.md](VERSIONING.md). No class connects to a
> server yet: this is the repository's kit and the codec of the
> protocol's frame header, with the rest to follow, milestone by milestone
> ([TODO.md](TODO.md)).

turborel-java is the Java driver for
[turborel](https://github.com/turborel/turborel), a relational database
system. Its JDBC 4.3 driver, `turborel-jdbc`, speaks turborel's native
protocol itself, in Java alone, with no native code and no library beyond
the JDK, so GUI tools such as DBeaver and DataGrip, and any program on the
JVM, load it as they load any JDBC driver.

Its version is the turborel release it is made for, the same number as
the server's: turborel-jdbc 0.0.0 is made for turborel 0.0.0, and speaks
protocol version 1.

## What it will have

Everything turborel-client, turborel's own client, does, checked row by
row in [CONFORMANCE.md](CONFORMANCE.md): TCP, Unix sockets and Windows
named pipes; TLS; trust, password, certificate and SCRAM-SHA-256(-PLUS)
logins; prepared statements and cursors; large objects by locator; copy,
import and export; connection settings, several servers and TLS modes;
pipelines and pools. JDBC 4.3 sits on top, and what JDBC has no place
for is reached by `unwrap` (RULES.md D2).

## What it needs

Java 17 or later, on Linux, macOS or Windows. It is tested on Java 17, 21
and 25. The jar is the JPMS module `com.turborel.jdbc`, and runs on the
class path too.

## Installing

The driver is not yet published to Maven Central: packages go up only
once every turborel driver is done (VERSIONING.md). Until then, build it
from source. The build needs JDK 17 and JDK 21 (RULES.md D4); Gradle comes
with the repository.

```
git clone https://github.com/turborel/turborel-java.git
cd turborel-java
./gradlew build
```

The jar is `build/libs/turborel-jdbc-0.0.0.jar`. If Gradle does not find
the JDKs, name their homes:
`./gradlew build -Dorg.gradle.java.installations.paths=<jdk17>,<jdk21>`.

Once it is published, it is the Maven artifact
`com.turborel:turborel-jdbc`:

```kotlin
dependencies {
    runtimeOnly("com.turborel:turborel-jdbc:0.0.0")
}
```

## A first query

This is what the API will be once the driver is done, at milestone M8.
None of it runs yet. The URL takes turborel's connection settings, as
turborel-client's `turborel://` URI does.

```java
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

try (Connection conn = DriverManager.getConnection(
                "jdbc:turborel://db.example:7422/?tls_mode=verify-full", "ANN", "ann's");
        Statement stmt = conn.createStatement();
        ResultSet rows = stmt.executeQuery("SELECT ID, NAME FROM STAFF ORDER BY ID")) {
    while (rows.next()) {
        System.out.println(rows.getInt("ID") + " " + rows.getString("NAME"));
    }
}
```

## Building and checking

```
./gradlew check     # every check a change must pass
./gradlew test      # the tests alone
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for how a change is made, and
[RULES.md](RULES.md) for the rules it follows.

## More

- [The manual](doc/README.md): the User's Guide, the API Reference and
  the appendices, also on turborel.com's documentation site
- [CONTRIBUTING.md](CONTRIBUTING.md): changes, commit messages, issues
- [SECURITY.md](SECURITY.md): reporting a vulnerability
- [TRADEMARK.md](TRADEMARK.md): the Turborel name and logos
- [LICENSE](LICENSE): BSD-2-Clause Plus Patent License
