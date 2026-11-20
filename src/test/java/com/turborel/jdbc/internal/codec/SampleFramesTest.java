package com.turborel.jdbc.internal.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.session.Capability;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The protocol's sample frames: their layout, the client's encoded, the compressed inflated. */
class SampleFramesTest {
    private static final Octets NONE = Octets.EMPTY;

    private static final CancelKey KEY;

    static {
        byte[] octets = new byte[CancelKey.BYTES];
        java.util.Arrays.fill(octets, (byte) 0xA5);
        try {
            KEY = CancelKey.of(octets);
        } catch (TurborelException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static FrameHeader header(byte[] frame) throws TurborelException {
        return FrameHeader.decode(java.util.Arrays.copyOf(frame, FrameHeader.BYTES), FrameHeader.MAX_BODY);
    }

    private static Octets text(String s) {
        return Octets.of(s.getBytes(StandardCharsets.UTF_8));
    }

    private static Request.Hello hello(List<String> capabilities, List<Attribute> attributes) {
        return new Request.Hello("ANN", "trust", NONE, capabilities, attributes);
    }

    /** The attributes a0 to a31, each of the empty value. */
    private static List<Attribute> most() {
        List<Attribute> most = new ArrayList<>();
        for (int i = 0; i < Attribute.MAX_ATTRIBUTES; i++) {
            most.add(new Attribute("a" + i, ""));
        }
        return most;
    }

    /** The requests of the client's samples, by name, as their meanings say. */
    private static Map<String, Request> requests() {
        Map<String, Request> r = new LinkedHashMap<>();
        r.put("hello-ping", hello(List.of(Capability.PING), List.of()));
        r.put("hello-none", hello(List.of(), List.of()));
        r.put(
                "hello-every",
                hello(
                        List.of(
                                Capability.STATUS,
                                Capability.DESCRIBE,
                                Capability.CURSORS,
                                Capability.COMPRESSION,
                                Capability.DECLARED_TYPES),
                        List.of(new Attribute("application", "app"))));
        r.put(
                "hello-attribute-longest",
                hello(
                        List.of(),
                        List.of(new Attribute("a".repeat(Attribute.MAX_NAME), "v".repeat(Attribute.MAX_VALUE)))));
        r.put("hello-attributes-most", hello(List.of(), most()));
        r.put("hello-scram", new Request.Hello("ANN", "scram-sha-256", text("n,,n=,r=abc"), List.of(), List.of()));
        r.put("answer", new Request.Answer(text("c=biws,r=abc,p=xyz")));
        r.put("answer-empty", new Request.Answer(NONE));
        r.put("cancel", new Request.Cancel(KEY));
        r.put("query", new Request.Query("VALUES 1", true, List.of(), false));
        r.put("query-empty", new Request.Query("", false, List.of(), false));
        r.put(
                "query-attributes",
                new Request.Query(
                        "VALUES QUERY_ATTRIBUTE('trace')", false, List.of(new Attribute("trace", "t1")), false));
        r.put("query-multi", new Request.Query("VALUES 1; VALUES 2", true, List.of(), true));
        r.put("prepare", new Request.Prepare(1, "SELECT K FROM T WHERE K > ?"));
        r.put(
                "execute",
                new Request.Execute(
                        1, List.of(Value.NULL, new Value.Text(""), new Value.Truth(true)), 2, false, List.of(), 0));
        r.put("execute-cursor", new Request.Execute(1, List.of(), 0, true, List.of(new Attribute("a", "b")), 7));
        r.put("fetch", new Request.Fetch(1, 100, 0));
        r.put("fetch-cursor", new Request.Fetch(1, 0xFFFF_FFFFL, 7));
        r.put("close", new Request.Close(1, 0));
        r.put("close-cursor", new Request.Close(1, 7));
        r.put("deallocate", new Request.Deallocate(0xFFFF_FFFFL));
        r.put("terminate", new Request.Terminate());
        lobsAndControls(r);
        return r;
    }

    private static void lobsAndControls(Map<String, Request> r) {
        r.put("lob-read", new LobRequest.Read(3, 0, 65_536, true));
        r.put("lob-create", new LobRequest.Create(LobKind.BINARY));
        r.put("lob-append", new LobRequest.Append(4, Octets.of(new byte[] {(byte) 0xC3})));
        r.put("lob-free", new LobRequest.Free(4));
        r.put("lob-hold", new LobRequest.Hold(3));
        r.put("describe-statement", new Request.Describe(false, 1, 0));
        r.put("describe-cursor", new Request.Describe(true, 1, 7));
        r.put("ping", new Request.Ping());
        r.put("reset", new Request.Reset());
        r.put("change-user", new Request.ChangeUser("BOB", "password", text("secret")));
        r.put("copy-in", new Request.CopyIn("S.T", List.of("ID", "NAME"), true));
        r.put("copy-in-every-column", new Request.CopyIn("T", List.of(), false));
        r.put(
                "copy-data",
                new Request.CopyData(List.of(
                        List.of(new Value.Text("1"), Value.NULL), List.of(new Value.Text("2"), new Value.Text("b")))));
        r.put("copy-end", new Request.CopyEnd(false));
        r.put("copy-end-abandon", new Request.CopyEnd(true));
    }

    /**
     * Verifies: [PROTOCOL Sample frames] every line of the file, at 045e42a9, is read as the page
     * lays it out: five fields apart by one tab, a direction of client or server, a name unique
     * in the file, needs of - or capabilities the protocol names, the whole frame in upper-case
     * hexadecimal, its header's length its body's, and a meaning; every request and reply kind is
     * among them.
     */
    @Test
    void everyLineIsAsThePageLaysItOut() throws TurborelException {
        List<String[]> lines = SampleFrames.lines();
        assertTrue(lines.size() >= 140, "147 frames at 045e42a9");
        Set<String> names = new HashSet<>();
        Set<String> kinds = new HashSet<>();
        for (String[] f : lines) {
            assertEquals(SampleFrames.FIELDS, f.length, String.join("|", f));
            assertTrue(f[0].equals("client") || f[0].equals("server"), f[0]);
            assertTrue(names.add(f[1]), "unique: " + f[1]);
            if (!f[2].equals("-")) {
                for (String need : f[2].split(",", -1)) {
                    assertTrue(Capability.ALL.contains(need), need);
                }
            }
            assertTrue(f[3].matches("([0-9A-F]{2})+"), f[1]);
            assertTrue(!f[4].isEmpty(), f[1]);
            byte[] frame = Fixture.hex(f[3]);
            FrameHeader header = header(frame);
            assertEquals(frame.length - FrameHeader.BYTES, header.length(), f[1]);
            kinds.add(f[0] + " " + (char) frame[0]);
        }
        assertEquals(names, SampleFrames.all().keySet());
        Set<String> every = new HashSet<>();
        for (char kind : "HAZQPEFCDXLYGWUIJNz".toCharArray()) {
            every.add("client " + kind);
        }
        for (char kind : "CRPDBSKELNGTz".toCharArray()) {
            every.add("server " + kind);
        }
        assertEquals(every, kinds, "every request and reply kind");
    }

    /**
     * Verifies: [PROTOCOL Sample frames] every client frame of the file is what the driver encodes
     * of the request its meaning names, byte for byte; none is left unchecked.
     */
    @Test
    void everyClientFrameIsEncodedByteForByte() throws TurborelException {
        Map<String, Request> requests = requests();
        Map<String, SampleFrames.Sample> samples = SampleFrames.of("client", "");
        samples.remove("query-compressed");
        assertEquals(samples.keySet(), new java.util.TreeSet<>(requests.keySet()), "a request for each sample");
        for (Map.Entry<String, Request> each : requests.entrySet()) {
            assertArrayEquals(
                    SampleFrames.in(samples, each.getKey()).bytes(),
                    each.getValue().encode(),
                    each.getKey());
        }
    }

    /**
     * Verifies: [PROTOCOL Sample frames] a compressed frame's sample, the {@code z} frame as sent,
     * inflates to the frame its meaning names, of that kind and length: the client's a Query of
     * 2000 x, the server's Rows of 64 rows, 0 to 63 and abcdefgh four times.
     */
    @Test
    void compressedFramesInflateToWhatTheyName() throws TurborelException {
        SampleFrames.Sample query = SampleFrames.named("query-compressed");
        Frames.Frame sent = Compression.inflate(query.body(), FrameHeader.MAX_BODY);
        byte[] expected =
                new Request.Query("SELECT '" + "x".repeat(2000) + "' FROM T", true, List.of(), false).encode();
        assertEquals(expected[0], sent.kind());
        assertEquals(2021, sent.body().length());
        assertArrayEquals(
                java.util.Arrays.copyOfRange(expected, FrameHeader.BYTES, expected.length),
                sent.body().toByteArray());
        SampleFrames.Sample rows = SampleFrames.named("rows-compressed");
        Frames.Frame inflated =
                Frames.unwrap(new Frames.Frame(rows.kind(), Octets.of(rows.body())), true, FrameHeader.MAX_BODY);
        assertEquals(Reply.ROWS, inflated.kind());
        assertEquals(3972, inflated.body().length());
        List<List<Value>> made = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            made.add(List.of(new Value.Exact(BigInteger.valueOf(i), 0), new Value.Text("abcdefgh".repeat(4))));
        }
        assertEquals(
                new Reply.Rows(made),
                ReplyCodec.decode(inflated.kind(), inflated.body().toByteArray()));
    }
}
