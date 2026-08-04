# RSocket Terminal — Design

**Date:** 2026-08-04
**Author:** suinevere
**Status:** Approved

## Purpose

Put the TCP service at `suinevere.duckdns.org:23` in a browser. A React page renders
an XTerm.js terminal; a Spring Boot service bridges that terminal to the upstream TCP
socket over RSocket.

## Upstream service, as observed

Probed 2026-08-04 against `suinevere.duckdns.org:23`. These are measurements, not
assumptions, and the design depends on all four:

| Property | Observed |
|---|---|
| Framing | Plain ASCII, CRLF line endings |
| Telnet negotiation | None — no IAC (byte 255) in 426 bytes across two exchanges |
| Echo | **None.** A bare `q` with no CR produced no response |
| Processing | Line-buffered; the server replies only after CRLF |

The service greets with the client IP, a version string (`0.0.9`, built 2026-07-09),
then prompts for an access code, accepting a bare Enter for the guest path. It
describes itself as "American tech from 1980," so ASCII-only output is expected.

Two consequences drive the design. Because the server never echoes, **the browser must
own echo and line editing** — a pure passthrough terminal would show nothing as the user
types. Because an empty line is meaningful (bare Enter takes the guest path), **an empty
payload cannot serve as the channel handshake**.

## Architecture

```
Browser — React + XTerm.js
  local echo, line buffer, Backspace, Ctrl+C
        │  RSocket over WebSocket, ws://localhost:8080/rsocket
        │  route: terminal.session (requestChannel)
        ▼
Spring Boot 4.1 — Kotlin, WebFlux + RSocket
  TerminalController → UpstreamTcpClient
        │  reactor-netty TcpClient
        ▼
suinevere.duckdns.org:23
```

One `requestChannel` maps to exactly one upstream TCP socket. The channel's lifetime is
the socket's lifetime, so there is no session registry, no correlation IDs, and no
orphan-cleanup bookkeeping. Channel terminates, socket disposes.

WebFlux joins the RSocket starter because browsers cannot speak RSocket over TCP, only
over WebSocket, and Spring Boot's WebSocket RSocket transport requires WebFlux. Setting
`spring.rsocket.server.mapping-path` makes RSocket share port 8080 with the static
bundle rather than opening a second port.

## Backend

Package `org.suinevere.site.terminal.suinevere_site_terminal`.

### TerminalProperties.kt

`@ConfigurationProperties("terminal.upstream")` binding host, port, connect timeout,
max concurrent sessions, and output buffer capacity.

### UpstreamTcpClient.kt

The only networking code. One function:

```kotlin
fun open(inbound: Flux<String>): Flux<String>
```

Connects via reactor-netty `TcpClient`, then merges the outbound write-pump with the
inbound read-stream so writes and reads proceed concurrently over one connection. The
write-pump is a `Mono<Void>` cast into the output type — it contributes no elements, only
its completion and errors. Disposes the connection on terminate, cancel, or error.

**Charset is ISO-8859-1, deliberately.** TCP splits reads at arbitrary byte boundaries.
UTF-8 decoding corrupts any multi-byte character straddling a segment; ISO-8859-1 is a
lossless one-byte-to-one-char mapping that cannot fail mid-stream. Safe here because the
upstream is ASCII.

Output is buffered with a bounded queue sized from config, so a chatty or hostile
upstream cannot exhaust JVM heap.

### TerminalController.kt

```kotlin
@MessageMapping("terminal.session")
fun session(inbound: Flux<String>): Flux<String>
```

Filters the handshake sentinel out of `inbound`, then delegates to `UpstreamTcpClient`.
Maps upstream errors to human-readable bracketed text on the output flux rather than
letting them surface as an RSocket ERROR frame, so the terminal always shows a reason.

Enforces the max-concurrent-sessions cap, rejecting past the limit with a bracketed
message instead of silently queueing.

### The handshake sentinel

RSocket requires an initial payload to open a channel, and that payload carries the
route metadata. Since an empty line is a meaningful input to this server, the priming
payload cannot be the empty string.

The browser's first payload is a sentinel: a single NUL character followed by `INIT`.
The backend filters exactly that value. A leading NUL is unreachable from the keyboard
through the line editor, so no user input can collide with it.

This is deliberately chosen over `inbound.skip(1)`, which would depend on whether Spring
surfaces the routing payload as the first element of the inbound `Flux` — a behavior
that, if it ever changed, would silently swallow the user's first real line. The sentinel
is correct either way. A test pins the actual behavior so the assumption is recorded
rather than trusted.

