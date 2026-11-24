# turborel-java rules

How turborel-java is designed, written and verified. Every change follows
these rules; `./gradlew check` checks those a tool can check, and
review checks the rest.

Rules (R-…) say how code and tests are written. **shall** is mandatory,
and a departure needs a decision recorded here (§7). **should** is
expected, and a reviewer may accept a reasoned exception.

Decisions (D1…) record how the driver is built and why. Change one only
by changing this file in a reviewed commit. A decision says what was
chosen and why, and the module it names holds the mechanism.

Cite both by ID in code, tests, reviews and commit messages, as in
`RULES.md D6` or `R-ERR-2`.

The rules are modelled on Turborel's own
[RULES.md](https://github.com/turborel/turborel/blob/main/RULES.md),
adapted to drivers. Every Turborel driver keeps the same requirement IDs
with the same meaning, and the same decisions D1 to D8, so a rule is
cited the same way in every repository; a driver's own decisions begin
at D9, and its own requirements are in families of their own. Where
Java's library conventions and a rule here disagree, the rule is
kept, and D8 lists where.

Before changing code, read §3 (code rules), §4 (test rules) and the
decisions covering your area. What the driver implements is in
[CONFORMANCE.md](CONFORMANCE.md), and open work is in [TODO.md](TODO.md).

[1 Decisions](#1-decisions) · [2 Layout](#2-project-layout) ·
[3 Code](#3-code-rules) · [4 Tests](#4-test-rules) ·
[5 Coverage](#5-coverage) · [6 Process](#6-process-and-tools) ·
[7 The decisions](#7-the-decisions) · [8 References](#8-references)

---

## 1. Decisions

Each is told in full in [§7](#7-the-decisions).

| # | Decision |
|---|---|
| **Shared by every Turborel driver** | |
| D1 | The native protocol, version 1, in layers that follow turborel-client |
| D2 | The language's standard API, over a low-level client of turborel-client's features |
| D3 | The runtime minimum follows the ecosystem's own support |
| D4 | One command runs every check, before each commit and in CI on three platforms |
| D5 | The version is the Turborel release's: 0.0.0 in the alpha, speaking protocol 1 |
| D6 | Tests and code cite Turborel's documents by their headings |
| D7 | A coverage floor, enforced in CI, that only rises |
| D8 | Where these rules depart from Java's conventions |
| **This driver's own** | |
| D9 | Every failure is a `TurborelException`, a `SQLException` |
| D10 | Gradle builds it, with JDK 17's compiler, and the checks run on JDK 21 |
| D11 | One jar, a JPMS module, published to Maven Central with its sources and Javadoc |
| D12 | Tests are JUnit Jupiter's, in the packages they test |
| D13 | TLS is the JDK's, and the platform's authorities are the JDK's trusted ones |
| D14 | JDBC's types are mapped from each column's declared type, else as the server sends it |

---

## 2. Project layout

| Path | What |
|---|---|
| `src/main/java/module-info.java` | the module `com.turborel.jdbc`, which exports the API package alone (R-MOD-1) |
| `src/main/java/com/turborel/jdbc/` | the layer `api`: the standard API (D2) and `TurborelException` (D9) |
| `src/main/java/com/turborel/jdbc/internal/codec/` | the layer `codec` (D1) |
| `src/main/java/com/turborel/jdbc/internal/<layer>/` | each later layer of D1, a package named after its commit area: `transport`, `auth`, `tls`, `session`, `statements`, `lobs`, `copy`, `settings`, `pipeline`, `pool` |
| `src/test/java/` | the tests, in the packages of the code they test (D12) |
| `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` | the build and its checks (D10) |
| `gradle/libs.versions.toml`, `gradle/wrapper/` | every version the build uses, and the wrapper |
| `buildSrc/` | the checks tool's own tasks: citations and the manual's layout (D4) |
| `config/checkstyle/` | the rules Checkstyle checks |
| `config/headings.txt` | the headings of Turborel's three documents, at the commit CONFORMANCE.md names (D6) |
| `doc/` | the manual |
| `.github/`, `.githooks/` | CI, issue forms, labels, Dependabot; the pre-commit hook |

---

## 3. Code rules

The tools named by "the formatter", "the linters", "the type checker" and
"the checks tool" are D4's.

### R-STYLE: formatting and idiom

| ID | Rule | Checked by |
|---|---|---|
| R-STYLE-1 | Code **shall** be formatted by the formatter. | the formatter |
| R-STYLE-2 | Names **shall** follow the language's own conventions; code implementing a message, field or setting is named after it, as the spec names it. | the linters; review |
| R-STYLE-3 | Library code **shall not** print, or write to a stream it was not given. | the linters or the checks tool |
| R-STYLE-4 | What is not public **shall** be private, by the language's own means, so the public API is what is documented. | review |

### R-SIZE: short functions and files

| ID | Rule | Checked by |
|---|---|---|
| R-SIZE-1 | A function **shall** have at most 50 lines of code, blank lines and comments not counted. | the linters or the checks tool |
| R-SIZE-2 | A source file **shall** have at most 600 lines; split by the spec's sections or by layer. | the linters or the checks tool |
| R-SIZE-3 | A function **shall** take at most 5 parameters; else a structure or an options object. | the linters |
| R-SIZE-4 | A module **should** hold one concept, stated in its doc comment. | review |
| R-SIZE-5 | A function's complexity **shall** be at most 15, and its blocks nested at most 5 deep. | the linters |

### R-DOC: comments and documentation

| ID | Rule | Checked by |
|---|---|---|
| R-DOC-1 | Every public item **shall** have a doc comment in the language's documentation format, saying each parameter, what it returns, and each failure it gives. | the linters; review |
| R-DOC-2 | Comments **shall** say *why*: the section implemented, the choice made, the invariant relied on. | review |
| R-DOC-3 | The API reference, where the language's tools build one, **shall** build without warnings. | the checks tool |
| R-DOC-4 | Behaviour the protocol or turborel-client governs **shall** cite it by its heading, `[PROTOCOL Frames]`, `[CLIENT Pools]`; the driver's own choices cite their decision (D6). | review; the checks tool |
| R-DOC-5 | No commented-out code, and no `TODO`, `FIXME` or `XXX` comments: open work goes in TODO.md, and code never cites TODO.md. | the linters or the checks tool |
| R-DOC-6 | Every source file **shall** begin with a doc comment giving its responsibility and the sections it implements. | review |
| R-DOC-7 | A change a program can see **shall** update the manual, `doc/`, in the same commit; every claim is checked against the code, and every example is run. The manual keeps Turborel's layout, which turborel.com's documentation site reads: each page opens with its `# Title` and a line holding only `[Contents](README.md)`, each volume's README.md lists its pages, links within `doc/` and to other files of this repository are relative links that resolve (the site points the latter at GitHub, at the same tag), a link to Turborel's own manual is its GitHub address under `https://github.com/turborel/turborel/blob/main/doc/`, `user/01-installing.md` keeps its name, and `doc/` holds no images. | review; the checks tool |

### R-CONST: no magic numbers or strings

| ID | Rule | Checked by |
|---|---|---|
| R-CONST-1 | Numbers in library code other than `0` or `1` **shall** be named constants, documenting the value's source. | the linters; review |
| R-CONST-2 | Every protocol tag **shall** be a named constant, defined once: a message's kind, a data type's and a value's tag, a tail tag, a capability's name, a mechanism's name, a status name, and each SQLSTATE the driver raises. | review |
| R-CONST-3 | Strings matched or looked up, such as settings' names, URI parts and environment variables, **shall** be named constants. Messages for people are not. | review |
| R-CONST-4 | Related constants **shall** live together in their layer's module, under a comment naming the section they come from. | review |
| R-CONST-5 | Tests may use literal fixtures, as the spec prints its bytes; an expected value restating a constant **should** use it. | review |

### R-ERR: errors

| ID | Rule | Checked by |
|---|---|---|
| R-ERR-1 | Every failure the library gives **shall** be the driver's error (D2): no stray exception, panic, abort, assertion or other error of the language or its libraries reaches a program, and no library code ends the process. | the linters; tests; review |
| R-ERR-2 | Each failure **shall** carry its SQLCODE, SQLSTATE and message, and, once read from a server, the rest of it: the rule it cites, its position, whether a transaction is still active, and its diagnostics. A failure the driver raises itself carries the SQLCODE and SQLSTATE turborel-client gives the same failure. | tests |
| R-ERR-3 | No failure **shall** be silenced. One caught and given again as the driver's keeps its cause; a result left unread says why it cannot matter. | the linters; review |
| R-ERR-4 | A connection that fails **shall** be closed, and every request waiting on it given that failure; a failed call leaks nothing and leaves nothing waiting. | tests; review |

### R-NUM: integers and numbers

| ID | Rule | Checked by |
|---|---|---|
| R-NUM-1 | Every integer of the protocol **shall** be read and written at its width, little-endian, explicitly: never by the platform's byte order, by casting a buffer, or by bitwise operators on numbers wider than the language's operators keep exact. | review; tests |
| R-NUM-2 | A value out of range for its field, or for the type it is read into, **shall** be refused with the driver's error, never truncated, wrapped or rounded. | tests |
| R-NUM-3 | An exact number (units `i128`, scale `u32`) and a DECFLOAT **shall** never pass through a binary floating-point type. | review; tests |
| R-NUM-4 | An approximate number **shall** keep its bits: NaN and -0.0 are read and written as the double they are, and floats are compared bit for bit, never for equality. | tests; review |
| R-NUM-5 | In a language whose integers are bounded, arithmetic on sizes, counts and offsets **shall** be shown not to overflow before it is done. | the linters; review |

### R-LINT: warnings

| ID | Rule | Checked by |
|---|---|---|
| R-LINT-1 | A warning is an error: of the compiler, the linters and the type checker, and of the runtime itself while the tests run. | the checks tool |
| R-LINT-2 | The strictest standard linters and type checking the language has **shall** be on; a check left off says why in the configuration that leaves it off. | review |
| R-LINT-3 | A warning **shall** be silenced only on its line, naming its rule and saying why; a suppression no longer needed **shall** be removed. | the linters; review |

### R-DEP: dependencies

| ID | Rule | Checked by |
|---|---|---|
| R-DEP-1 | The library **shall** depend on nothing but the language's standard library and runtime, unless a decision here admits a dependency, with its reason, version, license and review. | review |
| R-DEP-2 | A dependency, of the library or of its tools, **shall** be production-grade by its makers' own claims: a stable release they support. | review |
| R-DEP-3 | Tools **shall** be pinned by version, with the ecosystem's lock file under version control where it has one; a change of a tool's version is a commit of its own. | review |

### R-SAFE: what a server sends, and secrets

| ID | Rule | Checked by |
|---|---|---|
| R-SAFE-1 | A length or count a server, or a file the driver reads, gives **shall** be checked against what follows and the limit taken before anything is held for it, so no reply takes the program's memory without bound. | tests; review |
| R-SAFE-2 | What a server sends **shall** never be run, loaded or deserialized as code. | the linters; review |
| R-SAFE-3 | TLS **shall** be used and verified as the settings ask, and a connection **shall not** fall back to less than its mode allows. | tests |
| R-SAFE-4 | A secret (a password, anything derived from one, a SCRAM key or proof, a cancel key) **shall** never be logged, put in a failure's message or an object's display form, or sent where it was refused; where the language allows, it is cleared from memory once used. | tests; review |
| R-SAFE-5 | No module-level mutable state: what lasts belongs to an object a program made, such as a connection, a pool or a key cache. | review |
| R-SAFE-6 | The library **shall** start no thread, timer or task but for an object a program made, and a closed connection, cursor or pool leaves none behind. | tests; review |

### R-PLAT: platforms

| ID | Rule | Checked by |
|---|---|---|
| R-PLAT-1 | The driver **shall** run on Linux, macOS and Windows, on every runtime D3 supports; CI checks each (D4). | CI |
| R-PLAT-2 | Code of one platform, such as a Unix socket or a named pipe, **shall** be chosen by the platform, and elsewhere refused with the driver's error, saying so; never silently something else. | tests; review |
| R-PLAT-3 | Paths **shall** be made by the language's path functions, and files kept with LF line endings (`.gitattributes`). | review |
| R-PLAT-4 | Code built for one platform only **shall** say, when handed back, that it was not run on the others. | review |

### R-CF: control flow

| ID | Rule | Checked by |
|---|---|---|
| R-CF-1 | A boolean expression **shall** have at most 4 conditions; else a named predicate. | Checkstyle `BooleanExpressionComplexity` |
| R-CF-2 | A `switch` on an enum or a sealed type **should** list every case, with no `default` where the language lets the compiler check them. | review |
| R-CF-3 | Conditions **shall** have no side effects. | Checkstyle `InnerAssignment`; review |

### R-MOD: the module

| ID | Rule | Checked by |
|---|---|---|
| R-MOD-1 | The module **shall** export `com.turborel.jdbc` alone; every other package is under `com.turborel.jdbc.internal`. | `module-info.java`; review |
| R-MOD-2 | Every package **shall** have a `package-info.java` with its doc comment, marked `@NullMarked`, and NullAway **shall** pass over it. | Checkstyle `MissingJavadocPackage`; NullAway |

---

## 4. Test rules

### R-TEST: levels and layout

| ID | Rule | Checked by |
|---|---|---|
| R-TEST-1 | A test **shall** sit at the lowest level that shows its requirement: a codec test of bytes, a test of a layer over a stream the test controls, a test of the API, or a live test against a server. | review |
| R-TEST-2 | Tests **shall** derive from the spec and turborel-client, not from the code: normal cases, boundaries included, and robustness cases with the exact SQLCODE and SQLSTATE expected. | review |
| R-TEST-3 | A test's name **shall** say the behaviour checked. | review |
| R-TEST-4 | Test code may assert, print and use literal fixtures; every other rule applies. | the linters' configuration |
| R-TEST-5 | Tests **shall** be deterministic and independent: no clock, randomness, network beyond loopback, or order between them. A case generator uses a fixed seed. A live test runs against a server on loopback that its own set-up starts, on its own port and data directory. | review |
| R-TEST-6 | Every fixed defect **shall** get a regression test that fails without the fix, its doc comment saying so. | review |
| R-TEST-7 | A fixture or helper shared by tests **shall** be written once, in one place. Tests use the one framework D9 or later names, no other. | review |
| R-TEST-8 | Live tests **shall** run against `ghcr.io/turborel/turborel:nightly` in CI, set up by the repository's own server set-up, with test certificates, SCRAM users and SERVER.TRUST_LOCAL. | CI |
| R-TEST-9 | SCRAM **shall** be tested by RFC 7677's test vector, and settings by the URI, service file, password file and environment cases of turborel-client's settings tests, so every driver reads settings as turborel-client does. | review |

### R-TRACE: traceability

| ID | Rule | Checked by |
|---|---|---|
| R-TRACE-1 | Every test **shall** cite what it verifies, by its Javadoc comment, beginning `Verifies:`, beginning with a tag of D6: `[PROTOCOL <heading>]`, `[CLIENT <heading>]`, `[DESIGN] D<n>` or `[TOOL] R-<id>`. | the checks tool |
| R-TRACE-2 | A citation **shall** name a heading, decision or rule that exists. | the checks tool |
| R-TRACE-3 | Code implementing a requirement **shall** cite it (R-DOC-4). | review |
| R-TRACE-4 | CONFORMANCE.md **shall** change with the change that implements or changes a row. A row is marked done only where the driver's code for it exists and tests citing its section pass, or, of a driver built over another, as CONFORMANCE.md says. | review |

---

## 5. Coverage

| ID | Rule | Checked by |
|---|---|---|
| R-COV-1 | Coverage **shall** be measured by `./gradlew jacocoTestCoverageVerification`, over the library and every test. | the checks tool |
| R-COV-2 | Coverage **shall not** fall below the floor (D7); a change raising it **should** raise the floor. | the checks tool, in CI |
| R-COV-3 | A gap is closed by a test derived from a requirement, or by removing dead code; never by a test of no requirement, and never by excluding the code. | review |

---

## 6. Process and tools

| ID | Rule | Checked by |
|---|---|---|
| R-PROC-1 | `./gradlew check` **shall** pass before every commit; CI adds every platform, every supported runtime and the coverage floor (D4). | pre-commit hook; CI |
| R-PROC-2 | Every change **shall** be reviewed against the checklist below. | review |
| R-PROC-3 | Commits **should** be small and single-purpose, in CONTRIBUTING.md's form (`area: summary`, then why), citing the rules applied or changed. | review |
| R-PROC-4 | A threshold change **shall** change RULES.md and the configuration that holds it together. | review |
| R-PROC-5 | Project automation **shall** be written in the language's own tooling, never shell scripts; the git hook only hands over to the checks tool. | review |
| R-PROC-6 | CI's actions **shall** be pinned by their full commit SHA, with the version in a comment. | review |
| R-PROC-7 | A release **shall** be published only as VERSIONING.md says, by a maintainer (D5). | review |

The review checklist:

- the spec or turborel-client cited in code (R-DOC-4) and tests
  (R-TRACE-1);
- normal and robustness tests (R-TEST-2), and a failing-first test for a
  fix (R-TEST-6);
- every failure the driver's, carrying its condition (R-ERR);
- no magic numbers or strings, protocol tags named (R-CONST);
- every length a server sends bounded, no secret disclosed (R-SAFE);
- comments explain why (R-DOC-2);
- CONFORMANCE.md (R-TRACE-4), the manual (R-DOC-7) and TODO.md updated
  where they change.

---

## 7. The decisions

### Shared by every Turborel driver

#### D1. The native protocol, version 1, in layers that follow turborel-client

The driver speaks Turborel's native protocol, version 1, as
`doc/client/05-protocol.md` in Turborel's repository lays it out,
itself, in Java alone, with no native code and no library beyond the JDK. Where that page and turborel-protocol's code differ,
the code is right, and the driver follows it; CONFORMANCE.md names each
such place.

The reference is turborel-client. The driver does all it does before it
is called done, row by row in CONFORMANCE.md, and is built in the same
layers as turborel-client's modules, so a reviewer checks one against
the other. Each layer is a commit area of CONTRIBUTING.md.

| Layer | What it covers | turborel-client |
|---|---|---|
| `codec` | the 5-byte frame header; fields; the tail and its tag order; the data types and values; JSON values; zlib `z` frames within the length bound; the 16 MiB body limit | `protocol/src` |
| `transport` | TCP on 7422; Unix sockets; the Windows named pipe `\\.\pipe\turborel`; TLS begun on the same port; the connect and read timeouts | `target.rs`, `timeouts.rs`, `pipe.rs` |
| `auth`, `tls` | trust, password, certificate, SCRAM-SHA-256 and -PLUS; secrets refused over plain TCP; the SCRAM key cache; TLS | `mechanism.rs`, `scram.rs`, `tls.rs` |
| `session` | Hello, Ready, Status, notices, conditions, diagnostics, autocommit, cancel, terminate, ping, reset, change-user, query attributes, multi-statement | `connection.rs`, `session.rs`, `cancel.rs`, `told.rs` |
| `statements` | queries and streamed queries; prepare, execute, fetch, close, deallocate; numbered cursors; describe; completions | `statements.rs`, `frames.rs` |
| `lobs`, `copy` | locators, their reads, writes and streams; copy, import and export | `lobs.rs`; `copy.rs`, `import/`, `export.rs` |
| `settings` | the URI, options, variables, service and password files, several hosts, roles, TLS modes, channel binding | `settings/`, `failover.rs`, `role.rs` |
| `pipeline`, `pool` | pipelines; pools; the SQL-agent | `pipeline.rs`; `pool/`, `agent.rs` |
| `api` | the standard API (D2) | the language's own |

Each layer is a package of `com.turborel.jdbc.internal`, named after its
area, `internal.codec` first, and the standard API is the exported
package `com.turborel.jdbc` (R-MOD-1). TLS is the JDK's (JSSE), SCRAM's
hashes and keys `java.security` and `javax.crypto`, and compressed
frames `java.util.zip`. SCRAM-SHA-256-PLUS binds by `tls-exporter` where
the JDK gives the exporter, and else by `tls-server-end-point`.

#### D2. The language's standard API, over a low-level client of turborel-client's features

The driver's standard interface is JDBC 4.3, as the JPMS module `com.turborel.jdbc`, so a program, and the
tools built for Java's database interface, use it as they use
any other driver. It sits over a low-level client that has every feature
of turborel-client. What the standard interface has no place for, such
as pipelines, pools, copy, large objects by locator, notices and the
session's status, is reached through the low-level client or the
driver's own extensions of the standard interface.

Every failure is the driver's error, carrying its SQLCODE and SQLSTATE
(R-ERR). The standard interface is built at milestone M7 and checked in
M8's audit; until then its names may change.

The driver is a `java.sql.Driver`, found by `ServiceLoader`, of URLs that
begin `jdbc:turborel:`, with its connections, statements, prepared
statements, result sets, metadata and a `javax.sql.DataSource`. GUI
tools, DBeaver and DataGrip among them, load a JDBC driver as it is,
which is why the Java driver comes first among Turborel's. The error is
`TurborelException` (D9), a `SQLException`. An exact number is a
`BigDecimal`, unless its column was declared SMALLINT, INTEGER or
BIGINT (D14). What JDBC has no place for a program reaches by `unwrap`,
JDBC 4.3's `Wrapper`, from a JDBC object to an interface of the exported
package; the low-level client itself lives in packages the module does
not export, so it may change without breaking a program.

#### D3. The runtime minimum follows the ecosystem's own support

The driver supports Java 17 or later. The rule is the ecosystem's own:
support every release its makers still support, and drop each at its
end of life. Where a major framework of the language sets a higher
floor, the driver matches it. The minimum changes in a commit of its
own, which changes this decision, the package's metadata, CI and
README.md together.

The library is compiled for release 17 (`--release 17`) and runs on Java
17 and every later release. Spring Boot 4 and Spring Framework 7 keep
Java 17 as their floor, and a driver an application cannot load is no
use to it, so the driver keeps it too. CI tests Java 17, 21 and 25, the
long-term-support releases. Java 17 has records, sealed types, switch
expressions and `UnixDomainSocketAddress`, all the driver needs.

#### D4. One command runs every check, before each commit and in CI on three platforms

`./gradlew check` runs every check a change must pass, stopping at the
first failure: the formatter, the linters, the type checker where the
language has one, the checks tool's own rules (lengths a linter does not
measure, citations and their targets, open-work markers, the manual's
titles, `[Contents](README.md)` lines and links), and every test.
`./gradlew jacocoTestCoverageVerification` measures coverage and holds it to its floor (D7),
over every test: the live tests run only where `TURBOREL_TEST_SERVER`
names a server, so it is run with one named.

`.githooks/pre-commit` hands over to the same command. It is a script
only because git runs hooks as scripts. CI runs it on Linux, macOS and
Windows, over every runtime D3 supports, with the live tests against the
nightly image and coverage on Linux. Every action is pinned by its full
commit SHA, its version in a comment, and Dependabot proposes each bump.

`./gradlew check` runs, in Gradle: javac with `-Xlint:all -Werror`, and
`-Xdoclint:all/protected` on the library; Error Prone and NullAway; the
formatter, Spotless with palantir-java-format; Checkstyle, for the rules
a linter can check; SpotBugs, at its most effort and lowest threshold;
Javadoc with `-Werror`; the JUnit Jupiter tests; the checks tool's tasks
in `buildSrc/`, `traceability`, of every citation and the manual's
layout. The hook runs it all; the coverage floor is a command of its
own, above. Java's type
checker is javac, with NullAway over JSpecify's `@NullMarked`.

| Tool | What for | License |
|---|---|---|
| Gradle and its wrapper | the build | Apache-2.0 |
| Error Prone | bug patterns, as the code compiles | Apache-2.0 |
| NullAway | nullness, as the code compiles | MIT |
| JSpecify | nullness annotations, compile only | Apache-2.0 |
| Spotless, with palantir-java-format | the formatter | Apache-2.0 |
| Checkstyle | the code rules a tool can check | LGPL-2.1-or-later |
| SpotBugs | bug patterns, in the classes | LGPL-2.1-or-later |
| JUnit Jupiter | the tests | EPL-2.0 |
| JaCoCo | coverage | EPL-2.0 |

Each is pinned in `gradle/libs.versions.toml`, at a stable release.

#### D5. The version is the Turborel release's: 0.0.0 in the alpha, speaking protocol 1

The driver has no version scheme of its own. Its version is the number
of the Turborel release it is made for, the same as the server's: a
Turborel release and each driver's release for it share the number,
**MAJOR.MINOR.PATCH**, as Turborel's VERSIONING.md numbers it. The alpha
is 0.0.0, as Turborel's is, and speaks protocol version 1.

The number is written once, in `build.gradle.kts`'s `version`. Nothing is
published, and no release is tagged, until every Turborel driver is
done; then the maintainers publish them together (VERSIONING.md).

#### D6. Tests and code cite Turborel's documents by their headings

A test cites what it verifies, and code what it implements, by the
heading of the section that says it, as printed:

- `[PROTOCOL <heading>]`, a heading of The Native Protocol,
  `doc/client/05-protocol.md`, as `[PROTOCOL Frames]`;
- `[CLIENT <heading>]`, a heading of The Client Library,
  `doc/client/02-library.md`, or of Settings, Pipelines and Pools,
  `doc/client/02-library-connections.md`, which share no heading, as
  `[CLIENT Pipelines]`;
- `[DESIGN] D<n>`, a decision of this file;
- `[TOOL] R-<id>`, a rule the checks tool holds, for the tool's own
  tests.

What else a test relies on follows the tag, as an RFC by its section:
`[PROTOCOL Mechanisms] [RFC 7677 §3]`. The heading `Lob 'L'` is printed
twice in The Native Protocol, under Requests and under Replies, so a
citation of it says which it means.

The checks tool keeps the headings of those three documents, copied at
the Turborel commit CONFORMANCE.md names, and refuses a citation of a
heading, decision or rule that is not there (R-TRACE-2). In Java,
a test cites by its Javadoc comment, beginning `Verifies:`.

The protocol's sample frames, `doc/client/05-protocol-frames.tsv`, are
copied into the tests' resources at a commit of Turborel's main, at
1afa44c2 now, beside the code that reads them
(`src/test/resources/com/turborel/jdbc/internal/codec`). Copying them,
rather than reading main's file as the tests run, keeps each run's
outcome the commit's own; a change of the file comes in a commit that
copies it anew, with the headings.

#### D7. A coverage floor, enforced in CI, that only rises

`./gradlew jacocoTestCoverageVerification` measures the library's coverage over every test,
and CI fails below the floor. In every Turborel driver the floor is at
least 90 % of lines, and of branches where the tool measures them. It
only rises: a change that raises coverage well above it raises it. A
floor below 100 % says why.

The floor is 97 % of instructions and of branches, kept in
`build.gradle.kts`'s `jacocoTestCoverageVerification`. It is below 100 %
because a few refusals guard what a test cannot make, such as a body
longer than a Java array holds.

#### D8. Where these rules depart from Java's conventions

The driver follows its language's library conventions for layout,
naming, packaging, documentation and testing. Where one conflicts with a
rule here, the rule is kept:

- Libraries commonly leave the length of functions and files to taste.
  These are bounded (R-SIZE).
- Libraries commonly fail an argument out of range with the language's
  own error. Here every failure is the driver's, an argument's too, with
  the SQLCODE and SQLSTATE turborel-client gives it (R-ERR-1), so a
  program handles one kind of failure.

- The driver's own types name an accessor as a record does, `sqlcode()`;
  JDBC's `getX` is kept where JDBC defines it.
- A method that overrides one of JDBC's keeps JDBC's parameters, more
  than 5 where JDBC has more, as `DatabaseMetaData.getCrossReference`
  does (R-SIZE-3).
- `gradlew.bat` keeps CRLF line endings, since cmd.exe misreads a batch
  file's labels in LF lines; every other file is LF (R-PLAT-3).
- A test's private helpers need no Javadoc comment of their own.

Where the driver departs from turborel-client, so every Turborel driver
behaves alike:

- A value of tag 18 or 19, a large object's type, which
  turborel-protocol reads as a value, is refused. Its frame is whole, so
  it fails as any value the client cannot take: its request alone,
  SQLCODE -916 and SQLSTATE TB916, the rest of its replies read past,
  the connection kept (`[PROTOCOL Values and fields]`).
- Attributes past their bounds, more than 32, a name not of 1 to 64
  bytes or a value past 1024, are refused before anything is sent, with
  the code the server would fail the connection with, SQLCODE -919 and
  SQLSTATE 08006, and the connection kept. turborel-client sends them,
  and the server then fails the connection.
- A named pipe is opened as the JDK opens a file, which gives no
  Windows error code and sets no security quality of service. So a pipe
  whose every instance is busy is told by the system's message, which
  says "busy" in English, and is tried again until the connect timeout,
  where turborel-client tells it by ERROR_PIPE_BUSY; and the server is
  not limited to identifying the client, where turborel-client asks for
  SECURITY_IDENTIFICATION. Either needs native code (D1).
- Channel binding `require` with settings that can bind nothing is
  refused before any connection is made, SQLSTATE 28000, as Settings,
  Pipelines and Pools says (`[CLIENT TLS by mode]`). turborel-client
  connects first and refuses after, so of a server it cannot reach it
  gives 08006 instead.

### This driver's own

#### D9. Every failure is a `TurborelException`, a `SQLException`

Every failure the driver reports, its own or the server's, is a
`com.turborel.jdbc.TurborelException`, which extends
`java.sql.SQLException`. Its SQLSTATE is the `SQLException`'s, and its
SQLCODE the vendor code, so `getErrorCode()` gives it to a tool that
knows only JDBC, and `sqlcode()` to one that knows the driver. A failure
the server sends carries the rest of the protocol's failure too: the rule
it cites, its position, whether a transaction is active after it, and
its diagnostics.

A connection a peer's message broke fails with SQLCODE -919, SQLSTATE
08006 (`internal.codec.Broken`), as Turborel's does.

JDBC requires failures of its own types, and the driver gives them,
each carrying the driver's SQLSTATE and SQLCODE: a batch that fails is a
`BatchUpdateException` of every count, its cause the `TurborelException`
of the first statement that failed; a syntax error or access rule
violation, SQLSTATE class 42 (and 2A and 37 of older servers), of a
statement run or prepared, is a `SQLSyntaxErrorException` of its
SQLSTATE, SQLCODE and message, its cause the `TurborelException` that
carries all of it; and `getParentLogger`, of a driver that logs nothing,
is a `SQLFeatureNotSupportedException`.

#### D10. Gradle builds it, with JDK 17's compiler, and the checks run on JDK 21

The build is Gradle's, in the Kotlin DSL, run by the wrapper committed
with it, its distribution held to its SHA-256, with every version in
the catalog `gradle/libs.versions.toml`. The library and its tests are
compiled by a JDK 17 toolchain for release 17, so the classes published
are JDK 17's compiler's. Error Prone and Checkstyle run only on JDK 21 or
later, so Checkstyle runs on a JDK 21 toolchain, and Error Prone compiles
the sources a second time on JDK 21, still for release 17, its classes
thrown away (`errorProneMain`, `errorProneTest`). A build needs JDK 17
and JDK 21; Gradle finds them on the machine and never downloads one,
and a JDK it does not find is named by
`-Dorg.gradle.java.installations.paths`.

The jar depends on `java.base` and `java.sql` alone. JSpecify's nullness
annotations are read as the code compiles and absent at run time:
`compileOnly`, and `requires static` in `module-info.java` (R-DEP-1).

#### D11. One jar, a JPMS module, published to Maven Central with its sources and Javadoc

The driver is the Maven artifact `com.turborel:turborel-jdbc`, the JPMS
module `com.turborel.jdbc`, under the package `com.turborel.jdbc`. It is
published with its sources and Javadoc jars and a POM that names the
license, BSD-2-Clause-Patent, the project's URL and its source control,
as Maven Central requires. Each jar carries `LICENSE` in `META-INF`.
Until the drivers are published, the build stages a release in
`build/staging-deploy` and nothing leaves the machine.

#### D12. Tests are JUnit Jupiter's, in the packages they test

Tests use JUnit Jupiter, the one framework (R-TEST-7), under
`src/test/java`, in the package of the code they test, and run on the
class path, so they reach the internal packages. `-PtestJava=<n>` runs
them on another JDK, as CI does for each release D3 supports.

#### D13. TLS is the JDK's, and the platform's authorities are the JDK's trusted ones

TLS is JSSE, the JDK's own (`javax.net.ssl`), so the driver needs
nothing beyond the JDK. It checks the server's name as the JDK checks
an HTTPS server's, by the endpoint identification `HTTPS`.
SCRAM-SHA-256-PLUS binds by `tls-exporter` where the JDK exports keying
material, JDK 25 and later, and by the server's certificate before it.

turborel-client takes the platform's authorities from the operating
system's store, or from the files SSL_CERT_FILE and SSL_CERT_DIR name if
either is set. The driver takes the same two variables the same way, as
the crate rustls-native-certs reads them: SSL_CERT_DIR a list of
directories apart by the platform's separator, each file or directory
that cannot be read passed over, and none at all trusted a failure;
without them, it takes the JDK's own trusted authorities: its `cacerts`,
or the store `javax.net.ssl.trustStore` and `javax.net.ssl.trustStoreType`
name. For a Java program those are the platform's.

#### D14. JDBC's types are mapped from each column's declared type, else as the server sends it

A result set's and a parameter's types are those the server describes
in its Description and Prepared (`[PROTOCOL Data types]`). The tag a
value is written by names one exact number for SMALLINT, INTEGER,
BIGINT, DECIMAL and NUMERIC, its precision and scale alone, and one
approximate number for REAL, DOUBLE PRECISION and FLOAT. So the driver
asks for `declared-types` as it connects, as turborel-client does, and
the server ends each Prepared and Description with each column's
declared type, its name and as SQL writes it (`[PROTOCOL Declared
types]`).

A column's JDBC type is its declared type's, where the server told it
and JDBC numbers it: `getColumnType` gives `Types.INTEGER`, `SMALLINT`,
`BIGINT`, `DECIMAL`, `NUMERIC`, `REAL`, `DOUBLE` or `FLOAT`,
`getColumnTypeName` the declared name, a distinct type's own schema and
name, and `getObject` an `Integer` of an INTEGER or a SMALLINT, a `Long`
of a BIGINT and a `Double` of a FLOAT, as JDBC maps them. An array's
elements take the element type its declared type writes. Where no
declared type is told, of a server that does not grant the capability,
the column is mapped as the tag says: an exact number is DECIMAL of its precision and
scale and `getObject` gives a `BigDecimal` (D2), an approximate number
REAL to 24 binary digits and DOUBLE past them, a datetime of its kind,
and so on. The driver does not guess a declared type the server did not
tell. A parameter's type is always as its tag says, as the protocol
declares none for it. `getInt` and the other getters read any exact
number in their range.

DatabaseMetaData reads the Information Schema, which names each
column's type, so `getColumns` gives INTEGER, SMALLINT and BIGINT as
JDBC numbers them. A type JDBC has no number for, an interval, JSON, a
UUID or a multiset, is `Types.OTHER`, under its SQL name.

---

## 8. References

- Turborel, `doc/client/05-protocol.md`, *The Native Protocol*,
  version 1: the requirements of the codec and every message.
- Turborel, `doc/client/02-library.md`, *The Client Library*, and
  `doc/client/02-library-connections.md`, *Settings, Pipelines and
  Pools*, and the crates turborel-client and turborel-protocol: the
  features to match, and the reference where the spec is silent or
  differs.
- Turborel, `RULES.md` D89, the JSON value's binary tree, and D184, the
  UUID's tag.
- RFC 1950, *ZLIB Compressed Data Format Specification*; RFC 5802 and
  RFC 7677, SCRAM and SCRAM-SHA-256; RFC 5929 and RFC 9266, the
  `tls-server-end-point` and `tls-exporter` channel bindings.
- The standards of Java and of its database interface, named in
  D2 and D3.
