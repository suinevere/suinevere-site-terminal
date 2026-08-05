# Project memory

Running notes on the suinevere terminal stack and the server it talks to. Decisions and
current state, not a transcript.

## The stack

A browser terminal for the TCP service at `suinevere.duckdns.org:23`.

```
Browser (React + XTerm.js, owns local echo)
   │  RSocket over WebSocket, /rsocket, route terminal.session
Spring Boot 4.1 (Kotlin) — TerminalController → UpstreamTcpClient
   │  reactor-netty, one TCP connection per session
suinevere.duckdns.org:23 → multizorkd (Docker)
```

## Load-bearing facts about the upstream

Measured directly, not assumed:

- **It never echoes.** A bare `q` with no CR produces no response. It is line-buffered and
  replies only after CRLF. This is why the browser owns echo and line assembly.
- Plain ASCII, CRLF, **no telnet IAC negotiation**.
- An empty line is meaningful — bare Enter takes the guest path.

## Charset contract

**ISO-8859-1 on the TCP leg only.** TCP splits reads at arbitrary byte boundaries, and
ISO-8859-1 is the only lossless byte-to-char mapping that cannot fail mid-stream.

Past that hop the system carries a decoded `String`, so the RSocket leg uses **UTF-8** —
Spring's `text/plain` codecs default to it (`AbstractCharSequenceDecoder.DEFAULT_CHARSET`
and `CharSequenceEncoder.DEFAULT_CHARSET` are both `UTF_8`). Propagating ISO-8859-1 out to
the browser looks symmetric and silently mangles every byte above 0x7F.

## Two hazards that have bitten repeatedly

**The NUL escape.** Tooling silently converts a backslash-u-0000 escape into a raw NUL
byte, three times so far. It makes files binary, `grep` refuses to show content, and visual
diffs look correct. Verify at byte level after editing anything containing it.

**Node globals in the browser.** The `@rsocket/*` packages reference `Buffer` as a bare
global across 20 files and import it in none. `resolve.alias` only rewrites explicit
imports, and `define: { global: 'globalThis' }` does not cover `Buffer`. `frontend/src/polyfills.ts`
installs it and **must stay the first import in `main.tsx`** — guarded by a test.

Neither the Node-based RSocket gate nor `vite build` can catch that class of bug: Node has
`Buffer` global, and bundling succeeds because nothing is unresolved at build time.

---

# Server hardening

Oracle Cloud VPS, Ubuntu. DreamPi runs at home and dials out to this box.

## Attack surface as found

Externally reachable across a 1071-port TCP sweep: **22, 23, 80, 443**. Nothing else.

| Port | Service | Notes |
|---|---|---|
| 22 | sshd | Internet-facing |
| 23 | docker-proxy → multizork | The MUD, and the Saturn tunnel target |
| 80/443 | nginx | Reverse proxy |

## The Saturn tunnel needs no firewall carve-out

Dial code **199408 → `suinevere.duckdns.org:23`** in `Netlink/tunnel/netlink_config.ini`.
The Saturn tunnel is an ordinary TCP client of port 23 — the same port and service as the
web terminal. There is no separate tunnel port.

Consequence: anything done to port 23 affects the Saturn and the web terminal identically.

## Findings and status

- **rpcbind on `0.0.0.0:111` (TCP+UDP)** — UDP 111 is a DDoS reflection vector. **DONE: killed.**
- **Docker bypasses ufw.** Docker writes its own iptables rules evaluated before ufw's, so a
  published container port stays reachable regardless of ufw. Fix: bind the container to
  loopback and front it with nginx `stream`.
- **`multizorkd` compiled with no hardening flags.** An internet-facing hand-written C parser
  built with a bare `gcc -O2`. Adding `-D_FORTIFY_SOURCE=2`, full RELRO, and stack-clash
  protection does more than every container flag combined.
- **Container already runs non-root** — `USER multizork`, uid 1000, `/data` owned correctly.

## The AUTH handshake

