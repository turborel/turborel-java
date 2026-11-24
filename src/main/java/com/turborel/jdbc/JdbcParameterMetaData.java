package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.Called;
import com.turborel.jdbc.internal.codec.Parameter;
import com.turborel.jdbc.internal.codec.ParameterMode;
import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A prepared statement's parameters ([DESIGN] D2): each one's type as the server described it,
 * mapped as {@link JdbcTypes} maps it; each an IN parameter, as the protocol's parameters are.
 */
final class JdbcParameterMetaData implements ParameterMetaData {
    private final List<Parameter> parameters;
    private final List<ParameterMode> modes;

    /** The metadata of {@code parameters}, each IN, as of a statement other than a CALL told. */
    JdbcParameterMetaData(List<Parameter> parameters) {
        this(parameters, java.util.Collections.nCopies(parameters.size(), ParameterMode.IN));
    }

    /**
     * The metadata of a statement's {@code ?} marks: each its parameter and its mode, as a CALL's
     * are told, granted {@code parameter-modes} ([PROTOCOL Parameter modes]).
     */
    JdbcParameterMetaData(List<Parameter> parameters, List<ParameterMode> modes) {
        this.parameters = List.copyOf(parameters);
        this.modes = List.copyOf(modes);
    }

    /**
     * The metadata of {@code statement}'s {@code ?} marks: of a CALL told, each mark, its OUT ones
     * too, with its mode; else each input parameter, IN.
     */
    static JdbcParameterMetaData of(com.turborel.jdbc.internal.session.Statement statement) {
        Called called = statement.called();
        if (called == null) {
            return new JdbcParameterMetaData(statement.parameters());
        }
        return new JdbcParameterMetaData(
                called.marks().stream().map(Called.Mark::parameter).toList(),
                called.marks().stream().map(Called.Mark::mode).toList());
    }

    private Parameter parameter(int i) throws SQLException {
        if (i < 1 || i > parameters.size()) {
            throw Errors.misuse("parameter " + i + " is not of 1 to " + parameters.size());
        }
        return parameters.get(i - 1);
    }

    @Override
    public int getParameterCount() {
        return parameters.size();
    }

    @Override
    public int isNullable(int i) throws SQLException {
        parameter(i);
        return parameterNullableUnknown;
    }

    @Override
    public boolean isSigned(int i) throws SQLException {
        return JdbcTypes.signed(parameter(i).type());
    }

    @Override
    public int getPrecision(int i) throws SQLException {
        return JdbcTypes.precision(parameter(i).type());
    }

    @Override
    public int getScale(int i) throws SQLException {
        return JdbcTypes.scale(parameter(i).type());
    }

    @Override
    public int getParameterType(int i) throws SQLException {
        return JdbcTypes.of(parameter(i).type());
    }

    @Override
    public String getParameterTypeName(int i) throws SQLException {
        return JdbcTypes.name(parameter(i).type());
    }

    @Override
    public String getParameterClassName(int i) throws SQLException {
        return JdbcTypes.className(parameter(i).type());
    }

    @Override
    public int getParameterMode(int i) throws SQLException {
        parameter(i);
        return switch (modes.get(i - 1)) {
            case IN -> parameterModeIn;
            case INOUT -> parameterModeInOut;
            case OUT -> parameterModeOut;
        };
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
