# Values

[Contents](README.md)

## NAME

The values of the low-level client: the codec's data types, values and
JSON items.

## SYNOPSIS

```java
package com.turborel.jdbc.internal.codec;

public sealed interface DataType { ... }
public sealed interface Value { void encode(Writer w) throws TurborelException; ... }
public sealed interface JsonItem { ... }
public final class TypeCodec { public static DataType read(Reader r) throws TurborelException; }
public final class ValueCodec { public static Value read(Reader r) throws TurborelException; }
```

## DESCRIPTION

Every data type and value of the native protocol has a Java record of
its own, which [Appendix A](../appendix/a-types.md) lists with its
bounds. `TypeCodec` reads a column's or a parameter's type, and
`ValueCodec` a value, as the server writes them. Each value writes
itself, as a program's parameter or a row of a copy. Nothing a value
holds is converted on its way: an exact number keeps every unit and
scale, an approximate number its bits, a plug-in's value its type's
name, text and bytes, and a JSON value its stored form, checked whole
as it is read.

A JSON value is made of an item, or of JSON text by `Json.ofText`, the
text parsed as Turborel parses it, a decimal keeping the scale it was
written with; its `item()` gives it back, and `JsonText.of` an item's
text. An XML value is its serialization as CONTENT, of which `nodes()`
parses its nodes; a parameter of an XML type is made of its text. A
UUID is made of its 16 octets by `Value.Uuid.ofOctets`, or of its text
by `Value.Uuid.parse`, read as CAST reads it; `octets()` and `text()`
give them back, whatever types the program registered.

The package is the driver's own and the module does not export it. A
program reaches values through JDBC
([ResultSet and Metadata](04-api-result-sets.md)); this page describes
what is beneath them.

## ERRORS

| SQLSTATE | SQLCODE | When |
|---|---|---|
| 08006 | -919 | A value or a type received is malformed: a tag of none, a body cut short, a flag neither 0 nor 1, a string not UTF-8, a JSON value damaged, parts nested too deep. The connection fails. |
| 22003 | -301 | A value given cannot be carried: a number past its field, a DECFLOAT of more than 34 digits or past its exponents. Nothing is sent. |
| 22021 | -301 | A string given is not Unicode: it holds a lone surrogate. Nothing is sent. |
| TB916 | -916 | A JSON value's stored form given is damaged. |
| TB910 | -910 | A JSON item's scalar is of no SQL/JSON scalar's kind. |
| 22009 | -301 | A displacement given past -12:59 to +14:00, refused before it is sent with the code Turborel gives it. |
| 0A000 | -900 | A JSON item nests deeper than 128, or its stored form passes 256 MiB. |
| 22032 | -301 | A JSON item with no JSON text, as a datetime or an approximate number not finite, asked for its text; a string that is no JSON text, made a JSON value. |
| 22037 | -301 | Two members of one key in JSON text parsed with keys to be unique. |
| 2200N | -301 | An XML value's text that is not well-formed XML content, asked for its nodes. |
| 2200W | -301 | An XML value the server cannot serialize, as an attribute alone, fails the fetch. |
| TB910 | -910 | A UUID made of octets not 16. |

## EXAMPLES

A UUID, an XML value and a JSON value made of its text, sent and read
back:

```java
Statements statements = conn.statements();
statements.query("CREATE TABLE ORDERS (ID UUID, BODY XML, EXTRA JSON)");
Statement insert = statements.prepare("INSERT INTO ORDERS VALUES (?, ?, ?)");
statements.execute(insert, List.of(
        Value.Uuid.parse("919108f7-52d1-4320-9bac-f847db4148a8"),
        new Value.Xml("<order no=\"18\"><item>jam</item></order>"),
        OctetValue.Json.ofText("{\"rush\": true, \"price\": 1.50}")));
List<Value> row = ((Outcome.Rows) statements.query("SELECT ID, BODY, EXTRA FROM ORDERS")).rows().get(0);
Value.Uuid id = (Value.Uuid) row.get(0);
Value.Xml body = (Value.Xml) row.get(1);
OctetValue.Json extra = (OctetValue.Json) row.get(2);
System.out.println(id.text() + ", " + id.octets().length + " octets");
System.out.println(body.serialization() + ", its first node " + body.nodes().getFirstChild().getNodeName());
System.out.println(JsonText.of(extra.item()));
```

```
919108f7-52d1-4320-9bac-f847db4148a8, 16 octets
<order no="18"><item>jam</item></order>, its first node order
{"rush":true,"price":1.50}
```

## STANDARD

[PROTOCOL Data types], [PROTOCOL Values], [PROTOCOL JSON values],
[PROTOCOL Composite fields], [CLIENT XML values], [CLIENT UUIDs],
and Turborel's RULES.md D89, the JSON value's stored form, and D184,
the UUID's tag.

## SEE ALSO

[Appendix A: Types](../appendix/a-types.md),
[Errors](30-errors.md).
