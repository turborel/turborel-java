# TODO

Open work, by milestone. The milestones and their items are the same in
every Turborel driver, and each item names the rows of
[CONFORMANCE.md](CONFORMANCE.md) it marks done. An item covers only the
rows CONFORMANCE.md still marks "not yet"; one whose rows are all done
leaves this file, and git keeps its history. How the driver is built is
in [RULES.md](RULES.md).

Code never cites this file (R-DOC-5).

[M1 Codec](#m1-codec) · [M2 Connect](#m2-connect) ·
[M3 Statements](#m3-statements) ·
[M4 Large objects and bulk](#m4-large-objects-and-bulk) ·
[M5 Settings](#m5-settings) · [M6 Throughput](#m6-throughput) ·
[M7 Standard API](#m7-standard-api) · [M8 Parity audit](#m8-parity-audit)

---

## M1 Codec

Nothing is open. Every row M1 names is done; the other requests' fields
are encoded too, and their rows, which say how the server answers, are
done in the milestone that names them.

## M2 Connect

Nothing is open. Every row M2 named is done but P-STATUS-2 and
P-STATUS-11, which cover the Statuses after Reset and ChangeUser too,
and which M3 takes with them.

## M3 Statements

Nothing is open. Every row M3 named is done.

## M4 Large objects and bulk

Nothing is open. Every row M4 named is done.

## M5 Settings

- Settings, the URI and the files, read as turborel-client's settings
  tests read them (R-TEST-9): the C-SET, C-OPT, C-URI, C-SVC and C-PASS
  rows.
- Several servers and roles: the C-HOSTS rows.
- TLS modes and channel binding: the C-TLS rows.
- The key cache shared by settings: C-KEY-2.
- The manual: the chapter Settings, the page of settings, and
  Appendix C.

## M6 Throughput

- Pipelines: P-REQ-1 and the C-PIPE rows.
- Pools: the C-POOL rows.
- The SQL-agent: the C-AGENT rows.
- The manual: the chapter Pipelines and Pools; the pages of pipelines,
  pools and the SQL-agent.

## M7 Standard API

- The standard API (RULES.md D2): the S-API rows.
- The manual: the chapter Installing, the API Reference's pages of the
  standard API, and every page the earlier milestones filled checked
  again against the code, every example run.

## M8 Parity audit

- Every row of CONFORMANCE.md checked against turborel-client and the
  spec, the coverage floor held (RULES.md D7), and this file empty.
- Once Turborel keeps a file of sample frames beside the spec, every
  frame of it decoded, at a pinned commit.
