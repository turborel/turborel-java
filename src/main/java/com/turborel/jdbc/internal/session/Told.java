package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Attribute;
import com.turborel.jdbc.internal.codec.Completed;
import com.turborel.jdbc.internal.codec.Debugging;
import com.turborel.jdbc.internal.codec.Failure;
import com.turborel.jdbc.internal.codec.Reply;
import com.turborel.jdbc.internal.codec.Timing;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * What the server has told a connection beside each statement's replies, and what each request
 * carries that the program set ([CLIENT What a connection asks for], [CLIENT What the server
 * tells]): the capabilities granted, the session's settings as Status gave them, the notices, the
 * last commit's position and the last completion. As Turborel's {@code client/src/told.rs} and
 * {@code session.rs}.
 */
public final class Told {
    /** The most notices kept for a connection with no handler: the oldest dropped past them. */
    static final int KEPT_NOTICES = 64;

    private Set<String> granted = Set.of();
    private final Map<String, String> settings = new HashMap<>();
    private final Deque<Reply.Notice> notices = new ArrayDeque<>();
    private final Deque<Debugging.Event> events = new ArrayDeque<>();
    private @Nullable Consumer<Reply.Notice> handler;
    private @Nullable Long position;
    private Completed.@Nullable Fields completed;
    private @Nullable Integer compress;
    private List<Attribute> attributes = List.of();

    Set<String> granted() {
        return granted;
    }

    /** Takes Ready's grant, compressing from {@code compressFrom} bytes if compression is in it. */
    void grant(List<String> names, int compressFrom) {
        granted = Set.copyOf(names);
        compress = granted.contains(Capability.COMPRESSION) ? compressFrom : null;
    }

    boolean has(String capability) {
        return granted.contains(capability);
    }

    @Nullable
    Integer compress() {
        return compress;
    }

    void status(String name, String value) {
        settings.put(name, value);
    }

    @Nullable
    String setting(String name) {
        return settings.get(name);
    }

    Map<String, String> settings() {
        return Collections.unmodifiableMap(new HashMap<>(settings));
    }

    /** Keeps an Event the debugger's connection heard, until the debugger waits for it. */
    void event(Debugging.Event event) {
        events.addLast(event);
    }

    /** The oldest Event kept, taken; none if none is kept. */
    Debugging.@Nullable Event takeEvent() {
        return events.pollFirst();
    }

    /** The Events kept, dropped: of a target no longer debugged. */
    void dropEvents() {
        events.clear();
    }

    /** Takes a notice: to the handler, or kept, the oldest dropped past {@link #KEPT_NOTICES}. */
    void notice(Reply.Notice notice) {
        Consumer<Reply.Notice> to = handler;
        if (to != null) {
            to.accept(notice);
            return;
        }
        if (notices.size() == KEPT_NOTICES) {
            notices.removeFirst();
        }
        notices.addLast(notice);
    }

    void handler(@Nullable Consumer<Reply.Notice> to) {
        handler = to;
    }

    List<Reply.Notice> takeNotices() {
        List<Reply.Notice> taken = new ArrayList<>(notices);
        notices.clear();
        return taken;
    }

    void completed(Completed.Fields fields) {
        completed = fields;
        if (fields.position() != null) {
            position = fields.position();
        }
    }

    Completed.@Nullable Fields completed() {
        return completed;
    }

    @Nullable
    Long position() {
        return position;
    }

    /**
     * The log position of the last commit the server told of, once granted {@code
     * commit-position}: set as REPLICATION.READ_AFTER on a replica's session, that session's
     * statements read what the commit made.
     *
     * @return the position, or none
     */
    public @Nullable Long commitPosition() {
        return position;
    }

    /**
     * The rows the last statement inserted, updated, deleted or merged, as GET DIAGNOSTICS
     * ROW_COUNT gives them, or the rows the last query returned, once all were read; once granted
     * {@code row-counts}.
     *
     * @return the rows, or none
     */
    public @Nullable Long rowsAffected() {
        Completed.Fields last = completed;
        return last == null ? null : last.rows();
    }

    /**
     * The server's time for the last statement, in microseconds, once granted {@code row-counts}:
     * from when a worker began its request until its completion was ready, a cursor's summed over
     * its fetches, with the time its request waited for a worker and the time it waited for locks.
     *
     * @return the time, or none
     */
    public @Nullable Timing serverTime() {
        Completed.Fields last = completed;
        return last == null ? null : last.time();
    }

    /**
     * The identity values the last INSERT generated, in the order of its rows, as SQL writes them,
     * once granted {@code generated-values}: none of another statement, or of a trigger's.
     *
     * @return the values
     */
    public List<String> lastGenerated() {
        Completed.Fields last = completed;
        return last == null ? List.of() : last.generated();
    }

    /**
     * Each condition the last statement raised and completed with, once granted {@code
     * conditions}: the warnings of class 01 and the conditions of class 02 its routines signalled,
     * and the one it completed with.
     *
     * @return the conditions, each as a failure
     */
    public List<TurborelException> conditions() {
        Completed.Fields last = completed;
        if (last == null) {
            return List.of();
        }
        List<TurborelException> conditions = new ArrayList<>();
        for (Failure each : last.conditions()) {
            conditions.add(each.toException());
        }
        return conditions;
    }

    /**
     * The conditions of {@link #conditions}, taken, so a program that tells them after each
     * statement tells each once.
     *
     * @return the conditions
     */
    public List<TurborelException> takeConditions() {
        List<TurborelException> taken = conditions();
        Completed.Fields last = completed;
        if (last != null) {
            completed = new Completed.Fields(
                    last.position(), last.more(), last.rows(), last.generated(), List.of(), last.time());
        }
        return taken;
    }

    void attributes(List<Attribute> set) {
        attributes = List.copyOf(set);
    }

    /** The attributes a Query or an Execute carries: none unless granted. */
    List<Attribute> attributes() {
        return has(Capability.QUERY_ATTRIBUTES) ? attributes : List.of();
    }
}
