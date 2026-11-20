package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.internal.codec.Completed;

/** What a statement whose rows went to a {@link Sink} gave ([CLIENT Running statements]). */
public sealed interface Streamed {
    /**
     * A query: how many rows went to the sink.
     *
     * @param count the rows
     */
    record Rows(long count) implements Streamed {}

    /**
     * Any other statement's completion.
     *
     * @param completed the completion
     */
    record Done(Completed completed) implements Streamed {}
}
