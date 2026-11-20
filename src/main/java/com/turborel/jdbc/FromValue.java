package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.Failures;
import com.turborel.jdbc.internal.codec.Lob;
import com.turborel.jdbc.internal.codec.LobKind;
import com.turborel.jdbc.internal.codec.OctetValue;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.internal.codec.ValueText;
import com.turborel.jdbc.internal.session.Lobs;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A value the server sent, read as the Java type a getter asks for ([DESIGN] D2): an exact number
 * as a {@code BigDecimal}, a datetime as {@code java.time}, in its displacement, a large object
 * through its locator.
 */
final class FromValue {
    /** The day a datetime's instant counts from, 0001-01-01, as the protocol counts ([PROTOCOL Values]). */
    private static final LocalDateTime EPOCH = LocalDateTime.of(1, 1, 1, 0, 0);

    /** Microseconds in a minute. */
    private static final long MICROS_PER_MINUTE = 60_000_000L;

    /** Seconds in a minute. */
    private static final int SECONDS_PER_MINUTE = 60;

    /** The words of a truth value, in upper case. */
    private static final java.util.Set<String> TRUE_WORDS = java.util.Set.of("TRUE", "T", "YES", "Y", "1");

    private static final java.util.Set<String> FALSE_WORDS = java.util.Set.of("FALSE", "F", "NO", "N", "0");

    private FromValue() {}

    /**
     * The text of {@code v}: a string as it is, a large object's whole text or octets in
     * hexadecimal, another as Turborel writes it.
     *
     * @param v the value, not null
     * @param lobs what reads a large object
     * @return its text
     * @throws TurborelException a large object that cannot be read
     */
    static String string(Value v, @Nullable Lobs lobs) throws TurborelException {
        if (v instanceof Value.Text t) {
            return t.text();
        }
        if (v instanceof Lob.Locator locator && locator.kind() == LobKind.CHARACTER) {
            return new String(whole(locator, lobs), StandardCharsets.UTF_8);
        }
        if (v instanceof Lob.Locator locator) {
            return java.util.HexFormat.of().withUpperCase().formatHex(whole(locator, lobs));
        }
        return ValueText.of(v);
    }

    /**
     * A large object's octets, read whole.
     *
     * @param lob the large object
     * @param lobs what reads it
     * @return its octets
     * @throws TurborelException if it cannot be read
     */
    static byte[] whole(Lob lob, @Nullable Lobs lobs) throws TurborelException {
        if (lobs == null) {
            throw Errors.misuse("a large object is read by its connection");
        }
        try {
            return lobs.reader(lob).readAllBytes();
        } catch (IOException e) {
            if (e.getCause() instanceof TurborelException failure) {
                throw failure;
            }
            throw Errors.conversion("a large object could not be read: " + e.getMessage());
        }
    }

    /**
     * {@code v} as a number.
     *
     * @param v the value, not null
     * @return the number
     * @throws TurborelException SQLSTATE 22018 if it is no number, nor the text of one
     */
    static BigDecimal decimal(Value v) throws TurborelException {
        if (v instanceof Value.Exact e) {
            return new BigDecimal(e.units(), Math.toIntExact(e.scale()));
        } else if (v instanceof Value.Decfloat d) {
            return d.toBigDecimal();
        } else if (v instanceof Value.Approximate a && Double.isFinite(a.value())) {
            return BigDecimal.valueOf(a.value());
        } else if (v instanceof Value.Truth t) {
            return t.truth() ? BigDecimal.ONE : BigDecimal.ZERO;
        } else if (v instanceof Value.Text t) {
            try {
                return new BigDecimal(t.text().trim());
            } catch (NumberFormatException e) {
                throw Errors.conversion("'" + t.text() + "' is not a number");
            }
        }
        throw Errors.conversion(ValueText.of(v) + " is not a number");
    }

    /**
     * {@code v} as a whole number from {@code least} to {@code most}, its fraction dropped.
     *
     * @param v the value, not null
     * @param least the least
     * @param most the greatest
     * @return the number
     * @throws TurborelException SQLSTATE 22018 if it is no number; 22003 if it is out of range
     */
    static long whole(Value v, long least, long most) throws TurborelException {
        BigDecimal n = decimal(v).setScale(0, RoundingMode.DOWN);
        if (n.compareTo(BigDecimal.valueOf(least)) < 0 || n.compareTo(BigDecimal.valueOf(most)) > 0) {
            throw Failures.outOfRange("[DESIGN] D2 " + n + " is out of the range " + least + " to " + most);
        }
        return n.longValue();
    }

    /**
     * {@code v} as an approximate number.
     *
     * @param v the value, not null
     * @return the number
     * @throws TurborelException SQLSTATE 22018 if it is no number
     */
    static double approximate(Value v) throws TurborelException {
        if (v instanceof Value.Approximate a) {
            return a.value();
        }
        if (v instanceof Value.Text t) {
            try {
                return Double.parseDouble(t.text().trim());
            } catch (NumberFormatException e) {
                throw Errors.conversion("'" + t.text() + "' is not a number");
            }
        }
        return decimal(v).doubleValue();
    }

