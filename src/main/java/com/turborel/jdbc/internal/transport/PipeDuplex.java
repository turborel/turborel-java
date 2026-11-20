package com.turborel.jdbc.internal.transport;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Broken;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A Windows named pipe as a {@link Duplex}, opened as a file ([CLIENT Connecting], [CLIENT
 * Timeouts]): while every instance of the pipe is busy, the open is tried again until the limit
 * to connect runs out, as Turborel's {@code pipe.rs} does. Once open, its reads and writes are
 * not bounded: Java, as Rust's standard library, sets no limit of time on a file's reads.
 */
public final class PipeDuplex implements Duplex {
    /** How long a client waits before it tries a busy pipe again. */
    private static final Duration BUSY_RETRY = Duration.ofMillis(10);

    /** How the system says every instance of a pipe is busy, ERROR_PIPE_BUSY's text. */
    private static final String BUSY = "busy";

    private final RandomAccessFile pipe;
    private final InputStream input;
    private final OutputStream output;

    private PipeDuplex(RandomAccessFile pipe) {
        this.pipe = pipe;
        this.input = Channels.newInputStream(pipe.getChannel());
        this.output = Channels.newOutputStream(pipe.getChannel());
    }

    /**
     * The named pipe {@code path}, as {@code \\.\pipe\turborel}, opened, tried again while busy.
     *
     * @param path the pipe's name
     * @param within how long it is tried while busy, or without limit
     * @return the duplex
     * @throws TurborelException a connection failure if this is not Windows, or the pipe cannot
     *     be opened, in time
     */
    public static PipeDuplex open(Path path, @Nullable Duration within) throws TurborelException {
        return open(path, within, Platform.isWindows(), name -> new RandomAccessFile(name.toFile(), "rw"));
    }

    /** What opens a pipe by its name, as the system does. */
    @FunctionalInterface
    interface Opener {
        /**
         * The pipe of a name, opened.
         *
         * @param path its name
         * @return the pipe
         * @throws FileNotFoundException if it cannot be opened, its message saying why
         */
        RandomAccessFile open(Path path) throws FileNotFoundException;
    }

    /**
     * As {@link #open(Path, Duration)}, on a platform and by an opener given.
     *
     * @param path the pipe's name
     * @param within how long it is tried while busy, or without limit
     * @param windows whether this is Windows
     * @param opener what opens the pipe
     * @return the duplex
     * @throws TurborelException as {@link #open(Path, Duration)}
     */
    static PipeDuplex open(Path path, @Nullable Duration within, boolean windows, Opener opener)
            throws TurborelException {
        if (!windows) {
            throw Broken.connection("[CLIENT Connecting] " + path + " names a named pipe, which only Windows has");
        }
        long began = System.nanoTime();
        while (true) {
            try {
                return new PipeDuplex(opener.open(path));
            } catch (FileNotFoundException e) {
                String why = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
                if (!why.contains(BUSY)) {
                    throw Io.failed("connect", e);
                }
                if (within != null && System.nanoTime() - began >= within.toNanos()) {
                    throw Io.failed(
                            "connect", new java.net.SocketTimeoutException("every instance of the pipe is busy"));
                }
                sleep();
            }
        }
    }

    private static void sleep() throws TurborelException {
        try {
            Thread.sleep(BUSY_RETRY.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Io.failed("connect", new java.io.InterruptedIOException("interrupted while the pipe was busy"));
        }
    }

    @Override
    public InputStream input() {
        return input;
    }

    @Override
    public OutputStream output() {
        return output;
    }

    /** Not bounded: a pipe's reads wait as long as they must. */
    @Override
    public void bound(@Nullable Duration within) {
        // Java sets no limit of time on a file's reads (CLIENT Timeouts).
    }

    @Override
    public void close() {
        try {
            pipe.close();
        } catch (IOException ignored) {
            // A pipe given up is closed as far as the system lets it.
        }
    }
}
