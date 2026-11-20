package com.turborel.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** JDBC's escape syntax, translated to SQL ([DESIGN] D2). */
class EscapesTest {
    /**
     * Verifies: [DESIGN] D2 JDBC's escapes are translated: dates, times and timestamps as
     * literals, a call as CALL, a function and an outer join as themselves, a LIKE's escape as
     * ESCAPE, nested ones too; braces in a literal, a delimited identifier or a comment, simple or
     * bracketed and nested, are no escape; SQL with no brace is as it is.
     */
    @Test
    void escapesAreTranslated() throws SQLException {
        assertEquals("VALUES 1", Escapes.translate("VALUES 1"));
        assertEquals(
                "VALUES DATE '2026-01-02', TIME '03:04:05', TIMESTAMP '2026-01-02 03:04:05'",
                Escapes.translate("VALUES {d '2026-01-02'}, {t '03:04:05'}, {ts '2026-01-02 03:04:05'}"));
        assertEquals("CALL P1(?, ?)", Escapes.translate("{call P1(?, ?)}"));
        assertEquals(
                "SELECT UPPER(N) FROM T WHERE N LIKE 'a\\_%' ESCAPE '\\'",
                Escapes.translate("SELECT {fn UPPER(N)} FROM T WHERE N LIKE 'a\\_%' {escape '\\'}"));
        assertEquals(
                "SELECT * FROM A LEFT JOIN B ON A.K = B.K",
                Escapes.translate("SELECT * FROM {oj A LEFT JOIN B ON A.K = B.K}"));
        assertEquals("VALUES UPPER(LOWER('x'))", Escapes.translate("VALUES {fn UPPER({fn LOWER('x')})}"));
        assertEquals("VALUES '{d}', \"{t}\" -- {ts}\n", Escapes.translate("VALUES '{d}', \"{t}\" -- {ts}\n"));
        assertEquals("VALUES 'it''s {x}'", Escapes.translate("VALUES 'it''s {x}'"));
        assertEquals("VALUES '{' -- {", Escapes.translate("VALUES '{' -- {"));
        assertEquals("VALUES '{", Escapes.translate("VALUES '{"));
        assertEquals(
                "VALUES /* {d} /* {t} */ {ts} */ DATE '2026-01-02'",
                Escapes.translate("VALUES /* {d} /* {t} */ {ts} */ {d '2026-01-02'}"));
        assertEquals("VALUES UPPER('a' /* } */)", Escapes.translate("VALUES {fn UPPER('a' /* } */)}"));
        assertEquals("VALUES 1 /* {d", Escapes.translate("VALUES 1 /* {d"));
    }

    /**
     * Verifies: [DESIGN] D2 an escape not closed, or of a kind JDBC has not, is the program's
     * misuse (SQLSTATE TB910).
     */
    @Test
    void malformedEscapesAreRefused() {
        assertEquals(
                "TB910",
                assertThrows(SQLException.class, () -> Escapes.translate("VALUES {d '2026-01-02'"))
                        .getSQLState());
        assertEquals(
                "TB910",
                assertThrows(SQLException.class, () -> Escapes.translate("{?= call F(?)}"))
                        .getSQLState());
        assertEquals(
                "TB910",
                assertThrows(SQLException.class, () -> Escapes.translate("{limit}"))
                        .getSQLState());
    }
}
