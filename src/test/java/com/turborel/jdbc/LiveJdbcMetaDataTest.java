package com.turborel.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.testing.LiveServer;
import java.io.IOException;
import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * A live database's metadata, as a GUI tool browses it ([DESIGN] D2): schemas, tables, columns,
 * keys, indexes and routines.
 */
@EnabledIfEnvironmentVariable(named = LiveServer.VARIABLE, matches = ".+")
class LiveJdbcMetaDataTest {
    private static final String[] SCHEMA = {
        "CREATE TABLE JM_PARENT (ID INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY, NAME VARCHAR(20) NOT NULL,"
                + " PRICE DECIMAL(9,2) DEFAULT 1.5, STAMP TIMESTAMP(3), D DATE, TZ TIME WITH TIME ZONE, IV INTERVAL DAY TO SECOND)",
        "CREATE TABLE JM_CHILD (K INTEGER PRIMARY KEY, PARENT INTEGER REFERENCES JM_PARENT(ID) ON DELETE CASCADE)",
        "CREATE INDEX JM_CHILD_PARENT ON JM_CHILD (PARENT)",
        "CREATE TABLE JM_OTHER (K INTEGER PRIMARY KEY, A INTEGER, B INTEGER, CONSTRAINT JM_RULES FOREIGN KEY (A)"
                + " REFERENCES JM_PARENT (ID) ON UPDATE RESTRICT ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED)",
        "CREATE INDEX JM_OTHER_AB ON JM_OTHER (A, B)",
        "CREATE VIEW JM_VIEW AS SELECT ID, NAME FROM JM_PARENT",
        "CREATE FUNCTION JM_ADD1 (N INTEGER) RETURNS INTEGER CONTAINS SQL RETURN N + 1",
        "CREATE PROCEDURE JM_TWICE (IN A INTEGER, OUT B INTEGER, INOUT C INTEGER) CONTAINS SQL BEGIN SET B = A * 2;"
                + " SET C = C + 1; END"
    };

    /** Makes the tables and routines the tests read, once for every test of a run. */
    private static synchronized void made(Connection c) throws SQLException {
        try (ResultSet rs = c.getMetaData().getTables(null, "ANN", "JM_PARENT", null)) {
            if (rs.next()) {
                return;
            }
        }
        LiveJdbc.run(c, SCHEMA);
    }

    /** The values of column {@code column} of each row, the result set closed. */
    private static List<String> column(ResultSet rs, String column) throws SQLException {
        List<String> found = new ArrayList<>();
        try (rs) {
            while (rs.next()) {
                found.add(rs.getString(column));
            }
        }
        return found;
    }

