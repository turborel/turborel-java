package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.Column;
import com.turborel.jdbc.internal.codec.DataType;
import com.turborel.jdbc.internal.codec.DatetimeKind;
import com.turborel.jdbc.internal.codec.Declared;
import com.turborel.jdbc.internal.codec.LobKind;
import java.sql.Types;
import java.util.Locale;
import java.util.Map;

/**
 * The JDBC type of each type the server sends ([DESIGN] D2, D14): its {@link Types} number, its
 * name, its precision, scale and display size, and the class {@code getObject} gives. A column's
 * type is its declared type where the server tells it ([PROTOCOL Declared types]), so an INTEGER is
 * {@link Types#INTEGER}; else as its data type says, an exact number DECIMAL of its precision and
 * scale (D14).
 */
final class JdbcTypes {
    /** The precision of an approximate number at most which it is a REAL, in binary digits. */
    private static final long REAL_DIGITS = 24;

    /** The characters of a date, YYYY-MM-DD, and of a time, HH:MM:SS, without a fraction. */
    private static final int DATE_SIZE = 10;

    private static final int TIME_SIZE = 8;

    /** The characters of a zone's displacement, +HH:MM. */
    private static final int ZONE_SIZE = 6;

    /** The characters a sign and a decimal point add to a number's digits. */
    private static final int SIGN_AND_POINT = 2;

    /** The hexadecimal digits an octet is written in. */
    private static final int HEX_DIGITS = 2;

    /** The decimal digits a double is written in. */
    private static final int DOUBLE_DIGITS = 17;

    /** The display size of a type of no fixed size. */
    private static final int UNBOUNDED = Integer.MAX_VALUE;

    /** The characters of a UUID's text. */
    private static final int UUID_SIZE = 36;

    /** The JDBC type of each name of a type the Information Schema writes, as DATA_TYPE. */
    private static final Map<String, Integer> NAMED = Map.ofEntries(
            Map.entry("CHARACTER", Types.CHAR),
            Map.entry("CHARACTER VARYING", Types.VARCHAR),
            Map.entry("CHARACTER LARGE OBJECT", Types.CLOB),
            Map.entry("BINARY", Types.BINARY),
            Map.entry("BINARY VARYING", Types.VARBINARY),
            Map.entry("BINARY LARGE OBJECT", Types.BLOB),
            Map.entry("NUMERIC", Types.NUMERIC),
            Map.entry("DECIMAL", Types.DECIMAL),
            Map.entry("SMALLINT", Types.SMALLINT),
            Map.entry("INTEGER", Types.INTEGER),
            Map.entry("BIGINT", Types.BIGINT),
            Map.entry("FLOAT", Types.FLOAT),
            Map.entry("REAL", Types.REAL),
            Map.entry("DOUBLE PRECISION", Types.DOUBLE),
            Map.entry("DECFLOAT", Types.DECIMAL),
            Map.entry("BOOLEAN", Types.BOOLEAN),
            Map.entry("DATE", Types.DATE),
            Map.entry("TIME", Types.TIME),
            Map.entry("TIME WITH TIME ZONE", Types.TIME_WITH_TIMEZONE),
            Map.entry("TIMESTAMP", Types.TIMESTAMP),
            Map.entry("TIMESTAMP WITH TIME ZONE", Types.TIMESTAMP_WITH_TIMEZONE),
            Map.entry("ARRAY", Types.ARRAY),
            Map.entry("XML", Types.SQLXML),
            Map.entry("REF", Types.REF),
            Map.entry("ROW", Types.STRUCT),
            Map.entry("USER-DEFINED", Types.STRUCT));

    private JdbcTypes() {}

    /**
     * The JDBC type of a type the Information Schema names, as its DATA_TYPE writes it.
     *
     * @param name the name
     * @return its {@link Types} number; {@link Types#OTHER} of another
     */
    static int named(String name) {
        return NAMED.getOrDefault(name.toUpperCase(Locale.ROOT), Types.OTHER);
    }

    /** The declared type's name of a distinct type, whose values are its source's. */
    private static final String USER_DEFINED = "USER-DEFINED";

    /**
     * The {@link Types} number of {@code column}: of its declared type, if told and JDBC numbers
     * it, so an INTEGER is {@link Types#INTEGER}; else of its data type.
     *
     * @param column the column
     * @return its number
     */
    static int of(Column column) {
        Declared told = column.declared();
        if (told != null && !told.name().equals(USER_DEFINED)) {
            int named = named(told.name());
            if (named != Types.OTHER) {
                return named;
            }
        }
        return of(column.type());
    }

    /**
     * The name of {@code column}'s type: its declared type's, a distinct type's own name, if told;
     * else its data type's.
     *
     * @param column the column
     * @return its name
     */
    static String name(Column column) {
        Declared told = column.declared();
        if (told == null) {
            return name(column.type());
        }
        return told.name().equals(USER_DEFINED) ? told.written() : told.name();
    }

