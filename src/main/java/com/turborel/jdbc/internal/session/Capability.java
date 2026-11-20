package com.turborel.jdbc.internal.session;

import java.util.List;

/**
 * The capabilities of version 1, by name, in the order of the protocol's table, which is the
 * order Ready grants them in ([PROTOCOL Capabilities]).
 */
public final class Capability {
    /** Notices before a statement's completion. */
    public static final String NOTICES = "notices";
    /** A Status of each session setting. */
    public static final String STATUS = "status";
    /** An Error's failure carries tags 7 to 15. */
    public static final String DIAGNOSTICS = "diagnostics";
    /** The request Describe. */
    public static final String DESCRIBE = "describe";
    /** Several cursors of one prepared statement. */
    public static final String CURSORS = "cursors";
    /** The request Ping. */
    public static final String PING = "ping";
    /** The request Reset. */
    public static final String RESET = "reset";
    /** The request ChangeUser. */
    public static final String CHANGE_USER = "change-user";
    /** Attributes on Query and Execute. */
    public static final String QUERY_ATTRIBUTES = "query-attributes";
    /** Says Hello carries attributes; every server reads them, granted or not. */
    public static final String CONNECTION_ATTRIBUTES = "connection-attributes";
    /** A Query of several statements. */
    public static final String MULTI_STATEMENT = "multi-statement";
    /** Compressed frames, both ways. */
    public static final String COMPRESSION = "compression";
    /** A Complete that committed carries the commit's log position. */
    public static final String COMMIT_POSITION = "commit-position";
    /** The requests CopyIn, CopyData and CopyEnd. */
    public static final String COPY = "copy";
    /** A Complete carries the rows counted and the server's time. */
    public static final String ROW_COUNTS = "row-counts";
    /** A Complete of an INSERT carries the identity values it generated. */
    public static final String GENERATED_VALUES = "generated-values";
    /** An Execute that ends the transaction ends it with its cursor. */
    public static final String FETCH_ENDS = "fetch-ends";
    /** A Complete carries its statement's conditions, in place of Notices. */
    public static final String CONDITIONS = "conditions";

    /** Prepared and Description carry each column's declared type, tail 19. */
    public static final String DECLARED_TYPES = "declared-types";

    /** Every capability of version 1, in the table's order. */
    public static final List<String> ALL = List.of(
            NOTICES,
            STATUS,
            DIAGNOSTICS,
            DESCRIBE,
            CURSORS,
            PING,
            RESET,
            CHANGE_USER,
            QUERY_ATTRIBUTES,
            CONNECTION_ATTRIBUTES,
            MULTI_STATEMENT,
            COMPRESSION,
            COMMIT_POSITION,
            COPY,
            ROW_COUNTS,
            GENERATED_VALUES,
            FETCH_ENDS,
            CONDITIONS,
            DECLARED_TYPES);

    private Capability() {}
}
