# Appendix B: Errors

[Contents](README.md)

Every failure reaches a program as the driver's error,
`TurborelException`, of its SQLCODE and SQLSTATE
([Errors](../client/30-errors.md)). This appendix lists the codes the
driver raises itself, and how it takes those the server sends.

## The codes the driver raises itself

When the driver fails something itself, rather than relaying the
server's Error, it uses the codes of the table every Turborel driver
shares, turborel-client's own wherever it has the case.

| SQLCODE | SQLSTATE | When | The connection |
|---|---|---|---|
| -919 | 08006 | A reply malformed or too long; the stream failing or closing; a wait that runs out, the message saying the server did not answer in time; TLS's handshake failing; a file of certificates or a key unreadable; the server not proving it knows the SCRAM verifier | lost |
| -919 | 08006 | A request of a capability the server did not grant, or attributes past their bounds, refused before anything is sent; a read timeout set on a stream the program opened | kept |
| -919 | 28000 | A password, or SCRAM not in the clear, over plain TCP, refused before connecting; channel binding required where none can be had; a SCRAM message of the server's malformed or out of turn | not made, or lost |
| -910 | TB910 | The program's misuse for which turborel has no case of its own: a cancel key or a UUID's octets not 16, a statement's Describe naming a cursor, a JSON item's scalar of no SQL/JSON scalar's kind | kept |
| -301 | 22003 | A value the protocol cannot carry: a number past its field, a DECFLOAT of more than 34 digits or past its exponents; a JSON number no number of Turborel's holds exactly | kept |
| -301 | 22021 | A string that is not Unicode, holding a lone surrogate | kept |
| -301 | 22009 | A time zone displacement past -12:59 to +14:00 | kept |
| -301 | 22032 | A string that is no JSON text; a JSON item with no JSON text, as a datetime | kept |
| -301 | 22037 | Two members of a JSON object of one key, where keys are to be unique | kept |
| -301 | 2200N | An XML value's text that is not well-formed XML content | kept |
| -916 | TB916 | A JSON value's stored form, given by the program, damaged | kept |
| -916 | TB916 | A reply, its frame whole, holding a value the driver cannot take: a truth byte other than 0 or 1, a length's units neither 0 nor 1, a JSON value damaged, a Complete's condition malformed, a value of tag 18 or 19; its request fails alone, the rest of its replies read past | kept |
| -900 | 0A000 | Past Turborel's limits: a JSON item nested deeper than 128, or a stored form past 256 MiB, given or received; a message longer than a Java array holds; a scale past a `BigDecimal`'s | kept |

## The codes the server sends

The server's failures come as it sends them, their SQLCODE, SQLSTATE,
message, rule, position and diagnostics kept. The driver reads a few
classes itself:

| SQLSTATE | What the driver does |
|---|---|
| class 01 | A warning, not a failure: the statement completes, and `warning()` gives its SQLSTATE |
| 02000 | No data, not a failure: a query that returned no row completes with SQLCODE 100 |
| 01T01 | Reset or ChangeUser rolled back the transaction that was active: a notice, of severity NOTICE |
| class 08 but 08003 | The connection is lost |
| class 28, SQLCODE -919 | Credentials refused: the connection is lost, at a login or a change of user |
| TB920 | SQLCODE -920: the statement was cancelled; the session goes on |
| TBR01 | SQLCODE -920: a replica had not applied REPLICATION.READ_AFTER's position in time; the session goes on |

Every other code is the statement's failure, and the session goes on.
Turborel's own manual lists the codes its server gives, in its
appendix
[SQLSTATE and SQLCODE](https://github.com/turborel/turborel/blob/main/doc/appendix/a-sqlstate.md).
