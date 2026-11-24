package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.Called;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.internal.session.Outcome;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * A callable statement, a CALL ([DESIGN] D2). JDBC counts every {@code ?} of it, the protocol its
 * IN parameters alone and gives its OUT parameters as a row. Granted {@code parameter-modes}, the
 * server tells each {@code ?}'s mode and ordinal position ([PROTOCOL Parameter modes]): an IN or
 * INOUT one takes a value, sent in the order written, and an OUT or INOUT one is read from the row,
 * its column of its parameter's place among them. Else a parameter given a value is an IN
 * parameter, one registered an OUT parameter, and one both an INOUT, each read in order. JDBC's
 * {@code {call ...}} escape is a CALL.
 */
final class JdbcCallableStatement extends NamedParameters {
    private final TreeMap<Integer, @Nullable Object> given = new TreeMap<>();
    private final TreeSet<Integer> outs = new TreeSet<>();
    private @Nullable JdbcResultSet outRow;

    JdbcCallableStatement(JdbcConnection connection, String sql) throws SQLException {
        super(connection, sql);
    }

    @Override
    void set(int i, @Nullable Object x) throws SQLException {
        open();
        if (i < 1) {
            throw Errors.misuse("parameter " + i + " is not of 1 or more");
        }
        input(i);
        given.put(i, x);
    }

    @Override
    List<Value> values() throws SQLException {
        Called called = prepared.called();
        if (called != null) {
            return told(called.marks());
        }
        if (given.size() != prepared.parameters().size()) {
            throw new TurborelException(
                    Errors.DYNAMIC_SQLCODE,
                    Errors.NO_VALUE_STATE,
                    "[DESIGN] D2 the CALL takes " + prepared.parameters().size() + " IN parameters, and " + given.size()
                            + " have values");
        }
        List<Value> all = new ArrayList<>(given.size());
        int at = 1;
        for (Object x : given.values()) {
            all.add(ToValue.of(x, parameterType(at++)));
        }
        return all;
    }

    /** The values of the marks that take one, in the order written, each of its input's type. */
    private List<Value> told(List<Called.Mark> marks) throws SQLException {
        List<Value> all = new ArrayList<>();
        for (int i = 1; i <= marks.size(); i++) {
            if (!marks.get(i - 1).mode().takesValue()) {
                continue;
            }
            if (!given.containsKey(i)) {
                throw new TurborelException(
                        Errors.DYNAMIC_SQLCODE, Errors.NO_VALUE_STATE, "[DESIGN] D2 parameter " + i + " has no value");
            }
            all.add(ToValue.of(given.get(i), parameterType(input(i))));
        }
        return all;
    }

    /** Whether the CALL gives values back: of OUT or INOUT marks told, else of marks registered. */
    private boolean givesValues() {
        Called called = prepared.called();
        if (called == null) {
            return !outs.isEmpty();
        }
        return called.marks().stream().anyMatch(m -> m.mode().givesValue());
    }

    @Override
    boolean runPrepared() throws SQLException {
        outRow = null;
        List<Value> sent = values();
        return running(() -> {
            Outcome outcome = statements().execute(prepared, sent);
            if (outcome instanceof Outcome.Rows rows && givesValues()) {
                JdbcResultSet row = new JdbcResultSet(null, connection, rows.columns(), Rows.of(rows.rows()));
                row.next();
                outRow = row;
                return false;
            }
            return outcome(outcome);
        });
    }

    @Override
    public void clearParameters() throws SQLException {
        open();
        given.clear();
    }

    /** The OUT parameters' row, once executed. */
    JdbcResultSet out() throws SQLException {
        open();
        JdbcResultSet row = outRow;
        if (row == null) {
            throw Errors.misuse("the CALL gave no OUT parameters: execute it, its OUT parameters registered");
        }
        return row;
    }

    @Override
    JdbcResultSet byName() throws SQLException {
        return out();
    }

    /** The column of the row that OUT parameter {@code i} is. */
    private int column(int i) throws SQLException {
        Called called = prepared.called();
        if (called != null) {
            Called.Mark mark = gives(called, i);
            return (int) called.marks().stream()
                            .filter(m -> m.mode().givesValue() && m.ordinal() < mark.ordinal())
                            .map(Called.Mark::ordinal)
                            .distinct()
                            .count()
                    + 1;
        }
        if (!outs.contains(i)) {
            throw Errors.misuse("parameter " + i + " is not registered as an OUT parameter");
        }
        return outs.headSet(i).size() + 1;
    }

    private void register(int i) throws SQLException {
        open();
        if (i < 1) {
            throw Errors.misuse("parameter " + i + " is not of 1 or more");
        }
        Called called = prepared.called();
        if (called != null) {
            gives(called, i);
        }
        outs.add(i);
    }

    /** The mark {@code i} of a CALL told, one that gives a value back: OUT or INOUT. */
    private static Called.Mark gives(Called called, int i) throws SQLException {
        if (i > called.marks().size()) {
            throw Errors.misuse(
                    "parameter " + i + " is not of 1 to " + called.marks().size());
        }
        Called.Mark mark = called.marks().get(i - 1);
        if (!mark.mode().givesValue()) {
            throw Errors.misuse("parameter " + i + " is IN: it gives no value back");
        }
        return mark;
    }

    @Override
    public void registerOutParameter(int i, int sqlType) throws SQLException {
        register(i);
    }

    @Override
    public void registerOutParameter(int i, int sqlType, int scale) throws SQLException {
        register(i);
    }

