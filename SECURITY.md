# Security

Report a vulnerability privately, never in a public issue: on the
repository's **Security** tab, choose **Report a vulnerability**. Only the
maintainers see the report.

What counts, for a driver:

- TLS not used, or the server's certificate or name not verified, where
  the program or its settings asked for it;
- a password, a SCRAM key or proof, or a cancel key disclosed, to the
  network, a log, an exception's message or anywhere the program did not
  put it;
- SCRAM-SHA-256-PLUS's channel binding left out or not checked where it
  was asked for, or a server's SCRAM signature not checked;
- a server's reply, or a file the driver reads, such as the password or
  service file, that can crash the client, run code, or take its memory
  without bound.

A wrong result, a failure with the wrong SQLSTATE, or a crash only the
program's own misuse causes is a bug: report it with the **Bug** form, its
impact in the title (CONTRIBUTING.md, Reporting issues).

Say in the report:

- what an attacker needs, such as a server of their own, a place on the
  network, or a file the program reads;
- what they gain;
- the commit you tested (`git rev-parse --short=12 HEAD`);
- the smallest steps that show it.

You will hear back within a week. The fix is made in private and
published with an advisory that credits you, unless you ask not to be
named.
