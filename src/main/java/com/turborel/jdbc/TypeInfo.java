package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.Column;
import com.turborel.jdbc.internal.codec.Value;
import java.sql.DatabaseMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The types Turborel takes, as {@code getTypeInfo} lists them ([DESIGN] D2): each one's JDBC number,
 * its most precision, as INFORMATION_SCHEMA.SQL_SIZING gives it, its literal's prefix and suffix and
 * what its definition takes.
 */
enum TypeInfo {
    BOOLEAN("BOOLEAN", Types.BOOLEAN, 1, null, null),
    SMALLINT("SMALLINT", Types.SMALLINT, Limits.SMALLINT, null, null),
    INTEGER("INTEGER", Types.INTEGER, Limits.INTEGER, null, null),
    BIGINT("BIGINT", Types.BIGINT, Limits.NUMERIC, null, null),
    NUMERIC("NUMERIC", Types.NUMERIC, Limits.NUMERIC, null, "precision,scale"),
    DECIMAL("DECIMAL", Types.DECIMAL, Limits.NUMERIC, null, "precision,scale"),
    DECFLOAT("DECFLOAT", Types.DECIMAL, Limits.DECFLOAT, null, "precision"),
    FLOAT("FLOAT", Types.FLOAT, Limits.FLOAT, null, "precision"),
    REAL("REAL", Types.REAL, Limits.REAL, null, null),
    DOUBLE("DOUBLE PRECISION", Types.DOUBLE, Limits.FLOAT, null, null),
    CHARACTER("CHARACTER", Types.CHAR, Limits.STRING, "'", "length"),
    VARCHAR("CHARACTER VARYING", Types.VARCHAR, Limits.STRING, "'", "length"),
    CLOB("CHARACTER LARGE OBJECT", Types.CLOB, Integer.MAX_VALUE, "'", "length"),
    BINARY("BINARY", Types.BINARY, Limits.STRING, "X'", "length"),
    VARBINARY("BINARY VARYING", Types.VARBINARY, Limits.STRING, "X'", "length"),
    BLOB("BINARY LARGE OBJECT", Types.BLOB, Integer.MAX_VALUE, "X'", "length"),
    DATE("DATE", Types.DATE, Limits.DATE, "DATE '", null),
    TIME("TIME", Types.TIME, Limits.TIME, "TIME '", "precision"),
    TIME_ZONED("TIME WITH TIME ZONE", Types.TIME_WITH_TIMEZONE, Limits.TIME_ZONED, "TIME '", "precision"),
    TIMESTAMP("TIMESTAMP", Types.TIMESTAMP, Limits.TIMESTAMP, "TIMESTAMP '", "precision"),
    TIMESTAMP_ZONED(
            "TIMESTAMP WITH TIME ZONE",
            Types.TIMESTAMP_WITH_TIMEZONE,
            Limits.TIMESTAMP_ZONED,
            "TIMESTAMP '",
            "precision"),
    INTERVAL("INTERVAL", Types.OTHER, 0, "INTERVAL '", "fields"),
    XML("XML", Types.SQLXML, 0, null, null),
    JSON("JSON", Types.OTHER, 0, null, null),
    UUID("UUID", Types.OTHER, Limits.UUID, null, null),
    ARRAY("ARRAY", Types.ARRAY, 0, "ARRAY[", "element,cardinality");

    /** The limits of the types' precision. */
    private static final class Limits {
        static final int SMALLINT = 5;
        static final int INTEGER = 10;
        static final int NUMERIC = 18;
        static final int DECFLOAT = 34;
        static final int REAL = 24;
        static final int FLOAT = 53;
        static final int STRING = 32767;
        static final int DATE = 10;
        static final int TIME = 15;
        static final int TIME_ZONED = 21;
        static final int TIMESTAMP = 26;
        static final int TIMESTAMP_ZONED = 32;
        static final int UUID = 36;
        static final int FRACTION = 6;
        static final int DECIMAL_RADIX = 10;
        static final int BINARY_RADIX = 2;
        static final java.util.Set<Integer> APPROXIMATE = java.util.Set.of(Types.REAL, Types.FLOAT, Types.DOUBLE);
        static final java.util.Set<Integer> NUMBERS = java.util.Set.of(
                Types.DECIMAL,
                Types.NUMERIC,
                Types.SMALLINT,
                Types.INTEGER,
                Types.BIGINT,
                Types.REAL,
                Types.FLOAT,
                Types.DOUBLE);
        static final java.util.Set<Integer> FRACTIONS =
                java.util.Set.of(Types.TIME, Types.TIMESTAMP, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE);

