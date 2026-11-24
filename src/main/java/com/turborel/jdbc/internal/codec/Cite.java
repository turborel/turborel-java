package com.turborel.jdbc.internal.codec;

/**
 * The headings of the protocol's page the codec cites in its failures' messages (RULES.md
 * R-ERR-2), each checked against {@code doc/client/05-protocol.md}.
 */
public final class Cite {
    /** The section "Frames". */
    public static final String FRAMES = "[PROTOCOL Frames]";

    /** The section "Compressed frames". */
    public static final String COMPRESSED = "[PROTOCOL Compressed frames]";

    /** The section "Values and fields". */
    public static final String FIELDS = "[PROTOCOL Values and fields]";

    /** The section "Data types". */
    public static final String TYPES = "[PROTOCOL Data types]";

    /** The section "Values". */
    public static final String VALUES = "[PROTOCOL Values]";

    /** The section "Composite fields". */
    public static final String COMPOSITE = "[PROTOCOL Composite fields]";

    /** The section "The tail". */
    public static final String TAIL = "[PROTOCOL The tail]";

    /** Parameter modes, tail 20 of a Prepared. */
    public static final String MODES = "[PROTOCOL Parameter modes]";

    /** The section "Replies". */
    public static final String REPLIES = "[PROTOCOL Replies]";

    private Cite() {}
}
