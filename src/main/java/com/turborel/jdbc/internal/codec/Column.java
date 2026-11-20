package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A column of a result ([PROTOCOL Composite fields]): its name, if it has one, its data type,
 * whether it is NOT NULL, and the type it was declared with, if the server told it ([PROTOCOL
 * Declared types]).
 *
 * @param name its name, if named
 * @param type its data type
 * @param notNull whether it is constrained NOT NULL
 * @param declared its declared type, if told
 */
public record Column(
        @Nullable String name,
        DataType type,
        boolean notNull,
        @Nullable Declared declared) {
    /**
     * A column of no declared type told.
     *
     * @param name its name, if named
     * @param type its data type
     * @param notNull whether it is constrained NOT NULL
     */
    public Column(@Nullable String name, DataType type, boolean notNull) {
        this(name, type, notNull, null);
    }

    /**
     * Its type as SQL writes it: as it was declared if told, else as its tag and parameters say, so
     * INTEGER is NUMERIC(10,0) ([CLIENT Prepared statements]).
     *
     * @return its type's text
     */
    public String typeWritten() {
        Declared told = declared;
        return told == null ? TypeText.written(type) : told.written();
    }

    /**
     * This column, its declared type the one given.
     *
     * @param told the declared type
     * @return the column
     */
    public Column declaredAs(Declared told) {
        return new Column(name, type, notNull, told);
    }

    /**
     * A column read.
     *
     * @param r where it is read
     * @return the column
     * @throws TurborelException if it is malformed
     */
    static Column read(Reader r) throws TurborelException {
        String name = r.flag() ? r.string() : null;
        DataType type = TypeCodec.read(r);
        return new Column(name, type, r.flag());
    }

    /**
     * Columns read, then the tail of their declared types: tag 19, a count of one for each column,
     * then each one's name and text ([PROTOCOL Declared types]).
     *
     * @param r where they are read
     * @return the columns, each its declared type if told
     * @throws TurborelException if they are malformed, or the declared types are not one for each
     */
    static List<Column> readAll(Reader r) throws TurborelException {
        List<Column> columns = r.list(Column::read);
        Tail tail = new Tail(Tail.COLUMNS);
        for (int tag = tail.next(r); tag != Tail.END; tag = tail.next(r)) {
            List<Declared> declared = r.list(d -> new Declared(d.string(), d.string()));
            if (declared.size() != columns.size()) {
                throw r.malformed(Cite.TAIL, "its declared types are not one for each column");
            }
            List<Column> told = new ArrayList<>(columns.size());
            for (int i = 0; i < columns.size(); i++) {
                told.add(columns.get(i).declaredAs(declared.get(i)));
            }
            columns = told;
        }
        return columns;
    }
}
