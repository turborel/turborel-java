package com.turborel.jdbc;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Calls of every method of a JDBC interface, for the tests that check each one answers, or refuses
 * with the driver's failure, and no other exception (RULES.md R-ERR-1).
 */
final class Reflection {
    /** The argument given each parameter of a type: a number, a truth, a pattern; none of another. */
    private static final Map<Class<?>, Object> DEFAULTS = Map.of(
            int.class,
            1,
            long.class,
            1L,
            boolean.class,
            true,
            double.class,
            1.0,
            float.class,
            1.0f,
            short.class,
            (short) 1,
            byte.class,
            (byte) 1,
            String.class,
            "%");

    private Reflection() {}

    /**
     * Arguments of {@code m}, each its parameter's type's default.
     *
     * @param m the method
     * @return the arguments
     */
    static @Nullable Object[] arguments(Method m) {
        @Nullable Object[] args = new Object[m.getParameterCount()];
        for (int i = 0; i < args.length; i++) {
            args[i] = DEFAULTS.get(m.getParameterTypes()[i]);
        }
        return args;
    }

    /**
     * Invokes {@code m}, a failure of the driver's taken as the method's answer.
     *
     * @param m the method
     * @param target what it is invoked on
     * @param args its arguments
     * @return what it gave, or its failure
     * @throws IllegalAccessException if it cannot be invoked
     */
    static @Nullable Object invoke(Method m, Object target, @Nullable Object[] args) throws IllegalAccessException {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            assertInstanceOf(java.sql.SQLException.class, e.getCause(), m.toString());
            return e.getCause();
        }
    }
}
