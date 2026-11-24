package com.turborel.jdbc.internal.settings;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.internal.codec.ValueText;
import com.turborel.jdbc.internal.codec.WhiteSpace;
import com.turborel.jdbc.internal.session.Connection;
import com.turborel.jdbc.internal.session.Outcome;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a server is, to a connection's session ([CLIENT Several servers]): whether it takes
 * changes. A server alone does, and of a replicated group its primary. A replica following the
 * group's log refuses every change (SQLCODE -917). A replica shows its role in
 * INFORMATION_SCHEMA.REPLICAS, its own row {@code IS_SELF = 'YES'}, to the database's owner alone;
 * a server alone has no such table. A server that grants {@code status} tells every session
 * whether it takes changes, SERVER_READ_ONLY, so {@link #of} reads it with no query and sees a
 * replica's role whoever the session's user is; the query is the fallback. As Turborel's {@code
 * client/src/role.rs}.
 */
public enum Role {
    /** It takes changes. */
    READ_WRITE("read-write"),
    /** It takes none: a replica following its group. */
    READ_ONLY("read-only"),
    /** A replica whose role the session cannot see. */
    UNKNOWN("of a role this session cannot see");

    /** The query of a server's own role in its group. */
    public static final String ROLE_QUERY = "SELECT ROLE FROM INFORMATION_SCHEMA.REPLICAS WHERE IS_SELF = 'YES'";

    /** The session status of whether the server takes no changes. */
    public static final String SERVER_READ_ONLY = "SERVER_READ_ONLY";

    /** The status's word for a server that takes none. */
    private static final String YES = "YES";

    /** The role REPLICAS shows of the group's primary. */
    private static final String PRIMARY = "PRIMARY";

    /**
     * The SQLSTATE class of a table that does not exist, as a server alone answers {@link
     * #ROLE_QUERY}: a syntax error or access rule violation, of a statement run or prepared.
     */
    private static final String NO_SUCH_TABLE_CLASS = "42";

    private final String words;

    Role(String words) {
        this.words = words;
    }

    /**
     * It, in words.
     *
     * @return the words
     */
    public String describe() {
        return words;
    }

    /**
     * The role of {@code connection}'s server, as its session sees it: as its session status last
     * told it, if the server tells it; else asked as a statement that is its own transaction, so it
     * leaves none open.
     *
     * @param connection the connection
     * @return its server's role
     * @throws TurborelException a connection failure; the query's, but of a server alone
     */
    public static Role of(Connection connection) throws TurborelException {
        String told = connection.setting(SERVER_READ_ONLY);
        if (told != null) {
            return told(told);
        }
        boolean autocommit = connection.autocommit();
        connection.setAutocommit(true);
        try {
            return shown(connection.statements().query(ROLE_QUERY));
        } catch (TurborelException failure) {
            return failed(failure);
        } finally {
            connection.setAutocommit(autocommit);
        }
    }

    /**
     * The role a session status of SERVER_READ_ONLY of {@code told} shows.
     *
     * @param told the status's value
     * @return the role
     */
    static Role told(String told) {
        return YES.equals(told) ? READ_ONLY : READ_WRITE;
    }

    /**
     * The role {@code answer}, the outcome of {@link #ROLE_QUERY}, shows.
     *
     * @param answer the outcome
     * @return the role
     */
    static Role shown(Outcome answer) {
        List<List<Value>> rows = answer instanceof Outcome.Rows shown ? shown.rows() : List.of();
        if (rows.isEmpty()) {
            return UNKNOWN;
        }
        List<Value> row = rows.get(0);
        @Nullable Value first = row.isEmpty() ? null : row.get(0);
        boolean primary =
                first != null && WhiteSpace.trimEnd(ValueText.of(first)).equals(PRIMARY);
        return primary ? READ_WRITE : READ_ONLY;
    }

    /**
     * The role of a server whose answer to {@link #ROLE_QUERY} was {@code failure}.
     *
     * @param failure the query's failure
     * @return read-write, of a server alone, which has no REPLICAS
     * @throws TurborelException the failure, of another
     */
    static Role failed(TurborelException failure) throws TurborelException {
        if (String.valueOf(failure.getSQLState()).startsWith(NO_SUCH_TABLE_CLASS)) {
            return READ_WRITE;
        }
        throw failure;
    }
}
