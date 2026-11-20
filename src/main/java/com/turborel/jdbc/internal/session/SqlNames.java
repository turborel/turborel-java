package com.turborel.jdbc.internal.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Names of SQL as Turborel's lexer reads them ({@code src/lexer.rs}): a regular identifier, a
 * letter then letters, ASCII digits and underscores, in upper case, at most 128 letters and
 * digits, and never one of SQL-92's reserved words, which are key words; a delimited identifier,
 * in double quotes, a quote in it written twice, keeping its case. So an import names its table and
 * columns as the server would read them.
 */
final class SqlNames {
    /** The most letters and digits of an identifier. */
    private static final int MAX_IDENTIFIER = 128;

    /** The most parts of a table's name: catalog, schema and table. */
    private static final int MAX_NAME_PARTS = 3;

    private static final char DOUBLE_QUOTE = '"';

    /** What a delimited identifier's end is of one the lexer refuses. */
    private static final int REFUSED = -1;

    /** SQL-92's reserved words, the key words of Turborel's lexer. */
    private static final Set<String> KEY_WORDS = Set.of(("ABSOLUTE ACTION ADD ALL ALLOCATE ALTER AND ANY ARE AS ASC "
                    + "ASSERTION AT AUTHORIZATION AVG BEGIN BETWEEN BIT BIT_LENGTH BOTH BY CASCADE CASCADED CASE CAST "
                    + "CATALOG CHAR CHARACTER CHAR_LENGTH CHARACTER_LENGTH CHECK CLOSE COALESCE COLLATE COLLATION COLUMN "
                    + "COMMIT CONNECT CONNECTION CONSTRAINT CONSTRAINTS CONTINUE CONVERT CORRESPONDING COUNT CREATE CROSS "
                    + "CURRENT CURRENT_DATE CURRENT_TIME CURRENT_TIMESTAMP CURRENT_USER CURSOR DATE DAY DEALLOCATE DEC "
                    + "DECIMAL DECLARE DEFAULT DEFERRABLE DEFERRED DELETE DESC DESCRIBE DESCRIPTOR DIAGNOSTICS DISCONNECT "
                    + "DISTINCT DOMAIN DOUBLE DROP ELSE END ESCAPE EXCEPT EXCEPTION EXEC EXECUTE EXISTS EXTERNAL EXTRACT "
                    + "FALSE FETCH FIRST FLOAT FOR FOREIGN FOUND FROM FULL GET GLOBAL GO GOTO GRANT GROUP HAVING HOUR "
                    + "IDENTITY IMMEDIATE IN INDICATOR INITIALLY INNER INPUT INSENSITIVE INSERT INT INTEGER INTERSECT "
                    + "INTERVAL INTO IS ISOLATION JOIN KEY LANGUAGE LAST LEADING LEFT LEVEL LIKE LOCAL LOWER MATCH MAX MIN "
                    + "MINUTE MODULE MONTH NAMES NATIONAL NATURAL NCHAR NEXT NO NOT NULL NULLIF NUMERIC OCTET_LENGTH OF ON "
                    + "ONLY OPEN OPTION OR ORDER OUTER OUTPUT OVERLAPS PAD PARTIAL POSITION PRECISION PREPARE PRESERVE "
                    + "PRIMARY PRIOR PRIVILEGES PROCEDURE PUBLIC READ REAL REFERENCES RELATIVE RESTRICT REVOKE RIGHT "
                    + "ROLLBACK ROWS SCHEMA SCROLL SECOND SECTION SELECT SESSION SESSION_USER SET SIZE SMALLINT SOME SPACE "
                    + "SQL SQLCODE SQLERROR SQLSTATE SUBSTRING SUM SYSTEM_USER TABLE TEMPORARY THEN TIME TIMESTAMP "
                    + "TIMEZONE_HOUR TIMEZONE_MINUTE TO TRAILING TRANSACTION TRANSLATE TRANSLATION TRIM TRUE UNION UNIQUE "
                    + "UNKNOWN UPDATE UPPER USAGE USER USING VALUE VALUES VARCHAR VARYING VIEW WHEN WHENEVER WHERE WITH WORK "
                    + "WRITE YEAR ZONE")
            .split(" "));

    private SqlNames() {}

    /** A token of a name: an identifier, a key word, a period, or anything else. */
    private record Token(Kind kind, String text) {}

