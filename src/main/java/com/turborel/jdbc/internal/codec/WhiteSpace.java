package com.turborel.jdbc.internal.codec;

/** Unicode's White_Space property, as Rust's {@code char::is_whitespace} reads it. */
public final class WhiteSpace {
    /** The characters of White_Space outside its two ranges, tab to carriage return and U+2000 to U+200A. */
    private static final String SINGLES = " \u0085      　";

    private WhiteSpace() {}

    /**
     * Whether a character is white space, as Rust reads it; not as Java's {@code isWhitespace},
     * which takes U+001C to U+001F and leaves out U+00A0.
     *
     * @param c the character, as a code point
     * @return whether it is
     */
    public static boolean is(int c) {
        return within(c, '\t', '\r')
                || within(c, ' ', ' ')
                || (c < Character.MIN_SUPPLEMENTARY_CODE_POINT && SINGLES.indexOf(c) >= 0);
    }

    private static boolean within(int c, char first, char last) {
        return c >= first && c <= last;
    }
}
