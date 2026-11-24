package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The routine debugger's fields ([PROTOCOL Debug 'V'], [PROTOCOL Debugged 'V'], [PROTOCOL Event
 * 'W']) *(extension)*: what Debugged answers, the Events a target's session sends unasked, and the
 * breakpoints, pauses and frames they carry.
 */
public final class Debugging {
    private Debugging() {}

    /** The kind of a routine a breakpoint is set in, or a frame runs. */
    public enum Kind {
        /** A procedure, 0. */
        PROCEDURE(0),
        /** A function, 1. */
        FUNCTION(1),
        /** A trigger, 2. */
        TRIGGER(2);

        private final int code;

        Kind(int code) {
            this.code = code;
        }

        /**
         * Its code on the wire.
         *
         * @return the code
         */
        public int code() {
            return code;
        }

        static Kind read(Reader r) throws TurborelException {
            int code = r.u8();
            for (Kind k : values()) {
                if (k.code == code) {
                    return k;
                }
            }
            throw r.malformed(Cite.DEBUG, code + " is no kind of routine");
        }
    }

    /** How a paused target is told to go on. */
    public enum Action {
        /** Into: to its next statement, in whatever frame, 0. */
        INTO(0),
        /** Over: to the next of this frame or one it returns to, 1. */
        OVER(1),
        /** Out: to the next of the frame this one returns to, 2. */
        OUT(2),
        /** Continue: to a breakpoint, 3. */
        CONTINUE(3);

        private final int code;

        Action(int code) {
            this.code = code;
        }

        /**
         * Its code on the wire.
         *
         * @return the code
         */
        public int code() {
            return code;
        }
    }

    /**
     * A breakpoint set.
     *
     * @param number its number
     * @param routine its routine's specific name
     * @param line its line, from 1
     * @param condition the condition it pauses on, or none
     */
    public record Breakpoint(
            long number,
            TypeName routine,
            long line,
            @Nullable String condition) {
        static Breakpoint read(Reader r) throws TurborelException {
            return new Breakpoint(r.u32(), TypeName.read(r), r.u32(), given(r));
        }
    }

    /**
     * Where a frame's routine comes from.
     *
     * @param kind the routine's kind
     * @param name its specific name
     * @param shown its name
     */
    public record Origin(Kind kind, TypeName name, TypeName shown) {}

    /**
     * A parameter or variable of a frame.
     *
     * @param name its name
     * @param dataType its data type, as SQL writes it
     * @param value its value
     */
    public record Named(String name, String dataType, Value value) {
        static Named read(Reader r) throws TurborelException {
            return new Named(r.string(), r.string(), ValueCodec.read(r));
        }
    }

    /**
     * A cursor of a frame.
     *
     * @param name its name
     * @param open whether it is open
     * @param query its query
     */
    public record Cursor(String name, boolean open, String query) {}

