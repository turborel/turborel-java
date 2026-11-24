package com.turborel.jdbc.internal.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.turborel.jdbc.TurborelException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The server's sample frames of replies, each decoded to what its meaning says. */
class SampleRepliesTest {
    private static final DataType INT = new DataType.ExactType(10, 0);

    private static final String TRUNCATION = "string data, right truncation";

    private static final String BROKEN = "a unique constraint is broken";

    private static final String CITED = "ISO/IEC 9075-2:2023 §11.7";

    private static Octets text(String s) {
        return Octets.of(s.getBytes(StandardCharsets.UTF_8));
    }

    private static Reply complete(int sqlcode, String sqlstate, boolean active, Completed.Fields fields) {
        return new Reply.Complete(new Completed(sqlcode, sqlstate, active, fields));
    }

    private static Completed.Fields tail(@Nullable Long position, boolean more, @Nullable Long rows) {
        return new Completed.Fields(position, more, rows, List.of(), List.of(), null);
    }

    private static Diagnostics diagnostics(Object... pairs) {
        Map<DiagnosticField, String> fields = new EnumMap<>(DiagnosticField.class);
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put((DiagnosticField) pairs[i], (String) pairs[i + 1]);
        }
        return Diagnostics.of(fields);
    }

    private static List<Column> twoColumns() {
        return List.of(
                new Column("K", INT, true),
                new Column(null, new DataType.CharacterType(true, 5, CharUnits.CHARACTERS), false));
    }

    /** The replies of the opening, statements and their completions, by name. */
    private static Map<String, Reply> statements() throws TurborelException {
        Map<String, Reply> r = new LinkedHashMap<>();
        byte[] key = new byte[CancelKey.BYTES];
        java.util.Arrays.fill(key, (byte) 0xA5);
        r.put("challenge", new Reply.Challenge(text("r=abcdef,s=c2FsdA==,i=4096")));
        r.put("ready", new Reply.Ready("ANN", CancelKey.of(key), List.of("ping"), "turborel 0.0.0 (alpha)"));
        List<Parameter> parameters = List.of(
                new Parameter(true, new DataType.CharacterType(true, 0x7FFF, CharUnits.CHARACTERS)),
                new Parameter(false, INT));
        r.put("prepared", new Reply.Prepared(1, parameters, twoColumns()));
        r.put("prepared-empty", new Reply.Prepared(2, List.of(), List.of()));
        Parameter exact = new Parameter(false, INT);
        r.put(
                "prepared-called",
                new Reply.Prepared(
                        3,
                        List.of(exact),
                        List.of(new Column("G", INT, true)),
                        new Called(
                                new TypeName("S", "P"),
                                List.of(
                                        new Called.Mark(ParameterMode.IN, 1, exact),
                                        new Called.Mark(ParameterMode.OUT, 2, exact)))));
        r.put(
                "prepared-declared",
                new Reply.Prepared(
                        1, parameters, List.of(new Column("K", INT, true, new Declared("INTEGER", "INTEGER")))));
        r.put("description", new Reply.Description(twoColumns()));
        r.put("description-empty", new Reply.Description(List.of()));
        r.put("description-declared", new Reply.Description(declared()));
        r.put(
                "rows",
                new Reply.Rows(List.of(
                        List.of(new Value.Exact(BigInteger.ONE, 0), new Value.Text("a")),
                        List.of(Value.NULL, new Value.Text("")))));
        r.put("rows-none", new Reply.Rows(List.of()));
        r.put("rows-empty-row", new Reply.Rows(List.of(List.of())));
        r.put("suspended", new Reply.Suspended());
        r.putAll(completes());
        return r;
    }

    /** The Completes, each tail field among them, by name. */
    private static Map<String, Reply> completes() {
        Map<String, Reply> r = new LinkedHashMap<>();
        r.put("complete", complete(0, "00000", false, tail(null, false, null)));
        r.put("complete-active", complete(0, "00000", true, tail(null, false, null)));
        r.put("complete-no-data", complete(100, "02000", false, tail(null, false, null)));
        r.put("complete-position", complete(0, "00000", false, tail(4711L, false, null)));
        r.put("complete-more", complete(0, "00000", false, tail(null, true, null)));
        r.put("complete-rows", complete(0, "00000", false, tail(null, false, 1L)));
        r.put(
                "complete-every",
                complete(
                        0,
                        "00000",
                        false,
                        new Completed.Fields(
                                4711L, true, 3L, List.of("7", "8"), List.of(), new Timing(1500, 20, 300))));
        r.put(
                "complete-conditions",
                complete(
                        0,
                        "01004",
                        true,
                        new Completed.Fields(
                                null,
                                false,
                                null,
                                List.of(),
                                List.of(new Failure(0, "01004", TRUNCATION, null, null, true, null)),
                                null)));
        return r;
    }

    /** The columns I, NOT NULL, and S, B, D, R, P and M, each of the type it was declared with. */
    private static List<Column> declared() {
        DataType money = new DataType.ExactType(9, 2);
        Object[][] columns = {
            {"I", INT, "INTEGER", "INTEGER"},
            {"S", new DataType.ExactType(5, 0), "SMALLINT", "SMALLINT"},
            {"B", new DataType.ExactType(18, 0), "BIGINT", "BIGINT"},
            {"D", money, "DECIMAL", "DECIMAL(9,2)"},
            {"R", new DataType.ApproximateType(24), "REAL", "REAL"},
            {"P", new DataType.ApproximateType(53), "DOUBLE PRECISION", "DOUBLE PRECISION"},
            {"M", money, "USER-DEFINED", "S.MONEY"},
        };
        List<Column> made = new java.util.ArrayList<>();
        for (Object[] c : columns) {
            made.add(new Column(
                    (String) c[0], (DataType) c[1], c[0].equals("I"), new Declared((String) c[2], (String) c[3])));
        }
        return made;
    }

    /** The replies of failures, notices, large objects and the session's status, by name. */
    private static Map<String, Reply> others() {
        Map<String, Reply> r = new LinkedHashMap<>();
        r.put("error", new Reply.Error(new Failure(-803, "23505", BROKEN, null, null, false, null)));
        r.put("error-cited", new Reply.Error(new Failure(-803, "23505", BROKEN, CITED, 12L, true, null)));
        r.put(
                "error-diagnostics",
                new Reply.Error(new Failure(
                        -803,
                        "23505",
                        BROKEN,
                        CITED,
                        12L,
                        true,
                        diagnostics(
                                DiagnosticField.SEVERITY, "ERROR",
                                DiagnosticField.DETAIL, "key (K)=(1)",
                                DiagnosticField.HINT, "choose another K",
                                DiagnosticField.CONTEXT, "trigger S.TR",
                                DiagnosticField.SCHEMA, "S",
                                DiagnosticField.TABLE, "T",
                                DiagnosticField.COLUMN, "K",
                                DiagnosticField.DATA_TYPE, "INTEGER",
                                DiagnosticField.CONSTRAINT, "T_PK"))));
        r.put(
                "error-connection",
                new Reply.Error(new Failure(-919, "08006", "a malformed message", null, null, false, null)));
        r.put("lob-data", new Reply.LobData(text("aé")));
        r.put("lob-data-empty", new Reply.LobData(Octets.EMPTY));
        r.put("lob-locator", new Reply.LobLocator(4));
        r.put("notice", new Reply.Notice("01004", TRUNCATION, Diagnostics.NONE));
        r.put(
                "notice-diagnostics",
                new Reply.Notice(
                        "01004",
                        TRUNCATION,
                        diagnostics(DiagnosticField.SEVERITY, "WARNING", DiagnosticField.DETAIL, "cut to 4")));
        r.put("pong", new Reply.Pong());
        r.put("status-server-version", new Reply.Status("SERVER_VERSION", "turborel 0.0.0 (alpha)"));
        r.put("status-authorization", new Reply.Status("AUTHORIZATION", "ANN"));
        r.put("status-schema", new Reply.Status("SCHEMA", "ANN"));
        r.put("status-read-only", new Reply.Status("READ_ONLY", "NO"));
        r.put("status-server-read-only", new Reply.Status("SERVER_READ_ONLY", "YES"));
        r.put("status-catalog", new Reply.Status("CATALOG", "TURBOREL"));
        r.put("status-owner", new Reply.Status("OWNER", "YES"));
        r.put("status-time-zone", new Reply.Status("SESSION.TIME_ZONE", "+05:30"));
        r.put("status-null", new Reply.Status("STATEMENT.TIMEOUT_MS", ""));
        debugged(r);
        return r;
    }

    /**
     * Verifies: [PROTOCOL Sample frames] every server frame of a reply decodes to what its meaning
     * says, the capabilities it needs granted: the opening's, a statement's and its completion's
     * with each tail field, failures with and without their diagnostics, notices, large objects,
     * each Status, and the debugger's Debugged and Event ([PROTOCOL Debug 'V']); none is left
     * unchecked, each data type and value tag aside, which have their own test.
     */
    @Test
    void everyReplyDecodesAsItsMeaningSays() throws TurborelException {
        Map<String, Reply> expected = statements();
        expected.putAll(others());
        Map<String, SampleFrames.Sample> samples = SampleFrames.of("server", "");
        samples.keySet().removeIf(name -> name.startsWith("value-") || SampleTypesTest.isType(name));
        samples.remove("rows-compressed");
        assertEquals(samples.keySet(), new TreeSet<>(expected.keySet()), "an expectation for each sample");
        for (Map.Entry<String, Reply> each : expected.entrySet()) {
            SampleFrames.Sample sample = SampleFrames.in(samples, each.getKey());
            assertEquals(each.getValue(), ReplyCodec.decode(sample.kind(), sample.body()), each.getKey());
        }
    }

    /** The debugger's replies and events, by name. */
    private static void debugged(Map<String, Reply> r) {
        TypeName raise = new TypeName("ANN", "RAISE_PAY");
        Debugging.Breakpoint first = new Debugging.Breakpoint(1, raise, 4, null);
        Debugging.Frame frame = new Debugging.Frame(
                new Debugging.Origin(Debugging.Kind.PROCEDURE, raise, raise),
                true,
                1L,
                4L,
                3L,
                List.of(new Debugging.Named("PCT", "INTEGER", new Value.Exact(BigInteger.TEN, 0))),
                List.of(new Debugging.Named("N", "INTEGER", Value.NULL)),
                List.of(new Debugging.Cursor("C", false, "SELECT ID FROM STAFF")));
        Debugging.Paused paused = new Debugging.Paused(1L, List.of(frame));
        r.put("debugged-connection", new Reply.Debugged(new Debugging.Answer.Connection(2)));
        r.put("debugged-breakpoint", new Reply.Debugged(new Debugging.Answer.Set(first)));
        r.put("debugged-breakpoints", new Reply.Debugged(new Debugging.Answer.Listed(List.of(first))));
        r.put("debugged-running", new Reply.Debugged(new Debugging.Answer.Stack(null)));
        r.put("debugged-stack", new Reply.Debugged(new Debugging.Answer.Stack(paused)));
        r.put("event-paused", new Reply.Event(new Debugging.Event.Pause(paused)));
        r.put("event-timed-out", new Reply.Event(new Debugging.Event.TimedOut()));
        r.put("event-cancelled", new Reply.Event(new Debugging.Event.Cancelled()));
        r.put("event-ended", new Reply.Event(new Debugging.Event.Ended()));
    }
}