    private enum Kind {
        IDENTIFIER,
        KEY_WORD,
        PERIOD,
        OTHER
    }

    /**
     * A name as SQL text of delimited identifiers, a quote in it written twice.
     *
     * @param name the name
     * @return the delimited identifier
     */
    static String delimited(String name) {
        return DOUBLE_QUOTE + name.replace("\"", "\"\"") + DOUBLE_QUOTE;
    }

    /**
     * A table's name, as SQL writes it, as delimited identifiers, so nothing else is taken for one.
     *
     * @param text the name, as {@code SCHEMA.TABLE}
     * @return its parts, delimited, joined by periods; none if it is not a table's name
     */
    static @Nullable String tableName(String text) {
        List<Token> tokens = lex(text);
        if (tokens == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        int at = 0;
        while (true) {
            if (at >= tokens.size() || tokens.get(at).kind() != Kind.IDENTIFIER) {
                return null;
            }
            parts.add(delimited(tokens.get(at++).text()));
            if (at == tokens.size()) {
                return String.join(".", parts);
            }
            if (tokens.get(at++).kind() != Kind.PERIOD || parts.size() == MAX_NAME_PARTS) {
                return null;
            }
        }
    }

    /**
     * The column a heading names: the identifier it is, if it is one, a regular one in upper case,
     * and so a key word, as it would be one but for being reserved; else its name as written.
     *
     * @param heading the heading's name
     * @return the column's name
     */
    static String columnName(String heading) {
        List<Token> tokens = lex(heading);
        if (tokens != null
                && tokens.size() == 1
                && tokens.get(0).kind() != Kind.PERIOD
                && tokens.get(0).kind() != Kind.OTHER) {
            return tokens.get(0).text();
        }
        return heading;
    }

    /** The tokens of a text; none if it holds what the lexer refuses. */
    private static @Nullable List<Token> lex(String text) {
        List<Token> tokens = new ArrayList<>();
        int at = 0;
        while (at < text.length()) {
            int c = text.codePointAt(at);
            if (c == ' ' || c == '\n' || c == '\r') {
                at++;
            } else if (Character.isAlphabetic(c)) {
                int end = regularEnd(text, at);
                Token word = word(text.substring(at, end));
                if (word == null) {
                    return null;
                }
                tokens.add(word);
                at = end;
            } else if (c == DOUBLE_QUOTE) {
                int end = delimitedEnd(text, at, tokens);
                if (end < 0) {
                    return null;
                }
                at = end;
            } else if (c == '.') {
                tokens.add(new Token(Kind.PERIOD, "."));
                at++;
            } else {
                tokens.add(new Token(Kind.OTHER, Character.toString(c)));
                at += Character.charCount(c);
            }
        }
        return tokens;
    }

    private static int regularEnd(String text, int start) {
        int at = start + Character.charCount(text.codePointAt(start));
        while (at < text.length()) {
            int c = text.codePointAt(at);
            if (!Character.isAlphabetic(c) && !(c >= '0' && c <= '9') && c != '_') {
                break;
            }
            at += Character.charCount(c);
        }
        return at;
    }

    private static @Nullable Token word(String written) {
        String word = written.toUpperCase(Locale.ROOT);
        if (KEY_WORDS.contains(word)) {
            return new Token(Kind.KEY_WORD, word);
        }
        long length = written.codePoints().filter(c -> c != '_').count();
        return length <= MAX_IDENTIFIER ? new Token(Kind.IDENTIFIER, word) : null;
    }

    /** Reads a delimited identifier into the tokens: where it ends, or -1 if it is refused. */
    private static int delimitedEnd(String text, int start, List<Token> tokens) {
        StringBuilder body = new StringBuilder();
        int at = start + 1;
        while (true) {
            if (at >= text.length() || text.charAt(at) == '\n' || text.charAt(at) == '\r') {
                return REFUSED;
            }
            char c = text.charAt(at++);
            if (c == DOUBLE_QUOTE) {
                if (at >= text.length() || text.charAt(at) != DOUBLE_QUOTE) {
                    break;
                }
                at++;
            }
            body.append(c);
        }
        if (body.length() == 0 || body.codePoints().count() > MAX_IDENTIFIER) {
            return REFUSED;
        }
        tokens.add(new Token(Kind.IDENTIFIER, body.toString()));
        return at;
    }
}
