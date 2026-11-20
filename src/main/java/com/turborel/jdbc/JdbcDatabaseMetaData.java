package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.Column;
import com.turborel.jdbc.internal.codec.Value;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A database's metadata ([DESIGN] D2): its schemas, tables, columns, keys and indexes read from
 * Turborel's Information Schema and laid out as JDBC lays them, so a GUI tool browses it. A table
 * of a system schema, INFORMATION_SCHEMA and the other information schemas, is a SYSTEM TABLE or SYSTEM VIEW.
 * An index's columns are ascending, as Turborel makes every index.
 */
final class JdbcDatabaseMetaData extends MetaDataQueries {
    /** The types of table JDBC knows Turborel's by, in order. */
    private static final List<String> TABLE_TYPES =
            List.of("GLOBAL TEMPORARY", "LOCAL TEMPORARY", "SYSTEM TABLE", "SYSTEM VIEW", "TABLE", "VIEW");

    /** The schema of the Information Schema, and the end of the spatial ones' names. */
    private static final String INFORMATION_SCHEMA = "INFORMATION_SCHEMA";

    private static final String SPATIAL_SCHEMA = "_INFORMTN_SCHEMA";

    /** The characters of a date, a time and a timestamp without a fraction, and a zone's. */
    private static final int DATE_SIZE = 10;

    private static final int TIME_SIZE = 8;

    private static final int ZONE_SIZE = 6;

    /** The greatest length of a large object, as INFORMATION_SCHEMA.SQL_SIZING gives it. */
    private static final long MAX_LOB = 4_294_967_296L;

    /** The columns of a foreign key's row read as they are, the last its place. */
    private static final String[] KEY_COLUMNS = {
        "PKTABLE_CAT",
        "PKTABLE_SCHEM",
        "PKTABLE_NAME",
        "PKCOLUMN_NAME",
        "FKTABLE_CAT",
        "FKTABLE_SCHEM",
        "FKTABLE_NAME",
        "FKCOLUMN_NAME",
        "KEY_SEQ"
    };

    /** Where a table's type and name are in a row of {@code getTables}. */
    private static final int TYPE_AT = 3;

    private static final int NAME_AT = 2;

