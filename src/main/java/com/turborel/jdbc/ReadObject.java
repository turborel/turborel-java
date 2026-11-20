package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.Column;
import com.turborel.jdbc.internal.codec.IntervalField;
import com.turborel.jdbc.internal.codec.Lob;
import com.turborel.jdbc.internal.codec.LobKind;
import com.turborel.jdbc.internal.codec.OctetValue;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.internal.codec.ValueText;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A value as {@code getObject} gives it ([DESIGN] D2): of the class its column type maps to
 * ({@link JdbcTypes#className}), or of the class the program asks.
 */
final class ReadObject {
    /** Microseconds in a second. */
    private static final long MICROS_PER_SECOND = 1_000_000L;

    /** Nanoseconds in a microsecond. */
    private static final long NANOS_PER_MICRO = 1_000L;

    private ReadObject() {}

    /**
     * {@code v} as the class its column maps to: an Integer of an INTEGER or a SMALLINT, a Long of a
     * BIGINT, a Double of a FLOAT, declared as such; else as its data type maps to, an array's
     * elements of its element's type.
     *
     * @param v the value
     * @param column its column, or none known
     * @param connection its connection, which reads a large object
     * @return the object, or none for the null value
     * @throws SQLException a large object's failure to be read
     */
    static @Nullable Object object(Value v, @Nullable Column column, JdbcConnection connection) throws SQLException {
        if (v instanceof Value.Null) {
            return null;
        }
        return switch (column == null ? java.sql.Types.OTHER : JdbcTypes.of(column)) {
            case java.sql.Types.INTEGER, java.sql.Types.SMALLINT ->
                (int) FromValue.whole(v, Integer.MIN_VALUE, Integer.MAX_VALUE);
            case java.sql.Types.BIGINT -> FromValue.whole(v, Long.MIN_VALUE, Long.MAX_VALUE);
            case java.sql.Types.FLOAT -> FromValue.approximate(v);
            case java.sql.Types.REAL -> (float) FromValue.approximate(v);
            default -> plain(v, column, connection);
        };
    }

    private static Object plain(Value v, @Nullable Column column, JdbcConnection connection) throws SQLException {
        if (v instanceof Value.Text t) {
            return t.text();
        } else if (v instanceof Value.Exact || v instanceof Value.Decfloat) {
            return FromValue.decimal(v);
        } else if (v instanceof Value.Approximate a) {
            return a.value();
        } else if (v instanceof Value.Truth t) {
            return t.truth();
        } else if (v instanceof Value.Datetime d) {
            return datetime(d);
        } else if (v instanceof Value.Interval i) {
            return interval(i);
        }
        return other(v, column, connection);
    }

    private static Object other(Value v, @Nullable Column column, JdbcConnection connection) throws SQLException {
        if (v instanceof OctetValue.Binary || v instanceof OctetValue.Reference) {
            return FromValue.bytes(v, connection.session().lobs());
        } else if (v instanceof Lob.Locator l) {
            return l.kind() == LobKind.CHARACTER
                    ? new JdbcClob(l, connection.session().lobs())
                    : new JdbcBlob(l, connection.session().lobs());
        } else if (v instanceof Lob.Whole w) {
            return w.kind() == LobKind.CHARACTER
                    ? new JdbcClob(FromValue.string(w, connection.session().lobs()))
                    : new JdbcBlob(w.octets().toByteArray());
        } else if (v instanceof Value.Xml x) {
            return new JdbcSQLXML(x.serialization());
        } else if (v instanceof Value.Uuid u) {
            return u.uuid();
        } else if (v instanceof Value.Array a) {
            return new JdbcArray(column == null ? null : JdbcArray.element(column), a.elements(), connection);
        }
        return ValueText.of(v);
    }

    private static Object datetime(Value.Datetime d) throws SQLException {
        return switch (d.moment()) {
            case DATE -> java.sql.Date.valueOf(FromValue.local(d).toLocalDate());
            case TIME -> d.zoned() ? FromValue.offsetTime(d) : java.sql.Time.valueOf(FromValue.time(d));
            case TIMESTAMP -> d.zoned() ? FromValue.offset(d) : java.sql.Timestamp.valueOf(FromValue.local(d));
        };
    }

    /** An interval as a {@code Period}, of years and months, or a {@code Duration}. */
    static Object interval(Value.Interval i) {
        if (i.qualifier().end().number() <= IntervalField.MONTH.number()) {
            return Period.ofMonths(i.units().intValueExact()).normalized();
        }
        BigInteger[] parts = i.units().divideAndRemainder(BigInteger.valueOf(MICROS_PER_SECOND));
        return Duration.ofSeconds(parts[0].longValueExact(), parts[1].longValue() * NANOS_PER_MICRO);
    }

    /**
     * {@code v} as an object of {@code type}, as {@code getObject(int, Class)} gives it.
     *
     * @param <T> the class
     * @param v the value
     * @param type the class
     * @param column its column, or none known
     * @param connection its connection
     * @return the object, or none for the null value
     * @throws SQLException SQLSTATE 22018 if it is no such object
     */
    static <T> @Nullable T as(Value v, Class<T> type, @Nullable Column column, JdbcConnection connection)
            throws SQLException {
        if (v instanceof Value.Null) {
            return null;
        }
        Object made = asTemporal(v, type);
        if (made == null) {
            made = asOther(v, type, column, connection);
        }
        if (!type.isInstance(made)) {
            throw Errors.conversion("a value of " + v.getClass().getSimpleName() + " is no " + type.getName());
        }
        return type.cast(made);
    }

    private static @Nullable Object asTemporal(Value v, Class<?> type) throws SQLException {
        if (type == LocalDate.class) {
            return FromValue.local(v).toLocalDate();
        } else if (type == LocalTime.class) {
            return FromValue.time(v);
        } else if (type == LocalDateTime.class) {
            return FromValue.local(v);
        } else if (type == OffsetDateTime.class) {
            return FromValue.offset(v);
        } else if (type == OffsetTime.class) {
            return FromValue.offsetTime(v);
        } else if (type == ZonedDateTime.class) {
            return FromValue.offset(v).toZonedDateTime();
        } else if (type == java.time.Instant.class) {
            return FromValue.offset(v).toInstant();
        }
        return null;
    }

    private static @Nullable Object asOther(Value v, Class<?> type, @Nullable Column column, JdbcConnection connection)
            throws SQLException {
        if (type == String.class) {
            return FromValue.string(v, connection.session().lobs());
        } else if (type == BigDecimal.class) {
            return FromValue.decimal(v);
        } else if (type == BigInteger.class) {
            return FromValue.decimal(v).toBigInteger();
        } else if (type == Long.class) {
            return FromValue.whole(v, Long.MIN_VALUE, Long.MAX_VALUE);
        } else if (type == Integer.class) {
            return (int) FromValue.whole(v, Integer.MIN_VALUE, Integer.MAX_VALUE);
        } else if (type == Short.class) {
            return (short) FromValue.whole(v, Short.MIN_VALUE, Short.MAX_VALUE);
        } else if (type == Byte.class) {
            return (byte) FromValue.whole(v, Byte.MIN_VALUE, Byte.MAX_VALUE);
        } else if (type == Double.class) {
            return FromValue.approximate(v);
        } else if (type == Float.class) {
            return (float) FromValue.approximate(v);
        } else if (type == Boolean.class) {
            return FromValue.truth(v);
        } else if (type == UUID.class) {
            return FromValue.uuid(v);
        } else if (type == byte[].class) {
            return FromValue.bytes(v, connection.session().lobs());
        }
        return object(v, column, connection);
    }
}
