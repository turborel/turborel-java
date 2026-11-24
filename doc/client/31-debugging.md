# Debugging Routines

[Contents](README.md)

## NAME

`Debugger`: the low-level client's routine debugger *(extension)*, a
connection that drives the routines another connection's session runs

## SYNOPSIS

```java
package com.turborel.jdbc.internal.session;

public final class Debugger {
    public long id() throws TurborelException;
    public void attach(long id) throws TurborelException;
    public void detach() throws TurborelException;
    public Debugging.Breakpoint breakAt(Debugging.Kind kind, TypeName routine, long line,
            @Nullable String condition) throws TurborelException;
    public void clear(long number) throws TurborelException;
    public List<Debugging.Breakpoint> breakpoints() throws TurborelException;
    public Debugging.@Nullable Event waitFor(@Nullable Duration within) throws TurborelException;
    public Debugging.@Nullable Paused stack() throws TurborelException;
    public void resume(Debugging.Action action) throws TurborelException;
    public void interrupt() throws TurborelException;
}

package com.turborel.jdbc.internal.codec;

public final class Debugging {
    public enum Kind { PROCEDURE, FUNCTION, TRIGGER; public int code(); }
    public enum Action { INTO, OVER, OUT, CONTINUE; public int code(); }
    public record Breakpoint(long number, TypeName routine, long line, @Nullable String condition) {}
    public record Origin(Kind kind, TypeName name, TypeName shown) {}
    public record Named(String name, String dataType, Value value) {}
    public record Cursor(String name, boolean open, String query) {}
    public record Frame(@Nullable Origin origin, boolean visible, @Nullable Long at, @Nullable Long line,
            @Nullable Long column, List<Named> parameters, List<Named> variables, List<Cursor> cursors) {}
    public record Paused(@Nullable Long breakpoint, List<Frame> frames) {}
    public sealed interface Event {
        record Pause(Paused paused) implements Event {}
        record TimedOut() implements Event {}
        record Cancelled() implements Event {}
        record Ended() implements Event {}
    }
}
```

A connection's `debugger()` gives its `Debugger`
([Connections](21-connections.md)). These packages are not exported by
the module (RULES.md D2); a JDBC program reaches the same debugger as
`TurborelDebugger` ([Debugger](07-api-debugger.md)).

## DESCRIPTION

With `debugger` granted, which connecting asks for, one connection
debugs the session of another; without it each method fails with
SQLSTATE 08006 before anything is sent, the connection kept. `id` gives
a connection's ID, as INFORMATION_SCHEMA.CONNECTIONS shows it, and
`attach` makes this connection the debugger of the session of the
connection of that ID: one of its own user's, or any if its user is the
database's owner or holds MONITOR. `detach` ends the debugging: its
breakpoints go, its target, if paused, goes on, and the events kept are
dropped.

`breakAt` sets a breakpoint at a line, from 1, of a procedure's, a
function's or a trigger's text, named by its specific name, or by its
name if one routine of that kind has it; an empty schema is the
session's default schema. A condition, a search condition over the
frame's parameters and variables, pauses only where it is true; the
server judges it as the debugger's connection waits between requests,
and the target waits at the breakpoint until then. The breakpoint set
names its routine by its specific name. `clear` clears one, and
`breakpoints` lists them in the order they were set.

`waitFor` gives the target's next event: a pause, at a breakpoint
(`breakpoint` its number) or at the end of a step (none); a timeout,
the target paused longer than DEBUGGER.PAUSE_TIMEOUT_MS and gone on by
itself; a cancel of its statement while paused; or its end, the
debugger then detached. It waits at most `within`, without limit if
none, and gives none if nothing came. An Event the server sends while
the connection reads another request's replies is kept with what the
server told, and `waitFor` takes it first. It reads between requests
only, and a Notice or a Status that comes in an event's place is taken
as ever, none given. A connection over a stream the program gave waits
only as its maker bounded it, and refuses `within`.

A pause gives the call stack, the outermost frame first. Each frame
names its routine by its specific name (`name`) and its name (`shown`),
none of a statement run directly; gives its place in characters from
the beginning of the routine's text, its line and column from 1; and
its parameters, a trigger's its transition variables, its variables and
its cursors. A frame the debugger may not see into is not visible, and
gives its origin alone. `stack` gives the pause again, none if the
target runs. `resume` lets a paused target go on by its `Action`, and
`interrupt` pauses a running one at its next statement.

## ERRORS

| SQLCODE | SQLSTATE | When |
|---|---|---|
| -919 | 08006 | `debugger` not granted, refused before anything is sent, the connection kept; `within` given of a stream the program gave |
| -919 | 08006 | A Debugged whose answer is not the one its request is due, or a reply that is no Event where one was waited for: the connection lost |
| | 08003 | `attach` of an ID not served, or of a connection the user may not see |
| -104 | 42000 | A breakpoint in a routine the user may not debug |
| -910 | TBD01 | An action the target's state does not allow: `resume` of a target not paused, a second `attach`, an `attach` of a session another debugger has, or any request but `id` and `attach` of a debugger attached to none |

The server's refusals keep the session.

## EXAMPLES

A procedure paused at line 4 of its text, run on another thread, then
let go on:

```java
target.statements().query("""
        CREATE PROCEDURE COUNT_TO(IN TIMES INTEGER) CONTAINS SQL
        BEGIN
          DECLARE I INTEGER DEFAULT 0;
          WHILE I < TIMES DO
            SET I = I + 1;
          END WHILE;
        END""");
Debugger debugger = watching.debugger();
debugger.attach(target.debugger().id());
debugger.breakAt(Debugging.Kind.PROCEDURE, new TypeName("", "COUNT_TO"), 5, "I = 2");
ExecutorService runner = Executors.newSingleThreadExecutor();
Future<Outcome> call = runner.submit(() -> target.statements().query("CALL COUNT_TO(5)"));
if (debugger.waitFor(Duration.ofSeconds(10)) instanceof Debugging.Event.Pause pause) {
    Debugging.Frame frame = pause.paused().frames().get(0);
    System.out.println(frame.origin().shown() + " line " + frame.line() + ", breakpoint " + pause.paused().breakpoint());
    System.out.println(frame.variables());
    debugger.resume(Debugging.Action.CONTINUE);
}
System.out.println("completed: " + (call.get() instanceof Outcome.Done));
debugger.detach();
```

```
TypeName[schema=ANN, name=COUNT_TO] line 5, breakpoint 1
[Named[name=I, dataType=INTEGER, value=Exact[units=2, scale=0]]]
completed: true
```

The User's Guide's chapter [Debugging Routines](../user/11-debugging.md)
has more.

## STANDARD

*(extension)* [PROTOCOL Debug 'V'], [PROTOCOL Debugged 'V'] and
[PROTOCOL Event 'W']; [CLIENT Debugging routines].

## SEE ALSO

[Debugger](07-api-debugger.md), [Connections](21-connections.md),
[Errors](30-errors.md),
[Debugging Routines](../user/11-debugging.md)
