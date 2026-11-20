package com.turborel.jdbc.internal.settings;

import com.turborel.jdbc.TurborelException;

/** Whether servers are tried in the order given ([CLIENT Several servers]). */
public enum LoadBalance {
    /** In order. The default. */
    DISABLE,
    /** In an order chosen at random for each connection, so connections spread over them. */
    RANDOM;

    /**
     * The choice {@code text} names, the value of {@code key}.
     *
     * @param key the setting's name
     * @param text its value
     * @return the choice
     * @throws TurborelException SQLSTATE 08001 if it names none
     */
    public static LoadBalance parse(String key, String text) throws TurborelException {
        return Words.word(key, text, Words.LOAD_BALANCES);
    }
}
