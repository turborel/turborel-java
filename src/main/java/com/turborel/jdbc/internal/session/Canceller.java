package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.CancelKey;
import com.turborel.jdbc.internal.codec.Request;
import com.turborel.jdbc.internal.transport.Duplex;
import com.turborel.jdbc.internal.transport.Io;
import com.turborel.jdbc.internal.transport.Target;
import com.turborel.jdbc.internal.transport.Timeouts;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;

/**
 * What cancels the statement a connection runs, from any thread ([PROTOCOL Cancel 'Z'], [CLIENT
 * Running statements]): a new connection to the same server, made as the first was, whose first
 * and only message is Cancel with the key the first's Ready gave. The statement fails with SQLCODE
 * -920, SQLSTATE TB920; a cancel as nothing runs does nothing. As Turborel's {@code
 * client/src/cancel.rs}. It never shows its key.
 */
public final class Canceller {
    /** The most a cancel reads of the server before it closes: nothing is expected. */
    private static final int MOST_READ = 1;

    private final Target target;
    private final CancelKey key;

    /** The key it cancels by, which no one else is shown: for the session's tests to compare. */
    CancelKey key() {
        return key;
    }

    Canceller(Target target, CancelKey key) {
        this.target = target;
        this.key = key;
    }

    /**
     * Cancels the statement its connection runs, if one runs, giving up after 10 seconds.
     *
     * @throws TurborelException a connection failure if the server cannot be reached, or does not
     *     answer in time
     */
    public void cancel() throws TurborelException {
        cancelWithin(Timeouts.CANCEL_WITHIN);
    }

    /**
     * Cancels as {@link #cancel} does, waiting at most {@code limit} for each step.
     *
     * @param limit the longest each step waits
     * @throws TurborelException as {@link #cancel}
     */
    public void cancelWithin(Duration limit) throws TurborelException {
        Duplex duplex = target.opened(limit).duplex();
        try {
            OutputStream out = duplex.output();
            out.write(new Request.Cancel(key).encode());
            out.flush();
            // The server closes the connection, saying nothing.
            byte[] said = duplex.input().readNBytes(MOST_READ);
            while (said.length > 0) {
                said = duplex.input().readNBytes(MOST_READ);
            }
        } catch (IOException e) {
            throw Io.failed("cancel", e);
        } finally {
            duplex.close();
        }
    }

    /** Never the key. */
    @Override
    public String toString() {
        return "Canceller(..)";
    }
}
