package com.turborel.jdbc.internal.codec;

import static com.turborel.jdbc.internal.codec.Fixture.assertBroken;
import static com.turborel.jdbc.internal.codec.Fixture.bytes;
import static com.turborel.jdbc.internal.codec.Fixture.cat;
import static com.turborel.jdbc.internal.codec.Fixture.hex;
import static com.turborel.jdbc.internal.codec.Fixture.str;
import static com.turborel.jdbc.internal.codec.Fixture.u32;
import static com.turborel.jdbc.internal.codec.Fixture.u64;
import static com.turborel.jdbc.internal.codec.Fixture.u8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.turborel.jdbc.TurborelException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Every reply the client receives, decoded ([PROTOCOL Replies]). */
class ReplyCodecTest {
    /** A fixed seed, so the generated bodies are the same on every run (RULES.md R-TEST-5). */
    private static final long SEED = 7434;

    private static final byte[] COMPLETE_OK = cat(u32(0), str("00000"), u8(0));

    private static Reply decode(char kind, byte[]... body) throws TurborelException {
        return ReplyCodec.decode((byte) kind, cat(body));
    }

    /**
     * Verifies: [PROTOCOL A worked example] the Rows reply of one row of the exact number 1, and
     * the Complete with an empty tail, are the page's bytes; Pong is a header alone.
     */
    @Test
    void theWorkedExamplesReplies() throws TurborelException {
        byte[] rows = hex("01000000 01000000 02 0100000000000000 0000000000000000 00000000");
        Reply.Rows expected = new Reply.Rows(List.of(List.of(new Value.Exact(BigInteger.ONE, 0))));
        assertEquals(expected, ReplyCodec.decode((byte) 'B', rows));
        Completed done = new Completed(0, "00000", false, Completed.Fields.EMPTY);
        assertEquals(new Reply.Complete(done), ReplyCodec.decode((byte) 'K', hex("00000000 0500000030303030 30 00")));
        assertEquals(new Reply.Pong(), ReplyCodec.decode((byte) 'G', new byte[0]));
    }

    /**
     * Verifies: [PROTOCOL A worked example] the same Complete with row-counts granted ends with
     * tag 6 and the row count, then tag 18 and the server's three times.
     */
    @Test
    void theWorkedExamplesCompleteWithRowCounts() throws TurborelException {
        byte[] body = cat(COMPLETE_OK, hex("06 0100000000000000"), u8(18), u64(180), u64(4), u64(0));
        Completed.Fields tail = new Completed.Fields(null, false, 1L, List.of(), List.of(), new Timing(180, 4, 0));
        assertEquals(new Reply.Complete(new Completed(0, "00000", false, tail)), ReplyCodec.decode((byte) 'K', body));
    }

    /**
     * Verifies: [PROTOCOL Complete 'K'] Complete: SQLCODE 0 or 100, SQLSTATE, active, then tail
     * 4 position, 5 more, 6 rows, 16 generated, 17 conditions, each a failure as bytes whose own
     * tail ends where its length does, and 18 time.
     */
    @Test
    void completeReadsEveryTailTag() throws TurborelException {
        byte[] condition =
                cat(u32(0), str("01003"), str("nulls eliminated"), u8(0), u8(0), u8(1), u8(7), str("WARNING"));
        byte[] body = cat(
                u32(100),
                str("02000"),
                u8(1),
                u8(4),
                u64(4711),
                u8(5),
                u8(6),
                u64(3),
                u8(16),
                u32(2),
                str("3"),
                str("4"),
                u8(17),
                u32(1),
                bytes(condition),
                u8(18),
                u64(1),
                u64(2),
                u64(3));
        Completed completed = ((Reply.Complete) decode('K', body)).completed();
        assertEquals(100, completed.sqlcode());
        Completed.Fields tail = completed.fields();
        assertEquals(4711L, tail.position());
        assertEquals(true, tail.more());
        assertEquals(List.of("3", "4"), tail.generated());
        assertEquals(
                "WARNING",
                Objects.requireNonNull(tail.conditions().get(0).diagnostics()).get(DiagnosticField.SEVERITY));
        assertEquals(new Timing(1, 2, 3), tail.time());
        assertBroken(() -> decode('K', COMPLETE_OK, u8(17), u32(1), bytes(cat(condition, u8(0)))));
    }

    /**
     * Verifies: [PROTOCOL The tail] tags come in increasing order; a tag the message does not
     * take, or one not past the last, fails the connection; an empty tail is the first form.
     */
    @Test
    void aTailOutOfPlaceFails() throws TurborelException {
        assertEquals(
                Completed.Fields.EMPTY,
                ((Reply.Complete) decode('K', COMPLETE_OK)).completed().fields());
        assertBroken(() -> decode('K', COMPLETE_OK, u8(6), u64(1), u8(4), u64(1)));
        assertBroken(() -> decode('K', COMPLETE_OK, u8(5), u8(5)));
        assertBroken(() -> decode('K', COMPLETE_OK, u8(1), u32(0)));
        assertBroken(() -> decode('K', COMPLETE_OK, u8(7), str("ERROR")));
    }

