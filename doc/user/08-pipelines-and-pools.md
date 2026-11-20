# Pipelines and Pools

[Contents](README.md)

A program under load sends many requests and serves many threads. A
pipeline sends a connection's requests without waiting for each reply,
so a batch costs one round trip, not one a request. A pool keeps a
bounded set of connections that a program's threads share. The
SQL-agent holds several named connections, one current, as SQL-92's
connection statements ask. This chapter tells how, with the low-level
client and the table of [Prepared Statements](04-prepared-statements.md).

## Pipelines

A connection's `pipeline` queues requests: `query` and `execute`. Its
`run` writes them and gives one outcome each, in order: `Done` with what
the request gave, or `Failed` with its failure. The server answers each
request alone and in turn, so a failure is its request's own and the
requests after it run. Under autocommit each request is its own
transaction, as `query` makes it.

```java
Statement insert = conn.statements().prepare("INSERT INTO STAFF (ID, NAME) VALUES (?, ?)");
List<Pipeline.Piped> outcomes = conn.pipeline()
        .query("INSERT INTO STAFF (ID, NAME) VALUES (1, 'ANN')")
        .execute(insert, List.of(new Value.Exact(BigInteger.TWO, 0), new Value.Text("BOB")))
        .execute(insert, List.of(new Value.Exact(BigInteger.ONE, 0), new Value.Text("CAROL")))
        .query("SELECT ID, NAME FROM STAFF ORDER BY ID")
        .run();
for (Pipeline.Piped outcome : outcomes) {
    if (outcome instanceof Pipeline.Piped.Done done && done.outcome() instanceof Outcome.Rows rows) {
        System.out.println(rows.rows().size() + " rows");
    } else if (outcome instanceof Pipeline.Piped.Failed failed) {
        System.out.println(failed.failure().getSQLState() + ": " + failed.failure().getMessage());
    } else {
        System.out.println(outcome.getClass().getSimpleName());
    }
}
```

```
Done
Done
23000: PRIMARY KEY ANN.STAFF_PRIMARY1 (ID) of ANN.STAFF would be violated: a row of these values is there
2 rows
```

The requests are written a window of 64 KiB at a time
(`Pipeline.WINDOW`), its replies read before the next window is
written, so a long batch never leaves client and server each waiting
for the other to read.

`onError(Pipeline.OnError.SKIP)` makes a batch stop at its first
failure, the requests before it kept. The driver sets a savepoint before
each request but the first; the requests after a failure are rolled back
to the savepoint set after it, and those of a window not yet written are
never sent. Each is `Skipped`. Such a batch is one transaction: under
autocommit, of a batch that began none, it is committed as the batch
ends; otherwise it stays open, as a statement would leave it. A
session's settings, which no rollback undoes, are not undone.

```java
List<Pipeline.Piped> outcomes = conn.pipeline()
        .onError(Pipeline.OnError.SKIP)
        .query("INSERT INTO STAFF (ID, NAME) VALUES (3, 'DAN')")
        .query("INSERT INTO STAFF (ID, NAME) VALUES (1, 'EVE')")
        .query("INSERT INTO STAFF (ID, NAME) VALUES (4, 'FAY')")
        .run();
outcomes.forEach(o -> System.out.println(o.getClass().getSimpleName()));
System.out.println(((Outcome.Rows) conn.statements().query("SELECT ID FROM STAFF ORDER BY ID")).rows().size()
        + " rows, in transaction: " + conn.inTransaction());
```

```
Done
Failed
Skipped
3 rows, in transaction: false
```

`run` fails, giving no outcome, if the connection is lost (SQLSTATE
08006), or if a skipping batch cannot be undone.

## Pools

A `Pool` keeps a bounded set of connections that a program's threads
share, made by a `Pool.Source`: connection settings' `connect`, or any
function that makes a connection. `get` hands out an idle connection
that answers, or makes one while fewer than `max` are made, or waits for
one to come back. The `Pooled` it gives lends its `connection()` and
goes back to the pool as it is closed, as by try-with-resources.

