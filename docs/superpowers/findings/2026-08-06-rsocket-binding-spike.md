# RSocket-over-WebSocket binding spike: does base-path and Security cover it?

Date: 2026-08-06
Status: measured — Branch B

Task: `.superpowers/sdd/2026-08-06-oracle-deployment/task-2-brief.md`.
Plan: `docs/superpowers/plans/2026-08-06-oracle-deployment.md`.
Spec: `docs/superpowers/specs/2026-08-06-oracle-deployment-design.md`.

## Method

Added, temporarily and only for this measurement, `spring.webflux.base-path: /zork` to
`src/main/resources/application.yaml` and `spring-boot-starter-security` to
`build.gradle.kts`, with no further security configuration. Ran `./gradlew.bat bootRun`
against a real Netty listener on port 8080 and sent unauthenticated HTTP probes with no
credentials. Spring Security was confirmed active by the generated-password log line
below; that password was never used in any probe, so every probe below is genuinely
unauthenticated.

```
Using generated security password: 4cdb8a21-7a3a-4be3-b1fc-8a663d31702f
Netty started on port 8080 (http)
Started SuinevereSiteTerminalApplicationKt in 5.692 seconds
```

Probed all four paths the brief specifies, unauthenticated:

| Path | Probe | Status observed |
|---|---|---|
| `/rsocket` | WebSocket upgrade (`Upgrade: websocket`, `Sec-WebSocket-Key`, no credentials) | **101 Switching Protocols** |
| `/zork/rsocket` | Same WebSocket upgrade | 401 Unauthorized |
| `/` | Plain GET | 404 Not Found |
| `/zork/` | Plain GET | 401 Unauthorized |

`/rsocket` at `101` was reproduced twice in a row (identical result both times) to rule
out a fluke.

## Reading the four results together

- `/zork/` returning `401` confirms `spring.webflux.base-path` moved the ordinary HTTP
  app under `/zork` and that Spring Security's `WebFilter` chain is live and protecting
  it, as expected.
- `/` returning `404` (not `401`) confirms the app no longer answers at the root — it
  genuinely moved to `/zork`, it isn't just also present at both paths.
- `/zork/rsocket` returning `401` is Spring Security's WebFilter chain rejecting an
  unmapped path under its protected base — it is not evidence the RSocket endpoint
  moved there; nothing is listening for RSocket at that path.
- `/rsocket` returning `101` to a completely unauthenticated upgrade request is the
  decisive result: the RSocket-over-WebSocket endpoint is still bound at its
  unprefixed, unconfigured path, and the handshake completed with no credentials
  presented anywhere.

## Verdict: Branch B

**Spring Security's `WebFilter` chain does not cover the RSocket-over-WebSocket
endpoint, and `spring.webflux.base-path` does not relocate it.** The endpoint stayed at
`/rsocket` — outside `/zork` — and completed an unauthenticated WebSocket upgrade
(`101`) while every ordinary HTTP path under the app was correctly gated (`401`). This
matches the brief's hypothesis exactly: RSocket-over-WebSocket attaches to the Netty
server through a route provider that sits outside the `HttpHandler` layer where both
`base-path` and the security `WebFilter` chain are applied.

**Do not start Tasks 3–5.** The plan's "gate at the handshake" model — hang Google
OAuth in front of the whole app and assume the WebSocket inherits that gate — is
invalid as written. Shipping it as planned would present a login page at `/zork/` while
leaving `/rsocket` open to the internet with no authentication at all.

## Candidates for the redesign

Per the brief, the security boundary for the RSocket channel needs its own mechanism,
independent of the WebFlux `HttpHandler` path:

- `spring-security-rsocket`, gating on the RSocket `SETUP`/`REQUEST` frame itself
  (Spring Security has a dedicated RSocket security module for exactly this — it does
  not rely on the WebFilter chain), fed a token minted by an authenticated HTTP
  endpoint (e.g. after Google OAuth completes, the app hands the browser a short-lived
  token to present in the RSocket setup payload).
- An nginx `auth_request` directive in front of the `/rsocket` upgrade at the reverse
  proxy layer, so the WebSocket upgrade itself never reaches Netty unless nginx has
  already validated the caller's session.

This finding does not pick between them — that decision belongs to whoever revises the
spec.

## Verification housekeeping

`build.gradle.kts` and `src/main/resources/application.yaml` were reverted to their
committed state (`git checkout -- build.gradle.kts
src/main/resources/application.yaml`) before this document was written up. Only this
findings file was staged and committed.
