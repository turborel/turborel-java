package com.turborel.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.internal.codec.CharUnits;
import com.turborel.jdbc.internal.codec.DataType;
import com.turborel.jdbc.internal.codec.DatetimeKind;
import com.turborel.jdbc.internal.codec.IntervalField;
import com.turborel.jdbc.internal.codec.LobKind;
import com.turborel.jdbc.internal.codec.Qualifier;
import com.turborel.jdbc.internal.codec.TypeName;
import java.sql.Types;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The JDBC type of each type the server sends ([DESIGN] D2, D14). */
class JdbcTypesTest {
    private static final DataType.StructuredType POINT =
            new DataType.StructuredType(new TypeName("ANN", "POINT"), List.of());

    private record Case(DataType type, int jdbc, String name, int precision, int scale, String className) {}

    private static final List<Case> CASES = List.of(
            new Case(
                    new DataType.CharacterType(true, 20, CharUnits.CHARACTERS),
                    Types.VARCHAR,
                    "CHARACTER VARYING",
                    20,
                    0,
                    "java.lang.String"),
            new Case(
                    new DataType.CharacterType(false, 3, CharUnits.CHARACTERS),
                    Types.CHAR,
                    "CHARACTER",
                    3,
                    0,
                    "java.lang.String"),
            new Case(new DataType.ExactType(10, 0), Types.DECIMAL, "DECIMAL", 10, 0, "java.math.BigDecimal"),
            new Case(new DataType.ExactType(9, 2), Types.DECIMAL, "DECIMAL", 9, 2, "java.math.BigDecimal"),
            new Case(new DataType.DecfloatType(34), Types.DECIMAL, "DECFLOAT", 34, 0, "java.math.BigDecimal"),
            new Case(new DataType.ApproximateType(24), Types.REAL, "REAL", 24, 0, "java.lang.Float"),
            new Case(new DataType.ApproximateType(53), Types.DOUBLE, "DOUBLE PRECISION", 53, 0, "java.lang.Double"),
            new Case(new DataType.BooleanType(), Types.BOOLEAN, "BOOLEAN", 0, 0, "java.lang.Boolean"),
            new Case(new DataType.DatetimeType(DatetimeKind.DATE, 0), Types.DATE, "DATE", 10, 0, "java.sql.Date"),
            new Case(new DataType.DatetimeType(DatetimeKind.TIME, 0), Types.TIME, "TIME", 8, 0, "java.sql.Time"),
            new Case(
                    new DataType.DatetimeType(DatetimeKind.TIME_ZONED, 3),
                    Types.TIME_WITH_TIMEZONE,
                    "TIME WITH TIME ZONE",
                    18,
                    3,
                    "java.time.OffsetTime"),
            new Case(
                    new DataType.DatetimeType(DatetimeKind.TIMESTAMP, 6),
                    Types.TIMESTAMP,
                    "TIMESTAMP",
                    26,
                    6,
                    "java.sql.Timestamp"),
            new Case(
                    new DataType.DatetimeType(DatetimeKind.TIMESTAMP_ZONED, 6),
                    Types.TIMESTAMP_WITH_TIMEZONE,
                    "TIMESTAMP WITH TIME ZONE",
                    32,
                    6,
                    "java.time.OffsetDateTime"),
            new Case(new DataType.BinaryType(true, 10), Types.VARBINARY, "BINARY VARYING", 10, 0, "[B"),
            new Case(new DataType.BinaryType(false, 4), Types.BINARY, "BINARY", 4, 0, "[B"),
            new Case(
                    new DataType.LobType(LobKind.CHARACTER, 100, CharUnits.CHARACTERS),
                    Types.CLOB,
                    "CHARACTER LARGE OBJECT",
                    100,
                    0,
                    "java.sql.Clob"),
            new Case(
                    new DataType.LobType(LobKind.BINARY, 100, CharUnits.OCTETS),
                    Types.BLOB,
                    "BINARY LARGE OBJECT",
                    100,
                    0,
                    "java.sql.Blob"),
            new Case(
                    new DataType.ArrayType(10, new DataType.BooleanType()),
                    Types.ARRAY,
                    "ARRAY",
                    0,
                    0,
                    "java.sql.Array"),
            new Case(
                    new DataType.MultisetType(new DataType.BooleanType()),
                    Types.OTHER,
                    "MULTISET",
                    0,
                    0,
                    "java.lang.String"),
            new Case(
                    new DataType.RowType(List.of(new DataType.Field("A", new DataType.BooleanType()))),
                    Types.OTHER,
                    "ROW",
                    0,
                    0,
                    "java.lang.String"),
            new Case(
                    new DataType.IntervalType(new Qualifier(IntervalField.DAY, IntervalField.SECOND, 2, 6)),
                    Types.OTHER,
                    "INTERVAL",
                    0,
                    0,
                    "java.lang.String"),
            new Case(
                    new DataType.XmlType(DataType.XmlPrimary.CONTENT, DataType.XmlSecondary.UNTYPED, null),
                    Types.SQLXML,
                    "XML",
                    0,
                    0,
                    "java.sql.SQLXML"),
            new Case(new DataType.JsonType(), Types.OTHER, "JSON", 0, 0, "java.lang.String"),
            new Case(new DataType.UuidType(), Types.OTHER, "UUID", 36, 0, "java.util.UUID"),
            new Case(POINT, Types.STRUCT, "ANN.POINT", 0, 0, "java.lang.String"),
            new Case(new DataType.ReferenceType(POINT, null), Types.REF, "REF", 0, 0, "[B"),
            new Case(
                    new DataType.PluginType("GEOMETRY", List.of()), Types.OTHER, "GEOMETRY", 0, 0, "java.lang.String"));

