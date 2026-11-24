# Statement, PreparedStatement and CallableStatement

[Contents](README.md)

## NAME

`Statement`, `PreparedStatement` and `CallableStatement`: SQL run over a
connection of the driver, its parameters, batches and timeouts

## SYNOPSIS

```java
Statement stmt = conn.createStatement();
PreparedStatement prep = conn.prepareStatement(sql);
CallableStatement call = conn.prepareCall("{call P(?, ?)}");
```

Each is JDBC 4.3's interface, of a result set that is forward-only,
read-only and closed at commit (`TYPE_FORWARD_ONLY`,
`CONCUR_READ_ONLY`, `CLOSE_CURSORS_AT_COMMIT`).

## DESCRIPTION

**Running.** `execute`, `executeQuery` and `executeUpdate` run one
statement, its JDBC escapes first translated (below): a query gives a
result set, and another statement the rows it changed, as the server
counted them, granted `row-counts`, else 0. `executeQuery` of a
statement that gives no result set is refused, as is `executeUpdate` of
a query, its result set then closed. Running a statement closes its
last result set. Each statement gives one result, so `getMoreResults`
is false. Under autocommit each statement is its own transaction.

**Fetch size.** Of a fetch size of 0, the default, a query's rows are
read whole as it runs. Of a fetch size above 0 they are read by a
cursor, that many at a time as `next` needs them: a `Statement`
prepares its SQL to open it, and deallocates it as the cursor ends.
`setMaxRows` gives at most that many rows, all if 0; `setMaxFieldSize`
cuts each character or binary string read to at most that many octets,
a character string at a character's end as UTF-8 writes it, none if 0.

**Prepared statements.** `prepareStatement` prepares the SQL at once,
so a statement the server refuses fails there. Each `?` is a parameter,
its type the server's, and a value set is made a value of that type, as
[ResultSet and Metadata](04-api-result-sets.md) tells: so `setString`
of an INTEGER parameter sends the number the text is, and `setObject`'s
target type, and `setNull`'s, are not needed and not used. A parameter
of no value fails the statement, nothing sent (07001).
`getMetaData` gives the columns of a query, none of another statement,
and `getParameterMetaData` the parameters, without running it. Closing
it deallocates it.

**Callable statements.** `prepareCall` takes a CALL, or JDBC's
`{call …}`. JDBC counts each `?` of it, and the server takes its input
parameters alone. Granted `parameter-modes`, which connecting asks for,
the server tells each `?`'s mode, IN, OUT or INOUT, and its parameter's
position, which `getParameterMetaData` gives: an IN or INOUT `?` takes
a value, an OUT one none, and setting it is refused, as registering an
IN one by `registerOutParameter` is. Once the CALL is executed, each OUT
or INOUT parameter is read by its own number, by `getInt(2)` and the
others, from the row of their values the server sends in the
procedure's order, registered or not. A `PreparedStatement` of a CALL
counts and takes its marks the same way. Of a server that tells no
modes, each `?` given a value is an IN parameter, each registered an
OUT one, one both an INOUT, and the OUT values are read in the order of
their marks. A parameter set or registered by its name is refused.

**Batches.** `addBatch` keeps a statement's SQL, or a prepared
statement's values, and `executeBatch` sends them all as one pipeline
([Pipelines](27-pipelines.md)), giving each one's count of rows,
`SUCCESS_NO_INFO` where the server counted none. Each runs whatever
another fails. A batch of which one failed, or gave a result set, fails
with a `BatchUpdateException`: its counts, `EXECUTE_FAILED` for each
that did, its SQLSTATE and vendor code the first failure's, and that
failure its cause. Under autocommit each is its own transaction.

**Generated keys.** Granted `generated-values`, `getGeneratedKeys`
gives the identity values the last statement's INSERT generated, a row
of each, its one column GENERATED their text, whatever columns were
asked for by name or number.

