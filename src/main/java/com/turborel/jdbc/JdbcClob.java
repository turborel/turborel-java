package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.Lob;
import com.turborel.jdbc.internal.session.Lobs;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;

/**
 * A character large object ([DESIGN] D2): of a row, read through its locator, a range of characters
 * at a time, while the locator lives; or one the program made, held in memory and sent whole. Its
 * characters are counted as Turborel counts them, by code point.
 */
final class JdbcClob implements NClob {
    /** What {@code position} gives of a text not found. */
    private static final long NOT_FOUND = -1;

    /** A large object of a row: its locator, and what reads it. */
    private record Remote(Lob.Locator locator, Lobs lobs) {}

    private final @Nullable Remote remote;
    private final StringBuilder held;
    private boolean freed;

    /** A large object of a row, through its locator. */
    JdbcClob(Lob.Locator locator, Lobs lobs) {
        this.remote = new Remote(locator, lobs);
        this.held = new StringBuilder();
    }

    /** A large object held in memory, of {@code text}. */
    JdbcClob(String text) {
        this.remote = null;
        this.held = new StringBuilder(text);
    }

    private void live() throws SQLException {
        if (freed) {
            throw Errors.misuse("the Clob is freed");
        }
    }

    /** The value it is sent as: its locator, or its text whole. */
    Lob value() throws SQLException {
        Remote at = remote;
        return at != null ? at.locator() : Lob.Whole.ofText(held.toString());
    }

    @Override
    public long length() throws SQLException {
        live();
        Remote at = remote;
        return at != null ? at.locator().characters() : held.codePointCount(0, held.length());
    }

    @Override
    public String getSubString(long pos, int length) throws SQLException {
        live();
        if (pos < 1 || length < 0) {
            throw Errors.misuse("a Clob is read from character 1, a length of 0 or more");
        }
        Remote at = remote;
        if (at == null) {
            int count = held.codePointCount(0, held.length());
            int from = (int) Math.min(pos - 1, count);
            int to = (int) Math.min(count, from + (long) length);
            return held.substring(held.offsetByCodePoints(0, from), held.offsetByCodePoints(0, to));
        }
        StringBuilder read = new StringBuilder();
        long got = 0;
        while (got < length) {
            String part =
                    new String(at.lobs().read(at.locator(), pos - 1 + got, length - got, true), StandardCharsets.UTF_8);
            if (part.isEmpty()) {
                break;
            }
            read.append(part);
            got += part.codePointCount(0, part.length());
        }
        return read.toString();
    }

    private InputStream octets() throws SQLException {
        live();
        Remote at = remote;
        return at != null
                ? at.lobs().reader(at.locator())
                : new ByteArrayInputStream(held.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Reader getCharacterStream() throws SQLException {
        return new InputStreamReader(octets(), StandardCharsets.UTF_8);
    }

    @Override
    public Reader getCharacterStream(long pos, long length) throws SQLException {
        return new StringReader(getSubString(pos, Math.toIntExact(length)));
    }

    @Override
    public InputStream getAsciiStream() throws SQLException {
        return octets();
    }

    @Override
    public long position(String searchstr, long start) throws SQLException {
        String all = getSubString(1, Math.toIntExact(length()));
        int from =
                all.offsetByCodePoints(0, (int) Math.min(Math.max(0, start - 1), all.codePointCount(0, all.length())));
        int found = all.indexOf(searchstr, from);
        return found < 0 ? NOT_FOUND : all.codePointCount(0, found) + 1L;
    }

    @Override
    public long position(Clob searchstr, long start) throws SQLException {
        return position(searchstr.getSubString(1, Math.toIntExact(searchstr.length())), start);
    }

    private void writable() throws SQLException {
        live();
        if (remote != null) {
            throw Errors.unsupported("changing a large object of a row");
        }
    }

    @Override
    public int setString(long pos, String str) throws SQLException {
        return setString(pos, str, 0, str.length());
    }

    @Override
    public int setString(long pos, String str, int offset, int len) throws SQLException {
        writable();
        int count = held.codePointCount(0, held.length());
        if (pos < 1 || pos > count + 1L) {
            throw Errors.misuse("a Clob is written from character 1 to one past its end");
        }
        String part = str.substring(offset, offset + len);
        int from = held.offsetByCodePoints(0, (int) pos - 1);
        int replaced = Math.min(
                held.length(),
                held.offsetByCodePoints(
                        from,
                        Math.min(part.codePointCount(0, part.length()), held.codePointCount(from, held.length()))));
        held.replace(from, replaced, part);
        return len;
    }

    @Override
    public OutputStream setAsciiStream(long pos) throws SQLException {
        writable();
        return new ByteArrayOutputStream() {
            @Override
            public void close() throws java.io.IOException {
                try {
                    setString(pos, toString(StandardCharsets.UTF_8));
                } catch (SQLException e) {
                    throw new java.io.IOException(e.getMessage(), e);
                }
            }
        };
    }

    @Override
    public Writer setCharacterStream(long pos) throws SQLException {
        writable();
        return new StringWriter() {
            @Override
            public void close() throws java.io.IOException {
                try {
                    setString(pos, toString());
                } catch (SQLException e) {
                    throw new java.io.IOException(e.getMessage(), e);
                }
            }
        };
    }

    @Override
    public void truncate(long len) throws SQLException {
        writable();
        int count = held.codePointCount(0, held.length());
        held.setLength(held.offsetByCodePoints(0, (int) Math.min(len, count)));
    }

    @Override
    public void free() {
        freed = true;
    }
}
