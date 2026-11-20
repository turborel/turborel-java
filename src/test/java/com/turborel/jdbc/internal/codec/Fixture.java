package com.turborel.jdbc.internal.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.turborel.jdbc.TurborelException;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.function.Executable;

/**
 * The codec tests' shared fixtures (RULES.md R-TEST-7): the protocol's fields written by hand,
 * least significant byte first, as the page prints them, apart from the codec's own writer, and
 * the failures the codec gives.
 */
final class Fixture {
    private Fixture() {}

    static byte[] hex(String spaced) {
        return HexFormat.of().parseHex(spaced.replace(" ", "").replace("\n", ""));
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static byte[] u8(int value) {
        return new byte[] {(byte) value};
    }

    static byte[] u32(long value) {
        return ByteBuffer.allocate(Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt((int) value)
                .array();
    }

    static byte[] u64(long value) {
        return ByteBuffer.allocate(Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(value)
                .array();
    }

    static byte[] i128(BigInteger value) {
        byte[] big = value.toByteArray();
        byte[] little = new byte[16];
        for (int i = 0; i < 16; i++) {
            int from = big.length - 1 - i;
            little[i] = from >= 0 ? big[from] : (byte) (value.signum() < 0 ? 0xFF : 0);
        }
        return little;
    }

    static byte[] str(String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        return cat(u32(utf8.length), utf8);
    }

    static byte[] bytes(byte[] octets) {
        return cat(u32(octets.length), octets);
    }

    static Reader reader(byte[] body) {
        return new Reader(body, "reply");
    }

    /** Asserts a connection failure, SQLCODE -919 and SQLSTATE 08006 ([PROTOCOL Errors and notices]). */
    static TurborelException assertBroken(Executable call) {
        TurborelException failure = assertThrows(TurborelException.class, call);
        assertEquals(Broken.SQLCODE, failure.sqlcode(), failure.getMessage());
        assertEquals(Broken.SQLSTATE, failure.getSQLState(), failure.getMessage());
        return failure;
    }

    /** Asserts a program's misuse, SQLCODE -910 and SQLSTATE TB910. */
    static TurborelException assertMisuse(Executable call) {
        TurborelException failure = assertThrows(TurborelException.class, call);
        assertEquals(Failures.MISUSE_SQLCODE, failure.sqlcode(), failure.getMessage());
        assertEquals(Failures.MISUSE_SQLSTATE, failure.getSQLState(), failure.getMessage());
        return failure;
    }

    /** Asserts something past Turborel's limits, SQLCODE -900 and SQLSTATE 0A000. */
    static TurborelException assertBeyond(Executable call) {
        TurborelException failure = assertThrows(TurborelException.class, call);
        assertEquals(Failures.BEYOND_SQLCODE, failure.sqlcode(), failure.getMessage());
        assertEquals(Failures.BEYOND_SQLSTATE, failure.getSQLState(), failure.getMessage());
        return failure;
    }

    /** The bytes a value encodes to. */
    static byte[] encoded(Value value) throws TurborelException {
        Writer w = new Writer();
        value.encode(w);
        return w.toByteArray();
    }

    /** The value bytes decode to, every byte read. */
    static Value decoded(byte[] bytes) throws TurborelException {
        Reader r = reader(bytes);
        Value value = ValueCodec.read(r);
        r.finish();
        return value;
    }

    /** The type bytes decode to, every byte read. */
    static DataType type(byte[] bytes) throws TurborelException {
        Reader r = reader(bytes);
        DataType type = TypeCodec.read(r);
        r.finish();
        return type;
    }
}
