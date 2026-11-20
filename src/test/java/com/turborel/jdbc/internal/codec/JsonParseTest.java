package com.turborel.jdbc.internal.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

/** JSON text parsed as Turborel parses it, its tests ported from {@code src/json/text/tests.rs}. */
class JsonParseTest {
    private static JsonItem item(String text) throws TurborelException {
        return JsonParse.parse(text, JsonParse.Keys.ANY);
    }

    private static Value value(String text) throws TurborelException {
        return ((JsonItem.ScalarItem) item(text)).value();
    }

    /** The SQLSTATE {@code text} is refused with, or empty. */
    private static String state(String text) {
        return state(text, JsonParse.Keys.ANY);
    }

    private static String state(String text, JsonParse.Keys keys) {
        try {
            JsonParse.parse(text, keys);
            return "";
        } catch (TurborelException refused) {
            return refused.getSQLState();
        }
    }

    /** {@code text} with each {@code %} a reverse solidus and {@code u}. */
    private static String escapes(String text) {
        return text.replace("%", "\\u");
    }

    private static JsonItem number(long units, long scale) {
        return new JsonItem.ScalarItem(new Value.Exact(BigInteger.valueOf(units), scale));
    }

    private static JsonItem string(String text) {
        return new JsonItem.ScalarItem(new Value.Text(text));
    }

    private static JsonItem truth(boolean truth) {
        return new JsonItem.ScalarItem(new Value.Truth(truth));
    }

    private static JsonItem array(JsonItem... elements) {
        return new JsonItem.ArrayItem(List.of(elements));
    }

    private static JsonItem object(JsonItem.Member... members) {
        return new JsonItem.ObjectItem(List.of(members));
    }

    private static JsonItem.Member member(String key, JsonItem value) {
        return new JsonItem.Member(key, value);
    }

    private static long bits(String text) throws TurborelException {
        return Double.doubleToRawLongBits(((Value.Approximate) value(text)).value());
    }

    /**
     * Verifies: [PROTOCOL JSON values] JSON text is parsed as Turborel parses it: false and true are
     * the truth values, null the SQL/JSON null; a name of another case, cut short or longer is no
     * JSON text (SQLSTATE 22032).
     */
    @Test
    void theLiteralNames() throws TurborelException {
        assertEquals(truth(true), item("true"));
        assertEquals(truth(false), item("false"));
        assertEquals(JsonItem.NULL, item("null"));
        for (String text : List.of("True", "tru", "nul", "fals", "truee", "nulll", "none")) {
            assertEquals("22032", state(text), text);
        }
    }

    /**
     * Verifies: [PROTOCOL JSON values] spaces, tabs, line feeds and carriage returns around a value
     * and its structural characters are insignificant; an empty text, a second value and another
     * white space character, as VT or NBSP, are no JSON text.
     */
    @Test
    void whiteSpaceIsInsignificant() throws TurborelException {
        assertEquals(number(1, 0), item(" \t\r\n1 \t\r\n"));
        assertEquals(array(number(1, 0), number(2, 0)), item(" [ 1 , 2 ] "));
        assertEquals(object(member("a", number(1, 0)), member("b", number(2, 0))), item(" { \"a\" : 1 , \"b\" : 2 } "));
        for (String text : List.of("", "  ", "1 2", "1,", "\u000B1", "\u00A01")) {
            assertEquals("22032", state(text), text);
        }
    }

    /**
     * Verifies: [PROTOCOL JSON values] a number without an exponent is exact, its scale its
     * fraction's digits, so 1.50 keeps scale 2; -0 is 0.
     */
    @Test
    void aNumberWithoutAnExponentIsExact() throws TurborelException {
        assertEquals(number(0, 0), item("0"));
        assertEquals(number(0, 0), item("-0"));
        assertEquals(number(12, 0), item("12"));
        assertEquals(number(-150, 2), item("-1.50"));
        assertEquals(number(1, 3), item("0.001"));
        assertEquals(number(123_456_789_012_345_678L, 0), item("123456789012345678"));
    }

    /**
     * Verifies: [PROTOCOL JSON values] a number with an exponent is the approximate number of its
     * characters, the exponent signed or not, E of either case.
     */
    @Test
    void aNumberWithAnExponentIsApproximate() throws TurborelException {
        assertEquals(Double.doubleToRawLongBits(100.0), bits("1e2"));
        assertEquals(Double.doubleToRawLongBits(-1500.0), bits("-1.5E+3"));
        assertEquals(Double.doubleToRawLongBits(2.5), bits("25e-1"));
        assertEquals(Double.doubleToRawLongBits(0.0), bits("0E0"));
    }

