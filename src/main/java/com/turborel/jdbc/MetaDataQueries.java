package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.CharUnits;
import com.turborel.jdbc.internal.codec.Column;
import com.turborel.jdbc.internal.codec.DataType;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.internal.codec.ValueText;
import com.turborel.jdbc.internal.session.Outcome;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * DatabaseMetaData's queries of Turborel's Information Schema ([DESIGN] D2), each result laid out
 * as JDBC lays it: the routines, their parameters, privileges, and the types; the helpers the
 * tables, columns, keys and indexes share. A name pattern is a LIKE pattern, its escape {@code \};
 * a name none is no filter; a catalog {@code ""} matches none, as every name is of a catalog.
 */
abstract class MetaDataQueries extends MetaDataFacts {
    /** The type of a name's column. */
    static final DataType NAME = new DataType.CharacterType(true, NAME_LENGTH, CharUnits.CHARACTERS);

    /** The type of a text's column, of no fixed length. */
    static final DataType TEXT = new DataType.CharacterType(true, 0, CharUnits.CHARACTERS);

    /** The type of an integer's column, and of a short one. */
    static final DataType INT = new DataType.ExactType(10, 0);

    static final DataType SHORT = new DataType.ExactType(5, 0);

    /** The type of a truth value's column. */
    static final DataType TRUTH = new DataType.BooleanType();

    /** The columns of the Information Schema the routines' queries read. */
    private static final String CATALOG = "ROUTINE_CATALOG";

    private static final String SCHEMA = "ROUTINE_SCHEMA";

    private static final String ROUTINE = "ROUTINE_NAME";

    private static final String SPECIFIC = "SPECIFIC_NAME";

    private static final String MODE = "PARAMETER_MODE";

    private static final String OCTETS = "CHARACTER_OCTET_LENGTH";

    private static final String ORDINAL = "ORDINAL_POSITION";

    private static final String TYPE = "DATA_TYPE";

    final JdbcConnection connection;

    MetaDataQueries(JdbcConnection connection) {
        this.connection = connection;
    }

    /** A column of a result of the metadata, its name and type. */
    static Column column(String name, DataType type) {
        return new Column(name, type, false);
    }

    /** Columns of the names given, each of {@code type}. */
    static List<Column> columns(DataType type, String... names) {
        List<Column> made = new ArrayList<>(names.length);
        for (String name : names) {
            made.add(column(name, type));
        }
        return made;
    }

    /** A text value, or the null value for none. */
    static Value text(@Nullable String s) {
        return s == null ? Value.NULL : new Value.Text(s);
    }

    /** An integer value. */
    static Value number(long n) {
        return new Value.Exact(BigInteger.valueOf(n), 0);
    }

    /** An integer value, or the null value for none. */
    static Value number(@Nullable Long n) {
        return n == null ? Value.NULL : number(n.longValue());
    }

    /** {@code s} as an SQL character string literal. */
    static String literal(String s) {
        return ValueText.literal(new Value.Text(s));
    }

    /** A filter of {@code column} by a LIKE {@code pattern}: none for none. */
    static String like(String column, @Nullable String pattern) {
        return pattern == null ? "" : " AND " + column + " LIKE " + literal(pattern) + " ESCAPE '\\'";
    }

    /** A filter of {@code column} equal to {@code value}: none for none. */
    static String equal(String column, @Nullable String value) {
        return value == null ? "" : " AND " + column + " = " + literal(value);
    }

    /** A filter of a catalog's column: none for none; no row for {@code ""}. */
    static String catalog(String column, @Nullable String catalog) {
        return catalog != null && catalog.isEmpty() ? " AND 1 = 0" : equal(column, catalog);
    }

    /** The rows of a query of the driver's own, each read by its columns' names. */
    final List<Named> rows(String sql) throws SQLException {
        connection.open();
        Outcome outcome = connection.session().statements().query(sql);
        return outcome instanceof Outcome.Rows rows ? Named.of(rows.columns(), rows.rows()) : List.of();
    }

    /** A result set of {@code columns} and {@code rows}. */
    final ResultSet result(List<Column> columns, List<List<Value>> rows) {
        return new JdbcResultSet(null, connection, columns, Rows.of(rows));
    }

    /** A result of no row, of the columns named, each of type {@code type}. */
    final ResultSet empty(DataType type, String... names) {
        return result(columns(type, names), List.of());
    }

