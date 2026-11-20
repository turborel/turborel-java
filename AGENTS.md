# Guide for coding agents

How to work on turborel-java as a coding agent, with any tool. If this
file disagrees with [RULES.md](RULES.md) or
[CONTRIBUTING.md](CONTRIBUTING.md), follow those.

turborel-java is the Java driver for turborel, a relational database
system: a JDBC 4.3 driver, `com.turborel:turborel-jdbc`, that speaks
turborel's native protocol in Java alone. See [README.md](README.md).

## Before you change anything

Read RULES.md §3 (code), §4 (tests), §7 (process) and the §1 decisions for
your area. Read CONTRIBUTING.md for how a change is made, and the extra
checks for changes that touch the protocol.

Read the section of the protocol or of turborel-client's documents the
change implements, in turborel's repository: `doc/client/05-protocol.md`,
`doc/client/02-library.md` and `doc/client/02-library-connections.md`.
Where they and turborel-client's code (`client/src/`, `protocol/src/`)
differ, the code is right.

## Build and check

Every change must pass `./gradlew check` before it is handed back. The
build needs JDK 17 and JDK 21 (RULES.md D4).

```
./gradlew check                 # every check a change must pass
./gradlew spotlessApply         # format the sources (R-STYLE-1)
./gradlew test                  # the tests alone, on JDK 17
./gradlew test -PtestJava=21    # the tests on another JDK
./gradlew traceability          # every test cites what it verifies (R-TRACE-1)
./gradlew errorProneMain        # Error Prone and NullAway alone (R-LINT-2)
./gradlew jacocoTestReport      # coverage report, build/reports/jacoco/
./gradlew build                 # the jars, in build/libs/
git config core.hooksPath .githooks   # the fast checks before every commit (once per clone)
```

A JDK Gradle does not find is named with
`-Dorg.gradle.java.installations.paths=<jdk17>,<jdk21>`. Gradle never
downloads a JDK.

`check` runs javac with `-Xlint:all -Werror`, Error Prone and NullAway,
Spotless, Checkstyle, SpotBugs, Javadoc, the tests, their citations and
the coverage floor. CI runs it on Linux, macOS and Windows, on Java 17, 21
and 25. The coverage floor is in `build.gradle.kts` and only rises
(R-COV-2).

Project automation is the Gradle build, never shell scripts (D4).

## Where things are

| Path | What |
|---|---|
| `src/main/java/module-info.java` | the module `com.turborel.jdbc`; exports the API package alone |
| `src/main/java/com/turborel/jdbc/` | the JDBC API (D2) and `TurborelException` (D7) |
| `src/main/java/com/turborel/jdbc/internal/codec/` | the protocol's codec (D1) |
| `src/test/java/` | the tests, in the packages they test (D8) |
| `build.gradle.kts` | the build and its checks (D4) |
| `gradle/libs.versions.toml` | every version the build uses (D5) |
| `config/checkstyle/` | the code rules Checkstyle checks |
| `doc/` | the manual: `user/`, `client/`, `appendix/` |

Each later layer is a package of `com.turborel.jdbc.internal` named after
its commit area: `transport`, `auth`, `tls`, `session`, `statements`,
`lobs`, `copy`, `settings`, `pipeline`, `pool` (RULES.md §2).

## Rules that are broken most often

`check` treats every warning as an error. RULES.md §3 and §4 have the full
list.

- Every failure is a `TurborelException` with SQLCODE and SQLSTATE
  (R-ERR-1). No `IllegalArgumentException`, `IndexOutOfBoundsException`,
  `BufferUnderflowException` or `NullPointerException` may escape library
  code, whatever the server sends.
- A message the driver makes begins with what it cites,
  `[PROTOCOL <heading>]`, `[CLIENT <heading>]` or `[DESIGN]` (R-ERR-2).
- Nothing a server sends may make the driver allocate without bound:
  check a count or length against what remains first (R-ERR-4).
- The protocol's `u32` and `u64` are unsigned: compare them with
  `Integer.toUnsignedLong` or `Long.compareUnsigned` (R-NUM-1).
- No `catch` of `Exception`, `RuntimeException` or `Throwable`, and no
  empty `catch` (R-ERR-3).
- Methods of at most 50 lines and 5 parameters, files of at most 600 lines
  (R-SIZE).
- No magic numbers or strings; message kinds and tags are named constants
  (R-CONST).
- Every public and protected item has a Javadoc comment with its `@param`,
  `@return` and `@throws` (R-DOC-1). Every package has a
  `package-info.java`, `@NullMarked` (R-DOC-6).
- Comments say why (R-DOC-2). No `TODO` comments or commented-out code
  (R-DOC-5).
- No printing and no `System.exit` (R-STYLE-4).
- No dependency at run time, and no build tool without a decision in
  RULES.md D5 (R-DEP).
- Only `com.turborel.jdbc` is exported; everything else is `internal`
  (R-STYLE-6).
- Windows-only or Unix-only code is refused clearly elsewhere (R-PLAT-2).

## Tests

Every test has a doc comment beginning `Verifies:` and a tag
`./gradlew traceability` accepts (R-TRACE-1): `[PROTOCOL <heading>]` of
`05-protocol.md`, `[CLIENT <heading>]` of `02-library.md` or
`02-library-connections.md`, or `[DESIGN] D<n>`. For example
`Verifies: [PROTOCOL Frames] a body past the limit fails the connection`.

Tests are deterministic, with no clock, randomness or network beyond
loopback (R-TEST-4). Fixtures shared by test classes are written once
(R-TEST-6). A fixed defect gets a regression test that fails without the
fix (R-TEST-5).

## Citing the spec

Check every heading against its page before citing it, and never cite
from memory. Code never cites TODO.md, and Checkstyle refuses it
(R-DOC-5).

## Documents

A finished feature updates these in the same change:

- RULES.md, if it made a decision, including the row in §1's table
- CONFORMANCE.md, for a row implemented or changed (R-TRACE-3)
- TODO.md, which only holds open work
- [doc/](doc/README.md), for anything a program sees: the User's Guide in
  `doc/user/`, the API Reference in `doc/client/`, the appendices in
  `doc/appendix/`
- README.md, if what the driver has changes

turborel.com's documentation site reads `doc/` as it is: a page's title
is its first `# H1`, a chapter's next line is `[Contents](README.md)`, and
links between pages are relative `.md` links. Keep the layout and the
file names as they are; a new page goes in its volume's README.

Every claim in a document matches the code. No benchmarks or timings go in
the documents.

## Commits

- Commit only when the maintainer asks, and never push without approval.
- Follow CONTRIBUTING.md's form, with one logical change per commit.
- Add no AI or agent attribution to a commit, a pull request, a file or
  the package's metadata. That means no `Co-authored-by:` for an agent, no
  "Generated with" and no `Signed-off-by:`.
- Stage with an explicit path list, `git add -- <path> ...`. Never use
  `git add -A` or `git add .` on the whole tree.
- Never stage `LICENSE` or scratch files.
- Code for one platform only can't be verified locally on another. Say so
  when handing it back.
