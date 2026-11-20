package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.math.BigDecimal;
import java.util.List;

/**
 * The JSON text of an SQL/JSON item, written as Turborel writes it ({@code src/json/serialize.rs}):
 * no insignificant white space; an object's members in the order the item keeps them; a string's
 * quotation marks and reverse solidi as {@code \"} and {@code \\}, the control characters U+0000
 * to U+001F as the two-character escape RFC 8259 has of one, else as a reverse solidus, {@code u}
 * and four lower case hexadecimal digits, and no other character escaped; an exact number as its digits and its
 * scale's; a decimal floating point number plain, with no exponent; an approximate number as a
 * mantissa with a point, {@code E} and an exponent; true, false and null as their names.
 *
 * <p>No JSON text parses to a datetime, nor to an approximate number that is not finite, so an
 * item that is or holds one has no text: SQLSTATE 22032, as Turborel gives.
 */
public final class JsonText {
    /** The SQLCODE of a data exception, Turborel's -301. */
    public static final int DATA_SQLCODE = -301;

    /** The SQLSTATE of an item with no JSON text: invalid JSON text. */
    public static final String INVALID_JSON_TEXT = "22032";

    /** The last of the control characters, which are escaped. */
    private static final char LAST_CONTROL = 0x1F;

    private JsonText() {}

    /**
     * The JSON text of an item.
     *
     * @param item the item
     * @return its text
     * @throws TurborelException if it is or holds a datetime or an approximate number not finite
     *     (SQLSTATE 22032), or nests deeper than {@link JsonBinary#MAX_NESTING} (SQLCODE -900)
     */
    public static String of(JsonItem item) throws TurborelException {
        StringBuilder out = new StringBuilder();
        write(item, out, 0);
        return out.toString();
    }

    private static void write(JsonItem item, StringBuilder out, int depth) throws TurborelException {
        if (depth > JsonBinary.MAX_NESTING) {
            throw Failures.beyond(
                    Cite.VALUES + " a JSON value's arrays and objects nest deeper than " + JsonBinary.MAX_NESTING);
        }
        if (item instanceof JsonItem.ScalarItem scalar) {
            scalar(scalar.value(), out);
        } else if (item instanceof JsonItem.ArrayItem array) {
            elements(array.elements(), out, depth + 1);
        } else if (item instanceof JsonItem.ObjectItem object) {
            members(object.members(), out, depth + 1);
        } else {
            out.append("null");
        }
    }

    private static void elements(List<JsonItem> elements, StringBuilder out, int depth) throws TurborelException {
        out.append('[');
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            write(elements.get(i), out, depth);
        }
        out.append(']');
    }

    private static void members(List<JsonItem.Member> members, StringBuilder out, int depth) throws TurborelException {
        out.append('{');
        for (int i = 0; i < members.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            string(members.get(i).key(), out);
            out.append(':');
            write(members.get(i).value(), out, depth);
        }
        out.append('}');
    }

    private static void scalar(Value value, StringBuilder out) throws TurborelException {
        if (value instanceof Value.Text text) {
            string(text.text(), out);
        } else if (value instanceof Value.Truth truth) {
            out.append(truth.truth());
        } else if (value instanceof Value.Exact exact) {
            out.append(exact.toBigDecimal().toPlainString());
        } else if (value instanceof Value.Decfloat decfloat) {
            out.append(decfloat.toBigDecimal().toPlainString());
        } else if (value instanceof Value.Approximate approximate) {
            out.append(approximate(approximate.value()));
        } else {
            throw noText("a " + (value instanceof Value.Datetime ? "datetime" : "value of no SQL/JSON scalar's kind"));
        }
    }

    /** An approximate number as Turborel's CAST writes it: {@code 1.5E0}, {@code 1.0E2}, {@code 0E0}. */
    private static String approximate(double x) throws TurborelException {
        if (!Double.isFinite(x)) {
            throw noText("an approximate number that is not finite");
        }
        if (x == 0) {
            return "0E0";
        }
        BigDecimal exact = new BigDecimal(Double.toString(x)).stripTrailingZeros();
        int exponent = exact.precision() - exact.scale() - 1;
        BigDecimal mantissa = exact.movePointLeft(exponent);
        String digits = mantissa.toPlainString();
        return (digits.contains(".") ? digits : digits + ".0") + "E" + exponent;
    }

    private static void string(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c <= LAST_CONTROL ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        out.append('"');
    }

    private static TurborelException noText(String what) {
        return new TurborelException(DATA_SQLCODE, INVALID_JSON_TEXT, Cite.VALUES + " no JSON text parses to " + what);
    }
}
