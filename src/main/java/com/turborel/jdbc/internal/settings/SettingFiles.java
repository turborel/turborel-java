package com.turborel.jdbc.internal.settings;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.WhiteSpace;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The files connection settings come from ([CLIENT The service file], [CLIENT The password file]):
 * a service file of named sections of settings, and a password file of passwords by server and
 * user. As Turborel's {@code client/src/settings/files.rs}.
 *
 * <p>A service file holds sections, each {@code [<name>]} on a line of its own, then lines {@code
 * <key> = <value>}, each key a setting's name but {@code service} and {@code service_file}. A line
 * beginning with {@code #} is a comment; blank lines are skipped. A password file holds lines
 * {@code <host>:<port>:<user>:<password>}, a field {@code *} matching anything, {@code \} taking
 * the character after it as it is, so {@code \:} is a colon and {@code \\} a backslash; the first
 * line that matches is taken, and a line of fewer fields is skipped.
 *
 * <p>A password file should be readable by its owner alone. The client checks only that it is a
 * regular file, as the reference does on every platform, and its owner answers for the rest.
 */
final class SettingFiles {
    /** What begins a comment line, in either file. */
    private static final String COMMENT = "#";

    /** What matches any value in a password file. */
    private static final String ANY = "*";

    /** What escapes the character after it in a password file. */
    private static final int ESCAPE = '\\';

    /** What separates a password file's fields. */
    private static final int SEPARATOR = ':';

    /** The fields of a password file's line. */
    private static final int FIELDS = 4;

    /** The field of a password file's line that is its user. */
    private static final int USER = 2;

    /** The field of a password file's line that is its password. */
    private static final int PASSWORD = FIELDS - 1;

    private SettingFiles() {}

    /**
     * The lines of {@code text}, as Rust's {@code str::lines} gives them: split at each line feed,
     * a carriage return before it dropped, and no line after a final line feed.
     */
    static List<String> lines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            int next = end < 0 ? text.length() : end + 1;
            String line = text.substring(start, end < 0 ? text.length() : end);
            lines.add(end >= 0 && line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
            start = next;
        }
        return lines;
    }

    /**
     * The settings of the section {@code name} of the service file {@code text}, read from {@code
     * file}.
     *
     * @param text the file's text
     * @param name the section
     * @param file the file, as its failures name it
     * @return the section's settings
     * @throws TurborelException SQLSTATE 08001 if a line is malformed or sets no setting, or there
     *     is no section {@code name}
     */
    static Options service(String text, String name, Path file) throws TurborelException {
        @Nullable Options found = null;
        @Nullable String current = null;
        List<String> lines = lines(text);
        for (int i = 0; i < lines.size(); i++) {
            int n = i + 1;
            String line = WhiteSpace.trim(lines.get(i));
            if (line.isEmpty() || line.startsWith(COMMENT)) {
                continue;
            }
            if (line.startsWith("[")) {
                if (!line.endsWith("]")) {
                    throw at(file, n, "a section's name ends with ]");
                }
                current = WhiteSpace.trim(line.substring(1, line.length() - 1));
                continue;
            }
            if (current == null) {
                throw at(file, n, "a setting comes before any section");
            }
            int equals = line.indexOf('=');
            if (equals < 0) {
                throw at(file, n, "a setting is <key> = <value>");
            }
            String key = WhiteSpace.trim(line.substring(0, equals));
            if (key.equals(Options.SERVICE) || key.equals(Options.SERVICE_FILE)) {
                throw at(file, n, "a service cannot set " + key);
            }
            if (!Options.KEYS.contains(key)) {
                throw at(file, n, key + " is no connection setting");
            }
            Options options = new Options().set(key, WhiteSpace.trim(line.substring(equals + 1)));
            if (current.equals(name)) {
                found = options.over(found == null ? new Options() : found);
            }
        }
        if (found == null) {
            throw Invalid.setting("[CLIENT The service file] " + file + " has no service " + name);
        }
        return found;
    }

    private static TurborelException at(Path file, int n, String why) {
        return Invalid.setting("[CLIENT The service file] " + file + " line " + n + ": " + why);
    }

    /**
     * A field of a password file's line, unescaped, and whether it was written with no escape.
     *
     * @param value the field
     * @param plain whether no escape was in it
     */
    private record Field(String value, boolean plain) {
        /** Whether it matches {@code wanted}: a {@code *} written plain matches anything. */
        boolean matches(String wanted) {
            return (plain && value.equals(ANY)) || value.equals(wanted);
        }
    }

    /** The fields of the password file's line {@code line}: the first three split at each colon not escaped, the fourth the rest. */
    private static List<Field> fields(String line) {
        List<Field> fields = new ArrayList<>(FIELDS);
        StringBuilder field = new StringBuilder();
        boolean escaped = false;
        boolean plain = true;
        for (int i = 0; i < line.length(); i += Character.charCount(line.codePointAt(i))) {
            int c = line.codePointAt(i);
            if (escaped) {
                field.appendCodePoint(c);
                escaped = false;
                plain = false;
            } else if (c == ESCAPE) {
                escaped = true;
            } else if (c == SEPARATOR && fields.size() < PASSWORD) {
                fields.add(new Field(field.toString(), plain));
                field.setLength(0);
                plain = true;
            } else {
                field.appendCodePoint(c);
            }
        }
        fields.add(new Field(field.toString(), plain));
        return fields;
    }

    /**
     * The password the password file {@code text} gives {@code user} at {@code host} and {@code
     * port}: that of its first line matching all three.
     *
     * @param text the file's text
     * @param host the host's name, or a local socket's path
     * @param port the port, as text
     * @param user the user
     * @return the password, or none
     */
    static @Nullable String password(String text, String host, String port, String user) {
        for (String line : lines(text)) {
            if (WhiteSpace.trimStart(line).startsWith(COMMENT)) {
                continue;
            }
            List<Field> f = fields(line);
            if (f.size() == FIELDS
                    && f.get(0).matches(host)
                    && f.get(1).matches(port)
                    && f.get(USER).matches(user)) {
                return f.get(PASSWORD).value();
            }
        }
        return null;
    }

    /**
     * The text of the file at {@code path}, if it can be read and is a regular file (a link
     * followed to one); none otherwise.
     *
     * @param path the file
     * @return its text, or none
     */
    static @Nullable String readRegular(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