    /** The values of the columns named of each row, in order. */
    static List<List<Value>> values(List<Named> rows, String... names) {
        List<List<Value>> made = new ArrayList<>(rows.size());
        for (Named r : rows) {
            List<Value> row = new ArrayList<>(names.length);
            for (String name : names) {
                row.add(r.get(name));
            }
            made.add(row);
        }
        return made;
    }

    /** The JDBC type's number of a type the Information Schema names. */
    static Value jdbcType(@Nullable String type) {
        return number(type == null ? Types.OTHER : JdbcTypes.named(type));
    }

    /** The parameter mode's number of {@code mode}, as {@code getProcedureColumns} gives it. */
    private static int procedureMode(@Nullable String mode) {
        if ("OUT".equals(mode)) {
            return procedureColumnOut;
        }
        return "INOUT".equals(mode) ? procedureColumnInOut : procedureColumnIn;
    }

    /** The parameter mode's number of {@code mode}, as {@code getFunctionColumns} gives it. */
    private static int functionMode(@Nullable String mode) {
        if ("OUT".equals(mode)) {
            return functionColumnOut;
        }
        return "INOUT".equals(mode) ? functionColumnInOut : functionColumnIn;
    }

    private List<Named> routines(String type, @Nullable String catalog, @Nullable String schema, @Nullable String name)
            throws SQLException {
        return rows("SELECT ROUTINE_CATALOG, ROUTINE_SCHEMA, ROUTINE_NAME, SPECIFIC_NAME, DATA_TYPE"
                + " FROM INFORMATION_SCHEMA.ROUTINES WHERE ROUTINE_TYPE = " + literal(type)
                + catalog(CATALOG, catalog) + like(SCHEMA, schema) + like(ROUTINE, name)
                + " ORDER BY ROUTINE_CATALOG, ROUTINE_SCHEMA, ROUTINE_NAME, SPECIFIC_NAME");
    }

    @Override
    public ResultSet getProcedures(
            @Nullable String catalog, @Nullable String schemaPattern, @Nullable String procedureNamePattern)
            throws SQLException {
        List<List<Value>> made = new ArrayList<>();
        for (Named r : routines("PROCEDURE", catalog, schemaPattern, procedureNamePattern)) {
            made.add(List.of(
                    r.get(CATALOG),
                    r.get(SCHEMA),
                    r.get(ROUTINE),
                    Value.NULL,
                    Value.NULL,
                    Value.NULL,
                    Value.NULL,
                    number(procedureNoResult),
                    r.get(SPECIFIC)));
        }
        List<Column> shape = columns(
                NAME,
                "PROCEDURE_CAT",
                "PROCEDURE_SCHEM",
                "PROCEDURE_NAME",
                "RESERVED1",
                "RESERVED2",
                "RESERVED3",
                "REMARKS");
        shape.add(column("PROCEDURE_TYPE", SHORT));
        shape.add(column(SPECIFIC, NAME));
        return result(shape, made);
    }

