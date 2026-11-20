package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A frame's header: the kind of the message it holds, then its body's length, five bytes before
 * every message either way ([PROTOCOL Frames]).
 *
 * <p>The length is a {@code u32}, least significant byte first ([PROTOCOL Values and fields]),
 * and counts the body alone. Java has no unsigned int, so the length is kept in an {@code int}'s
 * bits and always compared as unsigned, so a length past 2 GiB is never read as negative.
 *
 * @param kind the message's kind, read in the direction it travels
 * @param length the body's length in bytes, the header not counted, as an unsigned {@code u32}
 */
public record FrameHeader(byte kind, int length) {
    /** The bytes of a header: the kind, a {@code u8}, then the length, a {@code u32}. */
    public static final int BYTES = 5;

    /** The longest body the client takes unless told otherwise, 16 MiB ([PROTOCOL Frames]). */
    public static final int MAX_BODY = 16 * 1024 * 1024;

    /**
     * The header's five bytes, as the protocol writes them.
     *
     * @return the kind, then the length little-endian
     */
    public byte[] encode() {
        byte[] out = new byte[BYTES];
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).put(kind).putInt(length);
        return out;
    }

    /**
     * The header the five bytes given hold, its body no longer than {@code maxBody}.
     *
     * <p>A longer body fails the connection from its header alone, before any of it is read, so
     * a peer cannot make the client hold more than it takes ([PROTOCOL Frames]).
     *
     * @param header the header's bytes, exactly {@link #BYTES} of them
     * @param maxBody the longest body taken, in bytes
     * @return the kind and length the header holds
     * @throws TurborelException a connection failure (SQLCODE -919, SQLSTATE 08006) if the header
     *     is not five bytes, or its length is more than {@code maxBody}
     */
    public static FrameHeader decode(byte[] header, int maxBody) throws TurborelException {
        if (header.length != BYTES) {
            throw Broken.connection("[PROTOCOL Frames] a frame's header is " + BYTES + " bytes, not " + header.length);
        }
        ByteBuffer in = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        byte kind = in.get();
        int length = in.getInt();
        if (Integer.toUnsignedLong(length) > maxBody) {
            throw Broken.connection("[PROTOCOL Frames] a message of " + Integer.toUnsignedString(length)
                    + " bytes is longer than the " + maxBody + " taken");
        }
        return new FrameHeader(kind, length);
    }
}
