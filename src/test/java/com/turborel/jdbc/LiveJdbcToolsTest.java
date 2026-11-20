package com.turborel.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.testing.LiveServer;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The driver as a database tool loads it ([DESIGN] D2): DBeaver and DataGrip load a driver's jar in
 * a class loader of their own, make its Driver by its class's name, connect by {@code connect} and
 * not by DriverManager, then read the database's metadata to browse it.
 */
@EnabledIfEnvironmentVariable(named = LiveServer.VARIABLE, matches = ".+")
class LiveJdbcToolsTest {
    /** The driver made from the driver's own code alone, in a class loader of its own. */
    private static java.sql.Driver loaded(URLClassLoader loader) throws ReflectiveOperationException {
        Class<?> made = Class.forName("com.turborel.jdbc.Driver", true, loader);
        assertNotSame(Driver.class, made, "its own class loader's");
        return (java.sql.Driver) made.getDeclaredConstructor().newInstance();
    }

    /** The names a metadata result set gives in {@code column}. */
    private static List<String> names(ResultSet rs, String column) throws SQLException {
        List<String> names = new ArrayList<>();
        try (rs) {
            while (rs.next()) {
                names.add(rs.getString(column));
            }
        }
        return names;
    }

    /**
     * Verifies: [DESIGN] D2 the driver's code alone, its jar needing nothing more, loaded in a class
     * loader of a tool's, is made by its class's name, refuses another's URL with none, lists its
     * settings, and connects by {@code connect}; the metadata a tool reads as it opens a connection
     * answers: the product and driver, the catalogs, schemas, table types and types, a schema's
     * tables, their columns of their declared types, keys and indexes, and its routines.
     */
    @Test
    void aToolLoadsTheDriverAndBrowses() throws SQLException, IOException, ReflectiveOperationException {
        try (Connection setup = LiveJdbc.connect()) {
            LiveJdbc.run(setup, "CREATE TABLE JDBC_TOOLS (ID INTEGER PRIMARY KEY, N VARCHAR(10))");
        }
        URL code = Driver.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {code}, ClassLoader.getPlatformClassLoader())) {
            java.sql.Driver driver = loaded(loader);
            Properties info = new Properties();
            info.setProperty("user", "ann");
            assertNull(driver.connect("jdbc:other://localhost/", info));
            assertTrue(driver.getPropertyInfo(LiveJdbc.URL, info).length > 0);
            try (Connection c = driver.connect(LiveJdbc.URL, info)) {
                assertNotNull(c);
                browse(c.getMetaData());
                try (Statement s = c.createStatement();
                        ResultSet rs = s.executeQuery("SELECT ID, N FROM JDBC_TOOLS")) {
                    assertEquals(Types.INTEGER, rs.getMetaData().getColumnType(1));
                    assertFalse(rs.next());
                }
            }
        }
    }

    private static void browse(DatabaseMetaData md) throws SQLException {
        assertEquals("turborel", md.getDatabaseProductName().toLowerCase(java.util.Locale.ROOT));
        assertNotNull(md.getDatabaseProductVersion());
        assertNotNull(md.getDriverName());
        assertEquals("\"", md.getIdentifierQuoteString());
        assertFalse(names(md.getCatalogs(), "TABLE_CAT").isEmpty());
        assertTrue(names(md.getSchemas(), "TABLE_SCHEM").contains("ANN"));
        assertTrue(names(md.getTableTypes(), "TABLE_TYPE").contains("TABLE"));
        assertTrue(names(md.getTypeInfo(), "TYPE_NAME").contains("INTEGER"));
        assertTrue(names(md.getTables(null, "ANN", "%", null), "TABLE_NAME").contains("JDBC_TOOLS"));
        assertEquals(
                List.of("INTEGER", "CHARACTER VARYING"),
                names(md.getColumns(null, "ANN", "JDBC_TOOLS", "%"), "TYPE_NAME"));
        assertEquals(List.of("ID"), names(md.getPrimaryKeys(null, "ANN", "JDBC_TOOLS"), "COLUMN_NAME"));
        assertTrue(names(md.getImportedKeys(null, "ANN", "JDBC_TOOLS"), "FKCOLUMN_NAME")
                .isEmpty());
        assertNotNull(names(md.getIndexInfo(null, "ANN", "JDBC_TOOLS", false, true), "INDEX_NAME"));
        assertNotNull(names(md.getProcedures(null, "ANN", "%"), "PROCEDURE_NAME"));
        assertNotNull(names(md.getFunctions(null, "ANN", "%"), "FUNCTION_NAME"));
    }
}
