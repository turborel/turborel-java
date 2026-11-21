package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JSON text parsed to an SQL/JSON item, as Turborel parses it ({@code src/json/text.rs}), by the
 * grammar of RFC 8259: so a JSON parameter may be given as text, and sent as its stored form.
 *
 * <p>A number without an exponent is exact, one with an exponent approximate. An exact one of more
 * significant digits than an exact number holds, 18, is a decimal floating point number, which
 * holds 34 exactly; its leading zeros, and the trailing zeros of its fraction, are not counted. A
 * number no number of Turborel's holds exactly is not rounded but refused (SQLSTATE 22003), as
 * one past an approximate number's range is. A string's escapes are replaced by what they stand
 * for; a surrogate escaped that is not of a pair is refused. Members of one key are all kept, in
 * the order written, unless keys are to be unique.
 */
public final class JsonParse {
    /**
     * The most digits of a JSON number held exact, Turborel's {@code JSON_EXACT_DIGITS}: those of
     * 64 bits, as RFC 8259 lets precision be limited, though an exact number of SQL holds 38; more
     * are decimal floating point (Turborel's RULES.md D695).
     */
    static final int MAX_EXACT_DIGITS = 18;

    /** The digits a decimal floating point number holds. */
    static final int MAX_DECFLOAT_DIGITS = 34;

    /** The SQLSTATE of two members of one key in an object whose keys are to be unique. */
    public static final String NON_UNIQUE_KEYS = "22037";

    /** What {@code peek} gives past the text's end. */
    private static final int END = -1;

    /** What an escape's letter gives that is {@code u}, four hexadecimal digits to follow. */
    private static final int UNICODE_ESCAPE = -2;

    private static final int LAST_CONTROL = 0x1F;
    private static final int HEX = 16;
    private static final int HEX_DIGITS = 4;

    /** The parts of a number with an exponent, split at its E. */
    private static final int MANTISSA_AND_EXPONENT = 2;

    private static final int HIGH_FIRST = 0xD800;
    private static final int HIGH_LAST = 0xDBFF;
    private static final int LOW_FIRST = 0xDC00;
    private static final int LOW_LAST = 0xDFFF;
    private static final int SURROGATE_BITS = 10;
    private static final int SUPPLEMENTARY = 0x1_0000;

    /** Whether two members of an object may have one key. */
    public enum Keys {
        /** WITHOUT UNIQUE KEYS: two members of an object may have one key. */
        ANY,
        /** WITH UNIQUE KEYS: no two members of an object have one key. */
        UNIQUE
    }

    private final int[] chars;
    private final Keys keys;
    private int at;

    private JsonParse(String text, Keys keys) {
        this.chars = text.codePoints().toArray();
        this.keys = keys;
    }

    /**
     * The SQL/JSON item a JSON text is.
     *
     * @param text the JSON text
     * @param keys whether its objects' keys are to be unique
     * @return the item
     * @throws TurborelException SQLSTATE 22032 if it is no JSON text, or escapes a surrogate not of
     *     a pair; 22037 if two members of an object have one key under {@link Keys#UNIQUE}; 22003
     *     if a number is one no number of Turborel's holds exactly; SQLCODE -900, SQLSTATE 0A000, if
     *     arrays and objects nest deeper than 128
     */
    public static JsonItem parse(String text, Keys keys) throws TurborelException {
        JsonParse reader = new JsonParse(text, keys);
        reader.ws();
        JsonItem item = reader.value(0);
        reader.ws();
        if (reader.at < reader.chars.length) {
            throw reader.invalid("characters follow its value");
        }
        return item;
    }

    private int peek() {
        return at < chars.length ? chars[at] : END;
    }

    private boolean accept(int c) {
        boolean found = peek() == c;
        if (found) {
            at++;
        }
        return found;
    }

    private void ws() {
        while (peek() == ' ' || peek() == '\t' || peek() == '\n' || peek() == '\r') {
            at++;
        }
    }

    private TurborelException invalid(String why) {
        return new TurborelException(
                Failures.DATA_SQLCODE,
                JsonText.INVALID_JSON_TEXT,
                "[ISO/IEC 9075-2:2023 §9.42 GR3a iii 1] the string is not a JSON text: " + why + " (at character "
                        + (at + 1) + ")");
    }

