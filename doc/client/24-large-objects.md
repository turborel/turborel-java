# Large Objects

[Contents](README.md)

## NAME

`Lobs`, `Lobs.Writer`, `Lob`: the low-level client's large objects, as
turborel-client gives them

## SYNOPSIS

```java
package com.turborel.jdbc.internal.session;

public final class Lobs {                           // Connection.lobs()
    public static final int INLINE_OCTETS = 64 * 1024;
    public static final int CHUNK_OCTETS = 262_144;
    public byte[] read(Lob lob, long from, long count, boolean characters) throws TurborelException;
    public InputStream reader(Lob lob);
    public Writer create(LobKind kind) throws TurborelException;
    public Completed free(Lob lob) throws TurborelException;
    public Completed hold(Lob lob) throws TurborelException;

    public final class Writer extends OutputStream {
        public Lob.Locator finish() throws TurborelException;
    }
}

package com.turborel.jdbc.internal.codec;

public sealed interface Lob extends Value {
    record Whole(LobKind kind, Octets octets) implements Lob {
        public static Whole ofText(String text) throws TurborelException;
    }
    record Locator(LobKind kind, long locator, long octets, long characters) implements Lob {}
}
public enum LobKind { CHARACTER, BINARY }
```

These packages are not exported by the module (RULES.md D2): JDBC's
`Clob` and `Blob` rest on them ([ResultSet and Metadata](04-api-result-sets.md)).

## DESCRIPTION

A large object of at most the server's SERVER.LOB_INLINE_BYTES, 64 KiB by
default, comes whole in its row, a `Lob.Whole`; a longer one by its
locator, a `Lob.Locator` of its octets and, of a character one, its
characters. A locator is valid until the transaction it was given in
ends, which a result that sent one keeps active, under autocommit too; a
held one until it is freed or a transaction is rolled back.

`read` reads at most a chunk, `CHUNK_OCTETS`, from octet `from`, from 0,
at most `count` of them; of a character large object, its characters if
`characters`, whole ones; fewer only at its end or a chunk's. Of a value
held whole it reads from the value itself. `reader` gives its octets as
an `InputStream`, a chunk a request.

`create` begins a new large object; its `Writer` sends what is written
in appends of at most a chunk, a character one cut where a character
ends, the server checking a character one's octets are UTF-8 as each
append comes; `finish` gives the value to pass as a parameter by its locator, used
once. `free` makes a locator no longer valid, nor the value it named;
`hold` keeps it valid past its transaction's commit.

## ERRORS

| SQLCODE | SQLSTATE | When |
|---|---|---|
| | 0F001 | A locator no longer valid, as one not held after its transaction's commit, or freed |
| -301 | 22000 | `free` or `hold` of a value sent whole, which has no locator |
| | 22021 | The server's: a character large object's octets, written, that are not UTF-8, and each append after; one that ends within a character, where it is used |
| | 22001 | `finish`: a value longer than its column |
| -919 | 08006 | A reply other than the Lob reply asked for, which loses the connection |

## EXAMPLES

A value written a piece at a time, then read by a range and as a stream:

```java
Lob.Locator body;
try (Lobs.Writer writer = conn.lobs().create(LobKind.CHARACTER)) {
    for (int chapter = 1; chapter <= 2000; chapter++) {
        writer.write(("Chapter " + chapter + ": a café by the sea.\n").getBytes(StandardCharsets.UTF_8));
    }
    body = writer.finish();
}
System.out.println(body.octets() + " octets, " + body.characters() + " characters");
```

```
66893 octets, 64893 characters
```

```java
byte[] range = conn.lobs().read(body, 10, 19, true);
System.out.println("characters 10 to 28: " + new String(range, StandardCharsets.UTF_8));
try (InputStream in = conn.lobs().reader(body)) {
    System.out.println("streamed: " + in.readAllBytes().length + " octets");
}
```

```
characters 10 to 28:  a café by the sea.
streamed: 66893 octets
```

The User's Guide's chapter [Large Objects](../user/06-large-objects.md)
has more.

## STANDARD

[PROTOCOL Values], [PROTOCOL Lob 'L']; [CLIENT Large objects].

## SEE ALSO

[Values](23-values.md),
[Copy, Import and Export](25-copy.md)
