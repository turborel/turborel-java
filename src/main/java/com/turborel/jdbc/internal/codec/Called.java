package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.util.List;

/**
 * What a Prepared of a CALL of an SQL-invoked procedure tells, granted {@code parameter-modes}
 * ([PROTOCOL Parameter modes]): the procedure's specific name, and each of the statement's {@code ?}
 * marks in the order written, its OUT ones too.
 *
 * @param procedure the procedure's specific name, its schema and name
 * @param marks each {@code ?}, in the order written
 */
public record Called(TypeName procedure, List<Mark> marks) {
    /**
     * Called, its marks copied.
     *
     * @param procedure the procedure's specific name
     * @param marks each {@code ?}
     */
    public Called {
        marks = List.copyOf(marks);
    }

    /**
     * A {@code ?} of a CALL: the mode and ordinal position of the procedure's parameter whose
     * argument holds it, and its type.
     *
     * @param mode the parameter's mode
     * @param ordinal the parameter's ordinal position, from 1
     * @param parameter its type, as a Prepared's head's parameter is
     */
    public record Mark(ParameterMode mode, long ordinal, Parameter parameter) {}

    /**
     * Tag 20's body read: the specific name, then a count and each {@code ?}'s mode {@code u8},
     * ordinal {@code u32} and parameter.
     *
     * @param r where it is read
     * @return what it tells
     * @throws TurborelException a connection failure if it is malformed: a mode of another code, or
     *     an ordinal position of 0
     */
    static Called read(Reader r) throws TurborelException {
        TypeName procedure = new TypeName(r.string(), r.string());
        return new Called(procedure, r.list(Called::mark));
    }

    private static Mark mark(Reader r) throws TurborelException {
        int code = r.u8();
        ParameterMode mode = ParameterMode.of(code);
        if (mode == null) {
            throw r.malformed(Cite.MODES, code + " is no parameter mode's code");
        }
        long ordinal = r.u32();
        if (ordinal == 0) {
            throw r.malformed(Cite.MODES, "a parameter's ordinal position is from 1");
        }
        return new Mark(mode, ordinal, Parameter.read(r));
    }
}