### application.yaml

```yaml
spring:
  rsocket:
    server:
      transport: websocket
      mapping-path: /rsocket
terminal:
  upstream:
    host: suinevere.duckdns.org
    port: 23
    connect-timeout: 10s
    max-sessions: 32
    output-buffer: 1024
```

## Frontend

A Vite + TypeScript + React app under `frontend/`.

### rsocket/terminalSession.ts

Wraps RSocket connection and channel setup, exposing `onData`, `send`, `close`. Route
metadata is hand-encoded as RSocket composite metadata using
`@rsocket/composite-metadata`; Spring will not route the request without it.

### terminal/lineEditor.ts

Pure and framework-free, therefore directly unit-testable. Printable characters echo and
append to the buffer. Backspace erases one character and emits backspace-space-backspace,
but is a no-op at column zero so it cannot chew through the server's prompt. Enter emits
CRLF locally and flushes the line plus CRLF upstream. Ctrl+C clears the buffer.

### components/Terminal.tsx

Mounts XTerm with FitAddon, wires the line editor to the session, and renders connection
state including a Reconnect control.

## Build integration

`settings.gradle.kts` adds the Foojay toolchain resolver so Gradle provisions JDK 25
itself, keeping the repo portable and the generated build file unmodified.

`build.gradle.kts` adds `spring-boot-starter-webflux` and a task that runs `npm ci` then
`npm run build`, copying the bundle into static resources so `bootJar` produces one
self-contained artifact.

Development runs Vite on :5173 proxying `/rsocket` to :8080 for HMR. Production serves
the bundle from the jar.

## Error handling

Every failure surfaces as bracketed terminal text plus a Reconnect affordance. A dead
prompt with no explanation is the one unacceptable outcome.

| Failure | Behavior |
|---|---|
| Upstream connect refused / DNS failure | `[unable to reach <host>:<port>]`, channel completes |
| Upstream closes connection | `[disconnected]`, Reconnect offered |
| WebSocket drops | `[connection lost]`, Reconnect offered |
| Session cap reached | `[server busy, try again shortly]` |

## Security

Upstream host and port come from server-side configuration and are **never accepted from
the client**. This is a fixed-destination proxy, not a general TCP relay, so it cannot be
turned into an SSRF or port-scanning vector. The session cap bounds how many upstream
sockets one browser can open.

No authentication is in scope — the upstream service performs its own access-code
challenge, and the bridge stays transparent to it.

## Testing

`UpstreamTcpClientTest` starts a local reactor-netty `TcpServer` on an ephemeral port
that sends a banner and echoes lines, then asserts round-trip behavior and that
disconnect propagates. No dependency on the real host, so tests stay hermetic.

`TerminalControllerTest` drives a real `RSocketRequester` against the app on a random
port, asserting the channel round-trips and that the sentinel never reaches upstream.

`lineEditor` gets vitest unit tests for echo, backspace-at-column-zero, Enter semantics,
and Ctrl+C.

## Verified dependencies

Checked against live registries on 2026-08-04:

| Dependency | Version | Note |
|---|---|---|
| Spring Boot | 4.1.0 | Current release on Maven Central |
| Temurin JDK | 25.0.4+7 | Available via Foojay for windows-x64 |
| foojay-resolver | 1.0.0 | Present on the Gradle plugin portal |
| `@xterm/xterm` | 6.0.0 | Published 2026-07-27 |
| `@xterm/addon-fit` | 0.11.0 | Published 2026-07-27 |
| `@rsocket/core` | 1.0.0-alpha.3 | **Last published 2024-02-21** |

## Risk: rsocket-js is abandoned

Both JavaScript RSocket families are stale and neither reached a stable release.
`rsocket-core` sits at `0.0.29-alpha.0` (2024-03-06) and `@rsocket/core` at
`1.0.0-alpha.3` (2024-02-21).

`@rsocket/core` is chosen because it is the newer API and ships the companion
`@rsocket/composite-metadata` package needed for route encoding. Versions are pinned
exactly, with no caret range, since an abandoned package offers no upgrade path worth
tracking.

If it proves unusable under Vite — an ESM/CJS interop failure being the likeliest cause —
the fallback is a hand-rolled encoder for the four frame types this application actually
uses: SETUP, REQUEST_CHANNEL, PAYLOAD, and KEEPALIVE. RSocket's binary framing is
straightforward and roughly 200 lines cover this scope. The fallback is only worth
building if the library fails; it is not the starting position.
