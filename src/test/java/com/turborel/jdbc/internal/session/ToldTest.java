package com.turborel.jdbc.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.internal.codec.Attribute;
import com.turborel.jdbc.internal.codec.Completed;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the server told a connection ([CLIENT What the server tells]). */
class ToldTest {
    /**
     * Verifies: [PROTOCOL Complete 'K'] the last commit's position is kept until another comes,
     * a Complete without one leaving it; the last Complete's fields are kept.
     */
    @Test
    void theLastCommitsPositionIsKept() {
        Told told = new Told();
        assertNull(told.position());
        assertNull(told.completed());
        Completed.Fields committed = new Completed.Fields(42L, false, 1L, List.of(), List.of(), null);
        told.completed(committed);
        assertEquals(42L, told.position());
        told.completed(Completed.Fields.EMPTY);
        assertEquals(42L, told.position(), "a Complete without a position leaves it");
        assertEquals(Completed.Fields.EMPTY, told.completed());
    }

    /**
     * Verifies: [PROTOCOL Capabilities] query attributes go with a Query or an Execute only when
     * query-attributes is granted.
     */
    @Test
    void queryAttributesOnlyWhenGranted() {
        Told told = new Told();
        List<Attribute> set = List.of(new Attribute("trace", "t1"));
        told.attributes(set);
        assertTrue(told.attributes().isEmpty());
        told.grant(List.of(Capability.QUERY_ATTRIBUTES), 0);
        assertEquals(set, told.attributes());
        assertNull(told.compress());
    }
}
