# Copy, Import and Export

[Contents](README.md)

## NAME

`Bulk`, `Copying`, `Source`, `Delimited`, `Import`: the low-level
client's copy, import and export, as turborel-client gives them

## SYNOPSIS

```java
package com.turborel.jdbc.internal.session;

public final class Bulk {                           // Connection.bulk()
    public enum Format { CSV, NATIVE }
    public Copying copyIn(String table, List<String> columns) throws TurborelException;
    public long copyRows(String table, List<String> columns, Iterable<List<Value>> rows) throws TurborelException;
    public long export(String sql, OutputStream out, Format format) throws TurborelException;
    public static Outcome.Rows readNative(InputStream input) throws TurborelException;
    public Import.Imported importRows(Source source, Import how, Consumer<Import.Rejected> rejected)
            throws TurborelException;
}

public final class Copying implements AutoCloseable {
    public static final int CHUNK_BYTES = 256 * 1024;
    public void send(List<Value> row) throws TurborelException;
    public long finish() throws TurborelException;
    public void abandon() throws TurborelException;
    public void close();
}

public interface Source {
    @Nullable List<Heading> headings() throws Failed;
    @Nullable Entry record() throws Failed;
    record Position(long record, @Nullable Long line) {}
    record Heading(String name, @Nullable DataType type) {}
    record Entry(List<Value> values, Position position) {}
    final class Failed extends TurborelException { public Position where(); }
}

public final class Delimited implements Source {
    public static final int DEFAULT_MAX_RECORD = 16 * 1024 * 1024;
    public Delimited(InputStream input, Dialect dialect);
    public record Dialect(byte delimiter, boolean quoted, boolean header, String nullText, int maxRecord) {
        public static Dialect csv();
        public static Dialect tsv();
        public Dialect withDelimiter(char delimiter) throws TurborelException;
        public Dialect withHeader();
        public Dialect withNull(String text);
        public Dialect withMaxRecord(int bytes);
    }
}

public record Import(String table, long batch, boolean skip) {
    public static final long DEFAULT_BATCH = 1000;
    public static Import into(String table);
    public record Imported(long rows, long rejected) {}
    public record Cause(@Nullable String input, @Nullable TurborelException sql) {}
    public record Rejected(Source.Position position, Cause cause) {}
    public static final class Stopped extends TurborelException {
        public @Nullable Source.Position where();
        public Cause cause();
        public long committed();
        public long rolledBack();
    }
}
```

These packages are not exported by the module (RULES.md D2).

## DESCRIPTION

**Copy.** Granted `copy`, `copyIn` begins a copy into a table, of the
columns named or all, its transaction's own under autocommit, else
under a savepoint of its own within the transaction. `send` sends rows
in CopyData messages of about `CHUNK_BYTES`, with no reply; `finish`
ends the copy, giving the rows inserted, or its failure, every row of it
undone, its detail naming the row, as `row 7 of the copy`. `abandon`, or
`close` before `finish`, undoes every row. While a copy is under way the
connection sends only its rows, its end and a ping. `copyRows` copies
the rows an iterable gives.

**Import.** `importRows` inserts a source's records into the table an
`Import` names: by copy where `copy` is granted, no row is to be skipped
and no column is a large object, a copy a batch; else through a prepared
INSERT of the source's headings, or the table's columns, a batch
committed at a time, `batch` rows, all at once if 0. Each string for a
character column is assigned as INSERT assigns it, so one too long is
refused; a field longer than `Lobs.INLINE_OCTETS` for a large object
column is sent by creating a large object. A row refused, or of too many
or too few fields, is skipped and given to `rejected` if `skip`, else
stops the import, the batch under way rolled back. The session must
have no transaction active.

**Sources.** A `Source` gives its headings once, then a record at a time,
holding no more than one. `Delimited` reads CSV, RFC 4180, or
tab-separated text, as its `Dialect` says: a delimiter, quoting, a
header, the text for null, and the longest record, past which a record
is refused rather than read on. A byte order mark beginning the input
is passed over.

**Export.** `export` runs a query and writes its rows as they come: as
CSV, a header of the columns' names then a record for each row, ended by
CR LF, a field quoted where it must be, a null empty, each value as
Turborel writes it; or as the protocol's own frames, a Description,
Rows and a Complete, which `readNative` reads back with every value as
it was.

## ERRORS

| SQLCODE | SQLSTATE | When |
|---|---|---|
| | the server's | `finish`: the table or a column not found, a row of too many or too few values, a value its column refuses, its detail naming the row |
| -910 | TB910 | A request other than the copy's rows, its end or a ping while a copy is under way; a delimiter not one ASCII character other than a quote or a line break |
| -301 | 22000 | A source's record that cannot be read, or an import that stops on one, saying where it began; an import begun with a transaction active, refused before any row |
| -301 | 22001 | An import's string too long for its column |
| -919 | 08006 | `copy` not granted, refused before anything is sent; `export` of a statement that is no query, or a write to its output that fails; native rows that are not, or cut short |

## EXAMPLES

A copy, and one refused at its fourth row:

```java
System.out.println("copied " + conn.bulk().copyRows("READINGS", List.of("ID", "SENSOR"), rows));
more.set(3, more.get(0));
try {
    conn.bulk().copyRows("READINGS", List.of(), more);
} catch (TurborelException e) {
    System.out.println(e.getSQLState() + ", " + e.diagnostics().get("DETAIL"));
}
```

```
copied 50000
23000, row 4 of the copy
```

An import of CSV, a title too long skipped:

```java
Delimited source = new Delimited(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
        Delimited.Dialect.csv().withHeader());
Import.Imported imported = conn.bulk().importRows(source, new Import("BOOKS", 1000, true),
        rejected -> System.out.println(rejected));
System.out.println(imported);
```

```
record 3, line 4: [ISO/IEC 9075:1992 §13.8 GR4b iii, §9.2 GR3e ii] a value of 47 characters, not spaces beyond 40, does not fit CHARACTER VARYING(40); skipped
Imported[rows=3, rejected=1]
```

The User's Guide's chapter
[Copying, Importing and Exporting](../user/07-copying.md) has more.

## STANDARD

[PROTOCOL CopyIn 'I'], [PROTOCOL CopyData 'J'], [PROTOCOL CopyEnd 'N'],
[PROTOCOL Conversations]; [CLIENT Copying and exporting],
[CLIENT Importing].

## SEE ALSO

[Large Objects](24-large-objects.md),
[Statements and Cursors](22-statements.md)