```java
Settings settings = Settings.fromUri("turborel://ann@127.0.0.1:17434/?tls_mode=disable");
Pool pool = new Pool(settings::connect, PoolConfig.defaults().withMax(2).withMaxWait(Duration.ofMillis(500)));
List<Thread> workers = new ArrayList<>();
for (int t = 0; t < 4; t++) {
    Thread worker = new Thread(() -> {
        try (Pooled pooled = pool.get()) {
            Connection conn = pooled.connection();
            conn.statements().query("UPDATE STAFF SET NAME = NAME WHERE ID = 1");
            conn.statements().query("COMMIT");
        } catch (TurborelException e) {
            System.out.println(e.getSQLState() + ": " + e.getMessage());
        }
    });
    worker.start();
    workers.add(worker);
}
for (Thread worker : workers) {
    worker.join();
}
System.out.println(pool.counts());
Pooled one = pool.get();
Pooled two = pool.get();
try {
    pool.get();
} catch (TurborelException e) {
    System.out.println(e.getSQLState() + ": " + e.getMessage());
}
one.close();
two.close();
pool.close();
```

```
Counts[made=2, idle=2]
08001: [CLIENT Pools] no connection of the pool's 2 came back within 500ms
```

Four threads shared two connections, each waiting its turn; with both
handed out, a third `get` waited half a second and failed.

A connection comes back reset to the session it logged in with: by the
protocol's Reset, one request that ends its transaction, prepared
statements, cursors, temporary tables and settings and keeps its user;
then autocommit is off and the pool's `reset` statements run. Its check,
as it is handed out again, is a Ping, which the server answers without a
worker. Of a server that takes neither, the check is ROLLBACK and the
reset one pipeline: its transaction rolled back, the statements it
prepared closed and destroyed, its user that of its login, its role
none, its schema its user's and its time zone local; a setting a
borrower changed by SET stays as it set it, unless `reset` sets it back.
A connection lost, whose reset fails, that has lived its time, or beyond
`maxIdle` is closed, never handed out again; one idle past `idleTimeout`,
or that does not answer its check, is closed as `get` meets it, and
another taken or made. [Appendix C](../appendix/c-settings.md) lists a
pool's settings and their defaults. A pool starts no thread; each wait
is the waiting thread's.

## The SQL-agent

An `Agent` holds a program's connections, each named, one current, and
runs CONNECT, SET CONNECTION and DISCONNECT itself, as SQL-92's
SQL-agent does; every other statement goes to the current connection.
An `Agent.Connector` the program gives makes the connections CONNECT
asks for, to the address it names, as the user it names. A connection
named by no AS takes its server's name, which must then be an
identifier.

```java
Agent agent = new Agent((server, user) -> {
    Connection conn = Settings.fromUri("turborel://" + (user == null ? "ann" : user) + "@"
            + (server == null ? "127.0.0.1:17434" : server) + "/?tls_mode=disable").connect();
    conn.setAutocommit(true);
    return conn;
});
agent.query("CONNECT TO DEFAULT");
agent.query("CONNECT TO '127.0.0.1:17434' AS 'reports' USER 'ann'");
System.out.println("current: " + agent.currentName());
System.out.println(((Outcome.Rows) agent.query("SELECT COUNT(*) FROM STAFF")).rows());
agent.query("SET CONNECTION DEFAULT");
System.out.println("current: " + agent.currentName());
for (String sql : List.of("CONNECT TO '127.0.0.1:17434'", "SET CONNECTION 'payroll'", "CONNECT TO DEFAULT")) {
    try {
        agent.query(sql);
    } catch (TurborelException e) {
        System.out.println(e.getSQLState() + ": " + e.getMessage());
    }
}
System.out.println(((Outcome.Done) agent.query("DISCONNECT ALL")).completed().sqlstate());
```

```
current: REPORTS
[[Exact[units=3, scale=0]]]
current: null
2E000: [ISO/IEC 9075:1992 §15.1 GR6] '127.0.0.1:17434' is not an <identifier>
08003: [ISO/IEC 9075:1992 §15.2 GR2] there is no connection PAYROLL
08002: [ISO/IEC 9075:1992 §15.1 GR7] the default connection is established
00000
```

A connection statement is refused while the current connection's
transaction is active (SQLSTATE 0A001), as no transaction spans servers,
and DISCONNECT of a connection whose transaction is active is refused
(25000). A connection that cannot be ended is a warning of DISCONNECT,
01002.

The API Reference's pages [Pipelines](../client/27-pipelines.md),
[Pools](../client/28-pools.md) and
[The SQL-agent](../client/29-sql-agent.md) give the classes.
