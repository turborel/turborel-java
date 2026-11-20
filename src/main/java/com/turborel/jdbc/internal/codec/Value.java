package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A value, as a row, an Execute or a CopyData carries it: a tag, then its body ([PROTOCOL
 * Values]). A value's tag says how it is written, which is not always its column type's tag.
 *
 * <p>Each kind writes itself ({@link #encode(Writer)}); {@link ValueCodec} reads them. The values whose
 * body is octets are in {@link OctetValue}, and the large objects in {@link Lob}.
 */
public sealed interface Value
        permits Value.Null,
                Value.Text,
                Value.Exact,
                Value.Approximate,
                Value.Datetime,
                Value.Interval,
                Value.Truth,
                Value.Decfloat,
                Value.Array,
                Value.Row,
                Value.Multiset,
                Value.Structured,
                Value.Xml,
                Value.Uuid,
                OctetValue,
                Lob {
    /** The null value, tag 0. */
    Value NULL = new Null();

    /**
     * Writes the value, its tag first.
     *
     * @param w where it is written
     * @throws TurborelException if a part of it is past what its field holds
     */
    void encode(Writer w) throws TurborelException;

    /** The null value, tag 0, with no body: unknown, of a truth value, too. */
    record Null() implements Value {
        @Override
        public void encode(Writer w) throws TurborelException {
            w.u8(Tags.NULL);
        }
    }

    /**
     * A character string, tag 1, of either character type.
     *
     * @param text the string
     */
    record Text(String text) implements Value {
        @Override
        public void encode(Writer w) throws TurborelException {
            w.u8(Tags.CHARACTER).string(text);
        }
    }

    /**
     * An exact number, tag 2: units divided by ten to the scale. It holds every value the protocol
     * carries, the whole {@code i128} range of units and every {@code u32} scale, with no loss.
     *
     * @param units the units, -2^127 to 2^127 - 1
     * @param scale the scale, a {@code u32}
     */
    record Exact(BigInteger units, long scale) implements Value {
        /**
         * The exact number of a {@code BigDecimal}: a negative scale is made 0, its units
         * multiplied to match, so the number is the same.
         *
         * @param number the number
         * @return the value
         */
        public static Exact of(BigDecimal number) {
            BigDecimal whole = number.scale() < 0 ? number.setScale(0) : number;
            return new Exact(whole.unscaledValue(), whole.scale());
        }

        /**
         * The number as a {@code BigDecimal}.
         *
         * @return the number
         * @throws TurborelException if its scale is past what a {@code BigDecimal} holds, 2^31 - 1
         */
        public BigDecimal toBigDecimal() throws TurborelException {
            if (scale > Integer.MAX_VALUE) {
                throw Failures.beyond(Cite.VALUES + " a scale of " + scale + " is past a BigDecimal's");
            }
            return new BigDecimal(units, (int) scale);
        }

        @Override
        public void encode(Writer w) throws TurborelException {
            w.u8(Tags.EXACT).i128(units).u32(scale);
        }
    }

    /**
     * An approximate number, tag 3: an IEEE 754 double, its bits kept, NaN's and -0.0's too.
     *
     * @param value the number
     */
    record Approximate(double value) implements Value {
        @Override
        public void encode(Writer w) throws TurborelException {
            w.u8(Tags.APPROXIMATE).u64(Double.doubleToRawLongBits(value));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Approximate a
                    && Double.doubleToRawLongBits(a.value) == Double.doubleToRawLongBits(value);
        }

        @Override
        public int hashCode() {
            return Long.hashCode(Double.doubleToRawLongBits(value));
        }
    }

    /**
     * A date, tag 8, a time, tag 9, or a timestamp, tag 11, of either kind.
     *
     * <p>Without a zone, the displacement is the one the value is shown in. The instant of a
     * timestamp is microseconds in UTC from 0001-01-01 00:00:00; of a date, its midnight's; of a
     * time, microseconds from midnight UTC, within a day ([PROTOCOL Values]).
     *
     * @param moment which of them
     * @param zoned whether its type is WITH TIME ZONE
     * @param precision its precision, a {@code u8}
     * @param displacement its zone's displacement from UTC, in minutes
     * @param micros its instant, microseconds in UTC from 0001-01-01 00:00:00, or from midnight
     *     for a time
     */
    record Datetime(Moment moment, boolean zoned, int precision, short displacement, long micros) implements Value {
        /** The least displacement, -12:59, in minutes ([PROTOCOL Values]). */
        public static final int MIN_DISPLACEMENT = -(12 * 60 + 59);

        /** The greatest displacement, +14:00, in minutes. */
        public static final int MAX_DISPLACEMENT = 14 * 60;

        @Override
        public void encode(Writer w) throws TurborelException {
            if (displacement < MIN_DISPLACEMENT || displacement > MAX_DISPLACEMENT) {
                // Refused with the code Turborel gives the same displacement, so a program sees
                // one behaviour whether the driver or the server refuses it.
                throw new TurborelException(
                        Failures.DATA_SQLCODE,
                        Failures.INVALID_TIME_ZONE_DISPLACEMENT,
                        Cite.VALUES + " a displacement of " + displacement + " minutes is past -12:59 to +14:00");
            }
            w.u8(moment.tag()).flag(zoned).u8(precision);
            w.u32(Short.toUnsignedLong(displacement)).u64(micros);
        }
    }

    /**
     * An interval, tag 13: months of a year-month interval, microseconds of a day-time one.
     *
     * @param qualifier its fields and precisions
     * @param units its units, -2^127 to 2^127 - 1
     */
    record Interval(Qualifier qualifier, BigInteger units) implements Value {
        @Override
        public void encode(Writer w) throws TurborelException {
            w.u8(Tags.INTERVAL);
            qualifier.encode(w);
            w.i128(units);
        }
    }

    /**
     * A truth value, tag 14: true or false; unknown is the null value.
     *
     * @param truth the truth value
     */
    record Truth(boolean truth) implements Value {
        @Override
        public void encode(Writer w) throws TurborelException {
            w.u8(Tags.BOOLEAN).flag(truth);
        }
    }

    /**
     * A decimal floating point number, tag 15: coefficient times ten to the exponent, in the
     * canonical form turborel takes, no trailing zero in the coefficient, zero as 0 and 0.
     *
     * @param coefficient the coefficient, at most 34 digits
     * @param exponent the exponent
     */
    record Decfloat(BigInteger coefficient, int exponent) implements Value {
        /**
         * The decimal floating point number of a {@code BigDecimal}, in canonical form.
         *
         * @param number the number
         * @return the value
         * @throws TurborelException if it needs more than 34 digits, or its exponent is out of
         *     DECFLOAT's range
         */
        public static Decfloat of(BigDecimal number) throws TurborelException {
            return Decfloats.canonical(number);
        }

        /**
         * The number as a {@code BigDecimal}.
         *
         * @return the number
         */
        public BigDecimal toBigDecimal() {
            return new BigDecimal(coefficient, -exponent);
        }

        @Override
        public void encode(Writer w) throws TurborelException {
            Decfloat canonical = Decfloats.canonical(toBigDecimal());
            w.u8(Tags.DECFLOAT).i128(canonical.coefficient).bits32(canonical.exponent);
        }
    }

    /**
     * An array, tag 16.
     *
     * @param elements its elements, in order
     */
    record Array(List<Value> elements) implements Value {
        /**
         * An array of the elements given.
         *
         * @param elements its elements, copied
         */
        public Array {
            elements = List.copyOf(elements);
        }

        @Override
        public void encode(Writer w) throws TurborelException {
            w.u8(Tags.ARRAY);
            ValueCodec.writeRow(w, elements);
        }
    }

    /**
     * A row, tag 17: a value for each field.
     *
     * @param fields its fields' values, in order
     */
    record Row(List<Value> fields) implements Value {
        /**
         * A row of the values given.
         *
         * @param fields its fields' values, copied
         */
        public Row {
            fields = List.copyOf(fields);
        }

        @Override
        public void encode(Writer w) throws TurborelException {
            w.u8(Tags.ROW);
            ValueCodec.writeRow(w, fields);
        }
    }

    /**
     * A multiset, tag 22: its elements in no set order.
     *
     * @param elements its elements
     */
    record Multiset(List<Value> elements) implements Value {
        /**
         * A multiset of the elements given.
         *
         * @param elements its elements, copied
         */
        public Multiset {
            elements = List.copyOf(elements);
        }

        @Override
        public void encode(Writer w) throws TurborelException {
            w.u8(Tags.MULTISET);
            ValueCodec.writeRow(w, elements);
        }
    }

    /**
     * A structured value, tag 23: its most specific type's name, then its attributes.
     *
     * @param type its most specific type's schema and name
     * @param attributes its attributes' values, in order
     */
    record Structured(TypeName type, List<Value> attributes) implements Value {
        /**
         * A structured value of the type and attributes given.
         *
         * @param type its most specific type
         * @param attributes its attributes, copied
         */
        public Structured {
            attributes = List.copyOf(attributes);
        }

        @Override
        public void encode(Writer w) throws TurborelException {
            w.u8(Tags.STRUCTURED).string(type.schema()).string(type.name());
            ValueCodec.writeRow(w, attributes);
        }
    }

    /**
     * An XML value, tag 26: its serialization as CONTENT.
     *
     * @param serialization the text
     */
    record Xml(String serialization) implements Value {
        @Override
        public void encode(Writer w) throws TurborelException {
            w.u8(Tags.XML).string(serialization);
        }
    }

    /**
     * A UUID, tag 27: its 16 octets, most significant first, as {@code bytes} (turborel's RULES.md
     * D184; not yet on the page).
     *
     * @param uuid the UUID
     */
    record Uuid(java.util.UUID uuid) implements Value {
        @Override
        public void encode(Writer w) throws TurborelException {
            w.u8(Tags.UUID).bits32(Uuids.OCTETS).u64(Long.reverseBytes(uuid.getMostSignificantBits()));
            w.u64(Long.reverseBytes(uuid.getLeastSignificantBits()));
        }
    }

    /** A datetime value's kind, with its tag: a time or a timestamp of either kind takes one tag. */
    enum Moment {
        /** A date, tag 8, moment 0 in a JSON value's datetime node. */
        DATE(Tags.DATE, 0),
        /** A time, tag 9, moment 1. */
        TIME(Tags.TIME, 1),
        /** A timestamp, tag 11, moment 2. */
        TIMESTAMP(Tags.TIMESTAMP, 2);

        private final int tag;
        private final int node;

        Moment(int tag, int node) {
            this.tag = tag;
            this.node = node;
        }

        /**
         * Its byte in a JSON value's datetime node (turborel's RULES.md D89).
         *
         * @return 0, 1 or 2
         */
        public int node() {
            return node;
        }

        /**
         * The moment of a JSON datetime node's byte.
         *
         * @param node the byte
         * @return the moment, or none of another byte
         */
        static @Nullable Moment ofNode(int node) {
            for (Moment each : values()) {
                if (each.node == node) {
                    return each;
                }
            }
            return null;
        }

        /**
         * Its tag.
         *
         * @return the tag
         */
        public int tag() {
            return tag;
        }
    }
}
