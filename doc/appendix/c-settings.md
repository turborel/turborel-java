# Appendix C: Settings

[Contents](README.md)

## Connection settings

Each connection setting has one name, the same in `Options`, a
connection URI's query and a service file's section; some are also
given by a variable of the environment. They are turborel-client's own,
read as it reads them. [Settings](../user/09-settings.md) tells how the
layers are laid: the program's, then the service's, then the
environment's, then the default.

| Setting | Variable | What it says | Default |
|---|---|---|---|
| `host` | TURBOREL_HOST | The servers, comma-separated: `name`, `name:port`, `[address]`, `[address]:port`, or a path, of a Unix socket or, on Windows, a named pipe | `localhost` |
| `port` | TURBOREL_PORT | The port of each host that carries none: one for all, or one each, comma-separated | 7422 |
| `user` | TURBOREL_USER | The user to connect as | none: it must be given |
| `password` | TURBOREL_PASSWORD | The user's password | the password file's |
| `tls_mode` | TURBOREL_TLS_MODE | `disable`, `prefer`, `require`, `verify-ca` or `verify-full` | `prefer` |
| `channel_binding` | | `disable`, `prefer` or `require` | `prefer` |
| `application_name` | TURBOREL_APPLICATION_NAME | The program's name, sent as the connection attribute `application` | none |
| `connect_timeout` | TURBOREL_CONNECT_TIMEOUT | Whole seconds each step of connecting waits; 0 for no limit | 30 |
| `service` | TURBOREL_SERVICE | The section of the service file to read | none |
| `service_file` | TURBOREL_SERVICE_FILE | The service file | `.turborel_service.conf` in the home directory |
| `passfile` | TURBOREL_PASSFILE | The password file | `.turborel_pass` in the home directory |
| `target` | | `any`, `read-write` or `read-only` | `any` |
| `load_balance` | | `disable`, the hosts tried in order, or `random` | `disable` |
| `tls_roots` | | A PEM file of the certificate authorities trusted | none |
| `tls_system_roots` | | `true` to trust the platform's authorities too, else `false` | `false` |
| `tls_certificate` | | A PEM file of the client's certificate chain, to present | none |
| `tls_key` | | A PEM file of its private key | none |
| `server_name` | | The name the server's certificate is checked against | the host's |

A variable set but empty gives nothing. A service's section cannot set
`service` or `service_file`. The words of `tls_mode`, `channel_binding`,
`target`, `load_balance` and `tls_system_roots` are written in lower
case; another is refused (SQLSTATE 08001).

## Pool settings

A pool's settings are the components of `PoolConfig`, each set by its
`with` method over `PoolConfig.defaults()`
([Pools](../client/28-pools.md)).

| `PoolConfig` | turborel-client | What it says | Default |
|---|---|---|---|
| `max` | `max` | The most connections made at once, handed out and idle | 10 |
| `maxIdle` | `max_idle` | The most kept idle | 10 |
| `idleTimeout` | `idle_timeout` | How long one may stay idle; none, as long as it lives | 10 minutes |
| `maxLifetime` | `max_lifetime` | How long one lives from when it was made; none, without limit | 1 hour |
| `maxWait` | `wait` | How long `get` waits for one to come back | 30 seconds |
| `check` | `check` | Whether an idle connection is checked to answer before it is handed out | yes |
| `reset` | `reset` | Statements run as each comes back, after the pool's own | none |
