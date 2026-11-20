# Installing

[Contents](README.md)

This chapter tells what the driver needs, how it is built and put on a
program's class path or module path, and how a database tool takes it.

## What it needs

The driver needs Java 17 or later, on Linux, macOS or Windows, and
nothing else: it speaks Turborel's native protocol itself, in Java
alone, with no native code and no library beyond the JDK. Java 17, 21
and 25 are tested. It talks to a Turborel server of the same release,
which speaks protocol version 1.

## Building it

No package is published yet. The driver's package,
`com.turborel:turborel-jdbc`, goes to Maven Central once every Turborel
driver is done, with the others ([Versions](../appendix/d-versions.md)).
Until then it is built from its source:

```
git clone https://github.com/turborel/turborel-java.git
cd turborel-java
./gradlew build
```

The build needs JDK 17 and JDK 21; if Gradle does not find them, name
their homes with `-Dorg.gradle.java.installations.paths=<jdk17>,<jdk21>`.
It makes one jar, `build/libs/turborel-jdbc-0.0.0.jar`.

## A program's path

On the class path, the jar is all a program needs: `DriverManager`
finds the driver by its `jdbc:turborel:` URLs.

```
java -cp turborel-jdbc-0.0.0.jar:. FirstQuery
```

On the module path the driver is the module `com.turborel.jdbc`, and a
program's module reads it and `java.sql`:

```java
module first.query {
    requires java.sql;
    requires com.turborel.jdbc;
}
```

A program needs the module itself only for what JDBC has no place for,
`TurborelConnection` and `TurborelDataSource`; `DriverManager` finds
the driver by `ServiceLoader` either way.

## A first query

```java
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class FirstQuery {
    public static void main(String[] args) throws Exception {
        try (Connection conn = DriverManager.getConnection(
                        "jdbc:turborel://127.0.0.1:17434/?tls_mode=verify-full&tls_roots=ca.pem&server_name=localhost", "ann", "ann's");
                PreparedStatement stmt = conn.prepareStatement(
                        "SELECT ID, NAME FROM STAFF WHERE ID < ? ORDER BY ID")) {
            stmt.setInt(1, 3);
            try (ResultSet rows = stmt.executeQuery()) {
                while (rows.next()) {
                    System.out.println(rows.getInt("ID") + " " + rows.getString("NAME"));
                }
            }
        }
    }
}
```

```
1 Ada
2 Grace
```

The server of this manual's examples listens at 127.0.0.1, port 17434,
with TLS, its certificate for `localhost` signed by the authority in
`ca.pem`; a server listens at 7422 unless told another. A password is
sent only over TLS ([Connecting](02-connecting.md)). Its table STAFF is made
in [Running Queries](03-queries.md).

## In a database tool

A tool that takes a JDBC driver as a jar, DBeaver and DataGrip among
them, is given the jar, the driver's class, `com.turborel.jdbc.Driver`,
and a URL, `jdbc:turborel://host:port/`, the user and password as the
tool asks for them. The tool loads the jar in a class loader of its own,
and lists schemas, tables, columns, keys, indexes and routines from the
driver's `DatabaseMetaData`, each column of the type it was declared
with ([DatabaseMetaData](../client/05-api-database-metadata.md)).
Settings such as `tls_mode` are given in the URL or as the driver's
properties, which the tool lists by `getPropertyInfo`
([Driver](../client/01-api-driver.md)).
