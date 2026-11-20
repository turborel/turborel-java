package com.turborel.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** A failure's SQLCODE, SQLSTATE and what the server added (RULES.md D9). */
class TurborelExceptionTest {
    /**
     * Verifies: [PROTOCOL Error 'E'] a failure the client raised carries no rule, position or
     * diagnostics, and says no transaction is active; one of the server's carries what it sent.
     */
    @Test
    void detailsAreTheServers() {
        TurborelException client = new TurborelException(-910, "TB910", "misuse");
        assertEquals(-910, client.sqlcode());
        assertEquals("TB910", client.getSQLState());
        assertNull(client.rule());
        assertEquals(OptionalLong.empty(), client.position());
        assertFalse(client.transactionActive());
        assertEquals(Map.of(), client.diagnostics());

        TurborelException unplaced = new TurborelException(
                -103, "42000", "syntax", new TurborelException.Details("§5.2", null, false, Map.of()));
        assertEquals("§5.2", unplaced.rule());
        assertEquals(OptionalLong.empty(), unplaced.position());
        assertFalse(unplaced.transactionActive());

        TurborelException placed = new TurborelException(
                -103, "42000", "syntax", new TurborelException.Details(null, 7L, true, Map.of("SEVERITY", "ERROR")));
        assertNull(placed.rule());
        assertEquals(OptionalLong.of(7), placed.position());
        assertTrue(placed.transactionActive());
        assertEquals(Map.of("SEVERITY", "ERROR"), placed.diagnostics());
    }

    /**
     * Verifies: [DESIGN] D9 a failure serialized keeps its SQLCODE, SQLSTATE and message,
     * but not the rest the server sent, which is transient.
     */
    @Test
    void aSerializedFailureKeepsItsCodes() throws java.io.IOException, ClassNotFoundException {
        TurborelException sent = new TurborelException(
                -103, "42000", "syntax", new TurborelException.Details("§5.2", 7L, true, Map.of("SEVERITY", "ERROR")));
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
            out.writeObject(sent);
        }
        TurborelException read;
        try (java.io.ObjectInputStream in =
                new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            read = (TurborelException) in.readObject();
        }
        assertEquals(-103, read.sqlcode());
        assertEquals("42000", read.getSQLState());
        assertEquals("syntax", read.getMessage());
        assertNull(read.rule());
        assertEquals(OptionalLong.empty(), read.position());
        assertFalse(read.transactionActive());
        assertEquals(Map.of(), read.diagnostics());
    }
}
