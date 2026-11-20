package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.util.Arrays;

/**
 * A message's tail, the fields version 1 gained after a message's first form: a tag and its
 * field, then the next, to the end of the body ([PROTOCOL The tail]). A field is written only
 * where its capability was granted and it says something, so a message with an empty tail is its
 * first form exactly. Read in order, each tag must be one the message takes and past the last;
 * else the connection fails.
 */
public final class Tail {
    // [PROTOCOL The tail]: the tags.
    /** Attributes, on Hello, Query and Execute. */
    public static final int ATTRIBUTES = 1;
    /** Multi, on Query. */
    public static final int MULTI = 2;
    /** Cursor, on Execute, Fetch and Close. */
    public static final int CURSOR = 3;
    /** Position, on Complete. */
    public static final int POSITION = 4;
    /** More, on Complete. */
    public static final int MORE = 5;
    /** Rows, on Complete. */
    public static final int ROWS = 6;
    /** Generated, on Complete. */
    public static final int GENERATED = 16;
    /** Conditions, on Complete. */
    public static final int CONDITIONS = 17;
    /** Time, on Complete. */
    public static final int TIME = 18;

    /** What {@link #next} gives at the end of the body. */
    public static final int END = -1;

    /** The tags of a failure's or a notice's diagnostics, 7 to 15. */
    static final int[] DIAGNOSTICS = Arrays.stream(DiagnosticField.values())
            .mapToInt(DiagnosticField::tag)
            .toArray();

    /** The tags of a Complete. */
    static final int[] COMPLETE = {POSITION, MORE, ROWS, GENERATED, CONDITIONS, TIME};

    private final int[] taken;
    private int last;

    /**
     * The start of a tail of the tags a message takes.
     *
     * @param taken its tags, in increasing order
     */
    Tail(int[] taken) {
        this.taken = taken.clone();
    }

    /**
     * The next tag, or {@link #END} at the end of the body.
     *
     * @param r where the tail is read
     * @return the tag
     * @throws TurborelException if the tag is one the message does not take, or not past the last
     */
    int next(Reader r) throws TurborelException {
        if (r.atEnd()) {
            return END;
        }
        int tag = r.u8();
        if (tag <= last || Arrays.binarySearch(taken, tag) < 0) {
            throw r.malformed(Cite.TAIL, "its tail has tag " + tag + " out of place");
        }
        last = tag;
        return tag;
    }
}
