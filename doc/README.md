# The turborel-java Documentation Set

turborel-java is the Java driver for turborel, a relational database
system. Its JDBC 4.3 driver, `turborel-jdbc`, connects a Java program, or
a tool written in Java such as DBeaver or DataGrip, to a turborel server,
`turboreld`, over turborel's native protocol, in Java alone. This set of
manuals describes it for those who connect programs and tools to turborel
with it.

The driver is in alpha: what these manuals describe may change in any
release until 1.0.0 ([Versions](appendix/d-versions.md)). Its version is
the turborel release it is made for. Each change updates the pages it
touches, so the manuals describe the release they come with. A chapter not
yet written says what it will cover, and the milestone that writes it.

## The volumes

| Volume | For | |
|---|---|---|
| [I. User's Guide](user/README.md) | Anyone who connects a Java program or tool to turborel | Installing, connecting, queries, prepared statements, transactions, large objects, copying, pipelines and pools, settings, errors, chapter by chapter |
| [II. API Reference](client/README.md) | Programmers, looking something up | The JDBC API as the driver implements it, and its own interfaces beyond JDBC, a page each |
| [Appendices](appendix/README.md) | Everyone | Types, errors, settings, versions |

Read the User's Guide from the start to learn the driver; turn to the API
Reference and the appendices to look a thing up.

## Conventions

**Code** is set in a fixed-width font. Java examples are complete enough to
compile, and each is run against a turborel server before it is printed
here, its output pasted from the run.

**SQL** in examples is written as turborel's manual writes it: key words
and names in upper case, as the standard folds them.

**Results** follow their examples, as the program prints them.

**Failures** are given by SQLSTATE, the code the SQL standard defines,
and SQLCODE, the older number turborel gives beside it. The driver reports
both on every failure, the SQLCODE as the `SQLException`'s vendor code
([Appendix B](appendix/b-errors.md)).

**Citations** name the section of turborel's documents a behaviour comes
from, by its heading: [PROTOCOL Frames] is the section "Frames" of
turborel's *The Native Protocol*; [CLIENT Pipelines] is the section
"Pipelines" of its client library's chapters.
