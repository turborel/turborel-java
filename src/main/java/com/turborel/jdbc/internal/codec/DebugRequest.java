package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import org.jspecify.annotations.Nullable;

/**
 * Debug, the request: what a debugger's connection asks of the session it debugs ([PROTOCOL Debug
 * 'V']) *(extension)*, an operation's byte, then its fields.
 */
public sealed interface DebugRequest extends Request {
    // [PROTOCOL Debug 'V']: the operations.
    /** Identify. */
    int IDENTIFY = 0;
    /** Attach. */
    int ATTACH = 1;
    /** Detach. */
    int DETACH = 2;
    /** Break. */
    int BREAK = 3;
    /** Clear. */
    int CLEAR = 4;
    /** Breakpoints. */
    int BREAKPOINTS = 5;
    /** Resume. */
    int RESUME = 6;
    /** Interrupt. */
    int INTERRUPT = 7;
    /** Stack. */
    int STACK = 8;

    /** Identify: this connection's ID, answered by Debugged. */
    record Identify() implements DebugRequest {
        @Override
        public byte[] encode() throws TurborelException {
            return Frames.frame(DEBUG, new Writer().u8(IDENTIFY).toByteArray());
        }
    }

    /**
     * Attach: this connection made the debugger of the session of the connection of that ID.
     *
     * @param connection the ID, a {@code u64}'s bits
     */
    record Attach(long connection) implements DebugRequest {
        @Override
        public byte[] encode() throws TurborelException {
            return Frames.frame(DEBUG, new Writer().u8(ATTACH).u64(connection).toByteArray());
        }
    }

    /** Detach: the debugging ended. */
    record Detach() implements DebugRequest {
        @Override
        public byte[] encode() throws TurborelException {
            return Frames.frame(DEBUG, new Writer().u8(DETACH).toByteArray());
        }
    }

    /**
     * Break: a breakpoint at a line of a routine's or a trigger's text, answered by Debugged.
     *
     * @param kind the routine's kind
     * @param routine its specific name, or its name, an empty schema the session's default
     * @param line the line, from 1, a {@code u32}
     * @param condition the condition it pauses on, or none
     */
    record Break(
            Debugging.Kind kind,
            TypeName routine,
            long line,
            @Nullable String condition) implements DebugRequest {
        @Override
        public byte[] encode() throws TurborelException {
            Writer w = new Writer()
                    .u8(BREAK)
                    .u8(kind.code())
                    .string(routine.schema())
                    .string(routine.name());
            w.u32(line).flag(condition != null);
            if (condition != null) {
                w.string(condition);
            }
            return Frames.frame(DEBUG, w.toByteArray());
        }
    }

    /**
     * Clear: the breakpoint of that number cleared.
     *
     * @param number the number, a {@code u32}
     */
    record Clear(long number) implements DebugRequest {
        @Override
        public byte[] encode() throws TurborelException {
            return Frames.frame(DEBUG, new Writer().u8(CLEAR).u32(number).toByteArray());
        }
    }

    /** Breakpoints: every breakpoint set, answered by Debugged. */
    record Breakpoints() implements DebugRequest {
        @Override
        public byte[] encode() throws TurborelException {
            return Frames.frame(DEBUG, new Writer().u8(BREAKPOINTS).toByteArray());
        }
    }

    /**
     * Resume: the paused target goes on as the action says.
     *
     * @param action how
     */
    record Resume(Debugging.Action action) implements DebugRequest {
        @Override
        public byte[] encode() throws TurborelException {
            return Frames.frame(DEBUG, new Writer().u8(RESUME).u8(action.code()).toByteArray());
        }
    }

    /** Interrupt: the running target pauses at its next statement. */
    record Interrupt() implements DebugRequest {
        @Override
        public byte[] encode() throws TurborelException {
            return Frames.frame(DEBUG, new Writer().u8(INTERRUPT).toByteArray());
        }
    }

    /** Stack: the target's pause, answered by Debugged. */
    record Stack() implements DebugRequest {
        @Override
        public byte[] encode() throws TurborelException {
            return Frames.frame(DEBUG, new Writer().u8(STACK).toByteArray());
        }
    }
}