    private static TurborelException outOfRange(String why) {
        return Failures.outOfRange("[ISO/IEC 9075-2:2023 §9.42 GR3a iii 3 A IV] " + why);
    }

    private JsonItem value(int depth) throws TurborelException {
        int c = peek();
        if (c == '{') {
            return object(depth + 1);
        } else if (c == '[') {
            return array(depth + 1);
        } else if (c == '"') {
            return new JsonItem.ScalarItem(new Value.Text(string()));
        } else if (c == '-' || (c >= '0' && c <= '9')) {
            return number();
        } else if (c == 'f') {
            return name("false", new JsonItem.ScalarItem(new Value.Truth(false)));
        } else if (c == 'n') {
            return name("null", JsonItem.NULL);
        } else if (c == 't') {
            return name("true", new JsonItem.ScalarItem(new Value.Truth(true)));
        }
        throw invalid(c == END ? "a value is missing" : "no value begins so");
    }

    private JsonItem name(String word, JsonItem item) throws TurborelException {
        for (int i = 0; i < word.length(); i++) {
            if (!accept(word.charAt(i))) {
                throw invalid("a literal name is none of false, null and true");
            }
        }
        return item;
    }

    private void enter(int depth) throws TurborelException {
        if (depth > JsonBinary.MAX_NESTING) {
            throw Failures.beyond(
                    "[DESIGN] the SQL/JSON item is beyond turborel's limits: arrays and objects nest too deep");
        }
        at++;
        ws();
    }

    private JsonItem array(int depth) throws TurborelException {
        enter(depth);
        List<JsonItem> elements = new ArrayList<>();
        if (accept(']')) {
            return new JsonItem.ArrayItem(elements);
        }
        while (true) {
            elements.add(value(depth));
            ws();
            if (accept(']')) {
                return new JsonItem.ArrayItem(elements);
            }
            if (!accept(',')) {
                throw invalid("a ',' or a ']' follows an array's element");
            }
            ws();
        }
    }

    private JsonItem object(int depth) throws TurborelException {
        enter(depth);
        List<JsonItem.Member> members = new ArrayList<>();
        if (accept('}')) {
            return new JsonItem.ObjectItem(members);
        }
        while (true) {
            members.add(member(depth));
            ws();
            if (accept('}')) {
                break;
            }
            if (!accept(',')) {
                throw invalid("a ',' or a '}' follows an object's member");
            }
            ws();
        }
        unique(members);
        return new JsonItem.ObjectItem(members);
    }

    private JsonItem.Member member(int depth) throws TurborelException {
        if (peek() != '"') {
            throw invalid("a member begins with its name, a string");
        }
        String key = string();
        ws();
        if (!accept(':')) {
            throw invalid("a ':' follows a member's name");
        }
        ws();
        return new JsonItem.Member(key, value(depth));
    }

    private void unique(List<JsonItem.Member> members) throws TurborelException {
        if (keys == Keys.ANY) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (JsonItem.Member member : members) {
            if (!seen.add(member.key())) {
                throw new TurborelException(
                        Failures.DATA_SQLCODE,
                        NON_UNIQUE_KEYS,
                        "[ISO/IEC 9075-2:2023 §9.42 GR3a iii 2] two members of a JSON object have the key \""
                                + member.key() + "\"");
            }
        }
    }

    private String string() throws TurborelException {
        at++;
        StringBuilder string = new StringBuilder();
        while (true) {
            int c = peek();
            if (c < 0) {
                throw invalid("a string is not closed");
            } else if (c == '"') {
                at++;
                return string.toString();
            } else if (c == '\\') {
                at++;
                string.appendCodePoint(escaped());
            } else if (c <= LAST_CONTROL) {
                throw invalid("a control character in a string is not escaped");
            } else {
                at++;
                string.appendCodePoint(c);
            }
        }
    }

    private int escaped() throws TurborelException {
        int c = peek();
        int character = switch (c) {
            case '"', '\\', '/' -> c;
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> UNICODE_ESCAPE;
            default -> Ascii.NONE;
        };
        if (c == END) {
            throw invalid("a string is not closed");
        }
        if (character == Ascii.NONE) {
            throw invalid("an escape is none of \\\" \\\\ \\/ \\b \\f \\n \\r \\t and \\u");
        }
        at++;
        return character == UNICODE_ESCAPE ? codePoint() : character;
    }