    @Override
    public ResultSet getFunctions(
            @Nullable String catalog, @Nullable String schemaPattern, @Nullable String functionNamePattern)
            throws SQLException {
        List<List<Value>> made = new ArrayList<>();
        for (Named r : routines("FUNCTION", catalog, schemaPattern, functionNamePattern)) {
            made.add(List.of(
                    r.get(CATALOG),
                    r.get(SCHEMA),
                    r.get(ROUTINE),
                    Value.NULL,
                    number(functionNoTable),
                    r.get(SPECIFIC)));
        }
        List<Column> shape = columns(NAME, "FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "REMARKS");
        shape.add(column("FUNCTION_TYPE", SHORT));
        shape.add(column(SPECIFIC, NAME));
        return result(shape, made);
    }

    /** The parameters of the routines of {@code type}, with their routine's names, in order. */
    private List<Named> parameters(String type, Filter filter) throws SQLException {
        return rows("SELECT r.ROUTINE_CATALOG, r.ROUTINE_SCHEMA, r.ROUTINE_NAME, p.PARAMETER_NAME, p.PARAMETER_MODE,"
                + " p.DATA_TYPE, p.CHARACTER_MAXIMUM_LENGTH, p.CHARACTER_OCTET_LENGTH, p.NUMERIC_PRECISION,"
                + " p.NUMERIC_SCALE, p.NUMERIC_PRECISION_RADIX, p.ORDINAL_POSITION, r.SPECIFIC_NAME"
                + " FROM INFORMATION_SCHEMA.ROUTINES r JOIN INFORMATION_SCHEMA.PARAMETERS p"
                + " ON p.SPECIFIC_CATALOG = r.SPECIFIC_CATALOG AND p.SPECIFIC_SCHEMA = r.SPECIFIC_SCHEMA"
                + " AND p.SPECIFIC_NAME = r.SPECIFIC_NAME WHERE r.ROUTINE_TYPE = " + literal(type)
                + catalog("r.ROUTINE_CATALOG", filter.catalog()) + like("r.ROUTINE_SCHEMA", filter.schema())
                + like("r.ROUTINE_NAME", filter.name()) + like("p.PARAMETER_NAME", filter.column())
                + " ORDER BY r.ROUTINE_CATALOG, r.ROUTINE_SCHEMA, r.ROUTINE_NAME, r.SPECIFIC_NAME, p.ORDINAL_POSITION");
    }

    /**
     * What a routine's columns are filtered by.
     *
     * @param catalog the catalog, or none
     * @param schema the schemas' pattern, or none
     * @param name the routines' pattern, or none
     * @param column the columns' pattern, or none
     */
    record Filter(
            @Nullable String catalog,
            @Nullable String schema,
            @Nullable String name,
            @Nullable String column) {}

    /** A routine column's row: its routine's names, its name, mode, type and sizes, as both layouts begin. */
    private static List<Value> routineColumn(Named p, int mode) {
        String type = p.text(TYPE);
        Value length = p.get("CHARACTER_MAXIMUM_LENGTH");
        List<Value> row = new ArrayList<>(List.of(
                p.get(CATALOG),
                p.get(SCHEMA),
                p.get(ROUTINE),
                p.get("PARAMETER_NAME"),
                number(mode),
                jdbcType(type),
                text(type)));
        row.addAll(List.of(
                length instanceof Value.Null ? p.get("NUMERIC_PRECISION") : length,
                p.get(OCTETS),
                p.get("NUMERIC_SCALE"),
                p.get("NUMERIC_PRECISION_RADIX"),
                number(procedureNullableUnknown),
                Value.NULL));
        return row;
    }

    @Override
    public ResultSet getProcedureColumns(
            @Nullable String catalog,
            @Nullable String schemaPattern,
            @Nullable String procedureNamePattern,
            @Nullable String columnNamePattern)
            throws SQLException {
        List<List<Value>> made = new ArrayList<>();
        Filter filter = new Filter(catalog, schemaPattern, procedureNamePattern, columnNamePattern);
        for (Named p : parameters("PROCEDURE", filter)) {
            List<Value> row = routineColumn(p, procedureMode(p.text(MODE)));
            row.addAll(List.of(
                    Value.NULL, Value.NULL, Value.NULL, p.get(OCTETS), p.get(ORDINAL), text(""), p.get(SPECIFIC)));
            made.add(row);
        }
        List<Column> shape = columns(NAME, "PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "COLUMN_NAME");
        shape.addAll(List.of(
                column("COLUMN_TYPE", SHORT),
                column(TYPE, INT),
                column("TYPE_NAME", NAME),
                column("PRECISION", INT),
                column("LENGTH", INT),
                column("SCALE", SHORT),
                column("RADIX", SHORT),
                column("NULLABLE", SHORT),
                column("REMARKS", TEXT),
                column("COLUMN_DEF", TEXT),
                column("SQL_DATA_TYPE", INT),
                column("SQL_DATETIME_SUB", INT),
                column("CHAR_OCTET_LENGTH", INT),
                column(ORDINAL, INT),
                column("IS_NULLABLE", NAME),
                column(SPECIFIC, NAME)));
        return result(shape, made);
    }

    /** A function's column of {@code getFunctionColumns}, and what orders it: its function, then its place. */
    private record FunctionColumn(String order, List<Value> row) {}

    @Override
    public ResultSet getFunctionColumns(
            @Nullable String catalog,
            @Nullable String schemaPattern,
            @Nullable String functionNamePattern,
            @Nullable String columnNamePattern)
            throws SQLException {
        List<FunctionColumn> made = new ArrayList<>();
        if (columnNamePattern == null || columnNamePattern.equals("%")) {
            for (Named r : routines("FUNCTION", catalog, schemaPattern, functionNamePattern)) {
                String type = r.text(TYPE);
                made.add(new FunctionColumn(
                        order(r, 0),
                        List.of(
                                r.get(CATALOG),
                                r.get(SCHEMA),
                                r.get(ROUTINE),
                                text(""),
                                number(functionReturn),
                                jdbcType(type),
                                text(type),
                                Value.NULL,
                                Value.NULL,
                                Value.NULL,
                                Value.NULL,
                                number(functionNullableUnknown),
                                Value.NULL,
                                Value.NULL,
                                number(0),
                                text(""),
                                r.get(SPECIFIC))));
            }
        }
        Filter filter = new Filter(catalog, schemaPattern, functionNamePattern, columnNamePattern);
        for (Named p : parameters("FUNCTION", filter)) {
            List<Value> row = routineColumn(p, functionMode(p.text(MODE)));
            row.addAll(List.of(p.get(OCTETS), p.get(ORDINAL), text(""), p.get(SPECIFIC)));
            Long place = p.whole(ORDINAL);
            made.add(new FunctionColumn(order(p, place == null ? 0 : place), row));
        }
        made.sort(Comparator.comparing(FunctionColumn::order));
        return result(functionColumns(), made.stream().map(FunctionColumn::row).toList());
    }

    /** The columns of {@code getFunctionColumns}. */
    private static List<Column> functionColumns() {
        List<Column> shape = columns(NAME, "FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "COLUMN_NAME");
        shape.addAll(List.of(
                column("COLUMN_TYPE", SHORT),
                column(TYPE, INT),
                column("TYPE_NAME", NAME),
                column("PRECISION", INT),
                column("LENGTH", INT),
                column("SCALE", SHORT),
                column("RADIX", SHORT),
                column("NULLABLE", SHORT),
                column("REMARKS", TEXT),
                column("CHAR_OCTET_LENGTH", INT),
                column(ORDINAL, INT),
                column("IS_NULLABLE", NAME),
                column(SPECIFIC, NAME)));
        return shape;
    }

    /** What orders a function's column: its function's names, its specific name, then its place. */
    private static String order(Named r, long place) {
        return String.join(
                "\u0000",
                String.valueOf(r.text(CATALOG)),
                String.valueOf(r.text(SCHEMA)),
                String.valueOf(r.text(ROUTINE)),
                String.valueOf(r.text(SPECIFIC)),
                String.format("%010d", place));
    }

    @Override
    public ResultSet getTablePrivileges(
            @Nullable String catalog, @Nullable String schemaPattern, @Nullable String tableNamePattern)
            throws SQLException {
        List<Named> found = rows("SELECT TABLE_CATALOG, TABLE_SCHEMA, TABLE_NAME, GRANTOR, GRANTEE, PRIVILEGE_TYPE,"
                + " IS_GRANTABLE FROM INFORMATION_SCHEMA.TABLE_PRIVILEGES WHERE 1 = 1"
                + catalog("TABLE_CATALOG", catalog) + like("TABLE_SCHEMA", schemaPattern)
                + like("TABLE_NAME", tableNamePattern)
                + " ORDER BY TABLE_CATALOG, TABLE_SCHEMA, TABLE_NAME, PRIVILEGE_TYPE");
        List<List<Value>> made = values(
                found,
                "TABLE_CATALOG",
                "TABLE_SCHEMA",
                "TABLE_NAME",
                "GRANTOR",
                "GRANTEE",
                "PRIVILEGE_TYPE",
                "IS_GRANTABLE");
        return result(
                columns(
                        NAME,
                        "TABLE_CAT",
                        "TABLE_SCHEM",
                        "TABLE_NAME",
                        "GRANTOR",
                        "GRANTEE",
                        "PRIVILEGE",
                        "IS_GRANTABLE"),
                made);
    }

    @Override
    public ResultSet getColumnPrivileges(
            @Nullable String catalog,
            @Nullable String schema,
            @Nullable String table,
            @Nullable String columnNamePattern)
            throws SQLException {
        List<Named> found = rows("SELECT TABLE_CATALOG, TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, GRANTOR, GRANTEE,"
                + " PRIVILEGE_TYPE, IS_GRANTABLE FROM INFORMATION_SCHEMA.COLUMN_PRIVILEGES WHERE 1 = 1"
                + catalog("TABLE_CATALOG", catalog) + equal("TABLE_SCHEMA", schema) + equal("TABLE_NAME", table)
                + like("COLUMN_NAME", columnNamePattern) + " ORDER BY COLUMN_NAME, PRIVILEGE_TYPE");
        List<List<Value>> made = values(
                found,
                "TABLE_CATALOG",
                "TABLE_SCHEMA",
                "TABLE_NAME",
                "COLUMN_NAME",
                "GRANTOR",
                "GRANTEE",
                "PRIVILEGE_TYPE",
                "IS_GRANTABLE");
        return result(
                columns(
                        NAME,
                        "TABLE_CAT",
                        "TABLE_SCHEM",
                        "TABLE_NAME",
                        "COLUMN_NAME",
                        "GRANTOR",
                        "GRANTEE",
                        "PRIVILEGE",
                        "IS_GRANTABLE"),
                made);
    }

    @Override
    public ResultSet getTypeInfo() {
        List<TypeInfo> types = new ArrayList<>(List.of(TypeInfo.values()));
        types.sort(Comparator.comparingInt(TypeInfo::jdbc));
        return result(TypeInfo.shape(), types.stream().map(TypeInfo::row).toList());
    }

    /** The columns of {@code getBestRowIdentifier} and {@code getVersionColumns}. */
    static List<Column> rowIdentifier() {
        return List.of(
                column("SCOPE", SHORT),
                column("COLUMN_NAME", NAME),
                column("DATA_TYPE", INT),
                column("TYPE_NAME", NAME),
                column("COLUMN_SIZE", INT),
                column("BUFFER_LENGTH", INT),
                column("DECIMAL_DIGITS", SHORT),
                column("PSEUDO_COLUMN", SHORT));
    }

    @Override
    public ResultSet getVersionColumns(@Nullable String catalog, @Nullable String schema, @Nullable String table) {
        return result(rowIdentifier(), List.of());
    }

    @Override
    public ResultSet getUDTs(
            @Nullable String catalog,
            @Nullable String schemaPattern,
            @Nullable String typeNamePattern,
            int @Nullable [] types) {
        return empty(NAME, "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "CLASS_NAME", "DATA_TYPE", "REMARKS", "BASE_TYPE");
    }

    @Override
    public ResultSet getSuperTypes(
            @Nullable String catalog, @Nullable String schemaPattern, @Nullable String typeNamePattern) {
        return empty(NAME, "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SUPERTYPE_CAT", "SUPERTYPE_SCHEM", "SUPERTYPE_NAME");
    }

    @Override
    public ResultSet getSuperTables(
            @Nullable String catalog, @Nullable String schemaPattern, @Nullable String tableNamePattern) {
        return empty(NAME, "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "SUPERTABLE_NAME");
    }

    @Override
    public ResultSet getAttributes(
            @Nullable String catalog,
            @Nullable String schemaPattern,
            @Nullable String typeNamePattern,
            @Nullable String attributeNamePattern) {
        return empty(
                NAME,
                "TYPE_CAT",
                "TYPE_SCHEM",
                "TYPE_NAME",
                "ATTR_NAME",
                "DATA_TYPE",
                "ATTR_TYPE_NAME",
                "ATTR_SIZE",
                "DECIMAL_DIGITS",
                "NUM_PREC_RADIX",
                "NULLABLE",
                "REMARKS",
                "ATTR_DEF",
                "SQL_DATA_TYPE",
                "SQL_DATETIME_SUB",
                "CHAR_OCTET_LENGTH",
                "ORDINAL_POSITION",
                "IS_NULLABLE",
                "SCOPE_CATALOG",
                "SCOPE_SCHEMA",
                "SCOPE_TABLE",
                "SOURCE_DATA_TYPE");
    }

    @Override
    public ResultSet getClientInfoProperties() {
        return empty(NAME, "NAME", "MAX_LEN", "DEFAULT_VALUE", "DESCRIPTION");
    }

    @Override
    public ResultSet getPseudoColumns(
            @Nullable String catalog,
            @Nullable String schemaPattern,
            @Nullable String tableNamePattern,
            @Nullable String columnNamePattern) {
        return empty(
                NAME,
                "TABLE_CAT",
                "TABLE_SCHEM",
                "TABLE_NAME",
                "COLUMN_NAME",
                "DATA_TYPE",
                "COLUMN_SIZE",
                "DECIMAL_DIGITS",
                "NUM_PREC_RADIX",
                "COLUMN_USAGE",
                "REMARKS",
                "CHAR_OCTET_LENGTH",
                "IS_NULLABLE");
    }
}
