# turborel-java rules

How turborel-java is designed, written and verified. Every change follows
these rules; `./gradlew check` checks those a tool can check, and review
checks the rest.

Rules (R-…) say how code and tests are written. **shall** is mandatory,
and a departure needs a recorded deviation (§6). **should** is expected, and
a reviewer may accept a reasoned exception.

Decisions (D1…) record how the driver is built and why. Change one only by
changing this file in a reviewed commit. A decision says what was chosen and
why, and the package it names holds the mechanism.

Cite both by ID in code, tests, reviews and commit messages, as in
`RULES.md D2` or `R-ERR-1`.

Before changing code, read §3 (code rules), §4 (test rules) and the
decisions covering your area. What the driver implements of the protocol
and of turborel-client is in [CONFORMANCE.md](CONFORMANCE.md), and open
work is in [TODO.md](TODO.md).

[1 Decisions](#1-decisions) · [2 Layout](#2-project-layout) ·
[3 Code](#3-code-rules) · [4 Tests](#4-test-rules) ·
[5 Coverage](#5-coverage) · [6 Deviations](#6-deviations) ·
[7 Process](#7-process-and-tools) · [8 References](#8-references)

---

## 1. Decisions

| # | Decision |
|---|---|
| **The driver** | |
| D1 | The driver speaks turborel's native protocol, version 1, in Java alone |
| D2 | JDBC 4.3 is the API, over a client of turborel-client's features |
| D3 | Java 17 is the oldest release the driver runs on |
| D7 | Every failure is a `TurborelException`, a `SQLException` |
| **Tooling and process** | |
| D4 | Gradle builds it, with JDK 17's compiler, and the checks run on JDK 21 |
| D5 | No dependency at run time; the build's tools each named here |
| D6 | One jar, a JPMS module, published to Maven Central with its sources and Javadoc |
| D8 | Tests are JUnit Jupiter's, each citing what it verifies |

### The driver

#### D1. The driver speaks turborel's native protocol, version 1, in Java alone

The driver implements the protocol of `doc/client/05-protocol.md` in
turborel's repository itself, in Java, with no native code and no wrapper
over another client. It is installed as one jar from Maven Central, needs
no build step on the machine that uses it, and runs wherever a JDK does.

Its layers follow turborel-client's modules, so a reviewer can check one
against the other: the codec (`protocol/src`), the transport (`target.rs`,
`timeouts.rs`, `pipe.rs`), authentication (`mechanism.rs`, `scram.rs`,
`tls.rs`), the session (`connection.rs`, `session.rs`, `cancel.rs`,
`told.rs`), statements, large objects, copy, settings, pipelines and pools.
Each layer is a package under `com.turborel.jdbc.internal` (§2). Where the
protocol's page and turborel-client's code differ, the code is right, and
the difference is reported to turborel.

TLS is the JDK's (JSSE), SCRAM's hashes and keys `java.security` and
`javax.crypto`, and compression `java.util.zip`.

#### D2. JDBC 4.3 is the API, over a client of turborel-client's features

The public API is JDBC 4.3 (`java.sql`, `javax.sql`), in the exported
package `com.turborel.jdbc`: a `Driver` found by `ServiceLoader`, its
connections, statements, prepared statements, result sets, metadata and a
`DataSource`. GUI tools, DBeaver and DataGrip among them, load a JDBC
driver as it is, so JDBC comes first. It is built in milestone M7 and
checked against turborel-client in M8.

Beneath it a low-level client has every feature of turborel-client, in
packages the module does not export, so it can change without breaking a
program. What JDBC has no place for, such as pipelines, copy, numbered
cursors, notices and the commit position, a program reaches by `unwrap`
from a JDBC object to an interface of the exported package, as JDBC 4.3's
`Wrapper` provides.

#### D3. Java 17 is the oldest release the driver runs on

The driver is compiled for release 17 (`--release 17`) and runs on Java 17
and every later release. Spring Boot 4 and Spring Framework 7 keep Java 17
as their floor, and a driver an application cannot load is no use to it,
so the driver keeps the same floor. CI tests it on 17, 21 and 25, the
long-term-support releases. A release that no major framework or vendor
still supports is dropped, by a change of this decision.

Java 17 has records, sealed types, switch expressions, `ByteBuffer`'s
little-endian access and `UnixDomainSocketAddress`, all the driver needs.

#### D7. Every failure is a `TurborelException`, a `SQLException`

Every failure the driver reports, its own or the server's, is a
`com.turborel.jdbc.TurborelException`, which extends `java.sql.SQLException`.
Its SQLSTATE is the `SQLException`'s, and its SQLCODE the vendor code, so
`getErrorCode()` gives it to a tool that knows only JDBC, and `sqlcode()`
to one that knows the driver. A failure the server sends carries the rest
of the protocol's failure too: the rule it cites, its position, whether a
transaction is active after it, and its diagnostics.

A failure of the connection, a peer's message the protocol does not allow
among them, is SQLCODE -919, SQLSTATE 08006, as turborel's
(`internal.codec.Broken`).

### Tooling and process

#### D4. Gradle builds it, with JDK 17's compiler, and the checks run on JDK 21

The build is Gradle's, in the Kotlin DSL (`build.gradle.kts`,
`settings.gradle.kts`), run by the wrapper committed with it
(`./gradlew`), with every version in the catalog
`gradle/libs.versions.toml`. The wrapper's distribution is held to its
SHA-256.

The library and its tests are compiled by a JDK 17 toolchain for release
17, so the classes published are JDK 17's compiler's. Error Prone and
Checkstyle run only on JDK 21 or later, so Checkstyle runs on a JDK 21
toolchain, and Error Prone compiles the sources a second time on JDK 21,
still for release 17, its classes thrown away (`errorProneMain`,
`errorProneTest`). A build needs JDK 17 and JDK 21. Gradle finds them on
the machine and never downloads one: a JDK it does not find is named by
`-Dorg.gradle.java.installations.paths=<home>,<home>`.

Project automation is the Gradle build, never shell scripts. The one
script, `.githooks/pre-commit`, only hands over to it.

#### D5. No dependency at run time; the build's tools each named here

The jar depends on the JDK alone: `java.base` and `java.sql`. A library is
added to it only by a change of this decision, after the JDK has been
shown not to do the work, and only one production-grade by its own claims.

Annotations read as the code compiles are allowed: JSpecify's nullness
annotations are `compileOnly`, `requires static` in `module-info.java`, and
absent at run time.

The build's tools, none of which ships:

| Tool | What for | License |
|---|---|---|
| Gradle and its wrapper | the build | Apache-2.0 |
| Error Prone | bug patterns, as the code compiles | Apache-2.0 |
| NullAway | nullness, as the code compiles | MIT |
| JSpecify | nullness annotations, compile only | Apache-2.0 |
| Spotless, with palantir-java-format | formatting | Apache-2.0 |
| Checkstyle | the code rules a tool can check | LGPL-2.1-or-later |
| SpotBugs | bug patterns, in the classes | LGPL-2.1-or-later |
| JUnit Jupiter | the tests | EPL-2.0 |
| JaCoCo | coverage | EPL-2.0 |

Each is taken at a stable release, never a milestone or a release
candidate. Dependabot proposes their updates (`.github/dependabot.yml`).

#### D6. One jar, a JPMS module, published to Maven Central with its sources and Javadoc

The driver is the Maven artifact `com.turborel:turborel-jdbc`, the JPMS
module `com.turborel.jdbc`, under the base package `com.turborel.jdbc`. It
is published with its sources and Javadoc jars and a POM that names the
license, BSD-2-Clause-Patent, the project's URL and its source control,
as Maven Central requires. Each jar carries `LICENSE` in `META-INF`.

Its version is the turborel release it is made for (VERSIONING.md).
Nothing is published until the owner publishes every driver together; the
release workflow's publishing step stays disabled until then.

#### D8. Tests are JUnit Jupiter's, each citing what it verifies

Tests use JUnit Jupiter, under `src/test/java`, in the package of the
code they test, and run on the class path so they reach the internal
packages. Each test's doc comment begins `Verifies:` and a tag, which the
build checks (`./gradlew traceability`, R-TRACE-1):

| Tag | Cites |
|---|---|
| `[PROTOCOL <heading>]` | a heading of `doc/client/05-protocol.md`, as `[PROTOCOL Frames]` |
| `[CLIENT <heading>]` | a heading of `doc/client/02-library.md` or `02-library-connections.md`, as `[CLIENT Pipelines]` |
| `[DESIGN] D<n>` | a decision of this file |

The headings of the two client pages are distinct, so a heading names
its page.

---

## 2. Project layout

```
build.gradle.kts          the build: compiling, checks, tests, publishing (D4)
settings.gradle.kts       the project's name and repositories
gradle.properties         Gradle's settings: caches, no JDK downloads
gradle/libs.versions.toml every version the build uses (D5)
gradle/wrapper/           the wrapper's jar and properties
gradlew, gradlew.bat      the wrapper
config/checkstyle/        the code rules Checkstyle checks (§3)
README.md                 what the driver is; how to build it
RULES.md                  this file
CONFORMANCE.md            the parity matrix: what is implemented
TODO.md                   open work, by milestone
VERSIONING.md             the version, and how publishing is done
CONTRIBUTING.md           changes, commit messages, pull requests, issues
SECURITY.md               how to report a vulnerability
TRADEMARK.md              the Turborel name and logos
AGENTS.md                 the guide for coding agents
CLAUDE.md                 points Claude Code to AGENTS.md
doc/                      the manual, read by turborel.com's docs site
  user/                   Volume I, the User's Guide
  client/                 Volume II, the API Reference
  appendix/               the appendices
artwork/                  the logos
.github/                  CI (workflows/), issue forms, labels, the
                          pull request template, Dependabot
.githooks/pre-commit      the fast checks before a commit
src/main/java/
  module-info.java        the module com.turborel.jdbc
  com/turborel/jdbc/      the JDBC API, exported (D2)
  com/turborel/jdbc/internal/
    codec/                frames, fields, values, the tail (D1)
src/test/java/            the tests, package by package (D8)
```

Each later layer of D1 is a package of `internal`, named after its
CONTRIBUTING.md area: `transport`, `auth`, `tls`, `session`, `statements`,
`lobs`, `copy`, `settings`, `pipeline` and `pool`.

---

## 3. Code rules

### R-STYLE: formatting and idiom

| ID | Rule | Checked by |
|---|---|---|
| R-STYLE-1 | Code **shall** be formatted by Spotless with palantir-java-format. | `spotlessCheck` |
| R-STYLE-2 | Names **shall** follow the Java conventions: packages in lower case, types in UpperCamelCase, methods and fields in lowerCamelCase, constants in UPPER_SNAKE_CASE. The driver's own types name an accessor as a record does, `sqlcode()`; JDBC's `getX` is kept where JDBC defines it. | review |
| R-STYLE-3 | One top-level type a file, named as its file. | `OneTopLevelClass`, `OuterTypeFilename` |
| R-STYLE-4 | Library code **shall not** print, or call `System.exit` or `Runtime.halt`. | Checkstyle `Regexp` |
| R-STYLE-5 | No wildcard imports. | `AvoidStarImport` |
| R-STYLE-6 | What a program may use is in `com.turborel.jdbc`; everything else is under `com.turborel.jdbc.internal`, which the module does not export. | `module-info.java`; review |

### R-SIZE: short methods and files

| ID | Rule | Checked by |
|---|---|---|
| R-SIZE-1 | A method or constructor **shall** have at most 50 lines of code. | `MethodLength` |
| R-SIZE-2 | A source file **shall** have at most 600 lines. | `FileLength` |
| R-SIZE-3 | A method **shall** take at most 5 parameters; else use a record. A method overriding JDBC's keeps JDBC's parameters. | `ParameterNumber` |
| R-SIZE-4 | A class **should** hold one concept, stated in its doc comment. | review |

### R-DOC: comments and documentation

| ID | Rule | Checked by |
|---|---|---|
| R-DOC-1 | Every public and protected type, method, constructor and field **shall** have a Javadoc comment, with `@param`, `@return` and `@throws` for each. | javac `-Xdoclint:all/protected`; `MissingJavadocMethod`, `MissingJavadocType` |
| R-DOC-2 | Comments **shall** say *why*: the rule implemented, the choice made, the invariant relied on. | review |
| R-DOC-3 | Javadoc **shall** build with no warning. | `javadoc` with `-Xdoclint:all -Werror` |
| R-DOC-4 | Behaviour the protocol or turborel-client governs **shall** cite it by heading, as tests do (D8): `[PROTOCOL Frames]`, `[CLIENT Pipelines]`. Choices no document governs say `[DESIGN]`. A heading is checked against its page before it is cited, never written from memory. | review |
| R-DOC-5 | No commented-out code, and no `TODO`, `FIXME` or `XXX` comments: open work goes in TODO.md, and code never cites TODO.md. | `TodoComment`, `RegexpSingleline`; review |
| R-DOC-6 | Every package **shall** have a `package-info.java` with its doc comment, marked `@NullMarked`. | `MissingJavadocPackage`; review |

### R-CONST: no magic numbers or strings

| ID | Rule | Checked by |
|---|---|---|
| R-CONST-1 | Numeric literals other than `0` or `1` **shall** be named constants, documenting the value's source. | `MagicNumber` |
| R-CONST-2 | Strings matched or looked up, such as capability names, status names, settings, SQLSTATEs and mechanism names, **shall** be named constants, defined once. Messages for people are not, but cite their rule (R-ERR-2). | review |
| R-CONST-3 | The protocol's message kinds, tail tags, type tags and value tags **shall** be named constants in the codec, grouped under a comment naming the section of the protocol they come from. | review |
| R-CONST-4 | Tests may use literal fixtures; an expected value that restates a constant **should** use it. | review |

### R-ERR: failures, not stray exceptions

| ID | Rule | Checked by |
|---|---|---|
| R-ERR-1 | Every failure **shall** reach the program as a `TurborelException` (D7), with its SQLCODE, SQLSTATE and message, and with the rest of the failure where the server sent it. No input and no server reply **shall** make library code throw another exception, call `System.exit` or crash. Java's habit of an unchecked exception for a caller's mistake gives way to this rule. | tests; review |
| R-ERR-2 | A message the driver makes **shall** begin with what it cites: `[PROTOCOL <heading>]`, `[CLIENT <heading>]` or `[DESIGN]`. A server's failure keeps the server's message. | review; tests |
| R-ERR-3 | No failure **shall** be swallowed: no empty `catch`, no `catch` of `Exception`, `RuntimeException` or `Throwable`. | `EmptyCatchBlock`, `IllegalCatch` |
| R-ERR-4 | A server's reply **shall not** make the client hold memory without bound: every count and length is checked against what remains of the body, or a named limit, before anything is allocated for it. | tests; review |
| R-ERR-5 | Every socket, stream and connection **shall** be closed on every path, by try-with-resources where its scope is a block. | Error Prone; SpotBugs; review |

### R-NUM: arithmetic and the protocol's integers

| ID | Rule | Checked by |
|---|---|---|
| R-NUM-1 | The protocol's `u32` and `u64` **shall** be read as unsigned (`Integer.toUnsignedLong`, `Long.compareUnsigned`), never compared as signed. | tests; review |
| R-NUM-2 | Arithmetic on lengths, counts and positions **shall** be exact (`Math.addExact` and the like) or shown in range. | review |
| R-NUM-3 | A narrowing cast **shall** follow a check that the value fits. | Error Prone; review |

### R-CF: control flow

| ID | Rule | Checked by |
|---|---|---|
| R-CF-1 | A boolean expression **shall** have at most 4 conditions; else extract a named predicate. | `BooleanExpressionComplexity` |
| R-CF-2 | A method's cyclomatic complexity **shall** be at most 10, and `if` nested at most 3 deep. | `CyclomaticComplexity`, `NestedIfDepth` |
| R-CF-3 | A `switch` on an enum or a sealed type **should** be a switch expression with no `default`, so the compiler checks that every case is handled. | review |
| R-CF-4 | Conditions **shall** have no side effects. | `InnerAssignment`; review |

### R-LINT: warnings are errors

| ID | Rule | Checked by |
|---|---|---|
| R-LINT-1 | javac **shall** compile with `-Xlint:all -Werror`. | `compileJava`, `compileTestJava` |
| R-LINT-2 | Error Prone **shall** pass with its checks, every warning an error, and NullAway with JSpecify's semantics over every `@NullMarked` package. | `errorProneMain`, `errorProneTest` |
| R-LINT-3 | SpotBugs **shall** report nothing, at its most effort and lowest threshold. | `spotbugsMain`, `spotbugsTest` |
| R-LINT-4 | Checkstyle **shall** report nothing. | `checkstyleMain`, `checkstyleTest` |
| R-LINT-5 | A `@SuppressWarnings` **shall** name the one check it suppresses and carry a comment citing the rule and why. | review |

### R-DEP: dependencies

| ID | Rule | Checked by |
|---|---|---|
| R-DEP-1 | The jar **shall** depend on JDK modules alone (D5). | the POM; review |
| R-DEP-2 | A tool or library the build takes **shall** be named in D5, with its license, and its version in `gradle/libs.versions.toml`. | review |
| R-DEP-3 | It **shall** be at a stable release, production-grade by its own project's claims. | review |

### R-PLAT: platforms

| ID | Rule | Checked by |
|---|---|---|
| R-PLAT-1 | The driver **shall** run on Linux, macOS and Windows, and CI **shall** test it on each. | CI |
| R-PLAT-2 | What works on one platform alone, such as a Windows named pipe, **shall** be refused clearly on the others, with a `TurborelException`, never left to fail some other way. | tests; review |
| R-PLAT-3 | Paths **shall** be built with `java.nio.file`, never by joining strings with a separator. | review |

---

## 4. Test rules

### R-TEST: how tests are written

| ID | Rule | Checked by |
|---|---|---|
| R-TEST-1 | Tests **shall** use JUnit Jupiter, in `src/test/java`, in the package of the code they test (D8). | review |
| R-TEST-2 | Tests **shall** derive from the protocol and turborel-client, not from the code: normal cases, boundaries, and robustness cases with the exact SQLCODE and SQLSTATE expected. | review |
| R-TEST-3 | A test's name **shall** say the behaviour checked. | review |
| R-TEST-4 | Tests **shall** be deterministic and independent: no clock, randomness, or network beyond loopback, and no order between them. A live test runs against a server it starts on loopback. | review |
| R-TEST-5 | Every fixed defect **shall** get a regression test that fails without the fix, its doc comment saying so. | review |
| R-TEST-6 | A fixture or helper shared by test classes **shall** be written once, in a class of its own in the test tree. | review |

### R-TRACE: traceability

| ID | Rule | Checked by |
|---|---|---|
| R-TRACE-1 | Every test **shall** have a doc comment beginning `Verifies:` and a tag of D8. | `./gradlew traceability` |
| R-TRACE-2 | Code implementing a requirement **shall** cite it (R-DOC-4). | review |
| R-TRACE-3 | CONFORMANCE.md **shall** change with the change that implements or changes a row of it. | review |

---

## 5. Coverage

| ID | Rule | Checked by |
|---|---|---|
| R-COV-1 | Coverage **shall** be measured by JaCoCo over the tests, and **shall not** fall below the floor in `build.gradle.kts`, 95 % of instructions and of branches. | `jacocoTestCoverageVerification` |
| R-COV-2 | The floor only rises; a change that raises coverage **should** raise it. | review |
| R-COV-3 | Gaps are closed by tests of a requirement, or by removing dead code, never by tests that cite nothing. | review |

---

## 6. Deviations

| ID | Rule | Checked by |
|---|---|---|
| R-DEV-1 | Every deviation from a rule **shall** be recorded here or beside it in the code, with its reason. | review |

The permanent deviations:

- Error Prone compiles on JDK 21, not on the JDK 17 that compiles what is
  published (D4), since it runs on no older JDK.
- `gradlew.bat` keeps CRLF line endings (`.gitattributes`), since cmd.exe
  misreads a batch file's labels in LF lines. Every other text file is LF.
- Tests may use literal numbers (R-CONST-4), and their private helpers need
  no doc comment (`config/checkstyle/suppressions.xml`).

---

## 7. Process and tools

| ID | Rule | Checked by |
|---|---|---|
| R-PROC-1 | `./gradlew check` **shall** pass before every commit: formatting, javac's lints, Error Prone and NullAway, Checkstyle, SpotBugs, Javadoc, the tests, their citations and the coverage floor. The pre-commit hook runs its fast part; CI runs all of it on each platform. | pre-commit hook; CI |
| R-PROC-2 | Every change **shall** be reviewed against the checklist below. | review |
| R-PROC-3 | A threshold's change **shall** change this file and the build's configuration in the same commit. | review |
| R-PROC-4 | Commits **should** be small and single-purpose, in CONTRIBUTING.md's form. | review |
| R-PROC-5 | Nothing **shall** be published except by the owner, as VERSIONING.md says. | review |
| R-CM-1 | The wrapper, its distribution's checksum and the version catalog **shall** be under version control, and a change of any is its own commit. | review |

The review checklist:

- the protocol or turborel-client cited in the code (R-DOC-4) and the
  tests (R-TRACE-1);
- normal, boundary and robustness tests (R-TEST-2), and a failing-first
  test for a fix (R-TEST-5);
- every failure a `TurborelException` (R-ERR-1), nothing held without
  bound (R-ERR-4);
- no magic numbers or strings (R-CONST);
- comments that explain why (R-DOC-2);
- CONFORMANCE.md updated (R-TRACE-3), and TODO.md where open work changed.

---

## 8. References

- turborel's `doc/client/05-protocol.md`, *The Native Protocol*, version 1;
  `doc/client/02-library.md`, *The Client Library*; and
  `doc/client/02-library-connections.md`, *Settings, Pipelines and Pools*:
  the requirements.
- turborel's crates `turborel-protocol` and `turborel-client`: the
  reference implementation.
- JSR 221, *JDBC 4.3 Specification*.
- RFC 5802, *Salted Challenge Response Authentication Mechanism (SCRAM)*;
  RFC 7677, *SCRAM-SHA-256 and SCRAM-SHA-256-PLUS*; RFC 9266, *Channel
  Bindings for TLS 1.3*; RFC 5929, *Channel Bindings for TLS*.
- RFC 1950, *ZLIB Compressed Data Format*.
- *The Java Language Specification* and *The Java Virtual Machine
  Specification*, Java SE 17; JEP 261, *Module System*.
- JSpecify 1.0, *Nullness*.
