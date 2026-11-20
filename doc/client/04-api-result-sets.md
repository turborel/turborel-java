# ResultSet and Metadata

[Contents](README.md)

## NAME

`ResultSet`, `ResultSetMetaData`, `ParameterMetaData`, `Blob`, `Clob`,
`SQLXML` and `Array`: a statement's rows, how each value is read and
sent, and the types of its columns and parameters

## SYNOPSIS

```java
ResultSet rs = stmt.executeQuery(sql);
ResultSetMetaData columns = rs.getMetaData();
ParameterMetaData parameters = prep.getParameterMetaData();
```

Each is JDBC 4.3's interface.

## DESCRIPTION

**Moving.** A result set is forward-only and read-only: `next` moves to
each row in turn, and `isBeforeFirst`, `isFirst` and `isAfterLast` say
where it is. Any other move is refused, as is `isLast`, which a result
set read by a cursor cannot know, and updating a row. A column is read
by its number, from 1, or by its label, without regard to case.
Closing the result set, its statement, or its connection closes it; a
cursor's close is sent as the result set closes.

**Types.** A column's JDBC type is its declared type's, where the
server told it, granted `declared-types` (RULES.md D14): SMALLINT,
INTEGER, BIGINT, DECIMAL, NUMERIC, REAL, DOUBLE PRECISION and FLOAT
each its own, an expression's as the server declares it. Where no
declared type is told, of a server that does not grant it, the type is
its values' tag's: an exact number DECIMAL, of its precision and scale,
an approximate one REAL to 24 binary digits and DOUBLE past them. A parameter's type is always its tag's.

| SQL type | `java.sql.Types` | `getObject` gives |
|---|---|---|
| CHARACTER, CHARACTER VARYING | CHAR, VARCHAR | `String` |
| SMALLINT, INTEGER declared | SMALLINT, INTEGER | `Integer` |
| BIGINT declared | BIGINT | `Long` |
| DECIMAL, NUMERIC declared | DECIMAL, NUMERIC | `BigDecimal` |
| an exact number, or DECFLOAT | DECIMAL | `BigDecimal` |
| REAL | REAL | `Float` |
| DOUBLE PRECISION, FLOAT declared | DOUBLE, FLOAT | `Double` |
| BOOLEAN | BOOLEAN | `Boolean` |
| DATE, TIME, TIMESTAMP | DATE, TIME, TIMESTAMP | `java.sql.Date`, `Time`, `Timestamp` |
| TIME and TIMESTAMP WITH TIME ZONE | TIME_WITH_TIMEZONE, TIMESTAMP_WITH_TIMEZONE | `OffsetTime`, `OffsetDateTime` |
| INTERVAL YEAR TO MONTH, DAY TO SECOND | OTHER | `Period`, `Duration` |
| BINARY, BINARY VARYING | BINARY, VARBINARY | `byte[]` |
| CLOB, BLOB | CLOB, BLOB | `Clob`, `Blob` |
| XML | SQLXML | `SQLXML` |
| an array | ARRAY | `Array` |
| UUID | OTHER | `UUID` |
| a reference | REF | `byte[]` |
| a structured type | STRUCT | `String`, as the value is written |
| JSON, a multiset, a row, a plug-in's type | OTHER | `String`, as the value is written |

**Reading.** `getObject(i, Class)` gives the value as `String`,
`BigDecimal`, `BigInteger`, `Long`, `Integer`, `Short`, `Byte`,
`Double`, `Float`, `Boolean`, `UUID`, `byte[]`, `LocalDate`,
`LocalTime`, `LocalDateTime`, `OffsetDateTime`, `OffsetTime`,
`ZonedDateTime` or `Instant`, or any class `getObject` gives. The
getters read what they can: `getString` any value, as it is written;
`getInt` and the other numbers a number, or text that is one, in their
range; `getBoolean` a truth value, a number, true unless zero, or text,
TRUE, T, YES, Y or 1 and their opposites; `getBytes` octets, a large
object, a reference, a UUID, or text, as UTF-8; `getDate`, `getTime` and `getTimestamp` a
datetime, or text that is one. A timestamp without a time zone is read
in the calendar given, else the JVM's zone; one with a time zone is its
instant. A null is null, or 0 or false, and `wasNull` says so.

**Large objects.** A large object comes whole in its row, or as a
locator the server holds until the transaction ends, read a part at a
time as the program asks; either is a `Clob` or a `Blob`, and `free`
releases it. One held whole can be changed as JDBC's methods change it;
one by locator is read alone, its changes refused. A `Clob`, `Blob`,
stream or reader set as a parameter is sent as the parameter's type
takes it.

**SQLXML.** An `SQLXML` from `createSQLXML` is written once, by
`setString`, `setCharacterStream`, `setBinaryStream` or
`setResult(StreamResult.class)`, then set as a parameter. One read is
read by `getString`, `getCharacterStream`, `getBinaryStream` or
`getSource(StreamSource.class)`. Other sources and results are refused.

**Arrays.** `createArrayOf` makes an array of the elements given, its
base type named as given, sent as its parameter's element type takes
it. An array read gives its elements by `getArray`, whole or from an
index for a count, each as `getObject` reads its element type, the
declared one where told; and by `getResultSet`, a row of INDEX and
VALUE for each. Its base type is its element type's.

**Metadata.** `ResultSetMetaData` gives each column's name, which is
also its label, "" of an unnamed one; its type, its type's name, the
declared name where told, its precision, scale and display size, and
the class `getObject` gives; whether it takes nulls, as the server
says; whether a number is signed, and a string compared with its case.
The protocol tells no column's table, so its table, schema and catalog
are "". `ParameterMetaData` gives each parameter's type, of its tag,
and its precision, scale and class; each is an IN parameter, whether
it takes nulls unknown.

## ERRORS

| SQLCODE | SQLSTATE | When |
|---|---|---|
| -910 | TB910 | A result set closed, or on no row; a column of no place, or of no label; a move other than `next`; an object freed, or an SQLXML written twice or read before it is written |
| -301 | 22018 | A value that a getter cannot read as asked |
| -301 | 22003 | A number out of the getter's range |
| -900 | 0A000 | `isLast`; updating a row; a REF, ROWID, URL or DATALINK; changing a large object read by locator; an SQLXML of another source or result; the result set of an array the program made |
| | the server's | A large object's read |

## EXAMPLES

The types of a query's columns:

```java
try (ResultSet rs = stmt.executeQuery("SELECT ID, NAME, SALARY, ID * 1.5 AS HALF_MORE FROM STAFF ORDER BY ID")) {
    ResultSetMetaData md = rs.getMetaData();
    for (int i = 1; i <= md.getColumnCount(); i++) {
        System.out.println(md.getColumnName(i) + " " + md.getColumnTypeName(i) + " " + md.getColumnClassName(i));
    }
    rs.next();
    System.out.println(rs.getObject(1).getClass().getSimpleName() + " " + rs.getObject(4));
}
```

```
ID INTEGER java.lang.Integer
NAME CHARACTER VARYING java.lang.String
SALARY DECIMAL java.math.BigDecimal
HALF_MORE NUMERIC java.math.BigDecimal
Integer 1.5
```

## STANDARD

[DESIGN] D2, D14; JDBC 4.3, `java.sql.ResultSet`,
`java.sql.ResultSetMetaData`, `java.sql.ParameterMetaData`;
[PROTOCOL Declared types].

## SEE ALSO

[Statement](03-api-statements.md), [Values](23-values.md),
[Large Objects](24-large-objects.md), [Appendix A](../appendix/a-types.md)
