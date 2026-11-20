package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Failures;
import org.jspecify.annotations.Nullable;

/**
 * What an import is to do ([CLIENT Importing]): the table its rows go into, how many rows are
 * committed at a time, and whether a row refused is skipped and reported rather than ending it. As
 * Turborel's {@code client/src/import/insert.rs}.
 *
 * @param table the table's name, as SQL writes it
 * @param batch how many rows are committed at a time; 0 for all at once
 * @param skip whether a row refused, by the server, as a string too long for its column, or of too
 *     many or too few fields, is skipped and reported
 */
public record Import(String table, long batch, boolean skip) {
    /** How many rows an import commits at a time, unless it is told. */
    public static final long DEFAULT_BATCH = 1000;

    /**
     * An import into {@code table}, {@link #DEFAULT_BATCH} rows at a time, stopping at the first
     * row that fails.
     *
     * @param table the table's name, as SQL writes it
     * @return the import
     */
    public static Import into(String table) {
        return new Import(table, DEFAULT_BATCH, false);
    }

    /**
     * What an import did.
     *
     * @param rows how many rows were inserted and committed
     * @param rejected how many were skipped, each reported
     */
    public record Imported(long rows, long rejected) {}

    /**
     * Why a row, or an import, failed: the source's, as a record that does not fit the columns, or
     * an SQL condition, the server's or of a string too long for its column.
     *
     * @param input what is wrong with the source, if it is the source's
     * @param sql the SQL condition, without its position in a statement the import made, if it is one
     */
    public record Cause(@Nullable String input, @Nullable TurborelException sql) {
        static Cause input(String message) {
            return new Cause(message, null);
        }

        static Cause sql(TurborelException failure) {
            TurborelException.Details details = new TurborelException.Details(
                    failure.rule(), null, failure.transactionActive(), failure.diagnostics());
            return new Cause(
                    null,
                    new TurborelException(
                            failure.sqlcode(), failure.getSQLState(), String.valueOf(failure.getMessage()), details));
        }

        @Override
        public String toString() {
            TurborelException condition = sql;
            return condition == null ? String.valueOf(input) : String.valueOf(condition.getMessage());
        }
    }

    /**
     * A row skipped: where it began, and why.
     *
     * @param position where its record began
     * @param cause why it failed
     */
    public record Rejected(Source.Position position, Cause cause) {
        @Override
        public String toString() {
            return position + ": " + cause + "; skipped";
        }
    }

    /**
     * Why an import stopped, and what of it was kept: an SQL condition's codes, or input that cannot
     * be read (SQLCODE -301, SQLSTATE 22000).
     */
    public static final class Stopped extends TurborelException {
        private static final long serialVersionUID = 1L;

        private final transient Source.@Nullable Position position;
        private final transient Cause cause;
        private final long committed;
        private final long rolledBack;

        Stopped(Source.@Nullable Position position, Cause cause, long committed, long rolledBack) {
            super(
                    sqlcode(cause),
                    sqlstate(cause),
                    (position == null ? "" : position + ": ") + cause + "; " + rows(committed) + " committed, "
                            + rolledBack + " rolled back");
            this.position = position;
            this.cause = cause;
            this.committed = committed;
            this.rolledBack = rolledBack;
        }

        private static int sqlcode(Cause cause) {
            TurborelException sql = cause.sql();
            return sql == null ? Failures.DATA_SQLCODE : sql.sqlcode();
        }

        private static String sqlstate(Cause cause) {
            TurborelException sql = cause.sql();
            return sql == null ? Source.Failed.DATA_EXCEPTION : sql.getSQLState();
        }

        /**
         * Where the record that stopped it began, if one did.
         *
         * @return the position, or none
         */
        public Source.@Nullable Position where() {
            return position;
        }

        /**
         * Why it stopped.
         *
         * @return the cause
         */
        public Cause cause() {
            return cause;
        }

        /**
         * How many rows were committed before.
         *
         * @return the rows
         */
        public long committed() {
            return committed;
        }

        /**
         * How many of the batch under way were rolled back.
         *
         * @return the rows
         */
        public long rolledBack() {
            return rolledBack;
        }
    }

    /**
     * Rows in words: {@code 1 row}, {@code 2 rows}.
     *
     * @param n how many
     * @return the words
     */
    public static String rows(long n) {
        return n + (n == 1 ? " row" : " rows");
    }
}
