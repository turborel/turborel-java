package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

/**
 * A value as Turborel writes it ({@code src/value.rs}'s {@code Display} of a value, and each type's
 * literal): so an export writes each field as Turborel's own client does. A number plainly, an
 * approximate one as a mantissa, {@code E} and an exponent; a binary string as its hexadecimal
 * digits; a datetime as it is shown, in its displacement; an interval in its qualifier's fields; a
 * large object sent by locator as what it is; a composite value as its literal.
 */
public final class ValueText {
    /** The most scale written plainly: an exact number's largest. */
    private static final long MAX_PLAIN_SCALE = 18;

    /** The least adjusted exponent of a decimal floating point number written plainly. */
    private static final long PLAIN_LEAST = -6;

    /** The digits of a decimal floating point number, past which it is written with an exponent. */
    private static final long DECFLOAT_DIGITS = 34;

    private static final long MICROS_PER_SECOND = 1_000_000L;
    private static final long MICROS_PER_MINUTE = 60 * MICROS_PER_SECOND;
    private static final long MICROS_PER_HOUR = 60 * MICROS_PER_MINUTE;
    private static final long MICROS_PER_DAY = 24 * MICROS_PER_HOUR;
    private static final long SECONDS_PER_MINUTE = 60;
    private static final long MINUTES_PER_HOUR = 60;
    private static final long MONTHS_PER_YEAR = 12;
    private static final int FRACTION_DIGITS = 6;

    /** Day 0 of a datetime's instant [PROTOCOL Values]. */
    private static final LocalDate EPOCH = LocalDate.of(1, 1, 1);

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private ValueText() {}

    /**
     * The value as Turborel shows it.
     *
     * @param v the value
     * @return its text
     */
    public static String of(Value v) {
        if (v instanceof Value.Null) {
            return "NULL";
        } else if (v instanceof Value.Text t) {
            return t.text();
        } else if (v instanceof Value.Exact e) {
            return exact(e.units(), e.scale());
        } else if (v instanceof Value.Approximate a) {
            return approximate(a.value());
        } else if (v instanceof Value.Decfloat d) {
            return decfloat(d.coefficient(), d.exponent());
        } else if (v instanceof Value.Truth t) {
            return t.truth() ? "TRUE" : "FALSE";
        } else if (v instanceof Value.Datetime d) {
            return datetime(d);
        } else if (v instanceof Value.Interval i) {
            return interval(i);
        }
        return octetsOrComposite(v);
    }

    private static String octetsOrComposite(Value v) {
        if (v instanceof OctetValue.Binary b) {
            return HEX.formatHex(b.octets().toByteArray());
        } else if (v instanceof Lob lob) {
            return lob(lob);
        } else if (v instanceof OctetValue.Json j) {
            return json(j);
        } else if (v instanceof Value.Xml x) {
            return x.serialization();
        } else if (v instanceof Value.Uuid u) {
            return u.text();
        } else if (v instanceof OctetValue.Plugin p) {
            return p.text();
        }
        return literal(v);
    }

    /**
     * The value as an SQL literal writes it, as a composite value's parts are written.
     *
     * @param v the value
     * @return its literal
     */
    public static String literal(Value v) {
        if (v instanceof Value.Text t) {
            return quoted(t.text());
        } else if (v instanceof Value.Decfloat) {
            return "CAST('" + of(v) + "' AS DECFLOAT)";
        } else if (v instanceof OctetValue.Binary) {
            return "X'" + of(v) + "'";
        } else if (v instanceof Lob.Whole whole) {
            return whole.kind() == LobKind.CHARACTER ? quoted(of(v)) : "X'" + of(v) + "'";
        } else if (v instanceof Value.Datetime d) {
            return d.moment().name() + " '" + of(v) + "'";
        } else if (v instanceof Value.Interval i) {
            String text = of(v);
            boolean negative = text.startsWith("-");
            return "INTERVAL " + (negative ? "-" : "") + "'" + (negative ? text.substring(1) : text) + "' "
                    + qualifier(i.qualifier());
        }
        return compositeLiteral(v);
    }

