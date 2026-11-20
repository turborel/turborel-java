package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Writes an SQL/JSON item as a JSON value's stored form, turborel's binary tree, as {@link
 * JsonBinary} lays it out: each exact number in the narrowest of its three forms, and an object's
 * members in the order given, its key directory sorted by key and, of one key, by that order, as
 * turborel writes them ({@code src/json/binary/write.rs}).
 */
public final class JsonBinaryWriter {
    /** The most octets a document holds, turborel's {@code MAX_DOCUMENT_OCTETS}, 256 MiB. */
    public static final int MAX_DOCUMENT_OCTETS = 1 << 28;

    /** The greatest scale of a decimal node, a {@code u8}'s. */
    private static final long DECIMAL_MAX_SCALE = 0xFF;

    /** The low byte of a datetime's zone, an {@code i16} written least significant first. */
    private static final int LOW_BYTE = 0xFF;

    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private JsonBinaryWriter() {}

    /**
     * The stored form of an item.
     *
     * @param item the item
     * @return its stored form
     * @throws TurborelException if arrays and objects nest deeper than {@link
     *     JsonBinary#MAX_NESTING} or the document passes 256 MiB (SQLCODE -900), or a scalar is of
     *     no SQL/JSON scalar's kind or past its field (SQLCODE -910)
     */
    public static byte[] encode(JsonItem item) throws TurborelException {
        return encode(item, MAX_DOCUMENT_OCTETS);
    }

    /**
     * As {@link #encode(JsonItem)}, a document of at most {@code most} octets.
     *
     * @param item the item
     * @param most the most octets the document may hold
     * @return its stored form
     * @throws TurborelException as {@link #encode(JsonItem)}, the document past {@code most}
     */
    static byte[] encode(JsonItem item, int most) throws TurborelException {
        Writer w = new Writer().u8(JsonBinary.FORMAT_VERSION);
        w.raw(node(item, 0));
        byte[] out = w.toByteArray();
        if (out.length > most) {
            throw Failures.beyond(Cite.VALUES + " a JSON value's stored form is longer than 256 MiB");
        }
        return out;
    }

    private static byte[] node(JsonItem item, int depth) throws TurborelException {
        if (depth > JsonBinary.MAX_NESTING) {
            throw Failures.beyond(
                    Cite.VALUES + " a JSON value's arrays and objects nest deeper than " + JsonBinary.MAX_NESTING);
        }
        Writer w = new Writer();
        if (item instanceof JsonItem.ScalarItem scalar) {
            scalar(scalar.value(), w);
        } else if (item instanceof JsonItem.ArrayItem array) {
            array(array.elements(), w, depth + 1);
        } else if (item instanceof JsonItem.ObjectItem object) {
            object(object.members(), w, depth + 1);
        } else {
            w.u8(JsonBinary.NULL);
        }
        return w.toByteArray();
    }

    private static void scalar(Value value, Writer w) throws TurborelException {
        if (value instanceof Value.Text text) {
            w.u8(JsonBinary.STRING).string(text.text());
        } else if (value instanceof Value.Exact exact) {
            exact(exact, w);
        } else if (value instanceof Value.Decfloat decfloat) {
            Value.Decfloat canonical = Decfloats.canonical(decfloat.toBigDecimal());
            w.u8(JsonBinary.DECFLOAT).i128(canonical.coefficient()).bits32(canonical.exponent());
        } else if (value instanceof Value.Approximate approximate) {
            w.u8(JsonBinary.APPROXIMATE).u64(Double.doubleToRawLongBits(approximate.value()));
        } else if (value instanceof Value.Truth truth) {
            w.u8(truth.truth() ? JsonBinary.TRUE : JsonBinary.FALSE);
        } else if (value instanceof Value.Datetime d) {
            w.u8(JsonBinary.DATETIME).u8(d.moment().node()).u64(d.micros());
            w.u8(d.displacement() & LOW_BYTE).u8((d.displacement() >> Byte.SIZE) & LOW_BYTE);
            w.flag(d.zoned()).u8(d.precision());
        } else {
            throw Failures.misuse(Cite.VALUES + " an SQL/JSON scalar is of no scalar's kind: " + value);
        }
    }

    private static void exact(Value.Exact exact, Writer w) throws TurborelException {
        boolean narrow = exact.units().compareTo(LONG_MIN) >= 0 && exact.units().compareTo(LONG_MAX) <= 0;
        if (narrow && exact.scale() == 0) {
            w.u8(JsonBinary.INTEGER).u64(exact.units().longValue());
        } else if (narrow && exact.scale() >= 0 && exact.scale() <= DECIMAL_MAX_SCALE) {
            w.u8(JsonBinary.DECIMAL).u64(exact.units().longValue()).u8((int) exact.scale());
        } else {
            w.u8(JsonBinary.WIDE).i128(exact.units()).u32(exact.scale());
        }
    }

    private static void array(List<JsonItem> elements, Writer w, int depth) throws TurborelException {
        List<byte[]> nodes = new ArrayList<>();
        for (JsonItem element : elements) {
            nodes.add(node(element, depth));
        }
        container(JsonBinary.ARRAY, nodes, offsetsInOrder(nodes), w);
    }

    private static void object(List<JsonItem.Member> members, Writer w, int depth) throws TurborelException {
        List<byte[]> nodes = new ArrayList<>();
        List<Entry> directory = new ArrayList<>();
        int at = 0;
        for (JsonItem.Member member : members) {
            byte[] key = Writer.utf8(member.key());
            byte[] value = node(member.value(), depth);
            byte[] whole = new Writer().bytes(key).raw(value).toByteArray();
            directory.add(new Entry(Octets.owning(key), at));
            nodes.add(whole);
            at += whole.length;
        }
        // A stable sort, so the members of one key stay in the order given.
        directory.sort(Comparator.comparing(e -> e.key().shared(), Arrays::compareUnsigned));
        List<Integer> offsets = new ArrayList<>();
        for (Entry entry : directory) {
            offsets.add(entry.offset());
        }
        container(JsonBinary.OBJECT, nodes, offsets, w);
    }

    private static List<Integer> offsetsInOrder(List<byte[]> nodes) {
        List<Integer> offsets = new ArrayList<>();
        int at = 0;
        for (byte[] node : nodes) {
            offsets.add(at);
            at += node.length;
        }
        return offsets;
    }

    private static void container(int tag, List<byte[]> nodes, List<Integer> offsets, Writer w)
            throws TurborelException {
        long size = 0;
        for (byte[] node : nodes) {
            size += node.length;
        }
        w.u8(tag).bits32(nodes.size()).u32(size);
        for (int offset : offsets) {
            w.bits32(offset);
        }
        for (byte[] node : nodes) {
            w.raw(node);
        }
    }

    /**
     * An entry of an object's directory.
     *
     * @param key the member's key, its UTF-8
     * @param offset where the member begins, from the first member
     */
    private record Entry(Octets key, int offset) {}
}
