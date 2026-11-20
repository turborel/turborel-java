package com.turborel.jdbc.internal.codec;

import static com.turborel.jdbc.internal.codec.Fixture.assertMisuse;
import static com.turborel.jdbc.internal.codec.Fixture.bytes;
import static com.turborel.jdbc.internal.codec.Fixture.cat;
import static com.turborel.jdbc.internal.codec.Fixture.hex;
import static com.turborel.jdbc.internal.codec.Fixture.str;
import static com.turborel.jdbc.internal.codec.Fixture.u32;
import static com.turborel.jdbc.internal.codec.Fixture.u64;
import static com.turborel.jdbc.internal.codec.Fixture.u8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.turborel.jdbc.TurborelException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every request the client sends, encoded ([PROTOCOL Requests]). */
class RequestTest {
    private static final List<Attribute> NONE = List.of();
    private static final List<Attribute> TRACE = List.of(new Attribute("traceparent", "00-1"));

    private static byte[] frame(char kind, byte[]... body) {
        byte[] whole = cat(body);
        return cat(u8(kind), u32(whole.length), whole);
    }

    /**
     * Verifies: [PROTOCOL A worked example] the Hello of a client that logs in by trust as ANN
     * and asks for ping is the page's 41 bytes, with no tail.
     */
    @Test
    void theWorkedExamplesHello() throws TurborelException {
        byte[] expected =
                hex("48 24000000 01000000 03000000414E4E 050000007472757374 00000000 01000000 0400000070696E67");
        assertArrayEquals(expected, new Request.Hello("ANN", "trust", Octets.EMPTY, List.of("ping"), NONE).encode());
    }

    /**
     * Verifies: [PROTOCOL A worked example] after Ready, a Ping is a header alone, and the Query
     * of VALUES 1 with ends set is the page's bytes.
     */
    @Test
    void theWorkedExamplesPingAndQuery() throws TurborelException {
        assertArrayEquals(hex("4700000000"), new Request.Ping().encode());
        assertArrayEquals(
                hex("51 0D000000 08000000 56414C5545532031 01"),
                new Request.Query("VALUES 1", true, NONE, false).encode());
    }

    /**
     * Verifies: [PROTOCOL Hello 'H'] Hello: version 1, user, mechanism, initial bytes,
     * capabilities, and its connection's attributes in tail tag 1.
     */
    @Test
    void helloCarriesItsAttributes() throws TurborelException {
        byte[] expected = frame(
                'H',
                u32(1),
                str("ANN"),
                str("scram-sha-256"),
                bytes(hex("6E2C2C")),
                u32(0),
                u8(1),
                u32(1),
                str("traceparent"),
                str("00-1"));
        assertArrayEquals(
                expected,
                new Request.Hello("ANN", "scram-sha-256", Octets.of(hex("6E2C2C")), List.of(), TRACE).encode());
    }

    /**
     * Verifies: [PROTOCOL Answer 'A'] Answer: bytes, the client's answer to a Challenge.
     */
    @Test
    void answer() throws TurborelException {
        assertArrayEquals(frame('A', bytes(hex("0102"))), new Request.Answer(Octets.of(hex("0102"))).encode());
    }

    /**
     * Verifies: [PROTOCOL Query 'Q'] Query: sql, ends flag, then tail 1 attributes and 2 multi,
     * in that order, each only where it says something.
     */
    @Test
    void queryCarriesItsTail() throws TurborelException {
        byte[] expected =
                frame('Q', str("SET X = 1; COMMIT"), u8(0), u8(1), u32(1), str("traceparent"), str("00-1"), u8(2));
        assertArrayEquals(expected, new Request.Query("SET X = 1; COMMIT", false, TRACE, true).encode());
        assertArrayEquals(frame('Q', str("X"), u8(0), u8(2)), new Request.Query("X", false, NONE, true).encode());
    }

    /**
     * Verifies: [PROTOCOL Prepare 'P'] Prepare: the client's number for the statement, then its
     * sql.
     */
    @Test
    void prepare() throws TurborelException {
        assertArrayEquals(frame('P', u32(7), str("SELECT 1")), new Request.Prepare(7, "SELECT 1").encode());
        assertMisuse(() -> new Request.Prepare(-1, "X").encode());
    }

    /**
     * Verifies: [PROTOCOL Execute 'E'] Execute: statement, values, rows (0 for all), ends, then
     * tail 1 attributes and 3 cursor, the cursor written only where it is not 0.
     */
    @Test
    void executeCarriesValuesAndItsTail() throws TurborelException {
        List<Value> values = List.of(new Value.Truth(true), Value.NULL);
        byte[] first = frame('E', u32(3), u32(2), u8(14), u8(1), u8(0), u32(0), u8(1));
        assertArrayEquals(first, new Request.Execute(3, values, 0, true, NONE, 0).encode());
        byte[] tailed = frame(
                'E', u32(3), u32(0), u32(50), u8(0), u8(1), u32(1), str("traceparent"), str("00-1"), u8(3), u32(2));
        assertArrayEquals(tailed, new Request.Execute(3, List.of(), 50, false, TRACE, 2).encode());
    }

