# Large Objects

[Contents](README.md)

This chapter will tell how a program reads and writes CLOB and BLOB
values: a value small enough comes whole, a longer one by a locator read
a chunk at a time, and a long value is written piece by piece. M4 builds
them, and the chapter is written in M7, with JDBC's `Blob` and `Clob`.
