package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Reads values, and writes rows of them: a tag, then its body ([PROTOCOL Values]); a row, its
 * count, then each value ([PROTOCOL Composite fields]).
 */
public final class ValueCodec {
    /** The greatest count of a multiset's elements, turborel's {@code MAX_MULTISET_CARDINALITY}. */
    public static final long MAX_MULTISET_CARDINALITY = 65_535;

    /** The mask of a displacement's low half, the {@code i16}'s bits. */
    private static final long LOW_HALF = 0xFFFF;

    private ValueCodec() {}

    /**
     * Writes a row, or an Execute's values: their count, then each.
     *
     * @param w where it is written
     * @param values the values
     * @throws TurborelException if a value is past what its field holds
     */
    public static void writeRow(Writer w, List<Value> values) throws TurborelException {
        w.bits32(values.size());
        for (Value each : values) {
            each.encode(w);
        }
    }

    /**
     * A row read: its count, then each value.
     *
     * @param r where it is read
     * @return the values
     * @throws TurborelException if a value is malformed
     */
    public static List<Value> readRow(Reader r) throws TurborelException {
        return r.list(ValueCodec::read);
    }

    /**
     * A value read.
     *
     * @param r where it is read
     * @return the value
     * @throws TurborelException if it is malformed: a tag of no value, a body out of range, parts
     *     nested too deep, or the body cut short
     */
    public static Value read(Reader r) throws TurborelException {
        int tag = r.u8();
        return switch (tag) {
            case Tags.NULL -> Value.NULL;
            case Tags.CHARACTER -> new Value.Text(r.string());
            case Tags.EXACT -> new Value.Exact(r.i128(), r.u32());
            case Tags.APPROXIMATE -> new Value.Approximate(Double.longBitsToDouble(r.u64()));
            case Tags.PLUGIN -> new OctetValue.Plugin(r.string(), r.string(), r.octets());
            case Tags.BINARY -> new OctetValue.Binary(r.octets());
            case Tags.DATE -> datetime(Value.Moment.DATE, r);
            case Tags.TIME -> datetime(Value.Moment.TIME, r);
            case Tags.TIMESTAMP -> datetime(Value.Moment.TIMESTAMP, r);
            case Tags.INTERVAL -> new Value.Interval(Qualifier.read(r), r.i128());
            case Tags.BOOLEAN -> new Value.Truth(r.flag());
            case Tags.DECFLOAT -> decfloat(r);
            default -> more(tag, r);
        };
    }

    /** The values of tags 16 and later. */
    private static Value more(int tag, Reader r) throws TurborelException {
        return switch (tag) {
            case Tags.ARRAY -> r.nested(n -> new Value.Array(readRow(n)));
            case Tags.ROW -> r.nested(n -> new Value.Row(readRow(n)));
            case Tags.MULTISET -> r.nested(ValueCodec::multiset);
            case Tags.STRUCTURED -> r.nested(n -> new Value.Structured(TypeName.read(n), readRow(n)));
            case Tags.LOB_WHOLE -> whole(r);
            case Tags.LOB_LOCATOR -> new Lob.Locator(LobKind.read(r), r.u32(), r.u64(), r.u64());
            case Tags.REFERENCE -> new OctetValue.Reference(r.octets());
            case Tags.JSON -> json(r);
            case Tags.XML -> new Value.Xml(r.string());
            case Tags.UUID -> uuid(r);
            default -> throw r.malformed(Cite.VALUES, "no value has tag " + tag);
        };
    }

    private static Value datetime(Value.Moment moment, Reader r) throws TurborelException {
        boolean zoned = r.flag();
        int precision = r.u8();
        long displacement = r.u32();
        if (displacement > LOW_HALF) {
            throw r.malformed(Cite.VALUES, "a displacement fills more than the low half of its u32");
        }
        return new Value.Datetime(moment, zoned, precision, (short) displacement, r.u64());
    }

    private static Value decfloat(Reader r) throws TurborelException {
        BigInteger coefficient = r.i128();
        int exponent = r.bits32();
        if (!Decfloats.isCanonical(coefficient, exponent)) {
            throw r.malformed(Cite.VALUES, "a DECFLOAT value is not in its canonical form");
        }
        return new Value.Decfloat(coefficient, exponent);
    }

    private static Value multiset(Reader r) throws TurborelException {
        List<Value> elements = readRow(r);
        if (elements.size() > MAX_MULTISET_CARDINALITY) {
            throw r.malformed(Cite.VALUES, "a multiset has more than " + MAX_MULTISET_CARDINALITY + " elements");
        }
        return new Value.Multiset(elements);
    }

    private static Value whole(Reader r) throws TurborelException {
        LobKind kind = LobKind.read(r);
        byte[] octets = r.bytes();
        if (kind == LobKind.CHARACTER && !isUtf8(octets)) {
            throw r.malformed(Cite.VALUES, "a character large object is not UTF-8");
        }
        return new Lob.Whole(kind, Octets.owning(octets));
    }

    private static Value json(Reader r) throws TurborelException {
        byte[] stored = r.bytes();
        try {
            return OctetValue.Json.ofStored(stored);
        } catch (TurborelException damaged) {
            throw r.malformed(Cite.VALUES, String.valueOf(damaged.getMessage()));
        }
    }

    private static Value uuid(Reader r) throws TurborelException {
        byte[] octets = r.bytes();
        if (octets.length != Uuids.OCTETS) {
            throw r.malformed(Cite.VALUES, "a UUID is 16 octets, not " + octets.length);
        }
        return new Value.Uuid(Uuids.of(octets));
    }

    private static boolean isUtf8(byte[] octets) {
        try {
            StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(octets));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