    /**
     * The class {@code getObject} gives a value of {@code column} as: an Integer of an INTEGER or a
     * SMALLINT, a Long of a BIGINT, a Double of a FLOAT, as JDBC maps them; else as its data type's.
     *
     * @param column the column
     * @return the class's name
     */
    static String className(Column column) {
        return switch (of(column)) {
            case Types.INTEGER, Types.SMALLINT -> Integer.class.getName();
            case Types.BIGINT -> Long.class.getName();
            case Types.FLOAT -> Double.class.getName();
            default -> className(column.type());
        };
    }

    /**
     * The {@link Types} number of {@code type}.
     *
     * @param type the type
     * @return its number
     */
    static int of(DataType type) {
        if (type instanceof DataType.CharacterType c) {
            return c.varying() ? Types.VARCHAR : Types.CHAR;
        } else if (type instanceof DataType.ExactType || type instanceof DataType.DecfloatType) {
            return Types.DECIMAL;
        } else if (type instanceof DataType.ApproximateType a) {
            return a.precision() <= REAL_DIGITS ? Types.REAL : Types.DOUBLE;
        } else if (type instanceof DataType.BooleanType) {
            return Types.BOOLEAN;
        } else if (type instanceof DataType.DatetimeType d) {
            return datetime(d.kind());
        } else if (type instanceof DataType.BinaryType b) {
            return b.varying() ? Types.VARBINARY : Types.BINARY;
        } else if (type instanceof DataType.LobType l) {
            return l.kind() == LobKind.CHARACTER ? Types.CLOB : Types.BLOB;
        }
        return constructed(type);
    }

    private static int constructed(DataType type) {
        if (type instanceof DataType.ArrayType) {
            return Types.ARRAY;
        } else if (type instanceof DataType.XmlType) {
            return Types.SQLXML;
        } else if (type instanceof DataType.StructuredType) {
            return Types.STRUCT;
        } else if (type instanceof DataType.ReferenceType) {
            return Types.REF;
        }
        return Types.OTHER;
    }

    private static int datetime(DatetimeKind kind) {
        return switch (kind) {
            case DATE -> Types.DATE;
            case TIME -> Types.TIME;
            case TIME_ZONED -> Types.TIME_WITH_TIMEZONE;
            case TIMESTAMP -> Types.TIMESTAMP;
            case TIMESTAMP_ZONED -> Types.TIMESTAMP_WITH_TIMEZONE;
        };
    }

    /**
     * The name of {@code type}, as SQL writes it without its parameters.
     *
     * @param type the type
     * @return its name
     */
    static String name(DataType type) {
        if (type instanceof DataType.CharacterType c) {
            return c.varying() ? "CHARACTER VARYING" : "CHARACTER";
        } else if (type instanceof DataType.ExactType) {
            return "DECIMAL";
        } else if (type instanceof DataType.DecfloatType) {
            return "DECFLOAT";
        } else if (type instanceof DataType.ApproximateType a) {
            return a.precision() <= REAL_DIGITS ? "REAL" : "DOUBLE PRECISION";
        } else if (type instanceof DataType.BooleanType) {
            return "BOOLEAN";
        } else if (type instanceof DataType.DatetimeType d) {
            return d.kind().name().replace("_ZONED", " WITH TIME ZONE");
        } else if (type instanceof DataType.BinaryType b) {
            return b.varying() ? "BINARY VARYING" : "BINARY";
        } else if (type instanceof DataType.LobType l) {
            return l.kind() == LobKind.CHARACTER ? "CHARACTER LARGE OBJECT" : "BINARY LARGE OBJECT";
        }
        return constructedName(type);
    }

    private static String constructedName(DataType type) {
        if (type instanceof DataType.ArrayType) {
            return "ARRAY";
        } else if (type instanceof DataType.MultisetType) {
            return "MULTISET";
        } else if (type instanceof DataType.RowType) {
            return "ROW";
        } else if (type instanceof DataType.IntervalType) {
            return "INTERVAL";
        } else if (type instanceof DataType.XmlType) {
            return "XML";
        } else if (type instanceof DataType.JsonType) {
            return "JSON";
        } else if (type instanceof DataType.UuidType) {
            return "UUID";
        } else if (type instanceof DataType.StructuredType s) {
            return s.name().schema() + "." + s.name().name();
        } else if (type instanceof DataType.PluginType p) {
            return p.name();
        }
        return "REF";
    }

