package com.turborel.jdbc.internal.codec;

import static com.turborel.jdbc.internal.codec.Fixture.assertBeyond;
import static com.turborel.jdbc.internal.codec.Fixture.assertMisuse;
import static com.turborel.jdbc.internal.codec.Fixture.cat;
import static com.turborel.jdbc.internal.codec.Fixture.hex;
import static com.turborel.jdbc.internal.codec.Fixture.i128;
import static com.turborel.jdbc.internal.codec.Fixture.str;
import static com.turborel.jdbc.internal.codec.Fixture.u32;
import static com.turborel.jdbc.internal.codec.Fixture.u64;
import static com.turborel.jdbc.internal.codec.Fixture.u8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.turborel.jdbc.TurborelException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** JSON values' stored form, the binary tree of Turborel's RULES.md D89 ([PROTOCOL Values]). */
class JsonBinaryTest {
    /** A fixed seed, so the generated documents are the same on every run (RULES.md R-TEST-5). */
    private static final long SEED = 89;

    private static JsonItem scalar(Value value) {
        return new JsonItem.ScalarItem(value);
    }

    private static JsonItem exact(long units, long scale) {
        return scalar(new Value.Exact(BigInteger.valueOf(units), scale));
    }

    /**
     * Verifies: [PROTOCOL Values] a document is its version octet, 1, then its root node; nodes 0
     * null, 1 false, 2 true, 7 approximate and 8 string, each read back as written.
     */
    @Test
    void theSimpleNodes() throws TurborelException {
        assertArrayEquals(hex("0100"), JsonBinaryWriter.encode(JsonItem.NULL));
        assertArrayEquals(hex("0101"), JsonBinaryWriter.encode(scalar(new Value.Truth(false))));
        assertArrayEquals(hex("0102"), JsonBinaryWriter.encode(scalar(new Value.Truth(true))));
        byte[] approximate = cat(u8(1), u8(7), u64(Double.doubleToRawLongBits(-0.0)));
        assertArrayEquals(approximate, JsonBinaryWriter.encode(scalar(new Value.Approximate(-0.0))));
        byte[] string = cat(u8(1), u8(8), str("é"));
        assertArrayEquals(string, JsonBinaryWriter.encode(scalar(new Value.Text("é"))));
        for (byte[] doc : List.of(hex("0100"), hex("0101"), hex("0102"), approximate, string)) {
            assertArrayEquals(doc, JsonBinaryWriter.encode(JsonBinary.decode(doc)));
        }
    }

