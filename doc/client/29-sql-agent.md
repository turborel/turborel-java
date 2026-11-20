# The SQL-agent

[Contents](README.md)

## NAME

`Agent`: a program's named connections, one current, as
turborel-client's SQL-agent gives them

## SYNOPSIS

```java
package com.turborel.jdbc.internal.session;

public final class Agent {
    @FunctionalInterface public interface Connector {
        Connection connect(@Nullable String server, @Nullable String user) throws TurborelException;
    }
    public Agent(Connector connector);
    public static Agent connected(Connector connector, Connection connection);
    public Outcome query(String sql) throws TurborelException;
    public Streamed queryInto(String sql, Sink sink) throws TurborelException;
    public @Nullable Completed connectionStatement(String sql) throws TurborelException;
    public Connection current() throws TurborelException;
    public @Nullable Connection currentConnection();
    public @Nullable String currentName();
    public @Nullable Canceller canceller();
    public @Nullable String warning();
    public boolean isLost();
    public void terminate() throws TurborelException;
}
```

These packages are not exported by the module (RULES.md D2).

## DESCRIPTION

`query` and `queryInto` run a connection statement themselves, giving
its completion, and send every other statement to the current
connection. A statement is a connection statement if it begins with
CONNECT or DISCONNECT, or with SET CONNECTION, and is then read whole,
as ISO/IEC 9075:1992 §15 writes them:

```text
CONNECT TO DEFAULT
CONNECT TO <server> [ AS <connection name> ] [ USER <user name> ]
SET CONNECTION { DEFAULT | <connection name> }
DISCONNECT { DEFAULT | ALL | CURRENT | <connection name> }
```

Each name is a character string literal, its spaces trimmed. CONNECT
asks the `Connector` for a connection to the server it names, or for
DEFAULT none, as the user it names, or none; the connection is named
by AS, else by the server's name, read as an identifier, a regular one
in upper case; DEFAULT names the default connection. It becomes
current. SET CONNECTION makes another current. DISCONNECT ends the
connections it names; if the current one is ended, none is current.
`connected` makes an agent whose default connection, current, is one
the program made. `terminate` ends every connection.

## ERRORS

| SQLCODE | SQLSTATE | When |
|---|---|---|
| -900 | 0A001 | CONNECT or SET CONNECTION while the current connection's transaction is active |
| -104 | 28000 | A user that is no authorization identifier |
| -919 | 2E000 | A connection name that is no identifier |
| -919 | 08002 | A connection of the name, or the default one, already made |
| -919 | 08001 | The connector's connection failure (SQLSTATE 08006): the server not reached |
| -919 | 08004 | The connector's other failure: the server refused |
| -919 | 08003 | No connection of the name, or none current |
| -917 | 25000 | DISCONNECT of a connection whose transaction is active |
| 0 | 01002 | DISCONNECT of a connection that could not be ended: a warning, the connection gone still |
| -103 | 2A000 | A name that is no character string literal, as a dynamic parameter |
| -102 | 2A000 | A statement that begins as a connection statement and is not one |
| -101 | 2A000 | A literal, a delimited identifier or a comment not closed |
| -900 | 0A000 | An embedded variable, `:name` |

## EXAMPLES

```java
agent.query("CONNECT TO DEFAULT");
agent.query("CONNECT TO '127.0.0.1:17434' AS 'reports' USER 'ann'");
System.out.println("current: " + agent.currentName());
```

```
current: REPORTS
```

The User's Guide's chapter [Pipelines and Pools](../user/08-pipelines-and-pools.md)
has the whole program.

## STANDARD

ISO/IEC 9075:1992 §15.1, §15.2, §15.3; [CLIENT The SQL-agent].

## SEE ALSO

[Settings](26-settings.md), [Connections](21-connections.md)
