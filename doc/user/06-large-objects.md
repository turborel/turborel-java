# Large Objects

[Contents](README.md)

A CHARACTER LARGE OBJECT or a BINARY LARGE OBJECT may be far longer than
a program wants to hold at once. This chapter tells how a program reads
one, whole or by its locator, a range or a stream at a time; writes one
a piece at a time to pass as a parameter; and how long a locator lasts.
The examples use the low-level client, as [Connecting](02-connecting.md)
does, and a table made so:

```sql
CREATE SCHEMA AUTHORIZATION ANN;
CREATE TABLE BOOKS (ID INTEGER PRIMARY KEY, TITLE VARCHAR(40), BODY CLOB(1M));
COMMIT;
```

## Writing one a piece at a time

A connection's `lobs().create` begins a new large object, its `Writer`
an `OutputStream`: what is written goes to the server in appends of at
most 256 KiB, a character large object's cut where a character ends, so
a character split between two writes is sent whole. Its octets must be
UTF-8; octets that are not are refused as they are written (SQLSTATE
22021). `finish` gives the value, a `Lob.Locator`, to pass as a
parameter, valid until its transaction ends. A short value may go whole
instead, as `Lob.Whole.ofText`.

```java
Statements statements = conn.statements();
Lob.Locator body;
try (Lobs.Writer writer = conn.lobs().create(LobKind.CHARACTER)) {
    for (int chapter = 1; chapter <= 2000; chapter++) {
        writer.write(("Chapter " + chapter + ": a café by the sea.\n").getBytes(StandardCharsets.UTF_8));
    }
    body = writer.finish();
}
System.out.println(body.octets() + " octets, " + body.characters() + " characters");
statements.execute(statements.prepare("INSERT INTO BOOKS VALUES (?, ?, ?)"), List.of(
        new Value.Exact(BigInteger.ONE, 0), new Value.Text("Long"), body));
statements.execute(statements.prepare("INSERT INTO BOOKS VALUES (?, ?, ?)"), List.of(
        new Value.Exact(BigInteger.TWO, 0), new Value.Text("Short"), Lob.Whole.ofText("Once.")));
statements.query("COMMIT");
```

```
66893 octets, 64893 characters
```

## Reading one

A large object of at most the server's SERVER.LOB_INLINE_BYTES, 64 KiB by
default, comes whole in its row, a `Lob.Whole`; a longer one by its
locator and its lengths, a `Lob.Locator`. `lobs().read` reads a range of
either, by octets, or of a character large object by its characters,
whole ones, at most 256 KiB at a time; `lobs().reader` gives its octets
as an `InputStream`, read a chunk at a time.

```java
List<List<Value>> rows = ((Outcome.Rows) conn.statements().query("SELECT BODY FROM BOOKS ORDER BY ID")).rows();
Lob.Locator body = (Lob.Locator) rows.get(0).get(0);
Lob.Whole once = (Lob.Whole) rows.get(1).get(0);
System.out.println("by locator: " + body.characters() + " characters; whole: " + once.octets().length() + " octets");
byte[] range = conn.lobs().read(body, 10, 19, true);
System.out.println("characters 10 to 28: " + new String(range, StandardCharsets.UTF_8));
try (InputStream in = conn.lobs().reader(body)) {
    System.out.println("streamed: " + in.readAllBytes().length + " octets");
}
```

```
by locator: 64893 characters; whole: 5 octets
characters 10 to 28:  a café by the sea.
streamed: 66893 octets
```

## How long a locator lasts

A locator is valid until the transaction it was given in ends; a result
that sent one keeps its transaction active, under autocommit too
([Transactions](05-transactions.md)). `lobs().hold` keeps it valid past
the commit, until it is freed or a transaction is rolled back;
`lobs().free` lets it go. A locator no longer valid fails (SQLSTATE
0F001); a value sent whole has none to hold or free (22000).

```java
Lob.Locator body = (Lob.Locator) ((Outcome.Rows) conn.statements().query(
        "SELECT BODY FROM BOOKS WHERE ID = 1")).rows().get(0).get(0);
conn.lobs().hold(body);
conn.statements().query("COMMIT");
System.out.println("held past the commit: " + conn.lobs().read(body, 0, 9, false).length + " octets read");
conn.lobs().free(body);
try {
    conn.lobs().read(body, 0, 9, false);
} catch (TurborelException e) {
    System.out.println(e.getSQLState() + ": " + e.getMessage());
}
```

```
held past the commit: 9 octets read
0F001: 1 is not a valid locator
```

The classes are told in full in the API Reference,
[Large Objects](../client/24-large-objects.md).
