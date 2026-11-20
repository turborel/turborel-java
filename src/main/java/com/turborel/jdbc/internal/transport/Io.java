package com.turborel.jdbc.internal.transport;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Broken;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;

/** The failures of what the client could not do on its stream ([CLIENT Timeouts]). */
public final class Io {
    private Io() {}

    /**
     * A connection failure of what the client could not do, saying so if the server took longer
     * than the connection waits ([CLIENT Timeouts]).
     *
     * @param what what it could not do, as "connect" or "receive"
     * @param e why
     * @return the failure, SQLCODE -919, SQLSTATE 08006
     */
    public static TurborelException failed(String what, IOException e) {
        String message = e instanceof SocketTimeoutException || e instanceof InterruptedIOException
                ? "[DESIGN] cannot " + what + ": the server did not answer in time (" + e.getMessage() + ")"
                : "[DESIGN] cannot " + what + ": " + e.getMessage();
        TurborelException failure = Broken.connection(message);
        failure.initCause(e);
        return failure;
    }
}
