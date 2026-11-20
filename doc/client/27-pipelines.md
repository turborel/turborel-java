# Pipelines

[Contents](README.md)

## NAME

`Pipeline`: requests written at once, one outcome each, as
turborel-client's pipelines give them

## SYNOPSIS

```java
package com.turborel.jdbc.internal.session;

public final class Pipeline {                       // Connection.pipeline()
    public static final int WINDOW = 64 * 1024;
    public enum OnError { CONTINUE, SKIP }
    public sealed interface Piped {
        record Done(Outcome outcome) implements Piped {}
        record Failed(TurborelException failure) implements Piped {}
        record Skipped() implements Piped {}
    }
    public Pipeline query(String sql);
    public Pipeline execute(Statement statement, List<Value> values);
    public Pipeline onError(OnError how);
    public int size();
    public boolean isEmpty();
    public List<Piped> run() throws TurborelException;
}
```

These packages are not exported by the module (RULES.md D2).

## DESCRIPTION

`query` queues a statement, run as `Statements.query` runs it;
`execute` an execution of a prepared statement, all of a query's rows.
`run` encodes every request, groups them into windows of at most
`WINDOW` bytes, a request longer than that alone in its own, writes each
window at once and reads its replies before writing the next, and gives
one outcome for each request, in the order queued; the pipeline is
empty after, to queue more. Under autocommit each request ends its
transaction, as a statement does, and carries the query attributes set.

Under `OnError.CONTINUE`, the default, a failure is its request's
outcome alone. Under `OnError.SKIP` a query `SAVEPOINT
TURBOREL_PIPELINE_n` is sent before each request but the first; after
the first failure the requests of windows not yet written are never
sent, those after it are rolled back to the savepoint set after it, and
each is `Skipped`; the first savepoint is released; and under autocommit
of a batch that began no transaction, its work is committed. The
batch's own statements never end the transaction. What a rollback
undoes is undone; a session's settings are not.

## ERRORS

| SQLCODE | SQLSTATE | When |
|---|---|---|
| | the server's | A request's own failure, as its outcome |
| -919 | 08006 | `run`: the connection lost; what was answered before is not given |
| -910 | TB910 | `run` while a copy is under way, nothing sent |
| | the codec's | A request the codec refuses, before any is sent, the connection kept |
| | the server's | A skipping batch's rollback, release or commit that fails |

## EXAMPLES

A request that fails, the rest run:

```java
List<Pipeline.Piped> outcomes = conn.pipeline()
        .query("INSERT INTO STAFF (ID, NAME) VALUES (1, 'ANN')")
        .execute(insert, List.of(new Value.Exact(BigInteger.TWO, 0), new Value.Text("BOB")))
        .execute(insert, List.of(new Value.Exact(BigInteger.ONE, 0), new Value.Text("CAROL")))
        .query("SELECT ID, NAME FROM STAFF ORDER BY ID")
        .run();
```

```
Done
Done
23000: PRIMARY KEY ANN.STAFF_PRIMARY1 (ID) of ANN.STAFF would be violated: a row of these values is there
2 rows
```

The User's Guide's chapter [Pipelines and Pools](../user/08-pipelines-and-pools.md)
has more.

## STANDARD

[PROTOCOL Requests]; [CLIENT Pipelines].

## SEE ALSO

[Statements and Cursors](22-statements.md), [Pools](28-pools.md)
