package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.DataType;
import com.turborel.jdbc.internal.codec.Failures;
import com.turborel.jdbc.internal.codec.Value;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Where rows to import come from ([CLIENT Importing]): any format a program reads, {@link Delimited}
 * reading CSV and tab-separated text. {@link #headings} is asked once, before the first record;
 * then {@link #record} until it gives none, and none again if asked after. A source holds no more
 * than a record at a time, so memory is bounded, and refuses a record past a bound of its own. As
 * Turborel's {@code client/src/import.rs}.
 */
public interface Source {
    /**
     * Its fields' names and types, if it knows them, as of a header row.
     *
     * @return the headings, or none, its fields then taken as the table's columns in order
     * @throws Failed why it cannot be read, and where
     */
    @Nullable
    List<Heading> headings() throws Failed;

    /**
     * The next record.
     *
     * @return the record, or none at the end
     * @throws Failed why it cannot be read, and where: a source cannot go on after
     */
    @Nullable
    Entry record() throws Failed;

    /**
     * Where in a source a record began.
     *
     * @param record its number among the source's records of data, the first 1, a header not counted
     * @param line the line it began on, the first 1, of a source made of lines
     */
    record Position(long record, @Nullable Long line) {
        @Override
        public String toString() {
            return "record " + record + (line == null ? "" : ", line " + line);
        }
    }

    /**
     * What a source says of one of its fields: a name, which names a column, and a type if it knows
     * one.
     *
     * @param name the name, read as an identifier if it is one, a regular one in upper case, else
     *     as written
     * @param type the type of its values, if the source knows it
     */
    record Heading(String name, @Nullable DataType type) {}

    /**
     * A record of a source: a value for each of its fields, cast to its column's type as it is
     * inserted, so a source that knows no types gives character strings, and null.
     *
     * @param values the values, one a field
     * @param position where it began
     */
    record Entry(List<Value> values, Position position) {
        /**
         * A record of the fields given.
         *
         * @param values the values, copied
         * @param position where it began
         */
        public Entry {
            values = List.copyOf(values);
        }
    }

    /**
     * Why a source cannot give its next record: what is wrong, and where; input that cannot be read
     * (SQLCODE -301, SQLSTATE 22000), as the drivers' shared table has it.
     */
    final class Failed extends TurborelException {
        private static final long serialVersionUID = 1L;

        /** The SQLSTATE of input that cannot be read: data exception. */
        static final String DATA_EXCEPTION = "22000";

        /** Where the record began. */
        private final transient Position position;

        /**
         * A failure of the source at a record.
         *
         * @param position where the record began
         * @param message what is wrong, in plain words
         */
        public Failed(Position position, String message) {
            super(Failures.DATA_SQLCODE, DATA_EXCEPTION, message);
            this.position = position;
        }

        /**
         * Where the record began.
         *
         * @return the position
         */
        public Position where() {
            return position;
        }
    }
}
