package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.DataType;
import com.turborel.jdbc.internal.codec.DatetimeKind;
import com.turborel.jdbc.internal.codec.IntervalField;
import com.turborel.jdbc.internal.codec.Lob;
import com.turborel.jdbc.internal.codec.LobKind;
import com.turborel.jdbc.internal.codec.OctetValue;
import com.turborel.jdbc.internal.codec.Octets;
import com.turborel.jdbc.internal.codec.Value;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A Java value made the value a parameter is sent as ([DESIGN] D2): of the parameter's type where
 * the server described it, so a string sent to a number is the number it writes; else of the
 * value's own type.
 */
final class ToValue {
    /** The day a datetime's instant counts from, 0001-01-01, as the protocol counts ([PROTOCOL Values]). */
    private static final LocalDateTime EPOCH = LocalDateTime.of(1, 1, 1, 0, 0);

    /** The fractional digits of a datetime sent of no type: microseconds. */
    private static final int MICRO_DIGITS = 6;

    /** Seconds in a minute. */
    private static final int SECONDS_PER_MINUTE = 60;

    /** Microseconds in a second, and nanoseconds in a microsecond. */
    private static final long MICROS_PER_SECOND = 1_000_000L;

    private static final int NANOS_PER_MICRO = 1_000;

    /** The digits of a time's hour, before its first colon. */
    private static final int HOUR_DIGITS = 2;

    /** Months in a year. */
    private static final int MONTHS_PER_YEAR = 12;

    private ToValue() {}

    /**
     * {@code x} as the value of a parameter of {@code type}, or of its own type if none is known.
     *
     * @param x the Java value, or none for the null value
     * @param type the parameter's type, or none
     * @return the value
     * @throws SQLException SQLSTATE 22018 if it cannot be one of the type; a large object's or an
     *     array's failure to be read
     */
    static Value of(@Nullable Object x, @Nullable DataType type) throws SQLException {
        if (x == null) {
            return Value.NULL;
        }
        if (x instanceof Value v) {
            return v;
        }
        if (type == null) {
            return natural(x);
        }
        if (type instanceof DataType.CharacterType) {
            return new Value.Text(text(x));
        } else if (type instanceof DataType.ExactType) {
            return Value.Exact.of(number(x));
        } else if (type instanceof DataType.DecfloatType) {
            return Value.Decfloat.of(number(x));
        } else if (type instanceof DataType.ApproximateType) {
            return new Value.Approximate(
                    x instanceof Number n ? n.doubleValue() : number(x).doubleValue());
        } else if (type instanceof DataType.BooleanType) {
            return new Value.Truth(FromValue.truth(natural(x)));
        } else if (type instanceof DataType.DatetimeType d) {
            return datetime(x, d.kind(), JdbcTypes.clamp(d.precision()));
        }
        return typed(x, type);
    }

    private static Value typed(Object x, DataType type) throws SQLException {
        if (type instanceof DataType.BinaryType) {
            return new OctetValue.Binary(Octets.of(bytes(x)));
        } else if (type instanceof DataType.LobType l) {
            return lob(x, l.kind());
        } else if (type instanceof DataType.UuidType) {
            return new Value.Uuid(x instanceof UUID u ? u : FromValue.uuid(natural(x)));
        } else if (type instanceof DataType.XmlType) {
            return new Value.Xml(x instanceof java.sql.SQLXML xml ? xml.getString() : text(x));
        } else if (type instanceof DataType.JsonType) {
            return OctetValue.Json.ofText(text(x));
        } else if (type instanceof DataType.ArrayType a) {
            return array(x, a.element());
        } else if (type instanceof DataType.IntervalType interval) {
            return interval(x, interval);
        }
        return natural(x);
    }

    /**
     * {@code x} as the value of its own type: a string, a number, a truth value, octets, a
     * datetime, a UUID, a large object, an XML value or an array.
     *
     * @param x the Java value
     * @return the value
     * @throws SQLException SQLSTATE 22018 if it is of no type a value is
     */
    static Value natural(Object x) throws SQLException {
        if (x instanceof Value v) {
            return v;
        } else if (x instanceof String || x instanceof Character) {
            return new Value.Text(x.toString());
        } else if (x instanceof Boolean b) {
            return new Value.Truth(b);
        } else if (x instanceof Float || x instanceof Double) {
            return new Value.Approximate(((Number) x).doubleValue());
        } else if (x instanceof Number) {
            return Value.Exact.of(number(x));
        } else if (x instanceof byte[] b) {
            return new OctetValue.Binary(Octets.of(b));
        } else if (x instanceof UUID u) {
            return new Value.Uuid(u);
        } else if (x instanceof java.sql.SQLXML xml) {
            return new Value.Xml(xml.getString());
        } else if (x instanceof JdbcClob || x instanceof JdbcBlob) {
            return x instanceof JdbcClob c ? c.value() : ((JdbcBlob) x).value();
        } else if (x instanceof java.sql.Array a) {
            return array(a, null);
        }
        return naturalTemporal(x);
    }

