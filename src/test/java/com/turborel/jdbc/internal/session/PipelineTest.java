package com.turborel.jdbc.internal.session;

import static com.turborel.jdbc.internal.session.Script.after;
import static com.turborel.jdbc.internal.session.Script.complete;
import static com.turborel.jdbc.internal.session.Script.error;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A pipeline over a stream the test scripts ([CLIENT Pipelines]). */
class PipelineTest {
    /**
     * Verifies: [CLIENT Pipelines] requests are grouped into windows of at most 64 KiB, in order, a
     * request longer than a window alone in one; none queued, no window.
     */
    @Test
    void requestsAreWrittenAWindowAtATime() throws TurborelException {
        String half = "x".repeat(Pipeline.WINDOW / 2);
        String whole = "y".repeat(Pipeline.WINDOW);
        assertEquals(List.of(3, 1, 1), Pipeline.windowSizes(List.of("a", "VALUES 1", half, whole, "z")));
        assertTrue(Pipeline.windowSizes(List.of()).isEmpty());
        assertEquals("TURBOREL_PIPELINE_3", Pipeline.savepoint(3));
    }

    /**
     * Verifies: [CLIENT Pipelines] run fails once the connection is lost (SQLSTATE 08006), what
     * was answered before not given; while a copy is under way a pipeline is the program's misuse
     * (SQLSTATE TB910), nothing sent.
     */
    @Test
    void runFailsOnceTheConnectionIsLost() throws TurborelException {
        try (Connection lost = after(List.of(), complete())) {
            TurborelException e = assertThrows(
                    TurborelException.class,
                    () -> lost.pipeline().query("COMMIT").query("COMMIT").run());
            assertEquals("08006", e.getSQLState());
            assertTrue(lost.isLost());
            assertEquals(
                    "08006",
                    assertThrows(
                                    TurborelException.class,
                                    () -> lost.pipeline().query("COMMIT").run())
                            .getSQLState());
        }
        try (Connection copying = after(List.of())) {
            copying.copying(true);
            assertEquals(
                    "TB910",
                    assertThrows(
                                    TurborelException.class,
                                    () -> copying.pipeline().query("COMMIT").run())
                            .getSQLState());
        }
    }

    private static String kinds(List<Pipeline.Piped> outcomes) {
        StringBuilder kinds = new StringBuilder();
        outcomes.forEach(o -> kinds.append(o.getClass().getSimpleName().charAt(0)));
        return kinds.toString();
    }

    /**
     * Verifies: [CLIENT Pipelines] under skip, a failure of the first request undoes those after
     * it to the savepoint set after it, then releases the first savepoint; a failure of the last
     * undoes nothing; a savepoint that fails is never released; a batch of one sets none; in a
     * transaction begun, the batch commits nothing.
     */
    @Test
    void skipSettlesAsTheFailureFalls() throws TurborelException {
        try (Connection first =
                after(List.of(), error(-103, "42000"), complete(), complete(), complete(), complete())) {
            List<Pipeline.Piped> outcomes = first.pipeline()
                    .onError(Pipeline.OnError.SKIP)
                    .query("A")
                    .query("B")
                    .run();
            assertEquals("FS", kinds(outcomes));
        }
        try (Connection last = after(List.of(), complete(), complete(), error(-103, "42000"), complete())) {
            assertEquals(
                    "DF",
                    kinds(last.pipeline()
                            .onError(Pipeline.OnError.SKIP)
                            .query("A")
                            .query("B")
                            .run()));
        }
        try (Connection savepoint = after(List.of(), complete(), error(-103, "42000"), complete())) {
            assertEquals(
                    "DS",
                    kinds(savepoint
                            .pipeline()
                            .onError(Pipeline.OnError.SKIP)
                            .query("A")
                            .query("B")
                            .run()));
        }
        try (Connection one = after(List.of(), complete(), complete())) {
            one.setAutocommit(true);
            assertEquals(
                    "D",
                    kinds(one.pipeline()
                            .onError(Pipeline.OnError.SKIP)
                            .query("A")
                            .run()));
        }
    }
}