    /**
     * Verifies: [DESIGN] D2 the catalogs, schemas and tables are listed as JDBC lays them: a base
     * table a TABLE, a view a VIEW, those of the information schemas SYSTEM ones; filtered by
     * pattern, type and catalog, a catalog "" matching none.
     */
    @Test
    void schemasAndTables() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect()) {
            made(c);
            DatabaseMetaData md = c.getMetaData();
            assertEquals(List.of("TURBOREL"), column(md.getCatalogs(), "TABLE_CAT"));
            assertTrue(column(md.getSchemas(), "TABLE_SCHEM").containsAll(List.of("ANN", "INFORMATION_SCHEMA")));
            assertEquals(List.of("ANN"), column(md.getSchemas("TURBOREL", "AN_"), "TABLE_SCHEM"));
            assertTrue(column(md.getSchemas("", null), "TABLE_SCHEM").isEmpty());
            assertEquals(
                    List.of("JM_CHILD", "JM_OTHER", "JM_PARENT", "JM_VIEW"),
                    column(md.getTables(null, "ANN", "JM\\_%", null), "TABLE_NAME"));
            assertEquals(
                    List.of("TABLE", "TABLE", "TABLE", "VIEW"),
                    column(md.getTables(null, "ANN", "JM%", null), "TABLE_TYPE"));
            assertEquals(
                    List.of("JM_VIEW"), column(md.getTables(null, "ANN", "JM%", new String[] {"VIEW"}), "TABLE_NAME"));
            assertEquals(
                    "SYSTEM VIEW",
                    column(md.getTables(null, "INFORMATION_SCHEMA", "TABLES", null), "TABLE_TYPE")
                            .get(0));
            assertTrue(column(md.getTables("", null, null, null), "TABLE_NAME").isEmpty());
            assertEquals(
                    List.of("GLOBAL TEMPORARY", "LOCAL TEMPORARY", "SYSTEM TABLE", "SYSTEM VIEW", "TABLE", "VIEW"),
                    column(md.getTableTypes(), "TABLE_TYPE"));
        }
    }

    /**
     * Verifies: [DESIGN] D2 a table's columns are listed in order, each its JDBC type from the
     * Information Schema's name of it, its size, digits, nullability, default, and whether it is an
     * identity.
     */
    @Test
    void columnsAreListed() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect()) {
            made(c);
            DatabaseMetaData md = c.getMetaData();
            try (ResultSet rs = md.getColumns(null, "ANN", "JM_PARENT", "%")) {
                assertTrue(rs.next());
                assertEquals("ID", rs.getString("COLUMN_NAME"));
                assertEquals(Types.INTEGER, rs.getInt("DATA_TYPE"));
                assertEquals("YES", rs.getString("IS_AUTOINCREMENT"));
                assertEquals(DatabaseMetaData.columnNoNulls, rs.getInt("NULLABLE"));
                assertTrue(rs.next());
                assertEquals(Types.VARCHAR, rs.getInt("DATA_TYPE"));
                assertEquals(20, rs.getInt("COLUMN_SIZE"));
                assertTrue(rs.next());
                assertEquals(Types.DECIMAL, rs.getInt("DATA_TYPE"));
                assertEquals(9, rs.getInt("COLUMN_SIZE"));
                assertEquals(2, rs.getInt("DECIMAL_DIGITS"));
                assertNotNull(rs.getString("COLUMN_DEF"));
                assertEquals("YES", rs.getString("IS_NULLABLE"));
                assertTrue(rs.next());
                assertEquals(Types.TIMESTAMP, rs.getInt("DATA_TYPE"));
                assertEquals(23, rs.getInt("COLUMN_SIZE"));
                assertEquals(3, rs.getInt("DECIMAL_DIGITS"));
                assertTrue(rs.next());
                assertEquals(10, rs.getInt("COLUMN_SIZE"));
                assertTrue(rs.next());
                assertEquals(Types.TIME_WITH_TIMEZONE, rs.getInt("DATA_TYPE"));
                assertTrue(rs.next());
                assertEquals("INTERVAL DAY TO SECOND", rs.getString("TYPE_NAME"));
                assertFalse(rs.next());
            }
        }
    }

    /**
     * Verifies: [DESIGN] D2 the primary key, foreign keys both ways and between two tables, and
     * indexes, as JDBC lays them; the best row identifier is the primary key; privileges and the
     * types are listed.
     */
    @Test
    void keysAndIndexes() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect()) {
            made(c);
            DatabaseMetaData md = c.getMetaData();
            assertEquals(List.of("ID"), column(md.getPrimaryKeys(null, "ANN", "JM_PARENT"), "COLUMN_NAME"));
            try (ResultSet rs = md.getImportedKeys(null, "ANN", "JM_CHILD")) {
                assertTrue(rs.next());
                assertEquals("JM_PARENT", rs.getString("PKTABLE_NAME"));
                assertEquals("ID", rs.getString("PKCOLUMN_NAME"));
                assertEquals("PARENT", rs.getString("FKCOLUMN_NAME"));
                assertEquals(1, rs.getInt("KEY_SEQ"));
                assertEquals(DatabaseMetaData.importedKeyCascade, rs.getInt("DELETE_RULE"));
                assertEquals(DatabaseMetaData.importedKeyNoAction, rs.getInt("UPDATE_RULE"));
                assertEquals(DatabaseMetaData.importedKeyNotDeferrable, rs.getInt("DEFERRABILITY"));
            }
            assertEquals(
                    List.of("JM_CHILD", "JM_OTHER"),
                    column(md.getExportedKeys(null, "ANN", "JM_PARENT"), "FKTABLE_NAME"));
            assertEquals(
                    List.of("PARENT"),
                    column(md.getCrossReference(null, "ANN", "JM_PARENT", null, "ANN", "JM_CHILD"), "FKCOLUMN_NAME"));
            assertEquals(
                    List.of("K", "PARENT"),
                    column(md.getIndexInfo(null, "ANN", "JM_CHILD", false, true), "COLUMN_NAME"));
            assertEquals(List.of("K"), column(md.getIndexInfo(null, "ANN", "JM_CHILD", true, true), "COLUMN_NAME"));
            assertEquals(
                    List.of("ID"),
                    column(
                            md.getBestRowIdentifier(null, "ANN", "JM_PARENT", DatabaseMetaData.bestRowSession, true),
                            "COLUMN_NAME"));
            assertFalse(column(md.getTablePrivileges(null, "ANN", "JM_PARENT"), "PRIVILEGE")
                    .isEmpty());
            assertNotNull(column(md.getColumnPrivileges(null, "ANN", "JM_PARENT", "%"), "PRIVILEGE"));
            assertTrue(
                    column(md.getTypeInfo(), "TYPE_NAME").containsAll(List.of("INTEGER", "CHARACTER VARYING", "UUID")));
        }
    }

    /**
     * Verifies: [DESIGN] D2 a foreign key's actions and deferrability are JDBC's numbers of them; an
     * index of several columns lists each in order, ascending.
     */
    @Test
    void rulesAndDirections() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect()) {
            made(c);
            DatabaseMetaData md = c.getMetaData();
            try (ResultSet rs = md.getImportedKeys(null, "ANN", "JM_OTHER")) {
                assertTrue(rs.next());
                assertEquals(DatabaseMetaData.importedKeyRestrict, rs.getInt("UPDATE_RULE"));
                assertEquals(DatabaseMetaData.importedKeySetNull, rs.getInt("DELETE_RULE"));
                assertEquals(DatabaseMetaData.importedKeyInitiallyDeferred, rs.getInt("DEFERRABILITY"));
            }
            try (ResultSet rs = md.getIndexInfo(null, "ANN", "JM_OTHER", false, true)) {
                List<String> found = new ArrayList<>();
                while (rs.next()) {
                    found.add(rs.getString("INDEX_NAME") + " " + rs.getString("COLUMN_NAME") + " "
                            + rs.getString("ASC_OR_DESC"));
                }
                assertTrue(found.contains("JM_OTHER_AB A A") && found.contains("JM_OTHER_AB B A"), found::toString);
            }
        }
    }

    /**
     * Verifies: [DESIGN] D2 procedures and functions are listed apart, each one's parameters in
     * order, IN, OUT and INOUT, a function's result its column of place 0.
     */
    @Test
    void routinesAreListed() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect()) {
            made(c);
            DatabaseMetaData md = c.getMetaData();
            assertEquals(List.of("JM_TWICE"), column(md.getProcedures(null, "ANN", "JM%"), "PROCEDURE_NAME"));
            assertEquals(List.of("JM_ADD1"), column(md.getFunctions(null, "ANN", "JM%"), "FUNCTION_NAME"));
            try (ResultSet rs = md.getProcedureColumns(null, "ANN", "JM_TWICE", "%")) {
                int[] modes = {
                    DatabaseMetaData.procedureColumnIn,
                    DatabaseMetaData.procedureColumnOut,
                    DatabaseMetaData.procedureColumnInOut
                };
                for (int mode : modes) {
                    assertTrue(rs.next());
                    assertEquals(mode, rs.getInt("COLUMN_TYPE"));
                    assertEquals(Types.INTEGER, rs.getInt("DATA_TYPE"));
                }
            }
            try (ResultSet rs = md.getFunctionColumns(null, "ANN", "JM_ADD1", null)) {
                assertTrue(rs.next());
                assertEquals(DatabaseMetaData.functionReturn, rs.getInt("COLUMN_TYPE"));
                assertTrue(rs.next());
                assertEquals("N", rs.getString("COLUMN_NAME"));
                assertEquals(DatabaseMetaData.functionColumnIn, rs.getInt("COLUMN_TYPE"));
            }
            assertEquals(List.of("N"), column(md.getFunctionColumns(null, "ANN", "JM_ADD1", "N"), "COLUMN_NAME"));
        }
    }

    /**
     * Verifies: [DESIGN] D2 a procedure is called by JDBC's escape, its IN parameters set, its OUT and
     * INOUT ones registered and read back by place and by name; a parameter not registered, or of
     * no value, and by name, are refused.
     */
    @Test
    void aProcedureIsCalled() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect()) {
            made(c);
            try (CallableStatement call = c.prepareCall("{call JM_TWICE(?, ?, ?)}")) {
                call.setInt(1, 21);
                call.registerOutParameter(2, Types.INTEGER);
                call.setInt(3, 5);
                call.registerOutParameter(3, Types.INTEGER);
                assertFalse(call.execute());
                assertEquals(42, call.getInt(2));
                assertFalse(call.wasNull());
                assertEquals(6, call.getInt(3));
                assertEquals(42, call.getInt("B"));
                assertEquals(
                        "TB910",
                        assertThrows(SQLException.class, () -> call.getInt(1)).getSQLState());
                call.clearParameters();
                assertEquals(
                        "07001", assertThrows(SQLException.class, call::execute).getSQLState());
                assertEquals(
                        "TB910",
                        assertThrows(SQLException.class, () -> call.setInt(0, 1))
                                .getSQLState());
                assertEquals(
                        "TB910",
                        assertThrows(SQLException.class, () -> call.registerOutParameter(0, Types.INTEGER))
                                .getSQLState());
                assertEquals(
                        "0A000",
                        assertThrows(SQLException.class, () -> call.setInt("A", 1))
                                .getSQLState());
                assertEquals(
                        "0A000",
                        assertThrows(SQLException.class, () -> call.registerOutParameter("B", Types.INTEGER))
                                .getSQLState());
            }
            try (CallableStatement call = c.prepareCall("{call JM_TWICE(?, ?, ?)}")) {
                assertEquals(
                        "TB910",
                        assertThrows(SQLException.class, () -> call.getInt(2)).getSQLState());
            }
        }
    }

    /**
     * Verifies: [DESIGN] D2 every method of the metadata's facts and results answers, or refuses with
     * the driver's failure; the metadata names the product, the driver and the connection.
     */
    @Test
    void everyMethodAnswers() throws SQLException, IOException, IllegalAccessException {
        try (Connection c = LiveJdbc.connect()) {
            made(c);
            DatabaseMetaData md = c.getMetaData();
            for (Method m : DatabaseMetaData.class.getMethods()) {
                if (m.getParameterCount() <= 2 && !m.getName().equals("unwrap")) {
                    Reflection.invoke(m, md, Reflection.arguments(m));
                }
            }
            for (Method m : DatabaseMetaData.class.getMethods()) {
                if (m.getParameterCount() >= 3 && m.getReturnType() == ResultSet.class) {
                    Object result = Reflection.invoke(m, md, Reflection.arguments(m));
                    if (result instanceof ResultSet rs) {
                        rs.close();
                    }
                }
            }
            assertEquals("turborel", md.getDatabaseProductName());
            assertTrue(md.getDatabaseProductVersion().contains("0.0.0"));
            assertEquals(0, md.getDatabaseMajorVersion() + md.getDatabaseMinorVersion());
            assertEquals("ANN", md.getUserName());
            assertEquals(LiveJdbc.URL, md.getURL());
            assertSame(c, md.getConnection());
            assertFalse(md.isReadOnly());
            assertTrue(md.supportsConvert(Types.INTEGER, Types.VARCHAR));
            assertFalse(md.supportsConvert(Types.INTEGER, Types.DATE));
            assertTrue(md.supportsTransactionIsolationLevel(Connection.TRANSACTION_SERIALIZABLE));
            assertTrue(md.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
            assertTrue(md.isWrapperFor(DatabaseMetaData.class));
            assertSame(md, md.unwrap(DatabaseMetaData.class));
        }
    }

    /**
     * Verifies: [DESIGN] D2 every method of the callable statement's getters and registrations by
     * index and by name answers, or refuses with the driver's failure.
     */
    @Test
    void everyCallableMethodAnswers() throws SQLException, IOException, IllegalAccessException {
        try (Connection c = LiveJdbc.connect()) {
            made(c);
            try (CallableStatement call = c.prepareCall("{call JM_TWICE(?, ?, ?)}")) {
                call.setInt(1, 1);
                call.setInt(3, 1);
                call.registerOutParameter(2, Types.INTEGER, 0);
                call.registerOutParameter(3, Types.INTEGER, "INTEGER");
                call.registerOutParameter(3, java.sql.JDBCType.INTEGER);
                call.registerOutParameter(3, java.sql.JDBCType.INTEGER, 0);
                call.registerOutParameter(3, java.sql.JDBCType.INTEGER, "INTEGER");
                call.execute();
                for (Method m : CallableStatement.class.getDeclaredMethods()) {
                    if ((m.getName().startsWith("get")
                                    || m.getName().startsWith("set")
                                    || m.getName().startsWith("register"))
                            && m.getParameterCount() >= 1) {
                        @Nullable Object[] args = Reflection.arguments(m);
                        if (m.getParameterTypes()[0] == int.class) {
                            args[0] = 2;
                        } else if (m.getParameterTypes()[0] == String.class) {
                            args[0] = "B";
                        }
                        Reflection.invoke(m, call, args);
                    }
                }
            }
        }
    }

    /**
     * Verifies: [CLIENT Prepared statements] a prepared CALL's parameters each have their mode,
     * granted {@code parameter-modes}, which connecting asks for: ParameterMetaData counts every
     * {@code ?} and gives IN, OUT and INOUT; an OUT one takes no value and an IN one is no OUT
     * parameter (SQLSTATE TB910); the OUT values are read back from the CALL's result, registered
     * or not ([PROTOCOL Parameter modes]).
     */
    @Test
    void aCallsModesAreTold() throws SQLException, IOException {
        try (Connection c = LiveJdbc.connect()) {
            made(c);
            assertTrue(c.unwrap(TurborelConnection.class).granted().contains("parameter-modes"));
            try (CallableStatement call = c.prepareCall("{call JM_TWICE(?, ?, ?)}")) {
                ParameterMetaData pm = call.getParameterMetaData();
                assertEquals(3, pm.getParameterCount());
                assertEquals(ParameterMetaData.parameterModeIn, pm.getParameterMode(1));
                assertEquals(ParameterMetaData.parameterModeOut, pm.getParameterMode(2));
                assertEquals(ParameterMetaData.parameterModeInOut, pm.getParameterMode(3));
                assertEquals(
                        "TB910",
                        assertThrows(SQLException.class, () -> call.setInt(2, 1))
                                .getSQLState());
                assertEquals(
                        "TB910",
                        assertThrows(SQLException.class, () -> call.registerOutParameter(1, Types.INTEGER))
                                .getSQLState());
                assertEquals(
                        "TB910",
                        assertThrows(SQLException.class, () -> call.registerOutParameter(9, Types.INTEGER))
                                .getSQLState());
                call.setInt(1, 21);
                call.setInt(3, 5);
                assertFalse(call.execute());
                assertEquals(42, call.getInt(2));
                assertEquals(6, call.getInt(3));
            }
            try (PreparedStatement prep = c.prepareStatement("CALL JM_TWICE(?, ?, ?)")) {
                assertEquals(3, prep.getParameterMetaData().getParameterCount());
                assertEquals(
                        "TB910",
                        assertThrows(SQLException.class, () -> prep.setInt(2, 1))
                                .getSQLState());
                assertEquals(
                        "TB910",
                        assertThrows(SQLException.class, () -> prep.setInt(9, 1))
                                .getSQLState());
                prep.setInt(1, 4);
                prep.setInt(3, 7);
                try (ResultSet rs = prep.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(8, rs.getInt("B"));
                    assertEquals(8, rs.getInt("C"));
                }
            }
        }
    }
}