    private int codePoint() throws TurborelException {
        int first = hex();
        if (first < HIGH_FIRST || first > HIGH_LAST) {
            if (first >= LOW_FIRST && first <= LOW_LAST) {
                throw invalid("a low surrogate follows no high surrogate");
            }
            return first;
        }
        int second = accept('\\') && accept('u') ? hex() : 0;
        if (second < LOW_FIRST || second > LOW_LAST) {
            throw invalid("no low surrogate follows a high surrogate");
        }
        return SUPPLEMENTARY + ((first - HIGH_FIRST) << SURROGATE_BITS) + (second - LOW_FIRST);
    }

    private int hex() throws TurborelException {
        int value = 0;
        for (int i = 0; i < HEX_DIGITS; i++) {
            int digit = Ascii.hexDigit(peek());
            if (digit == Ascii.NONE) {
                throw invalid("four hexadecimal digits follow \\u");
            }
            at++;
            value = value * HEX + digit;
        }
        return value;
    }

    private JsonItem number() throws TurborelException {
        int start = at;
        boolean negative = accept('-');
        String whole = accept('0') ? "0" : digits("a number's digits are missing");
        String fraction = accept('.') ? digits("a digit follows a number's '.'") : "";
        if (peek() != 'e' && peek() != 'E') {
            return exact(negative, whole, fraction);
        }
        at++;
        if (!accept('+')) {
            accept('-');
        }
        digits("a digit follows a number's exponent");
        return approximate(new String(chars, start, at - start));
    }

    private String digits(String why) throws TurborelException {
        StringBuilder digits = new StringBuilder();
        while (peek() >= '0' && peek() <= '9') {
            digits.appendCodePoint(peek());
            at++;
        }
        if (digits.length() == 0) {
            throw invalid(why);
        }
        return digits.toString();
    }

    /**
     * The exact number of a sign, the digits before and after its point: of at most 18 digits as
     * written, the most Turborel holds a JSON number exact by, exact; else exact without its
     * insignificant zeros if they made it too long; else the decimal floating point number it is.
     */
    static JsonItem exact(boolean negative, String whole, String fraction) throws TurborelException {
        String digits = whole + fraction;
        String significant = stripEnd(fraction);
        String trimmed = stripStart(whole + significant);
        boolean written = fits(digits.length(), fraction.length());
        String units = written ? digits : trimmed;
        int scale = written ? fraction.length() : significant.length();
        if (units.length() <= MAX_EXACT_DIGITS && scale <= MAX_EXACT_DIGITS) {
            BigInteger value = units.isEmpty() ? BigInteger.ZERO : new BigInteger(units);
            return new JsonItem.ScalarItem(new Value.Exact(negative ? value.negate() : value, scale));
        }
        if (stripEnd(trimmed).length() > MAX_DECFLOAT_DIGITS) {
            throw outOfRange("the number has more than " + MAX_DECFLOAT_DIGITS + " significant digits");
        }
        BigDecimal number = new BigDecimal(new BigInteger(digits), fraction.length());
        try {
            return new JsonItem.ScalarItem(Decfloats.canonical(negative ? number.negate() : number));
        } catch (TurborelException outside) {
            throw outOfRange("the number is out of the range of a decimal floating-point number");
        }
    }

    private static boolean fits(int digits, int fraction) {
        return digits <= MAX_EXACT_DIGITS && fraction <= MAX_EXACT_DIGITS;
    }

    private static String stripEnd(String digits) {
        int end = digits.length();
        while (end > 0 && digits.charAt(end - 1) == '0') {
            end--;
        }
        return digits.substring(0, end);
    }

    private static String stripStart(String digits) {
        int start = 0;
        while (start < digits.length() && digits.charAt(start) == '0') {
            start++;
        }
        return digits.substring(start);
    }

    /** The approximate number a number with an exponent is, refused out of a double's range. */
    static JsonItem approximate(String text) throws TurborelException {
        double number = Double.parseDouble(text);
        if (Double.isInfinite(number)) {
            throw outOfRange("the number is too great for an approximate number");
        }
        String mantissa = text.split("[eE]", MANTISSA_AND_EXPONENT)[0];
        if (number == 0 && mantissa.chars().anyMatch(c -> c >= '1' && c <= '9')) {
            throw outOfRange("the number is too small for an approximate number, but not zero");
        }
        return new JsonItem.ScalarItem(new Value.Approximate(number));
    }
}
