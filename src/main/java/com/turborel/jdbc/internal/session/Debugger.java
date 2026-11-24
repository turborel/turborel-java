package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.DebugRequest;
import com.turborel.jdbc.internal.codec.Debugging;
import com.turborel.jdbc.internal.codec.Reply;
import com.turborel.jdbc.internal.codec.TypeName;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The routine debugger, from a client ([CLIENT Debugging routines]) *(extension)*: a connection
 * granted {@code debugger} attaches to the session of another connection, by that one's ID, sets
 * breakpoints in the routines and triggers its user may debug, and drives that session's routines
 * as they pause, step by step, reading their call stack. What it hears of its target, an Event,
 * comes unasked and is kept until {@link #waitFor} takes it. As Turborel's {@code
 * client/src/debug.rs}.
 */
public final class Debugger {
    private final Connection connection;
    private final Statements statements;

    Debugger(Connection connection, Statements statements) {
        this.connection = connection;
        this.statements = statements;
    }

    /** The answer of a Debug request answered by Debugged. */
    private Debugging.Answer answered(DebugRequest request) throws TurborelException {
        connection.needs(false, Capability.DEBUGGER);
        connection.send(request);
        Reply reply = connection.receive();
        if (reply instanceof Reply.Debugged debugged) {
            return debugged.answer();
        }
        throw statements.unexpected(reply, "the server sent no Debugged reply");
    }

    /** A Debug request sent, its completion read. */
    private void done(DebugRequest request) throws TurborelException {
        connection.needs(false, Capability.DEBUGGER);
        connection.send(request);
        statements.completion();
    }

    private TurborelException none(String what) {
        return connection.lose("[PROTOCOL Debugged 'V'] the server sent no " + what);
    }

    /**
     * This connection's ID, as INFORMATION_SCHEMA.CONNECTIONS shows it: what a debugger attaches
     * to its session by.
     *
     * @return the ID, a {@code u64}'s bits
     * @throws TurborelException a connection failure: {@code debugger} not granted, or the server
     *     gone
     */
    public long id() throws TurborelException {
        if (answered(new DebugRequest.Identify()) instanceof Debugging.Answer.Connection id) {
            return id.id();
        }
        throw none("connection's ID");
    }

    /**
     * Makes this connection the debugger of the session of the connection of {@code id}.
     *
     * @param id the connection's ID
     * @throws TurborelException SQLSTATE 08003 of an ID not served or not seen; TBD01 if this
     *     connection already debugs one
     */
    public void attach(long id) throws TurborelException {
        done(new DebugRequest.Attach(id));
    }

    /**
     * Ends the debugging: the breakpoints go, the target, if paused, goes on, and the events kept
     * are dropped.
     *
     * @throws TurborelException SQLSTATE TBD01 if this connection debugs none
     */
    public void detach() throws TurborelException {
        connection.told().dropEvents();
        done(new DebugRequest.Detach());
    }

    /**
     * Sets a breakpoint at a line of a procedure's, a function's or a trigger's text.
     *
     * @param kind the routine's kind
     * @param routine its specific name, or its name if one routine of that kind has it, or the
     *     trigger's; an empty schema is this session's default schema
     * @param line the line, from 1
     * @param condition a search condition over the frame's parameters and variables it pauses on
     *     where true, or none
     * @return the breakpoint set, its routine by its specific name
     * @throws TurborelException SQLSTATE 42000 (SQLCODE -104) of a routine the user may not debug;
     *     TBD01 if this connection debugs none
     */
    public Debugging.Breakpoint breakAt(Debugging.Kind kind, TypeName routine, long line, @Nullable String condition)
            throws TurborelException {
        if (answered(new DebugRequest.Break(kind, routine, line, condition)) instanceof Debugging.Answer.Set set) {
            return set.breakpoint();
        }
        throw none("breakpoint");
    }

    /**
     * Clears the breakpoint of {@code number}.
     *
     * @param number the breakpoint's number
     * @throws TurborelException SQLSTATE TBD01 if this connection debugs none
     */
    public void clear(long number) throws TurborelException {
        done(new DebugRequest.Clear(number));
    }

    /**
     * Every breakpoint set, in the order set.
     *
     * @return the breakpoints
     * @throws TurborelException SQLSTATE TBD01 if this connection debugs none
     */
    public List<Debugging.Breakpoint> breakpoints() throws TurborelException {
        if (answered(new DebugRequest.Breakpoints()) instanceof Debugging.Answer.Listed listed) {
            return listed.breakpoints();
        }
        throw none("breakpoints");
    }

    /**
     * Lets the paused target go on: a step into, over or out, or to a breakpoint.
     *
     * @param action how
     * @throws TurborelException SQLSTATE TBD01 of a target not paused
     */
    public void resume(Debugging.Action action) throws TurborelException {
        done(new DebugRequest.Resume(action));
    }

    /**
     * Pauses the running target at its next statement.
     *
     * @throws TurborelException SQLSTATE TBD01 of a target not running
     */
    public void interrupt() throws TurborelException {
        done(new DebugRequest.Interrupt());
    }

    /**
     * The target's pause, with its call stack, the outermost frame first; none if it runs.
     *
     * @return the pause, or none
     * @throws TurborelException SQLSTATE TBD01 if this connection debugs none
     */
    public Debugging.@Nullable Paused stack() throws TurborelException {
        if (answered(new DebugRequest.Stack()) instanceof Debugging.Answer.Stack stack) {
            return stack.paused();
        }
        throw none("stack");
    }

    /**
     * The next event of the session this connection debugs not yet taken: one kept, else the next
     * the server sends, waited for at most {@code within}, without limit if none; none if none
     * came. It is read between requests, never while one is answered, and a Notice or a Status that
     * comes instead is taken as ever, none given. A connection over a stream the program gave waits
     * only as its maker bounded it.
     *
     * @param within the longest it waits, or none for as long as it takes
     * @return the event, or none
     * @throws TurborelException a connection failure: {@code within} given of a stream the program
     *     gave, the server gone, or a reply where an event was waited for
     */
    public Debugging.@Nullable Event waitFor(@Nullable Duration within) throws TurborelException {
        connection.needs(false, Capability.DEBUGGER);
        Debugging.Event kept = connection.told().takeEvent();
        if (kept != null) {
            return kept;
        }
        if (!arrives(within)) {
            return null;
        }
        Reply reply = connection.receiver().single();
        if (reply instanceof Reply.Event event) {
            return event.event();
        } else if (reply instanceof Reply.Notice notice) {
            connection.told().notice(notice);
        } else if (reply instanceof Reply.Status status) {
            connection.told().status(status.name(), status.value());
        } else {
            throw statements.unexpected(reply, "the server answered no request");
        }
        return null;
    }

    /** Whether a frame begins to arrive within {@code within}, the connection's own wait put back. */
    private boolean arrives(@Nullable Duration within) throws TurborelException {
        Duration kept = connection.readTimeout();
        boolean bound = !Objects.equals(within, kept);
        if (bound) {
            connection.setReadTimeout(within);
        }
        try {
            return connection.wire().arrives();
        } catch (TurborelException failed) {
            throw connection.lostBy(failed);
        } finally {
            if (bound && !connection.isLost()) {
                connection.setReadTimeout(kept);
            }
        }
    }
}
