# Debugging Routines

[Contents](README.md)

*(extension)* A procedure, a function or a trigger written in SQL can be
run under a debugger. The debugger is another connection to the same
server: it attaches to the session that runs the routine, sets
breakpoints at lines of the routine's text, and, each time that session
pauses, reads its call stack, each frame's parameters, variables and
cursors, and tells it how to go on. JDBC has no debugger, so the driver
gives Turborel's as its own interface, `TurborelDebugger`
([Debugger](../client/07-api-debugger.md)). The server's side of it is
told in Turborel's own manual.

## Who may debug what

A connection attaches to the session of another connection of its own
user. The database's owner, and a user granted the role MONITOR, attach
to any connection's session. Attaching shows nothing by itself: the
debugger sets breakpoints in, pauses in and sees the variables of only
the routines its user owns or holds DEBUG on. A frame of any other
routine is shown by its name alone.

## Two connections

The session to debug runs its statements as ever; here it has a table
and a procedure, whose UPDATE is line 4 of its text.

```java
try (Statement stmt = target.createStatement()) {
    stmt.execute("CREATE TABLE STAFF (ID INTEGER PRIMARY KEY, SALARY INTEGER)");
    stmt.execute("INSERT INTO STAFF VALUES (1, 50000), (2, 60000)");
    stmt.execute("CREATE PROCEDURE RAISE_PAY(IN PCT INTEGER) MODIFIES SQL DATA\n"
            + "BEGIN\n"
            + "  DECLARE N INTEGER DEFAULT 0;\n"
            + "  UPDATE STAFF SET SALARY = SALARY * (100 + PCT) / 100;\n"
            + "  SET N = (SELECT COUNT(*) FROM STAFF);\n"
            + "END");
}
```

A second connection of the same user attaches to it by its ID and sets a
breakpoint at line 4. The breakpoint names the procedure by its
specific name, as a routine's name may be overloaded.

```java
TurborelDebugger debugger = watching.unwrap(TurborelConnection.class).debugger();
debugger.attach(target.unwrap(TurborelConnection.class).debugger().id());
TurborelDebugger.Breakpoint set = debugger.setBreakpoint(
        TurborelDebugger.RoutineKind.PROCEDURE, new TurborelDebugger.Routine("", "RAISE_PAY"), 4, null);
System.out.println("Breakpoint " + set.number() + " at " + set.routine() + " line " + set.line());
```

```
Breakpoint 1 at Routine[schema=ANN, name=RAISE_PAY_1] line 4
```

The first connection calls the procedure on another thread. Its session
pauses as it comes to line 4, and its CALL completes only once the
debugger lets it go on. The debugger waits for the pause, at most 10
seconds here, and reads the call stack, the outermost frame first.

```java
ExecutorService runner = Executors.newSingleThreadExecutor();
Future<Boolean> call = runner.submit(() -> target.createStatement().execute("CALL RAISE_PAY(10)"));
TurborelDebugger.Event heard = debugger.waitForEvent(Duration.ofSeconds(10));
TurborelDebugger.Pause pause = ((TurborelDebugger.Event.Paused) heard).pause();
for (TurborelDebugger.Frame frame : pause.frames()) {
    System.out.println(frame.origin().name() + " line " + frame.line() + ", column " + frame.column());
    frame.parameters().forEach(p -> System.out.println("    " + p.name() + " " + p.type() + " = " + p.value()));
    frame.variables().forEach(v -> System.out.println("    " + v.name() + " " + v.type() + " = " + v.value()));
}
```

```
Routine[schema=ANN, name=RAISE_PAY] line 4, column 3
    PCT INTEGER = 10
    N INTEGER = 0
```

A step over runs the UPDATE and pauses at the next statement; continue
lets the session run to its next breakpoint, or here to the end of its
CALL, which completes in the first connection. The debugger then
detaches.

```java
debugger.resume(TurborelDebugger.Step.OVER);
TurborelDebugger.Pause next = ((TurborelDebugger.Event.Paused) debugger.waitForEvent(Duration.ofSeconds(10))).pause();
System.out.println("line " + next.frames().get(0).line());
debugger.resume(TurborelDebugger.Step.CONTINUE);
call.get();
debugger.detach();
```

```
line 5
```

## Stepping

`Step.INTO` pauses at the next statement, within a routine the
statement invokes too. `Step.OVER` pauses at the next statement of the
same frame, or of the one it returns to. `Step.OUT` pauses at the next
statement of the frame this one returns to. `Step.CONTINUE` runs to a
breakpoint, and `interrupt` pauses a running session at its next
statement. A breakpoint may carry a condition over the frame's
parameters and variables, and pauses only where it is true. The server
judges it for the debugger as its connection waits between requests;
the session waits at the breakpoint until then.
`breakpoints` lists the breakpoints, `clearBreakpoint` clears one, and
`stack` gives the paused session's frames again.

## When a pause ends by itself

A session paused longer than its setting DEBUGGER.PAUSE_TIMEOUT_MS goes
on by itself, and the debugger hears `Event.TimedOut`. A statement
cancelled while paused gives `Event.Cancelled`, and a session that ends
gives `Event.Ended`, the debugger then detached.

## When it is refused

An `attach` of an ID not served, or of a connection the user may not
see, fails with SQLSTATE 08003. A breakpoint in a routine the user may
not debug fails with 42000, SQLCODE -104. An action the session's state
does not allow, as `resume` of a session not paused, fails with TBD01,
SQLCODE -910. The debugger's session goes on after each.
