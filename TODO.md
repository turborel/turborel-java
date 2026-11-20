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

Nothing is open. Every row M5 named is done.

## M6 Throughput

Nothing is open. Every row M6 named is done.

## M7 Standard API

Nothing is open. Every row M7 named is done.

## M8 Parity audit

- Every row of CONFORMANCE.md checked against turborel-client and the
  spec, the coverage floor held (RULES.md D7), and this file empty.
- The sample frames: P-FRAMES-1 to P-FRAMES-4, every frame of
  `doc/client/05-protocol-frames.tsv` decoded or encoded as it says, at a
  pinned commit of Turborel.
