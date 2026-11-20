package com.turborel.jdbc;

/**
 * What a database is, as JDBC asks it, of answers that do not change ([DESIGN] D2): the SQL it
 * takes, its limits, its names. Turborel reads SQL-92, regular identifiers in upper case and
 * delimited ones as written; sorts nulls high; takes every isolation level; and a result set of the
 * driver's is forward-only and read-only. A limit of 0 is none, or none known.
 */
abstract class MetaDataFacts extends MetaDataLimits {
    @Override
    public boolean allProceduresAreCallable() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean allTablesAreSelectable() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean nullsAreSortedHigh() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean nullsAreSortedLow() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtStart() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtEnd() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean usesLocalFiles() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean usesLocalFilePerTable() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsMixedCaseIdentifiers() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean storesUpperCaseIdentifiers() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean storesLowerCaseIdentifiers() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean storesMixedCaseIdentifiers() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsColumnAliasing() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean nullPlusNonNullIsNull() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsConvert() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsConvert(int p0, int p1) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsTableCorrelationNames() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsExpressionsInOrderBy() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsOrderByUnrelated() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsGroupBy() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsGroupByUnrelated() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsGroupByBeyondSelect() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsLikeEscapeClause() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsMultipleResultSets() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsMultipleTransactions() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsNonNullableColumns() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsMinimumSQLGrammar() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsCoreSQLGrammar() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsExtendedSQLGrammar() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsANSI92FullSQL() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsOuterJoins() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsFullOuterJoins() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsLimitedOuterJoins() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean isCatalogAtStart() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsSchemasInDataManipulation() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsPositionedDelete() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsPositionedUpdate() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsSelectForUpdate() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsStoredProcedures() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInComparisons() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInExists() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInIns() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsCorrelatedSubqueries() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsUnion() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsUnionAll() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsTransactions() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int p0) throws java.sql.SQLException {
        return p0 != java.sql.Connection.TRANSACTION_NONE;
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsResultSetType(int p0) throws java.sql.SQLException {
        return p0 == java.sql.ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public boolean supportsResultSetConcurrency(int p0, int p1) throws java.sql.SQLException {
        return p0 == java.sql.ResultSet.TYPE_FORWARD_ONLY && p1 == java.sql.ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public boolean ownUpdatesAreVisible(int p0) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean ownDeletesAreVisible(int p0) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean ownInsertsAreVisible(int p0) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean othersUpdatesAreVisible(int p0) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean othersDeletesAreVisible(int p0) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean othersInsertsAreVisible(int p0) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean updatesAreDetected(int p0) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean deletesAreDetected(int p0) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean insertsAreDetected(int p0) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsBatchUpdates() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsSavepoints() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsNamedParameters() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsMultipleOpenResults() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsGetGeneratedKeys() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsResultSetHoldability(int p0) throws java.sql.SQLException {
        return p0 == java.sql.ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public boolean locatorsUpdateCopy() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsStatementPooling() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean generatedKeyAlwaysReturned() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsRefCursors() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsSharding() throws java.sql.SQLException {
        return false;
    }
}
