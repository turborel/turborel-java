# Copying, Importing and Exporting

[Contents](README.md)

Rows move in bulk three ways: a copy sends them into a table with no
round trip but its end; an import reads them from a source, such as CSV
text; an export writes a query's rows as they come. This chapter tells
how, with the low-level client and the table of
[Large Objects](06-large-objects.md).

## Copying rows into a table

A connection's `bulk().copyIn` begins a copy into a table, of the
columns named, or all of them; its `send` sends rows, many to a message,
with no reply each; its `finish` gives the rows inserted. `copyRows`
copies the rows an iterable gives. The server assigns each value to its
column as INSERT does, so a string too long is refused, not cut. A copy
is all or nothing: a row refused undoes every row of it, its failure's
detail naming the row. Under autocommit the copy is its own transaction;
otherwise it runs within the transaction under a savepoint of its own,
so a copy that fails keeps what the transaction did before. A copy
closed before it finishes is abandoned. While one is under way the
connection sends nothing but its rows, its end and a ping; any other
request is the program's misuse (SQLSTATE TB910).

Rows of text go faster. Where the server grants `columns`
*(extension)*, a copy that names its columns sends each row whose values
are all character strings or nulls by column, as the server takes it
apart to write it, and the server casts each cell to its column's type,
as it casts a string in a row: `"42"` into an INTEGER column is 42, and
a string too long is refused, not cut. Other rows go by row in the same
copy, in their order. An import by copy, whose fields are text, goes by
column too.

```java
conn.statements().query("CREATE TABLE READINGS (ID INTEGER PRIMARY KEY, SENSOR VARCHAR(10))");
conn.statements().query("COMMIT");
List<List<Value>> rows = new ArrayList<>();
for (int i = 1; i <= 50_000; i++) {
    rows.add(List.of(new Value.Exact(BigInteger.valueOf(i), 0), new Value.Text("s" + (i % 7))));
}
conn.setAutocommit(true);
System.out.println("copied " + conn.bulk().copyRows("READINGS", List.of("ID", "SENSOR"), rows));
List<List<Value>> more = new ArrayList<>();
for (int i = 50_001; i <= 50_010; i++) {
    more.add(List.of(new Value.Exact(BigInteger.valueOf(i), 0), new Value.Text("s0")));
}
more.set(3, more.get(0));
try {
    conn.bulk().copyRows("READINGS", List.of(), more);
} catch (TurborelException e) {
    System.out.println(e.getSQLState() + ", " + e.diagnostics().get("DETAIL"));
    System.out.println("rows kept of the second copy: " + ((com.turborel.jdbc.internal.session.Outcome.Rows)
            conn.statements().query("SELECT COUNT(*) FROM READINGS WHERE ID > 50000")).rows());
}
```

```
copied 50000
23000, row 4 of the copy
rows kept of the second copy: [[Exact[units=0, scale=0]]]
```

## Importing

`bulk().importRows` inserts a `Source`'s records into a table. A source
gives its headings, the columns it fills, if it knows them, and then a
record at a time, so memory holds one record whatever the source's
size. `Delimited` reads CSV and tab-separated text: a header, a
delimiter and a text for null as its `Dialect` says, a field in quotes
holding commas, quotes written twice and line breaks.

An `Import` names the table, how many rows are committed at a time,
1000 by default or 0 for all at once, and whether a row that fails is
skipped, and given to the program, rather than ending the import. The
session must have no transaction active, as an import commits its
batches. Where the server grants `copy`, no row is to be skipped, and no
column is a large object, the import goes by copy, with no round trip a
row; otherwise by a prepared INSERT. A string too long for its column
is refused, not cut, and a field longer than 64 KiB for a large object
column is sent by creating a large object. An import that stops throws
`Import.Stopped`, saying where its record began, why, and how many rows
were committed and rolled back.

```java
String csv = "ID,TITLE\n10,Emma\n11,\"Persuasion, a novel\"\n12,\"a title far longer than forty characters, it is\"\n13,Sanditon\n";
try (Connection conn = Connect.tcp("127.0.0.1", 17434, "ann", new Trust())) {
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

## Exporting

`bulk().export` runs a query and writes its rows to an `OutputStream` as
they come, a batch at a time, so memory holds a batch whatever the
result's size. As CSV, it writes a header of the columns' names, then a
record for each row, ended by CR LF, a field quoted where it holds a
comma, a quote, a line break or white space at either end, a null an
empty field, an empty string `""`; each value as Turborel writes it. As
native rows, it writes the protocol's own frames, which
`Bulk.readNative` reads back with every value as it was, of whatever
type.

```java
ByteArrayOutputStream csv = new ByteArrayOutputStream();
long rows = conn.bulk().export("SELECT ID, TITLE FROM BOOKS ORDER BY ID", csv, Bulk.Format.CSV);
System.out.print(csv.toString(StandardCharsets.UTF_8));
System.out.println(rows + " rows");
ByteArrayOutputStream frames = new ByteArrayOutputStream();
conn.bulk().export("SELECT ID, TITLE FROM BOOKS ORDER BY ID", frames, Bulk.Format.NATIVE);
Outcome.Rows back = Bulk.readNative(new ByteArrayInputStream(frames.toByteArray()));
System.out.println(back.rows());
```

```
ID,TITLE
1,Long
2,Short
2 rows
[[Exact[units=1, scale=0], Text[text=Long]], [Exact[units=2, scale=0], Text[text=Short]]]
```

The classes are told in full in the API Reference,
[Copy, Import and Export](../client/25-copy.md).
