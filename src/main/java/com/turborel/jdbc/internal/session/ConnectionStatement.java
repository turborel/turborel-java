package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A statement of an SQL-agent's connections ([CLIENT The SQL-agent]; ISO/IEC 9075:1992 §15), as
 * Turborel's parser reads one ({@code src/parser/connections.rs}):
 *
 * <pre>
 * &lt;connect statement&gt; ::= CONNECT TO &lt;connection target&gt;
 * &lt;connection target&gt; ::= &lt;SQL-server name&gt; [ AS &lt;connection name&gt; ] [ USER &lt;user name&gt; ] | DEFAULT
 * &lt;set connection statement&gt; ::= SET CONNECTION &lt;connection object&gt;
 * &lt;connection object&gt; ::= DEFAULT | &lt;connection name&gt;
 * &lt;disconnect statement&gt; ::= DISCONNECT &lt;disconnect object&gt;
 * &lt;disconnect object&gt; ::= &lt;connection object&gt; | ALL | CURRENT
 * </pre>
 *
 * <p>Each name is a {@code <simple value specification>}: a literal, or a dynamic parameter, which a
 * client's connection statement has no value for. The statement is read whole, a semicolon allowed
 * at its end.
 */
sealed interface ConnectionStatement {
    /** The SQLCODE of a lexical error. */
    int LEXICAL = -101;

    /** The SQLCODE of a syntax error. */
    int SYNTAX = -102;

    /** The SQLCODE of a syntax rule broken. */
    int SYNTAX_RULE = -103;

    /** The SQLCODE of a feature not supported. */
    int UNSUPPORTED = -900;

    /** The SQLSTATE of a syntax error or access rule violation in a direct SQL statement. */
    String SYNTAX_ERROR = "42000";

    /** The SQLSTATE of a feature not supported. */
    String NOT_SUPPORTED = "0A000";

    /** A {@code <simple value specification>}: a character string literal, another literal, or a dynamic parameter. */
    sealed interface Value {
        /**
         * A character string literal.
         *
         * @param text its characters
         */
        record Text(String text) implements Value {}

        /**
         * A literal of another kind.
         *
         * @param written as it is written
         */
        record Other(String written) implements Value {}

        /** A dynamic parameter, {@code ?}. */
        record Parameter() implements Value {}
    }

    /**
     * CONNECT TO a server, AS a name and USER a user if written.
     *
     * @param server the {@code <SQL-server name>}
     * @param name the {@code <connection name>}, or none
     * @param user the {@code <user name>}, or none
     */
    record Connect(
            Value server, @Nullable Value name, @Nullable Value user) implements ConnectionStatement {}

    /** CONNECT TO DEFAULT. */
    record ConnectDefault() implements ConnectionStatement {}

    /**
     * SET CONNECTION a name, or DEFAULT for none.
     *
     * @param name the name, or none
     */
    record Set(@Nullable Value name) implements ConnectionStatement {}

    /**
     * DISCONNECT an object: a name, DEFAULT, ALL or CURRENT.
     *
     * @param object which
     * @param name the name, of {@link Disconnected#NAMED}
     */
    record Disconnect(Disconnected object, @Nullable Value name) implements ConnectionStatement {}

    /** A {@code <disconnect object>}. */
    enum Disconnected {
        NAMED,
        DEFAULT,
        ALL,
        CURRENT
    }

    /**
     * The connection statement {@code sql} is, if it begins as one: CONNECT, DISCONNECT, or SET
     * CONNECTION; none for another statement.
     *
     * @param sql the statement
     * @return the connection statement, or none
     * @throws TurborelException it begins as one and is not one: a lexical error (SQLCODE -101), a
     *     syntax error (-102), SQLSTATE 42000; an embedded variable, SQLSTATE 0A000
     */
    static @Nullable ConnectionStatement of(String sql) throws TurborelException {
        Lexer lexer = new Lexer(sql);
        String first = lexer.word();
        String second = "SET".equals(first) ? lexer.word() : null;
        boolean connection = "CONNECT".equals(first) || "DISCONNECT".equals(first) || "CONNECTION".equals(second);
        if (!connection) {
            return null;
        }
        return new Parser(new Lexer(sql).tokens()).statement();
    }

