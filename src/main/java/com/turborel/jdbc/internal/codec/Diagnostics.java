package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A failure's or a notice's diagnostics, tags 7 to 15 of its tail, each field present only where
 * the server had something to say ([PROTOCOL Errors and notices]).
 */
public final class Diagnostics {
    /** None. */
    public static final Diagnostics NONE = new Diagnostics(new EnumMap<>(DiagnosticField.class));

    private final Map<DiagnosticField, String> fields;

    private Diagnostics(EnumMap<DiagnosticField, String> fields) {
        this.fields = Collections.unmodifiableMap(fields);
    }

    /**
     * Diagnostics of the fields given.
     *
     * @param fields each field said, by its kind
     * @return the diagnostics
     */
    public static Diagnostics of(Map<DiagnosticField, String> fields) {
        EnumMap<DiagnosticField, String> copy = new EnumMap<>(DiagnosticField.class);
        copy.putAll(fields);
        return new Diagnostics(copy);
    }

    /**
     * The diagnostics of a tail of diagnostics alone, to the end of what it reads.
     *
     * @param r where the tail is read
     * @return the diagnostics; none if the tail is empty
     * @throws TurborelException if a tag is out of place, or a field is malformed
     */
    static @Nullable Diagnostics read(Reader r) throws TurborelException {
        Tail tail = new Tail(Tail.DIAGNOSTICS);
        EnumMap<DiagnosticField, String> fields = new EnumMap<>(DiagnosticField.class);
        for (int tag = tail.next(r); tag != Tail.END; tag = tail.next(r)) {
            DiagnosticField field = DiagnosticField.of(tag);
            if (field != null) {
                fields.put(field, r.string());
            }
        }
        return fields.isEmpty() ? null : new Diagnostics(fields);
    }

    /**
     * A field, if the server said it.
     *
     * @param field which
     * @return its text, or none
     */
    public @Nullable String get(DiagnosticField field) {
        return fields.get(field);
    }

    /**
     * Every field said.
     *
     * @return the fields, by kind, unmodifiable
     */
    public Map<DiagnosticField, String> fields() {
        return fields;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Diagnostics d && d.fields.equals(fields);
    }

    @Override
    public int hashCode() {
        return fields.hashCode();
    }

    @Override
    public String toString() {
        return "Diagnostics" + fields;
    }
}
