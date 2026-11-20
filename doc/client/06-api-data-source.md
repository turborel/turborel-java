# DataSource

[Contents](README.md)

## NAME

`TurborelDataSource`: a `javax.sql.DataSource` of connections of the
driver

## SYNOPSIS

```java
package com.turborel.jdbc;

public class TurborelDataSource implements javax.sql.DataSource {
    public TurborelDataSource();
    public String getUrl();
    public void setUrl(String url);                  // jdbc:turborel://, unless set
    public void setSetting(String name, String value);
    public void setUser(String user);
    public String getUser();                         // or null
    public void setPassword(String password);
    public Connection getConnection() throws SQLException;
    public Connection getConnection(String username, String password) throws SQLException;
    public void setLoginTimeout(int seconds);
    public int getLoginTimeout();
}
```

## DESCRIPTION

A data source holds a URL, as [Driver](01-api-driver.md) takes one,
and settings beside it, each set by its name as a property is given to
the driver: `setUser` and `setPassword` set `user` and `password`, and
`setSetting` any other, such as `tls_mode` or `target`.
`getConnection` makes a connection of them, as `Driver.connect` does;
given a user and password, of them in place of those set. The login
timeout is the setting `connect_timeout`, in seconds, 0 for none, and 0
unless set, the driver's default then applying. A data source is a
bean, so a container or a tool may make one by its class's name and
set it by its properties. It logs nothing: a log writer set is kept,
and never written to, and `getParentLogger` fails. It makes each
connection anew; [Pools](28-pools.md) tells of the driver's pool.

## ERRORS

| SQLCODE | SQLSTATE | When |
|---|---|---|
| -910 | TB910 | A URL that does not begin `jdbc:turborel:` |
| | | As [Driver](01-api-driver.md) says of `connect` |
| -900 | 0A000 | `getParentLogger`, a `SQLFeatureNotSupportedException` |

## EXAMPLES

```java
TurborelDataSource source = new TurborelDataSource();
source.setUrl("jdbc:turborel://127.0.0.1:17434/");
source.setSetting("tls_mode", "verify-full");
source.setSetting("tls_roots", "ca.pem");
source.setSetting("server_name", "localhost");
source.setUser("ann");
source.setPassword("ann's");
source.setLoginTimeout(5);
try (Connection conn = source.getConnection()) {
    System.out.println(conn.getMetaData().getUserName() + " " + source.getLoginTimeout());
}
```

```
ANN 5
```

## STANDARD

[DESIGN] D2; JDBC 4.3, `javax.sql.DataSource`.

## SEE ALSO

[Driver](01-api-driver.md), [Pools](28-pools.md)