    /**
     * Verifies: [PROTOCOL Fetch 'F'] Fetch: statement, rows, then tail 3 cursor; [PROTOCOL Close
     * 'C'] Close: statement, then tail 3 cursor; [PROTOCOL Deallocate 'D'] Deallocate: statement.
     */
    @Test
    void fetchCloseAndDeallocate() throws TurborelException {
        assertArrayEquals(frame('F', u32(1), u32(2)), new Request.Fetch(1, 2, 0).encode());
        assertArrayEquals(frame('F', u32(1), u32(0), u8(3), u32(5)), new Request.Fetch(1, 0, 5).encode());
        assertArrayEquals(frame('C', u32(1)), new Request.Close(1, 0).encode());
        assertArrayEquals(frame('C', u32(1), u8(3), u32(5)), new Request.Close(1, 5).encode());
        assertArrayEquals(frame('D', u32(9)), new Request.Deallocate(9).encode());
    }

    /**
     * Verifies: [PROTOCOL Lob 'L'] Lob, the request: 0 read with locator, from, count and the
     * characters flag; 1 create of a kind; 2 append; 3 free; 4 hold.
     */
    @Test
    void theLobRequests() throws TurborelException {
        assertArrayEquals(
                frame('L', u8(0), u32(4), u64(0), u64(262_144), u8(1)),
                new LobRequest.Read(4, 0, 262_144, true).encode());
        assertArrayEquals(frame('L', u8(1), u8(1)), new LobRequest.Create(LobKind.BINARY).encode());
        assertArrayEquals(
                frame('L', u8(2), u32(4), bytes(hex("C3"))), new LobRequest.Append(4, Octets.of(hex("C3"))).encode());
        assertArrayEquals(frame('L', u8(3), u32(4)), new LobRequest.Free(4).encode());
        assertArrayEquals(frame('L', u8(4), u32(4)), new LobRequest.Hold(4).encode());
    }

    /**
     * Verifies: [PROTOCOL Cancel 'Z'] Cancel: the key Ready gave, 16 bytes with no length;
     * [PROTOCOL Terminate 'X'] Terminate: a header alone.
     */
    @Test
    void cancelAndTerminate() throws TurborelException {
        byte[] key = new byte[16];
        java.util.Arrays.fill(key, (byte) 7);
        assertArrayEquals(cat(hex("5A10000000"), key), new Request.Cancel(CancelKey.of(key)).encode());
        assertArrayEquals(hex("5800000000"), new Request.Terminate().encode());
    }

    /**
     * Verifies: [PROTOCOL Describe 'Y'] Describe: what, 0 a statement or 1 a cursor, the
     * statement, then the cursor, 0 of a statement; a statement's of another cursor is refused.
     */
    @Test
    void describe() throws TurborelException {
        assertArrayEquals(frame('Y', u8(0), u32(2), u32(0)), new Request.Describe(false, 2, 0).encode());
        assertArrayEquals(frame('Y', u8(1), u32(2), u32(3)), new Request.Describe(true, 2, 3).encode());
        assertMisuse(() -> new Request.Describe(false, 2, 3).encode());
    }

    /**
     * Verifies: [PROTOCOL Reset 'W'] Reset is a header alone; [PROTOCOL ChangeUser 'U']
     * ChangeUser: user, mechanism, initial bytes.
     */
    @Test
    void resetAndChangeUser() throws TurborelException {
        assertArrayEquals(hex("5700000000"), new Request.Reset().encode());
        assertArrayEquals(
                frame('U', str("BOB"), str("trust"), bytes(new byte[0])),
                new Request.ChangeUser("BOB", "trust", Octets.EMPTY).encode());
    }

    /**
     * Verifies: [PROTOCOL CopyIn 'I'] CopyIn: table, columns, ends; [PROTOCOL CopyData 'J']
     * CopyData: a list of rows; [PROTOCOL CopyEnd 'N'] CopyEnd: abandon.
     */
    @Test
    void theCopyRequests() throws TurborelException {
        assertArrayEquals(
                frame('I', str("T"), u32(2), str("ID"), str("NAME"), u8(1)),
                new Request.CopyIn("T", List.of("ID", "NAME"), true).encode());
        List<List<Value>> rows = List.of(List.of(Value.NULL), List.of());
        assertArrayEquals(frame('J', u32(2), u32(1), u8(0), u32(0)), new Request.CopyData(rows).encode());
        assertArrayEquals(frame('N', u8(0)), new Request.CopyEnd(false).encode());
    }
}