**Timeouts and cancelling.** `setQueryTimeout` cancels a statement
still running after so many seconds, 0 for none; `cancel` cancels the
one running now, from another thread. Each sends the server Cancel over
a connection of its own, as [Connections](21-connections.md) says, and
a statement cancelled fails (TB920). A connection over a stream the
program gave cannot be cancelled. The timer runs on the driver's
thread `turborel-jdbc-timeouts`.

**Warnings.** A statement's warnings are the conditions its completion
carried, granted `conditions`, each an `SQLWarning` of its SQLSTATE and
SQLCODE: as 01004, of a value cut to fit its column.

**Escapes.** Before it is sent, JDBC's escape syntax is translated:
`{d '…'}`, `{t '…'}` and `{ts '…'}` as DATE, TIME and TIMESTAMP
literals, `{call …}` as CALL, `{fn …}` and `{oj …}` as what they hold,
and `{escape '…'}` as ESCAPE; braces in a literal, a delimited
identifier or a comment are not escapes. `setEscapeProcessing(false)`
sends a `Statement`'s SQL as it is. A named cursor is refused.

## ERRORS

| SQLCODE | SQLSTATE | When |
|---|---|---|
| -910 | TB910 | A statement or its connection closed; `executeQuery` of no result set, `executeUpdate` of a query; a parameter of no place; SQL given to a prepared statement's `execute`; an escape not closed, or of a kind JDBC has not; a negative fetch size, maximum or timeout |
| -921 | 07001 | A parameter of no value, nothing sent |
| -301 | 22018 | A value that is no value of its parameter's type |
| -920 | TB920 | A statement cancelled, by `cancel` or its timeout |
| -900 | 0A000 | A named cursor; a parameter set or registered by its name; a REF, ROWID or DATALINK parameter |
| | the server's | The statement's own failure; a batch's first, as a `BatchUpdateException` |
| | 42000 | A syntax error or access rule violation, as a `SQLSyntaxErrorException`, its cause the `TurborelException` |

## EXAMPLES

A prepared statement run twice, then a batch:

```java
try (PreparedStatement prep = conn.prepareStatement("SELECT NAME FROM STAFF WHERE SALARY > ? ORDER BY ID")) {
    for (String least : List.of("5000", "6000")) {
        prep.setBigDecimal(1, new BigDecimal(least));
        try (ResultSet rs = prep.executeQuery()) {
            while (rs.next()) {
                System.out.print(rs.getString(1) + " ");
            }
        }
        System.out.println();
    }
}
try (PreparedStatement insert = conn.prepareStatement("INSERT INTO STAFF (NAME, SALARY) VALUES (?, ?)")) {
    for (String name : List.of("Barbara", "John")) {
        insert.setString(1, name);
        insert.setInt(2, 5000);
        insert.addBatch();
    }
    System.out.println(Arrays.toString(insert.executeBatch()));
}
```

```
Ada Grace
Grace
[1, 1]
```

A procedure's OUT parameter:

```java
try (Statement stmt = conn.createStatement()) {
    stmt.execute("CREATE PROCEDURE DOUBLED (IN N INTEGER, OUT M INTEGER) CONTAINS SQL BEGIN SET M = N * 2; END");
}
try (CallableStatement call = conn.prepareCall("{call DOUBLED(?, ?)}")) {
    call.setInt(1, 21);
    call.registerOutParameter(2, Types.INTEGER);
    call.execute();
    System.out.println(call.getInt(2));
}
```

```
42
```

## STANDARD

[DESIGN] D2; JDBC 4.3, `java.sql.Statement`, `java.sql.PreparedStatement`
and `java.sql.CallableStatement`; [CLIENT Prepared statements],
[CLIENT Pipelines].

## SEE ALSO

[Connection](02-api-connection.md),
[ResultSet and Metadata](04-api-result-sets.md),
[Statements and Cursors](22-statements.md)
