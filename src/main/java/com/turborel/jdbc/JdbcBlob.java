package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.Lob;
import com.turborel.jdbc.internal.codec.LobKind;
import com.turborel.jdbc.internal.codec.Octets;
import com.turborel.jdbc.internal.session.Lobs;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Blob;
import java.sql.SQLException;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * A binary large object ([DESIGN] D2): of a row, read through its locator, a range at a time, while
 * the locator lives; or one the program made by {@code createBlob}, held in memory and sent whole.
 */
final class JdbcBlob implements Blob {
    /** What {@code position} gives of a pattern not found. */
    private static final long NOT_FOUND = -1;

    /** A large object of a row: its locator, and what reads it. */
    private record Remote(Lob.Locator locator, Lobs lobs) {}

    private final @Nullable Remote remote;
    private byte[] held;
    private boolean freed;

    /** A large object of a row, through its locator. */
    JdbcBlob(Lob.Locator locator, Lobs lobs) {
        this.remote = new Remote(locator, lobs);
        this.held = new byte[0];
    }

    /** A large object held in memory, of {@code octets}. */
    JdbcBlob(byte[] octets) {
        this.remote = null;
        this.held = octets.clone();
    }

    private void live() throws SQLException {
        if (freed) {
            throw Errors.misuse("the Blob is freed");
        }
    }

    /** The value it is sent as: its locator, or its octets whole. */
    Lob value() {
        Remote at = remote;
        return at != null ? at.locator() : new Lob.Whole(LobKind.BINARY, Octets.of(held));
    }

    @Override
    public long length() throws SQLException {
        live();
        Remote at = remote;
        return at != null ? at.locator().octets() : held.length;
    }

    @Override
    public byte[] getBytes(long pos, int length) throws SQLException {
        live();
        if (pos < 1 || length < 0) {
            throw Errors.misuse("a Blob is read from octet 1, a length of 0 or more");
        }
        Remote at = remote;
        if (at == null) {
            int from = (int) Math.min(pos - 1, held.length);
            return Arrays.copyOfRange(held, from, (int) Math.min(held.length, from + (long) length));
        }
        ByteArrayOutputStream read = new ByteArrayOutputStream();
        long from = pos - 1;
        while (read.size() < length) {
            byte[] part = at.lobs().read(at.locator(), from + read.size(), length - read.size(), false);
            if (part.length == 0) {
                break;
            }
            read.writeBytes(part);
        }
        return read.toByteArray();
    }

    @Override
    public InputStream getBinaryStream() throws SQLException {
        live();
        Remote at = remote;
        return at != null ? at.lobs().reader(at.locator()) : new ByteArrayInputStream(held.clone());
    }

    @Override
    public InputStream getBinaryStream(long pos, long length) throws SQLException {
        return new ByteArrayInputStream(getBytes(pos, Math.toIntExact(length)));
    }

    @Override
    public long position(byte[] pattern, long start) throws SQLException {
        byte[] all = getBytes(1, Math.toIntExact(length()));
        for (int i = (int) Math.max(0, start - 1); i + pattern.length <= all.length; i++) {
            if (Arrays.equals(all, i, i + pattern.length, pattern, 0, pattern.length)) {
                return i + 1L;
            }
        }
        return NOT_FOUND;
    }

    @Override
    public long position(Blob pattern, long start) throws SQLException {
        return position(pattern.getBytes(1, Math.toIntExact(pattern.length())), start);
    }

    private void writable() throws SQLException {
        live();
        if (remote != null) {
            throw Errors.unsupported("changing a large object of a row");
        }
    }

    @Override
    public int setBytes(long pos, byte[] bytes) throws SQLException {
        return setBytes(pos, bytes, 0, bytes.length);
    }

    @Override
    public int setBytes(long pos, byte[] bytes, int offset, int len) throws SQLException {
        writable();
        if (pos < 1 || pos > held.length + 1L) {
            throw Errors.misuse("a Blob is written from octet 1 to one past its end");
        }
        int from = (int) pos - 1;
        if (from + len > held.length) {
            held = Arrays.copyOf(held, from + len);
        }
        System.arraycopy(bytes, offset, held, from, len);
        return len;
    }

    @Override
    public OutputStream setBinaryStream(long pos) throws SQLException {
        writable();
        return new ByteArrayOutputStream() {
            private long at = pos;

            @Override
            public void flush() throws java.io.IOException {
                byte[] written = toByteArray();
                reset();
                try {
                    at += setBytes(at, written);
                } catch (SQLException e) {
                    throw new java.io.IOException(e.getMessage(), e);
                }
            }

            @Override
            public void close() throws java.io.IOException {
                flush();
            }
        };
    }

    @Override
    public void truncate(long len) throws SQLException {
        writable();
        held = Arrays.copyOf(held, (int) Math.min(len, held.length));
    }

    @Override
    public void free() {
        freed = true;
    }
}
