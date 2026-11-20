package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a JSON value's stored form, turborel's binary tree (turborel's RULES.md D89, {@code
 * src/json/binary.rs}), checking it whole as it reads: every node within its document, every
 * string UTF-8, every offset an element's or a member's, and every object's key directory sorted.
 * Damaged octets are refused, never read in part.
 *
 * <pre>
 * document:  version u8 (1) | node
 * node:      tag u8 | its body
 *   0 null, 1 false, 2 true        no body
 *   3 integer                      i64, an exact number of scale 0
 *   4 decimal                      units i64 | scale u8
 *   5 wide decimal                 units i128 | scale u32
 *   6 decimal floating point       coefficient i128 | exponent i32
 *   7 approximate                  the bits of an f64
 *   8 string                       length u32 | UTF-8 octets
 *   9 datetime                     moment u8 | utc i64 | zone i16 | zoned u8 | precision u8
 *   10 array                       count u32 | size u32 | count x offset u32 | elements
 *   11 object                      count u32 | size u32 | count x offset u32 | members
 * member:    key length u32 | key UTF-8 | value node
 * </pre>
 *
 * <p>Integers are little-endian. A container's size is the octets of its elements or members
 * after its offsets, and each offset counts from the first of them. An object keeps its members in
 * the order written; its offsets are its key directory, sorted by key, octet by octet, and of one
 * key by the order written.
 */
public final class JsonBinary {
    /** The version of the form, every document's first octet. */
    static final int FORMAT_VERSION = 1;

    /** How deep arrays and objects nest, turborel's {@code MAX_NESTING} for SQL/JSON. */
    public static final int MAX_NESTING = 128;

    // The tags of the nodes, as the class's documentation lists them.
    static final int NULL = 0;
    static final int FALSE = 1;
    static final int TRUE = 2;
    static final int INTEGER = 3;
    static final int DECIMAL = 4;
    static final int WIDE = 5;
    static final int DECFLOAT = 6;
    static final int APPROXIMATE = 7;
    static final int STRING = 8;
    static final int DATETIME = 9;
    static final int ARRAY = 10;
    static final int OBJECT = 11;

    // The widths of a body's fields.
    /** A count, a size, an offset or a string's length. */
    static final int WORD = 4;
    /** An i64, an f64 or a datetime's instant. */
    static final int LONG = 8;
    /** An i128. */
    static final int WIDE_WORD = 16;
    /** A datetime's zone, an i16. */
    static final int SHORT = 2;
    /** A datetime's body: moment, instant, zone, zoned flag and precision. */
    static final int DATETIME_BODY = 1 + LONG + SHORT + 1 + 1;
    /** A container's head after its tag: its count and its size. */
    static final int CONTAINER_HEAD = 2 * WORD;

    /** The shortest document: its version, then a node of a tag alone. */
    private static final int SHORTEST = 2;

    /** No offset yet, before an object's directory's first entry. */
    private static final int NO_OFFSET = Integer.MIN_VALUE;

    private final byte[] doc;

    private JsonBinary(byte[] doc) {
        this.doc = doc;
    }

    /**
     * The item a stored form holds, checked whole.
     *
     * @param stored the stored form
     * @return the item
     * @throws TurborelException if the octets are damaged (SQLCODE -916, SQLSTATE TB916, as the
     *     program's, or the reader makes it a connection failure, as a reply's)
     */
    public static JsonItem decode(byte[] stored) throws TurborelException {
        if (stored.length < SHORTEST || stored[0] != FORMAT_VERSION) {
            throw damaged("it does not begin with its format's version");
        }
        Parsed root = new JsonBinary(stored).node(1, stored.length, 0);
        if (1 + root.length != stored.length) {
            throw damaged("octets follow its root");
        }
        return root.item;
    }

    /**
     * The failure of damaged octets.
     *
     * @param why what is wrong
     * @return the failure
     */
    static TurborelException damaged(String why) {
        return Failures.corrupt(Cite.VALUES + " a JSON value's stored form is damaged: " + why);
    }