`Netlink/tunnel/netlink.py` already implements a shared-secret handshake in
`netlink_standard_server` (line 1590). `netlink_transparent_server` (1448) does not — and
`199408` currently uses `transparent`, so it connects with a bare `sock.connect()`.

Wire format, client to server:

```
AUTH  +  <1-byte secret length>  +  <secret>      → plaintext
                                          \x01    ← accepted
```

**Honest assessment: a doorman, not a lock.** The secrets are committed to
`eaudunord/Netlink` on GitHub, a community project shipped inside DreamPi releases. There is
no nonce, so it is replayable by anyone who captures it once, and it is plaintext on the
wire. It will not stop anyone who reads the repo.

What it *does* buy is real: it rejects the constant background of internet scanners before
they reach a v0.0.9 hand-written C parser. Keeping hostile input away from that parser is
the point.

## Decision: proxy, not patch

The handshake goes in a **separate memory-safe proxy**, not in `multizorkd.c`. Adding a new
byte parser to the hand-written C service would grow the attack surface of the exact
component being protected.

```
Saturn/DreamPi ──► nginx stream :23 ──► authproxy :2322 ──► multizorkd 127.0.0.1:2323
                     (limit_conn)        (validates AUTH)      (container, loopback)

Spring bridge (same host) ─────────────────────────────────► 127.0.0.1:2323
                                                              (loopback, no auth needed)
```

If the Spring bridge runs on this same box it connects straight to loopback and never needs
the handshake, so no Kotlin change is required. If it runs elsewhere, it must send the
preamble itself.

## nginx notes

**`stream` is a sibling of `http`, not a child.** It cannot live in `sites-enabled/` or
`conf.d/`, because both are included from inside the `http` block. Put the include at top
level of `nginx.conf`:

```nginx
stream {
    include /etc/nginx/streams-enabled/*.conf;
}
```

On Ubuntu the stream module is a separate package — `libnginx-mod-stream`, a dependency of
`nginx-full` but not `nginx-light`. Check `/etc/nginx/modules-enabled/` before debugging
config syntax.

**The catch-all redirect will eat `/rsocket`.** Both vhosts for `suinevere.duckdns.org` end
in `location / { return 301 ... suin.uk ... }`, so a WebSocket upgrade to `/rsocket` gets
redirected away and the terminal never connects. It needs an explicit `location /rsocket`
with `Upgrade`/`Connection` headers ahead of the catch-all, or to be served from the
`suin.uk` vhost instead.

**Port 80 redirects to `http://suin.uk`, not `https://`** — a downgrade that leaves the
first request to the destination in cleartext.

## Expected behaviour once the gate is live

Plain `telnet host 23` **stops working, by design**. The signature of a healthy gate is:

```
TCP CONNECT: OK          <- nginx listening on 23, forwarding
READ: 0 bytes, closed    <- authproxy rejected: no AUTH preamble
```

A connect *timeout* means something else — nothing listening, or a firewall. Distinguish the
two before debugging.

Positive test, with the length byte computed from the secret:

```bash
SECRET='YourSecretHere'
printf "AUTH$(printf '\\%03o' ${#SECRET})%s" "$SECRET" | nc -q5 suinevere.duckdns.org 23
```

Admin access goes *around* the gate rather than reopening it — SSH to the box and use
`nc 127.0.0.1 2323`, which reaches multizorkd directly on loopback.

**The Spring bridge is affected identically to telnet.** If `terminal.upstream.host` still
points at the public name it opens a raw connection with no preamble and is rejected. On-box
it should be `127.0.0.1:2323`; off-box, `UpstreamTcpClient` must send the preamble itself.

## Ordering when moving port 23 behind nginx

nginx cannot bind 23 while Docker holds it, so the outage window is unavoidable but should
be seconds:

```bash
sudo nginx -t                  # syntax only, does not bind — must pass first
docker compose up -d           # frees 23, starts authproxy   <- outage begins
sudo systemctl reload nginx    # binds 23                     <- outage ends
```
