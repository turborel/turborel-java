# Errors

[Contents](README.md)

This page will describe the driver's error: its SQLCODE, SQLSTATE and
message, the rule it cites, its position, whether a transaction is still
active, its diagnostics and its cause; and the failures the driver
raises itself, each with the SQLCODE and SQLSTATE turborel-client gives
it. It is filled at milestone M3.

The driver's error exists already: `com.turborel.jdbc.TurborelException`,
a `java.sql.SQLException` whose `getSQLState()` is Turborel's SQLSTATE
and whose `getErrorCode()`, and `sqlcode()`, is its SQLCODE. In this
alpha it carries the SQLCODE, the SQLSTATE and the message; the rest of
a failure comes with M3, as the server's failures are read.
