package com.turborel.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.internal.codec.CharUnits;
import com.turborel.jdbc.internal.codec.Column;
import com.turborel.jdbc.internal.codec.DataType;
import com.turborel.jdbc.internal.codec.Declared;
import com.turborel.jdbc.internal.codec.Parameter;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.internal.session.Scripted;
import java.io.StringWriter;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.sql.Wrapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** The edges of the JDBC objects: their wrappers, their bounds and their types ([DESIGN] D2, D14). */
class EdgesTest {
    private static final DataType TEXT = new DataType.CharacterType(true, 10, CharUnits.CHARACTERS);

    private static final DataType INT = new DataType.ExactType(10, 0);

    private static JdbcConnection connection() throws TurborelException {
        return new JdbcConnection(Scripted.after(List.of()), "jdbc:turborel://scripted");
    }

    private static void assertMisuse(Executable call) {
        assertEquals("TB910", assertThrows(SQLException.class, call).getSQLState());
    }

    /**
     * Verifies: [DESIGN] D2 each JDBC object wraps itself alone: none of null, none of a class it
     * is not.
     */
    @Test
    void eachObjectWrapsItselfAlone() throws SQLException {
        JdbcConnection c = connection();
        try (java.sql.Statement s = c.createStatement()) {
            assertWrapsItselfAlone(c, s);
        }
    }

    private static void assertWrapsItselfAlone(JdbcConnection c, java.sql.Statement s) throws SQLException {
        List<Wrapper> each = List.of(
                c,
                s,
                c.getMetaData(),
                new TurborelDataSource(),
                new JdbcResultSet(null, c, List.of(), Rows.of(List.of())),
                new JdbcResultSetMetaData(List.of()),
                new JdbcParameterMetaData(List.of()));
        for (Wrapper w : each) {
            assertFalse(w.isWrapperFor(null), w.getClass().getName());
            assertFalse(w.isWrapperFor(StringWriter.class), w.getClass().getName());
            assertTrue(w.isWrapperFor(w.getClass()));
        }
    }

    /**
     * Verifies: [DESIGN] D2 a column or parameter of no place is refused, 0 as one past the last;
     * as are a Blob or Clob read before its first place or of a negative length, and written past
     * one beyond its end.
     */
    @Test
    void placesAreBounded() throws SQLException {
        JdbcConnection c = connection();
        ResultSet rs = new JdbcResultSet(
                null, c, List.of(new Column("A", TEXT, false)), Rows.of(List.of(List.of(new Value.Text("a")))));
        assertTrue(rs.next());
        assertMisuse(() -> rs.getString(0));
        assertMisuse(() -> rs.getMetaData().getColumnName(0));
        JdbcParameterMetaData pm = new JdbcParameterMetaData(List.of(new Parameter(false, TEXT)));
        assertMisuse(() -> pm.getParameterType(0));
        JdbcBlob blob = new JdbcBlob(new byte[] {1, 2});
        assertMisuse(() -> blob.getBytes(0, 1));
        assertMisuse(() -> blob.getBytes(1, -1));
        assertMisuse(() -> blob.setBytes(0, new byte[] {1}));
        assertMisuse(() -> blob.setBytes(4, new byte[] {1}));
        assertEquals(1, blob.setBytes(2, new byte[] {9}));
        assertEquals(2, blob.length(), "within its end, not grown");
        JdbcClob clob = new JdbcClob("ab");
        assertMisuse(() -> clob.getSubString(0, 1));
        assertMisuse(() -> clob.getSubString(1, -1));
        assertMisuse(() -> clob.setString(0, "x"));
        assertMisuse(() -> clob.setString(4, "x"));
    }

