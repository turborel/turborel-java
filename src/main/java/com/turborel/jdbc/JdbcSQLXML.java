package com.turborel.jdbc;

import com.turborel.jdbc.internal.codec.Value;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLXML;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import org.jspecify.annotations.Nullable;

/**
 * An XML value ([DESIGN] D2): of a row, read once, as text, a stream, or a DOM of its nodes, parsed
 * with no document type declaration; or one the program made, written once, as text or a stream,
 * and sent as its serialization.
 */
final class JdbcSQLXML implements SQLXML {
    private @Nullable String text;
    private @Nullable StringWriter writing;
    private boolean freed;

    /** An XML value of a row, of {@code serialization}. */
    JdbcSQLXML(String serialization) {
        this.text = serialization;
    }

    /** An XML value the program writes. */
    JdbcSQLXML() {
        this.text = null;
    }

    private String readable() throws SQLException {
        if (freed) {
            throw Errors.misuse("the SQLXML is freed");
        }
        StringWriter written = writing;
        if (written != null) {
            text = written.toString();
            writing = null;
        }
        String value = text;
        if (value == null) {
            throw Errors.misuse("the SQLXML has no value written");
        }
        return value;
    }

    private StringWriter writable() throws SQLException {
        if (freed || text != null || writing != null) {
            throw Errors.misuse("an SQLXML is written once, before it is read");
        }
        StringWriter writer = new StringWriter();
        writing = writer;
        return writer;
    }

    @Override
    public String getString() throws SQLException {
        return readable();
    }

    @Override
    public InputStream getBinaryStream() throws SQLException {
        return new ByteArrayInputStream(readable().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Reader getCharacterStream() throws SQLException {
        return new StringReader(readable());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Source> T getSource(@Nullable Class<T> sourceClass) throws SQLException {
        String value = readable();
        if (sourceClass == null || sourceClass == StreamSource.class) {
            return (T) new StreamSource(new StringReader(value));
        }
        if (sourceClass == DOMSource.class) {
            return (T) new DOMSource(new Value.Xml(value).nodes());
        }
        throw Errors.unsupported("an SQLXML as a " + sourceClass.getName());
    }

    @Override
    public void setString(String value) throws SQLException {
        writable().write(value);
    }

    @Override
    public OutputStream setBinaryStream() throws SQLException {
        return new Octets(writable());
    }

    @Override
    public Writer setCharacterStream() throws SQLException {
        return writable();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Result> T setResult(@Nullable Class<T> resultClass) throws SQLException {
        if (resultClass == null || resultClass == StreamResult.class) {
            return (T) new StreamResult(writable());
        }
        throw Errors.unsupported("an SQLXML set by a " + resultClass.getName());
    }

    /** The value it is sent as. */
    Value value() throws SQLException {
        return new Value.Xml(readable());
    }

    @Override
    public void free() {
        freed = true;
    }

    /** The octets written to an XML value, in UTF-8, its text as the stream closes. */
    private static final class Octets extends ByteArrayOutputStream {
        private final StringWriter writer;

        Octets(StringWriter writer) {
            this.writer = writer;
        }

        @Override
        public void close() {
            writer.write(toString(StandardCharsets.UTF_8));
        }
    }
}
