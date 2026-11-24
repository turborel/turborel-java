package com.turborel.jdbc.internal.codec;

import static com.turborel.jdbc.internal.codec.Fixture.assertBroken;
import static com.turborel.jdbc.internal.codec.Fixture.cat;
import static com.turborel.jdbc.internal.codec.Fixture.reader;
import static com.turborel.jdbc.internal.codec.Fixture.str;
import static com.turborel.jdbc.internal.codec.Fixture.u32;
import static com.turborel.jdbc.internal.codec.Fixture.u64;
import static com.turborel.jdbc.internal.codec.Fixture.u8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.turborel.jdbc.TurborelException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** The composite fields and the tail ([PROTOCOL Composite fields], [PROTOCOL The tail]). */
class CompositeTest {
    private static final byte[] BOOLEAN = u8(14);

    /**
     * Verifies: [PROTOCOL Composite fields] a column is its named flag, its name if named, its
     * data type and its not null flag.
     */
    @Test
    void columns() throws TurborelException {
        assertEquals(
                new Column("ID", new DataType.BooleanType(), true),
                Column.read(reader(cat(u8(1), str("ID"), BOOLEAN, u8(1)))));
        assertEquals(
                new Column(null, new DataType.BooleanType(), false), Column.read(reader(cat(u8(0), BOOLEAN, u8(0)))));
        assertBroken(() -> Column.read(reader(cat(u8(2), BOOLEAN, u8(0)))));
    }

    /**
     * Verifies: [PROTOCOL Composite fields] a parameter is its varying flag, then its type, written
     * all the same.
     */
    @Test
    void parameters() throws TurborelException {
        assertEquals(
                new Parameter(true, new DataType.CharacterType(true, 0xFFFF_FFFFL, CharUnits.CHARACTERS)),
                Parameter.read(reader(cat(u8(1), u8(5), u32(-1), u8(0)))));
        assertEquals(new Parameter(false, new DataType.BooleanType()), Parameter.read(reader(cat(u8(0), BOOLEAN))));
    }

    /**
     * Verifies: [PROTOCOL Composite fields] a failure is its SQLCODE i32 as u32, SQLSTATE,
     * message, cited flag and rule, placed flag and position, active flag, then tail 7 to 15.
     */
    @Test
    void failures() throws TurborelException {
        byte[] cited = cat(
                u32(-103),
                str("42000"),
                str("table ANN.NOPE is not defined"),
                u8(1),
                str("X3.135-1986 §5.7 SR3b"),
                u8(1),
                u64(14),
                u8(0));
        Failure failure = Failure.read(reader(cited));
        assertEquals(
                new Failure(-103, "42000", "table ANN.NOPE is not defined", "X3.135-1986 §5.7 SR3b", 14L, false, null),
                failure);
        byte[] plain =
                cat(u32(-911), str("TB911"), str(""), u8(0), u8(0), u8(1), u8(7), str("ERROR"), u8(12), str("T"));
        Failure diagnosed = Failure.read(reader(plain));
        assertNull(diagnosed.rule());
        assertNull(diagnosed.position());
        Diagnostics expected = Diagnostics.of(Map.of(DiagnosticField.SEVERITY, "ERROR", DiagnosticField.TABLE, "T"));
        assertEquals(expected, diagnosed.diagnostics());
        assertEquals(
                expected.hashCode(),
                Objects.requireNonNull(diagnosed.diagnostics()).hashCode());
        assertEquals("T", expected.get(DiagnosticField.TABLE));
        assertNull(expected.get(DiagnosticField.HINT));
        assertEquals("Diagnostics{SEVERITY=ERROR, TABLE=T}", expected.toString());
        assertNotEquals(expected, Diagnostics.NONE);
        assertNotEquals(expected, "T");
    }

    /**
     * Verifies: [PROTOCOL The tail] tags 7 to 15, a failure's and a notice's diagnostics, each a
     * string under its tag, in increasing order; one out of order, repeated, or not taken fails.
     */
    @Test
    void diagnosticsTagsInOrder() throws TurborelException {
        byte[] all = new byte[0];
        for (DiagnosticField field : DiagnosticField.values()) {
            all = cat(all, u8(field.tag()), str(field.name()));
        }
        Diagnostics every = Objects.requireNonNull(Diagnostics.read(reader(all)));
        assertEquals(DiagnosticField.values().length, every.fields().size());
        assertEquals("DATA_TYPE", every.get(DiagnosticField.DATA_TYPE));
        assertNull(Diagnostics.read(reader(new byte[0])));
        assertBroken(() -> Diagnostics.read(reader(cat(u8(8), str("d"), u8(7), str("s")))));
        assertBroken(() -> Diagnostics.read(reader(cat(u8(8), str("d"), u8(8), str("d")))));
        assertBroken(() -> Diagnostics.read(reader(cat(u8(16), u32(0)))));
        assertBroken(() -> Diagnostics.read(reader(cat(u8(6), u64(0)))));
    }

    /**
     * Verifies: [PROTOCOL The tail] tag 1, attributes: a count, then each name and value; written
     * only where there are any, at most 32, a name of 1 to 64 bytes, a value of at most 1024, more
     * refused before it is sent with the connection failure the server gives.
     */
    @Test
    void attributesAreBounded() throws TurborelException {
        Writer none = new Writer();
        Attribute.write(none, List.of());
        assertArrayEquals(new byte[0], none.toByteArray());
        Writer one = new Writer();
        Attribute.write(one, List.of(new Attribute("application", "app")));
        assertArrayEquals(cat(u8(1), u32(1), str("application"), str("app")), one.toByteArray());
        List<Attribute> most = new ArrayList<>();
        for (int i = 0; i < Attribute.MAX_ATTRIBUTES; i++) {
            most.add(new Attribute("n".repeat(Attribute.MAX_NAME), "v".repeat(Attribute.MAX_VALUE)));
        }
        Attribute.write(new Writer(), most);
        List<Attribute> more = new ArrayList<>(most);
        more.add(new Attribute("n", "v"));
        assertBroken(() -> Attribute.write(new Writer(), more));
        assertBroken(() -> Attribute.write(new Writer(), List.of(new Attribute("", "v"))));
        assertBroken(() -> Attribute.write(new Writer(), List.of(new Attribute("n".repeat(65), "v"))));
        assertBroken(() -> Attribute.write(new Writer(), List.of(new Attribute("n", "v".repeat(1025)))));
    }
}
