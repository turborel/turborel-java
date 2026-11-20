# Pools

[Contents](README.md)

## NAME

`Pool`, `PoolConfig`, `Pooled`: a bounded set of connections a
program's threads share, as turborel-client's pools give them

## SYNOPSIS

```java
package com.turborel.jdbc.internal.session;

public final class Pool {
    @FunctionalInterface public interface Source { Connection connect() throws TurborelException; }
    public record Counts(int made, int idle) {}
    public Pool(Source source, PoolConfig config);
    public Pooled get() throws TurborelException;
    public Counts counts();
    public PoolConfig config();
    public void close();
}

public record PoolConfig(int max, int maxIdle, @Nullable Duration idleTimeout,
        @Nullable Duration maxLifetime, Duration maxWait, boolean check, List<String> reset) {
    public static final int DEFAULT_MAX = 10;
    public static final Duration DEFAULT_WAIT, DEFAULT_IDLE_TIMEOUT, DEFAULT_MAX_LIFETIME;
    public static PoolConfig defaults();
    public PoolConfig withMax(int most);
    public PoolConfig withMaxIdle(int most);
    public PoolConfig withIdleTimeout(@Nullable Duration most);
    public PoolConfig withMaxLifetime(@Nullable Duration most);
    public PoolConfig withMaxWait(Duration most);
    public PoolConfig withCheck(boolean checked);
    public PoolConfig withReset(List<String> statements);
}

public final class Pooled implements AutoCloseable {
    public Connection connection() throws TurborelException;
    public void close();
}
```

`maxWait` is turborel-client's `wait`, a name Java's `Object` holds.
These packages are not exported by the module (RULES.md D2).

## DESCRIPTION

**Get.** `get` takes the idle connection given back last, if it is
fresh: not idle past `idleTimeout`, not older than `maxLifetime`, not
lost; one that is not is closed and the next looked at. Under `check`
it sends Ping, granted `ping`, else ROLLBACK, each waiting at most 30
seconds, or the connection's read timeout if shorter; one that does not
answer is closed and its place given up. With none idle, it makes one
while fewer than `max` are made, by `Source.connect`; else it waits for
one to come back, at most `maxWait`.

**Give back.** `Pooled.close` gives the connection back; a second close
does nothing, and `connection()` after it is misuse. A connection that
has lived `maxLifetime` is closed. Else it is reset: granted `reset`, by
Reset, then the `reset` statements; else, if the session left no more
than 256 statements prepared, by one pipeline of ROLLBACK, a Close and
a Deallocate of each statement it prepared, SET SESSION AUTHORIZATION
and SET SCHEMA of its login's name, SET TIME ZONE LOCAL, and the `reset`
statements, a failure of the Close and Deallocate taken as a statement
already gone. Either way autocommit is off after. A connection reset is
kept idle while fewer than `maxIdle` are; else, or if its reset fails,
it is closed.

**Threads.** A pool is thread-safe by a lock and its condition alone,
and starts no thread; each wait is the waiting thread's. `close` closes
every idle connection; one handed out still comes back to the pool as
before, so a program closes the pool once its threads are done.

## ERRORS

| SQLCODE | SQLSTATE | When |
|---|---|---|
| -919 | 08001 | `get`: no connection came back within `maxWait`, or the wait was interrupted, the thread's interrupt kept |
| | the source's | `get`: the source could not make a connection, its place given back |
| -910 | TB910 | `Pooled.connection` once it is given back |

## EXAMPLES

Four threads sharing two connections, then a `get` that waits in vain:

```java
Pool pool = new Pool(settings::connect, PoolConfig.defaults().withMax(2).withMaxWait(Duration.ofMillis(500)));
```

```
Counts[made=2, idle=2]
08001: [CLIENT Pools] no connection of the pool's 2 came back within 500ms
```

The User's Guide's chapter [Pipelines and Pools](../user/08-pipelines-and-pools.md)
has the whole program, and [Appendix C](../appendix/c-settings.md) the
pool's settings.

## STANDARD

[CLIENT Pools]; [PROTOCOL Ping 'G'], [PROTOCOL Reset 'W'].

## SEE ALSO

[Settings](26-settings.md), [Pipelines](27-pipelines.md),
[Connections](21-connections.md)
