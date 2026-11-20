package com.turborel.jdbc;

import java.sql.SQLException;
import java.sql.Savepoint;
import org.jspecify.annotations.Nullable;

/**
 * A savepoint ([DESIGN] D2): one the program names, or one of the driver's, named JDBC_SAVEPOINT_
 * and its number.
 */
final class JdbcSavepoint implements Savepoint {
    /** What names the driver's savepoints, then each one's number. */
    static final String PREFIX = "JDBC_SAVEPOINT_";

    private final int id;
    private final @Nullable String name;

    JdbcSavepoint(int id, @Nullable String name) {
        this.id = id;
        this.name = name;
    }

    /** Its name in SQL: the program's, delimited, or the driver's. */
    String sqlName() {
        String given = name;
        return given == null ? PREFIX + id : '"' + given.replace("\"", "\"\"") + '"';
    }

    @Override
    public int getSavepointId() throws SQLException {
        if (name != null) {
            throw Errors.misuse("a named savepoint has no ID");
        }
        return id;
    }

    @Override
    public String getSavepointName() throws SQLException {
        String given = name;
        if (given == null) {
            throw Errors.misuse("an unnamed savepoint has no name");
        }
        return given;
    }
}
