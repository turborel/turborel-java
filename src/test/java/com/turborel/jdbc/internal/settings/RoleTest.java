package com.turborel.jdbc.internal.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Completed;
import com.turborel.jdbc.internal.codec.Value;
import com.turborel.jdbc.internal.session.Outcome;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Whether a server takes changes ([CLIENT Several servers]). */
class RoleTest {
    private static Outcome shown(String role) {
        return new Outcome.Rows(List.of(), List.of(List.of(new Value.Text(role))));
    }

    /**
     * Verifies: [CLIENT Several servers] a server alone, which has no REPLICAS (class 2A or 42),
     * takes changes; of a replica, PRIMARY is read-write, BACKUP or UNKNOWN read-only; no row, as a
     * session not the owner's sees, is unknown; another failure is the query's; the session status
     * YES is read-only, another read-write.
     */
    @Test
    void aServersRoleIsReadFromReplicas() throws TurborelException {
        assertEquals(Role.READ_WRITE, Role.failed(new TurborelException(-101, "42000", "no table")));
        assertEquals(Role.READ_WRITE, Role.failed(new TurborelException(-101, "2A000", "no table")));
        assertEquals(Role.READ_WRITE, Role.shown(shown("PRIMARY")));
        assertEquals(Role.READ_ONLY, Role.shown(shown("BACKUP ")));
        assertEquals(Role.READ_ONLY, Role.shown(shown("UNKNOWN")));
        assertEquals(Role.READ_ONLY, Role.shown(new Outcome.Rows(List.of(), List.of(List.of()))));
        assertEquals(Role.UNKNOWN, Role.shown(new Outcome.Rows(List.of(), List.of())));
        assertEquals(
                Role.UNKNOWN, Role.shown(new Outcome.Done(new Completed(0, "00000", false, Completed.Fields.EMPTY))));
        TurborelException lost = new TurborelException(-919, "08006", "lost");
        assertSame(lost, assertThrows(TurborelException.class, () -> Role.failed(lost)));
        assertEquals("of a role this session cannot see", Role.UNKNOWN.describe());
        assertEquals(Role.READ_ONLY, Role.told("YES"));
        assertEquals(Role.READ_WRITE, Role.told("NO"));
    }
}
