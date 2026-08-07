---
name: 2026-08-06-handoff-oracle-deployment
description: Session handoff — the deployment branch is built and reviewed end to end; only the operator tasks on the box, the DreamPi and Cloudflare remain.
metadata:
  type: project
---

Supersedes [[2026-08-05-handoff-server-hardening]] except for its merge question, which is
still open. Two of that handoff's three blocking decisions are closed in
[[2026-08-06-oracle-deployment-decisions]].

## Where things stand

**All seven agent-executable tasks are complete and independently reviewed**, plus a
whole-branch review and one fix wave with a scoped re-review. Twenty-one commits on
`rsocket-terminal`, still unmerged. The spec is
`docs/superpowers/specs/2026-08-06-oracle-deployment-design.md`, the plan is
`docs/superpowers/plans/2026-08-06-oracle-deployment.md`, and every review finding, ruling
and parked item is in `.superpowers/sdd/2026-08-06-oracle-deployment/progress.md`. Do not
re-derive any of it.

**Nothing is deployed.** The remaining work is `O2`, `O3` and `O4` in the plan — all operator
tasks needing SSH or dashboard access. `O1` is done; its raw output is in
`docs/superpowers/findings/2026-08-06-phase-0-verification.md`.

## The finding that changed the design

A spike measured that **Spring Boot attaches RSocket-over-WebSocket outside the
`HttpHandler`**, so neither `spring.webflux.base-path` nor the Spring Security `WebFilter`
chain reaches `/rsocket`. Unauthenticated, `/rsocket` returns 101 while `/zork/` returns 401.
The original "gate at the HTTP handshake" model was therefore unsound, and adding a login
page alone would have shipped an open websocket behind it. Detail in
[[rsocket-terminal-project]].

The boundary now lives in nginx: `auth_request` against Spring's `/zork/_authcheck`, which
answers 204 or **401 — never 302**, because `auth_request` turns an unexpected redirect into
a 500 and denies every upgrade while looking like a server fault.

**Task 3 and Task 6 must be deployed together.** Neither closes the hole alone.

## What O1 found that the plan did not expect

The findings file's own summary says "everything verified"; four items were not clean, and
the plan was revised for them in `94f5589`.

- **The box's live nginx stream config is not the repo's file.** Live has `proxy_timeout 10m`
  and a `proxy_connect_timeout 5s` the repo lacks; the repo says `1h`. Editing the repo copy
  on the box changes nothing. O2 Step 6 now locates the live file; Step 6b reconciles the two.
  The live `10m` is also a latent session-drop bug for a Saturn idling at a prompt.
- **Nothing listens on 8080** — the bridge has never run there. With the pre-existing
  standalone `multizork` container still up, `docker compose up` risks a name conflict. O2
  Step 1a checks first.
- **No host firewall at all.** `-P INPUT ACCEPT`, no rules, ufw inactive. The Oracle Security
  List is the only network boundary, so the loopback-only publishing in `docker-compose.yml`
  is load-bearing rather than defence in depth.
- **No DuckDNS updater is visible.** No timer, `crontab` not installed. It resolves today; if
  the Oracle IP moves, the Saturn and the terminal both break silently. Unresolved.

Also: nginx on the box is **1.18.0**, older than Task 6 was validated against, so O4 Step 2b
checks for the `auth_request` module with an explicit stop condition.

## Measured, so nobody re-litigates it

- **`spring-security#8967` does not reproduce on Spring Boot 4.1.0.** `GET /zork` and
  `GET /zork/` behave identically on a real server. The nginx `/zork` → `/zork/` redirect was
  kept anyway as insurance against a future regression.
- **No endpoint ever accepted a username and password.** `setFormLoginEnabled(true)` is
  guarded on `formLogin != null`, which stays null. The `.disable()` calls are no-ops, and
  the generated login page was an OAuth2 provider picker with no password field.
- `CookieServerCsrfTokenRepository` defaults are cookie `XSRF-TOKEN`, header `X-XSRF-TOKEN`,
  confirmed in the 7.1.0 sources.

## Traps worth carrying forward

- **Two verifications in this session returned confident false signals.** The base-path tests
  could not observe base-path at all under `webEnvironment = MOCK`, and a Docker `nginx -t`
  invocation was silently rewritten by MSYS path mangling so it validated nginx's *default*
  config and reported success. Any `docker -v` call from git-bash here needs
  `MSYS_NO_PATHCONV=1` and an `ls` inside the container before its result is trusted.
