package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;

/**
 * An interval's qualifier: its start and end fields, its leading precision and its fractional
 * seconds precision, as the INTERVAL type writes them, each a {@code u32} ([PROTOCOL Data types]).
 *
 * <p>Of a year-month interval the units are months; of a day-time one, microseconds ([PROTOCOL
 * Values]).
 *
 * @param start its start field
 * @param end its end field
 * @param leading its leading field precision, a {@code u32}
 * @param fraction its fractional seconds precision, a {@code u32}
 */
public record Qualifier(IntervalField start, IntervalField end, long leading, long fraction) {
    /**
     * A qualifier read.
     *
     * @param r where it is read
     * @return the qualifier
     * @throws TurborelException if a field is out of range or the body is cut short
     */
    static Qualifier read(Reader r) throws TurborelException {
        IntervalField start = IntervalField.read(r);
        IntervalField end = IntervalField.read(r);
        return new Qualifier(start, end, r.u32(), r.u32());
    }

    /**
     * Writes it.
     *
     * @param w where it is written
     * @throws TurborelException if a precision is past a {@code u32}
     */
    void encode(Writer w) throws TurborelException {
        w.u32(start.number()).u32(end.number()).u32(leading).u32(fraction);
    }

    /**
     * Whether its units are months, YEAR TO MONTH or either alone, and not microseconds.
     *
     * @return whether it is a year-month interval's
     */
    public boolean yearMonth() {
        return start.number() <= IntervalField.MONTH.number();
    }
}