    /**
     * A frame of a paused call stack: a run of a routine's or a trigger's body.
     *
     * @param origin its routine, none of a statement run directly
     * @param visible whether the debugger may see into it; one not shows its origin alone
     * @param at its place, in characters from the beginning of the routine's text, if given
     * @param line its line from 1, if given
     * @param column its column from 1, given with its line
     * @param parameters its parameters, a trigger's its transition variables
     * @param variables its variables
     * @param cursors its cursors
     */
    public record Frame(
            @Nullable Origin origin,
            boolean visible,
            @Nullable Long at,
            @Nullable Long line,
            @Nullable Long column,
            List<Named> parameters,
            List<Named> variables,
            List<Cursor> cursors) {
        /**
         * A frame, its lists copied.
         *
         * @param origin what it is the body of; none of a statement run directly
         * @param visible whether the debugger may see into it
         * @param at its place, in characters from the beginning of its routine's text
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

        static Frame read(Reader r) throws TurborelException {
            Origin origin = r.flag() ? new Origin(Kind.read(r), TypeName.read(r), TypeName.read(r)) : null;
            boolean visible = r.flag();
            Long at = r.flag() ? r.u64() : null;
            Long line = null;
            Long column = null;
            if (r.flag()) {
                line = r.u32();
                column = r.u32();
            }
            List<Named> parameters = r.list(Named::read);
            List<Named> variables = r.list(Named::read);
            List<Cursor> cursors = r.list(c -> new Cursor(c.string(), c.flag(), c.string()));
            return new Frame(origin, visible, at, line, column, parameters, variables, cursors);
        }
    }

    /**
     * A target paused, and its call stack.
     *
     * @param breakpoint the breakpoint it paused at, or none of the end of a step
     * @param frames its frames, the outermost first
     */
    public record Paused(@Nullable Long breakpoint, List<Frame> frames) {
        /**
         * A pause, its frames copied.
         *
         * @param breakpoint the number of the breakpoint it is at; none of a step
         * @param frames its call stack, the outermost first
         */
        public Paused {
            frames = List.copyOf(frames);
        }

        static Paused read(Reader r) throws TurborelException {
            int reason = r.u8();
            Long breakpoint = switch (reason) {
                case AT_BREAKPOINT -> r.u32();
                case STEPPED -> null;
                default -> throw r.malformed(Cite.DEBUG, "no pause is for reason " + reason);
            };
            return new Paused(breakpoint, r.list(Frame::read));
        }
    }

    /** What a debugger hears of its target, sent unasked, an Event's body. */
    public sealed interface Event {
        /**
         * The target paused.
         *
         * @param paused the pause
         */
        record Pause(Paused paused) implements Event {}

        /** The target, paused longer than DEBUGGER.PAUSE_TIMEOUT_MS, went on by itself. */
        record TimedOut() implements Event {}

        /** The target's statement was cancelled while it was paused. */
        record Cancelled() implements Event {}

        /** The target's session ended, and the debugger is detached. */
        record Ended() implements Event {}
    }

    /** What a Debug request gave back, but a completion, a Debugged reply's body. */
    public sealed interface Answer {
        /**
         * A connection's ID, of identify.
         *
         * @param id the ID
         */
        record Connection(long id) implements Answer {}

        /**
         * The breakpoint set, of break.
         *
         * @param breakpoint the breakpoint
         */
        record Set(Breakpoint breakpoint) implements Answer {}

        /**
         * Every breakpoint set, in the order set, of breakpoints.
         *
         * @param breakpoints the breakpoints
         */
        record Listed(List<Breakpoint> breakpoints) implements Answer {
            /**
             * A list, copied.
             *
             * @param breakpoints the breakpoints
             */
            public Listed {
                breakpoints = List.copyOf(breakpoints);
            }
        }

        /**
         * The target's pause, of stack: none if it runs.
         *
         * @param paused the pause, or none
         */
        record Stack(@Nullable Paused paused) implements Answer {}
    }

    /** A pause's reason: at a breakpoint, or at the end of a step. */
    private static final int AT_BREAKPOINT = 0;

    private static final int STEPPED = 1;

    /** A Debugged reply's answers. */
    private static final int CONNECTION = 0;

    private static final int BREAKPOINT = 1;

    private static final int LISTED = 2;

    private static final int STACKED = 3;

    /** An Event's kinds. */
    private static final int PAUSED = 0;

    private static final int TIMED_OUT = 1;

    private static final int CANCELLED = 2;

    private static final int ENDED = 3;

    /** A string given if its flag says so. */
    static @Nullable String given(Reader r) throws TurborelException {
        return r.flag() ? r.string() : null;
    }

    /**
     * A Debugged reply's body read.
     *
     * @param r where it is read
     * @return the answer
     * @throws TurborelException a connection failure if it is malformed
     */
    static Answer answer(Reader r) throws TurborelException {
        int what = r.u8();
        return switch (what) {
            case CONNECTION -> new Answer.Connection(r.u64());
            case BREAKPOINT -> new Answer.Set(Breakpoint.read(r));
            case LISTED -> new Answer.Listed(r.list(Breakpoint::read));
            case STACKED -> new Answer.Stack(r.flag() ? Paused.read(r) : null);
            default -> throw r.malformed(Cite.DEBUG, "no Debugged reply is of kind " + what);
        };
    }

    /**
     * An Event's body read.
     *
     * @param r where it is read
     * @return the event
     * @throws TurborelException a connection failure if it is malformed
     */
    static Event event(Reader r) throws TurborelException {
        int what = r.u8();
        return switch (what) {
            case PAUSED -> new Event.Pause(Paused.read(r));
            case TIMED_OUT -> new Event.TimedOut();
            case CANCELLED -> new Event.Cancelled();
            case ENDED -> new Event.Ended();
            default -> throw r.malformed(Cite.DEBUG, "no Event is of kind " + what);
        };
    }
}
