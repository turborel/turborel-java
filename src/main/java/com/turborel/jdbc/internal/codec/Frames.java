package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;

/**
 * Frames whole ([PROTOCOL Frames]): every message, either way, is a frame, its five-byte header
 * ({@link FrameHeader}) then its body.
 */
public final class Frames {
    private Frames() {}

    /**
     * A frame of the kind and body given, as the protocol writes it.
     *
     * @param kind the message's kind
     * @param body its body
     * @return the header, then the body
     * @throws TurborelException if the frame would be longer than a Java array holds
     */
    public static byte[] frame(byte kind, byte[] body) throws TurborelException {
        return new Writer()
                .raw(new FrameHeader(kind, body.length).encode())
                .raw(body)
                .toByteArray();
    }

    /**
     * The frame a received frame holds: itself, or, of kind {@code z}, the frame it holds
     * inflated ([PROTOCOL Compressed frames]).
     *
     * @param received the frame as it came
     * @param compression whether {@code compression} was granted
     * @param maxBody the longest body taken
     * @return the frame to decode
     * @throws TurborelException a connection failure if a compressed frame came when compression
     *     was not granted, or it is malformed ({@link Compression#inflate})
     */
    public static Frame unwrap(Frame received, boolean compression, int maxBody) throws TurborelException {
        if (received.kind() != Compression.KIND) {
            return received;
        }
        if (!compression) {
            throw Broken.connection(Cite.COMPRESSED + " a compressed frame came, and compression was not granted");
        }
        return Compression.inflate(received.body().shared(), maxBody);
    }

    /**
     * A frame's kind and body.
     *
     * @param kind its kind
     * @param body its body
     */
    public record Frame(byte kind, Octets body) {}
}
