package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.util.List;

/**
 * The bytes a row takes in a Rows reply, a CopyData or an Execute's values, as Turborel's {@code
 * row_size} reckons them: the measure a copy sends its rows by.
 */
public final class RowSize {
    private static final int COUNT = 4;
    private static final int NULL = 1;
    private static final int COUNTED = 5;
    private static final int NUMBER = 21;
    private static final int APPROXIMATE = 9;
    private static final int TRUTH = 2;
    private static final int LOB_LOCATOR = 22;
    private static final int LOB_WHOLE = 6;
    private static final int DATETIME = 15;
    private static final int INTERVAL = 33;

    private static final int ONE_OCTET = 0x80;
    private static final int TWO_OCTETS = 0x800;

    private static final int UTF8_TWO = 2;
    private static final int UTF8_THREE = 3;
    private static final int UTF8_FOUR = 4;

    private RowSize() {}

    /**
     * The bytes of a row.
     *
     * @param row the row
     * @return its bytes, as Turborel reckons them
     * @throws TurborelException if a composite value cannot be written, as {@link Value#encode}
     */
    public static int of(List<Value> row) throws TurborelException {
        long size = COUNT;
        for (Value v : row) {
            size += value(v);
        }
        return (int) Math.min(size, Integer.MAX_VALUE);
    }

    private static long value(Value v) throws TurborelException {
        if (v instanceof Value.Null) {
            return NULL;
        } else if (v instanceof Value.Text t) {
            return utf8(t.text()) + COUNTED;
        } else if (v instanceof Value.Exact || v instanceof Value.Decfloat) {
            return NUMBER;
        } else if (v instanceof Value.Approximate) {
            return APPROXIMATE;
        } else if (v instanceof Value.Truth) {
            return TRUTH;
        } else if (v instanceof OctetValue.Binary b) {
            return (long) b.octets().length() + COUNTED;
        } else if (v instanceof Lob.Whole w) {
            return (long) w.octets().length() + LOB_WHOLE;
        } else if (v instanceof Lob.Locator) {
            return LOB_LOCATOR;
        } else if (v instanceof Value.Datetime) {
            return DATETIME;
        } else if (v instanceof Value.Interval) {
            return INTERVAL;
        } else if (v instanceof OctetValue.Reference r) {
            return (long) r.octets().length() + COUNTED;
        } else if (v instanceof OctetValue.Json j) {
            return (long) j.stored().length + COUNTED;
        }
        Writer w = new Writer();
        v.encode(w);
        return w.toByteArray().length;
    }

    /** The octets of a string's UTF-8. */
    private static long utf8(String s) {
        long octets = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < ONE_OCTET) {
                octets++;
            } else if (c < TWO_OCTETS) {
                octets += UTF8_TWO;
            } else if (Character.isHighSurrogate(c)) {
                octets += UTF8_FOUR;
                i++;
            } else {
                octets += UTF8_THREE;
            }
        }
        return octets;
    }
}