    /**
     * Verifies: [DESIGN] D14 a declared type JDBC numbers gives the column's number and name; a
     * distinct type, USER-DEFINED, its source's number under its own name; a declared type JDBC
     * has no number for, the number its data type has; an array's elements take its declared
     * element type, and a column of no array type has no elements.
     */
    @Test
    void declaredTypesMapWhereJdbcNumbersThem() {
        Column distinct = new Column("M", new DataType.ExactType(9, 2), false, new Declared("USER-DEFINED", "S.MONEY"));
        assertEquals(Types.DECIMAL, JdbcTypes.of(distinct));
        assertEquals("S.MONEY", JdbcTypes.name(distinct));
        Column plugin = new Column("G", TEXT, false, new Declared("GEOMETRY", "GEOMETRY"));
        assertEquals(Types.VARCHAR, JdbcTypes.of(plugin));
        assertEquals("GEOMETRY", JdbcTypes.name(plugin));
        Column bare = new Column("A", INT, false);
        assertEquals(Types.DECIMAL, JdbcTypes.of(bare));
        assertEquals("java.math.BigDecimal", JdbcTypes.className(bare));
        assertNull(JdbcArray.element(bare));
        Column array = new Column("I", new DataType.ArrayType(3, INT), false);
        assertEquals(new Column(null, INT, false), JdbcArray.element(array));
        Column declared =
                new Column("I", new DataType.ArrayType(3, INT), false, new Declared("ARRAY", "DECIMAL(9,2) ARRAY[3]"));
        Column element = JdbcArray.element(declared);
        assertEquals(new Declared("DECIMAL", "DECIMAL(9,2)"), element == null ? null : element.declared());
    }

