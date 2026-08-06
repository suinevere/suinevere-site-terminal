---
name: suinevere-server-hardening
description: Attack surface of the Oracle Cloud box, why the Saturn tunnel needs no firewall carve-out, and the state of each hardening measure.
metadata:
  type: project
---

Oracle Cloud VPS, Ubuntu. DreamPi runs at home and dials out to this box.

## Attack surface as found

A 1071-port TCP sweep found **22, 23, 80, 443** and nothing else.

| Port | Service |
|---|---|
| 22 | sshd |
| 23 | nginx `stream` → multizorkd |
| 80/443 | nginx — both vhosts redirect to suin.uk |

## The Saturn tunnel needs no carve-out

Dial code **199408 → `suinevere.duckdns.org:23`** in `Netlink/tunnel/netlink_config.ini`.
The Saturn tunnel is an ordinary TCP client of port 23 — the same port and service the web
terminal uses. There is no separate tunnel port, which is why the sweep found nothing extra.

Consequence: anything done to port 23 affects the Saturn and the web terminal identically.
There is no way to gate one without the other.

## Status

- **rpcbind on `0.0.0.0:111`** — a UDP DDoS reflection vector. **Removed.**
- **Docker bypasses ufw** — Docker writes iptables rules evaluated before ufw's, so a
  published container port stays reachable regardless of ufw. Mitigated by binding the
  container to loopback and fronting it with nginx `stream`.
- **`multizorkd` hardening flags** — **applied** in `docker/multizork/Dockerfile`:
  `-D_FORTIFY_SOURCE=2`, `-Wl,-z,relro,-z,now`, `-fstack-clash-protection`,
  `-Werror=format-security`, `-Wall -Wextra`, and control-flow protection selected by
  architecture because Oracle's free tier is ARM as often as x86. This does more than every
  container flag combined.
- **Container runs non-root already** — `USER multizork`, uid 1000, `/data` owned correctly.
- **nginx `limit_conn mud 3`** is live and valuable on its own: three connections per IP,
  enforced before anything reaches the C parser.

## The AUTH gate — built, deliberately bypassed

`ops/authproxy/` implements the shared-secret handshake that `netlink_standard_server`
speaks (`Netlink/tunnel/netlink.py:1590`). See `ops/authproxy/README.md` for the wire format
and configuration.

It lives in a **separate memory-safe proxy, not in `multizorkd.c`** — adding a byte parser to
the hand-written C service would grow the attack surface of the component being protected.

**Currently bypassed.** The nginx stream config proxies straight to `127.0.0.1:2323`, with
the `2322` line commented out. No `AUTH_SECRET` has been generated.

Honest assessment: the secrets live in `netlink_config.ini`, committed to the public
`eaudunord/Netlink` repo shipped inside DreamPi releases. Plaintext, no nonce, replayable.
It is a doorman, not a lock — it rejects background internet scanning before it reaches a
v0.0.9 C parser, and stops nobody who reads the repo.

## nginx gotchas

**`stream` is a sibling of `http`, not a child.** It cannot live in `sites-enabled/` or
`conf.d/`, both of which are included from inside `http`. The include goes at top level of
`nginx.conf`. On Ubuntu the module is a separate package, `libnginx-mod-stream`.

**The catch-all redirect will eat `/rsocket`.** Both `suinevere.duckdns.org` vhosts end in
`location / { return 301 ... suin.uk ... }`, so a WebSocket upgrade gets redirected away. The
terminal needs an explicit `location /rsocket` with `Upgrade`/`Connection` headers ahead of
the catch-all, or to be served from the `suin.uk` vhost.

**Port 80 redirects to `http://suin.uk`, not `https://`** — a downgrade leaving the first
request to the destination in cleartext.

## Ordering when moving port 23 behind nginx

nginx cannot bind 23 while Docker holds it, so an outage window is unavoidable — but it
should be seconds, and the syntax check must pass before anything is torn down:

```bash
sudo nginx -t                  # syntax only, does not bind
docker compose up -d           # frees 23                <- outage begins
sudo systemctl reload nginx    # binds 23                <- outage ends
```

## Diagnosing port 23

"TCP connect OK, then 0 bytes and close" is **ambiguous**. It means either the auth gate
rejecting a missing preamble, or nothing listening on the proxy target at all. Distinguish
with `docker compose ps`, `docker compose logs authproxy`, and `ss -ltnp | grep 2322` before
concluding anything. A connect *timeout* is a third case: firewall or no listener on 23.

Admin access should go *around* any gate rather than reopening it — SSH in and use
`nc 127.0.0.1 2323`.

Relates to [[rsocket-terminal-project]].
