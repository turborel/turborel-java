# Appendix A: Types

[Contents](README.md)

This appendix maps every data type and value tag of the native
protocol, 1 to 27, to the Java type the driver's codec gives and takes,
with the bounds of each. The codec's classes are in the package
`com.turborel.jdbc.internal.codec`, which the module does not export:
what a program sees through JDBC, `getObject` and the `java.sql.Types`
of each column, comes with the standard API at milestone M7. A number
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

Types nest at most 16 deep; a deeper type fails the connection.

## Values

As a row, an Execute or a CopyData carries them, read by `ValueCodec`
and written by each value ([PROTOCOL Values]).

| Tag | Value | Java | Bounds |
|---|---|---|---|
| 0 | null | `Value.NULL` | unknown, of a truth value too |
| 1 | character string | `Value.Text`, a `String` | of either character type; UTF-8, a string that is not Unicode refused |
| 2 | exact number | `Value.Exact`: units `BigInteger`, scale `long`; `toBigDecimal()` | units -2^127 to 2^127 - 1, scale 0 to 4294967295, every one kept; a `BigDecimal` holds a scale to 2147483647 |
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