    /**
     * Verifies: [PROTOCOL Values] an exact number is written in the narrowest of nodes 3 integer
     * (i64, scale 0), 4 decimal (i64, scale u8) and 5 wide (i128, scale u32), and node 6 a
     * decimal floating point number, coefficient i128 and exponent i32.
     */
    @Test
    void numbersTakeTheNarrowestNode() throws TurborelException {
        assertArrayEquals(cat(u8(1), u8(3), u64(-7)), JsonBinaryWriter.encode(exact(-7, 0)));
        assertArrayEquals(cat(u8(1), u8(4), u64(150), u8(2)), JsonBinaryWriter.encode(exact(150, 2)));
        assertArrayEquals(cat(u8(1), u8(5), i128(BigInteger.ONE), u32(256)), JsonBinaryWriter.encode(exact(1, 256)));
        BigInteger wide = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        JsonItem big = scalar(new Value.Exact(wide, 0));
        assertArrayEquals(cat(u8(1), u8(5), i128(wide), u32(0)), JsonBinaryWriter.encode(big));
        BigInteger under = BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE);
        assertArrayEquals(
                cat(u8(1), u8(5), i128(under), u32(3)), JsonBinaryWriter.encode(scalar(new Value.Exact(under, 3))));
        JsonItem decfloat = scalar(new Value.Decfloat(BigInteger.valueOf(150), -2));
        assertArrayEquals(cat(u8(1), u8(6), i128(BigInteger.valueOf(15)), u32(-1)), JsonBinaryWriter.encode(decfloat));
        assertEquals(
                scalar(new Value.Decfloat(BigInteger.valueOf(15), -1)),
                JsonBinary.decode(JsonBinaryWriter.encode(decfloat)));
        assertEquals(exact(150, 2), JsonBinary.decode(cat(u8(1), u8(4), u64(150), u8(2))));
        assertEquals(big, JsonBinary.decode(cat(u8(1), u8(5), i128(wide), u32(0))));
    }

    /**
     * Verifies: [PROTOCOL JSON values] the page's examples, byte for byte: {"a":[1,"x"]} is 51
     * octets; in {"b":1,"a":2} the members stay in that order and the directory puts a first; a
     * decimal keeps the scale it was written with, 1.50 units 150 and scale 2, in JSON text too.
     */
    @Test
    void theExamplesOfThePage() throws TurborelException {
        JsonItem nested = new JsonItem.ObjectItem(List.of(
                new JsonItem.Member("a", new JsonItem.ArrayItem(List.of(exact(1, 0), scalar(new Value.Text("x")))))));
        byte[] page = hex("01" + "0B" + "01000000" + "25000000" + "00000000" + "0100000061" + "0A" + "02000000"
                + "0F000000" + "00000000" + "09000000" + "03" + "0100000000000000" + "08" + "0100000078");
        assertEquals(51, page.length);
        assertArrayEquals(page, JsonBinaryWriter.encode(nested));
        assertEquals(nested, JsonBinary.decode(page));
        JsonItem ordered = new JsonItem.ObjectItem(
                List.of(new JsonItem.Member("b", exact(1, 0)), new JsonItem.Member("a", exact(2, 0))));
        byte[] written = JsonBinaryWriter.encode(ordered);
        assertArrayEquals(hex("0E000000" + "00000000"), java.util.Arrays.copyOfRange(written, 10, 18));
        assertEquals(ordered, JsonBinary.decode(written));
        assertEquals("{\"b\":1,\"a\":2}", JsonText.of(ordered));
        assertArrayEquals(cat(u8(1), u8(4), u64(150), u8(2)), JsonBinaryWriter.encode(exact(150, 2)));
        assertEquals("1.50", JsonText.of(exact(150, 2)));
    }

    /**
     * Verifies: [PROTOCOL JSON values] a document is at most 256 MiB: one longer is refused as
     * beyond the driver's limits (SQLCODE -900, SQLSTATE 0A000), here at a limit of a few octets.
     */
    @Test
    void aDocumentIsBounded() throws TurborelException {
        assertEquals(1 << 28, JsonBinaryWriter.MAX_DOCUMENT_OCTETS);
        JsonItem string = scalar(new Value.Text("twelve chars"));
        assertEquals(18, JsonBinaryWriter.encode(string, 18).length);
        assertBeyond(() -> JsonBinaryWriter.encode(string, 17));
    }

    /**
     * Verifies: [PROTOCOL Values] node 9, a datetime: moment, the instant in UTC i64, the zone's
     * minutes i16, zoned and precision.
     */
    @Test
    void theDatetimeNode() throws TurborelException {
        Value.Datetime d = new Value.Datetime(Value.Moment.TIMESTAMP, true, 3, (short) -300, 1_000_000L);
        byte[] doc = cat(u8(1), u8(9), u8(2), u64(1_000_000L), hex("D4FE"), u8(1), u8(3));
        assertArrayEquals(doc, JsonBinaryWriter.encode(scalar(d)));
        assertEquals(scalar(d), JsonBinary.decode(doc));
        assertMisuse(() -> JsonBinary.decode(cat(u8(1), u8(9), u8(3), u64(0), hex("0000"), u8(0), u8(0))));
        assertMisuse(() -> JsonBinary.decode(cat(u8(1), u8(9), u8(0), u64(0), hex("0000"), u8(2), u8(0))));
    }

    /**
     * Verifies: [PROTOCOL Values] nodes 10 and 11: an array, count, size, an offset of each
     * element, then the elements; an object, its offsets a key directory sorted by key octets and
     * of one key in written order, its members kept in written order.
     */
    @Test
    void arraysAndObjects() throws TurborelException {
        JsonItem array = new JsonItem.ArrayItem(List.of(exact(1, 0), scalar(new Value.Text("x"))));
        byte[] arrayDoc = cat(u8(1), u8(10), u32(2), u32(15), u32(0), u32(9), u8(3), u64(1), u8(8), str("x"));
        assertArrayEquals(arrayDoc, JsonBinaryWriter.encode(array));
        assertEquals(array, JsonBinary.decode(arrayDoc));
        List<JsonItem.Member> members = List.of(
                new JsonItem.Member("z", JsonItem.NULL),
                new JsonItem.Member("a", exact(1, 0)),
                new JsonItem.Member("a", JsonItem.NULL));
        JsonItem object = new JsonItem.ObjectItem(members);
        byte[] objectDoc = cat(
                u8(1), u8(11), u32(3), u32(26), u32(6), u32(20), u32(0), str("z"), u8(0), str("a"), u8(3), u64(1),
                str("a"), u8(0));
        assertArrayEquals(objectDoc, JsonBinaryWriter.encode(object));
        assertEquals(object, JsonBinary.decode(objectDoc));
        assertArrayEquals(hex("010A0000000000000000"), JsonBinaryWriter.encode(new JsonItem.ArrayItem(List.of())));
    }

    /**
     * Verifies: [PROTOCOL Values] a damaged document is refused, never read in part: every cut,
     * another version, a tag of no node, octets after the root, an offset not where its element
     * is, a size not its contents', an unsorted directory, a string not UTF-8.
     */
    @Test
    void damagedDocumentsAreRefused() throws TurborelException {
        byte[] good = JsonBinaryWriter.encode(new JsonItem.ObjectItem(List.of(
                new JsonItem.Member("a", new JsonItem.ArrayItem(List.of(exact(1, 0), scalar(new Value.Text("x"))))),
                new JsonItem.Member("b", scalar(new Value.Truth(true))))));
        for (int cut = 0; cut < good.length; cut++) {
            byte[] prefix = Arrays.copyOf(good, cut);
            assertMisuse(() -> JsonBinary.decode(prefix));
        }
        assertMisuse(() -> JsonBinary.decode(cat(good, u8(0))));
        assertMisuse(() -> JsonBinary.decode(hex("0200")));
        assertMisuse(() -> JsonBinary.decode(hex("010C")));
        assertMisuse(() -> JsonBinary.decode(cat(u8(1), u8(10), u32(1), u32(1), u32(1), u8(0))));
        assertMisuse(() -> JsonBinary.decode(cat(u8(1), u8(10), u32(1), u32(2), u32(0), u8(0), u8(0))));
        assertMisuse(() -> JsonBinary.decode(cat(u8(1), u8(11), u32(1), u32(6), u32(0), str("a"), u8(0), u8(0))));
        byte[] unsorted = cat(u8(1), u8(11), u32(2), u32(12), u32(0), u32(6), str("b"), u8(0), str("a"), u8(0));
        assertMisuse(() -> JsonBinary.decode(unsorted));
        byte[] stray = cat(u8(1), u8(11), u32(1), u32(6), u32(1), str("a"), u8(0));
        assertMisuse(() -> JsonBinary.decode(stray));
        assertMisuse(() -> JsonBinary.decode(cat(u8(1), u8(8), u32(1), hex("FF"))));
        assertMisuse(() -> JsonBinary.decode(cat(u8(1), u8(8), u32(5), hex("41"))));
        assertMisuse(() -> JsonBinary.decode(cat(u8(1), u8(10), u32(-1), u32(0))));
        assertMisuse(() -> JsonBinary.decode(cat(u8(1), u8(6), i128(BigInteger.ONE), u32(Integer.MIN_VALUE))));
        assertMisuse(() -> JsonBinary.decode(cat(u8(1), u8(6), i128(BigInteger.ONE), u32(7000))));
    }

    /**
     * Verifies: [PROTOCOL Values] a decimal floating point node of more than 34 digits reads as
     * Turborel reads it, rounded half to even to 34.
     */
    @Test
    void aWideDecfloatNodeIsRounded() throws TurborelException {
        BigInteger digits35 = BigInteger.TEN.pow(34).add(BigInteger.valueOf(5));
        JsonItem read = JsonBinary.decode(cat(u8(1), u8(6), i128(digits35), u32(0)));
        assertEquals(scalar(new Value.Decfloat(BigInteger.ONE, 34)), read);
    }

    /**
     * Verifies: [PROTOCOL Values] arrays and objects nest at most 128 deep: a deeper item is
     * refused as it is written, and a deeper document as it is read.
     */
    @Test
    void nestingIsBounded() throws TurborelException {
        JsonItem within = JsonItem.NULL;
        for (int i = 0; i < JsonBinary.MAX_NESTING; i++) {
            within = new JsonItem.ArrayItem(List.of(within));
        }
        byte[] doc = JsonBinaryWriter.encode(within);
        assertEquals(within, JsonBinary.decode(doc));
        JsonItem deeper = new JsonItem.ArrayItem(List.of(within));
        assertBeyond(() -> JsonBinaryWriter.encode(deeper));
        assertBeyond(() -> JsonText.of(deeper));
        byte[] tooDeep =
                cat(u8(1), u8(10), u32(1), u32(doc.length - 1), u32(0), Arrays.copyOfRange(doc, 1, doc.length));
        assertMisuse(() -> JsonBinary.decode(tooDeep));
    }

    /**
     * Verifies: [PROTOCOL Values] only an SQL/JSON scalar's kind is written as a scalar node; a
     * value of another kind is refused.
     */
    @Test
    void aScalarOfNoScalarKindIsRefused() {
        assertMisuse(() -> JsonBinaryWriter.encode(scalar(Value.NULL)));
        assertMisuse(() -> JsonBinaryWriter.encode(scalar(new Value.Xml("<a/>"))));
    }

    /**
     * Verifies: [PROTOCOL Values] every item written reads back as itself, over items generated
     * from a fixed seed; and every document with one octet flipped is refused with the driver's
     * error or reads as some item, never another exception.
     */
    @Test
    void generatedItemsReadBackAndFlippedOctetsNeverEscape() throws TurborelException {
        Random random = new Random(SEED);
        for (int round = 0; round < 200; round++) {
            JsonItem item = generated(random, 0);
            byte[] doc = JsonBinaryWriter.encode(item);
            assertEquals(item, JsonBinary.decode(doc));
            for (int at = 0; at < doc.length; at++) {
                byte[] flipped = doc.clone();
                flipped[at] ^= (byte) (1 + random.nextInt(255));
                try {
                    JsonBinary.decode(flipped);
                } catch (TurborelException refused) {
                    assertEquals(Failures.MISUSE_SQLCODE, refused.sqlcode());
                }
            }
        }
    }

    private static JsonItem generated(Random random, int depth) {
        int kind = random.nextInt(depth > 3 ? 6 : 8);
        return switch (kind) {
            case 0 -> JsonItem.NULL;
            case 1 -> scalar(new Value.Truth(random.nextBoolean()));
            case 2 -> exact(random.nextLong(), random.nextInt(4) == 0 ? random.nextInt(300) : 0);
            case 3 -> scalar(new Value.Approximate(random.nextDouble()));
            case 4 -> scalar(new Value.Text(Integer.toString(random.nextInt(), Character.MAX_RADIX)));
            case 5 ->
                scalar(new Value.Decfloat(
                        BigInteger.valueOf(1 + random.nextInt(999) * 10L + 1), random.nextInt(20) - 10));
            case 6 -> {
                List<JsonItem> elements = new ArrayList<>();
                for (int i = random.nextInt(4); i > 0; i--) {
                    elements.add(generated(random, depth + 1));
                }
                yield new JsonItem.ArrayItem(elements);
            }
            default -> {
                List<JsonItem.Member> members = new ArrayList<>();
                for (int i = random.nextInt(4); i > 0; i--) {
                    members.add(new JsonItem.Member(
                            Character.toString('a' + random.nextInt(3)), generated(random, depth + 1)));
                }
                yield new JsonItem.ObjectItem(members);
            }
        };
    }
}
