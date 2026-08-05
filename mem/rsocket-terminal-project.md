---
name: rsocket-terminal-project
description: The browser terminal stack, the measured facts about the upstream service, and the charset contract that spans it.
metadata:
  type: project
---

A browser terminal for the TCP service at `suinevere.duckdns.org:23`.

```
Browser (React + XTerm.js, owns local echo)
   │  RSocket over WebSocket, /rsocket, route terminal.session
Spring Boot 4.1 (Kotlin) — TerminalController → UpstreamTcpClient
   │  reactor-netty, one TCP connection per session
suinevere.duckdns.org:23 → multizorkd (Docker)
```

Design and task breakdown live in `docs/superpowers/specs/2026-08-04-rsocket-terminal-design.md`
and `docs/superpowers/plans/2026-08-04-rsocket-terminal.md`. Execution history, deferred
findings and review rulings are in `.superpowers/sdd/2026-08-04-rsocket-terminal/progress.md`.
Do not restate those here.

## Measured facts about the upstream

Probed directly, not assumed. The design depends on all three:

- **It never echoes.** A bare `q` with no CR produces nothing. Line-buffered; replies only
  after CRLF. This is why the browser owns echo and line assembly — wiring XTerm's `onData`
  to the session instead of the line editor yields a terminal that looks dead while working.
- Plain ASCII, CRLF, **no telnet IAC negotiation**.
- An empty line is meaningful — bare Enter takes the guest path. This is why the RSocket
  handshake needs a sentinel rather than an empty first payload.

## Charset contract

**ISO-8859-1 on the TCP leg only.** TCP splits reads at arbitrary byte boundaries, and
ISO-8859-1 is the only lossless byte-to-char mapping that cannot fail mid-stream.

Past that hop the system carries a decoded `String`, so the RSocket leg uses **UTF-8** —
Spring's `text/plain` codecs default to it. Propagating ISO-8859-1 out to the browser looks
symmetric and silently mangles every byte above 0x7F. A whole-branch review caught this
after nine task reviews missed it, because no test crosses both legs with non-ASCII.

Relates to [[recurring-build-hazards]], [[suinevere-server-hardening]].
