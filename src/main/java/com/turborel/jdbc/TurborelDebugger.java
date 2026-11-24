package com.turborel.jdbc;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The routine debugger *(extension)*, which JDBC has no place for ([DESIGN] D15): this connection
 * attaches to the session of another connection of the server, by that one's ID, sets breakpoints
 * in the routines and triggers its user may debug, and drives that session's routines as they
 * pause, step by step, reading their call stack, parameters, variables and cursors ([CLIENT
 * Debugging routines]). Reached by {@code connection.unwrap(TurborelConnection.class).debugger()};
 * the target connection runs its statements as ever, often on another thread.
 */
public interface TurborelDebugger {
    /** The kind of a routine a breakpoint is set in, or a frame runs. */
    enum RoutineKind {
        /** A procedure. */
        PROCEDURE,
        /** A function. */
        FUNCTION,
        /** A trigger. */
        TRIGGER
    }

    /** How a paused target goes on. */
    enum Step {
        /** To its next statement, in whatever frame. */
        INTO,
        /** To the next statement of this frame, or of one it returns to. */
        OVER,
        /** To the next statement of the frame this one returns to. */
        OUT,
        /** To a breakpoint. */
        CONTINUE
    }

    /**
     * A routine's or a trigger's name.
     *
     * @param schema its schema
     * @param name its name
     */
    record Routine(String schema, String name) {}

    /**
     * A breakpoint set.
     *
     * @param number its number
     * @param routine its routine, by its specific name
     * @param line its line, from 1
     * @param condition the condition it pauses on, or none
     */
    record Breakpoint(
            long number,
            Routine routine,
            long line,
            @Nullable String condition) {}

    /**
     * Where a frame's routine comes from.
     *
     * @param kind its kind
     * @param specificName its specific name
     * @param name its name
     */
    record Origin(RoutineKind kind, Routine specificName, Routine name) {}

    /**
     * A parameter or variable of a frame.
     *
     * @param name its name
     * @param type its data type, as SQL writes it
     * @param value its value, as {@code getObject} gives one, or none for the null value
     */
    record Variable(String name, String type, @Nullable Object value) {}

    /**
     * A cursor of a frame.
     *
     * @param name its name
     * @param open whether it is open
     * @param query its query
     */
    record Cursor(String name, boolean open, String query) {}

    /**
     * A frame of a paused call stack: a run of a routine's or a trigger's body.
     *
     * @param origin its routine, none of a statement run directly
     * @param visible whether this debugger may see into it; one not gives its origin alone
     * @param place its place, in characters from the beginning of its routine's text, if given
     * @param line its line from 1, if given
     * @param column its column from 1, given with its line
     * @param parameters its parameters, a trigger's its transition variables
     * @param variables its variables
     * @param cursors its cursors
     */
    record Frame(
            @Nullable Origin origin,
            boolean visible,
            @Nullable Long place,
            @Nullable Long line,
            @Nullable Long column,
            List<Variable> parameters,
            List<Variable> variables,
            List<Cursor> cursors) {
        /**
         * A frame, its lists copied.
         *
         * @param origin what it is the body of; none of a statement run directly
         * @param visible whether the debugger may see into it
         * @param place its place, in characters from the beginning of its routine's text
         * @param line its line, from 1
         * @param column its column, from 1
         * @param parameters its parameters, or a trigger's transition variables
         * @param variables its variables in scope
         * @param cursors its cursors in scope
         */
        public Frame {
            parameters = List.copyOf(parameters);
            variables = List.copyOf(variables);
            cursors = List.copyOf(cursors);
        }
    }

    /**
     * A target paused, and its call stack.
     *
     * @param breakpoint the number of the breakpoint it paused at, or none at the end of a step
     * @param frames its frames, the outermost first
     */
    record Pause(@Nullable Long breakpoint, List<Frame> frames) {
        /**
         * A pause, its frames copied.
         *
         * @param breakpoint the number of the breakpoint it is at; none of a step
         * @param frames its call stack, the outermost first
         */
        public Pause {
            frames = List.copyOf(frames);
        }
    }

