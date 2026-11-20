package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.io.IOException;
import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The nodes of an XML value's serialization as CONTENT ([CLIENT XML values]), parsed by the JDK's
 * own parser: a document's content, which may hold several elements and text around them, after
 * an XML declaration of version 1.0 or 1.1 if the server wrote one. A document type declaration,
 * which content holds none of, and so any external entity, is refused.
 */
final class XmlNodes {
    /** The SQLSTATE of a string that is not well-formed XML content: invalid XML content. */
    static final String INVALID_XML_CONTENT = "2200N";

    /** What begins an XML declaration. */
    private static final String DECLARATION = "<?xml";

    /** What ends one. */
    private static final String DECLARATION_END = "?>";

    /** The element that holds the content while it is parsed, of no namespace. */
    private static final String HOLDER = "content";

    private XmlNodes() {}

    /**
     * The nodes a serialization holds, in order.
     *
     * @param serialization the serialization as CONTENT
     * @return its nodes, in a fragment of their own
     * @throws TurborelException SQLSTATE 2200N if it is not well-formed XML content (SQLCODE -301)
     */
    static DocumentFragment of(String serialization) throws TurborelException {
        String declaration = "";
        String content = serialization;
        if (serialization.startsWith(DECLARATION)) {
            int end = serialization.indexOf(DECLARATION_END);
            if (end < 0) {
                throw invalid("its XML declaration is not closed");
            }
            declaration = serialization.substring(0, end + DECLARATION_END.length());
            content = serialization.substring(end + DECLARATION_END.length());
        }
        String held = declaration + "<" + HOLDER + ">" + content + "</" + HOLDER + ">";
        try {
            Document document = builder().parse(new InputSource(new StringReader(held)));
            Element holder = document.getDocumentElement();
            DocumentFragment nodes = document.createDocumentFragment();
            while (holder.getFirstChild() != null) {
                nodes.appendChild(holder.getFirstChild());
            }
            return nodes;
        } catch (SAXException | IOException e) {
            throw invalid(String.valueOf(e.getMessage()));
        }
    }

    private static DocumentBuilder builder() throws TurborelException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            return builder;
        } catch (ParserConfigurationException e) {
            throw invalid("the JDK's parser cannot be made: " + e.getMessage());
        }
    }

    private static TurborelException invalid(String why) {
        return new TurborelException(
                Failures.DATA_SQLCODE,
                INVALID_XML_CONTENT,
                "[ISO/IEC 9075-14:2006 draft §10.16 GR6a ii] the string is not well-formed XML content: " + why);
    }
}
