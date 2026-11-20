package com.turborel.jdbc;

import java.sql.SQLException;
import java.util.Locale;

/**
 * JDBC's escape syntax ([DESIGN] D2), translated to SQL before a statement is sent: {@code {d
 * '...'}}, {@code {t '...'}} and {@code {ts '...'}} as DATE, TIME and TIMESTAMP literals; {@code
 * {call ...}} as CALL; {@code {fn ...}} as the function itself; {@code {oj ...}} as the outer join
 * itself; {@code {escape '...'}} as ESCAPE. Braces within a literal, a delimited identifier or a
 * comment are not escapes.
 */
final class Escapes {
    /** A quote written twice, within a quoted text. */
    private static final int QUOTE_TWICE = 2;

    private Escapes() {}

    /**
     * {@code sql} with its escapes translated.
     *
     * @param sql the statement
     * @return the statement as SQL writes it
     * @throws SQLException SQLSTATE TB910 of an escape not closed, or of a kind JDBC has not
     */
    static String translate(String sql) throws SQLException {
        if (sql.indexOf('{') < 0) {
            return sql;
        }
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                int end = closing(sql, i, c);
                out.append(sql, i, end);
                i = end;
            } else if (sql.startsWith("--", i)) {
                int end = sql.indexOf('\n', i);
                end = end < 0 ? sql.length() : end;
                out.append(sql, i, end);
                i = end;
            } else if (c == '{') {
                int end = matching(sql, i);
                out.append(escape(translate(sql.substring(i + 1, end)).trim()));
                i = end + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Where the quoted text beginning at {@code at} ends, past its closing quote; a quote twice is in it. */
    private static int closing(String sql, int at, char quote) {
        int i = at + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += QUOTE_TWICE;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return sql.length();
    }

    /** Where the escape beginning at {@code at} closes. */
    private static int matching(String sql, int at) throws SQLException {
        int depth = 0;
        int i = at;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                i = closing(sql, i, c);
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
            i++;
        }
        throw Errors.misuse("an escape of JDBC is not closed: " + sql.substring(at));
    }

    private static String escape(String body) throws SQLException {
        int space = body.indexOf(' ');
        String keyword = (space < 0 ? body : body.substring(0, space)).toLowerCase(Locale.ROOT);
        String rest = space < 0 ? "" : body.substring(space + 1).trim();
        return switch (keyword) {
            case "d" -> "DATE " + rest;
            case "t" -> "TIME " + rest;
            case "ts" -> "TIMESTAMP " + rest;
            case "call" -> "CALL " + rest;
            case "fn", "oj" -> rest;
            case "escape" -> "ESCAPE " + rest;
            default -> throw Errors.misuse("{" + keyword + "} is no escape of JDBC");
        };
    }
}
