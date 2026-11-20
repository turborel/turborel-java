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

    /**
     * A text without its white space at either end, as Rust's {@code str::trim} gives it.
     *
     * @param text the text
     * @return it trimmed
     */
    public static String trim(String text) {
        return trimEnd(trimStart(text));
    }

    /**
     * A text without its white space at its start, as Rust's {@code str::trim_start}.
     *
     * @param text the text
     * @return it trimmed
     */
    public static String trimStart(String text) {
        int start = 0;
        while (start < text.length() && is(text.codePointAt(start))) {
            start += Character.charCount(text.codePointAt(start));
        }
        return text.substring(start);
    }

    /**
     * A text without its white space at its end, as Rust's {@code str::trim_end}.
     *
     * @param text the text
     * @return it trimmed
     */
    public static String trimEnd(String text) {
        int end = text.length();
        while (end > 0 && is(text.codePointBefore(end))) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        return text.substring(0, end);
    }

    private static boolean within(int c, char first, char last) {
        return c >= first && c <= last;
    }
}
