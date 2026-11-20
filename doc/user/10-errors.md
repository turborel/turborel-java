# Errors and Diagnostics

[Contents](README.md)

Every failure reaches a program as the driver's error,
`com.turborel.jdbc.TurborelException`, a `java.sql.SQLException`: the
server's own, as it reports it, or one the driver raises itself, with
the SQLCODE and SQLSTATE Turborel gives the same failure. This chapter
tells what a failure carries, which end the connection, and how warnings
come. The examples use the low-level client and the table of
[Running Queries](03-queries.md).

## What a failure carries

`getSQLState()` gives its SQLSTATE, the code the SQL standard defines,
and `sqlcode()`, as `getErrorCode()`, its SQLCODE, the older number.
`getMessage()` says what went wrong in plain words. Of the server's
failures, `rule()` gives the rule it cites, `position()` where in the
statement it failed, `transactionActive()` whether a transaction is
still active after it, and, granted `diagnostics`, `diagnostics()` what
it is about: its severity, detail, hint, context, schema, table,
column, data type and constraint, each if known.

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

## Failures that end the connection

A statement's failure leaves the connection as it was. A failure of the
connection itself ends it: an Error the server sends of SQLSTATE class
08 but 08003, or of class 28 and SQLCODE -919, credentials refused, as
at a login or a change of user; and every
failure the driver raises of the connection, SQLCODE -919 and SQLSTATE
08006: a reply malformed or too long, the stream failing, a wait that
runs out. A connection lost takes no more: `isLost` says so, and each
later request fails with the failure that lost it. The codes the driver
raises itself are listed in [Appendix B](../appendix/b-errors.md).

## Warnings

A statement that completes with a warning, of SQLSTATE class 01, is not
a failure: the connection's `warning()` gives its SQLSTATE. Its
conditions, the warnings it raised, are told one way. Granted
`conditions`, they come in its completion, and the connection's
`told().conditions()` gives them, `takeConditions()` taking them so each
is told once.

```java
conn.statements().query("CREATE TABLE BONUS (AMOUNT INTEGER)");
conn.statements().query("INSERT INTO BONUS VALUES (NULL), (100)");
conn.statements().query("SELECT SUM(AMOUNT) FROM BONUS");
System.out.println("warning " + conn.warning());
for (TurborelException condition : conn.told().takeConditions()) {
    System.out.println(condition.getSQLState() + ": " + condition.getMessage());
}
```

```
warning 01003
01003: the statement completed with warning: null value eliminated in set function
```

Otherwise, granted `notices`, each comes as a notice before the
statement's outcome, given to the handler `setNoticeHandler` set, or,
with none, kept, the last 64, for `takeNotices`. A statement that fails
has no completion, so its conditions come as notices. The server's own
notices, as 01T01 of a transaction a reset rolled back, always come as
notices.

```java
Offer offer = new Offer(List.of(Capability.NOTICES), List.of(), Offer.none().compressFrom());
Target target = new Target.Tcp(new InetSocketAddress("127.0.0.1", 17434));
try (Connection conn = Connect.to(target, "ann", new Trust(), Timeouts.DEFAULT, offer)) {
    conn.setNoticeHandler(notice -> System.out.println("notice " + notice.sqlstate() + ": " + notice.message()));
    conn.statements().query("CREATE TABLE TIPS (AMOUNT INTEGER)");
    conn.statements().query("INSERT INTO TIPS VALUES (NULL), (5)");
    conn.statements().query("SELECT SUM(AMOUNT) FROM TIPS");
```

```
notice 01003: the statement completed with warning: null value eliminated in set function
```

The error is told in full in the API Reference,
[Errors](../client/30-errors.md).
