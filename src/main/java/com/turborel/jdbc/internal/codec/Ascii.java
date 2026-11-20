package com.turborel.jdbc.internal.codec;

/** The ASCII characters the codec reads digits of, as Rust's {@code char::to_digit} reads them. */
final class Ascii {
    /** What a character that is no digit of the radix asked for gives. */
    static final int NONE = -1;

    /** The first character past ASCII. */
    private static final int END = 0x80;

    /** The radix of hexadecimal digits. */
    private static final int HEX = 16;

    private Ascii() {}

    /**
     * The value of an ASCII hexadecimal digit, in either case; {@link #NONE} of any other
     * character, a digit of another script too.
     *
     * @param c the character, as a code point
     * @return its value, or {@link #NONE}
     */
    static int hexDigit(int c) {
        return c >= 0 && c < END ? Character.digit(c, HEX) : NONE;
    }
}
