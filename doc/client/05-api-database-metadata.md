# DatabaseMetaData

[Contents](README.md)

## NAME

`DatabaseMetaData`: what the server holds and does, read from its
Information Schema, as tools browse it

## SYNOPSIS

```java
DatabaseMetaData md = conn.getMetaData();
```

It is JDBC 4.3's interface.

## DESCRIPTION

**Browsing.** Each method that gives a result set reads Turborel's
Information Schema over the connection, as the session's authorization
identifier may see it, so a tool lists what the user can reach. A
pattern is a LIKE pattern, `%` and `_` with `\` their escape
(`getSearchStringEscape`), and a name that is not a pattern, as of
`getPrimaryKeys`, matches itself alone; none matches any. A catalog is
matched by its name; of "", which names what has no catalog, nothing
matches, as everything the server holds is of its one catalog.

| Method | Read from |
|---|---|
| `getCatalogs` | INFORMATION_SCHEMA.CATALOG_NAME, the server's one catalog |
| `getSchemas` | SCHEMATA, and the schemas of TABLES |
| `getTables` | TABLES; their types TABLE, VIEW, GLOBAL TEMPORARY and LOCAL TEMPORARY, and SYSTEM TABLE and SYSTEM VIEW of the Information Schema's own (`getTableTypes`) |
| `getColumns` | COLUMNS: each column's declared type, by its name and JDBC's number, its sizes, nullability, default, place, and whether it is an identity or generated column |
| `getPrimaryKeys`, `getBestRowIdentifier` | TABLE_CONSTRAINTS and KEY_COLUMN_USAGE: the primary key's columns |
| `getImportedKeys`, `getExportedKeys`, `getCrossReference` | REFERENTIAL_CONSTRAINTS and KEY_COLUMN_USAGE: each foreign key's columns, its update and delete rules, its deferrability |
| `getIndexInfo` | INDEXES: each index's columns in order, unique ones first, every column ascending as the server keeps them |
| `getProcedures`, `getFunctions` | ROUTINES |
| `getProcedureColumns`, `getFunctionColumns` | ROUTINES and PARAMETERS: each parameter's mode and type, and a function's result |
| `getTablePrivileges`, `getColumnPrivileges` | TABLE_PRIVILEGES and COLUMN_PRIVILEGES |
| `getTypeInfo` | the driver's own list of the SQL types, each with JDBC's number |

`getUDTs`, `getSuperTypes`, `getSuperTables`, `getAttributes`,
`getPseudoColumns`, `getVersionColumns` and `getClientInfoProperties`
give no rows, their columns as JDBC lays them out.

**The product.** `getDatabaseProductName` gives `turborel`, and
`getDatabaseProductVersion` the server's version, as the session's
SERVER_VERSION gives it, whose numbers are its major and minor
versions. `getUserName` gives the session's authorization identifier,
`getURL` the URL the connection was made of, and `isReadOnly` the
connection's. The driver's own name and version are those of
[Driver](01-api-driver.md).

**What the server does.** The answers follow Turborel: identifiers are
kept in upper case, delimited ones as written, quoted by `"`; nulls
sort high; transactions, the four isolation levels, READ COMMITTED by
default, savepoints, batches, generated keys, stored procedures, outer
joins, full ones too, subqueries and UNION are supported; a schema may
qualify a name wherever one is used. Result sets are forward-only and
read-only, closed at commit; statements stay open across commits.

## ERRORS

| SQLCODE | SQLSTATE | When |
|---|---|---|
| -910 | TB910 | A connection closed |
| | the server's | A query of the Information Schema |

## EXAMPLES

A schema's tables and their columns, as a tool lists them:

```java
DatabaseMetaData md = conn.getMetaData();
try (ResultSet tables = md.getTables(null, "ANN", "%", new String[] {"TABLE"})) {
    while (tables.next()) {
        String table = tables.getString("TABLE_NAME");
        try (ResultSet columns = md.getColumns(null, "ANN", table, "%")) {
            while (columns.next()) {
                System.out.println(table + "." + columns.getString("COLUMN_NAME") + " "
                        + columns.getString("TYPE_NAME") + " " + columns.getInt("DATA_TYPE"));
            }
        }
    }
}
try (ResultSet keys = md.getPrimaryKeys(null, "ANN", "STAFF")) {
    keys.next();
    System.out.println(keys.getString("COLUMN_NAME") + " " + keys.getString("PK_NAME"));
}
```

```
STAFF.ID INTEGER 4
STAFF.NAME CHARACTER VARYING 12
STAFF.SALARY DECIMAL 3
ID STAFF_PRIMARY1
```

## STANDARD

[DESIGN] D2; JDBC 4.3, `java.sql.DatabaseMetaData`.

## SEE ALSO

[Connection](02-api-connection.md),
[ResultSet and Metadata](04-api-result-sets.md)
