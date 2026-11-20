package com.turborel.jdbc.internal.codec;

import static com.turborel.jdbc.internal.codec.Fixture.assertMisuse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.turborel.jdbc.TurborelException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** UUID and XML values as a program makes and reads them ([CLIENT UUIDs], [CLIENT XML values]). */
class UuidXmlTest {
    /** The UUID of RFC 9562's example of version 4, as Turborel's tests take it. */
    private static final String SAMPLE = "919108f7-52d1-4320-9bac-f847db4148a8";

    /**
     * Verifies: [CLIENT UUIDs] a UUID parameter is made of its text, read as Turborel reads it:
     * hyphenated, braced, upper case, without hyphens and with a hyphen after any group of four
     * digits, its white space trimmed; a text of the wrong length, a stray hyphen or a digit not an
     * ASCII hexadecimal one writes none. Its text is written lower case and hyphenated.
     */
    @Test
    void uuidTextsAreReadInEachForm() {
        Value.Uuid sample = Value.Uuid.parse(SAMPLE);
        assertNotNull(sample);
        assertEquals(SAMPLE, sample.text());
        for (String form : List.of(
                "{919108f7-52d1-4320-9bac-f847db4148a8}",
                "919108F7-52D1-4320-9BAC-F847DB4148A8",
                "919108f752d143209bacf847db4148a8",
                " 9191-08f7-52d1-4320-9bac-f847-db41-48a8 ",
                "\u00A0" + SAMPLE + "\u3000",
                "\u000B\u2003" + SAMPLE + "\u2029")) {
            assertEquals(sample, Value.Uuid.parse(form), form);
        }
        for (String bad : List.of(
                "919108f7-52d1-4320-9bac-f847db4148a",
                "919108f7-52d1-4320-9bac-f847db4148a8-",
                "-919108f7-52d1-4320-9bac-f847db4148a8",
                "919108f7--52d1-4320-9bac-f847db4148a8",
                "919108f7-52d1-4320-9bac-f847db4148ag",
                "919-108f7-52d1-4320-9bac-f847db4148a8",
                "{919108f7-52d1-4320-9bac-f847db4148a8",
                "",
                "{}",
                "\u001C" + SAMPLE,
                "919108f7-52d1-4320-9bac-f847db4148a8a",
                "９19108f7-52d1-4320-9bac-f847db4148a8")) {
            assertNull(Value.Uuid.parse(bad), bad);
        }
    }

    /**
     * Verifies: [CLIENT UUIDs] a UUID read gives its 16 octets, most significant first, and a UUID
     * parameter is made of them; octets of another count are refused (SQLCODE -910).
     */
    @Test
    void aUuidIsItsOctets() throws TurborelException {
        Value.Uuid sample = Value.Uuid.parse(SAMPLE);
        assertNotNull(sample);
        byte[] octets = Fixture.hex("919108F752D143209BACF847DB4148A8");
        assertArrayEquals(octets, sample.octets());
        assertEquals(sample, Value.Uuid.ofOctets(octets));
        octets[0] = 0;
        assertEquals((byte) 0x91, sample.octets()[0], "copied, not shared");
        assertMisuse(() -> Value.Uuid.ofOctets(new byte[15]));
    }

    /**
     * Verifies: [CLIENT XML values] an XML value's nodes are parsed from its serialization as
     * CONTENT: several elements and text around them, after an XML declaration of version 1.1 if one
     * is there; the text, read whole, is what the value is.
     */
    @Test
    void anXmlValuesNodesAreParsed() throws TurborelException {
        Value.Xml order = new Value.Xml("<order no=\"18\"><item>jam</item></order>");
        DocumentFragment nodes = order.nodes();
        assertEquals(1, nodes.getChildNodes().getLength());
        Element element = (Element) nodes.getFirstChild();
        assertEquals("order", element.getTagName());
        assertEquals("18", element.getAttribute("no"));
        assertEquals("jam", element.getTextContent());
        DocumentFragment content = new Value.Xml("a<b/>c&amp;<d xmlns=\"urn:x\"/>").nodes();
        assertEquals(4, content.getChildNodes().getLength());
        assertEquals(Node.TEXT_NODE, content.getFirstChild().getNodeType());
        assertEquals("urn:x", content.getLastChild().getNamespaceURI());
        DocumentFragment v11 = new Value.Xml("<?xml version=\"1.1\"?><a>&#x1;</a>").nodes();
        assertEquals("\u0001", v11.getFirstChild().getTextContent());
        assertEquals(0, new Value.Xml("").nodes().getChildNodes().getLength());
    }

    /**
     * Verifies: [CLIENT XML values] a text that is not well-formed XML content, a document type
     * declaration among it, is refused (SQLCODE -301, SQLSTATE 2200N), so no external entity is
     * ever read.
     */
    @Test
    void textThatIsNotXmlContentIsRefused() {
        for (String bad : List.of(
                "<a>",
                "<a></b>",
                "<?xml version=\"1.0\"",
                "<!DOCTYPE a [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><a>&e;</a>",
                "<a>&e;</a>",
                "&")) {
            TurborelException refused = assertThrows(TurborelException.class, () -> new Value.Xml(bad).nodes(), bad);
            assertEquals(-301, refused.sqlcode(), bad);
            assertEquals("2200N", refused.getSQLState(), bad);
        }
    }
}
