# Conformance

This file says what turborel-java implements of turborel's native
protocol, version 1, and of turborel-client, the reference client, row by
row. The matrix is the same in every turborel driver. It follows the order
of the documents it is drawn from: the protocol's page,
`doc/client/05-protocol.md`, then the client library's,
`doc/client/02-library.md`, then `doc/client/02-library-connections.md`,
in turborel's repository. Where the page and turborel-client's code differ,
the code is right, and the row says so. The last section, [JDBC](#jdbc), is
this driver's own: its standard API over the rest.

**Not yet** means the row is not implemented. **Done** means it is
implemented and tested, its tests citing the row's section (RULES.md
R-TRACE-1). A row changes in the commit that implements it (R-TRACE-3). The
milestone is the one of [TODO.md](TODO.md) that builds it.

Contents: [Frames](#frames) · [Fields](#fields) ·
[Data types](#data-types) · [Values](#values) ·
[Composite fields](#composite-fields) · [The tail](#the-tail) ·
[Mechanisms](#mechanisms) · [Capabilities](#capabilities) ·
[Requests](#requests) · [Replies](#replies) ·
[Session status](#session-status) ·
[Conversations, errors and notices](#conversations-errors-and-notices) ·
[The client library](#the-client-library) ·
[Settings, pipelines and pools](#settings-pipelines-and-pools) ·
[JDBC](#jdbc)

---

## Frames

[PROTOCOL Frames], [PROTOCOL Compressed frames]

| Item | Milestone | Status |
|---|---|---|
| The header: kind `u8`, length `u32` little-endian, the body | M1 | not yet |
| A kind not known, a body cut short, bytes left over: 08006 | M1 | not yet |
| Replies of at most 16 MiB of body, unless set otherwise | M1 | not yet |
| Compressed frame `z`: kind, length, zlib stream | M1 | not yet |
| `z` inflated to at most `length`, itself at most the body limit | M1 | not yet |
| `z` inside `z`, another length, or `z` not granted: 08006 | M1 | not yet |
| The client's own compression threshold | M1 | not yet |

## Fields

[PROTOCOL Values and fields]

| Item | Milestone | Status |
|---|---|---|
| `u8`, `u32`, `u64`, little-endian | M1 | not yet |
| `i128`, two's complement | M1 | not yet |
| flag, 0 or 1; any other byte: 08006 | M1 | not yet |
| string, its length `u32` then UTF-8 | M1 | not yet |
| bytes, its length `u32` then the bytes | M1 | not yet |
| list and strings, a count `u32` then each | M1 | not yet |
| key, 16 bytes | M1 | not yet |
| A count past what follows fails as the body runs out, reserving nothing | M1 | not yet |

## Data types

[PROTOCOL Data types]

| Tag | Type | Milestone | Status |
|---|---|---|---|
| 1 | CHARACTER, length and units | M1 | not yet |
| 2 | exact numeric, precision and scale | M1 | not yet |
| 3 | approximate numeric, precision | M1 | not yet |
| 4 | a plug-in's type, name and parameters | M1 | not yet |
| 5 | CHARACTER VARYING, length and units | M1 | not yet |
| 6 | BINARY, length | M1 | not yet |
| 7 | BINARY VARYING, length | M1 | not yet |
| 8 | DATE, precision | M1 | not yet |
| 9 | TIME, precision | M1 | not yet |
| 10 | TIME WITH TIME ZONE, precision | M1 | not yet |
| 11 | TIMESTAMP, precision | M1 | not yet |
| 12 | TIMESTAMP WITH TIME ZONE, precision | M1 | not yet |
| 13 | INTERVAL, its four fields | M1 | not yet |
| 14 | BOOLEAN | M1 | not yet |
| 15 | DECFLOAT, precision | M1 | not yet |
| 16 | array, maximum cardinality and element type | M1 | not yet |
| 17 | row, its fields' names and types | M1 | not yet |
| 18 | CHARACTER LARGE OBJECT, length and units | M1 | not yet |
| 19 | BINARY LARGE OBJECT, length and units | M1 | not yet |
| 22 | multiset, element type | M1 | not yet |
| 23 | structured type, schema, name and supertypes | M1 | not yet |
| 24 | reference type, referenced type and scope | M1 | not yet |
| 25 | JSON | M1 | not yet |
| 26 | XML, its modifiers and schema | M1 | not yet |
| 27 | UUID, no parameters (turborel RULES.md D184; not yet on the page) | M1 | not yet |

## Values

[PROTOCOL Values]

| Tag | Value | Milestone | Status |
|---|---|---|---|
| 0 | null | M1 | not yet |
| 1 | character string | M1 | not yet |
| 2 | exact number, units `i128` and scale, as `BigDecimal` | M1 | not yet |
| 3 | approximate number, IEEE 754 double | M1 | not yet |
| 4 | a plug-in's value, its type's name, text and bytes | M1 | not yet |
| 6 | binary string | M1 | not yet |
| 8 | date, as a timestamp | M1 | not yet |
| 9 | time, as a timestamp | M1 | not yet |
| 11 | timestamp: zoned, precision, displacement, microseconds in UTC | M1 | not yet |
| 13 | interval, its qualifier and units | M1 | not yet |
| 14 | truth value | M1 | not yet |
| 15 | decimal floating point, coefficient and exponent | M1 | not yet |
| 16 | array | M1 | not yet |
| 17 | row | M1 | not yet |
| 20 | large object, whole | M1 | not yet |
| 21 | large object, by locator | M1 | not yet |
| 22 | multiset | M1 | not yet |
| 23 | structured value | M1 | not yet |
| 24 | reference | M1 | not yet |
| 25 | JSON, turborel's binary tree (turborel RULES.md D89), read and written | M1 | not yet |
| 26 | XML, its serialization as CONTENT | M1 | not yet |
| 27 | UUID, bytes of 16 octets (turborel RULES.md D184; not yet on the page) | M1 | not yet |

## Composite fields

[PROTOCOL Composite fields]

| Item | Milestone | Status |
|---|---|---|
| row, a list of values | M1 | not yet |
| column, named flag, name, data type, not null flag | M1 | not yet |
| parameter, varying flag and data type | M1 | not yet |
| failure, SQLCODE, SQLSTATE, message, rule, position, active, tail 7 to 15 | M1 | not yet |

## The tail

[PROTOCOL The tail]

| Tag | Field | Milestone | Status |
|---|---|---|---|
| — | Tags in increasing order; one not taken or not past the last: 08006 | M1 | not yet |
| 1 | attributes, at most 32, names 1 to 64 bytes, values at most 1024 | M1 | not yet |
| 2 | multi | M1 | not yet |
| 3 | cursor | M1 | not yet |
| 4 | position | M1 | not yet |
| 5 | more | M1 | not yet |
| 6 | rows | M1 | not yet |
| 7 | severity | M1 | not yet |
| 8 | detail | M1 | not yet |
| 9 | hint | M1 | not yet |
| 10 | context | M1 | not yet |
| 11 | schema | M1 | not yet |
| 12 | table | M1 | not yet |
| 13 | column | M1 | not yet |
| 14 | data type | M1 | not yet |
| 15 | constraint | M1 | not yet |
| 16 | generated | M1 | not yet |
| 17 | conditions | M1 | not yet |
| 18 | time, total, queued and locks | M1 | not yet |

## Mechanisms

[PROTOCOL Opening a connection], [PROTOCOL Mechanisms]

| Item | Milestone | Status |
|---|---|---|
| Hello, Challenge and Answer steps, then Status and Ready | M2 | not yet |
| `trust` | M2 | not yet |
| `password`, only over TLS, a Unix socket or a named pipe | M2 | not yet |
| `certificate`, the TLS client certificate | M2 | not yet |
| `scram-sha-256`, RFC 5802 and RFC 7677 | M2 | not yet |
| `scram-sha-256-plus` by `tls-exporter`, over TLS 1.3 | M2 | not yet |
| `scram-sha-256-plus` by `tls-server-end-point`, over TLS 1.2 | M2 | not yet |
| The server's signature checked before the last Answer | M2 | not yet |

## Capabilities

[PROTOCOL Capabilities]

| Capability | Milestone | Status |
|---|---|---|
| `notices` | M3 | not yet |
| `status` | M2 | not yet |
| `diagnostics` | M3 | not yet |
| `describe` | M3 | not yet |
| `cursors` | M3 | not yet |
| `ping` | M3 | not yet |
| `reset` | M3 | not yet |
| `change-user` | M3 | not yet |
| `query-attributes` | M3 | not yet |
| `connection-attributes` | M2 | not yet |
| `multi-statement` | M3 | not yet |
| `compression` | M2 | not yet |
| `commit-position` | M3 | not yet |
| `copy` | M4 | not yet |
| `row-counts` | M3 | not yet |
| `generated-values` | M3 | not yet |
| `fetch-ends` | M3 | not yet |
| `conditions` | M3 | not yet |
| No request or tail field of a capability not granted is sent | M2 | not yet |

## Requests

[PROTOCOL Requests]

| Request | Milestone | Status |
|---|---|---|
| Hello `H` | M2 | not yet |
| Answer `A` | M2 | not yet |
| Query `Q`, with `ends` | M3 | not yet |
| Prepare `P` | M3 | not yet |
| Execute `E` | M3 | not yet |
| Fetch `F` | M3 | not yet |
| Close `C` | M3 | not yet |
| Deallocate `D` | M3 | not yet |
| Lob `L`: read, create, append, free, hold | M4 | not yet |
| Cancel `Z`, on a connection of its own | M2 | not yet |
| Terminate `X` | M2 | not yet |
| Describe `Y` | M3 | not yet |
| Ping `G` | M3 | not yet |
| Reset `W` | M3 | not yet |
| ChangeUser `U` | M3 | not yet |
| CopyIn `I` | M4 | not yet |
| CopyData `J` | M4 | not yet |
| CopyEnd `N`, with `abandon` | M4 | not yet |
| Requests sent before the last is answered, replies read in order | M6 | not yet |

## Replies

[PROTOCOL Replies]

| Reply | Milestone | Status |
|---|---|---|
| Challenge `C` | M2 | not yet |
| Ready `R`: authorization, key, capabilities, server | M2 | not yet |
| Prepared `P` | M3 | not yet |
| Description `D` | M3 | not yet |
| Rows `B` | M3 | not yet |
| Suspended `S` | M3 | not yet |
| Complete `K` | M3 | not yet |
| Error `E` | M2 | not yet |
| Lob `L`: data or locator | M4 | not yet |
| Notice `N` | M3 | not yet |
| Status `T` | M2 | not yet |
| Pong `G` | M3 | not yet |

## Session status

[PROTOCOL Status 'T'], and turborel's `server/src/native/status.rs`

| Status | Milestone | Status |
|---|---|---|
| AUTHORIZATION | M2 | not yet |
| SCHEMA | M2 | not yet |
| READ_ONLY, YES or NO | M2 | not yet |
| SERVER_VERSION | M2 | not yet |
| SERVER_READ_ONLY, YES or NO (not yet on the page) | M2 | not yet |
| CATALOG (not yet on the page) | M2 | not yet |
| OWNER, YES or NO (not yet on the page) | M2 | not yet |
| Each setting a session may set, SESSION.TIME_ZONE and TRANSACTION.ISOLATION among them | M2 | not yet |
| A setting a statement changes, told again; one reset, at the database's value | M2 | not yet |

## Conversations, errors and notices

[PROTOCOL Conversations], [PROTOCOL Errors and notices], [PROTOCOL A worked example]

| Item | Milestone | Status |
|---|---|---|
| A statement's replies in order: Rows, Notices, Statuses, then its last | M3 | not yet |
| An Error of class 08 but 08003, or of class 28 at login, ends the connection | M2 | not yet |
| Severity ERROR, FATAL, WARNING, NOTICE | M3 | not yet |
| Conditions in Complete with `conditions`, else Notices with `notices` | M3 | not yet |
| SQLSTATE 01T01, Reset or ChangeUser rolled back | M3 | not yet |
| SQLSTATE TBR01, a replica behind REPLICATION.READ_AFTER | M3 | not yet |
| A malformed message, a frame too long, a request out of turn: -919, 08006 | M1 | not yet |
| The page's worked example, byte for byte | M1 | not yet |

---

## The client library

[CLIENT Connecting] to [CLIENT The SQL-agent], `doc/client/02-library.md`

| Item | Milestone | Status |
|---|---|---|
| **Connecting** | | |
| Connect over TCP, port 7422 by default | M2 | not yet |
| Connect over a Unix socket | M2 | not yet |
| Connect over a Windows named pipe, `\\.\pipe\turborel` | M2 | not yet |
| Connect to a target, TCP or local, over TLS or not | M2 | not yet |
| SCRAM-SHA-256 of a password; the server's verifier proved (08006 otherwise) | M2 | not yet |
| SCRAM refused over plain TCP unless in the clear is asked for | M2 | not yet |
| SCRAM-SHA-256-PLUS over TLS: `tls-exporter` on 1.3, the certificate on 1.2 | M2 | not yet |
| Binding a TLS stream the program opened | M2 | not yet |
| Password, sent whole | M2 | not yet |
| Certificate, a chain and key presented | M2 | not yet |
| Trust | M2 | not yet |
| Password and SCRAM refused over plain TCP before connecting (28000) | M2 | not yet |
| Trusted authorities: a file's, the platform's, or both | M2 | not yet |
| `terminate`, its commit's failure given; waits at most 60 s | M2 | not yet |
| **Timeouts** | | |
| Connect timeout, 30 s by default, each step of the login | M2 | not yet |
| Read timeout, none by default | M2 | not yet |
| Read timeout changed on a connection | M2 | not yet |
| A wait run out loses the connection (08006) | M2 | not yet |
| The longest reply taken, 16 MiB by default, set by the program | M2 | not yet |
| **Running statements** | | |
| `query`: rows with their columns, or a completion | M3 | not yet |
| A failure: SQLCODE, SQLSTATE, message, rule, position | M3 | not yet |
| `query_into`: rows given a batch at a time | M3 | not yet |
| Every value type, as the program's language holds it | M3 | not yet |
| `in_transaction`, as the server last said | M3 | not yet |
| Cancel from another thread, giving up after 10 s; a limit of its own | M2 | not yet |
| Autocommit by `ends`, with no COMMIT sent | M3 | not yet |
| **Prepared statements** | | |
| `prepare` and `execute` | M3 | not yet |
| Parameters' types and columns | M3 | not yet |
| `open_cursor`, `fetch`, `close` | M3 | not yet |
| `deallocate` | M3 | not yet |
| **Large objects** | | |
| A value whole up to the server's inline limit, else by locator | M4 | not yet |
| `read_lob` by octets or characters, 256 KiB chunks at most | M4 | not yet |
| A reader of a whole large object | M4 | not yet |
| A locator keeps its transaction; `free_lob`, `hold_lob` | M4 | not yet |
| `create_lob`, a writer; `finish` gives the parameter | M4 | not yet |
| A CLOB's character split between writes | M4 | not yet |
| **XML values** | | |
| XML read as its text; sent of its text | M3 | not yet |
| **UUIDs** | | |
| UUID read as 16 octets and as text; sent of either | M3 | not yet |
| **Importing** | | |
| `import`: a source's rows into a table | M4 | not yet |
| A source of headings and records, in bounded memory | M4 | not yet |
| CSV and tab-separated text, header, delimiter and null text | M4 | not yet |
| Rows committed in batches, 1000 by default; rows that fail skipped | M4 | not yet |
| The import's failure: where, why, rows committed and rolled back | M4 | not yet |
| A field over 64 KiB for a large object sent by `create_lob` | M4 | not yet |
| **What a connection asks for** | | |
| Every capability but compression, by default | M2 | not yet |
| An offer of its own: none, compressed, attributes | M2 | not yet |
| What was granted; a method needing one not granted fails before sending | M2 | not yet |
| **What the server tells** | | |
| A setting and all settings, as last told, with no round trip | M2 | not yet |
| A notice handler; else the last 64 notices kept and taken | M3 | not yet |
| The last statement's conditions | M3 | not yet |
| Rows affected | M3 | not yet |
| The values an INSERT generated | M3 | not yet |
| The server's time: total, queued, locks | M3 | not yet |
| The commit's log position | M3 | not yet |
| A failure's diagnostics | M3 | not yet |
| Under autocommit, a cursor ends its transaction as it ends | M3 | not yet |
| **Controlling the session** | | |
| `ping` | M3 | not yet |
| `reset` | M3 | not yet |
| `change_user` | M3 | not yet |
| Query attributes on every later statement | M3 | not yet |
| `query_many`: each outcome, and the failure that stopped the rest | M3 | not yet |
| `describe` of a statement; numbered cursors; `describe_cursor` | M3 | not yet |
| **Copying and exporting** | | |
| `copy_in`, `send`, `finish` giving the rows inserted | M4 | not yet |
| A copy dropped before `finish` abandoned | M4 | not yet |
| `copy_rows` of an iterator | M4 | not yet |
| Importing by copy where granted and no column is a large object | M4 | not yet |
| Export as CSV | M4 | not yet |
| Export as the protocol's frames, and read back | M4 | not yet |
| **The SQL-agent** | | |
| Named connections, one current | M6 | not yet |
| CONNECT, SET CONNECTION and DISCONNECT run by the agent | M6 | not yet |
| A connector the program gives | M6 | not yet |

## Settings, pipelines and pools

[CLIENT Connecting by settings] to [CLIENT The SCRAM key cache],
`doc/client/02-library-connections.md`

| Item | Milestone | Status |
|---|---|---|
| **Connecting by settings** | | |
| Settings connect, as a target does | M5 | not yet |
| SCRAM of a password, else `password`; else certificate; else trust | M5 | not yet |
| A secret mechanism refused over plain TCP (28000) | M5 | not yet |
| **Where settings come from** | | |
| `host`: names, `name:port`, `[IPv6]:port`, a path for a local socket | M5 | not yet |
| `port`: one for all, or one each | M5 | not yet |
| `user` | M5 | not yet |
| `password` | M5 | not yet |
| `tls_mode` | M5 | not yet |
| `channel_binding` | M5 | not yet |
| `application_name`, sent as attribute `application` | M5 | not yet |
| `connect_timeout` | M5 | not yet |
| `service` | M5 | not yet |
| `service_file` | M5 | not yet |
| `passfile` | M5 | not yet |
| `target` | M5 | not yet |
| `load_balance` | M5 | not yet |
| `tls_roots` | M5 | not yet |
| `tls_system_roots` | M5 | not yet |
| `tls_certificate`, `tls_key` | M5 | not yet |
| `server_name` | M5 | not yet |
| Layers: the program's, the service file's, the environment's, the defaults | M5 | not yet |
| TURBOREL_* variables; one set but empty gives nothing | M5 | not yet |
| The environment read through a function the program gives | M5 | not yet |
| A setting not read, a service not found, no user: 08001 | M5 | not yet |
| **The connection URI** | | |
| `turborel://` with user, password, hosts, ports and settings | M5 | not yet |
| Percent-encoding; IPv6 in brackets | M5 | not yet |
| Another scheme, a path, an unknown setting, a pair without `=`: 08001 | M5 | not yet |
| **The service file** | | |
| Sections and `setting = value` lines; comments and blank lines | M5 | not yet |
| `service` and `service_file` refused in a section | M5 | not yet |
| A malformed line refused, its file and line named | M5 | not yet |
| **The password file** | | |
| `host:port:user:password`, `*`, `\` escapes, comments | M5 | not yet |
| The first line that matches; a local socket by its path and the default port | M5 | not yet |
| Read only if a regular file, or a link to one | M5 | not yet |
| **Several servers** | | |
| Hosts tried in order, or at random with `load_balance=random` | M5 | not yet |
| Each address a name resolves to, within the connect timeout | M5 | not yet |
| Passed if unreachable, refusing the user, or not of the role | M5 | not yet |
| The failure names each address and why (08001; 28000) | M5 | not yet |
| `target=read-write` and `read-only` by SERVER_READ_ONLY | M5 | not yet |
| The role from INFORMATION_SCHEMA.REPLICAS where not told | M5 | not yet |
| The role a connection's session sees | M5 | not yet |
| **TLS by mode** | | |
| `disable` | M5 | not yet |
| `prefer`, falling back to plain TCP after a failed handshake | M5 | not yet |
| `require` | M5 | not yet |
| `verify-ca` | M5 | not yet |
| `verify-full`, against `server_name` or the host | M5 | not yet |
| A local socket or named pipe uses no TLS | M5 | not yet |
| `channel_binding` `disable`, `prefer`, `require` (28000 where none can bind) | M5 | not yet |
| **Pipelines** | | |
| Queue requests, run them, one outcome each in order | M6 | not yet |
| Written a 64 KiB window at a time, its replies read before the next | M6 | not yet |
| Under autocommit, each request its own transaction | M6 | not yet |
| Skip on error: a savepoint before each request but the first | M6 | not yet |
| A skipping batch is one transaction, committed as it ends under autocommit | M6 | not yet |
| `run` fails on a lost connection (08006) or a batch not undone | M6 | not yet |
| **Pools** | | |
| `get`: an idle connection that answers, a new one, or a wait | M6 | not yet |
| A pooled connection goes back as it is released | M6 | not yet |
| `max` 10 | M6 | not yet |
| `max_idle` 10 | M6 | not yet |
| `idle_timeout` 10 minutes | M6 | not yet |
| `max_lifetime` 1 hour | M6 | not yet |
| `wait` 30 seconds | M6 | not yet |
| `check`, by Ping, on by default | M6 | not yet |
| `reset` statements, after the pool's own | M6 | not yet |
| Returned by the protocol's Reset, autocommit off | M6 | not yet |
| Without Reset and Ping: ROLLBACK and a pipeline | M6 | not yet |
| Closed when lost, reset fails, lived out, or beyond `max_idle` | M6 | not yet |
| `get` fails with 08001 after `wait` | M6 | not yet |
| Made of settings or of the program's source; no thread of its own | M6 | not yet |
| **The SCRAM key cache** | | |
| Keys kept after a login the server proved; PBKDF2 skipped on a hit | M2 | not yet |
| Found by salt, iterations and a keyed tag of the password | M2 | not yet |
| At most 64 entries, oldest dropped; an entry dropped when its login fails | M2 | not yet |
| Shared by every connection settings make | M5 | not yet |
| A mechanism the program makes takes a cache of its own | M2 | not yet |
| Hits and misses counted | M2 | not yet |

---

## JDBC

The driver's standard API, JDBC 4.3 (RULES.md D2), over the rows above.

| Item | Milestone | Status |
|---|---|---|
| `Driver`, found by `ServiceLoader`, of `jdbc:turborel:` URLs | M7 | not yet |
| `Connection`, autocommit, isolation, savepoints, `isValid` by Ping | M7 | not yet |
| `Statement` and `PreparedStatement`, batches, generated keys | M7 | not yet |
| `CallableStatement` | M7 | not yet |
| `ResultSet`, forward-only and read-only, every value type | M7 | not yet |
| `ResultSetMetaData` and `ParameterMetaData` | M7 | not yet |
| `DatabaseMetaData`, from the Information Schema | M7 | not yet |
| `Blob`, `Clob`, `SQLXML` over locators | M7 | not yet |
| `DataSource` | M7 | not yet |
| `SQLWarning` of notices and conditions | M7 | not yet |
| `Statement.cancel` and query timeouts by Cancel | M7 | not yet |
| `unwrap` to the driver's own interfaces for what JDBC lacks | M7 | not yet |
| Loaded by DBeaver and DataGrip | M7 | not yet |
