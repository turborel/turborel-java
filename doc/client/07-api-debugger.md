# Debugger

[Contents](README.md)

## NAME

`TurborelDebugger`: the routine debugger *(extension)*, which drives
the routines another connection's session runs

## SYNOPSIS

```java
package com.turborel.jdbc;

public interface TurborelConnection extends java.sql.Connection {
    TurborelDebugger debugger() throws SQLException;
}

public interface TurborelDebugger {
    enum RoutineKind { PROCEDURE, FUNCTION, TRIGGER }
    enum Step { INTO, OVER, OUT, CONTINUE }
    record Routine(String schema, String name) {}
    record Breakpoint(long number, Routine routine, long line, String condition) {}
    record Origin(RoutineKind kind, Routine specificName, Routine name) {}
    record Variable(String name, String type, Object value) {}
    record Cursor(String name, boolean open, String query) {}
    record Frame(Origin origin, boolean visible, Long place, Long line, Long column,
            List<Variable> parameters, List<Variable> variables, List<Cursor> cursors) {}
    record Pause(Long breakpoint, List<Frame> frames) {}
    sealed interface Event {
        record Paused(Pause pause) implements Event {}
        record TimedOut() implements Event {}
        record Cancelled() implements Event {}
        record Ended() implements Event {}
    }

    long id() throws SQLException;
    void attach(long id) throws SQLException;
    void detach() throws SQLException;
    Breakpoint setBreakpoint(RoutineKind kind, Routine routine, long line, String condition) throws SQLException;
    void clearBreakpoint(long number) throws SQLException;
    List<Breakpoint> breakpoints() throws SQLException;
    Event waitForEvent(Duration timeout) throws SQLException;        // or null
    Pause stack() throws SQLException;                                // or null
    void resume(Step step) throws SQLException;
    void interrupt() throws SQLException;
}
```

The low-level client's `Connection.debugger()` gives the same requests,
`Debugger`, over the codec's own types (RULES.md D2).

## DESCRIPTION

JDBC has no debugger, so the driver gives Turborel's as an interface of
its own, reached by `connection.unwrap(TurborelConnection.class)
.debugger()` (RULES.md D15). It needs `debugger` granted, which
connecting asks for. Two connections take part: the target runs its
statements as ever, often on another thread, and the debugger, a second
connection, drives the routines the target's session runs
([Debugging Routines](../user/11-debugging.md)).

**Attaching.** `id` gives a connection's ID, as
INFORMATION_SCHEMA.CONNECTIONS shows it, and the debugger's `attach`
makes it the debugger of the session of the connection of that ID: one
of the same user, unless the user is the database's owner or holds the
role MONITOR. `detach` ends the debugging; its breakpoints go, and its
target, if paused, goes on. The server lets the debugger go once the
wait for its next event under way ends, so a session attached again at
once may still have it, and refuse the new one.

**Breakpoints.** `setBreakpoint` sets one at a line, from 1, of the text
of a procedure, a function or a trigger, named by its specific name, or
by its name if one routine of that kind has it; an empty schema is the
debugger's session's default schema. Its condition, a search condition
over the frame's parameters and variables, or none, pauses only where
it is true. The server judges it in a session of the debugger's user,
as the debugger's connection waits between requests, and the target
waits at the breakpoint until it is judged: a pause timeout too short
for that ends such a wait as a timeout. The breakpoint set names its routine by its specific name.
`clearBreakpoint` clears one, and `breakpoints` lists them in the order
they were set. The debugger pauses in, and sees into, only the routines
its user owns or holds DEBUG on.

**Pauses.** `waitForEvent` gives the target's next event, waiting at
most the timeout, without limit if none, and none if none came: a
pause, at a breakpoint or at the end of a step; a timeout, the target
having paused longer than DEBUGGER.PAUSE_TIMEOUT_MS and gone on by
itself; a cancel of its statement while it was paused; or its end, the
debugger then detached. An event the server sends while the debugger's
connection reads another reply is kept until `waitForEvent` takes it.
A pause gives the call stack, the outermost frame first. Each frame
names its routine by its specific name and its name, none of a statement
run directly; gives its place in characters from the beginning of the
routine's text, its line and column from 1; and its parameters, a
trigger's its transition variables, its variables, each a name, a type
as SQL writes it and a value as `getObject` gives one, and its cursors.
A frame the debugger may not see into is not visible, and gives its
origin alone. `stack` gives the pause again, none if the target runs.

**Going on.** `resume` lets the paused target go on: `INTO` to its next
statement in whatever frame, `OVER` to the next of this frame or one it
returns to, `OUT` to the next of the frame this one returns to,
`CONTINUE` to a breakpoint. `interrupt` pauses the running target at its
next statement.

## ERRORS

| SQLCODE | SQLSTATE | When |
|---|---|---|
| -919 | 08006 | `debugger` not granted, refused before anything is sent, the connection kept; a timeout given to `waitForEvent` of a connection over a stream the program gave |
| | 08003 | `attach` of an ID not served, or of a connection the user may not see |
| -104 | 42000 | A breakpoint in a routine the user may not debug, as a `SQLSyntaxErrorException` |
| -910 | TBD01 | An action the target's state does not allow: `resume` of a target not paused, a second `attach`, an `attach` of a session another debugger has, or any request but `id` and `attach` of a debugger attached to none |

Each refusal keeps the session.

## EXAMPLES

A debugger stops a procedure at line 4 of its text, steps over it, and
lets it finish:

```java
TurborelDebugger debugger = watching.unwrap(TurborelConnection.class).debugger();
debugger.attach(target.unwrap(TurborelConnection.class).debugger().id());
debugger.setBreakpoint(TurborelDebugger.RoutineKind.PROCEDURE, new TurborelDebugger.Routine("", "RAISE_PAY"), 4, null);
// The target's CALL RAISE_PAY(10) runs on another thread.
if (debugger.waitForEvent(Duration.ofSeconds(10)) instanceof TurborelDebugger.Event.Paused paused) {
    TurborelDebugger.Frame top = paused.pause().frames().get(paused.pause().frames().size() - 1);
    System.out.println(top.origin().name().name() + " line " + top.line() + ", " + top.parameters());
    debugger.resume(TurborelDebugger.Step.OVER);
}
System.out.println(((TurborelDebugger.Event.Paused) debugger.waitForEvent(Duration.ofSeconds(10))).pause().frames().get(0).line());
debugger.resume(TurborelDebugger.Step.CONTINUE);
debugger.detach();
```

```
RAISE_PAY line 4, [Variable[name=PCT, type=INTEGER, value=10]]
5
```

## STANDARD

[DESIGN] D15; [CLIENT Debugging routines]; [PROTOCOL Debug 'V'].

## SEE ALSO

[Connection](02-api-connection.md),
[Debugging Routines](../user/11-debugging.md)
