# Errors

[Contents](README.md)

## NAME

`TurborelException`: the driver's error, of every failure

## SYNOPSIS

```java
package com.turborel.jdbc;

public class TurborelException extends SQLException {
    public TurborelException(int sqlcode, String sqlstate, String message);
    public TurborelException(int sqlcode, String sqlstate, String message, Details details);
    public int sqlcode();
    public @Nullable String rule();
    public OptionalLong position();
    public boolean transactionActive();
    public Map<String, String> diagnostics();

    public record Details(@Nullable String rule, @Nullable Long position, boolean active,
            Map<String, String> diagnostics) {}
}
```

## DESCRIPTION

Every failure is a `TurborelException` (RULES.md D9): the server's own,
as it reports it, or one the driver raises itself, with the SQLCODE and
SQLSTATE of the table every Turborel driver shares, turborel-client's
own where it has the case. `getSQLState()` gives its SQLSTATE;
`sqlcode()`, as `getErrorCode()`, its SQLCODE; `getMessage()` what went
wrong, beginning with the rule it cites if the driver raised it.

Of the server's failures, `rule()` gives the rule the failure cites,
apart from its message; `position()` where in the statement it failed,
counted in characters; `transactionActive()` whether a transaction is
still active after it; and, granted `diagnostics`, `diagnostics()` what
it is about, by the names SEVERITY (ERROR, FATAL, WARNING or NOTICE),
DETAIL, HINT, CONTEXT, SCHEMA, TABLE, COLUMN, DATA_TYPE and CONSTRAINT,
each if known. A failure the driver raises has none of them, and says
no transaction is active.

A failure's rule, position and diagnostics are not serialized with it:
a failure read back from its serialized form keeps its SQLCODE, SQLSTATE
and message alone.

A statement's failure keeps the connection. A failure of the connection
ends it: an Error of SQLSTATE class 08 but 08003, or of class 28 and
SQLCODE -919, and each connection failure the driver raises.

## ERRORS

The codes the driver raises itself, and those it gives the server's
failures, are listed in [Appendix B](../appendix/b-errors.md).

## EXAMPLES

A duplicate key, granted `diagnostics`:

```java
try {
    conn.statements().query("INSERT INTO STAFF (ID, NAME) VALUES (1, 'Ada')");
} catch (TurborelException e) {
    System.out.println(e.sqlcode() + " " + e.getSQLState() + ": " + e.getMessage());
    System.out.println("rule " + e.rule());
    System.out.println("transaction still active: " + e.transactionActive());
    System.out.println(e.diagnostics());
}
System.out.println("connection lost: " + conn.isLost());
```

```
-201 23000: PRIMARY KEY ANN.STAFF_PRIMARY1 (ID) of ANN.STAFF would be violated: a row of these values is there
rule X3.135-1986 §6.4 GR2
transaction still active: true
{SCHEMA=ANN, CONSTRAINT=STAFF_PRIMARY1, TABLE=STAFF, SEVERITY=ERROR, DETAIL=the key (1) is held by another row}
connection lost: false
```

The User's Guide's chapter [Errors and Diagnostics](../user/10-errors.md)
has more.

## STANDARD

[PROTOCOL Error 'E'], [PROTOCOL Errors and notices];
[CLIENT Running statements], [CLIENT What the server tells]. The class is a
`java.sql.SQLException` of JDBC 4.3.

## SEE ALSO

[Appendix B: Errors](../appendix/b-errors.md),
[Statements and Cursors](22-statements.md)
