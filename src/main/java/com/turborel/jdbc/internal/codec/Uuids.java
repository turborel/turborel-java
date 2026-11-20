package com.turborel.jdbc.internal.codec;

import java.nio.ByteBuffer;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A UUID's octets, as its value's {@code bytes} carry them, most significant first; and its text,
 * read as Turborel reads it ({@code src/value/types/uuid.rs}).
 */
final class Uuids {
    /** The octets of a UUID. */
    static final int OCTETS = 16;

    /** The hexadecimal digits of a UUID. */
    private static final int HEXITS = 32;

    /** The digits after which a hyphen may come. */
    private static final int HEXITS_PER_HYPHEN = 4;

    /** The hexadecimal digits of an octet. */
    private static final int HEXITS_PER_OCTET = 2;

    private static final int NIBBLE = 4;

    private Uuids() {}

    /**
     * The UUID a text writes, as CAST reads it: 32 hexadecimal digits in either case, its white
     * space trimmed, braced or not, a hyphen allowed after each group of four digits but the last;
     * none if it writes none.
     *
     * @param text the text
     * @return the UUID, or none
     */
    static @Nullable UUID parse(String text) {
        String t = trim(text);
        if (t.startsWith("{") && t.endsWith("}") && t.length() > 1) {
            t = t.substring(1, t.length() - 1);
        }
        byte[] octets = new byte[OCTETS];
        int digits = 0;
        int at = 0;
        while (at < t.length()) {
            int value = Ascii.hexDigit(t.charAt(at++));
            if (value == Ascii.NONE || digits >= HEXITS) {
                return null;
            }
            int octet = digits / HEXITS_PER_OCTET;
            octets[octet] = (byte) ((octets[octet] << NIBBLE) | value);
            digits++;
            boolean grouped = digits % HEXITS_PER_HYPHEN == 0;
            if (at < t.length() && t.charAt(at) == '-' && grouped && digits < HEXITS) {
                at++;
            }
        }
        return digits == HEXITS ? of(octets) : null;
    }

    /** The text without its leading and trailing white space, as Rust's {@code str::trim}. */
    static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && WhiteSpace.is(text.charAt(start))) {
            start++;
        }
        while (end > start && WhiteSpace.is(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    /**
     * The UUID of 16 octets.
     *
     * @param octets the octets, exactly 16
     * @return the UUID
     */
    static UUID of(byte[] octets) {
        ByteBuffer big = ByteBuffer.wrap(octets);
        return new UUID(big.getLong(), big.getLong());
    }
}
