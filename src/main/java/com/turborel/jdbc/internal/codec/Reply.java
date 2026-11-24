package com.turborel.jdbc.internal.codec;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What the server sends ([PROTOCOL Replies]), as {@link ReplyCodec} reads it. A reply's values
 * are what the server wrote; nothing is converted to a Java type until the API asks.
 */
public sealed interface Reply {
    // [PROTOCOL Replies]: the kinds, read in the direction they travel.
    /** Challenge. */
    byte CHALLENGE = 'C';
    /** Ready. */
    byte READY = 'R';
    /** Prepared. */
    byte PREPARED = 'P';
    /** Description. */
    byte DESCRIPTION = 'D';
    /** Rows. */
    byte ROWS = 'B';
    /** Suspended. */
    byte SUSPENDED = 'S';
    /** Complete. */
    byte COMPLETE = 'K';
    /** Error. */
    byte ERROR = 'E';
    /** Lob. */
    byte LOB = 'L';
    /** Notice. */
    byte NOTICE = 'N';
    /** Status. */
    byte STATUS = 'T';
    /** Pong. */
    byte PONG = 'G';

    /**
     * Challenge: the next step of an authentication exchange.
     *
     * @param challenge its bytes
     */
    record Challenge(Octets challenge) implements Reply {}

    /**
     * Ready: the client is authenticated.
     *
     * @param authorization the session's authorization identifier
     * @param key the key that cancels the session's statements, the client's secret
     * @param capabilities the capabilities granted, in the order of the page's table
     * @param server the server's product, as {@code turborel 0.0.0 (alpha)}
     */
    record Ready(String authorization, CancelKey key, List<String> capabilities, String server) implements Reply {
        /**
         * A Ready of the fields given.
         *
         * @param authorization the authorization identifier
         * @param key the cancel key
         * @param capabilities the capabilities granted, copied
         * @param server the server's product
         */
        public Ready {
            capabilities = List.copyOf(capabilities);
        }
    }

    /**
     * Prepared: a statement prepared, or described.
     *
     * @param statement its number, a {@code u32}
     * @param parameters its input parameters, in the order of their {@code ?} marks
     * @param columns its columns, a query's, or a CALL's output parameters'; none of another
     * @param called of a CALL, its procedure and each {@code ?}'s mode, granted {@code
     *     parameter-modes}; none of another statement
     */
    record Prepared(
            long statement,
            List<Parameter> parameters,
            List<Column> columns,
            @Nullable Called called) implements Reply {
        /**
         * A Prepared of the fields given.
         *
         * @param statement its number
         * @param parameters its parameters, copied
         * @param columns its columns, copied
         * @param called of a CALL, what it tells, or none
         */
        public Prepared {
            parameters = List.copyOf(parameters);
            columns = List.copyOf(columns);
        }

        /**
         * A Prepared of no CALL told.
         *
         * @param statement its number
         * @param parameters its parameters
         * @param columns its columns
         */
        public Prepared(long statement, List<Parameter> parameters, List<Column> columns) {
            this(statement, parameters, columns, null);
        }
    }

    /**
     * Description: the columns of the rows that follow a Query, or of an open cursor.
     *
     * @param columns the columns
     */
    record Description(List<Column> columns) implements Reply {
        /**
         * A Description of the columns given.
         *
         * @param columns the columns, copied
         */
        public Description {
            columns = List.copyOf(columns);
        }
    }

    /**
     * Rows: a batch of rows.
     *
     * @param rows the rows, each a list of values
     */
    record Rows(List<List<Value>> rows) implements Reply {
        /**
         * A batch of the rows given.
         *
         * @param rows the rows, copied
         */
        public Rows {
            rows = List.copyOf(rows.stream().map(List::copyOf).toList());
        }
    }

    /** Suspended: the rows asked for were sent, and the cursor has more. */
    record Suspended() implements Reply {}

    /**
     * Complete: a statement's completion.
     *
     * @param completed what it says
     */
    record Complete(Completed completed) implements Reply {}

    /**
     * Error: a statement's failure, or the connection's.
     *
     * @param failure the failure
     */
    // Named Error as the protocol names it (R-STYLE-2); it is always written Reply.Error.
    @SuppressWarnings("AvoidCommonTypeNames")
    record Error(Failure failure) implements Reply {}

    /**
     * Lob, the reply: the data a Lob read asked for ([PROTOCOL Lob 'L'], under Replies).
     *
     * @param data the octets read
     */
    record LobData(Octets data) implements Reply {}

    /**
     * Lob, the reply: the locator a Lob create made ([PROTOCOL Lob 'L'], under Replies).
     *
     * @param locator the locator, a {@code u32}
     */
    record LobLocator(long locator) implements Reply {}

    /**
     * Notice, with {@code notices}: a condition a statement raised, or a notice of the server's own.
     *
     * @param sqlstate its SQLSTATE
     * @param message what it says
     * @param diagnostics what else it says, tags 7 to 15, written only with {@code diagnostics}
     */
    record Notice(String sqlstate, String message, Diagnostics diagnostics) implements Reply {}

    /**
     * Status, with {@code status}: a setting of the session.
     *
     * @param name its name, as AUTHORIZATION or SESSION.TIME_ZONE
     * @param value its value, as SQL writes it; empty for a null
     */
    record Status(String name, String value) implements Reply {}

    /** Pong, with {@code ping}: the answer to Ping. */
    record Pong() implements Reply {}
}
