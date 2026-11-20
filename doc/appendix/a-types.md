# Appendix A: Types

[Contents](README.md)

This appendix will list each data type and value tag of turborel's
native protocol, 1 to 27, and the Java type the driver reads and writes
it as, with its JDBC type: `BigDecimal` for exact numbers, `java.time`
types for datetimes, and the rest. M1 decodes and encodes the values,
and the table is written in M7, with the JDBC types.