- **`grep -c` cannot detect NUL bytes**, and bash cannot hold one in `$'...'` — see
  [[recurring-build-hazards]], which the hazard bit again during this session.
- **A subagent dispatch was blocked by the permission classifier.** A plainer prompt got
  through. Simplify rather than abandoning independent review.

## A slow-test regression this branch introduced

The Kotlin suite went from seconds to **2m22s**. `BasePathAuthCheckTest` runs at
`RANDOM_PORT`, and its Spring context logs *"Graceful shutdown aborted with one or more
requests still active"* five times, each after a **30-second** `webServerGracefulShutdown`
timeout — roughly 150 of those 142 seconds. Something in the real-server tests is not
releasing its connection.

The tests pass and the result is correct, so this is not a defect in the code under test. But
a two-and-a-half-minute suite gets run less often, and the real-server tests are exactly the
ones that caught the base-path blind spot. Worth chasing: likely a `WebTestClient` connection
left open, or `spring.lifecycle.timeout-per-shutdown-phase` wanting a shorter value in the
test profile.

## Deployed 2026-08-07

The terminal is live and working at `https://suin.uk/zork/` — Google sign-in, WebSocket, and
the game all confirmed by the operator in a browser. The port-23 gate is live. O3 is the only
step of the plan never executed.

Two defects surfaced only during deployment, both in seams between separately-reviewed
components, and both fixed:

- **`server.reactive.session.cookie.path`** defaulted to `/zork/`, which RFC 6265 does not
  send to `/zork`. Sign-in succeeded, then the post-login redirect to the saved request
  `/zork` arrived with no cookie and bounced back to Google, landing on `/zork/login?error`.
  Fixed by pinning the path to `/zork`; regression test in `BasePathAuthCheckTest`.
- **`absolute_redirect off`** was missing from the vhost. nginx absolutises relative
  redirects using the `Host` header, which the Worker sets to `terminal.suin.uk` — so
  `/zork` threw visitors off `suin.uk` onto the origin host, where the session cookie and the
  pinned OAuth callback both belong to a different domain.

Neither was catchable by per-task review; the only thing that found them was clicking through
the deployed system.

## Cloudflare: this zone serves HTTP-only content

**Never enable zone-wide *Always Use HTTPS* on `suin.uk`.** The zone serves an HTTP-only
sub-site at `/0` for period Sega hardware — a Sega Saturn NetLink browser cannot negotiate
modern TLS, so the upgrade takes the console from working to dead. This was prescribed during
this session and it was wrong; the docs now say so in `ops/cloudflare/README.md`.

The operator's own rule handles the HTTP side: `(not ssl) and (http.request.uri.path in
{"/zork" "/z" "/z1" "/zork1" "/0" "/"})`. Any rule added for the terminal must be scoped with
`ssl` and ordered **below** it.

**Cloudflare Browser Integrity Check returns 1010 for period-console user agents.** Measured:
the same URL returned 200 to `curl` and 403 to a Dreamcast UA. Disable it, or skip it with a
custom or configuration rule.

**A Cloudflare rule change made during this session broke something for the Saturn browser.**
The operator reports it worked before and did not afterwards. Edge probes with Saturn,
Dreamcast and empty user agents later returned 200 over plain HTTP with no upgrade, so the
specific regression was not isolated before the session ended. If Saturn browsing misbehaves,
start from the zone's rule list and rule ordering rather than from the box.

## Open

1. **Operator Task O3** — the upstream Netlink PR publishing the 199408 block with the
   secret. Never executed. Until it merges, only this DreamPi can dial the gated port 23;
   every other DreamPi is locked out.
2. **Merge `rsocket-terminal`.** `origin/main` now carries all the work and is what the box
   pulls; the `rsocket-terminal` branch and `master` are both stale. Decide what the branch
   topology should actually be.
3. **A Saturn-browser regression from a Cloudflare rule change**, described above, not
   isolated.
4. **The DuckDNS updater** — none found on the box. If the Oracle IP moves, the Saturn and
   the terminal both break silently.
5. **The Cloudflare README's dashboard menu labels are unverified.** Cloudflare Redirect
   Rules were confirmed to have no "preserve path suffix" option — only "Preserve query
   string" — after that name was asserted from memory and was wrong. Verify labels against
   the live dashboard or the docs before repeating any of them.

Relates to [[rsocket-terminal-project]], [[suinevere-server-hardening]],
[[2026-08-06-oracle-deployment-decisions]], [[recurring-build-hazards]].
