package com.turborel.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.testing.LiveServer;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The types of columns and parameters through JDBC on a live server ([DESIGN] D14). */
@EnabledIfEnvironmentVariable(named = LiveServer.VARIABLE, matches = ".+")
class LiveJdbcTypesTest {
    /** Each column's name, its JDBC type, its type's name and its object's class, as declared. */
    private static final Object[][] DECLARED = {
        {"A", Types.INTEGER, "INTEGER", Integer.class},
        {"B", Types.SMALLINT, "SMALLINT", Integer.class},
        {"C", Types.BIGINT, "BIGINT", Long.class},
        {"D", Types.DECIMAL, "DECIMAL", java.math.BigDecimal.class},
        {"E", Types.NUMERIC, "NUMERIC", java.math.BigDecimal.class},
        {"F", Types.REAL, "REAL", Float.class},
        {"G", Types.DOUBLE, "DOUBLE PRECISION", Double.class},
        {"H", Types.FLOAT, "FLOAT", Double.class},
        {"I", Types.ARRAY, "ARRAY", java.sql.Array.class},
    };

    /**
     * Verifies: [PROTOCOL Declared types] each column's declared type is its JDBC type, granted
     * {@code declared-types}, which connecting asks for: an INTEGER INTEGER and an Integer, a
     * BIGINT BIGINT and a Long, a FLOAT FLOAT and a Double; an array's elements of its element's
     * declared type ([CLIENT Prepared statements]).
     */
    @Test
    void declaredTypesAreShown() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect();
                Statement s = c.createStatement()) {
            assertTrue(c.unwrap(TurborelConnection.class).granted().contains("declared-types"));
            LiveJdbc.run(
                    c,
                    "CREATE TABLE JDBC_DECLARED (A INTEGER, B SMALLINT, C BIGINT, D DECIMAL(9,2),"
                            + " E NUMERIC(5), F REAL, G DOUBLE PRECISION, H FLOAT(20), I INTEGER ARRAY[3])");
            s.executeUpdate("INSERT INTO JDBC_DECLARED VALUES (1, 2, 3, 4.5, 6, 7, 8, 9, ARRAY[10])");
            try (ResultSet rs = s.executeQuery("SELECT * FROM JDBC_DECLARED")) {
                ResultSetMetaData md = rs.getMetaData();
                assertTrue(rs.next());
                for (int i = 0; i < DECLARED.length; i++) {
                    Object[] row = DECLARED[i];
                    assertEquals(row[0], md.getColumnName(i + 1));
                    assertEquals(row[1], md.getColumnType(i + 1), row[0].toString());
                    assertEquals(row[2], md.getColumnTypeName(i + 1));
                    assertEquals(((Class<?>) row[3]).getName(), md.getColumnClassName(i + 1));
                    assertInstanceOf((Class<?>) row[3], rs.getObject(i + 1), row[0].toString());
                }
                assertEquals(5, md.getPrecision(5));
                java.sql.Array a = rs.getArray(9);
                assertEquals(Types.INTEGER, a.getBaseType());
                assertEquals(10, ((Object[]) a.getArray())[0]);
            }
        }
    }

    /**
     * Verifies: [DESIGN] D14 a result set's metadata gives each column's name, its declared type,
     * so an INTEGER is INTEGER and its object an Integer, a DECIMAL DECIMAL of its precision and
     * scale, and whether it takes nulls; a prepared statement's parameter metadata each
     * parameter's, as its data type says ([PROTOCOL Declared types]).
     */
    @Test
    void metadataIsTheServers() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect()) {
            LiveJdbc.run(c, "CREATE TABLE JDBC_META (ID INTEGER PRIMARY KEY, N VARCHAR(20), P DECIMAL(9,2))");
            try (PreparedStatement p = c.prepareStatement("SELECT ID, N, P FROM JDBC_META WHERE ID = ?")) {
                ResultSetMetaData md = p.getMetaData();
                assertEquals(3, md.getColumnCount());
                assertEquals("ID", md.getColumnName(1));
                assertEquals("ID", md.getColumnLabel(1));
                assertEquals(Types.INTEGER, md.getColumnType(1));
                assertEquals("INTEGER", md.getColumnTypeName(1));
                assertEquals("java.lang.Integer", md.getColumnClassName(1));
                assertEquals(Types.DECIMAL, md.getColumnType(3));
                assertEquals("DECIMAL", md.getColumnTypeName(3));
                assertEquals(10, md.getPrecision(1));
                assertEquals(0, md.getScale(1));
                assertEquals(ResultSetMetaData.columnNoNulls, md.isNullable(1));
                assertEquals(ResultSetMetaData.columnNullable, md.isNullable(2));
                assertEquals(Types.VARCHAR, md.getColumnType(2));
                assertEquals(20, md.getColumnDisplaySize(2));
                assertEquals(2, md.getScale(3));
                assertEquals("java.math.BigDecimal", md.getColumnClassName(3));
                assertTrue(md.isSigned(3) && md.isCaseSensitive(2) && md.isSearchable(1));
                assertFalse(md.isAutoIncrement(1) || md.isCurrency(1) || md.isReadOnly(1));
                assertFalse(md.isWritable(1) || md.isDefinitelyWritable(1));
                assertEquals("", md.getTableName(1) + md.getSchemaName(1) + md.getCatalogName(1));
                assertEquals(
                        "TB910",
                        assertThrows(SQLException.class, () -> md.getColumnName(4))
                                .getSQLState());
                assertTrue(md.isWrapperFor(ResultSetMetaData.class));
                assertEquals(md, md.unwrap(ResultSetMetaData.class));
                parameters(p.getParameterMetaData());
            }
        }
    }

    private static void parameters(ParameterMetaData pm) throws SQLException {
        assertEquals(1, pm.getParameterCount());
        assertEquals(Types.DECIMAL, pm.getParameterType(1));
        assertEquals("DECIMAL", pm.getParameterTypeName(1));
        assertEquals(10, pm.getPrecision(1));
        assertEquals(0, pm.getScale(1));
        assertTrue(pm.isSigned(1));
        assertEquals(ParameterMetaData.parameterModeIn, pm.getParameterMode(1));
        assertEquals(ParameterMetaData.parameterNullableUnknown, pm.isNullable(1));
        assertEquals("java.math.BigDecimal", pm.getParameterClassName(1));
        assertEquals(
                "TB910",
                assertThrows(SQLException.class, () -> pm.getParameterType(2)).getSQLState());
        assertTrue(pm.isWrapperFor(ParameterMetaData.class));
        assertEquals(pm, pm.unwrap(ParameterMetaData.class));
    }
}
