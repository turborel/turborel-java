package com.turborel.jdbc.internal.session;

import static com.turborel.jdbc.internal.session.LiveStatementsTest.connected;
import static com.turborel.jdbc.internal.session.LiveStatementsTest.integer;
import static com.turborel.jdbc.internal.session.LiveStatementsTest.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.session.Pipeline.Piped;
import com.turborel.jdbc.testing.LiveServer;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Pipelines of requests on a live server ([CLIENT Pipelines]). */
@EnabledIfEnvironmentVariable(named = LiveServer.VARIABLE, matches = ".+")
class LivePipelineTest {
    private static long count(Connection c, String table) throws TurborelException {
        return Long.parseLong(com.turborel.jdbc.internal.codec.ValueText.of(
                rows(c.statements().query("SELECT COUNT(*) FROM " + table))
                        .get(0)
                        .get(0)));
    }

    /**
     * Verifies: [CLIENT Pipelines] queries and executions queued are written at once, one outcome
     * read for each, in order ([PROTOCOL Requests]); a failure is its request's own and the
     * requests after it run; under autocommit each request is its own transaction, so a failed
     * one undoes nothing before it; the pipeline is empty after it runs.
     */
    @Test
    void eachRequestHasItsOutcomeInOrder() throws TurborelException, IOException {
        try (Connection c = connected()) {
            c.setAutocommit(true);
            c.statements().query("CREATE TABLE PIPE_EACH (K INTEGER PRIMARY KEY)");
            Statement insert = c.statements().prepare("INSERT INTO PIPE_EACH VALUES (?)");
            Pipeline pipeline = c.pipeline()
                    .query("INSERT INTO PIPE_EACH VALUES (1)")
                    .execute(insert, List.of(integer(2)))
                    .execute(insert, List.of(integer(1)))
                    .query("SELECT K FROM PIPE_EACH ORDER BY K");
            assertEquals(4, pipeline.size());
            List<Piped> outcomes = pipeline.run();
            assertTrue(pipeline.isEmpty());
            assertEquals(4, outcomes.size());
            assertInstanceOf(Piped.Done.class, outcomes.get(0));
            assertInstanceOf(Piped.Done.class, outcomes.get(1));
            Piped.Failed failed = assertInstanceOf(Piped.Failed.class, outcomes.get(2));
            assertEquals("23000", failed.failure().getSQLState().substring(0, 2) + "000");
            Outcome selected = ((Piped.Done) outcomes.get(3)).outcome();
            assertEquals(List.of(List.of(integer(1)), List.of(integer(2))), rows(selected));
            assertFalse(c.inTransaction(), "each its own transaction");
            assertEquals(2, count(c, "PIPE_EACH"));
            c.terminate();
        }
    }

    /**
     * Verifies: [CLIENT Pipelines] skip on error: a savepoint before each request but the first,
     * those after a failure rolled back and skipped, the batch one transaction, committed as it
     * ends under autocommit where it began none; outside autocommit the transaction is left open,
     * the requests before the failure in it.
     */
    @Test
    void skipStopsAtTheFirstFailure() throws TurborelException, IOException {
        try (Connection c = connected()) {
            c.setAutocommit(true);
            c.statements().query("CREATE TABLE PIPE_SKIP (K INTEGER PRIMARY KEY)");
            List<Piped> outcomes = c.pipeline()
                    .onError(Pipeline.OnError.SKIP)
                    .query("INSERT INTO PIPE_SKIP VALUES (1)")
                    .query("INSERT INTO PIPE_SKIP VALUES (1)")
                    .query("INSERT INTO PIPE_SKIP VALUES (3)")
                    .run();
            assertInstanceOf(Piped.Done.class, outcomes.get(0));
            assertInstanceOf(Piped.Failed.class, outcomes.get(1));
            assertInstanceOf(Piped.Skipped.class, outcomes.get(2));
            assertFalse(c.inTransaction(), "committed as it ended");
            assertEquals(List.of(List.of(integer(1))), rows(c.statements().query("SELECT K FROM PIPE_SKIP")));
            c.setAutocommit(false);
            List<Piped> open = c.pipeline()
                    .onError(Pipeline.OnError.SKIP)
                    .query("INSERT INTO PIPE_SKIP VALUES (4)")
                    .query("INSERT INTO PIPE_SKIP VALUES (5)")
                    .run();
            assertInstanceOf(Piped.Done.class, open.get(1));
            assertTrue(c.inTransaction(), "outside autocommit the transaction stays open");
            c.statements().query("ROLLBACK");
            assertEquals(1, count(c, "PIPE_SKIP"));
            c.statements().query("COMMIT");
            c.terminate();
        }
    }

    /**
     * Verifies: [CLIENT Pipelines] requests are written a window of 64 KiB at a time, its replies
     * read before the next, so a batch longer than the sockets' buffers is answered whole; under
     * skip, the requests of windows after a failure are never sent.
     */
    @Test
    void manyWindowsAreAnsweredWhole() throws TurborelException, IOException {
        try (Connection c = connected()) {
            c.setAutocommit(true);
            String padded = "VALUES 1 -- " + "x".repeat(1000) + "\n";
            Pipeline pipeline = c.pipeline();
            for (int i = 0; i < 300; i++) {
                pipeline.query(padded);
            }
            List<Piped> outcomes = pipeline.run();
            assertEquals(300, outcomes.size());
            assertTrue(
                    outcomes.stream().allMatch(o -> o instanceof Piped.Done),
                    () -> outcomes.stream()
                            .filter(o -> o instanceof Piped.Failed)
                            .map(o -> ((Piped.Failed) o).failure().getMessage())
                            .findFirst()
                            .orElse("?"));
            c.statements().query("CREATE TABLE PIPE_WINDOWS (K INTEGER PRIMARY KEY)");
            Pipeline skipping = c.pipeline().onError(Pipeline.OnError.SKIP).query("VALUES 1 / 0");
            for (int i = 0; i < 200; i++) {
                skipping.query("INSERT INTO PIPE_WINDOWS VALUES (" + i + ") -- " + "y".repeat(1000) + "\n");
            }
            List<Piped> skipped = skipping.run();
            assertInstanceOf(Piped.Failed.class, skipped.get(0));
            assertTrue(skipped.subList(1, skipped.size()).stream().allMatch(o -> o instanceof Piped.Skipped));
            assertEquals(0, count(c, "PIPE_WINDOWS"));
            c.terminate();
        }
    }
}
