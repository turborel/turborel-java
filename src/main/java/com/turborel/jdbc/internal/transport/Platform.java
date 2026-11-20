package com.turborel.jdbc.internal.transport;

import java.util.Locale;

/** The platform the driver runs on, for what one platform alone has (RULES.md R-PLAT-2). */
public final class Platform {
    /** The system property naming the operating system. */
    private static final String OS_NAME = "os.name";

    private Platform() {}

    /**
     * Whether this is Windows, whose local transport is a named pipe, not a Unix socket.
     *
     * @return whether the operating system is Windows
     */
    public static boolean isWindows() {
        return System.getProperty(OS_NAME, "").toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
