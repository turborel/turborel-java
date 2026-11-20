package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Compression;
import com.turborel.jdbc.internal.codec.FrameHeader;
import com.turborel.jdbc.internal.codec.Frames;
import com.turborel.jdbc.internal.codec.Octets;
import com.turborel.jdbc.internal.codec.Reply;
import com.turborel.jdbc.internal.codec.ReplyCodec;
import com.turborel.jdbc.internal.codec.Request;
import com.turborel.jdbc.internal.transport.Duplex;
import com.turborel.jdbc.internal.transport.Io;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.jspecify.annotations.Nullable;

/**
 * A connection's frames ([PROTOCOL Frames], [PROTOCOL Compressed frames]): each request encoded
 * and written, compressed once compression is granted, and each reply read and decoded, inflated
 * if it came compressed, a body read as its bytes arrive so a header alone reserves little. As
 * Turborel's {@code client/src/frames.rs}.
 */
final class Wire {
    /** The bytes a stream is read and written through at a time. */
    private static final int BUFFER = 64 * 1024;

    private final Duplex duplex;
    private final InputStream in;
    private final OutputStream out;

    Wire(Duplex duplex) {
        this.duplex = duplex;
        this.in = new BufferedInputStream(duplex.input(), BUFFER);
        this.out = new BufferedOutputStream(duplex.output(), BUFFER);
    }

    Duplex duplex() {
        return duplex;
    }

    /**
     * Writes frames already encoded, at once, each past the threshold compressed if given.
     *
     * @param frames the frames
     * @param compressFrom the compression threshold, or none if compression is not granted
     * @throws TurborelException a connection failure if they cannot be written
     */
    void write(byte[] frames, @Nullable Integer compressFrom) throws TurborelException {
        byte[] sent = compressFrom == null ? frames : Compression.frames(frames, compressFrom);
        try {
            out.write(sent);
            out.flush();
        } catch (IOException e) {
            throw Io.failed("send", e);
        }
    }

    /**
     * Writes a request.
     *
     * @param request the request
     * @param compressFrom the compression threshold, or none
     * @throws TurborelException the codec's refusal; a connection failure if it cannot be written
     */
    void send(Request request, @Nullable Integer compressFrom) throws TurborelException {
        write(request.encode(), compressFrom);
    }

    /**
     * The next frame the server sends, decoded: inflated first if it came compressed and
     * compression was granted; else a compressed frame fails.
     *
     * @param maxBody the longest body taken
     * @param compression whether compression was granted
     * @return the reply
     * @throws TurborelException a connection failure if the connection closed, or the frame is
     *     malformed or too long
     */
    Reply receive(int maxBody, boolean compression) throws TurborelException {
        Frames.Frame frame = frame(maxBody, compression);
        return ReplyCodec.decode(frame.kind(), frame.body().toByteArray());
    }

    /**
     * The next frame the server sends, whole, inflated first if it came compressed and compression
     * was granted, not yet decoded: so a value in it the client cannot take leaves the stream in
     * step ([PROTOCOL Values and fields]).
     *
     * @param maxBody the longest body taken
     * @param compression whether compression was granted
     * @return the frame
     * @throws TurborelException a connection failure if the connection closed, or the frame's
     *     header or compression is malformed, or it is too long
     */
    Frames.Frame frame(int maxBody, boolean compression) throws TurborelException {
        byte[] head = read(FrameHeader.BYTES);
        if (head.length < FrameHeader.BYTES) {
            throw Io.failed("receive", new IOException("the connection closed"));
        }
        FrameHeader header = FrameHeader.decode(head, maxBody);
        byte[] body = read(header.length());
        if (body.length != header.length()) {
            throw Io.failed("receive", new IOException("the connection closed within a reply"));
        }
        return Frames.unwrap(new Frames.Frame(header.kind(), Octets.of(body)), compression, maxBody);
    }

    /**
     * What is left to read, to the end of the stream, of at most {@code most} bytes.
     *
     * @param most the most read
     * @return the bytes
     * @throws TurborelException a connection failure if they cannot be read
     */
    byte[] rest(int most) throws TurborelException {
        return read(most);
    }

    private byte[] read(int count) throws TurborelException {
        try {
            return in.readNBytes(count);
        } catch (IOException e) {
            throw Io.failed("receive", e);
        }
    }
}
