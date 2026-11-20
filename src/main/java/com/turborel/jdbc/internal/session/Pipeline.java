package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Attribute;
import com.turborel.jdbc.internal.codec.Request;
import com.turborel.jdbc.internal.codec.Value;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Requests queued on a connection, written at once by {@link #run} ([CLIENT Pipelines]): one outcome
 * read for each, in order, as the server answers each request alone and in turn, so a batch costs
 * one round trip, not one a request ([PROTOCOL Requests]). As Turborel's {@code
 * client/src/pipeline.rs}.
 *
 * <p>The requests are written a window at a time, each window at most {@link #WINDOW} bytes, and its
 * replies read before the next is written. A window fits the buffers of the two sockets between
 * client and server, so it is written whole even while the server writes the replies of its first
 * requests and reads no more.
 *
 * <p>By default a failure is its request's outcome alone and the rest run. {@link OnError#SKIP}
 * makes the batch stop at its first failure, the requests before it kept: a savepoint is set before
 * each request but the first, and the requests after a failed one are undone to the savepoint set
 * after it, or never sent if their window was not yet written, each {@link Piped.Skipped}. Its
 * requests are of one transaction, which under autocommit, of a batch that began none, is committed
 * as the batch ends.
 */
public final class Pipeline {
    /**
     * The most bytes of requests written before their replies are read: well within the smallest
     * socket buffer a platform gives, so a window is written whole whatever the server is doing.
     */
    public static final int WINDOW = 64 * 1024;

    /** What the savepoints {@link OnError#SKIP} sets are named by, then each its place. */
    static final String SAVEPOINT = "TURBOREL_PIPELINE";

    /** What a pipeline does after a request fails. */
    public enum OnError {
        /** Every request runs; a failure is its request's outcome alone. The default. */
        CONTINUE,
        /** The batch stops at its first failure: the requests after it are undone or never sent. */
        SKIP
    }

    /** The outcome of one request of a pipeline. */
    public sealed interface Piped {
        /**
         * What the request gave.
         *
         * @param outcome its outcome
         */
        record Done(Outcome outcome) implements Piped {}

        /**
         * How the request failed, as the server reported it.
         *
         * @param failure the failure
         */
        record Failed(TurborelException failure) implements Piped {}

        /** Not run, or run and undone, after an earlier request failed under {@link OnError#SKIP}. */
        record Skipped() implements Piped {}
    }

    /** A request queued, and how its reply is read. */
    private sealed interface Queued {
        record Query(String sql) implements Queued {}

        record Execute(Statement statement, List<Value> values) implements Queued {}

        /** Close and Deallocate of a statement by its number, of a pool's reset. */
        record Close(long statement) implements Queued {}

        record Deallocate(long statement) implements Queued {}

        /** The savepoint {@link OnError#SKIP} sets before the request of this place, its outcome never shown. */
        record Savepoint(int place) implements Queued {}
    }

    /** The requests queued before each savepoint {@link OnError#SKIP} sets: one each. */
    private static final int GUARDED = 2;

    /** Where no request failed. */
    private static final int NONE = -1;

    /** A request of a window, and its frames. */
    private static final class Encoded {
        private final Queued queued;
        private final byte[] frames;

        Encoded(Queued queued, byte[] frames) {
            this.queued = queued;
            this.frames = frames;
        }

        Queued queued() {
            return queued;
        }

        byte[] frames() {
            return frames;
        }
    }

    private final Connection connection;
    private final List<Queued> queued = new ArrayList<>();
    private OnError onError = OnError.CONTINUE;

    Pipeline(Connection connection) {
        this.connection = connection;
    }

    /**
     * Queues {@code sql}, run as {@link Statements#query} runs it.
     *
     * @param sql the statement
     * @return this pipeline
     */
    public Pipeline query(String sql) {
        queued.add(new Queued.Query(sql));
        return this;
    }

    /**
     * Queues an execution of {@code statement} with {@code values}, as {@link Statements#execute}
     * runs it: all of a query's rows.
     *
     * @param statement the statement
     * @param values a value for each parameter
     * @return this pipeline
     */
    public Pipeline execute(Statement statement, List<Value> values) {
        queued.add(new Queued.Execute(statement, List.copyOf(values)));
        return this;
    }

    /** Queues the closing of statement {@code number}'s cursor, then its destruction: a pool's reset of what a borrower left. */
    Pipeline release(long number) {
        queued.add(new Queued.Close(number));
        queued.add(new Queued.Deallocate(number));
        return this;
    }

    /**
     * What the batch does after a request fails.
     *
     * @param how continue or skip
     * @return this pipeline
     */
    public Pipeline onError(OnError how) {
        onError = how;
        return this;
    }

    /**
     * How many requests are queued.
     *
     * @return the count
     */
    public int size() {
        return queued.size();
    }

    /**
     * Whether none is queued.
     *
     * @return whether empty
     */
    public boolean isEmpty() {
        return queued.isEmpty();
    }

    /**
     * Writes the requests, a window at a time, and reads one outcome for each, in the order they
     * were queued; the pipeline is empty after, to queue more.
     *
     * @return an outcome for each request
     * @throws TurborelException a connection failure, once the connection is lost: what the
     *     requests answered before it is not given; the codec's refusal of a request, before any is
     *     sent; under {@link OnError#SKIP}, the failure of undoing the requests after a failed one
     */
    public List<Piped> run() throws TurborelException {
        boolean skip = onError == OnError.SKIP;
        boolean began = connection.inTransaction();
        List<Queued> sent = skip ? guarded(queued) : List.copyOf(queued);
        queued.clear();
        boolean ends = connection.autocommit() && !skip;
        List<Piped> outcomes = new ArrayList<>(sent.size());
        for (List<Encoded> window : windows(sent, ends, connection.told().attributes())) {
            if (skip && outcomes.stream().anyMatch(o -> o instanceof Piped.Failed)) {
                window.forEach(each -> outcomes.add(new Piped.Skipped()));
                continue;
            }
            outcomes.addAll(readWindow(window));
        }
        if (skip) {
            settle(sent, outcomes, began);
        }
        List<Piped> shown = new ArrayList<>(outcomes.size());
        for (int i = 0; i < sent.size(); i++) {
            if (!(sent.get(i) instanceof Queued.Savepoint)) {
                shown.add(outcomes.get(i));
            }
        }
        return shown;
    }

    /** The savepoint {@link OnError#SKIP} sets before the request of place {@code n}. */
    static String savepoint(int n) {
        return SAVEPOINT + "_" + n;
    }

    /** {@code queued}, a savepoint of its own before each request but the first. */
    private static List<Queued> guarded(List<Queued> queued) {
        List<Queued> sent = new ArrayList<>(queued.size() * GUARDED);
        for (int n = 0; n < queued.size(); n++) {
            if (n > 0) {
                sent.add(new Queued.Savepoint(n));
            }
            sent.add(queued.get(n));
        }
        return sent;
    }

    /**
     * The requests of {@code queued}, the transaction ended with each if {@code ends}, carrying
     * {@code attributes}, grouped into windows of at most {@link #WINDOW} bytes: a request longer
     * than that alone in its own.
     */
    private static List<List<Encoded>> windows(List<Queued> queued, boolean ends, List<Attribute> attributes)
            throws TurborelException {
        List<List<Encoded>> windows = new ArrayList<>();
        long filled = WINDOW;
        for (Queued q : queued) {
            byte[] frames = request(q, ends, attributes).encode();
            filled += frames.length;
            if (filled > WINDOW) {
                windows.add(new ArrayList<>());
                filled = frames.length;
            }
            windows.get(windows.size() - 1).add(new Encoded(q, frames));
        }
        return windows;
    }

    /** The sizes of the windows {@code sqls}, queued as queries, are written in. */
    static List<Integer> windowSizes(List<String> sqls) throws TurborelException {
        List<Queued> queued = new ArrayList<>();
        sqls.forEach(sql -> queued.add(new Queued.Query(sql)));
        return windows(queued, false, List.of()).stream().map(List::size).toList();
    }

    /** The request {@code q} sends, the transaction ended with it if {@code ends}, a statement of the program's carrying {@code attributes}. */
    private static Request request(Queued q, boolean ends, List<Attribute> attributes) {
        if (q instanceof Queued.Query query) {
            return new Request.Query(query.sql(), ends, attributes, false);
        }
        if (q instanceof Queued.Execute execute) {
            return Statements.executeRequest(execute.statement(), execute.values(), ends, attributes);
        }
        if (q instanceof Queued.Close close) {
            return new Request.Close(close.statement(), 0);
        }
        if (q instanceof Queued.Deallocate deallocate) {
            return new Request.Deallocate(deallocate.statement());
        }
        Queued.Savepoint savepoint = (Queued.Savepoint) q;
        return new Request.Query("SAVEPOINT " + savepoint(savepoint.place()), false, List.of(), false);
    }

    /** Writes {@code window} at once and reads its outcomes. */
    private List<Piped> readWindow(List<Encoded> window) throws TurborelException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        window.forEach(each -> bytes.writeBytes(each.frames()));
        connection.writeEncoded(bytes.toByteArray());
        Statements statements = connection.statements();
        List<Piped> outcomes = new ArrayList<>(window.size());
        for (Encoded each : window) {
            Queued q = each.queued();
            try {
                Outcome outcome;
                if (q instanceof Queued.Execute execute) {
                    outcome = statements.executeReply(execute.statement());
                } else if (q instanceof Queued.Close || q instanceof Queued.Deallocate) {
                    outcome = new Outcome.Done(statements.completion());
                } else {
                    outcome = statements.queryReply();
                }
                outcomes.add(new Piped.Done(outcome));
            } catch (TurborelException failure) {
                if (connection.isLost()) {
                    throw failure;
                }
                outcomes.add(new Piped.Failed(failure));
            }
        }
        return outcomes;
    }

    /**
     * Ends a batch of {@code sent} run under {@link OnError#SKIP}: the requests after the first
     * that failed undone, to the savepoint set after it, and each marked skipped; the savepoints
     * released; and, under autocommit of a batch that began no transaction, its work committed.
     */
    private void settle(List<Queued> sent, List<Piped> outcomes, boolean began) throws TurborelException {
        int failed = NONE;
        for (int i = 0; i < outcomes.size() && failed < 0; i++) {
            if (outcomes.get(i) instanceof Piped.Failed) {
                failed = i;
            }
        }
        List<String> statements = new ArrayList<>();
        if (failed >= 0) {
            int after = failed + 1;
            if (after < sent.size()
                    && sent.get(after) instanceof Queued.Savepoint savepoint
                    && outcomes.get(after) instanceof Piped.Done) {
                statements.add("ROLLBACK TO SAVEPOINT " + savepoint(savepoint.place()));
            }
            for (int i = after; i < outcomes.size(); i++) {
                outcomes.set(i, new Piped.Skipped());
            }
        }
        if (sent.size() > 1 && sent.get(1) instanceof Queued.Savepoint first && outcomes.get(1) instanceof Piped.Done) {
            statements.add("RELEASE SAVEPOINT " + savepoint(first.place()));
        }
        if (!began && connection.autocommit()) {
            statements.add("COMMIT");
        }
        for (String sql : statements) {
            connection.send(new Request.Query(sql, false, List.of(), false));
            connection.statements().queryReply();
        }
    }
}