    /**
     * Verifies: [DESIGN] D2 the metadata says what the driver does not support: no transaction of
     * isolation none, no scrollable or updatable result set, none held over a commit.
     */
    @Test
    void theMetadataRefusesWhatItLacks() throws SQLException {
        DatabaseMetaData md = connection().getMetaData();
        assertFalse(md.supportsTransactionIsolationLevel(java.sql.Connection.TRANSACTION_NONE));
        assertTrue(md.supportsTransactionIsolationLevel(java.sql.Connection.TRANSACTION_SERIALIZABLE));
        assertFalse(md.supportsResultSetType(ResultSet.TYPE_SCROLL_INSENSITIVE));
        assertTrue(md.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse(md.supportsResultSetConcurrency(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY));
        assertFalse(md.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE));
        assertTrue(md.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
        assertFalse(md.supportsResultSetHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT));
        assertTrue(md.supportsResultSetHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT));
    }

    /**
     * Verifies: [DESIGN] D2 a number of each kind is signed and a string of none; a string of each
     * kind is compared with its case and a number is not; a null read as a boolean is false.
     */
    @Test
    void columnsSayTheirKind() throws SQLException {
        List<DataType> types = List.of(
                new DataType.ApproximateType(24),
                new DataType.ApproximateType(53),
                new DataType.CharacterType(false, 1, CharUnits.CHARACTERS),
                new DataType.LobType(com.turborel.jdbc.internal.codec.LobKind.CHARACTER, 9, CharUnits.CHARACTERS),
                new DataType.BooleanType());
        List<Column> columns = new java.util.ArrayList<>();
        for (DataType t : types) {
            columns.add(new Column("C", t, false));
        }
        java.sql.ResultSetMetaData md = new JdbcResultSetMetaData(columns);
        assertTrue(md.isSigned(1) && md.isSigned(2));
        assertFalse(md.isSigned(3) || md.isCaseSensitive(1) || md.isCaseSensitive(5));
        assertTrue(md.isCaseSensitive(3) && md.isCaseSensitive(4));
        ResultSet rs = new JdbcResultSet(
                null,
                connection(),
                List.of(new Column("B", types.get(4), false)),
                Rows.of(List.of(List.of(Value.NULL))));
        assertTrue(rs.isBeforeFirst());
        assertTrue(rs.next());
        assertFalse(rs.isBeforeFirst());
        assertTrue(rs.isFirst());
        assertFalse(rs.getBoolean(1));
        assertTrue(rs.wasNull());
        assertFalse(rs.next());
        assertFalse(rs.isFirst());
    }

    /**
     * Verifies: [DESIGN] D2 a stream or reader of none sends nothing; an array of no element type
     * known is of base type NULL, its elements read as their values say, its result set's values
     * of text.
     */
    @Test
    void noneIsNothing() throws SQLException {
        assertEquals(0, JdbcPreparedStatement.read((java.io.InputStream) null, -1).length);
        assertEquals("", JdbcPreparedStatement.read((java.io.Reader) null, -1));
        java.sql.Array array = new JdbcArray((Column) null, List.of(new Value.Text("x")), connection());
        assertEquals("NULL", array.getBaseTypeName());
        assertEquals("x", ((Object[]) array.getArray())[0]);
        assertEquals(new Value.Text("x"), ((JdbcArray) array).items()[0], "sent back as read");
        try (ResultSet rs = array.getResultSet()) {
            assertTrue(rs.next());
            assertEquals("x", rs.getString(2));
        }
    }

    /**
     * Verifies: [DESIGN] D2 an SQLXML being written or freed is written no more; an escape's
     * quoted name may hold a brace; an empty result set, read to its end, is not before its first
     * row, and a false value is read false; a Float and a Double are both approximate numbers.
     */
    @Test
    void writtenOnceAndRead() throws SQLException {
        JdbcSQLXML freed = new JdbcSQLXML();
        freed.free();
        assertMisuse(() -> freed.setString("<a/>"));
        JdbcSQLXML writing = new JdbcSQLXML();
        assertTrue(writing.setCharacterStream() != null);
        assertMisuse(() -> writing.setString("<a/>"));
        assertEquals("SELECT UPPER(\"{N}\") FROM T", Escapes.translate("SELECT {fn UPPER(\"{N}\")} FROM T"));
        ResultSet rs = new JdbcResultSet(null, connection(), List.of(new Column("A", TEXT, false)), Rows.of(List.of()));
        assertFalse(rs.next());
        assertFalse(rs.isBeforeFirst());
        ResultSet no = new JdbcResultSet(
                null,
                connection(),
                List.of(new Column("B", new DataType.BooleanType(), false)),
                Rows.of(List.of(List.of(new Value.Truth(false)))));
        assertTrue(no.next());
        assertFalse(no.getBoolean(1));
        assertFalse(no.wasNull());
        assertEquals(new Value.Approximate(1.5), ToValue.natural(1.5f));
        assertEquals(new Value.Approximate(2.5), ToValue.natural(2.5d));
    }

    /**
     * Verifies: [DESIGN] D2 a statement's most field size cuts each character or binary string
     * read to that many octets, a character string at a character's end as UTF-8 writes it; a
     * shorter one, or a value of another type, is read whole.
     */
    @Test
    void fieldsAreCutToTheirMostOctets() throws SQLException {
        assertEquals(new Value.Text("a\u00f1"), JdbcResultSet.cut(new Value.Text("a\u00f1b"), 3));
        assertEquals(new Value.Text("a"), JdbcResultSet.cut(new Value.Text("a\u00f1b"), 2));
        assertEquals(new Value.Text("ab"), JdbcResultSet.cut(new Value.Text("ab"), 2));
        com.turborel.jdbc.internal.codec.OctetValue.Binary octets =
                new com.turborel.jdbc.internal.codec.OctetValue.Binary(
                        com.turborel.jdbc.internal.codec.Octets.of(new byte[] {1, 2, 3}));
        assertEquals(
                2,
                ((com.turborel.jdbc.internal.codec.OctetValue.Binary) JdbcResultSet.cut(octets, 2))
                        .octets()
                        .length());
        assertEquals(octets, JdbcResultSet.cut(octets, 3));
        assertEquals(new Value.Truth(true), JdbcResultSet.cut(new Value.Truth(true), 1));
        ResultSet rs = new JdbcResultSet(
                        null,
                        connection(),
                        List.of(new Column("A", TEXT, false)),
                        Rows.of(List.of(List.of(new Value.Text("abc")))))
                .cut(2);
        assertTrue(rs.next());
        assertEquals("ab", rs.getString(1));
    }

    /**
     * Verifies: [DESIGN] D2 a connection aborted closes its statements and their result sets,
     * sending nothing; each then refuses its use.
     */
    @Test
    void anAbortedConnectionClosesItsStatements() throws SQLException {
        JdbcConnection c = connection();
        try (JdbcStatement s = (JdbcStatement) c.createStatement()) {
            s.outcome(new com.turborel.jdbc.internal.session.Outcome.Rows(
                    List.of(new Column("A", TEXT, false)), List.of()));
            ResultSet rs = s.getResultSet();
            c.abort(Runnable::run);
            assertTrue(c.isClosed() && s.isClosed() && rs != null && rs.isClosed());
            assertMisuse(s::getResultSet);
            assertMisuse(rs::next);
        }
    }

    /**
     * Verifies: [DESIGN] D2 a connection of a stream the program gave, told no status: its
     * isolation READ COMMITTED, its version the Ready's; autocommit turned on outside a
     * transaction sends nothing; a statement of no count told completes with 0; cancel with no
     * canceller does nothing; a value read as a class it is none of is refused (22018).
     */
    @Test
    void aConnectionToldLittle() throws SQLException {
        JdbcConnection c = connection();
        assertEquals(java.sql.Connection.TRANSACTION_READ_COMMITTED, c.getTransactionIsolation());
        assertEquals("turborel 0.0.0 (alpha)", c.getMetaData().getDatabaseProductVersion());
        c.setAutoCommit(false);
        c.setAutoCommit(true);
        c.setAutoCommit(true);
        assertTrue(c.getAutoCommit());
        try (JdbcStatement s = (JdbcStatement) c.createStatement()) {
            s.outcome(
                    new com.turborel.jdbc.internal.session.Outcome.Done(new com.turborel.jdbc.internal.codec.Completed(
                            0,
                            "00000",
                            false,
                            new com.turborel.jdbc.internal.codec.Completed.Fields(
                                    null, false, null, List.of(), List.of(), null))));
            assertEquals(0, s.getUpdateCount());
            s.cancel();
        }
        ResultSet rs = new JdbcResultSet(
                null, c, List.of(new Column("A", TEXT, false)), Rows.of(List.of(List.of(new Value.Text("x")))));
        assertTrue(rs.next());
        assertEquals(
                "22018",
                assertThrows(SQLException.class, () -> rs.getObject(1, java.util.Map.class))
                        .getSQLState());
    }

    /**
     * Verifies: [DESIGN] D2 an SQLXML is sent as text to a character parameter, and octets of a
     * binary string as they are; an array's array elements, of no element type known, are read as
     * arrays; an SQLXML written is written no more.
     */
    @Test
    void objectsAreSentAsTheyAre() throws SQLException {
        assertEquals("<a/>", ToValue.text(new JdbcSQLXML("<a/>")));
        byte[] octets = {1, 2};
        assertArrayEquals(
                octets,
                ToValue.bytes(new com.turborel.jdbc.internal.codec.OctetValue.Binary(
                        com.turborel.jdbc.internal.codec.Octets.of(octets))));
        java.sql.Array nested =
                new JdbcArray((Column) null, List.of(new Value.Array(List.of(new Value.Text("x")))), connection());
        assertInstanceOf(java.sql.Array.class, ((Object[]) nested.getArray())[0]);
        JdbcSQLXML written = new JdbcSQLXML();
        written.setString("<a/>");
        assertMisuse(written::setCharacterStream);
    }

    /**
     * Verifies: [DESIGN] D2 isValid of a server that grants no Ping checks by a query that begins
     * no transaction of the program's: with none active, autocommit off, it is sent to end with
     * its statement, and autocommit stays off.
     */
    @Test
    void checkingBeginsNoTransaction() throws SQLException {
        JdbcConnection c =
                new JdbcConnection(Scripted.after(List.of(), Scripted.complete(false)), "jdbc:turborel://scripted");
        c.setAutoCommit(false);
        assertTrue(c.isValid(0));
        assertFalse(c.getAutoCommit());
        byte[] sent = Scripted.written(c.session());
        byte[] check = new com.turborel.jdbc.internal.codec.Request.Query("VALUES 1", true, List.of(), false).encode();
        assertArrayEquals(check, java.util.Arrays.copyOfRange(sent, sent.length - check.length, sent.length));
    }

    /**
     * Verifies: [DESIGN] D9 a failure of SQLSTATE class 42, or the older 2A and 37, is thrown as a
     * SQLSyntaxErrorException of its SQLSTATE, vendor code and message, its cause the failure; a
     * failure of another class, or not the driver's, as it is.
     */
    @Test
    void syntaxFailuresAreSqlSyntaxErrors() {
        for (String state : List.of("42000", "2A000", "37000")) {
            TurborelException failure = new TurborelException(-103, state, "no such table");
            SQLException classed = Errors.classed(failure);
            assertInstanceOf(java.sql.SQLSyntaxErrorException.class, classed, state);
            assertEquals(state, classed.getSQLState());
            assertEquals(-103, classed.getErrorCode());
            assertEquals(failure, classed.getCause());
        }
        TurborelException data = new TurborelException(-301, "22012", "division by zero");
        assertEquals(data, Errors.classed(data));
        SQLException foreign = new SQLException("x", "42000");
        assertEquals(foreign, Errors.classed(foreign));
        SQLException none = new TurborelException(-1, "4", "short");
        assertEquals(none, Errors.classed(none));
    }
}
