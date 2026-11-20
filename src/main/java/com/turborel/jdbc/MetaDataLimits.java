package com.turborel.jdbc;

import java.sql.DatabaseMetaData;

/**
 * What a database is called and its limits, as JDBC asks them ([DESIGN] D2): its product, the
 * driver, the version of JDBC, the terms and the quote it uses, and the most of each name; a limit
 * of 0 is none, or none known.
 */
abstract class MetaDataLimits implements DatabaseMetaData {
    /** The product's name, as its status SERVER_VERSION begins. */
    static final String PRODUCT = "turborel";

    /** The driver's name and version (RULES.md D5). */
    static final String DRIVER = "turborel-jdbc";

    static final String VERSION = "0.0.0";

    /** The version of JDBC the driver gives. */
    static final int JDBC_MAJOR = 4;

    static final int JDBC_MINOR = 3;

    /** The most characters of a name: an identifier's. */
    static final int NAME_LENGTH = 128;

    @Override
    public String getDatabaseProductName() throws java.sql.SQLException {
        return PRODUCT;
    }

    @Override
    public String getDriverName() throws java.sql.SQLException {
        return DRIVER;
    }

    @Override
    public String getDriverVersion() throws java.sql.SQLException {
        return VERSION;
    }

    @Override
    public int getDriverMajorVersion() {
        return 0;
    }

    @Override
    public int getDriverMinorVersion() {
        return 0;
    }

    @Override
    public String getIdentifierQuoteString() throws java.sql.SQLException {
        return "\"";
    }

    @Override
    public String getSQLKeywords() throws java.sql.SQLException {
        return "";
    }

    @Override
    public String getNumericFunctions() throws java.sql.SQLException {
        return "";
    }

    @Override
    public String getStringFunctions() throws java.sql.SQLException {
        return "";
    }

    @Override
    public String getSystemFunctions() throws java.sql.SQLException {
        return "";
    }

    @Override
    public String getTimeDateFunctions() throws java.sql.SQLException {
        return "";
    }

    @Override
    public String getSearchStringEscape() throws java.sql.SQLException {
        return "\\";
    }

    @Override
    public String getExtraNameCharacters() throws java.sql.SQLException {
        return "";
    }

    @Override
    public String getSchemaTerm() throws java.sql.SQLException {
        return "schema";
    }

    @Override
    public String getProcedureTerm() throws java.sql.SQLException {
        return "procedure";
    }

    @Override
    public String getCatalogTerm() throws java.sql.SQLException {
        return "catalog";
    }

    @Override
    public String getCatalogSeparator() throws java.sql.SQLException {
        return ".";
    }

    @Override
    public int getMaxBinaryLiteralLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxCharLiteralLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxColumnNameLength() throws java.sql.SQLException {
        return NAME_LENGTH;
    }

    @Override
    public int getMaxColumnsInGroupBy() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxColumnsInIndex() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxColumnsInOrderBy() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxColumnsInSelect() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxColumnsInTable() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxConnections() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxCursorNameLength() throws java.sql.SQLException {
        return NAME_LENGTH;
    }

    @Override
    public int getMaxIndexLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxSchemaNameLength() throws java.sql.SQLException {
        return NAME_LENGTH;
    }

    @Override
    public int getMaxProcedureNameLength() throws java.sql.SQLException {
        return NAME_LENGTH;
    }

    @Override
    public int getMaxCatalogNameLength() throws java.sql.SQLException {
        return NAME_LENGTH;
    }

    @Override
    public int getMaxRowSize() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxStatementLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxStatements() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxTableNameLength() throws java.sql.SQLException {
        return NAME_LENGTH;
    }

    @Override
    public int getMaxTablesInSelect() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxUserNameLength() throws java.sql.SQLException {
        return NAME_LENGTH;
    }

    @Override
    public int getDefaultTransactionIsolation() throws java.sql.SQLException {
        return java.sql.Connection.TRANSACTION_READ_COMMITTED;
    }

    @Override
    public int getResultSetHoldability() throws java.sql.SQLException {
        return java.sql.ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public int getJDBCMajorVersion() throws java.sql.SQLException {
        return JDBC_MAJOR;
    }

    @Override
    public int getJDBCMinorVersion() throws java.sql.SQLException {
        return JDBC_MINOR;
    }

    @Override
    public int getSQLStateType() throws java.sql.SQLException {
        return sqlStateSQL;
    }

    @Override
    public java.sql.RowIdLifetime getRowIdLifetime() throws java.sql.SQLException {
        return java.sql.RowIdLifetime.ROWID_UNSUPPORTED;
    }
}