    /**
     * The precision of {@code type}: a string's length, a number's digits, a datetime's
     * characters; 0 of another.
     *
     * @param type the type
     * @return its precision
     */
    static int precision(DataType type) {
        if (type instanceof DataType.CharacterType c) {
            return clamp(c.length());
        } else if (type instanceof DataType.ExactType e) {
            return clamp(e.precision());
        } else if (type instanceof DataType.DecfloatType d) {
            return clamp(d.precision());
        } else if (type instanceof DataType.ApproximateType a) {
            return clamp(a.precision());
        } else if (type instanceof DataType.BinaryType b) {
            return clamp(b.length());
        } else if (type instanceof DataType.LobType l) {
            return clamp(l.length());
        } else if (type instanceof DataType.DatetimeType d) {
            return datetimeSize(d);
        } else if (type instanceof DataType.UuidType) {
            return UUID_SIZE;
        }
        return 0;
    }

    /**
     * The scale of {@code type}: an exact number's, a datetime's fractional digits; 0 of another.
     *
     * @param type the type
     * @return its scale
     */
    static int scale(DataType type) {
        if (type instanceof DataType.ExactType e) {
            return clamp(e.scale());
        } else if (type instanceof DataType.DatetimeType d) {
            return clamp(d.precision());
        }
        return 0;
    }

    /**
     * The most characters a value of {@code type} is written in.
     *
     * @param type the type
     * @return its display size
     */
    static int displaySize(DataType type) {
        if (type instanceof DataType.ExactType e) {
            return clamp(e.precision() + SIGN_AND_POINT);
        } else if (type instanceof DataType.ApproximateType) {
            return DOUBLE_DIGITS + SIGN_AND_POINT + SIGN_AND_POINT + 1;
        } else if (type instanceof DataType.BooleanType) {
            return "FALSE".length();
        } else if (type instanceof DataType.BinaryType b) {
            return clamp(HEX_DIGITS * b.length());
        }
        int precision = precision(type);
        return precision == 0 ? UNBOUNDED : precision;
    }

    private static int datetimeSize(DataType.DatetimeType d) {
        int fraction = d.precision() > 0 ? clamp(d.precision()) + 1 : 0;
        return switch (d.kind()) {
            case DATE -> DATE_SIZE;
            case TIME -> TIME_SIZE + fraction;
            case TIME_ZONED -> TIME_SIZE + fraction + ZONE_SIZE;
            case TIMESTAMP -> DATE_SIZE + 1 + TIME_SIZE + fraction;
            case TIMESTAMP_ZONED -> DATE_SIZE + 1 + TIME_SIZE + fraction + ZONE_SIZE;
        };
    }

    /**
     * The class {@code getObject} gives a value of {@code type} as.
     *
     * @param type the type
     * @return the class's name
     */
    static String className(DataType type) {
        return switch (of(type)) {
            case Types.CHAR, Types.VARCHAR -> String.class.getName();
            case Types.DECIMAL -> java.math.BigDecimal.class.getName();
            case Types.REAL -> Float.class.getName();
            case Types.DOUBLE -> Double.class.getName();
            case Types.BOOLEAN -> Boolean.class.getName();
            case Types.DATE -> java.sql.Date.class.getName();
            case Types.TIME -> java.sql.Time.class.getName();
            case Types.TIMESTAMP -> java.sql.Timestamp.class.getName();
            case Types.TIME_WITH_TIMEZONE -> java.time.OffsetTime.class.getName();
            case Types.TIMESTAMP_WITH_TIMEZONE -> java.time.OffsetDateTime.class.getName();
            case Types.BINARY, Types.VARBINARY -> byte[].class.getName();
            case Types.CLOB -> java.sql.Clob.class.getName();
            case Types.BLOB -> java.sql.Blob.class.getName();
            case Types.ARRAY -> java.sql.Array.class.getName();
            case Types.SQLXML -> java.sql.SQLXML.class.getName();
            default -> otherClass(type);
        };
    }

    private static String otherClass(DataType type) {
        if (type instanceof DataType.UuidType) {
            return java.util.UUID.class.getName();
        } else if (type instanceof DataType.ReferenceType) {
            return byte[].class.getName();
        }
        return String.class.getName();
    }

    /**
     * Whether values of {@code type} are numbers, which have a sign.
     *
     * @param type the type
     * @return whether signed
     */
    static boolean signed(DataType type) {
        int jdbc = of(type);
        return jdbc == Types.DECIMAL || jdbc == Types.REAL || jdbc == Types.DOUBLE;
    }

    /**
     * Whether values of {@code type} are compared with their case: character strings.
     *
     * @param type the type
     * @return whether case sensitive
     */
    static boolean caseSensitive(DataType type) {
        int jdbc = of(type);
        return jdbc == Types.CHAR || jdbc == Types.VARCHAR || jdbc == Types.CLOB;
    }

    /** {@code n} as an int, at most the greatest. */
    static int clamp(long n) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, n));
    }
}
