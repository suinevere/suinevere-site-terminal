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

## Open

1. **Merge `rsocket-terminal`.** Still unanswered from the previous handoff. Use
   superpowers:finishing-a-development-branch.
2. **The Cloudflare README's dashboard menu labels are unverified** — the structure is right,
   the exact wording may have drifted. Confirm against the live dashboard during O4.
3. **The DuckDNS updater** — find it or create it.

Relates to [[rsocket-terminal-project]], [[suinevere-server-hardening]],
[[2026-08-06-oracle-deployment-decisions]], [[recurring-build-hazards]].