        private Limits() {}
    }

    private final String name;
    private final int jdbc;
    private final int precision;
    private final @Nullable String prefix;
    private final @Nullable String params;

    TypeInfo(String name, int jdbc, int precision, @Nullable String prefix, @Nullable String params) {
        this.name = name;
        this.jdbc = jdbc;
        this.precision = precision;
        this.prefix = prefix;
        this.params = params;
    }

    /**
     * Its JDBC type's number.
     *
     * @return the number
     */
    int jdbc() {
        return jdbc;
    }

    /** The suffix of a literal of the prefix given. */
    private static @Nullable String suffix(@Nullable String prefix) {
        if (prefix == null) {
            return null;
        }
        return prefix.endsWith("[") ? "]" : "'";
    }

    /** Its row of {@code getTypeInfo}. */
    List<Value> row() {
        boolean number = Limits.NUMBERS.contains(jdbc);
        boolean exact = jdbc == Types.DECIMAL || jdbc == Types.NUMERIC;
        boolean fraction = Limits.FRACTIONS.contains(jdbc);
        boolean text = jdbc == Types.CHAR || jdbc == Types.VARCHAR || jdbc == Types.CLOB;
        List<Value> row = new ArrayList<>();
        row.addAll(List.of(
                MetaDataQueries.text(name),
                MetaDataQueries.number(jdbc),
                MetaDataQueries.number(precision),
                MetaDataQueries.text(prefix),
                MetaDataQueries.text(suffix(prefix)),
                MetaDataQueries.text(params),
                MetaDataQueries.number(DatabaseMetaData.typeNullable),
                new Value.Truth(text),
                MetaDataQueries.number(DatabaseMetaData.typeSearchable),
                new Value.Truth(!number)));
        int scale = exact ? Limits.NUMERIC : fraction ? Limits.FRACTION : 0;
        int radix = Limits.APPROXIMATE.contains(jdbc) ? Limits.BINARY_RADIX : Limits.DECIMAL_RADIX;
        row.addAll(List.of(
                new Value.Truth(false),
                new Value.Truth(false),
                MetaDataQueries.text(name),
                MetaDataQueries.number(0),
                MetaDataQueries.number(scale),
                Value.NULL,
                Value.NULL,
                MetaDataQueries.number(radix)));
        return row;
    }

    /** The columns of {@code getTypeInfo}. */
    static List<Column> shape() {
        return List.of(
                MetaDataQueries.column("TYPE_NAME", MetaDataQueries.NAME),
                MetaDataQueries.column("DATA_TYPE", MetaDataQueries.INT),
                MetaDataQueries.column("PRECISION", MetaDataQueries.INT),
                MetaDataQueries.column("LITERAL_PREFIX", MetaDataQueries.NAME),
                MetaDataQueries.column("LITERAL_SUFFIX", MetaDataQueries.NAME),
                MetaDataQueries.column("CREATE_PARAMS", MetaDataQueries.NAME),
                MetaDataQueries.column("NULLABLE", MetaDataQueries.SHORT),
                MetaDataQueries.column("CASE_SENSITIVE", MetaDataQueries.TRUTH),
                MetaDataQueries.column("SEARCHABLE", MetaDataQueries.SHORT),
                MetaDataQueries.column("UNSIGNED_ATTRIBUTE", MetaDataQueries.TRUTH),
                MetaDataQueries.column("FIXED_PREC_SCALE", MetaDataQueries.TRUTH),
                MetaDataQueries.column("AUTO_INCREMENT", MetaDataQueries.TRUTH),
                MetaDataQueries.column("LOCAL_TYPE_NAME", MetaDataQueries.NAME),
                MetaDataQueries.column("MINIMUM_SCALE", MetaDataQueries.SHORT),
                MetaDataQueries.column("MAXIMUM_SCALE", MetaDataQueries.SHORT),
                MetaDataQueries.column("SQL_DATA_TYPE", MetaDataQueries.INT),
                MetaDataQueries.column("SQL_DATETIME_SUB", MetaDataQueries.INT),
                MetaDataQueries.column("NUM_PREC_RADIX", MetaDataQueries.INT));
    }
}