    private static String compositeLiteral(Value v) {
        if (v instanceof Value.Array a) {
            return "ARRAY[" + literals(a.elements()) + "]";
        } else if (v instanceof Value.Multiset m) {
            return "MULTISET[" + literals(m.elements()) + "]";
        } else if (v instanceof Value.Row r) {
            return "ROW(" + literals(r.fields()) + ")";
        } else if (v instanceof Value.Structured s) {
            return s.type().schema() + "." + s.type().name() + "(" + literals(s.attributes()) + ")";
        } else if (v instanceof OctetValue.Reference r) {
            return "REF(" + HEX.formatHex(r.octets().toByteArray()) + ")";
        } else if (v instanceof OctetValue.Json) {
            return "JSON(" + quoted(of(v)) + ")";
        } else if (v instanceof Value.Xml) {
            return "XMLPARSE(CONTENT " + quoted(of(v)) + " PRESERVE WHITESPACE)";
        } else if (v instanceof Value.Uuid) {
            return "UUID '" + of(v) + "'";
        }
        return of(v);
    }

    private static String literals(List<Value> parts) {
        StringBuilder out = new StringBuilder();
        for (Value part : parts) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(literal(part));
        }
        return out.toString();
    }

    private static String quoted(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    /** An exact number plainly, of a scale past an exact number's with a power of ten. */
    static String exact(BigInteger units, long scale) {
        String sign = units.signum() < 0 ? "-" : "";
        String digits = units.abs().toString();
        if (scale == 0) {
            return sign + digits;
        }
        if (scale > MAX_PLAIN_SCALE) {
            return sign + digits + "E-" + scale;
        }
        String padded = "0".repeat((int) Math.max(0, scale + 1 - digits.length())) + digits;
        int point = padded.length() - (int) scale;
        return sign + padded.substring(0, point) + "." + padded.substring(point);
    }

    /** An approximate number as Rust's {@code {:E}} writes it: its shortest digits, E, its exponent. */
    static String approximate(double x) {
        if (Double.isNaN(x)) {
            return "NaN";
        }
        String sign = x < 0 || (x == 0 && 1 / x < 0) ? "-" : "";
        if (Double.isInfinite(x)) {
            return sign + "inf";
        }
        if (x == 0) {
            return sign + "0E0";
        }
        BigDecimal shortest = new BigDecimal(Double.toString(Math.abs(x))).stripTrailingZeros();
        String digits = shortest.unscaledValue().toString();
        long exponent = digits.length() - 1L - shortest.scale();
        String rest = digits.substring(1);
        return sign + digits.charAt(0) + (rest.isEmpty() ? "" : "." + rest) + "E" + exponent;
    }

    /** A decimal floating point number plainly within its range of plain writing, else with E. */
    static String decfloat(BigInteger coefficient, int exponent) {
        String sign = coefficient.signum() < 0 ? "-" : "";
        String digits = coefficient.abs().toString();
        long adjusted = (long) exponent + digits.length() - 1;
        if (coefficient.signum() == 0 || (adjusted >= PLAIN_LEAST && adjusted < DECFLOAT_DIGITS)) {
            if (exponent >= 0) {
                return sign + digits + "0".repeat(exponent);
            }
            return exact(coefficient, -(long) exponent);
        }
        String rest = digits.substring(1);
        return sign + digits.charAt(0) + (rest.isEmpty() ? "" : "." + rest) + "E" + adjusted;
    }

    /** A datetime as it is shown: in its displacement, its fraction to its precision, its zone if zoned. */
    static String datetime(Value.Datetime d) {
        long local = d.micros() + d.displacement() * MICROS_PER_MINUTE;
        long time = Math.floorMod(local, MICROS_PER_DAY);
        StringBuilder out = new StringBuilder();
        if (d.moment() != Value.Moment.TIME) {
            LocalDate date = EPOCH.plusDays(Math.floorDiv(local, MICROS_PER_DAY));
            out.append(String.format("%04d-%02d-%02d", date.getYear(), date.getMonthValue(), date.getDayOfMonth()));
        }
        if (d.moment() == Value.Moment.DATE) {
            return out.toString();
        }
        if (d.moment() == Value.Moment.TIMESTAMP) {
            out.append(' ');
        }
        long seconds = Math.floorDiv(time, MICROS_PER_SECOND);
        out.append(String.format(
                "%02d:%02d:%02d",
                seconds / (SECONDS_PER_MINUTE * MINUTES_PER_HOUR),
                (seconds / SECONDS_PER_MINUTE) % MINUTES_PER_HOUR,
                seconds % SECONDS_PER_MINUTE));
        if (d.precision() > 0) {
            String fraction = String.format("%06d", Math.floorMod(time, MICROS_PER_SECOND));
            out.append('.').append(fraction, 0, Math.min(d.precision(), FRACTION_DIGITS));
        }
        if (d.zoned()) {
            int size = Math.abs(d.displacement());
            out.append(d.displacement() < 0 ? '-' : '+')
                    .append(String.format("%02d:%02d", size / MINUTES_PER_HOUR, size % MINUTES_PER_HOUR));
        }
        return out.toString();
    }

    /** An interval in its qualifier's fields, the first as it is, each after it of two digits. */
    static String interval(Value.Interval i) {
        Qualifier q = i.qualifier();
        BigInteger size = i.units().abs();
        StringBuilder out = new StringBuilder(i.units().signum() < 0 ? "-" : "");
        IntervalField previous = null;
        for (IntervalField field : IntervalField.values()) {
            if (field.number() < q.start().number() || field.number() > q.end().number()) {
                continue;
            }
            BigInteger above = previous == null ? size : size.mod(unit(previous));
            previous = field;
            BigInteger n = field == IntervalField.SECOND ? above : above.divide(unit(field));
            BigInteger whole = field == IntervalField.SECOND ? n.divide(BigInteger.valueOf(MICROS_PER_SECOND)) : n;
            if (field == q.start()) {
                out.append(whole);
            } else {
                out.append(separator(field)).append(String.format("%02d", whole));
            }
            if (field == IntervalField.SECOND && q.fraction() > 0) {
                String fraction = String.format("%06d", n.mod(BigInteger.valueOf(MICROS_PER_SECOND)));
                out.append('.').append(fraction, 0, (int) Math.min(q.fraction(), FRACTION_DIGITS));
            }
        }
        return out.toString();
    }

    private static BigInteger unit(IntervalField field) {
        return BigInteger.valueOf(
                switch (field) {
                    case YEAR -> MONTHS_PER_YEAR;
                    case MONTH, SECOND -> 1;
                    case DAY -> MICROS_PER_DAY;
                    case HOUR -> MICROS_PER_HOUR;
                    case MINUTE -> MICROS_PER_MINUTE;
                });
    }

    private static char separator(IntervalField field) {
        return switch (field) {
            case MONTH -> '-';
            case HOUR -> ' ';
            case YEAR, DAY, MINUTE, SECOND -> ':';
        };
    }

    /** A qualifier as Turborel writes it, as {@code DAY(2) TO SECOND(6)}. */
    static String qualifier(Qualifier q) {
        String start = q.start().name();
        if (q.start() == q.end()) {
            return q.end() == IntervalField.SECOND
                    ? "SECOND(" + q.leading() + ", " + q.fraction() + ")"
                    : start + "(" + q.leading() + ")";
        }
        String to = start + "(" + q.leading() + ") TO " + q.end().name();
        return q.end() == IntervalField.SECOND ? to + "(" + q.fraction() + ")" : to;
    }

    private static String lob(Lob lob) {
        if (lob instanceof Lob.Whole whole) {
            byte[] octets = whole.octets().toByteArray();
            return whole.kind() == LobKind.CHARACTER
                    ? new String(octets, StandardCharsets.UTF_8)
                    : HEX.formatHex(octets);
        }
        Lob.Locator locator = (Lob.Locator) lob;
        return locator.kind() == LobKind.CHARACTER
                ? "<CHARACTER LARGE OBJECT of " + locator.characters() + " characters>"
                : "<BINARY LARGE OBJECT of " + locator.octets() + " octets>";
    }

    private static String json(OctetValue.Json j) {
        try {
            return JsonText.of(j.item());
        } catch (TurborelException none) {
            return "<" + none.getMessage() + ">";
        }
    }
}
