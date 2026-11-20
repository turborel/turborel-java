# TODO

Open work, by milestone. Each item says what is missing. How the driver is
built is in [RULES.md](RULES.md), and what is implemented, row by row, in
[CONFORMANCE.md](CONFORMANCE.md), whose rows each milestone below marks
done. Finished work leaves this file, and git keeps its history.

Code never cites this file (RULES.md R-DOC-5).

[M1](#m1-codec) · [M2](#m2-connect) · [M3](#m3-statements) ·
[M4](#m4-large-objects-and-bulk) · [M5](#m5-settings) ·
[M6](#m6-throughput) · [M7](#m7-standard-api) ·
[M8](#m8-parity-audit)

---

## M1. Codec

- Frames whole: a reader and writer of a frame's body, kinds as named
  constants, a body cut short or with bytes left over refused.
- Fields: `u8`, `u32`, `u64`, `i128`, flag, string, bytes, list, strings,
  key, each counted against what remains before anything is allocated.
- The 27 data types and values at their edges: `i128` limits and scales as
  `BigDecimal`, NaN and −0.0, DECFLOAT, each interval qualifier,
  displacements −12:59 to +14:00, nested arrays, rows, multisets and
  structured values, locators, each XML modifier, UUID (tag 27).
- JSON values as turborel's D89 binary tree, decoded and encoded, every
  node.
- The tail, tags 1 to 18, in increasing order.
- Compressed frames: zlib by `java.util.zip`, inflating at most the length
  given.
- Every request and reply encoded and decoded, the page's worked example
  first.

## M2. Connect

- Transports: TCP, a Unix socket (`UnixDomainSocketAddress`), a Windows
  named pipe, each refused clearly where the platform has none.
- TLS by JSSE, begun on the same port.
- Mechanisms: trust, password, certificate, SCRAM-SHA-256 and -PLUS by
  `tls-exporter` where the JDK gives it, else `tls-server-end-point`; the
  SCRAM key cache.
- Hello, Challenge, Answer, Ready and Status; capabilities asked for and
  granted.
- Timeouts, terminate, cancel by key on a connection of its own.
- A live test server set up in `test/server/`: test certificates, SCRAM
  users and SERVER.TRUST_LOCAL, against `ghcr.io/turborel/turborel:nightly`
  in CI.

## M3. Statements

- Query and streamed query; prepare, execute, fetch, close, deallocate;
  numbered cursors; describe.
- Failures with diagnostics, notices, conditions; autocommit by `ends`;
  rows affected, generated values, server time, commit position.
- Multi-statement, query attributes, ping, reset, change-user.

## M4. Large objects and bulk

- Locators, chunked reads, create, append, free, hold; a reader and a
  writer.
- CopyIn, CopyData, CopyEnd and abandon; CSV and TSV import; CSV and
  native export.

## M5. Settings

- The `turborel://` URI, options, TURBOREL_* variables, the service file
  and the password file, read as turborel-client's tests read them.
- Several hosts, `load_balance`, the target role.
- `tls_mode` and `channel_binding`.

## M6. Throughput

- Pipelines with the 64 KiB window and skip-on-error by savepoints.
- Pools with PoolConfig's defaults, a Ping check and a Reset return.
- The SQL-agent: CONNECT, SET CONNECTION, DISCONNECT.

## M7. Standard API

- JDBC 4.3 over the client: the driver and its URL, connections,
  statements, result sets, metadata, large objects, the data source.
- Decide whether failures take JDBC's `SQLException` subclasses by
  SQLSTATE class, as tools such as connection pools read them.
- The manual in `doc/`, every example run.

## M8. Parity audit

- Every row of CONFORMANCE.md checked against turborel-client and the
  protocol's page, the coverage floor held, this file empty.