    private static Value naturalTemporal(Object x) throws SQLException {
        if (x instanceof java.sql.Date || x instanceof LocalDate) {
            return datetime(x, DatetimeKind.DATE, 0);
        } else if (x instanceof java.sql.Time || x instanceof LocalTime) {
            return datetime(x, DatetimeKind.TIME, MICRO_DIGITS);
        } else if (x instanceof OffsetTime) {
            return datetime(x, DatetimeKind.TIME_ZONED, MICRO_DIGITS);
        } else if (x instanceof OffsetDateTime || x instanceof ZonedDateTime || x instanceof Instant) {
            return datetime(x, DatetimeKind.TIMESTAMP_ZONED, MICRO_DIGITS);
        } else if (x instanceof java.util.Date || x instanceof LocalDateTime) {
            return datetime(x, DatetimeKind.TIMESTAMP, MICRO_DIGITS);
        } else if (x instanceof java.sql.Clob c) {
            return Lob.Whole.ofText(c.getSubString(1, Math.toIntExact(c.length())));
        } else if (x instanceof java.sql.Blob b) {
            return new Lob.Whole(LobKind.BINARY, Octets.of(b.getBytes(1, Math.toIntExact(b.length()))));
        } else if (x instanceof Object[] || x instanceof List) {
            return array(x, null);
        }
        throw Errors.conversion("a " + x.getClass().getName() + " is sent as no value");
    }

    /** {@code x} as text: a string's, a number's plain, a datetime's ISO, another's own. */
    static String text(Object x) throws SQLException {
        if (x instanceof BigDecimal d) {
            return d.toPlainString();
        } else if (x instanceof java.sql.Clob c) {
            return c.getSubString(1, Math.toIntExact(c.length()));
        } else if (x instanceof java.sql.SQLXML xml) {
            return xml.getString();
        } else if (x instanceof byte[] b) {
            return java.util.HexFormat.of().withUpperCase().formatHex(b);
        }
        return x.toString();
    }

    /** {@code x} as a number: a number's, a truth value's 1 or 0, a string's. */
    static BigDecimal number(Object x) throws SQLException {
        if (x instanceof BigDecimal d) {
            return d;
        } else if (x instanceof BigInteger i) {
            return new BigDecimal(i);
        } else if (x instanceof Float || x instanceof Double) {
            double d = ((Number) x).doubleValue();
            if (!Double.isFinite(d)) {
                throw Errors.conversion(d + " is no exact number");
            }
            return BigDecimal.valueOf(d);
        } else if (x instanceof Number n) {
            return BigDecimal.valueOf(n.longValue());
        }
        return FromValue.decimal(natural(x));
    }

    /** {@code x} as octets: octets', a UUID's, a large object's held whole, a string's in UTF-8. */
    static byte[] bytes(Object x) throws SQLException {
        if (x instanceof byte[] b) {
            return b.clone();
        } else if (x instanceof java.sql.Blob b) {
            return b.getBytes(1, Math.toIntExact(b.length()));
        }
        Value v = natural(x);
        if (v instanceof Value.Uuid u) {
            return u.octets();
        } else if (v instanceof Value.Text t) {
            return t.text().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } else if (v instanceof OctetValue.Binary b) {
            return b.octets().toByteArray();
        } else if (v instanceof Lob.Whole w) {
            return w.octets().toByteArray();
        }
        throw Errors.conversion("a " + x.getClass().getName() + " is not octets");
    }

    /** {@code x} as a large object of {@code kind}, held whole. */
    private static Value lob(Object x, LobKind kind) throws SQLException {
        if (x instanceof JdbcClob c && kind == LobKind.CHARACTER) {
            return c.value();
        } else if (x instanceof JdbcBlob b && kind == LobKind.BINARY) {
            return b.value();
        } else if (kind == LobKind.CHARACTER) {
            return Lob.Whole.ofText(text(x));
        }
        return new Lob.Whole(LobKind.BINARY, Octets.of(bytes(x)));
    }

