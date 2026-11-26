package com.turborel.jdbc.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.auth.Trust;
import com.turborel.jdbc.internal.codec.FrameHeader;
import com.turborel.jdbc.internal.codec.Value;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which request carries a copy's rows, over a connection the test scripts ([CLIENT Copying and exporting]). */
class CopyingTest {
    private static final List<Value> TEXT = List.of(new Value.Text("1"), Value.NULL);
    private static final List<Value> TYPED = List.of(new Value.Exact(BigInteger.TWO, 0), new Value.Text("b"));

    /** The kinds of the frames the client wrote after Hello, in order, of a copy of the rows given. */
    private static String sent(List<String> granted, List<String> columns, List<List<Value>> rows)
            throws TurborelException {
        Script stream = new Script(Script.cat(Script.ready(granted), Script.complete()));
        Connection c = Connection.open(stream, "ann", new Trust(), Offer.none());
        int hello = stream.writtenSize();
        try (Copying copying = c.bulk().copyIn("T", columns)) {
            for (List<Value> row : rows) {
                copying.send(row);
            }
            copying.finish();
        }
        ByteBuffer b = ByteBuffer.wrap(stream.written()).order(ByteOrder.LITTLE_ENDIAN);
        b.position(hello);
        StringBuilder kinds = new StringBuilder();
        while (b.hasRemaining()) {
            kinds.append((char) b.get());
            int length = b.getInt();
            b.position(b.position() + length);
        }
        assertEquals(FrameHeader.BYTES, Byte.BYTES + Integer.BYTES);
        return kinds.toString();
    }

    /**
     * Verifies: [CLIENT Copying and exporting] granted {@code columns}, rows of text and nulls go by
     * column in a CopyColumns and any other row by row in a CopyData, the rows before it sent first,
     * so one copy mixes the two in the rows' order ([PROTOCOL CopyColumns 'O']).
     */
    @Test
    void textRowsGoByColumnWhereGranted() throws TurborelException {
        List<String> both = List.of(Capability.COPY, Capability.COLUMNS);
        assertEquals("IOJON", sent(both, List.of("A", "B"), List.of(TEXT, TEXT, TYPED, TEXT)));
        assertEquals("IJN", sent(both, List.of("A", "B"), List.of(List.of(new Value.Text("1")))), "a row too short");
    }

    /**
     * Verifies: [CLIENT Copying and exporting] rows go by row in CopyData where {@code columns} is
     * not granted, or where the copy's width is not known, every column of the table copied
     * ([PROTOCOL CopyIn 'I']: until CopyEnd, only CopyData, CopyColumns, CopyEnd and Ping).
     */
    @Test
    void rowsGoByRowOtherwise() throws TurborelException {
        assertEquals("IJN", sent(List.of(Capability.COPY), List.of("A", "B"), List.of(TEXT, TYPED, TEXT)));
        assertEquals("IJN", sent(List.of(Capability.COPY, Capability.COLUMNS), List.of(), List.of(TEXT, TEXT)));
    }
}
