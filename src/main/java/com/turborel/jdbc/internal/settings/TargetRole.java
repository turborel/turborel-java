package com.turborel.jdbc.internal.settings;

import com.turborel.jdbc.TurborelException;

/** What a server must be for a connection to it to be kept ([CLIENT Several servers]). */
public enum TargetRole {
    /** Any server. The default. */
    ANY,
    /** One that takes changes: a server alone, or its group's primary. */
    READ_WRITE,
    /** One that takes none: a replica following its group's log. */
    READ_ONLY;

    /**
     * The role {@code text} names, the value of {@code key}.
     *
     * @param key the setting's name
     * @param text its value
     * @return the role
     * @throws TurborelException SQLSTATE 08001 if it names none
     */
    public static TargetRole parse(String key, String text) throws TurborelException {
        return Words.word(key, text, Words.TARGET_ROLES);
    }
}
