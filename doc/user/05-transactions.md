# Transactions

[Contents](README.md)

A transaction begins with a session's first statement and lasts until
COMMIT or ROLLBACK, as SQL-92 has it. This chapter tells how a program
sees it, how autocommit makes each statement its own, how a cursor and a
large object's locator keep theirs, and how a reset or a change of user
ends it. The examples use the low-level client and the table of
[Running Queries](03-queries.md).

## Whether one is active

`inTransaction` says whether the session's transaction is active, as
the server said after the last statement, a query's rows read or a
statement's completion. `setAutocommit(true)` makes each statement its
own transaction: the server commits it as it succeeds, or rolls it back
as it fails, within the same request, so no COMMIT is sent. A commit
that fails then, as one of a deferred constraint not satisfied
(SQLSTATE 40002), fails the statement, its transaction rolled back.

```java
Statements statements = conn.statements();
System.out.println("at login: " + conn.inTransaction());
statements.query("UPDATE STAFF SET SALARY = SALARY + 1");
System.out.println("after UPDATE: " + conn.inTransaction());
statements.query("ROLLBACK");
System.out.println("after ROLLBACK: " + conn.inTransaction());
conn.setAutocommit(true);
statements.query("UPDATE STAFF SET SALARY = SALARY + 1");
System.out.println("under autocommit, after UPDATE: " + conn.inTransaction());
statements.query("UPDATE STAFF SET SALARY = SALARY - 1");
```

```
at login: false
after UPDATE: true
after ROLLBACK: false
under autocommit, after UPDATE: false
```

## A cursor's transaction, and a locator's

A cursor is read within its transaction, which it keeps while it is
open, under autocommit too. Granted `fetch-ends`, under autocommit the
transaction ends as the cursor does: the fetch that reads its last row
commits, close commits, and rows that fail roll it back. Not granted, it
is left active.

```java
conn.setAutocommit(true);
Statements statements = conn.statements();
Statement names = statements.prepare("SELECT NAME FROM STAFF ORDER BY ID");
statements.openCursor(names, List.of(), 1);
System.out.println("cursor open: " + conn.inTransaction());
statements.fetch(names, 0);
System.out.println("its last row read: " + conn.inTransaction());
```

```
cursor open: true
its last row read: false
```

A large object longer than the server sends whole comes as a locator,
valid within its transaction alone, so a result that sent one keeps its
transaction active, under autocommit too, until the program commits or
rolls back.

## Reset, and a change of user

`controls().reset()` puts the session back as it was at login: its
transaction rolled back, its prepared statements, cursors, temporary
tables and settings gone, its user and cancel key kept. A transaction
it rolls back is told by notice 01T01. The server tells every setting
again, so `setting` gives each at the database's value.

```java
conn.statements().query("SET SESSION.TIME_ZONE = '+05:00'");
System.out.println("time zone " + conn.setting("SESSION.TIME_ZONE"));
conn.statements().query("UPDATE STAFF SET SALARY = 0");
conn.controls().reset();
for (Reply.Notice notice : conn.takeNotices()) {
    System.out.println(notice.sqlstate() + ": " + notice.message());
}
System.out.println("time zone " + conn.setting("SESSION.TIME_ZONE") + ", in a transaction: "
        + conn.inTransaction());
```

```
time zone +05:00
01T01: [DESIGN] Reset rolled back the transaction that was active
time zone +00:00, in a transaction: false
```

`controls().changeUser` authenticates the connection again as another
user, by a mechanism as a connection's login does, whose session it
then is, renewed as a reset renews it, under a new cancel key; no
transaction is active after it. A refusal fails the connection
([Connections](../client/21-connections.md)).