    /** What the debugger hears of its target. */
    sealed interface Event {
        /**
         * The target paused.
         *
         * @param pause the pause
         */
        record Paused(Pause pause) implements Event {}

        /** The target, paused longer than DEBUGGER.PAUSE_TIMEOUT_MS, went on by itself. */
        record TimedOut() implements Event {}

        /** The target's statement was cancelled while it was paused. */
        record Cancelled() implements Event {}

        /** The target's session ended, and this debugger is detached. */
        record Ended() implements Event {}
    }

    /**
     * This connection's ID, as INFORMATION_SCHEMA.CONNECTIONS shows it: what a debugger attaches
     * to its session by.
     *
     * @return the ID
     * @throws SQLException SQLSTATE 08006 if the server granted no {@code debugger}
     */
    long id() throws SQLException;

    /**
     * Makes this connection the debugger of the session of the connection of {@code id}, one of
     * the same user, unless the user is the database's owner or holds the role MONITOR.
     *
     * @param id the connection's ID
     * @throws SQLException SQLSTATE 08003 of an ID not served or not seen; TBD01 if this
     *     connection already debugs one
     */
    void attach(long id) throws SQLException;

    /**
     * Ends the debugging: its breakpoints go, its target, if paused, goes on, and the events not yet
     * taken are dropped.
     *
     * @throws SQLException SQLSTATE TBD01 if this connection debugs none
     */
    void detach() throws SQLException;

    /**
     * Sets a breakpoint at a line of a procedure's, a function's or a trigger's text.
     *
     * @param kind the routine's kind
     * @param routine its specific name, or its name if one routine of that kind has it, or the
     *     trigger's; an empty schema is this session's default schema
     * @param line the line, from 1
     * @param condition a search condition over the frame's parameters and variables, pausing only
     *     where it is true, or none
     * @return the breakpoint set
     * @throws SQLException a {@code SQLSyntaxErrorException}, SQLSTATE 42000 (SQLCODE -104), of a
     *     routine the user may not debug; TBD01 if this connection debugs none
     */
    Breakpoint setBreakpoint(RoutineKind kind, Routine routine, long line, @Nullable String condition)
            throws SQLException;

    /**
     * Clears the breakpoint of {@code number}.
     *
     * @param number its number
     * @throws SQLException SQLSTATE TBD01 if this connection debugs none
     */
    void clearBreakpoint(long number) throws SQLException;

    /**
     * Every breakpoint set, in the order set.
     *
     * @return the breakpoints
     * @throws SQLException SQLSTATE TBD01 if this connection debugs none
     */
    List<Breakpoint> breakpoints() throws SQLException;

    /**
     * The target's next event not yet taken, waited for at most {@code timeout}, without limit if
     * none: a pause with its call stack, a timeout, a cancel or its end; none if none came.
     *
     * @param timeout the longest it waits, or none for as long as it takes
     * @return the event, or none
     * @throws SQLException SQLSTATE 08006 if the connection is lost, or is of a stream the program
     *     gave and a timeout is given
     */
    @Nullable
    Event waitForEvent(@Nullable Duration timeout) throws SQLException;

    /**
     * The target's pause again, with its call stack; none if it runs.
     *
     * @return the pause, or none
     * @throws SQLException SQLSTATE TBD01 if this connection debugs none
     */
    @Nullable
    Pause stack() throws SQLException;

    /**
     * Lets the paused target go on: a step into, over or out, or to a breakpoint.
     *
     * @param step how
     * @throws SQLException SQLSTATE TBD01 of a target not paused
     */
    void resume(Step step) throws SQLException;

    /**
     * Pauses the running target at its next statement.
     *
     * @throws SQLException SQLSTATE TBD01 of a target not running
     */
    void interrupt() throws SQLException;
}