    /** The node at {@code at}, within {@code end}, {@code depth} containers in. */
    private Parsed node(int at, int end, int depth) throws TurborelException {
        if (depth > MAX_NESTING) {
            throw damaged("arrays and objects nest deeper than " + MAX_NESTING);
        }
        within(at, 1, end);
        int body = at + 1;
        return switch (doc[at]) {
            case NULL -> new Parsed(JsonItem.NULL, 1);
            case FALSE, TRUE -> scalar(new Value.Truth(doc[at] == TRUE), 0);
            case INTEGER -> scalar(new Value.Exact(BigInteger.valueOf(i64(body, end)), 0), LONG);
            case DECIMAL -> scalar(new Value.Exact(BigInteger.valueOf(i64(body, end)), u8(body + LONG, end)), LONG + 1);
            case WIDE -> scalar(wide(body, end), WIDE_WORD + WORD);
            case DECFLOAT -> scalar(decfloat(i128(body, end), i32(body + WIDE_WORD, end)), WIDE_WORD + WORD);
            case APPROXIMATE -> scalar(new Value.Approximate(Double.longBitsToDouble(i64(body, end))), LONG);
            case STRING -> scalar(new Value.Text(string(body, end)), WORD + utf8Length(body));
            case DATETIME -> scalar(datetime(body, end), DATETIME_BODY);
            case ARRAY -> array(at, end, depth);
            case OBJECT -> object(at, end, depth);
            default -> throw damaged("a node's tag " + doc[at] + " is no kind's");
        };
    }

    private Value wide(int body, int end) throws TurborelException {
        BigInteger units = i128(body, end);
        return new Value.Exact(units, Integer.toUnsignedLong(i32(body + WIDE_WORD, end)));
    }

    private static Parsed scalar(Value value, int body) {
        return new Parsed(new JsonItem.ScalarItem(value), 1 + body);
    }

    private Parsed array(int at, int end, int depth) throws TurborelException {
        Container c = container(at, end);
        List<JsonItem> elements = new ArrayList<>();
        int position = 0;
        for (int i = 0; i < c.count; i++) {
            if (u32(c.offsets + i * WORD, end) != position) {
                throw damaged("an element's offset is not where it is");
            }
            Parsed element = node(c.first + position, c.bodyEnd, depth + 1);
            elements.add(element.item);
            position += element.length;
        }
        if (position != c.size) {
            throw damaged("an array's size is not its elements'");
        }
        return new Parsed(new JsonItem.ArrayItem(elements), c.bodyEnd - at);
    }

    private Parsed object(int at, int end, int depth) throws TurborelException {
        Container c = container(at, end);
        List<JsonItem.Member> members = new ArrayList<>();
        Map<Integer, byte[]> keys = new HashMap<>();
        int position = 0;
        for (int i = 0; i < c.count; i++) {
            int start = c.first + position;
            String key = string(start, c.bodyEnd);
            int keyLength = utf8Length(start);
            Parsed value = node(start + WORD + keyLength, c.bodyEnd, depth + 1);
            keys.put(position, Arrays.copyOfRange(doc, start + WORD, start + WORD + keyLength));
            members.add(new JsonItem.Member(key, value.item));
            position += WORD + keyLength + value.length;
        }
        if (position != c.size) {
            throw damaged("an object's size is not its members'");
        }
        directory(c, keys, end);
        return new Parsed(new JsonItem.ObjectItem(members), c.bodyEnd - at);
    }

    /** That an object's offsets list each member once, sorted by key, then by place. */
    private void directory(Container c, Map<Integer, byte[]> keys, int end) throws TurborelException {
        byte[] previousKey = null;
        int previousOffset = NO_OFFSET;
        for (int i = 0; i < c.count; i++) {
            int offset = u32(c.offsets + i * WORD, end);
            byte[] key = keys.get(offset);
            if (key == null || (previousKey != null && !before(previousKey, previousOffset, key, offset))) {
                throw damaged("an object's key directory is not sorted");
            }
            previousKey = key;
            previousOffset = offset;
        }
    }

