# Appendix A: Types

[Contents](README.md)

This appendix maps every data type and value tag of the native
protocol, 1 to 27, to the Java type the driver's codec gives and takes,
with the bounds of each. The codec's classes are in the package
`com.turborel.jdbc.internal.codec`, which the module does not export:
what a program sees through JDBC, `getObject` and the `java.sql.Types`
of each column, is told in
[ResultSet and Metadata](../client/04-api-result-sets.md). A number
the protocol writes as a `u32` is held in a Java `long`, 0 to
4294967295, and a `u64` in a `long`'s bits, read as unsigned.

## Data types

As a column's or a parameter's type, read by `TypeCodec`
([PROTOCOL Data types]). A distinct type comes as its source type.

| Tag | Type | Java |
|---|---|---|
| 1, 5 | CHARACTER, CHARACTER VARYING | `DataType.CharacterType`: varying, length, units (`CharUnits`) |
| 2 | exact numeric | `DataType.ExactType`: precision, scale |
| 3 | approximate numeric | `DataType.ApproximateType`: precision |
| 4 | a plug-in's type | `DataType.PluginType`: name, parameters |
| 6, 7 | BINARY, BINARY VARYING | `DataType.BinaryType`: varying, length |
| 8 to 12 | DATE, TIME, TIME WITH TIME ZONE, TIMESTAMP, TIMESTAMP WITH TIME ZONE | `DataType.DatetimeType`: kind (`DatetimeKind`), precision |
| 13 | INTERVAL | `DataType.IntervalType`: a `Qualifier` of start and end fields (`IntervalField`, 0 YEAR to 5 SECOND), leading and fractional precision |
| 14 | BOOLEAN | `DataType.BooleanType` |
| 15 | DECFLOAT | `DataType.DecfloatType`: precision |
| 16 | array | `DataType.ArrayType`: maximum cardinality, 1 to 65535, and element type |
| 17 | row | `DataType.RowType`: one field or more, each a name and a type |
| 18, 19 | CHARACTER LARGE OBJECT, BINARY LARGE OBJECT | `DataType.LobType`: kind (`LobKind`), length, units |
| 22 | multiset | `DataType.MultisetType`: element type |
| 23 | structured type | `DataType.StructuredType`: its name and its proper supertypes', each a `TypeName` |
| 24 | reference type | `DataType.ReferenceType`: the referenced type, and its scope if scoped |
| 25 | JSON | `DataType.JsonType` |
| 26 | XML | `DataType.XmlType`: primary and secondary modifiers, and of XMLSCHEMA the schema, namespace and element |
| 27 | UUID | `DataType.UuidType` |

Granted `declared-types`, a column of a Prepared or a Description also
carries the type it was declared with, `Column.declared()`, a
`Declared` of its name and its type as SQL writes it
([PROTOCOL Declared types]): SMALLINT, INTEGER, BIGINT, DECIMAL and
NUMERIC are all tag 2, and REAL, DOUBLE PRECISION and FLOAT all tag 3.
`Column.typeWritten()` gives the declared type, else the type the tag
says, as `TypeText` writes it.

Types nest at most 16 deep; a deeper type fails the connection.

## Values

As a row, an Execute or a CopyData carries them, read by `ValueCodec`
and written by each value ([PROTOCOL Values]).

