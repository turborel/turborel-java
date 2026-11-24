package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.internal.codec.Called;
import com.turborel.jdbc.internal.codec.Column;
import com.turborel.jdbc.internal.codec.Parameter;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A statement the server prepared ([CLIENT Prepared statements], [PROTOCOL Prepared 'P']).
 *
 * @param number the number the client gave it, a {@code u32}
 * @param parameters the types its dynamic parameters take, in the order of their {@code ?} marks:
 *     its inputs alone
 * @param columns the columns of its rows, of a query; of a CALL, one for each {@code ?} of an OUT or
 *     INOUT parameter, whose values executing it gives as a row; none of another
 * @param called of a CALL of an SQL-invoked procedure, granted {@code parameter-modes}, the
 *     procedure and each {@code ?}'s mode and ordinal position, its OUT ones too ([PROTOCOL
 *     Parameter modes]); none of another statement, whose parameters are all IN
 */
public record Statement(
        long number,
        List<Parameter> parameters,
        List<Column> columns,
        @Nullable Called called) {
    /**
     * A statement of the fields given.
     *
     * @param number its number
     * @param parameters its parameters' types, copied
     * @param columns its columns, copied
     * @param called of a CALL, what the server told of it, or none
     */
    public Statement {
        parameters = List.copyOf(parameters);
        columns = List.copyOf(columns);
    }

    /**
     * A statement of no CALL told.
     *
     * @param number its number
     * @param parameters its parameters' types
     * @param columns its columns
     */
    public Statement(long number, List<Parameter> parameters, List<Column> columns) {
        this(number, parameters, columns, null);
    }
}
