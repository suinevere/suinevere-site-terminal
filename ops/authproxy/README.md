# authproxy

Gates `multizorkd` behind the Netlink shared-secret handshake, so unauthenticated traffic
never reaches the hand-written C parser.

The handshake lives here rather than in `multizorkd.c` deliberately. Adding a new byte
parser to the C service would grow the attack surface of the exact component being
protected.

## Wire format

Spoken by `netlink_standard_server` in `Netlink/tunnel/netlink.py`:

```
client → AUTH  +  <1-byte secret length>  +  <secret>
server → \x01                                            (accepted)
server → close                                           (rejected)
```

Plaintext, no nonce, and the secrets are committed to a public repo — so this is a doorman,
not a lock. It rejects background internet scanning before it reaches the parser. It does
not stop anyone who reads the Netlink repo.

## Traffic path

```
Saturn/DreamPi ──► nginx stream :23 ──► authproxy :2322 ──► multizorkd 127.0.0.1:2323
                     (limit_conn)        (validates AUTH)      (container, loopback)

Spring bridge (same host) ─────────────────────────────────► 127.0.0.1:2323
                                                              (loopback, no auth needed)
```

## Configuration

All settings come from the environment. The secret is never baked into the image.

| Variable | Default | Purpose |
|---|---|---|
| `AUTH_SECRET` | *(required)* | Shared secret; the process exits without it |
| `AUTH_MAGIC` | `AUTH` | Preamble marker, matches `auth_magic` in the Netlink config |
| `LISTEN_HOST` | `0.0.0.0` | Bind address inside the container |
| `LISTEN_PORT` | `2322` | Listener port |
| `UPSTREAM_HOST` | `127.0.0.1` | Where `multizorkd` listens. **In the compose stack this must be `multizork`** — inside a container `127.0.0.1` is the proxy's own loopback, not the game. |
| `UPSTREAM_PORT` | `2323` | Upstream port |
| `AUTH_TIMEOUT` | `5.0` | Seconds to wait for a complete preamble |
| `MAX_CONN` | `32` | Concurrent authenticated relays |
| `IDLE_TIMEOUT` | `1800` | Seconds before an established relay is dropped |

`AUTH_TIMEOUT` matters: without it a connection that opens and never sends a preamble would
occupy a slot indefinitely.

## Netlink side

Switch dial code `199408` from the transparent handler to the authenticating one:

```ini
[server:199408]
name = MultiZork
host = suinevere.duckdns.org
port = 23
shared_secret = <the same value as AUTH_SECRET>
auth_magic = AUTH
auth_timeout = 5.0
```

Removing `handler = transparent` selects `netlink_standard_server`, which performs the
handshake. The two handlers have different relay implementations, so test with a real
Saturn session rather than assuming they behave identically.

## Verifying

Rejected without a preamble:

```bash
printf 'hello\r\n' | timeout 8 nc <host> 23        # expect immediate close
```

Accepted with one — note `\x05` is the length of `SECRET`:

```bash
printf 'AUTH\x05SECRET' | timeout 8 nc <host> 23   # expect \x01 then the banner
```
