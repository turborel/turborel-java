package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.internal.codec.Column;
import com.turborel.jdbc.internal.codec.Parameter;
import java.util.List;

/**
 * A statement the server prepared ([CLIENT Prepared statements], [PROTOCOL Prepared 'P']).
 *
 * @param number the number the client gave it, a {@code u32}
 * @param parameters the types its dynamic parameters take, in the order of their {@code ?} marks:
 *     its inputs alone
 * @param columns the columns of its rows, of a query; of a CALL, one for each {@code ?} of an OUT or
 *     INOUT parameter, whose values executing it gives as a row; none of another
 */
public record Statement(long number, List<Parameter> parameters, List<Column> columns) {
    /**
     * A statement of the fields given.
     *
     * @param number its number
     * @param parameters its parameters' types, copied
     * @param columns its columns, copied
     */
    public Statement {
        parameters = List.copyOf(parameters);
        columns = List.copyOf(columns);
    }
}
