# Prepared Statements

[Contents](README.md)

A statement run many times, with other values each time, is prepared
once: the server reads it, and says what type each of its dynamic
parameters, written `?`, takes, and what columns a query's rows have.
This chapter tells how, with the low-level client and the table of
[Running Queries](03-queries.md).

## Prepare and execute

`prepare` gives a `Statement`, its parameters' types in the order of
their `?` marks and its columns. `execute` runs it with a value for each
parameter, as many times as wanted, across transactions; `deallocate`
lets it go.

```java
Statements statements = conn.statements();
Statement raise = statements.prepare("UPDATE STAFF SET SALARY = SALARY + ? WHERE ID = ?");
System.out.println(raise.parameters());
for (long id = 1; id <= 2; id++) {
    statements.execute(raise, List.of(new Value.Exact(BigInteger.valueOf(10000), 2),
            new Value.Exact(BigInteger.valueOf(id), 0)));
}
Statement pay = statements.prepare("SELECT SALARY FROM STAFF WHERE ID = ?");
Outcome.Rows rows = (Outcome.Rows) statements.execute(pay, List.of(new Value.Exact(BigInteger.ONE, 0)));
System.out.println(rows.rows());
statements.deallocate(raise);
statements.deallocate(pay);
statements.query("ROLLBACK");
```

```
[Parameter[varying=false, type=ExactType[precision=8, scale=2]], Parameter[varying=false, type=ExactType[precision=10, scale=0]]]
[[Exact[units=530000, scale=2]]]
```

Values not one for each parameter fail (SQLSTATE 07001); a statement
deallocated, or forgotten by a reset, is none (26000).

## A cursor, read in parts

`openCursor` executes a query and takes at most as many of its rows as
asked, all if 0; the `Batch` says whether more follow. `fetch` takes the
next, and the last batch closes the cursor; `closeCursor` closes it
early. A cursor already open, or one not open fetched or closed, fails
(SQLSTATE 24000); while a cursor is open its statement is neither
replaced nor deallocated.

```java
Statement names = statements.prepare("SELECT NAME FROM STAFF ORDER BY ID");
Batch batch = statements.openCursor(names, List.of(), 1);
System.out.println(batch);
while (batch.more()) {
    batch = statements.fetch(names, 1);
    System.out.println(batch);
}
```

```
Batch[rows=[[Text[text=Ada]]], more=true]
Batch[rows=[[Text[text=Grace]]], more=false]
```

A cursor is read within its transaction, which it keeps while it is
open ([Transactions](05-transactions.md)).

## A CALL's output parameters

A CALL's parameters are its inputs alone. Its columns are one for each
`?` of an OUT or INOUT parameter, in the procedure's order, named as
each, an INOUT one both a parameter and a column. Executed, it gives a
row of their values.

```java
statements.query("CREATE PROCEDURE PAYROLL (IN MOST DECIMAL(8,2), OUT HEADS INTEGER) READS SQL DATA "
        + "SELECT COUNT(*) INTO HEADS FROM STAFF WHERE SALARY <= MOST");
Statement call = statements.prepare("CALL PAYROLL(?, ?)");
System.out.println(call.parameters().size() + " parameter, columns "
        + call.columns().stream().map(Column::name).toList());
Outcome.Rows out = (Outcome.Rows) statements.execute(call,
        List.of(new Value.Exact(BigInteger.valueOf(600000), 2)));
System.out.println(out.rows());
```

```
1 parameter, columns [HEADS]
[[Exact[units=1, scale=0]]]
```

## Several cursors, and describe

Granted `cursors`, a statement has several cursors open at once, each
by a number of the program's, a `Statements.Cursor`; 0 is the one a
statement has without it. Granted `describe`, `describe` gives an open
cursor's columns, or a statement's parameters and columns as they are
now.

```java
Statement names = statements.prepare("SELECT NAME FROM STAFF ORDER BY ID");
Statements.Cursor first = new Statements.Cursor(names, 1);
Statements.Cursor second = new Statements.Cursor(names, 2);
System.out.println(statements.openCursor(first, List.of(), 1).rows());
System.out.println(statements.openCursor(second, List.of(), 1).rows());
System.out.println(statements.describe(first));
statements.closeCursor(first);
statements.closeCursor(second);
```

```
[[Text[text=Ada]]]
[[Text[text=Ada]]]
[Column[name=NAME, type=CharacterType[varying=true, length=20, units=CHARACTERS], notNull=false]]
```

The classes are told in full in the API Reference,
[Statements and Cursors](../client/22-statements.md).
