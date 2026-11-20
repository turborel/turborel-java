# Contributing to turborel-java

turborel-java carries a program's data, passwords and keys to a turborel
server and back. A driver that misreads a value returns a wrong result,
and one that leaks a key or skips a check of TLS exposes everything behind
it. So a change is judged first on whether it keeps values exact, secrets
secret and the protocol followed, and then on what it adds. The process
follows the Linux kernel's, adapted to pull requests.

[Before you start](#before-you-start) · [Setting up](#setting-up) ·
[Making a change](#making-a-change) ·
[Changes that touch the protocol](#changes-that-touch-the-protocol) ·
[Commit messages](#commit-messages) · [Pull requests](#pull-requests) ·
[Review](#review) · [Reporting issues](#reporting-issues) ·
[Security](#security) · [License](#license)

---

## Before you start

Read [RULES.md](RULES.md) §3 (code), §4 (tests), §7 (process) and the
decisions for your area. Every rule applies to every change, and
`./gradlew check` checks those a tool can.
[CONFORMANCE.md](CONFORMANCE.md) says what is implemented of the protocol
and of turborel-client, and [TODO.md](TODO.md) lists open work.

Open an issue first for anything larger than a fix. That covers new
features, changes to a decision in RULES.md, new dependencies (R-DEP) and
changes to the public API. Agreeing on the design first saves everyone a
rewrite.

The protocol is turborel's `doc/client/05-protocol.md`, and the client's
features are its `doc/client/02-library.md` and
`02-library-connections.md`. Read the section before writing code, and
cite it by its heading, as in `[PROTOCOL Frames]` or `[CLIENT Pipelines]`.
Never cite from memory. Where the page and turborel-client's code differ,
the code is right: build to it, and report the difference to turborel.

## Setting up

Install JDK 17 and JDK 21 (RULES.md D4). The Gradle wrapper comes with the
repository.

```
git config core.hooksPath .githooks   # the fast checks before each commit (once per clone)
./gradlew check                       # every check a change must pass
./gradlew spotlessApply               # format the sources
./gradlew test                        # the tests alone
./gradlew test -PtestJava=21          # the tests on another JDK
```

If Gradle does not find a JDK, name its home with
`-Dorg.gradle.java.installations.paths=<jdk17>,<jdk21>`, on the command
line or in `~/.gradle/gradle.properties` as
`org.gradle.java.installations.paths=...`.

CI runs `check` on Linux, macOS and Windows, on Java 17, 21 and 25, and
the coverage floor on Linux. A change that passes on one platform and fails
on another isn't done.

## Making a change

Keep one logical change per commit, and make each commit pass
`./gradlew check` on its own, because anyone bisecting may land on it.
Move code in one commit and change it in another. Don't mix a reformat or
rename with a change of behaviour.

Tests come with the change, at the lowest level that shows it. Cover
normal cases, boundaries, and robustness cases with the exact SQLCODE and
SQLSTATE expected (R-TEST-2). Each test's doc comment begins `Verifies:`
and a citation (R-TRACE-1). A fixed defect gets a test that fails without
the fix (R-TEST-5).

Documents change in the same commit. Update CONFORMANCE.md for a row
implemented or changed (R-TRACE-3), RULES.md for a decision made or
changed, and TODO.md for open work done or found. Code never cites
TODO.md. The manual, [doc/](doc/README.md), changes with anything a
program sees, its examples run again: Volume I, the User's Guide, in
`doc/user/`; Volume II, the API Reference, in `doc/client/`; and the
appendices in `doc/appendix/`. turborel.com's documentation site builds
from that folder, so each page opens with its `# Title` and a line
`[Contents](README.md)`, and links between pages are relative `.md`
links.

Some rules deserve a reminder.

- Every failure is a `TurborelException` (R-ERR-1), and nothing a server
  sends can make the driver hold memory without bound (R-ERR-4).
- No dependency without a decision in RULES.md (R-DEP).
- Coverage floors only rise (R-COV-2).

## Changes that touch the protocol

These need more than passing tests. Say in the commit message what was done
for each that applies.

A change to the codec is tested byte by byte against the protocol's page,
both ways, and with malformed input that must fail the connection (SQLCODE
-919, SQLSTATE 08006).

A change to authentication or TLS says what an attacker in the middle, or
a server of their own, could do before and after it, and is tested
against a live server with the mechanism concerned.

A change to what the driver asks for or sends says what a server of an
earlier build sees: the protocol grows by capabilities, and a driver
never sends a request of one it was not granted.

## Commit messages

The form is Linux's.

```
area: summary in the imperative, at most 75 characters

The problem: what goes wrong, or is missing, and who it affects. A
wrong value, a failure with the wrong SQLSTATE, a leak or a hang is
said first, with the call that shows it.

Why this change, and how it solves the problem. Mention the choices
made and the alternatives rejected, the rules and sections applied
(RULES.md D7, R-ERR-4, [PROTOCOL The tail]), and what was measured,
with numbers.

Fixes: 54a4f0239f2e ("codec: refuse a tail tag not past the last")
Closes: <link to the issue>
Reported-by: A Reporter <reporter@example.org>
```

The subject is `area: summary`, at most 75 characters, in the imperative
("refuse a frame...", not "refused"), with no full stop. It says what
changes, isn't a file name, and isn't reused within a series.

The body is wrapped at 75 columns and written for someone reading `git log`
years later with no context. Say what the change does in plain prose, since
the diff shows how. Don't write "as discussed" or "v2".

Pick the area where most of the change is.

| Area | Covers |
|---|---|
| `codec` | `internal/codec/`: frames, fields, values, the tail, compression |
| `transport` | `internal/transport/`: TCP, Unix sockets, named pipes, timeouts |
| `auth` | `internal/auth/`: mechanisms, SCRAM and its key cache |
| `tls` | `internal/tls/`: TLS, trust, channel binding |
| `session` | `internal/session/`: Hello and Ready, status, notices, cancel, terminate |
| `statements` | `internal/statements/`: queries, prepared statements, cursors |
| `lobs` | `internal/lobs/`: large objects by locator |
| `copy` | `internal/copy/`: copy, import and export |
| `settings` | `internal/settings/`: the URI, the service and password files, several hosts |
| `pipeline` | `internal/pipeline/` |
| `pool` | `internal/pool/` |
| `api` | `com/turborel/jdbc/`: the JDBC API, and `module-info.java` |
| `doc` | `doc/`, README.md, RULES.md, CONFORMANCE.md, TODO.md, VERSIONING.md, AGENTS.md, CLAUDE.md, this file |
| `ci` | `.github/`, `.githooks/` |
| `build` | `build.gradle.kts`, `settings.gradle.kts`, `gradle/`, the wrapper, `config/` |

The packages are under `src/main/java/com/turborel/jdbc/`, and their tests
under `src/test/java/` in the same packages.

Trailers go at the end, one per line, never wrapped.

| Trailer | Meaning |
|---|---|
| `Fixes: <12+ hex digits> ("<subject>")` | the commit that introduced the defect fixed |
| `Closes: <link>` | the issue this commit resolves |
| `Link: <link>` | background, such as a discussion or an issue fixed only in part |
| `Reported-by:` | who reported the defect fixed, with `Closes:` |
| `Suggested-by:` | who suggested the idea |
| `Co-authored-by:` | a co-author, as GitHub credits one |
| `Reviewed-by:` | a reviewer who checked the change and is satisfied with it |
| `Acked-by:` | a maintainer of the area who approves it without a full review |
| `Tested-by:` | someone who tested it, saying where if that matters |

`Reported-by:` and `Suggested-by:` may be added without asking. The others
name someone who said so on the record. `git config core.abbrev 12` gives
12-digit hashes, and `git log -1 --pretty='Fixes: %h ("%s")' <commit>` writes
the `Fixes:` line.

A commit carries no attribution to an AI or a coding agent: no
`Co-authored-by:` for one, no "Generated with" line, and no
`Signed-off-by:`. Whoever submits a change answers for it.

Stage a commit by an explicit list of paths, `git add -- <path> ...`,
never `git add -A` or `git add .` over the whole tree, so nothing slips in
that wasn't meant to.

## Pull requests

A pull request is a patch series, and its description is the cover letter.
The template asks for what and why, the series commit by commit, testing,
compatibility and performance. Each commit message still stands alone.

Base it on `main` and keep it rebased, without merging `main` into it. When
revising, rewrite the commits so the series is again what should be merged,
with no "fix review comments" commits. Force-push, and say in a comment what
changed since the last version.

Trailers given in review go into the commits they cover. Drop them from a
commit that later changes substantially, and say so.

Keep a series to about 15 commits. Split a larger one into series that each
stand alone.

## Review

Reviewers check each commit against RULES.md §7's checklist, and that the
manual is updated. For
[changes that touch the protocol](#changes-that-touch-the-protocol), they
also check the tests and the account of what a server sees. Review
comments are about the change, never the person. A reviewer who is
satisfied gives `Reviewed-by:`.

## Reporting issues

Use the issue forms, **Bug**, **Conformance**, **Performance** or
**Feature**. Try the latest `main` first, and add to an existing issue that
matches rather than opening another.

Reproduce with the smallest program that shows it, and the statements it
runs against an empty database.

State the impact first. Put it in the title's first word when it is severe.

| Tag | Meaning |
|---|---|
| `[WRONG RESULT]` | a value read or sent differs from what the server holds or was given |
| `[CRASH]` | an exception other than a `TurborelException`, a JVM crash, or a hang |
| `[REGRESSION]` | worked in an earlier commit (give the last good commit and, if you can, the first bad one) |

Give the commit (`git rev-parse --short=12 HEAD`), the JDK and its
version, the platform and architecture, and the turborel server's
release, as `turboreld --version` prints it.

## Security

Don't open a public issue for a vulnerability. Report it privately, as
[SECURITY.md](SECURITY.md) says.

## License

turborel-java is under the BSD-2-Clause Plus Patent License in
[LICENSE](LICENSE), and a contribution is made under that license.
Contributors keep their copyright and own their changes. Whoever
submits a change answers for all of it, however it was written.