    /** A version's numbers, as SERVER_VERSION writes them. */
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)");

    /** The group of a version's minor number. */
    private static final int MINOR = 2;

    JdbcDatabaseMetaData(JdbcConnection connection) {
        super(connection);
    }

    /** Whether {@code schema} is a system schema. */
    static boolean system(@Nullable String schema) {
        return schema != null && (schema.endsWith(INFORMATION_SCHEMA) || schema.endsWith(SPATIAL_SCHEMA));
    }

    /** JDBC's type of a table of {@code schema}, of the Information Schema's TABLE_TYPE. */
    static String tableType(@Nullable String schema, @Nullable String type) {
        if ("BASE TABLE".equals(type)) {
            return system(schema) ? "SYSTEM TABLE" : "TABLE";
        }
        if ("VIEW".equals(type)) {
            return system(schema) ? "SYSTEM VIEW" : "VIEW";
        }
        return type == null ? "TABLE" : type;
    }

    private static final String CAT = "TABLE_CATALOG";

    private static final String SCHEM = "TABLE_SCHEMA";

    private static final String TABLE = "TABLE_NAME";

    private static final String COLUMN = "COLUMN_NAME";

    private static final String YES = "YES";

    @Override
    public ResultSet getTables(
            @Nullable String catalog,
            @Nullable String schemaPattern,
            @Nullable String tableNamePattern,
            String @Nullable [] types)
            throws SQLException {
        Set<String> wanted = types == null ? null : Set.of(types);
        List<List<Value>> made = new ArrayList<>();
        for (Named r : rows("SELECT TABLE_CATALOG, TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE FROM INFORMATION_SCHEMA.TABLES"
                + " WHERE 1 = 1" + catalog(CAT, catalog) + like(SCHEM, schemaPattern)
                + like(TABLE, tableNamePattern))) {
            String type = tableType(r.text(SCHEM), r.text("TABLE_TYPE"));
            if (wanted == null || wanted.contains(type)) {
                made.add(List.of(
                        r.get(CAT),
                        r.get(SCHEM),
                        r.get(TABLE),
                        text(type),
                        Value.NULL,
                        Value.NULL,
                        Value.NULL,
                        Value.NULL,
                        Value.NULL,
                        Value.NULL));
            }
        }
        made.sort(Comparator.comparing((List<Value> r) -> String.valueOf(string(r.get(TYPE_AT))))
                .thenComparing(r -> String.valueOf(string(r.get(0))))
                .thenComparing(r -> String.valueOf(string(r.get(1))))
                .thenComparing(r -> String.valueOf(string(r.get(NAME_AT)))));
        return result(
                columns(
                        NAME,
                        "TABLE_CAT",
                        "TABLE_SCHEM",
                        TABLE,
                        "TABLE_TYPE",
                        "REMARKS",
                        "TYPE_CAT",
                        "TYPE_SCHEM",
                        "TYPE_NAME",
                        "SELF_REFERENCING_COL_NAME",
                        "REF_GENERATION"),
                made);
    }

    /** The text of {@code v}, none for the null value. */
    private static @Nullable String string(Value v) {
        return v instanceof Value.Text t ? t.text() : null;
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        return getSchemas(null, null);
    }

    @Override
    public ResultSet getSchemas(@Nullable String catalog, @Nullable String schemaPattern) throws SQLException {
        TreeSet<String> names = new TreeSet<>();
        String catalogName = null;
        for (Named r : rows("SELECT CATALOG_NAME, SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE 1 = 1"
                + catalog("CATALOG_NAME", catalog) + like("SCHEMA_NAME", schemaPattern))) {
            catalogName = r.text("CATALOG_NAME");
            names.add(String.valueOf(r.text("SCHEMA_NAME")));
        }
        for (Named r : rows("SELECT DISTINCT TABLE_CATALOG, TABLE_SCHEMA FROM INFORMATION_SCHEMA.TABLES WHERE 1 = 1"
                + catalog(CAT, catalog) + like(SCHEM, schemaPattern))) {
            catalogName = r.text(CAT);
            names.add(String.valueOf(r.text(SCHEM)));
        }
        List<List<Value>> made = new ArrayList<>();
        for (String name : names) {
            made.add(List.of(text(name), text(catalogName)));
        }
        return result(columns(NAME, "TABLE_SCHEM", CAT), made);
    }

    @Override
    public ResultSet getCatalogs() throws SQLException {
        return result(
                columns(NAME, "TABLE_CAT"),
                values(rows("SELECT CATALOG_NAME FROM INFORMATION_SCHEMA.CATALOG_NAME"), "CATALOG_NAME"));
    }

    @Override
    public ResultSet getTableTypes() {
        List<List<Value>> made = new ArrayList<>();
        for (String type : TABLE_TYPES) {
            made.add(List.of(text(type)));
        }
        return result(columns(NAME, "TABLE_TYPE"), made);
    }

    /** The characters a datetime's column is written in, its fraction of {@code precision} digits. */
    static @Nullable Long datetimeSize(String type, @Nullable Long precision) {
        long fraction = precision == null || precision == 0 ? 0 : precision + 1;
        long zone = type.endsWith("WITH TIME ZONE") ? ZONE_SIZE : 0;
        if (type.equals("DATE")) {
            return (long) DATE_SIZE;
        } else if (type.startsWith("TIMESTAMP")) {
            return DATE_SIZE + 1 + TIME_SIZE + fraction + zone;
        } else if (type.startsWith("TIME")) {
            return TIME_SIZE + fraction + zone;
        }
        return null;
    }

    @Override
    public ResultSet getColumns(
            @Nullable String catalog,
            @Nullable String schemaPattern,
            @Nullable String tableNamePattern,
            @Nullable String columnNamePattern)
            throws SQLException {
        List<List<Value>> made = new ArrayList<>();
        for (Named r : rows("SELECT TABLE_CATALOG, TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, DATA_TYPE,"
                + " CHARACTER_MAXIMUM_LENGTH, CHARACTER_OCTET_LENGTH, NUMERIC_PRECISION, NUMERIC_PRECISION_RADIX,"
                + " NUMERIC_SCALE, DATETIME_PRECISION, INTERVAL_TYPE, IS_NULLABLE, COLUMN_DEFAULT, ORDINAL_POSITION,"
                + " IS_IDENTITY, IS_GENERATED FROM INFORMATION_SCHEMA.COLUMNS WHERE 1 = 1" + catalog(CAT, catalog)
                + like(SCHEM, schemaPattern) + like(TABLE, tableNamePattern) + like(COLUMN, columnNamePattern)
                + " ORDER BY TABLE_CATALOG, TABLE_SCHEMA, TABLE_NAME, ORDINAL_POSITION")) {
            made.add(columnRow(r));
        }
        List<Column> shape = columns(NAME, "TABLE_CAT", "TABLE_SCHEM", TABLE, COLUMN);
        shape.addAll(List.of(
                column("DATA_TYPE", INT),
                column("TYPE_NAME", NAME),
                column("COLUMN_SIZE", INT),
                column("BUFFER_LENGTH", INT),
                column("DECIMAL_DIGITS", INT),
                column("NUM_PREC_RADIX", INT),
                column("NULLABLE", INT),
                column("REMARKS", TEXT),
                column("COLUMN_DEF", TEXT),
                column("SQL_DATA_TYPE", INT),
                column("SQL_DATETIME_SUB", INT),
                column("CHAR_OCTET_LENGTH", INT),
                column("ORDINAL_POSITION", INT),
                column("IS_NULLABLE", NAME),
                column("SCOPE_CATALOG", NAME),
                column("SCOPE_SCHEMA", NAME),
                column("SCOPE_TABLE", NAME),
                column("SOURCE_DATA_TYPE", SHORT),
                column("IS_AUTOINCREMENT", NAME),
                column("IS_GENERATEDCOLUMN", NAME)));
        return result(shape, made);
    }

    /** A row of {@code getColumns}, of a row of INFORMATION_SCHEMA.COLUMNS. */
    private static List<Value> columnRow(Named r) throws SQLException {
        String type = String.valueOf(r.text("DATA_TYPE"));
        String interval = r.text("INTERVAL_TYPE");
        Long precision = r.whole("DATETIME_PRECISION");
        Value length = r.get("CHARACTER_MAXIMUM_LENGTH");
        Value digits = r.get("NUMERIC_PRECISION");
        Value size = !(length instanceof Value.Null)
                ? length
                : !(digits instanceof Value.Null) ? digits : number(datetimeSize(type, precision));
        Value scale = r.get("NUMERIC_SCALE");
        Value decimals =
                !(scale instanceof Value.Null) ? scale : type.startsWith("TIME") ? number(precision) : Value.NULL;
        boolean nullable = YES.equals(r.text("IS_NULLABLE"));
        List<Value> row = new ArrayList<>(List.of(
                r.get(CAT),
                r.get(SCHEM),
                r.get(TABLE),
                r.get(COLUMN),
                number(JdbcTypes.named(type)),
                text(interval == null ? type : type + " " + interval),
                size,
                Value.NULL,
                decimals,
                r.get("NUMERIC_PRECISION_RADIX")));
        row.addAll(List.of(
                number(nullable ? columnNullable : columnNoNulls),
                Value.NULL,
                r.get("COLUMN_DEFAULT"),
                Value.NULL,
                Value.NULL,
                r.get("CHARACTER_OCTET_LENGTH"),
                r.get("ORDINAL_POSITION"),
                text(nullable ? YES : "NO"),
                Value.NULL,
                Value.NULL,
                Value.NULL,
                Value.NULL,
                text(YES.equals(r.text("IS_IDENTITY")) ? YES : "NO"),
                text("ALWAYS".equals(r.text("IS_GENERATED")) ? YES : "NO")));
        return row;
    }

    /** The primary key's columns of a table: its names, the column's, its place, the key's name. */
    private List<Named> primaryKey(@Nullable String catalog, @Nullable String schema, @Nullable String table)
            throws SQLException {
        return rows("SELECT k.TABLE_CATALOG, k.TABLE_SCHEMA, k.TABLE_NAME, k.COLUMN_NAME, k.ORDINAL_POSITION,"
                + " k.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS c JOIN"
                + " INFORMATION_SCHEMA.KEY_COLUMN_USAGE k ON k.CONSTRAINT_CATALOG = c.CONSTRAINT_CATALOG AND"
                + " k.CONSTRAINT_SCHEMA = c.CONSTRAINT_SCHEMA AND k.CONSTRAINT_NAME = c.CONSTRAINT_NAME"
                + " WHERE c.CONSTRAINT_TYPE = 'PRIMARY KEY'" + table("k", catalog, schema, table)
                + " ORDER BY k.TABLE_CATALOG, k.TABLE_SCHEMA, k.TABLE_NAME, k.COLUMN_NAME");
    }

    @Override
    public ResultSet getPrimaryKeys(@Nullable String catalog, @Nullable String schema, @Nullable String table)
            throws SQLException {
        List<Column> shape = columns(NAME, "TABLE_CAT", "TABLE_SCHEM", TABLE, COLUMN);
        shape.add(column("KEY_SEQ", SHORT));
        shape.add(column("PK_NAME", NAME));
        return result(
                shape,
                values(
                        primaryKey(catalog, schema, table),
                        CAT,
                        SCHEM,
                        TABLE,
                        COLUMN,
                        "ORDINAL_POSITION",
                        "CONSTRAINT_NAME"));
    }

    /** The rule's number of a referential action, as JDBC numbers it. */
    static int rule(@Nullable String action) {
        return switch (action == null ? "" : action) {
            case "CASCADE" -> importedKeyCascade;
            case "RESTRICT" -> importedKeyRestrict;
            case "SET NULL" -> importedKeySetNull;
            case "SET DEFAULT" -> importedKeySetDefault;
            default -> importedKeyNoAction;
        };
    }

    /** The deferrability's number of a constraint, as JDBC numbers it. */
    static int deferrability(@Nullable String deferrable, @Nullable String deferred) {
        if (!YES.equals(deferrable)) {
            return importedKeyNotDeferrable;
        }
        return YES.equals(deferred) ? importedKeyInitiallyDeferred : importedKeyInitiallyImmediate;
    }

    /** The foreign keys' columns, of the filter given, ordered as {@code order} says. */
    private ResultSet keys(String filter, String order) throws SQLException {
        List<List<Value>> made = new ArrayList<>();
        for (Named r : rows("SELECT p.TABLE_CATALOG AS PKTABLE_CAT, p.TABLE_SCHEMA AS PKTABLE_SCHEM,"
                + " p.TABLE_NAME AS PKTABLE_NAME, p.COLUMN_NAME AS PKCOLUMN_NAME, f.TABLE_CATALOG AS FKTABLE_CAT,"
                + " f.TABLE_SCHEMA AS FKTABLE_SCHEM, f.TABLE_NAME AS FKTABLE_NAME, f.COLUMN_NAME AS FKCOLUMN_NAME,"
                + " f.ORDINAL_POSITION AS KEY_SEQ, rc.UPDATE_RULE, rc.DELETE_RULE, rc.CONSTRAINT_NAME AS FK_NAME,"
                + " rc.UNIQUE_CONSTRAINT_NAME AS PK_NAME, tc.IS_DEFERRABLE, tc.INITIALLY_DEFERRED"
                + " FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE f"
                + " ON f.CONSTRAINT_CATALOG = rc.CONSTRAINT_CATALOG AND f.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA"
                + " AND f.CONSTRAINT_NAME = rc.CONSTRAINT_NAME JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE p"
                + " ON p.CONSTRAINT_CATALOG = rc.UNIQUE_CONSTRAINT_CATALOG AND p.CONSTRAINT_SCHEMA ="
                + " rc.UNIQUE_CONSTRAINT_SCHEMA AND p.CONSTRAINT_NAME = rc.UNIQUE_CONSTRAINT_NAME"
                + " AND p.ORDINAL_POSITION = f.POSITION_IN_UNIQUE_CONSTRAINT JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc"
                + " ON tc.CONSTRAINT_CATALOG = rc.CONSTRAINT_CATALOG AND tc.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA"
                + " AND tc.CONSTRAINT_NAME = rc.CONSTRAINT_NAME WHERE 1 = 1" + filter + " ORDER BY " + order)) {
            List<Value> row = new ArrayList<>(values(List.of(r), KEY_COLUMNS).get(0));
            row.addAll(List.of(
                    number(rule(r.text("UPDATE_RULE"))),
                    number(rule(r.text("DELETE_RULE"))),
                    r.get("FK_NAME"),
                    r.get("PK_NAME"),
                    number(deferrability(r.text("IS_DEFERRABLE"), r.text("INITIALLY_DEFERRED")))));
            made.add(row);
        }
        List<Column> shape = columns(NAME, KEY_COLUMNS);
        shape.set(KEY_COLUMNS.length - 1, column("KEY_SEQ", SHORT));
        shape.addAll(List.of(
                column("UPDATE_RULE", SHORT),
                column("DELETE_RULE", SHORT),
                column("FK_NAME", NAME),
                column("PK_NAME", NAME),
                column("DEFERRABILITY", SHORT)));
        return result(shape, made);
    }

    private static String table(
            String alias, @Nullable String catalog, @Nullable String schema, @Nullable String table) {
        return catalog(alias + "." + CAT, catalog)
                + equal(alias + "." + SCHEM, schema)
                + equal(alias + "." + TABLE, table);
    }

    @Override
    public ResultSet getImportedKeys(@Nullable String catalog, @Nullable String schema, @Nullable String table)
            throws SQLException {
        return keys(
                table("f", catalog, schema, table),
                "p.TABLE_CATALOG, p.TABLE_SCHEMA, p.TABLE_NAME, f.ORDINAL_POSITION");
    }

    @Override
    public ResultSet getExportedKeys(@Nullable String catalog, @Nullable String schema, @Nullable String table)
            throws SQLException {
        return keys(
                table("p", catalog, schema, table),
                "f.TABLE_CATALOG, f.TABLE_SCHEMA, f.TABLE_NAME, f.ORDINAL_POSITION");
    }

    @Override
    public ResultSet getCrossReference(
            @Nullable String parentCatalog,
            @Nullable String parentSchema,
            @Nullable String parentTable,
            @Nullable String foreignCatalog,
            @Nullable String foreignSchema,
            @Nullable String foreignTable)
            throws SQLException {
        return keys(
                table("p", parentCatalog, parentSchema, parentTable)
                        + table("f", foreignCatalog, foreignSchema, foreignTable),
                "f.TABLE_CATALOG, f.TABLE_SCHEMA, f.TABLE_NAME, f.ORDINAL_POSITION");
    }

    @Override
    public ResultSet getIndexInfo(
            @Nullable String catalog,
            @Nullable String schema,
            @Nullable String table,
            boolean unique,
            boolean approximate)
            throws SQLException {
        List<List<Value>> made = new ArrayList<>();
        for (Named r : rows("SELECT TABLE_CATALOG, TABLE_SCHEMA, TABLE_NAME, INDEX_NAME, IS_UNIQUE, COLUMN_NAMES"
                + " FROM INFORMATION_SCHEMA.INDEXES WHERE 1 = 1" + catalog(CAT, catalog) + equal(SCHEM, schema)
                + equal(TABLE, table) + (unique ? " AND IS_UNIQUE = 'YES'" : "")
                + " ORDER BY IS_UNIQUE DESC, INDEX_NAME")) {
            String names = r.text("COLUMN_NAMES");
            int place = 0;
            for (String each : names == null ? new String[0] : names.split(",")) {
                String name = each.trim();
                made.add(List.of(
                        r.get(CAT),
                        r.get(SCHEM),
                        r.get(TABLE),
                        new Value.Truth(!YES.equals(r.text("IS_UNIQUE"))),
                        Value.NULL,
                        r.get("INDEX_NAME"),
                        number(tableIndexOther),
                        number(++place),
                        text(name),
                        text("A"),
                        Value.NULL,
                        Value.NULL,
                        Value.NULL));
            }
        }
        List<Column> shape = columns(NAME, "TABLE_CAT", "TABLE_SCHEM", TABLE);
        shape.addAll(List.of(
                column("NON_UNIQUE", TRUTH),
                column("INDEX_QUALIFIER", NAME),
                column("INDEX_NAME", NAME),
                column("TYPE", SHORT),
                column("ORDINAL_POSITION", SHORT),
                column(COLUMN, NAME),
                column("ASC_OR_DESC", NAME),
                column("CARDINALITY", INT),
                column("PAGES", INT),
                column("FILTER_CONDITION", TEXT)));
        return result(shape, made);
    }

    @Override
    public ResultSet getBestRowIdentifier(
            @Nullable String catalog, @Nullable String schema, @Nullable String table, int scope, boolean nullable)
            throws SQLException {
        List<List<Value>> made = new ArrayList<>();
        for (Named key : primaryKey(catalog, schema, table)) {
            ResultSet column = getColumns(
                    key.text(CAT), escaped(key.text(SCHEM)), escaped(key.text(TABLE)), escaped(key.text(COLUMN)));
            if (column.next()) {
                made.add(List.of(
                        number(bestRowSession),
                        key.get(COLUMN),
                        number(column.getInt("DATA_TYPE")),
                        text(column.getString("TYPE_NAME")),
                        number(column.getLong("COLUMN_SIZE")),
                        Value.NULL,
                        number(column.getLong("DECIMAL_DIGITS")),
                        number(bestRowNotPseudo)));
            }
        }
        return result(rowIdentifier(), made);
    }

    /** {@code name} as a LIKE pattern that matches it alone. */
    static @Nullable String escaped(@Nullable String name) {
        return name == null
                ? null
                : name.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Override
    public java.sql.Connection getConnection() {
        return connection;
    }

    @Override
    public String getDatabaseProductVersion() throws SQLException {
        String told = connection.sessionStatus("SERVER_VERSION");
        return told == null ? connection.session().server() : told;
    }

    private int version(boolean major) throws SQLException {
        Matcher m = VERSION.matcher(getDatabaseProductVersion());
        return m.find() ? Integer.parseInt(major ? m.group(1) : m.group(MINOR)) : 0;
    }

    @Override
    public int getDatabaseMajorVersion() throws SQLException {
        return version(true);
    }

    @Override
    public int getDatabaseMinorVersion() throws SQLException {
        return version(false);
    }

    @Override
    public String getURL() {
        return connection.url();
    }

    @Override
    public String getUserName() throws SQLException {
        connection.open();
        return connection.session().authorization();
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return connection.isReadOnly();
    }

    @Override
    public long getMaxLogicalLobSize() {
        return MAX_LOB;
    }

    @Override
    public boolean supportsConvert(int fromType, int toType) {
        return fromType == toType || toType == Types.VARCHAR;
    }

    @Override
    public <T> T unwrap(@Nullable Class<T> iface) throws SQLException {
        return Wrapping.unwrap(this, iface);
    }

    @Override
    public boolean isWrapperFor(@Nullable Class<?> iface) {
        return iface != null && iface.isInstance(this);
    }
}
