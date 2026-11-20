# Connection

[Contents](README.md)

## NAME

`Connection`, `Savepoint` and `TurborelConnection`: a connection of the
driver, its transactions, and what the server told its session

## SYNOPSIS

```java
package com.turborel.jdbc;

public interface TurborelConnection extends java.sql.Connection {
    String sessionStatus(String name) throws SQLException;          // or null
    Map<String, String> sessionStatus() throws SQLException;
    Long commitPosition() throws SQLException;                       // or null
    Set<String> granted() throws SQLException;
}
```

A connection the driver makes is a `java.sql.Connection`, and
`unwrap(TurborelConnection.class)` gives it as a `TurborelConnection`.

## DESCRIPTION

**Transactions.** A connection begins under autocommit, as JDBC begins
one: each statement is its own transaction, the server ending it with
the statement; granted `fetch-ends`, a query read by a cursor ends it
as its last row is read or the cursor closed. `setAutoCommit(false)` leaves the next
transaction open until `commit` or `rollback`, which send COMMIT and
ROLLBACK; `setAutoCommit(true)` within a transaction commits it first.
`commit` and `rollback` under autocommit are refused.

**Isolation.** `setTransactionIsolation` sets the session setting
TRANSACTION.ISOLATION to READ UNCOMMITTED, READ COMMITTED, REPEATABLE
READ or SERIALIZABLE, by `SET TRANSACTION.ISOLATION = '…'`;
`getTransactionIsolation` reads it as the server last told it, READ
COMMITTED unless set. `TRANSACTION_NONE` is refused.

**Savepoints.** Within a transaction, autocommit off, `setSavepoint`
sends SAVEPOINT: of a name given, that name as a delimited identifier;
of none, `JDBC_SAVEPOINT_` and a number counted from 1 for the
connection, which `getSavepointId` gives. `rollback(savepoint)` sends
ROLLBACK TO SAVEPOINT, and `releaseSavepoint` RELEASE SAVEPOINT. Under
autocommit a savepoint is refused, as is another driver's.

**The session.** `getCatalog` and `getSchema` give the session's
CATALOG and SCHEMA, as the server told them; `setSchema` sends
`SET SCHEMA` of the name, as a delimited identifier, so of its case as
given. A server serves one catalog, so `setCatalog` does nothing, as
JDBC asks of such a driver. `setReadOnly` is kept as JDBC's hint and
sends nothing; `isReadOnly` is true if it was set, or if the server
tells READ_ONLY YES. `getWarnings` gives the Notices the server sent
the connection, chained, each an `SQLWarning` of its SQLSTATE and
message, its vendor code 0; a statement's own conditions are the
statement's warnings ([Statement](03-api-statements.md)).

**What JDBC has no place for.** `sessionStatus` gives a session setting
the server told, by its name: AUTHORIZATION, CATALOG, SCHEMA, READ_ONLY,
SERVER_VERSION, TRANSACTION.ISOLATION, or any other it sent; with no
name, all of them. `commitPosition` gives the log position of the last
commit a statement of the connection made, granted `commit-position`;
`granted` the capabilities the server granted, by their protocol names.

**Checking and timeouts.** `isValid` sends Ping, granted `ping`, else
the query VALUES 1; it is true if the server answered within the
timeout given, 0 waiting as long as reads wait. A connection closed or
lost is not valid. `setNetworkTimeout` sets how long each read waits,
0 for as long as it takes, and the executor is not used.

**Objects.** `createBlob`, `createClob`, `createNClob` and
`createSQLXML` make objects held in memory to be set as parameters,
and `createArrayOf` an array of the elements given
([ResultSet and Metadata](04-api-result-sets.md)). A `Struct`, a type
map that maps a type, a result set that is not forward-only and
read-only, and a cursor held over a commit are refused. `nativeSQL`
gives the SQL with JDBC's escapes translated
([Statement](03-api-statements.md)). Client info is kept by the
connection, as given, and never sent.

**Closing.** `close` closes each of the connection's statements and
their result sets, sends Terminate, so the server ends the session's
transaction as its setting SESSION.COMMIT_AT_END says, then closes the
stream; closing it again does nothing. `abort` closes the stream at
once, sending nothing. A connection the server or the stream lost is
closed too; a call that uses it then fails.

## ERRORS

| SQLCODE | SQLSTATE | When |
|---|---|---|
| -910 | TB910 | A closed connection; `commit` or `rollback` under autocommit; a savepoint under autocommit, or another driver's; an isolation level JDBC does not name; a negative timeout; `abort` of no executor |
| -919 | 08006 | A connection lost |
| -900 | 0A000 | A `Struct`, a type map that maps a type, a result set not forward-only and read-only, a cursor held over a commit |
| | the server's | A statement the connection sends for the program, such as COMMIT or SET SCHEMA |

## EXAMPLES

A transaction, rolled back to a savepoint:

```java
try (Connection conn = DriverManager.getConnection("jdbc:turborel://127.0.0.1:17434/?tls_mode=verify-full&tls_roots=ca.pem&server_name=localhost", "ann", "ann's");
        Statement stmt = conn.createStatement()) {
    conn.setAutoCommit(false);
    stmt.executeUpdate("INSERT INTO STAFF (NAME, SALARY) VALUES ('Edsger', 4800.00)");
    Savepoint before = conn.setSavepoint();
    stmt.executeUpdate("UPDATE STAFF SET SALARY = SALARY * 2");
    conn.rollback(before);
    conn.commit();
    try (ResultSet rs = stmt.executeQuery("SELECT NAME, SALARY FROM STAFF ORDER BY ID")) {
        while (rs.next()) {
            System.out.println(rs.getString(1) + " " + rs.getBigDecimal(2));
        }
    }
    conn.commit();
}
```

```
Ada 5200.00
Grace 6100.50
Edsger 4800.00
```

What the server told the session:

```java
TurborelConnection told = conn.unwrap(TurborelConnection.class);
System.out.println(told.sessionStatus("AUTHORIZATION") + " " + told.sessionStatus("SERVER_VERSION"));
System.out.println(told.granted().contains("declared-types") + " " + conn.getTransactionIsolation());
```

```
ANN turborel 0.0.0 (alpha)
true 2
```

## STANDARD

[DESIGN] D2; JDBC 4.3, `java.sql.Connection` and `java.sql.Savepoint`;
[CLIENT Connecting].

## SEE ALSO

[Driver](01-api-driver.md), [Statement](03-api-statements.md),
[Connections](21-connections.md)