    /**
     * Verifies: [PROTOCOL JSON values] an approximate number too great, or not zero and too small to
     * be other than zero, is SQLSTATE 22003; a zero of any exponent, and a subnormal one, are taken.
     */
    @Test
    void anApproximateNumberOutOfRangeIs22003() throws TurborelException {
        for (String text : List.of("1E400", "-1e400", "1e-400", "-0.5e-400", "[1e-999]")) {
            assertEquals("22003", state(text), text);
        }
        assertEquals(Double.doubleToRawLongBits(0.0), bits("0e-400"));
        assertNotEquals(0, bits("1e-310"));
    }

    /**
     * Verifies: [PROTOCOL JSON values] an exact number of more significant digits than 18 is a
     * decimal floating point number, which holds it exactly; leading zeros and the fraction's
     * trailing zeros are not counted, so a long number of few significant digits stays exact; past
     * 34 significant digits, or past its range, it is SQLSTATE 22003, not rounded.
     */
    @Test
    void anExactNumberOfManyDigitsIsDecimalFloatingPoint() throws TurborelException {
        assertEquals(new Value.Decfloat(new BigInteger("1234567890123456789"), 0), value("1234567890123456789"));
        assertEquals(new Value.Decfloat(BigInteger.ONE.negate(), -21), value("-0.000000000000000000001"));
        assertEquals(number(15, 1).toString(), item("1.5" + "0".repeat(40)).toString());
        assertEquals(number(12, 18), item("0.000000000000000012"));
        assertEquals(number(0, 0), item("0." + "0".repeat(20)));
        String widest = "1" + "0".repeat(32) + "1";
        assertEquals(new Value.Decfloat(new BigInteger(widest), 0), value(widest));
        assertTrue(value("1" + "0".repeat(60)) instanceof Value.Decfloat);
        assertEquals("22003", state("1" + "0".repeat(33) + "1"));
        assertEquals("22003", state("1" + "0".repeat(7000)));
        assertEquals("22003", state("0." + "0".repeat(7000) + "1"));
    }

    /**
     * Verifies: [PROTOCOL JSON values] what is no number: a leading zero, a plus sign, no digit
     * before or after the point or in the exponent, a hexadecimal one, Infinity and NaN.
     */
    @Test
    void whatIsNoNumber() {
        for (String text : List.of(
                "01",
                "-01",
                "+1",
                "-",
                ".5",
                "1.",
                "1.e2",
                "1e",
                "1e+",
                "1E-",
                "0x10",
                "Infinity",
                "NaN",
                "-a",
                "1_000",
                "١")) {
            assertEquals("22032", state(text), text);
        }
    }

    /**
     * Verifies: [PROTOCOL JSON values] a string is its characters with each escape replaced: the
     * two-character escapes, a backslash-u of four hexadecimal digits of either case, and two of
     * them that are a surrogate pair, one character.
     */
    @Test
    void aStringIsItsCharactersUnescaped() throws TurborelException {
        assertEquals(string(""), item("\"\""));
        assertEquals(string("a b"), item("\"a b\""));
        assertEquals(string("\"\\/\b\f\n\r\t"), item("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\""));
        assertEquals(string("Aéé\\"), item(escapes("\"%0041%00e9%00E9%005C\"")));
        assertEquals(string(new String(Character.toChars(0x1D11E))), item(escapes("\"%D834%DD1E\"")));
        assertEquals(string(new String(Character.toChars(0x10FFFF))), item(escapes("\"%DBFF%DFFF\"")));
        String wide = "é" + new String(Character.toChars(0x1D11E)) + "\u007F";
        assertEquals(string(wide), item("\"" + wide + "\""));
        assertEquals(string("a\0b"), item(escapes("\"a%0000b\"")));
        assertEquals(string("\uE000\uFFFF"), item(escapes("\"%E000%FFFF\"")));
    }

    /**
     * Verifies: [PROTOCOL JSON values] what is no string: one not closed, a control character not
     * escaped, an escape of another character or cut short, fewer than four hexadecimal digits or
     * digits not ASCII, a single quotation mark, and a surrogate that is not of a pair.
     */
    @Test
    void whatIsNoString() {
        List<String> texts = List.of(
                "\"a",
                "\"a\\",
                "\"a\nb\"",
                "\"\u001F\"",
                "\"\\a\"",
                "\"%12\"",
                "\"%12G4\"",
                "\"%004",
                "'a'",
                "\"%D834\"",
                "\"%D834x\"",
                "\"%D834\\n\"",
                "\"%D834%0041\"",
                "\"%D834%D834\"",
                "\"%DD1E\"",
                "\"%D834%DD1\"",
                "\"%D834%E000\"",
                "\"%００41\"");
        for (String text : texts) {
            assertEquals("22032", state(escapes(text)), text);
        }
    }