    @Override
    public void registerOutParameter(int i, int sqlType, @Nullable String typeName) throws SQLException {
        register(i);
    }

    @Override
    public void registerOutParameter(int i, @Nullable SQLType sqlType) throws SQLException {
        register(i);
    }

    @Override
    public void registerOutParameter(int i, @Nullable SQLType sqlType, int scale) throws SQLException {
        register(i);
    }

    @Override
    public void registerOutParameter(int i, @Nullable SQLType sqlType, @Nullable String typeName) throws SQLException {
        register(i);
    }

    @Override
    public void registerOutParameter(String name, int sqlType) throws SQLException {
        throw Errors.unsupported("a parameter registered by its name");
    }

    @Override
    public void registerOutParameter(String name, int sqlType, int scale) throws SQLException {
        throw Errors.unsupported("a parameter registered by its name");
    }

    @Override
    public void registerOutParameter(String name, int sqlType, @Nullable String typeName) throws SQLException {
        throw Errors.unsupported("a parameter registered by its name");
    }

    @Override
    public void registerOutParameter(String name, @Nullable SQLType sqlType) throws SQLException {
        throw Errors.unsupported("a parameter registered by its name");
    }

    @Override
    public void registerOutParameter(String name, @Nullable SQLType sqlType, int scale) throws SQLException {
        throw Errors.unsupported("a parameter registered by its name");
    }

    @Override
    public void registerOutParameter(String name, @Nullable SQLType sqlType, @Nullable String typeName)
            throws SQLException {
        throw Errors.unsupported("a parameter registered by its name");
    }

    @Override
    public boolean wasNull() throws SQLException {
        return out().wasNull();
    }

    @Override
    public @Nullable String getString(int i) throws SQLException {
        return out().getString(column(i));
    }

    @Override
    public boolean getBoolean(int i) throws SQLException {
        return out().getBoolean(column(i));
    }

    @Override
    public byte getByte(int i) throws SQLException {
        return out().getByte(column(i));
    }

    @Override
    public short getShort(int i) throws SQLException {
        return out().getShort(column(i));
    }

    @Override
    public int getInt(int i) throws SQLException {
        return out().getInt(column(i));
    }

    @Override
    public long getLong(int i) throws SQLException {
        return out().getLong(column(i));
    }

    @Override
    public float getFloat(int i) throws SQLException {
        return out().getFloat(column(i));
    }

    @Override
    public double getDouble(int i) throws SQLException {
        return out().getDouble(column(i));
    }

    @Override
    @Deprecated
    public @Nullable BigDecimal getBigDecimal(int i, int p1) throws SQLException {
        return out().getBigDecimal(column(i), p1);
    }

    @Override
    public byte @Nullable [] getBytes(int i) throws SQLException {
        return out().getBytes(column(i));
    }

    @Override
    public @Nullable Date getDate(int i) throws SQLException {
        return out().getDate(column(i));
    }

    @Override
    public @Nullable Time getTime(int i) throws SQLException {
        return out().getTime(column(i));
    }

    @Override
    public @Nullable Timestamp getTimestamp(int i) throws SQLException {
        return out().getTimestamp(column(i));
    }

    @Override
    public @Nullable Object getObject(int i) throws SQLException {
        return out().getObject(column(i));
    }

    @Override
    public @Nullable BigDecimal getBigDecimal(int i) throws SQLException {
        return out().getBigDecimal(column(i));
    }

    @Override
    public @Nullable Object getObject(int i, @Nullable Map<String, Class<?>> p1) throws SQLException {
        return out().getObject(column(i), p1);
    }

    @Override
    public Ref getRef(int i) throws SQLException {
        throw Errors.unsupported("getRef");
    }

    @Override
    public @Nullable Blob getBlob(int i) throws SQLException {
        return out().getBlob(column(i));
    }

    @Override
    public @Nullable Clob getClob(int i) throws SQLException {
        return out().getClob(column(i));
    }

    @Override
    public @Nullable Array getArray(int i) throws SQLException {
        return out().getArray(column(i));
    }

    @Override
    public @Nullable Date getDate(int i, @Nullable Calendar p1) throws SQLException {
        return out().getDate(column(i), p1);
    }

    @Override
    public @Nullable Time getTime(int i, @Nullable Calendar p1) throws SQLException {
        return out().getTime(column(i), p1);
    }

    @Override
    public @Nullable Timestamp getTimestamp(int i, @Nullable Calendar p1) throws SQLException {
        return out().getTimestamp(column(i), p1);
    }

    @Override
    public URL getURL(int i) throws SQLException {
        throw Errors.unsupported("getURL");
    }

    @Override
    public RowId getRowId(int i) throws SQLException {
        throw Errors.unsupported("getRowId");
    }

    @Override
    public @Nullable NClob getNClob(int i) throws SQLException {
        return out().getNClob(column(i));
    }

    @Override
    public @Nullable SQLXML getSQLXML(int i) throws SQLException {
        return out().getSQLXML(column(i));
    }

    @Override
    public @Nullable String getNString(int i) throws SQLException {
        return out().getNString(column(i));
    }

    @Override
    public @Nullable Reader getNCharacterStream(int i) throws SQLException {
        return out().getNCharacterStream(column(i));
    }

    @Override
    public @Nullable Reader getCharacterStream(int i) throws SQLException {
        return out().getCharacterStream(column(i));
    }

    @Override
    public <T> @Nullable T getObject(int i, @Nullable Class<T> p1) throws SQLException {
        return out().getObject(column(i), p1);
    }
}