    /**
     * {@code v} as a truth value: a number true if not zero; a string TRUE, T, YES, Y or 1, or
     * FALSE, F, NO, N or 0.
     *
     * @param v the value, not null
     * @return the truth
     * @throws TurborelException SQLSTATE 22018 of another
     */
    static boolean truth(Value v) throws TurborelException {
        if (v instanceof Value.Truth t) {
            return t.truth();
        }
        if (v instanceof Value.Text t) {
            String word = t.text().trim().toUpperCase(Locale.ROOT);
            if (TRUE_WORDS.contains(word)) {
                return true;
            }
            if (FALSE_WORDS.contains(word)) {
                return false;
            }
            throw Errors.conversion("'" + t.text() + "' is not a truth value");
        }
        return decimal(v).signum() != 0;
    }

    /**
     * {@code v} as octets: binary's, a large object's, a UUID's or a reference's; a string's in
     * UTF-8.
     *
     * @param v the value, not null
     * @param lobs what reads a large object
     * @return the octets
     * @throws TurborelException SQLSTATE 22018 of another
     */
    static byte[] bytes(Value v, @Nullable Lobs lobs) throws TurborelException {
        if (v instanceof OctetValue.Binary b) {
            return b.octets().toByteArray();
        } else if (v instanceof Lob lob) {
            return whole(lob, lobs);
        } else if (v instanceof Value.Uuid u) {
            return u.octets();
        } else if (v instanceof OctetValue.Reference r) {
            return r.octets().toByteArray();
        } else if (v instanceof Value.Text t) {
            return t.text().getBytes(StandardCharsets.UTF_8);
        }
        throw Errors.conversion(ValueText.of(v) + " is not octets");
    }

    /**
     * {@code v} as a date and time in its displacement: a date's at midnight, a time's on
     * 0001-01-01; the text of a timestamp read.
     *
     * @param v the value, not null
     * @return the date and time
     * @throws TurborelException SQLSTATE 22018 of another
     */
    static LocalDateTime local(Value v) throws TurborelException {
        if (v instanceof Value.Datetime d) {
            return EPOCH.plus(d.micros() + d.displacement() * MICROS_PER_MINUTE, ChronoUnit.MICROS);
        }
        if (v instanceof Value.Text t) {
            String text = t.text().trim();
            try {
                return text.length() <= "YYYY-MM-DD".length()
                        ? LocalDate.parse(text).atStartOfDay()
                        : LocalDateTime.parse(text.replace(' ', 'T'));
            } catch (DateTimeParseException e) {
                throw Errors.conversion("'" + text + "' is not a date or timestamp");
            }
        }
        throw Errors.conversion(ValueText.of(v) + " is not a datetime");
    }

    /**
     * {@code v} as a time of day in its displacement.
     *
     * @param v the value, not null
     * @return the time
     * @throws TurborelException SQLSTATE 22018 of another
     */
    static LocalTime time(Value v) throws TurborelException {
        if (v instanceof Value.Text t) {
            try {
                return LocalTime.parse(t.text().trim());
            } catch (DateTimeParseException e) {
                throw Errors.conversion("'" + t.text() + "' is not a time");
            }
        }
        return local(v).toLocalTime();
    }

    /**
     * {@code v} as a date and time with its displacement; of a datetime without a zone, the one it
     * is shown in.
     *
     * @param v the value, not null
     * @return the date and time
     * @throws TurborelException SQLSTATE 22018 of another
     */
    static OffsetDateTime offset(Value v) throws TurborelException {
        if (v instanceof Value.Text t) {
            try {
                return OffsetDateTime.parse(t.text().trim().replace(' ', 'T'));
            } catch (DateTimeParseException e) {
                throw Errors.conversion("'" + t.text() + "' is not a timestamp with a time zone");
            }
        }
        int minutes = v instanceof Value.Datetime d ? d.displacement() : 0;
        return local(v).atOffset(ZoneOffset.ofTotalSeconds(minutes * SECONDS_PER_MINUTE));
    }

    /**
     * {@code v} as a time with its displacement.
     *
     * @param v the value, not null
     * @return the time
     * @throws TurborelException SQLSTATE 22018 of another
     */
    static OffsetTime offsetTime(Value v) throws TurborelException {
        return offset(v).toOffsetTime();
    }

    /**
     * {@code v} as a UUID: a UUID's, or the text or 16 octets of one.
     *
     * @param v the value, not null
     * @return the UUID
     * @throws TurborelException SQLSTATE 22018 of another
     */
    static UUID uuid(Value v) throws TurborelException {
        if (v instanceof Value.Uuid u) {
            return u.uuid();
        }
        if (v instanceof Value.Text t) {
            Value.Uuid parsed = Value.Uuid.parse(t.text());
            if (parsed != null) {
                return parsed.uuid();
            }
        } else if (v instanceof OctetValue.Binary b) {
            return Value.Uuid.ofOctets(b.octets().toByteArray()).uuid();
        }
        throw Errors.conversion(ValueText.of(v) + " is not a UUID");
    }
}