    private static boolean before(byte[] key, int offset, byte[] next, int nextOffset) {
        int order = Arrays.compareUnsigned(key, next);
        return order < 0 || (order == 0 && offset < nextOffset);
    }

    /** A container's head, offsets and body, checked to lie within {@code end}. */
    private Container container(int at, int end) throws TurborelException {
        long count = Integer.toUnsignedLong(i32(at + 1, end));
        long size = Integer.toUnsignedLong(i32(at + 1 + WORD, end));
        long offsets = (long) at + 1 + CONTAINER_HEAD;
        long first = offsets + count * WORD;
        if (first > end || first + size > end) {
            throw damaged("a container runs past its document");
        }
        return new Container((int) count, (int) size, (int) offsets, (int) first, (int) (first + size));
    }

    private Value decfloat(BigInteger coefficient, int exponent) throws TurborelException {
        long scale = -(long) exponent;
        if (scale > Integer.MAX_VALUE) {
            throw damaged("a decimal floating point number is out of range");
        }
        BigDecimal rounded = new BigDecimal(coefficient, (int) scale)
                .round(new MathContext(Decfloats.MAX_DIGITS, RoundingMode.HALF_EVEN));
        try {
            return Decfloats.canonical(rounded);
        } catch (TurborelException outOfRange) {
            throw damaged("a decimal floating point number is out of range");
        }
    }

    private Value datetime(int body, int end) throws TurborelException {
        Value.Moment moment = Value.Moment.ofNode(u8(body, end));
        if (moment == null) {
            throw damaged("a datetime's moment is no moment");
        }
        long utc = i64(body + 1, end);
        short zone = (short) (u8(body + 1 + LONG, end) | u8(body + 1 + LONG + 1, end) << Byte.SIZE);
        int zoned = u8(body + 1 + LONG + SHORT, end);
        if (zoned > 1) {
            throw damaged("a datetime's zoned flag is neither 0 nor 1");
        }
        int precision = u8(body + 1 + LONG + SHORT + 1, end);
        return new Value.Datetime(moment, zoned == 1, precision, zone, utc);
    }

    private String string(int at, int end) throws TurborelException {
        int length = utf8LengthWithin(at, end);
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(doc, at + WORD, length))
                    .toString();
        } catch (CharacterCodingException e) {
            throw damaged("a string is not UTF-8");
        }
    }

    private int utf8LengthWithin(int at, int end) throws TurborelException {
        long length = Integer.toUnsignedLong(i32(at, end));
        if ((long) at + WORD + length > end) {
            throw damaged("a string runs past its document");
        }
        return (int) length;
    }

    /** A string's length at {@code at}, already checked by {@link #string}. */
    private int utf8Length(int at) {
        return ByteBuffer.wrap(doc, at, WORD).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private void within(int at, int width, int end) throws TurborelException {
        if (at > end - width) {
            throw damaged("a node runs past its document");
        }
    }

    private int u8(int at, int end) throws TurborelException {
        within(at, 1, end);
        return Byte.toUnsignedInt(doc[at]);
    }

    private int i32(int at, int end) throws TurborelException {
        within(at, WORD, end);
        return ByteBuffer.wrap(doc, at, WORD).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private int u32(int at, int end) throws TurborelException {
        return i32(at, end);
    }

    private long i64(int at, int end) throws TurborelException {
        within(at, LONG, end);
        return ByteBuffer.wrap(doc, at, LONG).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private BigInteger i128(int at, int end) throws TurborelException {
        within(at, WIDE_WORD, end);
        byte[] big = new byte[WIDE_WORD];
        for (int i = 0; i < WIDE_WORD; i++) {
            big[i] = doc[at + WIDE_WORD - 1 - i];
        }
        return new BigInteger(big);
    }

    /** A node read and the octets it takes, its tag included. */
    private record Parsed(JsonItem item, int length) {}

    /** A container's count, size, and where its offsets, first element and end are. */
    private record Container(int count, int size, int offsets, int first, int bodyEnd) {}
}