    /**
     * Verifies: [PROTOCOL JSON values] an array is its elements in order, of any types, nested or
     * none; one not closed, with a comma before no element or elements not separated is no JSON
     * text.
     */
    @Test
    void anArrayIsItsElementsInOrder() throws TurborelException {
        assertEquals(array(), item("[]"));
        assertEquals(array(number(1, 0), string("a"), JsonItem.NULL, truth(true)), item("[1,\"a\",null,true]"));
        assertEquals(array(array(number(1, 0)), array()), item("[[1],[]]"));
        for (String text : List.of("[", "[1", "[1,", "[1,]", "[,1]", "[1 2]", "[1;2]", "]", "[1]]")) {
            assertEquals("22032", state(text), text);
        }
    }

    /**
     * Verifies: [PROTOCOL JSON values] an object is its members, each a string key and a value; a
     * key that is no string, a member without its colon or value, a comma before no member and an
     * object not closed are no JSON text.
     */
    @Test
    void anObjectIsItsMembers() throws TurborelException {
        assertEquals(object(), item("{}"));
        assertEquals(
                object(member("a", number(1, 0)), member("b", object(member("c", array())))),
                item("{\"a\":1,\"b\":{\"c\":[]}}"));
        assertEquals(object(member("", JsonItem.NULL)), item("{\"\":null}"));
        assertEquals(object(member("a", number(1, 0))), item(escapes("{\"%0061\":1}")));
        for (String text : List.of(
                "{",
                "{\"a\"",
                "{\"a\":",
                "{\"a\":1",
                "{\"a\":1,",
                "{\"a\":1,}",
                "{a:1}",
                "{1:1}",
                "{\"a\" 1}",
                "{\"a\":1 \"b\":2}",
                "}")) {
            assertEquals("22032", state(text), text);
        }
    }

    /**
     * Verifies: [PROTOCOL JSON values] members of one key are all kept, in the order written, unless
     * keys are to be unique: then SQLSTATE 22037, in a nested object too, keys compared after their
     * escapes are replaced; one key in two objects is unique in each.
     */
    @Test
    void membersOfOneKeyAreKeptOrRefused() throws TurborelException {
        assertEquals(object(member("a", number(1, 0)), member("a", number(2, 0))), item("{\"a\":1,\"a\":2}"));
        JsonParse.Keys unique = JsonParse.Keys.UNIQUE;
        assertEquals("22037", state("{\"a\":1,\"a\":2}", unique));
        assertEquals("22037", state("[{\"b\":{\"a\":1,\"c\":2,\"a\":1}}]", unique));
        assertEquals("22037", state(escapes("{\"a\":1,\"%0061\":2}"), unique));
        assertEquals("", state("{\"a\":1,\"A\":2}", unique));
        assertEquals("", state("[{\"a\":1},{\"a\":1}]", unique));
        assertEquals("", state("{\"a\":{\"a\":1}}", unique));
        assertEquals("", state("{}", unique));
    }

    /**
     * Verifies: [PROTOCOL JSON values] arrays and objects nested 128 deep are parsed, one level
     * deeper refused as past Turborel's limits (SQLCODE -900, SQLSTATE 0A000), whether the deepest is
     * an array or an object.
     */
    @Test
    void nestingIsBounded() throws TurborelException {
        int most = JsonBinary.MAX_NESTING;
        item("[".repeat(most) + "]".repeat(most));
        Fixture.assertBeyond(() -> item("[".repeat(most + 1) + "]".repeat(most + 1)));
        item("{\"k\":".repeat(most) + "1" + "}".repeat(most));
        Fixture.assertBeyond(() -> item("{\"k\":".repeat(most + 1) + "1" + "}".repeat(most + 1)));
        Fixture.assertBeyond(() -> item("[".repeat(most) + "{}" + "]".repeat(most)));
    }

    /**
     * Verifies: [PROTOCOL JSON values] a refusal of a string that is no JSON text names its rule and
     * the character it was refused at, as a data exception (SQLCODE -301); a JSON value is made of
     * its text, its stored form the item's.
     */
    @Test
    void aRefusalNamesItsRuleAndPlace() throws TurborelException {
        TurborelException refused = assertThrows(TurborelException.class, () -> item("[1, x]"));
        assertEquals(-301, refused.sqlcode());
        assertTrue(String.valueOf(refused.getMessage()).startsWith("[ISO/IEC 9075-2:2023 §9.42 GR3a iii 1]"));
        assertTrue(String.valueOf(refused.getMessage()).contains("(at character 5)"), refused.getMessage());
        assertEquals(OctetValue.Json.of(item("{\"a\":[1,\"x\"]}")), OctetValue.Json.ofText("{\"a\":[1,\"x\"]}"));
    }
}
