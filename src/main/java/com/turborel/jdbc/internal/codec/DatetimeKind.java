package com.turborel.jdbc.internal.codec;

/**
 * The datetime types, each with its tag as a data type ([PROTOCOL Data types]). As a value, a
 * date is tag 8, a time of either kind tag 9 and a timestamp of either kind tag 11 ([PROTOCOL
 * Values]).
 */
public enum DatetimeKind {
    /** DATE, tag 8. */
    DATE(Tags.DATE),
    /** TIME, tag 9. */
    TIME(Tags.TIME),
    /** TIME WITH TIME ZONE, tag 10. */
    TIME_ZONED(Tags.TIME_ZONED),
    /** TIMESTAMP, tag 11. */
    TIMESTAMP(Tags.TIMESTAMP),
    /** TIMESTAMP WITH TIME ZONE, tag 12. */
    TIMESTAMP_ZONED(Tags.TIMESTAMP_ZONED);

    private final int tag;

    DatetimeKind(int tag) {
        this.tag = tag;
    }

    /**
     * Its tag as a data type.
     *
     * @return the tag, 8 to 12
     */
    public int tag() {
        return tag;
    }
}
