<!--
This description is the series' cover letter (CONTRIBUTING.md, Pull
requests). Each commit message still stands alone, in Linux's form.
Base the series on main, keep it rebased, and on revising say what
changed in a comment, not here.
-->

## What and why

<!-- The problem and its impact, then the approach and the choices made.
Link the issue: "Closes #N". -->

## The series

<!-- One line per commit, in order: what it does. Each passes
`./gradlew check` on its own. -->

1.

## Testing

<!-- The tests added and what each verifies. For a change that touches the
protocol, the byte-by-byte, malformed-input and live-server tests done. -->

- [ ] `./gradlew check` passes
- [ ] The tests pass on Java 17, 21 and 25 (`-PtestJava=<n>`), or CI shows it

## Compatibility

<!-- What a program using the driver sees change, and what a server of an
earlier build sees the driver send; "none" if nothing changes. -->

## Performance

<!-- If the change claims any: what was measured, before and after, with
the numbers. Otherwise "none claimed". -->

## Checklist (RULES.md §7)

- [ ] One logical change per commit, each passing `./gradlew check`
- [ ] The protocol or turborel-client cited in the code (R-DOC-4) and in the tests (`Verifies:`, R-TRACE-1)
- [ ] Normal, boundary and robustness tests (R-TEST-2), a failing-first test for a fix (R-TEST-5)
- [ ] Every failure a `TurborelException`, nothing held without bound, no new dependency without a decision (R-ERR-1, R-ERR-4, R-DEP)
- [ ] CONFORMANCE.md, RULES.md, TODO.md and `doc/` updated where they change
