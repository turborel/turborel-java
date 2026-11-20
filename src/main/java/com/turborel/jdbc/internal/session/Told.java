package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.internal.codec.Attribute;
import com.turborel.jdbc.internal.codec.Completed;
import com.turborel.jdbc.internal.codec.Reply;
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
 * last commit's position and the last completion. As Turborel's {@code client/src/told.rs}.
 */
final class Told {
    /** The most notices kept for a connection with no handler: the oldest dropped past them. */
    static final int KEPT_NOTICES = 64;

    private Set<String> granted = Set.of();
    private final Map<String, String> settings = new HashMap<>();
    private final Deque<Reply.Notice> notices = new ArrayDeque<>();
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

    void attributes(List<Attribute> set) {
        attributes = List.copyOf(set);
    }

    /** The attributes a Query or an Execute carries: none unless granted. */
    List<Attribute> attributes() {
        return has(Capability.QUERY_ATTRIBUTES) ? attributes : List.of();
    }
}
