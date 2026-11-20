package com.turborel.jdbc.internal.settings;

import com.turborel.jdbc.TurborelException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * A connection URI ([CLIENT The connection URI]), read into {@link Options}:
 *
 * <pre>
 * turborel://[&lt;user&gt;[:&lt;password&gt;]@][&lt;host&gt;[:&lt;port&gt;][,&lt;host&gt;[:&lt;port&gt;]]...][/][?&lt;key&gt;=&lt;value&gt;[&amp;&lt;key&gt;=&lt;value&gt;]...]
 * </pre>
 *
 * <p>Its user, password, hosts and values are percent-encoded (RFC 3986 §2.1), so a host {@code
 * %2Frun%2Fturborel.sock} names a Unix socket, and an IPv6 address is written in brackets (RFC
 * 3986 §3.2.2). Each key is a setting's name ({@link Options#KEYS}). A server serves one database,
 * so the URI names none, and its path is empty, or {@code /} alone. As Turborel's {@code
 * client/src/settings/uri.rs}.
 */
public final class Uri {
    /** The scheme a connection URI begins with. */
    public static final String SCHEME = "turborel://";

    /** The radix of a percent-encoded octet's digits. */
    private static final int HEX = 16;

    /** The characters of a percent-encoded octet, {@code %} and two digits. */
    private static final int ESCAPED = 3;

    /** What {@link #digit} gives where no digit is. */
    private static final int NONE = -1;

    /** What {@code String.split} is given to keep the empty strings after the last separator. */
    private static final int KEEP_EMPTY = -1;

    private Uri() {}

    /**
     * {@code text} percent-decoded (RFC 3986 §2.1), as UTF-8.
     *
     * @param text the text
     * @return it decoded
     * @throws TurborelException SQLSTATE 08001 if a {@code %} is not followed by two hexadecimal
     *     digits, or the octets are not UTF-8
     */
    static String decode(String text) throws TurborelException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream octets = new ByteArrayOutputStream(bytes.length);
        int i = 0;
        while (i < bytes.length) {
            if (bytes[i] != '%') {
                octets.write(bytes[i]);
                i++;
                continue;
            }
            int high = digit(bytes, i + 1);
            int low = digit(bytes, i + ESCAPED - 1);
            if (high < 0 || low < 0) {
                throw Invalid.setting(
                        "[CLIENT The connection URI] " + text + " has a % without two hexadecimal digits");
            }
            octets.write(high * HEX + low);
            i += ESCAPED;
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(octets.toByteArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            throw Invalid.setting("[CLIENT The connection URI] " + text + " is not UTF-8 once decoded");
        }
    }

    /** The value of the hexadecimal digit at {@code at} of {@code bytes}, or a negative number if none is there. */
    private static int digit(byte[] bytes, int at) {
        return at < bytes.length ? Character.digit(bytes[at], HEX) : NONE;
    }

    /**
     * The settings the connection URI {@code uri} gives.
     *
     * @param uri the URI
     * @return its settings
     * @throws TurborelException SQLSTATE 08001 if it is not one
     */
    static Options options(String uri) throws TurborelException {
        if (!uri.startsWith(SCHEME)) {
            throw Invalid.setting("[CLIENT The connection URI] a connection URI begins with " + SCHEME);
        }
        String rest = uri.substring(SCHEME.length());
        int mark = rest.indexOf('?');
        String before = mark < 0 ? rest : rest.substring(0, mark);
        String query = mark < 0 ? "" : rest.substring(mark + 1);
        int slash = before.indexOf('/');
        String authority = slash < 0 ? before : before.substring(0, slash);
        String path = slash < 0 ? "" : before.substring(slash + 1);
        if (!path.isEmpty()) {
            throw Invalid.setting(
                    "[CLIENT The connection URI] a connection URI names no database, so has no path, not /" + path);
        }
        Options options = new Options();
        int at = authority.lastIndexOf('@');
        String hosts = at < 0 ? authority : authority.substring(at + 1);
        if (at > 0) {
            userinfo(options, authority.substring(0, at));
        }
        if (!hosts.isEmpty()) {
            options.set(Options.HOST, decode(hosts));
        }
        for (String pair : query.split("&", KEEP_EMPTY)) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            if (equals < 0) {
                throw Invalid.setting("[CLIENT The connection URI] " + pair + " in a connection URI has no =");
            }
            options.set(decode(pair.substring(0, equals)), decode(pair.substring(equals + 1)));
        }
        return options;
    }

    /** Sets the user and password of a URI's {@code <user>[:<password>]}. */
    private static void userinfo(Options options, String userinfo) throws TurborelException {
        int colon = userinfo.indexOf(':');
        options.set(Options.USER, decode(colon < 0 ? userinfo : userinfo.substring(0, colon)));
        if (colon >= 0) {
            options.set(Options.PASSWORD, decode(userinfo.substring(colon + 1)));
        }
    }
}