    /** A token: a word in upper case, a character string literal, another literal, or a delimiter. */
    record Token(Kind kind, String text) {}

    /** The kinds of token. */
    enum Kind {
        WORD,
        TEXT,
        LITERAL,
        DELIMITER
    }

    /** The lexer of a connection statement. */
    final class Lexer {
        /** A quote written twice, in a delimited identifier. */
        private static final int PAIR = 2;

        /** What begins a comment to the end of its line, and what begins and ends a bracketed one. */
        private static final String LINE_COMMENT = "--";

        private static final String COMMENT_OPEN = "/*";

        private static final String COMMENT_CLOSE = "*/";

        private final String sql;
        private int at;

        Lexer(String sql) {
            this.sql = sql;
        }

        /** The next word, in upper case, past separators; none if the next token is no word or the text cannot be read so far. */
        @Nullable
        String word() {
            try {
                skip();
            } catch (TurborelException unclosed) {
                return null;
            }
            if (at >= sql.length() || !Character.isLetter(sql.codePointAt(at))) {
                return null;
            }
            return readWord().text();
        }

        /** Every token, read whole. */
        List<Token> tokens() throws TurborelException {
            List<Token> tokens = new ArrayList<>();
            while (true) {
                skip();
                if (at >= sql.length()) {
                    return tokens;
                }
                tokens.add(token());
            }
        }

        private Token token() throws TurborelException {
            int c = sql.codePointAt(at);
            if (c == '\'') {
                return new Token(Kind.TEXT, text());
            }
            if (Character.isLetter(c)) {
                int start = at;
                Token word = readWord();
                if (at < sql.length() && sql.charAt(at) == '\'') {
                    // An introducer: N'...', X'...', B'...'.
                    text();
                    return new Token(Kind.LITERAL, sql.substring(start, at));
                }
                return word;
            }
            if (c == '"') {
                return delimitedIdentifier();
            }
            if ((c >= '0' && c <= '9') || c == '.') {
                return number();
            }
            at += Character.charCount(c);
            return new Token(Kind.DELIMITER, Character.toString(c));
        }

        /** A delimited identifier, as written: a word, which no connection statement takes where a name stands. */
        private Token delimitedIdentifier() throws TurborelException {
            int start = at;
            int close = sql.indexOf('"', at + 1);
            while (close >= 0 && close + 1 < sql.length() && sql.charAt(close + 1) == '"') {
                close = sql.indexOf('"', close + PAIR);
            }
            if (close < 0) {
                throw lexical("a delimited identifier is not closed");
            }
            at = close + 1;
            return new Token(Kind.WORD, sql.substring(start, at));
        }

        /** A numeric literal, as written. */
        private Token number() {
            int start = at;
            while (at < sql.length() && (Character.isLetterOrDigit(sql.charAt(at)) || sql.charAt(at) == '.')) {
                at++;
            }
            return new Token(Kind.LITERAL, sql.substring(start, at));
        }

        private Token readWord() {
            int start = at;
            while (at < sql.length()) {
                int c = sql.codePointAt(at);
                if (!Character.isLetterOrDigit(c) && c != '_') {
                    break;
                }
                at += Character.charCount(c);
            }
            return new Token(Kind.WORD, sql.substring(start, at).toUpperCase(Locale.ROOT));
        }

        /**
         * A character string literal's characters, a quote in it written twice, its parts separated
         * by a separator that holds a new line one literal (ISO/IEC 9075:1992 §5.3 SR1, SR4).
         */
        private String text() throws TurborelException {
            StringBuilder body = new StringBuilder();
            while (true) {
                at++;
                while (true) {
                    if (at >= sql.length()) {
                        throw lexical("a character string literal is not closed");
                    }
                    char c = sql.charAt(at++);
                    if (c == '\'') {
                        if (at < sql.length() && sql.charAt(at) == '\'') {
                            body.append('\'');
                            at++;
                            continue;
                        }
                        break;
                    }
                    body.append(c);
                }
                int end = at;
                boolean newLine = skip();
                if (!newLine || at >= sql.length() || sql.charAt(at) != '\'') {
                    at = end;
                    return body.toString();
                }
            }
        }

