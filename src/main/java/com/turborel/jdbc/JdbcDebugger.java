package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.Debugging;
import com.turborel.jdbc.internal.codec.TypeName;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.internal.session.Debugger;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The routine debugger of a connection ([DESIGN] D15), over the low-level client's: each request
 * as the session's {@link Debugger} sends it, each answer made of the extension's own types, a
 * value as {@code getObject} gives it.
 */
final class JdbcDebugger implements TurborelDebugger {
    private final JdbcConnection connection;

    JdbcDebugger(JdbcConnection connection) {
        this.connection = connection;
    }

    /** What a request of the debugger runs. */
    @FunctionalInterface
    private interface Asked<T> {
        T ask(Debugger debugger) throws SQLException;
    }

    private <T> T asked(Asked<T> request) throws SQLException {
        connection.open();
        try {
            return request.ask(connection.session().debugger());
        } catch (SQLException failure) {
            throw Errors.classed(failure);
        }
    }

    @Override
    public long id() throws SQLException {
        return asked(Debugger::id);
    }

    @Override
    public void attach(long id) throws SQLException {
        asked(d -> {
            d.attach(id);
            return id;
        });
    }

    @Override
    public void detach() throws SQLException {
        asked(d -> {
            d.detach();
            return d;
        });
    }

    @Override
    public Breakpoint setBreakpoint(RoutineKind kind, Routine routine, long line, @Nullable String condition)
            throws SQLException {
        Debugging.Kind of = Debugging.Kind.valueOf(kind.name());
        TypeName name = new TypeName(routine.schema(), routine.name());
        return breakpoint(asked(d -> d.breakAt(of, name, line, condition)));
    }

    @Override
    public void clearBreakpoint(long number) throws SQLException {
        asked(d -> {
            d.clear(number);
            return number;
        });
    }

    @Override
    public List<Breakpoint> breakpoints() throws SQLException {
        List<Breakpoint> all = new ArrayList<>();
        for (Debugging.Breakpoint each : asked(Debugger::breakpoints)) {
            all.add(breakpoint(each));
        }
        return all;
    }

    @Override
    public @Nullable Event waitForEvent(@Nullable Duration timeout) throws SQLException {
        connection.open();
        Debugging.Event heard = connection.session().debugger().waitFor(timeout);
        if (heard == null) {
            return null;
        } else if (heard instanceof Debugging.Event.Pause pause) {
            return new Event.Paused(pause(pause.paused()));
        } else if (heard instanceof Debugging.Event.TimedOut) {
            return new Event.TimedOut();
        }
        return heard instanceof Debugging.Event.Cancelled ? new Event.Cancelled() : new Event.Ended();
    }

    @Override
    public @Nullable Pause stack() throws SQLException {
        connection.open();
        Debugging.Paused paused = connection.session().debugger().stack();
        return paused == null ? null : pause(paused);
    }

    @Override
    public void resume(Step step) throws SQLException {
        asked(d -> {
            d.resume(Debugging.Action.valueOf(step.name()));
            return step;
        });
    }

    @Override
    public void interrupt() throws SQLException {
        asked(d -> {
            d.interrupt();
            return d;
        });
    }

    private static Routine routine(TypeName name) {
        return new Routine(name.schema(), name.name());
    }

    private static Breakpoint breakpoint(Debugging.Breakpoint b) {
        return new Breakpoint(b.number(), routine(b.routine()), b.line(), b.condition());
    }

    private Pause pause(Debugging.Paused paused) throws SQLException {
        List<Frame> frames = new ArrayList<>();
        for (Debugging.Frame f : paused.frames()) {
            Debugging.Origin o = f.origin();
            Origin origin = o == null
                    ? null
                    : new Origin(RoutineKind.valueOf(o.kind().name()), routine(o.name()), routine(o.shown()));
            List<Cursor> cursors = f.cursors().stream()
                    .map(c -> new Cursor(c.name(), c.open(), c.query()))
                    .toList();
            frames.add(new Frame(
                    origin,
                    f.visible(),
                    f.at(),
                    f.line(),
                    f.column(),
                    named(f.parameters()),
                    named(f.variables()),
                    cursors));
        }
        return new Pause(paused.breakpoint(), frames);
    }

    private List<Variable> named(List<Debugging.Named> all) throws SQLException {
        List<Variable> made = new ArrayList<>();
        for (Debugging.Named each : all) {
            Value v = each.value();
            made.add(new Variable(each.name(), each.dataType(), connection.object(v, null)));
        }
        return made;
    }
}
