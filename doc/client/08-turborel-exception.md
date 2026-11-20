# TurborelException

[Contents](README.md)

`com.turborel.jdbc.TurborelException` is the driver's one failure type,
a `java.sql.SQLException` whose `getSQLState()` is turborel's SQLSTATE
and whose `getErrorCode()`, and `sqlcode()`, is its SQLCODE. It exists
in this alpha, carrying the SQLCODE, SQLSTATE and message. This page
will describe the rest of a failure it carries once the server's
failures are read, in M3: the rule it cites, its position, whether a
transaction is active after it, and its diagnostics.
