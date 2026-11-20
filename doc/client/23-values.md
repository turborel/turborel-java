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

The package is the driver's own and the module does not export it. A
program reaches values through JDBC, from milestone M7, and through the
driver's own interfaces beyond JDBC; this page describes what is
beneath them. The connection that carries values comes with M2, and the
statements that give them with M3.

## ERRORS

| SQLSTATE | SQLCODE | When |
|---|---|---|
| 08006 | -919 | A value or a type received is malformed: a tag of none, a body cut short, a flag neither 0 nor 1, a string not UTF-8, a JSON value damaged, parts nested too deep. The connection fails. |
| TB910 | -910 | A value given cannot be carried: a number past its field, a string that is not Unicode, a DECFLOAT of more than 34 digits. Nothing is sent. |
| 22009 | -301 | A displacement given past -12:59 to +14:00, refused before it is sent with the code Turborel gives it. |
| 0A000 | -900 | A JSON item nests deeper than 128, or its stored form passes 256 MiB. |
| 22032 | -301 | A JSON item with no JSON text, as a datetime or an approximate number not finite, asked for its text. |

## EXAMPLES

None yet: the client sends and receives values once it connects, at
milestone M2.

## STANDARD

[PROTOCOL Data types], [PROTOCOL Values], [PROTOCOL Composite fields],
and Turborel's RULES.md D89, the JSON value's stored form, and D184,
the UUID's tag.

## SEE ALSO

[Appendix A: Types](../appendix/a-types.md),
[Errors](30-errors.md).