    /**
     * Verifies: [PROTOCOL Challenge 'C'] Challenge: bytes; [PROTOCOL Ready 'R'] Ready:
     * authorization, key of 16 bytes, capabilities, server; [PROTOCOL Status 'T'] Status: name
     * and value.
     */
    @Test
    void theLoginReplies() throws TurborelException {
        assertEquals(new Reply.Challenge(Octets.of(hex("FF"))), decode('C', bytes(hex("FF"))));
        byte[] key = new byte[16];
        Arrays.fill(key, (byte) 0xAB);
        Reply ready = decode('R', str("ANN"), key, u32(1), str("ping"), str("turborel 0.0.0 (alpha)"));
        assertEquals(new Reply.Ready("ANN", CancelKey.of(key), List.of("ping"), "turborel 0.0.0 (alpha)"), ready);
        assertEquals(new Reply.Status("SERVER_READ_ONLY", "NO"), decode('T', str("SERVER_READ_ONLY"), str("NO")));
        assertEquals(new Reply.Status("SCHEMA", ""), decode('T', str("SCHEMA"), str("")));
        assertBroken(() -> decode('R', str("ANN"), new byte[15]));
    }

    /**
     * Verifies: [PROTOCOL Prepared 'P'] Prepared: statement, parameters in order, columns;
     * [PROTOCOL Description 'D'] Description: columns; [PROTOCOL Suspended 'S'] Suspended.
     */
    @Test
    void theStatementReplies() throws TurborelException {
        byte[] boolColumn = cat(u8(1), str("B"), u8(14), u8(0));
        Reply prepared = decode('P', u32(9), u32(1), u8(0), u8(14), u32(1), boolColumn);
        Column column = new Column("B", new DataType.BooleanType(), false);
        assertEquals(
                new Reply.Prepared(9, List.of(new Parameter(false, new DataType.BooleanType())), List.of(column)),
                prepared);
        assertEquals(new Reply.Prepared(0, List.of(), List.of()), decode('P', u32(0), u32(0), u32(0)));
        assertEquals(new Reply.Description(List.of(column)), decode('D', u32(1), boolColumn));
        assertEquals(new Reply.Suspended(), decode('S'));
    }

    /**
     * Verifies: [PROTOCOL Error 'E'] Error: a failure, its tail 7 to 15 written with
     * diagnostics; [PROTOCOL Notice 'N'] Notice: SQLSTATE, message, tail 7 to 15.
     */
    @Test
    void errorsAndNotices() throws TurborelException {
        byte[] failure = cat(u32(-919), str("08006"), str("gone"), u8(0), u8(0), u8(0), u8(7), str("FATAL"));
        Failure read = ((Reply.Error) decode('E', failure)).failure();
        assertEquals(-919, read.sqlcode());
        assertEquals("FATAL", Objects.requireNonNull(read.diagnostics()).get(DiagnosticField.SEVERITY));
        Reply notice = decode('N', str("01T01"), str("rolled back"), u8(7), str("NOTICE"));
        assertEquals("NOTICE", ((Reply.Notice) notice).diagnostics().get(DiagnosticField.SEVERITY));
        assertEquals(new Reply.Notice("01000", "w", Diagnostics.NONE), decode('N', str("01000"), str("w")));
    }

    /**
     * Verifies: [PROTOCOL Lob 'L'] Lob, the reply: 0 data, bytes; 1 locator, u32; another what
     * fails.
     */
    @Test
    void theLobReplies() throws TurborelException {
        assertEquals(new Reply.LobData(Octets.of(hex("0102"))), decode('L', u8(0), bytes(hex("0102"))));
        assertEquals(new Reply.LobLocator(0xFFFF_FFFFL), decode('L', u8(1), u32(-1)));
        assertBroken(() -> decode('L', u8(2)));
    }

    /**
     * Verifies: [PROTOCOL Frames] a kind is read in the direction it travels, so a request's
     * kind the server never sends fails, as does a kind no message has, a body cut short and
     * bytes left after the last field.
     */
    @Test
    void aKindUnknownOrABodyMalformedFails() {
        for (char kind : new char[] {'Q', 'H', 'z', 'X', 0}) {
            assertBroken(() -> decode(kind));
        }
        assertBroken(() -> decode('K', u32(0), str("00000")));
        assertBroken(() -> decode('G', u8(0)));
        assertBroken(() -> decode('S', u8(0)));
    }

    /**
     * Verifies: [PROTOCOL Errors and notices] a malformed message fails the connection with
     * SQLCODE -919 and SQLSTATE 08006 and nothing else: bodies generated from a fixed seed, of
     * every kind, decode or fail so, never with another exception.
     */
    @Test
    void generatedBodiesFailOnlyAsTheConnection() {
        Random random = new Random(SEED);
        byte[] kinds = "CRPDBSKELNTGQz".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int round = 0; round < 20_000; round++) {
            byte[] body = new byte[random.nextInt(48)];
            random.nextBytes(body);
            if (random.nextBoolean() && body.length > 4) {
                body[random.nextInt(4)] = 0;
            }
            byte kind = kinds[random.nextInt(kinds.length)];
            try {
                ReplyCodec.decode(kind, body);
            } catch (TurborelException failure) {
                assertEquals(Broken.SQLCODE, failure.sqlcode(), failure.getMessage());
            }
        }
    }
}
