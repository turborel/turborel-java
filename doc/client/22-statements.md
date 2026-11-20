# Statements and Cursors

[Contents](README.md)

## NAME

`Statements`, `Statement`, `Outcome`, `Batch`, `Sink`, `Streamed`,
`Told`, and of `Controls` `queryMany` and `setQueryAttributes`: the
low-level client's statements, as turborel-client gives them

## SYNOPSIS

```java
package com.turborel.jdbc.internal.session;

public final class Statements {                     // Connection.statements()
    public Outcome query(String sql) throws TurborelException;
    public Streamed queryInto(String sql, Sink sink) throws TurborelException;
    public Statement prepare(String sql) throws TurborelException;
    public Outcome execute(Statement statement, List<Value> values) throws TurborelException;
    public Batch openCursor(Statement statement, List<Value> values, long rows) throws TurborelException;
    public Batch openCursor(Cursor cursor, List<Value> values, long rows) throws TurborelException;
    public Batch fetch(Statement statement, long rows) throws TurborelException;
    public Batch fetch(Cursor cursor, long rows) throws TurborelException;
    public void closeCursor(Statement statement) throws TurborelException;
    public void closeCursor(Cursor cursor) throws TurborelException;
    public Statement describe(Statement statement) throws TurborelException;
    public List<Column> describe(Cursor cursor) throws TurborelException;
    public void deallocate(Statement statement) throws TurborelException;
    public record Cursor(Statement statement, long number) {}
}

public record Statement(long number, List<Parameter> parameters, List<Column> columns) {}
public sealed interface Outcome {
    record Rows(List<Column> columns, List<List<Value>> rows) implements Outcome {}
    record Done(Completed completed) implements Outcome {}
}
public record Batch(List<List<Value>> rows, boolean more) {}
public interface Sink {
    void columns(List<Column> columns);
    void rows(List<List<Value>> rows);
}
public sealed interface Streamed {
    record Rows(long count) implements Streamed {}
    record Done(Completed completed) implements Streamed {}
}

public final class Told {                           // Connection.told()
    public @Nullable Long commitPosition();
    public @Nullable Long rowsAffected();
    public @Nullable Timing serverTime();
    public List<String> lastGenerated();
    public List<TurborelException> conditions();
    public List<TurborelException> takeConditions();
}

public final class Controls {                       // Connection.controls()
    public Ran queryMany(String sql) throws TurborelException;
    public void setQueryAttributes(List<Attribute> attributes) throws TurborelException;
}
public record Ran(List<Outcome> outcomes, @Nullable TurborelException failure) {}

public final class Connection {
    public void setAutocommit(boolean on);
    public boolean autocommit();
    public boolean inTransaction();
    public @Nullable String warning();
}
```

These packages are not exported by the module (RULES.md D2): JDBC's
classes rest on them, from milestone M7.

## DESCRIPTION

**Running.** `query` runs one statement: of a query, its columns and
rows, `Outcome.Rows`; of another, its completion, `Outcome.Done`, its
SQLCODE, 0 or 100, and SQLSTATE. `queryInto` gives a query's columns,
then its rows a batch at a time as they come, to a `Sink`, and gives how
many there were. Each statement ends its transaction under autocommit,
by the request's `ends`.

**Prepared statements.** `prepare` gives a `Statement` of the client's
number, counted from 0: its parameters' types, its inputs alone, in the
order of their `?` marks; and its columns, a query's, or of a CALL one
for each `?` of an OUT or INOUT parameter, in the procedure's order.
`execute` runs it with a value for each parameter: a query's rows, all
of them; a CALL's output parameters' values, as a row; another
statement's completion. `deallocate` destroys it.

**Cursors.** `openCursor` executes a query and takes at most `rows` of
its rows, all if 0; the `Batch` says whether more follow. `fetch` takes
the next, the last batch closing the cursor; `closeCursor` closes it
early. Granted `cursors`, a statement has several cursors open at once,
each a `Cursor` of a number of the program's; 0 is the one a statement
has without it. Under autocommit, granted `fetch-ends`, a cursor's
transaction ends as it does.

**Describe.** Granted `describe`, `describe` gives a statement's
parameters and columns as they are now, or an open cursor's columns.

**Several statements.** Granted `multi-statement`, `queryMany` runs a
text of several statements as one request, each in order, each ending
its transaction under autocommit, the first that fails stopping the
rest: `Ran` gives each outcome, and the failure that stopped the rest.

**Query attributes.** Granted `query-attributes`,
`setQueryAttributes` sets attributes every later Query and Execute
carries until set again, read in SQL by QUERY_ATTRIBUTE(name);
`traceparent` is the request's trace context.

**What a completion tells.** `Told` gives the last statement's, each as
granted: `rowsAffected` (`row-counts`), the rows changed, or a query's
rows once all were read; `lastGenerated` (`generated-values`), the
identity values an INSERT generated; `conditions` (`conditions`), the
warnings it raised, `takeConditions` taking them; `serverTime`
(`row-counts`), total, queued and locks, in microseconds; and
`commitPosition` (`commit-position`), the last commit's log position,
kept until another comes. `warning` gives the SQLSTATE of a warning the
last statement completed with.

## ERRORS

| SQLCODE | SQLSTATE | When |
|---|---|---|
| | the statement's | The server's failure of the statement, which keeps the connection |
| 100 | 02000 | Not a failure: a query that returned no row completes so |
| | 07001 | `execute`: values not one for each parameter |
| | 24000 | A cursor already open opened, one not open fetched or closed, or a statement of an open cursor replaced or deallocated |
| | 26000 | A statement deallocated, or forgotten by a reset |
| -919 | 08006 | A numbered cursor, describe, several statements or attributes not granted, refused before anything is sent, the connection kept; a reply out of its statement's order, which loses the connection |
| -919 | 08006 | Attributes past their bounds, refused before anything is sent, the connection kept |

## EXAMPLES

A query's rows:

```java
Outcome.Rows rows = (Outcome.Rows) conn.statements().query("SELECT ID, NAME, SALARY FROM STAFF ORDER BY ID");
for (Column column : rows.columns()) {
    System.out.println(column.name() + " " + column.type());
}
for (List<Value> row : rows.rows()) {
    System.out.println(row);
}
```

```
ID ExactType[precision=10, scale=0]
NAME CharacterType[varying=true, length=20, units=CHARACTERS]
SALARY ExactType[precision=8, scale=2]
[Exact[units=1, scale=0], Text[text=Ada], Exact[units=520000, scale=2]]
[Exact[units=2, scale=0], Text[text=Grace], Exact[units=610050, scale=2]]
```

A cursor, read in parts:

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

The User's Guide's chapters [Running Queries](../user/03-queries.md)
and [Prepared Statements](../user/04-prepared-statements.md) have more.

## STANDARD

[PROTOCOL Query 'Q'], [PROTOCOL Prepare 'P'], [PROTOCOL Execute 'E'],
[PROTOCOL Fetch 'F'], [PROTOCOL Close 'C'], [PROTOCOL Deallocate 'D'],
[PROTOCOL Describe 'Y'], [PROTOCOL Prepared 'P'], [PROTOCOL Complete 'K'],
[PROTOCOL Conversations]; [CLIENT Running statements],
[CLIENT Prepared statements], [CLIENT What the server tells],
[CLIENT Controlling the session].

## SEE ALSO

[Connections](21-connections.md),
[Values](23-values.md),
[Errors](30-errors.md)
