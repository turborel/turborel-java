# Errors and Warnings

[Contents](README.md)

This chapter will tell how failures and warnings reach a program: every
failure a `TurborelException`, a `SQLException` whose SQLSTATE is
turborel's and whose vendor code is its SQLCODE; the rule a failure
cites, its position and its diagnostics; notices and conditions as
`SQLWarning`; and which failures end a connection. M3 builds them
beneath JDBC, and the chapter is written in M7. The SQLSTATE classes are
listed in [Appendix B](../appendix/b-errors.md).
