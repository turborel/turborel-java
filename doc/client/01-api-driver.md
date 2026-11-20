# Driver

[Contents](README.md)

## NAME

`Driver`: the JDBC driver, of `jdbc:turborel:` URLs

## SYNOPSIS

```java
package com.turborel.jdbc;

public final class Driver implements java.sql.Driver {
    public static final String PREFIX = "jdbc:turborel:";
    public Driver();
    public Connection connect(String url, Properties info) throws SQLException;
    public boolean acceptsURL(String url);
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info);
    public int getMajorVersion();                    // 0
    public int getMinorVersion();                    // 0
    public boolean jdbcCompliant();                  // false
    public Logger getParentLogger() throws SQLFeatureNotSupportedException;
}
```

```
jdbc:turborel://[user[:password]@][host[:port][,...]][/][?setting=value[&...]]
```

## DESCRIPTION

`Driver` is the module's `java.sql.Driver`, provided to `ServiceLoader`
by the module's `provides` and, on the class path, by
`META-INF/services/java.sql.Driver`, and registered with `DriverManager`
as its class loads. A tool that loads a driver's jar in a class loader
of its own and makes the driver by its class's name,
`com.turborel.jdbc.Driver`, as DBeaver and DataGrip do, needs nothing
beyond the jar.

`acceptsURL` takes a URL that begins `jdbc:turborel:`; `connect` gives
none for any other, as JDBC asks, so `DriverManager` tries the next
driver. What follows `jdbc:` is a connection URI, as
[Settings](26-settings.md) reads one: its user, password, hosts, and
each setting by its name. A property given beside the URL sets the
setting of its name, read without regard to case, over the URL's:
`user` and `password`, and every other setting `Options.KEYS` names,
such as `tls_mode`, `target` or `connect_timeout`. A property of another
name is passed over. The settings are then laid over a service's, the
environment's and the defaults, and the connection is made as
`Settings.connect` makes it, trying each host in turn.

`getPropertyInfo` lists every setting, `user` the one marked required,
each with the value the properties give it, if any. The driver's version
is 0.0; `jdbcCompliant` is false, as the driver has not been run against
JDBC's compliance tests. It logs nothing, so `getParentLogger` fails.

## ERRORS

| SQLCODE | SQLSTATE | When |
|---|---|---|
| -919 | 08001 | `connect`: a setting that cannot be read, or settings no connection can be made by |
| | | `connect`: as [Connections](21-connections.md) says of connecting |
| -900 | 0A000 | `getParentLogger`, a `SQLFeatureNotSupportedException` |

## EXAMPLES

A connection by `DriverManager`, the password a property:

```java
Properties info = new Properties();
info.setProperty("user", "ann");
info.setProperty("password", "ann's");
try (Connection conn = DriverManager.getConnection("jdbc:turborel://127.0.0.1:17434/?tls_mode=verify-full&tls_roots=ca.pem&server_name=localhost", info)) {
    DatabaseMetaData md = conn.getMetaData();
    System.out.println(md.getDatabaseProductVersion() + ", as " + md.getUserName());
}
```

```
turborel 0.0.0 (alpha), as ANN
```

The driver made by its class's name, as a tool makes it:

```java
java.sql.Driver driver = (java.sql.Driver) Class.forName("com.turborel.jdbc.Driver")
        .getDeclaredConstructor().newInstance();
System.out.println(driver.acceptsURL("jdbc:turborel://db.example/") + " " + driver.acceptsURL("jdbc:other://db.example/"));
```

```
true false
```

## STANDARD

[DESIGN] D2; JDBC 4.3, `java.sql.Driver`; [CLIENT Where settings come from].

## SEE ALSO

[Connection](02-api-connection.md), [DataSource](06-api-data-source.md),
[Settings](26-settings.md)
