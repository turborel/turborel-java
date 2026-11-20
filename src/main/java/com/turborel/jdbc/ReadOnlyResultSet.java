package com.turborel.jdbc;

import java.sql.ResultSet;

/**
 * What a forward-only, read-only result set refuses ([DESIGN] D2): every update, the insert row,
 * and every move but {@code next}; a row is never updated, inserted or deleted.
 */
abstract class ReadOnlyResultSet implements ResultSet {
    @Override
    public String getCursorName() throws java.sql.SQLException {
        throw Errors.unsupported("a result set's cursor name");
    }

    @Override
    public void beforeFirst() throws java.sql.SQLException {
        throw Errors.misuse("a forward-only result set moves by next alone");
    }

    @Override
    public void afterLast() throws java.sql.SQLException {
        throw Errors.misuse("a forward-only result set moves by next alone");
    }

    @Override
    public boolean first() throws java.sql.SQLException {
        throw Errors.misuse("a forward-only result set moves by next alone");
    }

    @Override
    public boolean last() throws java.sql.SQLException {
        throw Errors.misuse("a forward-only result set moves by next alone");
    }

    @Override
    public boolean absolute(int p0) throws java.sql.SQLException {
        throw Errors.misuse("a forward-only result set moves by next alone");
    }

    @Override
    public boolean relative(int p0) throws java.sql.SQLException {
        throw Errors.misuse("a forward-only result set moves by next alone");
    }

    @Override
    public boolean previous() throws java.sql.SQLException {
        throw Errors.misuse("a forward-only result set moves by next alone");
    }

    @Override
    public boolean rowUpdated() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean rowInserted() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean rowDeleted() throws java.sql.SQLException {
        return false;
    }

    @Override
    public void updateNull(int p0) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateBoolean(int p0, boolean p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateByte(int p0, byte p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateShort(int p0, short p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateInt(int p0, int p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateLong(int p0, long p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateFloat(int p0, float p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateDouble(int p0, double p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateBigDecimal(int p0, java.math.BigDecimal p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateString(int p0, String p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateBytes(int p0, byte[] p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateDate(int p0, java.sql.Date p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateTime(int p0, java.sql.Time p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateTimestamp(int p0, java.sql.Timestamp p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateAsciiStream(int p0, java.io.InputStream p1, int p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateBinaryStream(int p0, java.io.InputStream p1, int p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateCharacterStream(int p0, java.io.Reader p1, int p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateObject(int p0, Object p1, int p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateObject(int p0, Object p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateNull(String p0) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateBoolean(String p0, boolean p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateByte(String p0, byte p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateShort(String p0, short p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateInt(String p0, int p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateLong(String p0, long p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateFloat(String p0, float p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateDouble(String p0, double p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateBigDecimal(String p0, java.math.BigDecimal p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateString(String p0, String p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateBytes(String p0, byte[] p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateDate(String p0, java.sql.Date p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateTime(String p0, java.sql.Time p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateTimestamp(String p0, java.sql.Timestamp p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateAsciiStream(String p0, java.io.InputStream p1, int p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateBinaryStream(String p0, java.io.InputStream p1, int p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateCharacterStream(String p0, java.io.Reader p1, int p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateObject(String p0, Object p1, int p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateObject(String p0, Object p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void insertRow() throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateRow() throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void deleteRow() throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void refreshRow() throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void cancelRowUpdates() throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void moveToInsertRow() throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void moveToCurrentRow() throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateRef(int p0, java.sql.Ref p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateRef(String p0, java.sql.Ref p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateBlob(int p0, java.sql.Blob p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateBlob(String p0, java.sql.Blob p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateClob(int p0, java.sql.Clob p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateClob(String p0, java.sql.Clob p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateArray(int p0, java.sql.Array p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateArray(String p0, java.sql.Array p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public java.sql.RowId getRowId(int p0) throws java.sql.SQLException {
        throw Errors.unsupported("a row ID");
    }

    @Override
    public java.sql.RowId getRowId(String p0) throws java.sql.SQLException {
        throw Errors.unsupported("a row ID");
    }

    @Override
    public void updateRowId(int p0, java.sql.RowId p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateRowId(String p0, java.sql.RowId p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateNString(int p0, String p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateNString(String p0, String p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateNClob(int p0, java.sql.NClob p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateNClob(String p0, java.sql.NClob p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateSQLXML(int p0, java.sql.SQLXML p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateSQLXML(String p0, java.sql.SQLXML p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateNCharacterStream(int p0, java.io.Reader p1, long p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateNCharacterStream(String p0, java.io.Reader p1, long p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateAsciiStream(int p0, java.io.InputStream p1, long p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateBinaryStream(int p0, java.io.InputStream p1, long p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateCharacterStream(int p0, java.io.Reader p1, long p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateAsciiStream(String p0, java.io.InputStream p1, long p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateBinaryStream(String p0, java.io.InputStream p1, long p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateCharacterStream(String p0, java.io.Reader p1, long p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateBlob(int p0, java.io.InputStream p1, long p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateBlob(String p0, java.io.InputStream p1, long p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateClob(int p0, java.io.Reader p1, long p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateClob(String p0, java.io.Reader p1, long p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateNClob(int p0, java.io.Reader p1, long p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateNClob(String p0, java.io.Reader p1, long p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateNCharacterStream(int p0, java.io.Reader p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateNCharacterStream(String p0, java.io.Reader p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateAsciiStream(int p0, java.io.InputStream p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateBinaryStream(int p0, java.io.InputStream p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateCharacterStream(int p0, java.io.Reader p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateAsciiStream(String p0, java.io.InputStream p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateBinaryStream(String p0, java.io.InputStream p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateCharacterStream(String p0, java.io.Reader p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateBlob(int p0, java.io.InputStream p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateBlob(String p0, java.io.InputStream p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateClob(int p0, java.io.Reader p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateClob(String p0, java.io.Reader p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateNClob(int p0, java.io.Reader p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateNClob(String p0, java.io.Reader p1) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateObject(int p0, Object p1, java.sql.SQLType p2, int p3) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateObject(String p0, Object p1, java.sql.SQLType p2, int p3) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateObject(int p0, Object p1, java.sql.SQLType p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }

    @Override
    public void updateObject(String p0, Object p1, java.sql.SQLType p2) throws java.sql.SQLException {
        throw Errors.unsupported("updating a result set");
    }
}