        /** Passes white space and comments; whether a new line was among them. */
        private boolean skip() throws TurborelException {
            boolean newLine = false;
            while (at < sql.length()) {
                char c = sql.charAt(at);
                if (c == '\n') {
                    newLine = true;
                    at++;
                } else if (Character.isWhitespace(c)) {
                    at++;
                } else if (sql.startsWith(LINE_COMMENT, at)) {
                    int end = sql.indexOf('\n', at);
                    at = end < 0 ? sql.length() : end;
                } else if (sql.startsWith(COMMENT_OPEN, at)) {
                    int end = sql.indexOf(COMMENT_CLOSE, at + COMMENT_OPEN.length());
                    if (end < 0) {
                        throw lexical("a comment is not closed");
                    }
                    at = end + COMMENT_CLOSE.length();
                } else {
                    break;
                }
            }
            return newLine;
        }

        private static TurborelException lexical(String why) {
            return new TurborelException(LEXICAL, SYNTAX_ERROR, "[CLIENT The SQL-agent] " + why);
        }
    }

    /** The parser of a connection statement's tokens. */
    final class Parser {
        private final List<Token> tokens;
        private int at;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        ConnectionStatement statement() throws TurborelException {
            ConnectionStatement statement;
            if (accept("CONNECT")) {
                expect("TO");
                if (accept("DEFAULT")) {
                    statement = new ConnectDefault();
                } else {
                    Value server = value();
                    Value name = accept("AS") ? value() : null;
                    Value user = accept("USER") ? value() : null;
                    statement = new Connect(server, name, user);
                }
            } else if (accept("SET")) {
                expect("CONNECTION");
                statement = new Set(accept("DEFAULT") ? null : value());
            } else {
                expect("DISCONNECT");
                statement = disconnect();
            }
            if (at < tokens.size() && tokens.get(at).equals(new Token(Kind.DELIMITER, ";"))) {
                at++;
            }
            if (at < tokens.size()) {
                throw syntax("expected end of statement, not " + tokens.get(at).text());
            }
            return statement;
        }

        private ConnectionStatement disconnect() throws TurborelException {
            if (accept("DEFAULT")) {
                return new Disconnect(Disconnected.DEFAULT, null);
            }
            if (accept("ALL")) {
                return new Disconnect(Disconnected.ALL, null);
            }
            if (accept("CURRENT")) {
                return new Disconnect(Disconnected.CURRENT, null);
            }
            return new Disconnect(Disconnected.NAMED, value());
        }

        private Value value() throws TurborelException {
            if (at >= tokens.size()) {
                throw syntax("expected a <simple value specification>, not the end of the statement");
            }
            Token token = tokens.get(at++);
            if (token.kind() == Kind.TEXT) {
                return new Value.Text(token.text());
            }
            if (token.kind() == Kind.LITERAL) {
                return new Value.Other(token.text());
            }
            if (token.text().equals("?")) {
                return new Value.Parameter();
            }
            if (token.text().equals(":")) {
                throw new TurborelException(
                        UNSUPPORTED,
                        NOT_SUPPORTED,
                        "[CLIENT The SQL-agent] a <variable specification> (embedded SQL) is not supported");
            }
            throw syntax("expected a <simple value specification>, not " + token.text());
        }

        private boolean accept(String word) {
            if (at < tokens.size() && tokens.get(at).equals(new Token(Kind.WORD, word))) {
                at++;
                return true;
            }
            return false;
        }

        private void expect(String word) throws TurborelException {
            if (!accept(word)) {
                String found = at < tokens.size() ? tokens.get(at).text() : "the end of the statement";
                throw syntax("expected " + word + ", not " + found);
            }
        }

        private static TurborelException syntax(String why) {
            return new TurborelException(SYNTAX, SYNTAX_ERROR, "[CLIENT The SQL-agent] " + why);
        }
    }
}