| Tag | Value | Java | Bounds |
|---|---|---|---|
| 0 | null | `Value.NULL` | unknown, of a truth value too |
| 1 | character string | `Value.Text`, a `String` | of either character type; UTF-8, a string that is not Unicode refused |
| 2 | exact number | `Value.Exact`: units `BigInteger`, scale `long`; `toBigDecimal()` | units -2^127 to 2^127 - 1, scale 0 to 4294967295, every one kept; a `BigDecimal` holds a scale to 2147483647; Turborel holds at most 38 digits, and refuses more (SQLSTATE 22003) |
| 3 | approximate number | `Value.Approximate`, a `double` | its bits kept, NaN's and -0.0's too |
| 4 | a plug-in's value | `OctetValue.Plugin`: type name, text, bytes | kept as it came, and sent back unchanged |
| 6 | binary string | `OctetValue.Binary` | of either binary type |
| 8, 9, 11 | date, time, timestamp | `Value.Datetime`: moment, zoned, precision, displacement in minutes, the instant in microseconds of UTC | displacement -12:59 to +14:00 written; a displacement past the low half of its `u32` refused as read |
| 13 | interval | `Value.Interval`: `Qualifier`, units `BigInteger` | months of a year-month interval, microseconds of a day-time one |
| 14 | truth value | `Value.Truth`, a `boolean` | |
| 15 | decimal floating point | `Value.Decfloat`: coefficient `BigInteger`, exponent `int`; `toBigDecimal()` | Turborel's canonical form: at most 34 digits, no trailing zero, adjusted exponent -6143 to 6144 |
| 16, 17, 22 | array, row, multiset | `Value.Array`, `Value.Row`, `Value.Multiset`, each a list of values | nested at most 16 deep; a multiset of at most 65535 elements |
| 20 | large object, whole | `Lob.Whole`: kind, octets | a CLOB's octets UTF-8 |
| 21 | large object, by locator | `Lob.Locator`: kind, locator, octets, characters | read by Lob requests, from M4 |
| 23 | structured value | `Value.Structured`: its type's `TypeName`, its attributes | |
| 24 | reference | `OctetValue.Reference` | |
| 25 | JSON | `OctetValue.Json`, its stored form; `item()` a `JsonItem` | checked whole as it is read (Turborel's RULES.md D89) |
| 26 | XML | `Value.Xml`, its serialization as CONTENT | |
| 27 | UUID | `Value.Uuid`, a `java.util.UUID` | 16 octets, most significant first (Turborel's RULES.md D184) |

The page lists 1, 6, 9 and 11 as the value tags of the character,
binary, time and timestamp types. As turborel-protocol's reader takes
them, a value of tag 5, 7, 10 or 12, its type's tag, is read as one of
tag 1, 6, 9 or 11: CHARACTER VARYING as CHARACTER, BINARY VARYING as
BINARY, and TIME and TIMESTAMP WITH TIME ZONE as TIME and TIMESTAMP,
whose bodies say they are zoned. A value of tag 18 or 19, a large
object's type, fails its request alone (SQLCODE -916, the connection
kept), where turborel-protocol reads it (RULES.md D8); a large object
comes as tag 20 or 21. A truth byte other than 0 or 1, or a JSON value
damaged, fails its request so too ([Appendix B](b-errors.md)).

## JSON items

A JSON value's stored form is Turborel's binary tree; `JsonBinary`
reads it and `JsonBinaryWriter` writes it, and `JsonText` gives its
JSON text as Turborel writes it ([PROTOCOL Values]).

| Node | Item | Java |
|---|---|---|
| 0 | the SQL/JSON null | `JsonItem.NULL` |
| 1, 2 | false, true | `JsonItem.ScalarItem` of a `Value.Truth` |
| 3, 4, 5 | integer, decimal, wide decimal | `JsonItem.ScalarItem` of a `Value.Exact`, written in the narrowest node that holds it |
| 6 | decimal floating point | `JsonItem.ScalarItem` of a `Value.Decfloat` |
| 7 | approximate | `JsonItem.ScalarItem` of a `Value.Approximate` |
| 8 | string | `JsonItem.ScalarItem` of a `Value.Text` |
| 9 | datetime | `JsonItem.ScalarItem` of a `Value.Datetime`; it has no JSON text (SQLSTATE 22032) |
| 10 | array | `JsonItem.ArrayItem` |
| 11 | object | `JsonItem.ObjectItem`, its members in the order written |

Arrays and objects nest at most 128 deep. A damaged document is
refused, never read in part.
