package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.util.List;

/**
 * What the client sends ([PROTOCOL Requests]): each request encodes itself as a frame, its kind
 * then its body, its tail written only where it says something, so a request whose tail is empty
 * is its first form exactly ([PROTOCOL The tail]). The large object's requests are {@link
 * LobRequest}'s.
 */
public sealed interface Request
        permits Request.Hello,
                Request.Answer,
                Request.Query,
                Request.Prepare,
                Request.Execute,
                Request.Fetch,
                Request.Close,
                Request.Deallocate,
                Request.Cancel,
                Request.Terminate,
                Request.Describe,
                Request.Ping,
                Request.Reset,
                Request.ChangeUser,
                Request.CopyIn,
                Request.CopyData,
                Request.CopyEnd,
                LobRequest {
    // [PROTOCOL Requests]: the kinds, read in the direction they travel.
    /** Hello. */
    byte HELLO = 'H';
    /** Answer. */
    byte ANSWER = 'A';
    /** Query. */
    byte QUERY = 'Q';
    /** Prepare. */
    byte PREPARE = 'P';
    /** Execute. */
    byte EXECUTE = 'E';
    /** Fetch. */
    byte FETCH = 'F';
    /** Close. */
    byte CLOSE = 'C';
    /** Deallocate. */
    byte DEALLOCATE = 'D';
    /** Lob. */
    byte LOB = 'L';
    /** Cancel. */
    byte CANCEL = 'Z';
    /** Terminate. */
    byte TERMINATE = 'X';
    /** Describe. */
    byte DESCRIBE = 'Y';
    /** Ping. */
    byte PING = 'G';
    /** Reset. */
    byte RESET = 'W';
    /** ChangeUser. */
    byte CHANGE_USER = 'U';
    /** CopyIn. */
    byte COPY_IN = 'I';
    /** CopyData. */
    byte COPY_DATA = 'J';
    /** CopyEnd. */
    byte COPY_END = 'N';

    /** The protocol's version, the first field of Hello: 1, the only version. */
    long VERSION = 1;

    /**
     * The request as a frame.
     *
     * @return its header, then its body
     * @throws TurborelException if a field is past what it holds (SQLCODE -301, SQLSTATE 22003),
     *     or a string is not Unicode (22021)
     */
    byte[] encode() throws TurborelException;

    /**
     * Hello: the first message of a connection that logs in.
     *
     * @param user the user, read as an identifier
     * @param mechanism the mechanism of authentication
     * @param initial the mechanism's first bytes
     * @param capabilities the capabilities asked for
     * @param attributes the connection's attributes, tag 1
     */
    record Hello(String user, String mechanism, Octets initial, List<String> capabilities, List<Attribute> attributes)
            implements Request {
        /**
         * A Hello of the fields given.
         *
         * @param user the user
         * @param mechanism the mechanism
         * @param initial its first bytes
         * @param capabilities the capabilities, copied
         * @param attributes the attributes, copied
         */
        public Hello {
            capabilities = List.copyOf(capabilities);
            attributes = List.copyOf(attributes);
        }

        @Override
        public byte[] encode() throws TurborelException {
            Writer w = new Writer().u32(VERSION).string(user).string(mechanism).bytes(initial);
            w.strings(capabilities);
            Attribute.write(w, attributes);
            return Frames.frame(HELLO, w.toByteArray());
        }
    }

    /**
     * Answer: the client's answer to a Challenge.
     *
     * @param answer its bytes
     */
    record Answer(Octets answer) implements Request {
        @Override
        public byte[] encode() throws TurborelException {
            return Frames.frame(ANSWER, new Writer().bytes(answer).toByteArray());
        }
    }

    /**
     * Query: a direct SQL statement.
     *
     * @param sql the statement, or several with {@code multi}
     * @param ends whether the transaction ends with the statement
     * @param attributes the request's attributes, tag 1, with {@code query-attributes}
     * @param multi tag 2, whether the text is several statements, with {@code multi-statement}
     */
    record Query(String sql, boolean ends, List<Attribute> attributes, boolean multi) implements Request {
        /**
         * A Query of the fields given.
         *
         * @param sql the statement
         * @param ends whether the transaction ends with it
         * @param attributes the attributes, copied
         * @param multi whether the text is several statements
         */
        public Query {
            attributes = List.copyOf(attributes);
        }

        @Override
        public byte[] encode() throws TurborelException {
            Writer w = new Writer().string(sql).flag(ends);
            Attribute.write(w, attributes);
            if (multi) {
                w.u8(Tail.MULTI);
            }
            return Frames.frame(QUERY, w.toByteArray());
        }
    }

    /**
     * Prepare: a statement under the client's number for it.
     *
     * @param statement the client's number for it, a {@code u32}
     * @param sql the statement
     */
    record Prepare(long statement, String sql) implements Request {
        @Override
        public byte[] encode() throws TurborelException {
            return Frames.frame(PREPARE, new Writer().u32(statement).string(sql).toByteArray());
        }
    }

    /**
     * Execute: a prepared statement run, a query's cursor opened.
     *
     * @param statement the statement's number, a {@code u32}
     * @param values a value for each parameter
     * @param rows the most rows sent now, 0 for all, a {@code u32}
     * @param ends whether the transaction ends with the statement
     * @param attributes the request's attributes, tag 1
     * @param cursor tag 3, the cursor's number, 0 unless the capability cursors names another, a {@code
     *     u32}
     */
    record Execute(long statement, List<Value> values, long rows, boolean ends, List<Attribute> attributes, long cursor)
            implements Request {
        /**
         * An Execute of the fields given.
         *
         * @param statement the statement's number
         * @param values the values, copied
         * @param rows the most rows sent now
         * @param ends whether the transaction ends with it
         * @param attributes the attributes, copied
         * @param cursor the cursor's number
         */
        public Execute {
            values = List.copyOf(values);
            attributes = List.copyOf(attributes);
        }

        @Override
        public byte[] encode() throws TurborelException {
            Writer w = new Writer().u32(statement);
            ValueCodec.writeRow(w, values);
            w.u32(rows).flag(ends);
            Attribute.write(w, attributes);
            Requests.cursor(w, cursor);
            return Frames.frame(EXECUTE, w.toByteArray());
        }
    }

    /**
     * Fetch: more rows of an open cursor.
     *
     * @param statement the statement's number, a {@code u32}
     * @param rows the most rows sent, 0 for all, a {@code u32}
     * @param cursor tag 3, the cursor's number, a {@code u32}
     */
    record Fetch(long statement, long rows, long cursor) implements Request {
        @Override
        public byte[] encode() throws TurborelException {
            Writer w = new Writer().u32(statement).u32(rows);
            Requests.cursor(w, cursor);
            return Frames.frame(FETCH, w.toByteArray());
        }
    }

    /**
     * Close: an open cursor closed.
     *
     * @param statement the statement's number, a {@code u32}
     * @param cursor tag 3, the cursor's number, a {@code u32}
     */
    record Close(long statement, long cursor) implements Request {
        @Override
        public byte[] encode() throws TurborelException {
            Writer w = new Writer().u32(statement);
            Requests.cursor(w, cursor);
            return Frames.frame(CLOSE, w.toByteArray());
        }
    }

    /**
     * Deallocate: a prepared statement destroyed.
     *
     * @param statement the statement's number, a {@code u32}
     */
    record Deallocate(long statement) implements Request {
        @Override
        public byte[] encode() throws TurborelException {
            return Frames.frame(DEALLOCATE, new Writer().u32(statement).toByteArray());
        }
    }

    /**
     * Cancel: the first and only message of a connection that cancels the statement another
     * connection runs.
     *
     * @param key the key that connection's Ready gave
     */
    record Cancel(CancelKey key) implements Request {
        @Override
        public byte[] encode() throws TurborelException {
            Writer w = new Writer();
            key.encode(w);
            return Frames.frame(CANCEL, w.toByteArray());
        }
    }

    /** Terminate: the session ends. */
    record Terminate() implements Request {
        @Override
        public byte[] encode() throws TurborelException {
            return Frames.frame(TERMINATE, new byte[0]);
        }
    }

    /**
     * Describe, with {@code describe}: a prepared statement, or an open cursor of one.
     *
     * @param cursorOf whether it describes a cursor, else a statement
     * @param statement the statement's number, a {@code u32}
     * @param cursor the cursor's number, 0 of a statement, a {@code u32}
     */
    record Describe(boolean cursorOf, long statement, long cursor) implements Request {
        @Override
        public byte[] encode() throws TurborelException {
            if (!cursorOf && cursor != 0) {
                throw Failures.misuse("[PROTOCOL Describe 'Y'] a statement's Describe names cursor 0");
            }
            Writer w = new Writer().u8(cursorOf ? 1 : 0).u32(statement).u32(cursor);
            return Frames.frame(DESCRIBE, w.toByteArray());
        }
    }

    /** Ping, with {@code ping}. */
    record Ping() implements Request {
        @Override
        public byte[] encode() throws TurborelException {
            return Frames.frame(PING, new byte[0]);
        }
    }

    /** Reset, with {@code reset}: the session put back as it was at login. */
    record Reset() implements Request {
        @Override
        public byte[] encode() throws TurborelException {
            return Frames.frame(RESET, new byte[0]);
        }
    }

    /**
     * ChangeUser, with {@code change-user}: the connection authenticated again.
     *
     * @param user the user
     * @param mechanism the mechanism of authentication
     * @param initial the mechanism's first bytes
     */
    record ChangeUser(String user, String mechanism, Octets initial) implements Request {
        @Override
        public byte[] encode() throws TurborelException {
            Writer w = new Writer().string(user).string(mechanism).bytes(initial);
            return Frames.frame(CHANGE_USER, w.toByteArray());
        }
    }

    /**
     * CopyIn, with {@code copy}: a copy of rows into a table begun.
     *
     * @param table the table, as SQL names it
     * @param columns its columns the rows give, none for every column in order
     * @param ends whether the transaction ends with the copy
     */
    record CopyIn(String table, List<String> columns, boolean ends) implements Request {
        /**
         * A CopyIn of the fields given.
         *
         * @param table the table
         * @param columns the columns, copied
         * @param ends whether the transaction ends with the copy
         */
        public CopyIn {
            columns = List.copyOf(columns);
        }

        @Override
        public byte[] encode() throws TurborelException {
            Writer w = new Writer().string(table).strings(columns).flag(ends);
            return Frames.frame(COPY_IN, w.toByteArray());
        }
    }

    /**
     * CopyData: rows of the copy, as many at a time as the client likes.
     *
     * @param rows the rows
     */
    record CopyData(List<List<Value>> rows) implements Request {
        /**
         * A CopyData of the rows given.
         *
         * @param rows the rows, copied
         */
        public CopyData {
            rows = List.copyOf(rows.stream().map(List::copyOf).toList());
        }

        @Override
        public byte[] encode() throws TurborelException {
            Writer w = new Writer().bits32(rows.size());
            for (List<Value> row : rows) {
                ValueCodec.writeRow(w, row);
            }
            return Frames.frame(COPY_DATA, w.toByteArray());
        }
    }

    /**
     * CopyEnd: the copy ended.
     *
     * @param abandon whether every row is undone
     */
    record CopyEnd(boolean abandon) implements Request {
        @Override
        public byte[] encode() throws TurborelException {
            return Frames.frame(COPY_END, new Writer().flag(abandon).toByteArray());
        }
    }
}
