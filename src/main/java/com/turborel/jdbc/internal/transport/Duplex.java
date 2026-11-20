package com.turborel.jdbc.internal.transport;

import com.turborel.jdbc.TurborelException;
import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * A stream to a server that reads and writes, of any transport, and bounds its own waits
 * ([CLIENT Timeouts]): each read by a limit of time set on the stream itself.
 */
public interface Duplex extends Closeable {
    /**
     * What reads the server's bytes.
     *
     * @return the stream's input
     */
    InputStream input();

    /**
     * What writes to the server.
     *
     * @return the stream's output
     */
    OutputStream output();

    /**
     * Bounds each read by {@code within}; none, no limit.
     *
     * @param within the longest a read waits, or none
     * @throws TurborelException a connection failure if the system cannot bound it
     */
    void bound(@Nullable Duration within) throws TurborelException;

    /** Closes the stream; a failure to close is of no consequence once it is given up. */
    @Override
    void close();
}
