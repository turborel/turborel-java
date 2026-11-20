package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.CharUnits;
import com.turborel.jdbc.internal.codec.DataType;
import com.turborel.jdbc.internal.codec.Failures;
import com.turborel.jdbc.internal.codec.Lob;
import com.turborel.jdbc.internal.codec.LobKind;
import com.turborel.jdbc.internal.codec.Parameter;
import com.turborel.jdbc.internal.codec.Value;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A character string assigned to a character string column as INSERT assigns it, as Turborel's
 * {@code value::assign} does ({@code src/value/types/character.rs}, {@code varying.rs}, {@code
 * lob.rs}): CHARACTER padded with spaces to its length, CHARACTER VARYING as it is, a CHARACTER
 * LARGE OBJECT held whole; one longer than the column, spaces apart, refused (SQLSTATE 22001), where
 * the cast of a parameter's value would cut it with a warning. So an import refuses a field too
 * long for its column, not cuts it.
 */
final class Assign {
    /** The rule by which INSERT assigns a value to its column. */
    private static final String INSERT_ASSIGNS = "ISO/IEC 9075:1992 §13.8 GR4b iii";

    /** The SQLSTATE of a string too long for its column: string data, right truncation. */
    private static final String RIGHT_TRUNCATION = "22001";

    private Assign() {}

    /**
     * Values as they are sent for parameters: a character string for a character string column
     * assigned to it, any other value as it is, for the server to cast.
     *
     * @param values a value for each parameter
     * @param parameters the parameters
     * @return the values to send
     * @throws TurborelException SQLSTATE 22001 for a string too long for its column
     */
    static List<Value> assigned(List<Value> values, List<Parameter> parameters) throws TurborelException {
        List<Value> sent = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Value v = values.get(i);
            DataType to = parameters.get(i).type();
            sent.add(v instanceof Value.Text text ? string(text.text(), to) : v);
        }
        return sent;
    }

    private static Value string(String s, DataType to) throws TurborelException {
        if (to instanceof DataType.CharacterType c) {
            return c.varying() ? varying(s, c) : fixed(s, c);
        }
        if (to instanceof DataType.LobType l && l.kind() == LobKind.CHARACTER) {
            return clob(s, l);
        }
        return new Value.Text(s);
    }

    private static Value fixed(String s, DataType.CharacterType c) throws TurborelException {
        int fits = (int) Math.min(c.length(), Integer.MAX_VALUE);
        long count = count(s, c.units());
        if (count <= fits) {
            return new Value.Text(s + " ".repeat((int) (fits - count)));
        }
        String kept = prefix(s, fits, c.units());
        if (spaces(s.substring(kept.length()))) {
            return new Value.Text(kept + " ".repeat((int) (fits - count(kept, c.units()))));
        }
        throw tooLong(count, fits, c.units(), "CHARACTER", "§9.2 GR3b ii");
    }

    private static Value varying(String s, DataType.CharacterType c) throws TurborelException {
        int most = (int) Math.min(c.length(), Integer.MAX_VALUE);
        long count = count(s, c.units());
        if (count <= most) {
            return new Value.Text(s);
        }
        String kept = prefix(s, most, c.units());
        if (spaces(s.substring(kept.length()))) {
            return new Value.Text(kept);
        }
        throw tooLong(count, most, c.units(), "CHARACTER VARYING", "§9.2 GR3e ii");
    }

    private static Value clob(String s, DataType.LobType l) throws TurborelException {
        long most = l.length();
        String kept = count(s, l.units()) <= most ? s : prefix(s, (int) Math.min(most, Integer.MAX_VALUE), l.units());
        if (!spaces(s.substring(kept.length()))) {
            String type = written("CHARACTER LARGE OBJECT", most, l.units());
            throw new TurborelException(
                    Failures.DATA_SQLCODE,
                    RIGHT_TRUNCATION,
                    "[" + INSERT_ASSIGNS
                            + "; ISO/IEC 9075-2:2023 §9.2 GR3e ii] a large object longer than " + type
                            + ", not padding beyond it, does not fit it");
        }
        return Lob.Whole.ofText(kept);
    }

    private static TurborelException tooLong(long count, int length, CharUnits units, String name, String clause) {
        String unit = units == CharUnits.OCTETS ? "octets" : "characters";
        return new TurborelException(
                Failures.DATA_SQLCODE,
                RIGHT_TRUNCATION,
                "[" + INSERT_ASSIGNS + ", " + clause
                        + "] a value of " + count + " " + unit + ", not spaces beyond " + length + ", does not fit "
                        + written(name, length, units));
    }

    /** A character type's name as Turborel writes it, as {@code CHARACTER(5)} or {@code (5 OCTETS)}. */
    private static String written(String name, long length, CharUnits units) {
        return units == CharUnits.OCTETS ? name + "(" + length + " OCTETS)" : name + "(" + length + ")";
    }

    private static long count(String s, CharUnits units) {
        return units == CharUnits.OCTETS ? s.getBytes(StandardCharsets.UTF_8).length : s.codePointCount(0, s.length());
    }

    /** The longest prefix of whole characters within {@code most} of the units. */
    private static String prefix(String s, int most, CharUnits units) {
        int at = 0;
        long taken = 0;
        while (at < s.length()) {
            int c = s.codePointAt(at);
            long size = units == CharUnits.OCTETS ? Character.toString(c).getBytes(StandardCharsets.UTF_8).length : 1;
            if (taken + size > most) {
                break;
            }
            taken += size;
            at += Character.charCount(c);
        }
        return s.substring(0, at);
    }

    private static boolean spaces(String rest) {
        return rest.chars().allMatch(c -> c == ' ');
    }
}