    /** {@code x} as a datetime of {@code kind} and {@code precision}. */
    static Value datetime(Object x, DatetimeKind kind, int precision) throws SQLException {
        OffsetDateTime at = instant(x);
        boolean zoned = kind == DatetimeKind.TIME_ZONED || kind == DatetimeKind.TIMESTAMP_ZONED;
        Value.Moment moment = kind == DatetimeKind.DATE
                ? Value.Moment.DATE
                : kind == DatetimeKind.TIME || kind == DatetimeKind.TIME_ZONED
                        ? Value.Moment.TIME
                        : Value.Moment.TIMESTAMP;
        int minutes = zoned ? at.getOffset().getTotalSeconds() / SECONDS_PER_MINUTE : 0;
        LocalDateTime local = at.toLocalDateTime();
        if (moment == Value.Moment.DATE) {
            local = local.toLocalDate().atStartOfDay();
        } else if (moment == Value.Moment.TIME) {
            local = EPOCH.with(local.toLocalTime());
        }
        long micros = ChronoUnit.MICROS.between(EPOCH, local) - minutes * (long) SECONDS_PER_MINUTE * MICROS_PER_SECOND;
        return new Value.Datetime(moment, zoned, precision, (short) minutes, micros);
    }

    /** {@code x} as a date and time with an offset: a local one's, the JVM's zone's. */
    private static OffsetDateTime instant(Object x) throws SQLException {
        ZoneId here = ZoneId.systemDefault();
        if (x instanceof OffsetDateTime o) {
            return o;
        } else if (x instanceof ZonedDateTime z) {
            return z.toOffsetDateTime();
        } else if (x instanceof Instant i) {
            return i.atZone(here).toOffsetDateTime();
        } else if (x instanceof OffsetTime t) {
            return t.atDate(LocalDate.of(1, 1, 1));
        }
        LocalDateTime local = localOf(x);
        return local.atZone(here).toOffsetDateTime();
    }

    private static LocalDateTime localOf(Object x) throws SQLException {
        if (x instanceof java.sql.Date d) {
            return d.toLocalDate().atStartOfDay();
        } else if (x instanceof java.sql.Time t) {
            return EPOCH.with(t.toLocalTime());
        } else if (x instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime();
        } else if (x instanceof java.util.Date d) {
            return LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault());
        } else if (x instanceof LocalDate d) {
            return d.atStartOfDay();
        } else if (x instanceof LocalTime t) {
            return EPOCH.with(t);
        } else if (x instanceof LocalDateTime t) {
            return t;
        }
        Value v = natural(x);
        return v instanceof Value.Text t && t.text().trim().indexOf(':') == HOUR_DIGITS
                ? EPOCH.with(FromValue.time(v))
                : FromValue.local(v);
    }

    /** {@code x} as an interval: a {@code Duration} of a day-time one, a {@code Period} of a year-month one. */
    private static Value interval(Object x, DataType.IntervalType type) throws SQLException {
        boolean yearMonth = type.qualifier().end().number() <= IntervalField.MONTH.number();
        if (x instanceof Period p && yearMonth) {
            return new Value.Interval(type.qualifier(), BigInteger.valueOf(p.toTotalMonths()));
        }
        if (x instanceof Duration d && !yearMonth) {
            BigInteger micros = BigInteger.valueOf(d.getSeconds())
                    .multiply(BigInteger.valueOf(MICROS_PER_SECOND))
                    .add(BigInteger.valueOf(d.getNano() / NANOS_PER_MICRO));
            return new Value.Interval(type.qualifier(), micros);
        }
        if (x instanceof Number n && yearMonth) {
            return new Value.Interval(type.qualifier(), BigInteger.valueOf(n.longValue() * MONTHS_PER_YEAR));
        }
        throw Errors.conversion("a " + x.getClass().getName() + " is sent as no interval of its fields");
    }

    /** {@code x} as an array of elements of {@code element}'s type, if known. */
    private static Value array(Object x, @Nullable DataType element) throws SQLException {
        Object[] items;
        if (x instanceof java.sql.Array a) {
            items = (Object[]) a.getArray();
        } else if (x instanceof List<?> list) {
            items = list.toArray();
        } else if (x instanceof Object[] objects) {
            items = objects;
        } else {
            throw Errors.conversion("a " + x.getClass().getName() + " is no array");
        }
        List<Value> elements = new ArrayList<>(items.length);
        for (Object each : items) {
            elements.add(of(each, element));
        }
        return new Value.Array(elements);
    }
}
