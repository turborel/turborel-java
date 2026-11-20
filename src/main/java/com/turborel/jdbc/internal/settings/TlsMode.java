package com.turborel.jdbc.internal.settings;

import com.turborel.jdbc.TurborelException;

/**
 * How a connection over TCP uses TLS ([CLIENT TLS by mode]). A local connection, over a Unix
 * socket or a named pipe, is private, and uses none whatever this says.
 */
public enum TlsMode {
    /** Never: plain TCP. */
    DISABLE,
    /** TLS if the server takes it, else plain TCP; its certificate not checked. The default. */
    PREFER,
    /** TLS or no connection; the server's certificate not checked. */
    REQUIRE,
    /** TLS, the server's certificate signed by an authority trusted, its name not checked. */
    VERIFY_CA,
    /** TLS, the server's certificate signed by an authority trusted and of the server's name. */
    VERIFY_FULL;

    /**
     * The mode {@code text} names, the value of {@code key}.
     *
     * @param key the setting's name
     * @param text its value
     * @return the mode
     * @throws TurborelException SQLSTATE 08001 if it names none
     */
    public static TlsMode parse(String key, String text) throws TurborelException {
        return Words.word(key, text, Words.TLS_MODES);
    }

    /**
     * Whether the server's certificate is checked against the authorities trusted.
     *
     * @return whether it verifies
     */
    public boolean verifies() {
        return this == VERIFY_CA || this == VERIFY_FULL;
    }
}
