package com.turborel.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.internal.codec.CharUnits;
import com.turborel.jdbc.internal.codec.Column;
import com.turborel.jdbc.internal.codec.DataType;
import com.turborel.jdbc.internal.codec.Value;
import java.math.BigDecimal;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** DatabaseMetaData's mapping of the Information Schema to JDBC's layouts ([DESIGN] D2). */
class MetaDataHelpersTest {
    /**
     * Verifies: [DESIGN] D2 a table of an information schema is a SYSTEM TABLE or SYSTEM VIEW,
     * another a TABLE or VIEW, a temporary table of its own type; a datetime's column size is its
     * characters, a fraction and a zone added; a referential action and a deferrability are JDBC's
     * numbers of them; a name is escaped to a pattern of itself.
     */
    @Test
    void theInformationSchemaMapsToJdbc() {
        assertTrue(
                JdbcDatabaseMetaData.system("INFORMATION_SCHEMA") && JdbcDatabaseMetaData.system("ST_INFORMTN_SCHEMA"));
        assertTrue(JdbcDatabaseMetaData.system("FT_INFORMATION_SCHEMA"));
        assertFalse(JdbcDatabaseMetaData.system("ANN") || JdbcDatabaseMetaData.system(null));
        assertEquals("SYSTEM TABLE", JdbcDatabaseMetaData.tableType("INFORMATION_SCHEMA", "BASE TABLE"));
        assertEquals("TABLE", JdbcDatabaseMetaData.tableType("ANN", "BASE TABLE"));
        assertEquals("SYSTEM VIEW", JdbcDatabaseMetaData.tableType("INFORMATION_SCHEMA", "VIEW"));
        assertEquals("VIEW", JdbcDatabaseMetaData.tableType("ANN", "VIEW"));
        assertEquals("LOCAL TEMPORARY", JdbcDatabaseMetaData.tableType("ANN", "LOCAL TEMPORARY"));
        assertEquals("TABLE", JdbcDatabaseMetaData.tableType("ANN", null));
        assertEquals(10L, JdbcDatabaseMetaData.datetimeSize("DATE", 0L));
        assertEquals(8L, JdbcDatabaseMetaData.datetimeSize("TIME", null));
        assertEquals(12L, JdbcDatabaseMetaData.datetimeSize("TIME", 3L));
        assertEquals(14L, JdbcDatabaseMetaData.datetimeSize("TIME WITH TIME ZONE", 0L));
        assertEquals(26L, JdbcDatabaseMetaData.datetimeSize("TIMESTAMP", 6L));
        assertEquals(32L, JdbcDatabaseMetaData.datetimeSize("TIMESTAMP WITH TIME ZONE", 6L));
        assertNull(JdbcDatabaseMetaData.datetimeSize("INTEGER", null));
        assertEquals(DatabaseMetaData.importedKeyCascade, JdbcDatabaseMetaData.rule("CASCADE"));
        assertEquals(DatabaseMetaData.importedKeyRestrict, JdbcDatabaseMetaData.rule("RESTRICT"));
        assertEquals(DatabaseMetaData.importedKeySetNull, JdbcDatabaseMetaData.rule("SET NULL"));
        assertEquals(DatabaseMetaData.importedKeySetDefault, JdbcDatabaseMetaData.rule("SET DEFAULT"));
        assertEquals(DatabaseMetaData.importedKeyNoAction, JdbcDatabaseMetaData.rule("NO ACTION"));
        assertEquals(DatabaseMetaData.importedKeyNoAction, JdbcDatabaseMetaData.rule(null));
        assertEquals(DatabaseMetaData.importedKeyNotDeferrable, JdbcDatabaseMetaData.deferrability("NO", null));
        assertEquals(DatabaseMetaData.importedKeyInitiallyDeferred, JdbcDatabaseMetaData.deferrability("YES", "YES"));
        assertEquals(DatabaseMetaData.importedKeyInitiallyImmediate, JdbcDatabaseMetaData.deferrability("YES", "NO"));
        assertEquals("A\\_B\\%\\\\", JdbcDatabaseMetaData.escaped("A_B%\\"));
        assertNull(JdbcDatabaseMetaData.escaped(null));
    }

    /**
     * Verifies: [DESIGN] D2 a row of the driver's own query is read by its columns' names, an
     * unnamed column passed over and a name not there the null value; a filter of none is none, a
     * catalog "" none at all.
     */
    @Test
    void rowsAreReadByName() throws SQLException {
        DataType text = new DataType.CharacterType(true, 10, CharUnits.CHARACTERS);
        List<Column> columns = List.of(
                new Column("A", text, false),
                new Column(null, text, false),
                new Column("N", new DataType.ExactType(10, 0), false));
        Named row = Named.of(
                        columns,
                        List.of(List.of(new Value.Text("a"), new Value.Text("b"), Value.Exact.of(BigDecimal.TEN))))
                .get(0);
        assertEquals("a", row.text("A"));
        assertEquals("10", row.text("N"));
        assertEquals(10L, row.whole("N"));
        assertEquals(Value.NULL, row.get("MISSING"));
        assertNull(row.text("MISSING"));
        assertNull(row.whole("MISSING"));
        assertEquals(
                "",
                MetaDataQueries.like("C", null)
                        + MetaDataQueries.equal("C", null)
                        + MetaDataQueries.catalog("C", null));
        assertEquals(" AND 1 = 0", MetaDataQueries.catalog("C", ""));
        assertEquals(" AND C = 'it''s'", MetaDataQueries.equal("C", "it's"));
        assertEquals(Value.NULL, MetaDataQueries.number((Long) null));
        assertEquals(Value.NULL, MetaDataQueries.text(null));
        assertEquals(MetaDataQueries.number(java.sql.Types.OTHER), MetaDataQueries.jdbcType(null));
        assertEquals(
                List.of(List.of(new Value.Text("a"), Value.NULL)),
                MetaDataQueries.values(List.of(row), "A", "MISSING"));
        assertEquals(
                2,
                Arrays.asList(TypeInfo.values()).stream()
                        .filter(t -> t.jdbc() == java.sql.Types.VARBINARY || t.jdbc() == java.sql.Types.BINARY)
                        .count());
    }
}
