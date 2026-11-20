package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A statement's completion, Complete ([PROTOCOL Complete 'K']): SQLCODE 0 or 100, its SQLSTATE,
 * whether a transaction is active after it, and its tail.
 *
 * @param sqlcode its SQLCODE, 0 or 100
 * @param sqlstate its SQLSTATE
 * @param active whether a transaction is active after it
 * @param fields what its tail says
 */
public record Completed(int sqlcode, String sqlstate, boolean active, Fields fields) {
    /**
     * A Complete's tail: tags 4, 5, 6, 16, 17 and 18, each where the server wrote it.
     *
     * @param position tag 4, the log position of the commit the statement made, a {@code u64}'s
     *     bits
     * @param more tag 5, whether another statement's replies follow
     * @param rows tag 6, the rows the statement counted, a {@code u64}'s bits
     * @param generated tag 16, each identity value an INSERT generated, as SQL writes it
     * @param conditions tag 17, each condition the statement raised
     * @param time tag 18, the server's time for the statement
     */
    public record Fields(
            @Nullable Long position,
            boolean more,
            @Nullable Long rows,
            List<String> generated,
            List<Failure> conditions,
            @Nullable Timing time) {
        /** A tail with nothing in it: the message's first form. */
        public static final Fields EMPTY = new Fields(null, false, null, List.of(), List.of(), null);

        /**
         * A tail of the fields given.
         *
         * @param position tag 4
         * @param more tag 5
         * @param rows tag 6
         * @param generated tag 16, copied
         * @param conditions tag 17, copied
         * @param time tag 18
         */
        public Fields {
            generated = List.copyOf(generated);
            conditions = List.copyOf(conditions);
        }
    }

    /**
     * A Complete's body read.
     *
     * @param r where it is read
     * @return the completion
     * @throws TurborelException if it is malformed
     */
    static Completed read(Reader r) throws TurborelException {
        int sqlcode = r.bits32();
        String sqlstate = r.string();
        boolean active = r.flag();
        Tail tags = new Tail(Tail.COMPLETE);
        Long position = null;
        boolean more = false;
        Long rows = null;
        List<String> generated = List.of();
        List<Failure> conditions = List.of();
        Timing time = null;
        for (int tag = tags.next(r); tag != Tail.END; tag = tags.next(r)) {
            switch (tag) {
                case Tail.POSITION -> position = r.u64();
                case Tail.MORE -> more = true;
                case Tail.ROWS -> rows = r.u64();
                case Tail.GENERATED -> generated = r.strings();
                case Tail.CONDITIONS -> conditions = conditions(r);
                default -> time = new Timing(r.u64(), r.u64(), r.u64());
            }
        }
        return new Completed(sqlcode, sqlstate, active, new Fields(position, more, rows, generated, conditions, time));
    }

    /** Tag 17's conditions: each a failure as bytes, its own tail ending where its length does. */
    private static List<Failure> conditions(Reader r) throws TurborelException {
        long count = r.u32();
        List<Failure> conditions = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            Reader inner = new Reader(r.bytes(), "condition");
            conditions.add(Failure.read(inner));
            inner.finish();
        }
        return conditions;
    }
}