    /**
     * Verifies: [DESIGN] D14 each type maps to its JDBC number, name, precision, scale and class as
     * the server describes it: an exact number is DECIMAL of its precision and scale, as the
     * protocol carries an INTEGER as DECIMAL(10, 0).
     */
    @Test
    void eachTypeMapsAsTheServerDescribesIt() {
        for (Case c : CASES) {
            assertEquals(c.jdbc(), JdbcTypes.of(c.type()), c.name());
            assertEquals(c.name(), JdbcTypes.name(c.type()), c.name());
            assertEquals(c.precision(), JdbcTypes.precision(c.type()), c.name());
            assertEquals(c.scale(), JdbcTypes.scale(c.type()), c.name());
            assertEquals(c.className(), JdbcTypes.className(c.type()), c.name());
            assertTrue(JdbcTypes.displaySize(c.type()) > 0, c.name());
        }
        assertEquals(12, JdbcTypes.displaySize(new DataType.ExactType(10, 0)));
        assertEquals(20, JdbcTypes.displaySize(new DataType.BinaryType(true, 10)));
        assertEquals(5, JdbcTypes.displaySize(new DataType.BooleanType()));
        assertEquals(Integer.MAX_VALUE, JdbcTypes.displaySize(new DataType.JsonType()));
        assertTrue(
                JdbcTypes.signed(new DataType.ExactType(10, 0)) && JdbcTypes.signed(new DataType.ApproximateType(53)));
        assertFalse(JdbcTypes.signed(new DataType.BooleanType()));
        assertTrue(JdbcTypes.caseSensitive(new DataType.CharacterType(true, 1, CharUnits.CHARACTERS)));
        assertFalse(JdbcTypes.caseSensitive(new DataType.ExactType(1, 0)));
        assertEquals(Integer.MAX_VALUE, JdbcTypes.clamp(Long.MAX_VALUE));
        assertEquals(0, JdbcTypes.clamp(-1));
    }

    /**
     * Verifies: [DESIGN] D2 a type the Information Schema names maps to its JDBC number, whatever
     * its case; another is OTHER.
     */
    @Test
    void namedTypesMap() {
        assertEquals(Types.INTEGER, JdbcTypes.named("integer"));
        assertEquals(Types.BIGINT, JdbcTypes.named("BIGINT"));
        assertEquals(Types.VARCHAR, JdbcTypes.named("CHARACTER VARYING"));
        assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, JdbcTypes.named("TIMESTAMP WITH TIME ZONE"));
        assertEquals(Types.OTHER, JdbcTypes.named("UUID"));
    }
}
