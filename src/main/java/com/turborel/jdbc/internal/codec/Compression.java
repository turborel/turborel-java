package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Compressed frames ([PROTOCOL Compressed frames]): once {@code compression} is granted, either
 * side may send any frame compressed, as a frame of kind {@code z} whose body is the held frame's
 * kind, its body's length, and its body as a zlib stream (RFC 1950), by {@code java.util.zip}.
 *
 * <p>The reader inflates at most the length declared, itself at most the longest body it takes,
 * so a stream cannot make it hold more (RULES.md R-SAFE-1).
 */
public final class Compression {
    /** The kind of a compressed frame, the same both ways. */
    public static final byte KIND = 'z';

    /**
     * The client's default threshold, as Turborel's {@code compress.rs} has it: a frame shorter
     * than this, header included, is never compressed, as the stream's header, its trailer and
     * the time taken outweigh what it saves. The client chooses its own.
     */
    public static final int DEFAULT_THRESHOLD = 1024;

    /** The bytes a compressed frame's body takes before its stream: the kind and the length. */
    private static final int PREFIX = 5;

    /** The bytes a stream is deflated into at a time. */
    private static final int CHUNK = 8192;

    private Compression() {}

    /**
     * A frame, compressed if it is at least {@code threshold} bytes long, header included, and
     * comes out shorter so; else as it is.
     *
     * @param kind the frame's kind
     * @param body the frame's body
     * @param threshold the shortest frame compressed
     * @return the frame to send, header and body
     * @throws TurborelException if the frame would be longer than a Java array holds
     */
    public static byte[] frame(byte kind, byte[] body, int threshold) throws TurborelException {
        if ((long) FrameHeader.BYTES + body.length >= threshold) {
            byte[] stream = deflate(body);
            if (stream.length + PREFIX < body.length) {
                Writer held = new Writer()
                        .u8(Byte.toUnsignedInt(kind))
                        .bits32(body.length)
                        .raw(stream);
                return Frames.frame(KIND, held.toByteArray());
            }
        }
        return Frames.frame(kind, body);
    }

    /**
     * Frames already encoded, one or a batch, each of at least {@code threshold} bytes compressed
     * where that makes it shorter ([PROTOCOL Compressed frames]).
     *
     * @param frames the frames, each its header then its body
     * @param threshold the shortest frame compressed
     * @return the frames to send
     * @throws TurborelException if the frames would be longer than a Java array holds
     */
    public static byte[] frames(byte[] frames, int threshold) throws TurborelException {
        Writer out = new Writer();
        int at = 0;
        while (frames.length - at >= FrameHeader.BYTES) {
            byte kind = frames[at];
            int length = (int) Math.min(
                    frames.length - at - FrameHeader.BYTES,
                    Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(frames, at + 1, Integer.BYTES)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .getInt()));
            int body = at + FrameHeader.BYTES;
            out.raw(frame(kind, java.util.Arrays.copyOfRange(frames, body, body + length), threshold));
            at = body + length;
        }
        return out.toByteArray();
    }

    /**
     * The frame a compressed frame's body holds.
     *
     * @param body the compressed frame's body
     * @param maxBody the longest body taken
     * @return the held frame's kind and body
     * @throws TurborelException a connection failure if the body is malformed, holds another
     *     compressed frame, declares more than {@code maxBody}, is no zlib stream, or inflates to
     *     another length than it declares
     */
    public static Frames.Frame inflate(byte[] body, int maxBody) throws TurborelException {
        Reader r = new Reader(body, "compressed frame");
        int kind = r.u8();
        long length = r.u32();
        if (kind == KIND) {
            throw Broken.connection(Cite.COMPRESSED + " a compressed frame holds another");
        }
        if (length > maxBody) {
            throw Broken.connection(Cite.COMPRESSED + " a compressed message of " + length
                    + " bytes is longer than the " + maxBody + " taken");
        }
        byte[] stream = r.take(body.length - PREFIX);
        return new Frames.Frame((byte) kind, Octets.owning(inflated(stream, (int) length)));
    }

    private static byte[] deflate(byte[] body) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(body);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[CHUNK];
            while (!deflater.finished()) {
                out.write(chunk, 0, deflater.deflate(chunk));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** The stream's data, which must be exactly {@code length} bytes, the stream all of it. */
    private static byte[] inflated(byte[] stream, int length) throws TurborelException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(stream);
            byte[] out = new byte[length];
            int filled = 0;
            while (filled < length) {
                int got = inflater.inflate(out, filled, length - filled);
                if (got == 0) {
                    break;
                }
                filled += got;
            }
            if (filled < length || inflater.inflate(new byte[1]) > 0 || !inflater.finished()) {
                throw Broken.connection(
                        Cite.COMPRESSED + " a compressed frame inflates to another length than it declares");
            }
            if (inflater.getRemaining() > 0) {
                throw Broken.connection(Cite.COMPRESSED + " bytes follow a compressed frame's stream");
            }
            return out;
        } catch (DataFormatException e) {
            throw Broken.connection(Cite.COMPRESSED + " a compressed frame's stream is not zlib: " + e.getMessage());
        } finally {
            inflater.end();
        }
    }
}
