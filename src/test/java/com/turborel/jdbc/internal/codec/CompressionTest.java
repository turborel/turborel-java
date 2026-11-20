package com.turborel.jdbc.internal.codec;

import static com.turborel.jdbc.internal.codec.Fixture.assertBroken;
import static com.turborel.jdbc.internal.codec.Fixture.cat;
import static com.turborel.jdbc.internal.codec.Fixture.u32;
import static com.turborel.jdbc.internal.codec.Fixture.u8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.turborel.jdbc.TurborelException;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/** Frames whole and compressed frames ([PROTOCOL Frames], [PROTOCOL Compressed frames]). */
class CompressionTest {
    /** A fixed seed, so the incompressible body is the same on every run (RULES.md R-TEST-5). */
    private static final long SEED = 7422;

    private static final byte QUERY = 'Q';
    private static final int THRESHOLD = Compression.DEFAULT_THRESHOLD;

    /**
     * Verifies: [PROTOCOL Frames] a frame is its kind, its body's length and the body, and one
     * received of another kind than z is decoded as it came.
     */
    @Test
    void aFrameIsItsHeaderThenItsBody() throws TurborelException {
        byte[] frame = Frames.frame(QUERY, new byte[] {1, 2});
        assertArrayEquals(new byte[] {'Q', 2, 0, 0, 0, 1, 2}, frame);
        Frames.Frame plain = new Frames.Frame(QUERY, Octets.of(new byte[] {1, 2}));
        assertSame(plain, Frames.unwrap(plain, false, FrameHeader.MAX_BODY));
    }

    /**
     * Verifies: [PROTOCOL Compressed frames] a frame of at least the client's threshold, header
     * included, that comes out shorter is sent as kind z: the held frame's kind, its body's
     * length, a zlib stream of the body; and it reads back as the frame it holds.
     */
    @Test
    void aLongFrameIsSentCompressed() throws TurborelException {
        byte[] body = new byte[4096];
        Arrays.fill(body, (byte) 'x');
        byte[] sent = Compression.frame(QUERY, body, THRESHOLD);
        assertEquals(Compression.KIND, sent[0]);
        assertEquals(QUERY, sent[FrameHeader.BYTES]);
        assertArrayEquals(u32(body.length), Arrays.copyOfRange(sent, FrameHeader.BYTES + 1, FrameHeader.BYTES + 5));
        assertEquals(0x78, sent[FrameHeader.BYTES + 5] & 0xFF, "a zlib stream's header");
        Frames.Frame received =
                new Frames.Frame(sent[0], Octets.of(Arrays.copyOfRange(sent, FrameHeader.BYTES, sent.length)));
        Frames.Frame held = Frames.unwrap(received, true, FrameHeader.MAX_BODY);
        assertEquals(new Frames.Frame(QUERY, Octets.of(body)), held);
    }

    /**
     * Verifies: [PROTOCOL Compressed frames] the threshold is the client's own: a frame shorter
     * than it, or one compressing would not shorten, is sent as it is.
     */
    @Test
    void aShortOrIncompressibleFrameIsSentAsItIs() throws TurborelException {
        byte[] small = new byte[THRESHOLD - FrameHeader.BYTES - 1];
        assertArrayEquals(Frames.frame(QUERY, small), Compression.frame(QUERY, small, THRESHOLD));
        byte[] noise = new byte[4096];
        Random random = new Random(SEED);
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (byte) random.nextInt();
        }
        assertArrayEquals(Frames.frame(QUERY, noise), Compression.frame(QUERY, noise, THRESHOLD));
        byte[] repeated = new byte[64];
        assertEquals(Compression.KIND, Compression.frame(QUERY, repeated, 0)[0]);
    }

    /**
     * Verifies: [PROTOCOL Compressed frames] a z frame received when compression was not granted
     * fails the connection.
     */
    @Test
    void aCompressedFrameNotGrantedFails() {
        Frames.Frame z = new Frames.Frame(Compression.KIND, Octets.of(cat(u8('Q'), u32(0), stream(new byte[0]))));
        assertBroken(() -> Frames.unwrap(z, false, FrameHeader.MAX_BODY));
    }

    /**
     * Verifies: [PROTOCOL Compressed frames] a z frame holding another z frame fails, and one that
     * declares more than the longest body taken fails before anything is inflated.
     */
    @Test
    void aNestedOrOverlongCompressedFrameFails() {
        assertBroken(() -> Compression.inflate(cat(u8('z'), u32(1), stream(new byte[1])), FrameHeader.MAX_BODY));
        assertBroken(() -> Compression.inflate(cat(u8('Q'), u32(17), stream(new byte[17])), 16));
        assertBroken(() -> Compression.inflate(cat(u8('Q'), u32(0xFFFF_FFFFL)), FrameHeader.MAX_BODY));
        assertBroken(() -> Compression.inflate(new byte[] {'Q', 1}, FrameHeader.MAX_BODY));
    }

    /**
     * Verifies: [PROTOCOL Compressed frames] the reader inflates at most the length declared: a
     * stream that inflates to more, or to less, fails the connection, as does one that is not
     * zlib, one cut short, and one with bytes after its end.
     */
    @Test
    void aStreamOfAnotherLengthFails() throws TurborelException {
        byte[] ten = new byte[10];
        assertEquals(
                new Frames.Frame(QUERY, Octets.of(ten)), Compression.inflate(cat(u8('Q'), u32(10), stream(ten)), 10));
        assertBroken(() -> Compression.inflate(cat(u8('Q'), u32(9), stream(ten)), FrameHeader.MAX_BODY));
        assertBroken(() -> Compression.inflate(cat(u8('Q'), u32(11), stream(ten)), FrameHeader.MAX_BODY));
        assertBroken(() -> Compression.inflate(cat(u8('Q'), u32(10), new byte[] {1, 2, 3}), FrameHeader.MAX_BODY));
        byte[] cut = Arrays.copyOf(stream(ten), stream(ten).length - 2);
        assertBroken(() -> Compression.inflate(cat(u8('Q'), u32(10), cut), FrameHeader.MAX_BODY));
        assertBroken(() -> Compression.inflate(cat(u8('Q'), u32(10), stream(ten), u8(0)), FrameHeader.MAX_BODY));
        assertEquals(
                new Frames.Frame(QUERY, Octets.EMPTY),
                Compression.inflate(cat(u8('Q'), u32(0), stream(new byte[0])), 0));
    }

    private static byte[] stream(byte[] data) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[256];
            while (!deflater.finished()) {
                out.write(chunk, 0, deflater.deflate(chunk));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }
}
