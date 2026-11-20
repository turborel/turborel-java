package com.turborel.jdbc.internal.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.turborel.jdbc.TurborelException;
import org.junit.jupiter.api.Test;

/** The frame header, encoded and decoded byte by byte ([PROTOCOL Frames]). */
class FrameHeaderTest {
    private static final byte PING = 'G';
    private static final byte HELLO = 'H';
    private static final byte QUERY = 'Q';

    /**
     * Verifies: [PROTOCOL A worked example] the headers of the page's example, a Ping and a Pong
     * of no body, a Hello of 36 bytes and a Query of 13, encode to its bytes and decode back.
     */
    @Test
    void theWorkedExampleHeadersEncodeAndDecode() throws TurborelException {
        assertRoundTrip(new FrameHeader(PING, 0), new byte[] {0x47, 0x00, 0x00, 0x00, 0x00});
        assertRoundTrip(new FrameHeader(HELLO, 36), new byte[] {0x48, 0x24, 0x00, 0x00, 0x00});
        assertRoundTrip(new FrameHeader(QUERY, 13), new byte[] {0x51, 0x0D, 0x00, 0x00, 0x00});
    }

    /**
     * Verifies: [PROTOCOL Frames] the header is the kind, a u8, then the body's length, a u32
     * least significant byte first ([PROTOCOL Values and fields]); a body exactly as long as the
     * client takes, 16 MiB by default, is taken.
     */
    @Test
    void theLengthIsLittleEndianUpToTheLongestBodyTaken() throws TurborelException {
        assertRoundTrip(new FrameHeader(QUERY, 0x0003_0201), new byte[] {0x51, 0x01, 0x02, 0x03, 0x00});
        assertRoundTrip(new FrameHeader(QUERY, FrameHeader.MAX_BODY), new byte[] {0x51, 0x00, 0x00, 0x00, 0x01});
    }

    /**
     * Verifies: [PROTOCOL Frames] a body longer than the reader takes fails the connection
     * (SQLCODE -919, SQLSTATE 08006) from its header alone, a length past 2 GiB among them, which
     * is never read as negative; one exactly as long is taken.
     */
    @Test
    void aBodyPastTheLimitFailsTheConnection() throws TurborelException {
        byte[] seven = {0x51, 0x07, 0x00, 0x00, 0x00};
        assertEquals(new FrameHeader(QUERY, 7), FrameHeader.decode(seven, 7));
        assertBroken(seven, 6);
        assertBroken(new byte[] {0x51, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}, FrameHeader.MAX_BODY);
        assertBroken(new byte[] {0x51, 0x01, 0x00, 0x00, 0x01}, FrameHeader.MAX_BODY);
    }

    /**
     * Verifies: [PROTOCOL Frames] a header cut short, or longer than five bytes, fails the
     * connection (SQLCODE -919, SQLSTATE 08006).
     */
    @Test
    void aHeaderNotFiveBytesFailsTheConnection() {
        assertBroken(new byte[] {0x47, 0x00, 0x00, 0x00}, FrameHeader.MAX_BODY);
        assertBroken(new byte[] {0x47, 0x00, 0x00, 0x00, 0x00, 0x00}, FrameHeader.MAX_BODY);
        assertBroken(new byte[0], FrameHeader.MAX_BODY);
    }

    private static void assertRoundTrip(FrameHeader header, byte[] bytes) throws TurborelException {
        assertArrayEquals(bytes, header.encode(), header::toString);
        assertEquals(header, FrameHeader.decode(bytes, FrameHeader.MAX_BODY));
    }

    private static void assertBroken(byte[] header, int maxBody) {
        TurborelException failure = assertThrows(TurborelException.class, () -> FrameHeader.decode(header, maxBody));
        assertEquals(-919, failure.sqlcode());
        assertEquals(-919, failure.getErrorCode());
        assertEquals("08006", failure.getSQLState());
    }
}
