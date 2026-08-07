# Oracle Cloud Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put the browser terminal behind Google sign-in at `https://suin.uk/zork`, and put the Saturn's port-23 path behind the Netlink `AUTH` doorman, without an outage.

**Architecture:** Two independent gates. The Saturn passes a shared-secret preamble validated by `ops/authproxy` in front of `multizorkd`; the browser passes Google OAuth enforced by Spring Security. The web path reaches `multizork` over the compose network and never touches port 23, so the two cannot break each other. A Cloudflare Worker keeps `suin.uk` in the URL bar by passing requests through verbatim to `terminal.suin.uk`.

**Tech Stack:** Kotlin 2.3 / Spring Boot 4.1 (WebFlux + RSocket + OAuth2 client), React 19 + XTerm.js + Vite 7, Docker Compose, nginx (`http` and `stream`), Cloudflare Workers.

**Spec:** `docs/superpowers/specs/2026-08-06-oracle-deployment-design.md`
**Decisions:** `mem/2026-08-06-oracle-deployment-decisions.md`

## Global Constraints

- Author of record is **suinevere**. Every file, method and constant gets the project header block. No comments inside function bodies. Prose in comments: one sentence, the non-obvious thing, then stop.
- Commit after every change. Message is **one sentence** — no body, no bullets, no trailers, and no mention of Claude, AI, or the session.
- **NUL hazard.** `frontend/src/rsocket/terminalSession.ts` declares `INIT_SENTINEL` as the four letters `INIT` preceded by a Unicode escape for code point zero, written as six source characters. Write/Edit can silently collapse that escape into a single raw NUL byte, which still compiles and then fails at runtime. **This bit while authoring this very plan** — the warning line itself was corrupted and had to be rewritten. Any task touching that file must afterwards confirm the file contains no NUL bytes: `tr -dc '\000' < frontend/src/rsocket/terminalSession.ts | wc -c` must print **0**. Note that `grep -c` counts matching *lines*, and bash cannot hold a NUL in `$'...'`, so both are useless for this check — use `tr`. See `mem/recurring-build-hazards.md`.
- **No secret is ever committed.** `AUTH_SECRET`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` live only in `docker/.env`, mode 600, gitignored.
- Stage explicit paths in `git add`. Never `git add -A` — it previously swept in an unrelated IntelliJ edit.
- `Netlink/` is an embedded upstream git repo and is gitignored. Do not commit anything under it from this repo.
- Docker and compose files are pinned to LF by `.gitattributes`; a CRLF checkout breaks Dockerfile line continuations.
- **Every test must be verified to fail when its feature is removed** before it counts as done. Three tests in this repo previously passed with their feature deleted.

## Task types

| Prefix | Who runs it | Why |
|---|---|---|
| **Task N** | Agent or engineer, in the repo | Ordinary code change with a test cycle |
| **Operator Task ON** | **suinevere only**, over SSH / in a web console | Needs credentials to the Oracle box, the DreamPi, Cloudflare, or Google Cloud — cannot be delegated |

Operator tasks are written as runbooks with explicit expected output, so the result can be pasted back and checked.

---

## File Structure

**Created:**
- `ops/cloudflare/worker.js` — the pass-through Worker. Lives beside `ops/authproxy/` because both are deployed artefacts that are not part of the Gradle build.
- `ops/cloudflare/README.md` — routes, redirect rules, and the Google Console values.
- `src/main/kotlin/.../terminal/security/SecurityConfig.kt` — the only place authentication policy is expressed.
- `src/test/kotlin/.../terminal/security/SecurityConfigTest.kt` — proves the policy, including that password login stays off.
- `frontend/src/rsocket/sessionUrl.ts` — base-path-aware WebSocket URL, extracted so it can be tested.
- `frontend/src/rsocket/sessionUrl.test.ts`
- `frontend/src/components/SignOut.tsx` — logout control.
- `docs/superpowers/findings/2026-08-06-phase-0-verification.md` — Phase 0 output.
- `docs/superpowers/findings/2026-08-06-rsocket-binding-spike.md` — Task 2 output.

**Modified:**
- `.gitignore` — ignore `docker/.env`
- `docker/docker-compose.yml` — add the `authproxy` service
- `ops/authproxy/README.md` — correct the `UPSTREAM_HOST` default
- `docker/nginx/stream-multizork.conf` — repoint at the authproxy
- `docker/nginx/terminal.suin.uk.conf` — `Origin` guard, `/zork/rsocket`
- `build.gradle.kts` — OAuth2 client and `spring-security-test`
- `src/main/resources/application.yaml` — base path, OAuth registration
- `frontend/vite.config.ts` — `base: '/zork/'`, dev proxy path
- `frontend/src/components/Terminal.tsx` — use the extracted `sessionUrl`, mount `SignOut`

---

## Operator Task O1: Phase 0 verification

**Runs on:** your laptop, the Oracle box, the DreamPi.
**Deliverable:** `docs/superpowers/findings/2026-08-06-phase-0-verification.md` recording each command and its actual output.

Nothing is changed in this task. If a step fails, record it and continue — the findings list is triaged before Phase 1 starts.

- [ ] **Step 1: DNS is current**

From your laptop, then on the box:

```bash
dig +short suinevere.duckdns.org
```
```bash
curl -s ifconfig.me; echo
systemctl list-timers | grep -i duck
crontab -l | grep -i duck
```

Expected: the two addresses match. If they differ the DuckDNS updater is dead — read its last log line. A timer existing is not evidence it succeeded.

- [ ] **Step 2: Oracle ingress, both layers**

From your laptop:

```bash
for p in 22 23 80 443 111 8080; do
  (timeout 3 bash -c "</dev/tcp/suinevere.duckdns.org/$p" 2>/dev/null \
     && echo "$p open") || echo "$p closed"
done
```

On the box:

```bash
sudo iptables -S INPUT
```

Expected: 22, 23, 80, 443 open; 111 and 8080 closed. Oracle's stock Ubuntu image ships `/etc/iptables/rules.v4` rejecting everything but 22 — an open Security List with a closed host table looks identical from outside, so both layers must be read.

- [ ] **Step 3: Listener map**

```bash
sudo ss -ltnp
sudo ufw status verbose
```

Expected: exactly `0.0.0.0:22`, `:23`, `:80`, `:443` and `127.0.0.1:2323`, `127.0.0.1:8080`. Anything else on `0.0.0.0` is a finding. `ufw` output is advisory only for published container ports — Docker's iptables rules run first.

- [ ] **Step 4: nginx actually loaded the stream block**

```bash
sudo nginx -t
sudo nginx -T | grep -B2 -A8 'listen 23'
dpkg -l | grep libnginx-mod-stream
```

Expected: the `stream` server appears in the dumped config. A correct file in `streams-enabled/` that `nginx.conf` never includes at top level starts cleanly and serves nothing.

- [ ] **Step 5: Stack health**

```bash
cd /opt/suinevere-site-terminal/docker
docker compose ps
docker compose logs --tail=50
```

Expected: `multizork` and `suinevere-site-terminal` both healthy.

- [ ] **Step 6: End to end**

From your laptop:

```bash
printf 'q\r\n' | timeout 8 nc suinevere.duckdns.org 23
```

Expected: the MultiZork banner. "Connect OK then zero bytes" is ambiguous between a gate rejection and nothing listening upstream — if you see it, run `sudo ss -ltnp | grep 2323` on the box before concluding anything.

- [ ] **Step 7: DreamPi**

```bash
systemctl status dreampi
grep -A5 '199408' /path/to/netlink_config.ini
```

Expected: service running, 199408 pointing at `suinevere.duckdns.org:23` with `handler = transparent`.

- [ ] **Step 8: TLS inventory**

```bash
sudo certbot certificates
sudo nginx -T | grep -A6 'listen 80'
```

Expected: no `terminal.suin.uk` certificate yet — Operator Task O4 issues it. Record whether the port-80 vhost redirects to `http://suin.uk` or `https://`.

- [ ] **Step 9: Write and commit the findings**

```bash
git add docs/superpowers/findings/2026-08-06-phase-0-verification.md
git commit -m "Record the pre-deployment verification of the Oracle box, its firewall layers and the Saturn path"
```

---

## Task 1: Add the authproxy service and stop secrets reaching git

**Files:**
- Modify: `.gitignore`
- Modify: `docker/docker-compose.yml`
- Modify: `ops/authproxy/README.md`

**Interfaces:**
- Consumes: `ops/authproxy/Dockerfile` (existing), `multizork` service (existing)
- Produces: an `authproxy` service listening on `127.0.0.1:2322`, reachable by host nginx. Operator Task O2 depends on that address and on `docker/.env` holding `AUTH_SECRET`.

The `.gitignore` change comes first on purpose: the secret must have nowhere to land before it exists.

- [ ] **Step 1: Ignore the env file**

Append to `.gitignore`:

```gitignore

### Deployment secrets (never committed) ###
docker/.env
```

- [ ] **Step 2: Verify the ignore rule works**

```bash
printf 'AUTH_SECRET=placeholder\n' > docker/.env
git status --porcelain docker/.env
```

Expected: **no output**. If `docker/.env` is listed, the rule is wrong — fix it before continuing.

```bash
rm docker/.env
```

- [ ] **Step 3: Add the authproxy service**

In `docker/docker-compose.yml`, insert between the `multizork` and `terminal` services:

```yaml
  authproxy:
    build:
      context: ../ops/authproxy
      dockerfile: Dockerfile
    image: suinevere-authproxy:latest
    container_name: suinevere-authproxy
    restart: unless-stopped
    depends_on:
      - multizork

    # nginx on the host proxies port 23 here. Never published beyond loopback.
    ports:
      - "127.0.0.1:2322:2322"

    environment:
      AUTH_SECRET: "${AUTH_SECRET:?AUTH_SECRET must be set in docker/.env}"
      AUTH_MAGIC: "${AUTH_MAGIC:-AUTH}"
      LISTEN_HOST: "0.0.0.0"
      LISTEN_PORT: "2322"
      UPSTREAM_HOST: "multizork"
      UPSTREAM_PORT: "2323"
      AUTH_TIMEOUT: "5.0"
      MAX_CONN: "32"
      IDLE_TIMEOUT: "1800"

    read_only: true
    tmpfs:
      - /tmp
    security_opt:
      - no-new-privileges:true
    cap_drop:
      - ALL

    deploy:
      resources:
        limits:
          memory: 128M

    logging:
      driver: json-file
      options:
        max-size: 10m
        max-file: "3"
```

`UPSTREAM_HOST` is `multizork`, not `127.0.0.1`. Inside a container, loopback is the proxy's own loopback and the relay would connect to nothing.

- [ ] **Step 4: Verify compose rejects a missing secret**

```bash
cd docker && docker compose config >/dev/null
```

Expected: **failure**, with `AUTH_SECRET must be set in docker/.env`. That proves the service cannot start secretless.

- [ ] **Step 5: Verify compose accepts a present secret**

```bash
printf 'AUTH_SECRET=placeholder\n' > docker/.env
cd docker && docker compose config | grep -A3 'UPSTREAM_HOST'
rm docker/.env
```

Expected: `UPSTREAM_HOST: multizork`.

- [ ] **Step 6: Correct the README's wrong default**

In `ops/authproxy/README.md`, change the `UPSTREAM_HOST` row of the configuration table to:

```markdown
| `UPSTREAM_HOST` | `127.0.0.1` | Where `multizorkd` listens. **In the compose stack this must be `multizork`** — inside a container `127.0.0.1` is the proxy's own loopback, not the game. |
```

- [ ] **Step 7: Commit**

```bash
git add .gitignore docker/docker-compose.yml ops/authproxy/README.md
git commit -m "Run the authproxy in the stack on loopback and keep its secret out of git"
```

---

## Operator Task O2: Raise the port-23 gate, staged

**Runs on:** the Oracle box and the DreamPi.
**Depends on:** Task 1 merged and pulled onto the box.

No step here is irreversible, and the cutover is an nginx *reload* — port 23 is never released.

### What O1 changed about this task

O1's findings (`docs/superpowers/findings/2026-08-06-phase-0-verification.md`) invalidated an
assumption this task was originally written on. Read this before Step 1.

**The box's live nginx stream config is not the repo's file.** The deployed block reads
`proxy_timeout 10m;` plus a `proxy_connect_timeout 5s;` that the repo's
`docker/nginx/stream-multizork.conf` does not contain, and the repo's file specifies
`proxy_timeout 1h;`. Editing the repo file on the box will therefore change nothing.
Step 6 below has been rewritten to locate and edit the **live** file.

The `1h` in the repo was deliberate — a Saturn sitting at a prompt sends nothing, and the
memo records that a short timeout drops it mid-game. **The live `10m` is a latent
session-drop bug**, independent of this deployment. Fix it during the cutover.

**Only `multizork` is running on the box; nothing listens on 8080.** The terminal bridge has
never been deployed there. Combined with the previous session's note that a *pre-existing
standalone* `multizork` container is still running, `docker compose up` may hit a container
name conflict. Step 1a checks for that before anything else.

**There is no host firewall.** `iptables -S INPUT` is `-P INPUT ACCEPT` with no rules and ufw
is inactive, so the Oracle Cloud Security List is the *only* network boundary. Nothing here
depends on that, but it means the loopback-only publishing in `docker-compose.yml` is now
load-bearing rather than defence in depth — a `0.0.0.0` publish would be immediately public.

**No DuckDNS updater is visible on the box.** `systemctl list-timers` shows none and
`crontab` is not installed. The A record currently resolves, so something is updating it or
the IP has not moved since it was set by hand. Worth resolving separately; it is not a
blocker for this task, but if the Oracle IP ever changes, both the Saturn and the terminal
break with no warning.

- [ ] **Step 1a: Check for a container name conflict before touching anything**

```bash
docker ps -a --format '{{.Names}}\t{{.Image}}\t{{.Status}}' | grep -i multizork
```

If a standalone `multizork` container exists outside the compose stack, `docker compose up`
will fail on the name. Decide deliberately whether to stop and remove it — it is the
container currently serving the game, so removing it is an outage — or to run the stack under
a different container name. Do not let `docker compose` discover this for you mid-cutover.

- [ ] **Step 1: Generate the secret**

```bash
cd /opt/suinevere-site-terminal/docker
umask 077
printf 'AUTH_SECRET=%s\n' "$(openssl rand -hex 16)" > .env
chmod 600 .env
cat .env
```

Record the value; you need it on the DreamPi and in the upstream PR. Thirty-two characters, well inside the protocol's one-byte length field.

- [ ] **Step 2: Bring the proxy up**

```bash
docker compose up -d authproxy
docker compose ps authproxy
sudo ss -ltnp | grep 2322
```

Expected: running, and listening on `127.0.0.1:2322` only.

- [ ] **Step 3: Prove rejection**

```bash
printf 'hello\r\n' | timeout 8 nc 127.0.0.1 2322; echo "exit=$?"
```

Expected: immediate close, no banner.

- [ ] **Step 4: Prove acceptance**

```bash
SECRET=$(grep AUTH_SECRET .env | cut -d= -f2)
printf 'AUTH\x20%s' "$SECRET" | timeout 8 nc 127.0.0.1 2322
```

Expected: `\x01` then the MultiZork banner. `\x20` is 32, the secret's length — **a wrong length byte fails identically to a wrong secret**, so a rejection here is not yet evidence the secret is wrong. Recount before doubting anything else.

- [ ] **Step 5: Test a real Saturn against the proxy, with no new internet exposure**

On the DreamPi:

```bash
ssh -N -L 2322:127.0.0.1:2322 <oracle-host>
```

In a second shell on the DreamPi, edit `netlink_config.ini` so 199408 reads:

```ini
[server:199408]
name = MultiZork
host = 127.0.0.1
port = 2322
shared_secret = <the value from Step 1>
auth_magic = AUTH
auth_timeout = 5.0
```

Removing `handler = transparent` selects `netlink_standard_server`. Restart the tunnel and dial 199408 from the Saturn.

Expected: the game, over a real modem session. **The two handlers have different relay implementations — a passing `nc` test in Step 4 does not imply a working modem session, which is the entire reason this step exists.** If it fails, `docker compose logs -f authproxy` on the box during the dial.

- [ ] **Step 6: Cut over — on the LIVE config, not the repo copy**

Find the file nginx actually loads. It is not the repo's copy:

```bash
grep -rl 'listen 23' /etc/nginx/ 2>/dev/null
```

Edit **that** file: set the active `proxy_pass` to `127.0.0.1:2322`, and while you are in
there raise `proxy_timeout` from the live `10m` to `1h` — the repo's value, chosen because a
Saturn idling at a prompt sends nothing and gets dropped. Then:

```bash
sudo nginx -t
sudo systemctl reload nginx
```

Expected: `test is successful`, then a clean reload. A reload, not a restart — nginx keeps holding port 23 throughout, so there is no outage. Rollback is putting the old `proxy_pass` line back and reloading again.

- [ ] **Step 6b: Reconcile the repo with the box**

The live file and `docker/nginx/stream-multizork.conf` had drifted before this task; leaving
them drifted guarantees the next person edits the wrong one. Copy the now-correct live
content into the repo file, keeping the repo's header block, and commit it:

```bash
git add docker/nginx/stream-multizork.conf
git commit -m "Match the stream config in the repository to the one the server actually loads"
```

- [ ] **Step 7: Confirm the public path**

From your laptop:

```bash
printf 'hello\r\n'            | timeout 8 nc suinevere.duckdns.org 23; echo "exit=$?"
printf 'AUTH\x20<secret>'     | timeout 8 nc suinevere.duckdns.org 23
```

Expected: first closes immediately, second reaches the banner.

- [ ] **Step 8: Point the DreamPi back at the public address**

Set 199408 back to `host = suinevere.duckdns.org, port = 23`, keeping `shared_secret`. Restart the tunnel, close the SSH forward, dial from the Saturn.

- [ ] **Step 9: Confirm the web terminal is untouched**

```bash
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/
```

Expected: `200`. The bridge reaches `multizork` over the compose network, so nothing in this task should have affected it. **If this fails, `TERMINAL_UPSTREAM_HOST` is wrong** — it must be `multizork`, not the public hostname.

- [ ] **Step 10: Commit the cutover**

```bash
git add docker/nginx/stream-multizork.conf
git commit -m "Route port 23 through the authproxy so the Saturn path is gated by the shared secret"
```

---

## Operator Task O3: Publish the dial entry upstream

**Runs in:** the Netlink tunnel repo, on GitHub.
**Depends on:** O2 complete and the Saturn confirmed working.

- [ ] **Step 1: Open the PR**

Change the 199408 block in the upstream `netlink_config.ini` to the authenticating handler, **including the secret value**:

```ini
[server:199408]
name = MultiZork
host = suinevere.duckdns.org
port = 23
shared_secret = <the value from O2 Step 1>
auth_magic = AUTH
auth_timeout = 5.0
```

Publishing it is the accepted trade, recorded in `mem/2026-08-06-oracle-deployment-decisions.md`: the gate filters background internet scanning before a v0.0.9 hand-written C parser, and stops nobody who reads the repo. The web terminal and the SSH admin path (`nc 127.0.0.1 2323`) both bypass it by design.

- [ ] **Step 2: Confirm a stock DreamPi still plays**

After the PR merges, on a DreamPi with an unmodified checkout, dial 199408 and reach the game.

---

## Task 2: Spike — where does RSocket-over-WebSocket actually bind, and is it authenticated?

**Files:**
- Modify (temporarily): `src/main/resources/application.yaml`
- Create: `docs/superpowers/findings/2026-08-06-rsocket-binding-spike.md`

**Interfaces:**
- Produces: a written finding that Tasks 3 and 4 depend on — the real path of the RSocket WebSocket endpoint under a base path, and whether Spring Security's `WebFilter` chain covers it.

**Why this is a task and not an assumption.** With `spring.rsocket.server.transport=websocket` and no separate `spring.rsocket.server.port`, Spring Boot plugs RSocket into the running Netty server through a route provider rather than through the `HttpHandler`. `spring.webflux.base-path` is applied at the `HttpHandler` layer, and so is the Spring Security `WebFilter` chain. If RSocket bypasses that layer, then **`/rsocket` neither moves under `/zork` nor gets protected**, and the design's "gate at the handshake" model does not hold. Measure it before building on it.

- [ ] **Step 1: Add the base path and a throwaway security config**

In `src/main/resources/application.yaml`, under `spring:`, add:

```yaml
  webflux:
    base-path: /zork
```

Temporarily add to `build.gradle.kts` dependencies:

```kotlin
	implementation("org.springframework.boot:spring-boot-starter-security")
```

- [ ] **Step 2: Start the app**

```bash
./gradlew bootRun
```

- [ ] **Step 3: Find the endpoint**

In another shell, attempt a WebSocket upgrade against both candidate paths:

```bash
for p in /rsocket /zork/rsocket; do
  echo "== $p"
  curl -sS -i -o - -m 5 \
    -H "Connection: Upgrade" -H "Upgrade: websocket" \
    -H "Sec-WebSocket-Version: 13" \
    -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
    "http://localhost:8080$p" | head -5
done
```

Record which path returns `101 Switching Protocols`, which returns `404`, and which returns `302`/`401`.

- [ ] **Step 4: Determine whether security covers it**

With `spring-boot-starter-security` on the classpath and no configuration, Spring generates a user and protects everything it filters. Repeat Step 3 without credentials.

- **`101`** on the RSocket path → **security does not cover it**. Branch B.
- **`401`/`302`** on the RSocket path → **security covers it**. Branch A.

Confirm the same for an ordinary page: `curl -sS -i http://localhost:8080/zork/ | head -3` should be `401`/`302`.

- [ ] **Step 5: Write the finding**

Create `docs/superpowers/findings/2026-08-06-rsocket-binding-spike.md` recording the exact paths, status codes and the branch. State plainly which of these holds:

- **Branch A — RSocket is filtered by Spring Security.** Proceed to Task 3 as written. Note the endpoint's real path; if it did **not** move under `/zork`, Task 3 pins `spring.rsocket.server.mapping-path: /zork/rsocket` explicitly, and if it **did** move, `mapping-path` stays `/rsocket` so the prefix is not applied twice.
- **Branch B — RSocket is not filtered.** **Stop. Do not start Task 3.** The handshake-gating model in the spec is invalid and the spec needs revising; report back so the security boundary can be redesigned (candidates: `spring-security-rsocket` with a token minted by an authenticated HTTP endpoint, or nginx `auth_request` in front of the upgrade). Guessing here would ship an open WebSocket behind a login page.

- [ ] **Step 6: Revert the throwaway change and commit only the finding**

```bash
git checkout -- build.gradle.kts src/main/resources/application.yaml
git add docs/superpowers/findings/2026-08-06-rsocket-binding-spike.md
git commit -m "Record where the RSocket websocket binds under a base path and whether the security filter covers it"
```

---

## Task 3: Require a Google identity, refuse every password

**Task 2 recorded Branch B.** The RSocket WebSocket is **not** covered by the Spring Security
`WebFilter` chain and does **not** move under `/zork`. This task was revised accordingly: it
no longer claims to protect the WebSocket. It protects the HTTP surface and exposes the
`/_authcheck` endpoint that nginx uses to protect the WebSocket in Task 6. **Neither task
alone closes the hole — Task 3 and Task 6 must both land before the app is deployed.**

**Files:**
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/application.yaml`
- Create: `src/main/kotlin/org/suinevere/site/terminal/suinevere_site_terminal/security/SecurityConfig.kt`
- Create: `src/main/kotlin/org/suinevere/site/terminal/suinevere_site_terminal/security/AuthCheckController.kt`
- Test: `src/test/kotlin/org/suinevere/site/terminal/suinevere_site_terminal/security/SecurityConfigTest.kt`

**Interfaces:**
- Produces: every HTTP exchange requires an authenticated Google principal; the app is served under `/zork`; `GET /zork/_authcheck` returns **204** when authenticated and **401** — never 302 — when not, which Task 6's `auth_request` depends on; the CSRF token is readable by JavaScript from the `XSRF-TOKEN` cookie, which Task 5's sign-out depends on.

- [ ] **Step 1: Add the dependencies**

In `build.gradle.kts`, add to `dependencies`:

```kotlin
	implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
	testImplementation("org.springframework.security:spring-security-test")
```

- [ ] **Step 2: Write the failing test**

Create `src/test/kotlin/org/suinevere/site/terminal/suinevere_site_terminal/security/SecurityConfigTest.kt`:

```kotlin
/*----------------------
 | SecurityConfigTest.kt
 | Description: Proves the app admits only a Google identity and offers no password credential of any kind.
 | Author: suinevere
 | Dependencies: SecurityConfig, spring-security-test, WebTestClient
 | Globals: N/A
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal.security

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOAuth2Login
import org.springframework.test.web.reactive.server.WebTestClient
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(
	properties = [
		"spring.security.oauth2.client.registration.google.client-id=test-client",
		"spring.security.oauth2.client.registration.google.client-secret=test-secret",
	],
)
@AutoConfigureWebTestClient
class SecurityConfigTest {

	@Autowired
	lateinit var client: WebTestClient

	@Test
	fun `an anonymous visitor is sent to google`() {
		val location = client.get().uri("/")
			.exchange()
			.expectStatus().is3xxRedirection
			.returnResult(String::class.java)
			.responseHeaders.location

		assertNotNull(location)
		assertTrue(
			location.toString().endsWith("/oauth2/authorization/google"),
			"expected a redirect to the google authorization endpoint, got $location",
		)
	}

	@Test
	fun `a google identity is admitted`() {
		client.mutateWith(mockOAuth2Login())
			.get().uri("/")
			.exchange()
			.expectStatus().isOk
	}

	@Test
	fun `there is no form login page`() {
		client.get().uri("/login")
			.exchange()
			.expectStatus().is3xxRedirection
	}

	@Test
	fun `the auth check endpoint answers 401 and never a redirect`() {
		client.get().uri("/_authcheck")
			.exchange()
			.expectStatus().isUnauthorized
	}

	@Test
	fun `the auth check endpoint answers 204 once signed in`() {
		client.mutateWith(mockOAuth2Login())
			.get().uri("/_authcheck")
			.exchange()
			.expectStatus().isNoContent
	}

	@Test
	fun `basic authentication is not offered`() {
		val challenge = client.get().uri("/")
			.header("Authorization", "Basic dXNlcjpwYXNzd29yZA==")
			.exchange()
			.returnResult(String::class.java)
			.responseHeaders.getFirst("WWW-Authenticate")

		assertNull(challenge, "the app offered a password challenge: $challenge")
	}
}
```

`/login` returning a redirect rather than `200` with an HTML form is the observable difference between `formLogin` enabled and disabled — that is what makes the third test meaningful rather than decorative.

- [ ] **Step 3: Run it and watch it fail**

```bash
./gradlew test --tests '*SecurityConfigTest*'
```

Expected: FAIL. Without `SecurityConfig`, requests return `200` unauthenticated and the first test fails on `is3xxRedirection`.

- [ ] **Step 4: Write the configuration**

Create `src/main/kotlin/org/suinevere/site/terminal/suinevere_site_terminal/security/SecurityConfig.kt`:

```kotlin
/*----------------------
 | SecurityConfig.kt
 | Description: Admits only a Google identity and disables every password-based credential.
 | Author: suinevere
 | Dependencies: spring-boot-starter-oauth2-client
 | Globals: N/A
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher

@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

	/*----------------------
	 | authCheckFilterChain
	 | Description: Answers nginx's auth_request subrequest with 401 rather than the redirect oauth2Login would otherwise send.
	 | Author: suinevere
	 | Dependencies: ServerHttpSecurity
	 | Globals: N/A
	 | Params: http -- the chain builder supplied by Spring Security
	 | Returns: the SecurityWebFilterChain guarding the auth-check endpoint alone
	 ----------------------*/
	@Bean
	@Order(1)
	fun authCheckFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain =
		http
			.securityMatcher(PathPatternParserServerWebExchangeMatcher("/_authcheck"))
			.authorizeExchange { it.anyExchange().authenticated() }
			.exceptionHandling { it.authenticationEntryPoint(HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)) }
			.csrf { it.disable() }
			.build()

	/*----------------------
	 | securityWebFilterChain
	 | Description: Requires a Google sign-in for every other exchange and refuses form and basic credentials outright.
	 | Author: suinevere
	 | Dependencies: ServerHttpSecurity
	 | Globals: N/A
	 | Params: http -- the chain builder supplied by Spring Security
	 | Returns: the configured SecurityWebFilterChain
	 ----------------------*/
	@Bean
	@Order(2)
	fun securityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain =
		http
			.authorizeExchange { it.anyExchange().authenticated() }
			.oauth2Login { }
			.formLogin { it.disable() }
			.httpBasic { it.disable() }
			.csrf { it.csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse()) }
			.logout { }
			.build()
}
```

Two things here are load-bearing and neither is obvious from reading the code:

`HttpStatusServerEntryPoint(UNAUTHORIZED)` on the first chain exists because `oauth2Login` would answer an unauthenticated `/_authcheck` with a **302 to Google**. nginx's `auth_request` treats a 302 as an unexpected response and returns **500** — a failure that reads as a server fault while actually denying every connection. The `@Order(1)` chain must therefore match `/_authcheck` **before** the general chain sees it.

`withHttpOnlyFalse` is what lets the browser read `XSRF-TOKEN` for the sign-out request in Task 5; without it, logout gets a 403 that looks like a session problem.

- [ ] **Step 4b: Write the auth-check endpoint**

Create `src/main/kotlin/org/suinevere/site/terminal/suinevere_site_terminal/security/AuthCheckController.kt`:

```kotlin
/*----------------------
 | AuthCheckController.kt
 | Description: The endpoint nginx subrequests to decide whether a websocket upgrade carries a signed-in session.
 | Author: suinevere
 | Dependencies: spring-boot-starter-webflux
 | Globals: N/A
 ----------------------*/
package org.suinevere.site.terminal.suinevere_site_terminal.security

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class AuthCheckController {

	/*----------------------
	 | authCheck
	 | Description: Returns an empty success once the filter chain has proved the caller is signed in.
	 | Author: suinevere
	 | Dependencies: N/A
	 | Globals: N/A
	 | Params: N/A
	 | Returns: N/A -- the 204 status is the whole answer
	 ----------------------*/
	@GetMapping("/_authcheck")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun authCheck() {
	}
}
```

The body is empty on purpose: reaching this method at all is the proof, because an unauthenticated request is rejected by the filter chain before it arrives.

- [ ] **Step 5: Add the application configuration**

In `src/main/resources/application.yaml`, under `spring:`:

```yaml
  webflux:
    base-path: /zork
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID}
            client-secret: ${GOOGLE_CLIENT_SECRET}
            scope: [openid, profile, email]
            redirect-uri: https://suin.uk/zork/login/oauth2/code/google
```

and at the top level:

```yaml
server:
  forward-headers-strategy: framework
```

**Leave `spring.rsocket.server.mapping-path` at its existing `/rsocket`.** Task 2 measured that base-path does not reach the RSocket endpoint, and Task 6 maps the external `/zork/rsocket` onto the internal `/rsocket` in nginx. Changing `mapping-path` here as well would double the prefix.

The absolute `redirect-uri` is deliberate: behind the Worker, Spring sees `Host: terminal.suin.uk` and would otherwise build a callback Google rejects with `redirect_uri_mismatch`, a failure that presents as a Console misconfiguration and is not one.

- [ ] **Step 6: Run the tests and watch them pass**

```bash
./gradlew test --tests '*SecurityConfigTest*'
```

Expected: PASS, six tests.

- [ ] **Step 7: Verify each test actually tests something**

Remove one feature at a time, re-run, confirm the named test FAILS, then restore it:

| Remove | Test that must fail |
|---|---|
| `.formLogin { it.disable() }` | `there is no form login page` |
| `.httpBasic { it.disable() }` | `basic authentication is not offered` |
| `.authorizeExchange { ... }` from the `@Order(2)` chain | `an anonymous visitor is sent to google` |
| the whole `authCheckFilterChain` bean | `the auth check endpoint answers 401 and never a redirect` |

The last one is the most important of the four. If that test still passes with the chain deleted, it is asserting nothing — and nginx will get a 302, answer 500, and refuse every WebSocket while looking like a server fault.

This step is not optional. Three tests in this repo previously passed with their feature deleted.

- [ ] **Step 8: Commit**

```bash
git add build.gradle.kts src/main/resources/application.yaml \
        src/main/kotlin/org/suinevere/site/terminal/suinevere_site_terminal/security/SecurityConfig.kt \
        src/main/kotlin/org/suinevere/site/terminal/suinevere_site_terminal/security/AuthCheckController.kt \
        src/test/kotlin/org/suinevere/site/terminal/suinevere_site_terminal/security/SecurityConfigTest.kt
git commit -m "Require a Google sign-in for the terminal and answer the nginx auth check with a status rather than a redirect"
```

---

## Task 4: Make the frontend base-path aware

**Files:**
- Create: `frontend/src/rsocket/sessionUrl.ts`
- Test: `frontend/src/rsocket/sessionUrl.test.ts`
- Modify: `frontend/src/components/Terminal.tsx`
- Modify: `frontend/vite.config.ts`

**Interfaces:**
- Consumes: the endpoint path recorded by Task 2.
- Produces: `sessionUrl(location: UrlLocation, baseUrl: string): string`, where `UrlLocation` is `{ protocol: string; host: string }`. `Terminal.tsx` calls it with `window.location` and `import.meta.env.BASE_URL`.

`sessionUrl` currently lives inline and untested in `Terminal.tsx`. Extracting it is what makes the base-path change verifiable rather than hopeful.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/rsocket/sessionUrl.test.ts`:

```ts
/*----------------------
 | sessionUrl.test.ts
 | Description: Covers websocket scheme selection and base-path joining for the RSocket endpoint.
 | Author: suinevere
 | Dependencies: vitest, sessionUrl
 | Globals: N/A
 ----------------------*/
import { describe, expect, it } from 'vitest'
import { sessionUrl } from './sessionUrl'

describe('sessionUrl', () => {
  it('joins a base path onto a secure endpoint', () => {
    expect(sessionUrl({ protocol: 'https:', host: 'suin.uk' }, '/zork/'))
      .toBe('wss://suin.uk/zork/rsocket')
  })

  it('uses ws when the page is not secure', () => {
    expect(sessionUrl({ protocol: 'http:', host: 'localhost:5173' }, '/'))
      .toBe('ws://localhost:5173/rsocket')
  })

  it('tolerates a base path without a trailing slash', () => {
    expect(sessionUrl({ protocol: 'https:', host: 'suin.uk' }, '/zork'))
      .toBe('wss://suin.uk/zork/rsocket')
  })
})
```

- [ ] **Step 2: Run it and watch it fail**

```bash
cd frontend && npx vitest run src/rsocket/sessionUrl.test.ts
```

Expected: FAIL — `sessionUrl.ts` does not exist.

- [ ] **Step 3: Write the implementation**

Create `frontend/src/rsocket/sessionUrl.ts`:

```ts
/*----------------------
 | sessionUrl.ts
 | Description: Builds the RSocket websocket URL from the page location and the bundle's base path.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 ----------------------*/

export type UrlLocation = {
  protocol: string
  host: string
}

/*----------------------
 | sessionUrl
 | Description: Resolves the RSocket endpoint so the same bundle works at the site root and under /zork.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: location -- the page protocol and host; baseUrl -- the bundle base path, with or without a trailing slash
 | Returns: an absolute ws:// or wss:// URL for the RSocket mapping path
 ----------------------*/
export function sessionUrl(location: UrlLocation, baseUrl: string): string {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
  const base = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl
  return `${scheme}://${location.host}${base}/rsocket`
}
```

- [ ] **Step 4: Run the tests and watch them pass**

```bash
cd frontend && npx vitest run src/rsocket/sessionUrl.test.ts
```

Expected: PASS, three tests.

- [ ] **Step 5: Verify the tests bite**

Change `location.protocol === 'https:'` to `false` and re-run: the first and third tests must FAIL. Restore it.

- [ ] **Step 6: Use it from Terminal.tsx**

In `frontend/src/components/Terminal.tsx`, delete the local `sessionUrl` function and its header block, add to the imports:

```ts
import { sessionUrl } from '../rsocket/sessionUrl'
```

and change the call site from `url: sessionUrl(),` to:

```ts
      url: sessionUrl(window.location, import.meta.env.BASE_URL),
```

- [ ] **Step 7: Set the bundle base path and fix the dev proxy**

In `frontend/vite.config.ts`, add `base: '/zork/',` immediately after `plugins: [react()],`, and change the dev proxy key from `'/rsocket'` to `'/zork/rsocket'`:

```ts
  server: {
    port: 5173,
    proxy: {
      '/zork/rsocket': {
        target: 'ws://localhost:8080',
        ws: true,
        rewrite: (path) => path.replace(/^\/zork/, ''),
      },
    },
  },
```

The `rewrite` is required because **Spring serves the WebSocket at `/rsocket`, not `/zork/rsocket`** — Task 2 measured that `spring.webflux.base-path` does not reach the RSocket endpoint. In production nginx performs this same mapping; in dev there is no nginx, so Vite must do it. Without the rewrite the dev server proxies to a path Spring does not serve and the terminal silently fails to connect while the page loads fine.

Without `base`, assets are requested from `/assets/*` and 404 through the Worker while the page itself loads — a failure that looks like a Worker bug.

- [ ] **Step 8: Typecheck, test, and confirm the built asset paths**

```bash
cd frontend && npm run build && npm test
grep -o '/zork/assets/[^"]*' dist/index.html | head
```

Expected: build and tests pass, and `dist/index.html` references `/zork/assets/...`.

- [ ] **Step 9: Confirm the NUL sentinel survived**

```bash
tr -dc '\000' < frontend/src/rsocket/terminalSession.ts | wc -c
```

Expected: **0**. That file was not edited by this task, but the check is cheap and the hazard is silent. Use `tr`, not `grep -c` — `grep -c` counts matching lines, not bytes.

- [ ] **Step 10: Commit**

```bash
git add frontend/src/rsocket/sessionUrl.ts frontend/src/rsocket/sessionUrl.test.ts \
        frontend/src/components/Terminal.tsx frontend/vite.config.ts
git commit -m "Serve the terminal bundle under the zork base path and derive the websocket URL from it"
```

---

## Task 5: Sign-out control

**Files:**
- Create: `frontend/src/components/SignOut.tsx`
- Modify: `frontend/src/components/Terminal.tsx`
- Modify: `frontend/src/index.css`

**Interfaces:**
- Consumes: `CookieServerCsrfTokenRepository.withHttpOnlyFalse()` from Task 3, which makes `XSRF-TOKEN` readable from JavaScript.
- Produces: a `<SignOut />` element rendered in the terminal shell.

**No unit test here, deliberately.** `vitest` runs with `environment: 'node'`; this component needs `document.cookie`, `fetch` and `window.location`, so testing it means adding `jsdom` and a DOM testing library for one button. The behaviour is verified end to end in Operator Task O4 Step 9 instead. If the sign-out logic grows past "read a cookie, POST, reload", revisit that trade.

- [ ] **Step 1: Write the component**

Create `frontend/src/components/SignOut.tsx`:

```tsx
/*----------------------
 | SignOut.tsx
 | Description: Ends the Google session with a CSRF-bearing POST and reloads into a fresh sign-in.
 | Author: suinevere
 | Dependencies: react
 | Globals: N/A
 ----------------------*/

/*----------------------
 | csrfToken
 | Description: Reads the CSRF token Spring writes to a JavaScript-readable cookie.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: N/A
 | Returns: the decoded token, or null when the cookie is absent
 ----------------------*/
function csrfToken(): string | null {
  const entry = document.cookie
    .split('; ')
    .find((candidate) => candidate.startsWith('XSRF-TOKEN='))
  return entry ? decodeURIComponent(entry.slice('XSRF-TOKEN='.length)) : null
}

/*----------------------
 | SignOut
 | Description: Renders the control that ends the session.
 | Author: suinevere
 | Dependencies: csrfToken
 | Globals: N/A
 | Params: N/A
 | Returns: React element for the sign-out button
 ----------------------*/
export default function SignOut() {
  const end = async (): Promise<void> => {
    const token = csrfToken()
    await fetch(`${import.meta.env.BASE_URL}logout`, {
      method: 'POST',
      headers: token ? { 'X-XSRF-TOKEN': token } : {},
    })
    window.location.reload()
  }

  return (
    <button type="button" className="sign-out" onClick={() => void end()}>
      Sign out
    </button>
  )
}
```

- [ ] **Step 2: Mount it**

In `frontend/src/components/Terminal.tsx`, add to the imports:

```ts
import SignOut from './SignOut'
```

and add `<SignOut />` as the last child of the `terminal-shell` div, after the Reconnect button block.

- [ ] **Step 3: Style it**

Append to `frontend/src/index.css`:

```css
.sign-out {
  position: absolute;
  top: 0.5rem;
  right: 0.5rem;
  background: transparent;
  border: 1px solid #3a3a3a;
  color: #8a8a8a;
  font: inherit;
  font-size: 0.75rem;
  padding: 0.15rem 0.5rem;
  cursor: pointer;
}
```

- [ ] **Step 4: Typecheck and build**

```bash
cd frontend && npm run build && npm test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/SignOut.tsx frontend/src/components/Terminal.tsx frontend/src/index.css
git commit -m "Add a sign-out control that ends the Google session"
```

---

## Task 6: Guard the WebSocket origin and move the vhost under /zork

**Files:**
- Modify: `docker/nginx/terminal.suin.uk.conf`

**Interfaces:**
- Consumes: `GET /zork/_authcheck` from Task 3 — 204 authenticated, 401 otherwise.
- Produces: an nginx vhost that authenticates the WebSocket upgrade, rejects cross-origin upgrades, maps the external `/zork/rsocket` onto Spring's internal `/rsocket`, and forwards `X-Forwarded-Host`.

**This task is what actually protects the WebSocket.** Task 2 measured that Spring Security does not cover the RSocket endpoint: unauthenticated, `/rsocket` returns 101 while `/zork/` returns 401. Task 3 alone therefore ships a login page in front of an open socket. Both must land before the app is exposed.

Two distinct checks live here and neither substitutes for the other. `auth_request` answers *is this visitor signed in*. The `Origin` map answers *is this page entitled to use their session* — browsers do not apply CORS to WebSockets, so a cookie-authenticated upgrade is cross-origin openable by any page.

- [ ] **Step 1: Add the origin map**

At the top of `docker/nginx/terminal.suin.uk.conf`, above the first `server` block:

```nginx
# Browsers do not apply CORS to WebSockets, so a cookie-authenticated upgrade is
# cross-origin openable unless the Origin is checked here.
map $http_origin $rsocket_origin_ok {
    default           0;
    "https://suin.uk" 1;
}
```

- [ ] **Step 2: Replace the /rsocket location**

In the `listen 443` server block, replace the `location /rsocket { ... }` block with:

```nginx
    # Spring Security does not filter the RSocket endpoint, so the upgrade is
    # authenticated here or nowhere.
    location = /_authcheck_internal {
        internal;
        proxy_pass http://127.0.0.1:8080/zork/_authcheck;
        proxy_pass_request_body off;
        proxy_set_header Content-Length "";
        proxy_set_header Host $host;
        proxy_set_header Cookie $http_cookie;
    }

    location = /zork/rsocket {
        if ($rsocket_origin_ok = 0) {
            return 403;
        }

        auth_request /_authcheck_internal;

        # Spring serves the socket at /rsocket; base-path does not reach it.
        proxy_pass http://127.0.0.1:8080/rsocket;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Host $http_x_forwarded_host;
        proxy_set_header X-Forwarded-Proto $scheme;

        # A session idling at a prompt sends nothing; the 60s default would drop it.
        proxy_read_timeout 3600s;
    }
```

Four details that will each break this quietly if changed:

- **`internal;`** keeps `/_authcheck_internal` unreachable from outside — without it the endpoint is directly fetchable.
- **`proxy_set_header Cookie $http_cookie;`** forwards the session to the subrequest. Omit it and every upgrade is denied, because Spring sees an anonymous request.
- **`proxy_pass http://127.0.0.1:8080/rsocket;`** carries a URI part, which is what replaces the matched location — that is the mapping from external `/zork/rsocket` to Spring's `/rsocket`. Dropping the `/rsocket` suffix would forward the original path and 404.
- **`location =`** is an exact match, matching how the browser connects, and it must stay declared ahead of the catch-all `location /`.

- [ ] **Step 2b: Confirm the auth_request module is present**

```bash
nginx -V 2>&1 | tr ' ' '\n' | grep auth_request
```

Expected: `--with-http_auth_request_module`. It is standard on Ubuntu's nginx. If it is absent, stop — the config will fail `nginx -t` with "unknown directive", and report it rather than working around it.

- [ ] **Step 3: Forward the Worker's host header on the catch-all**

In the same server block, add to `location / `:

```nginx
        proxy_set_header X-Forwarded-Host $http_x_forwarded_host;
```

- [ ] **Step 4: Verify the syntax**

```bash
docker run --rm -v "$PWD/docker/nginx:/etc/nginx/conf.d:ro" nginx:alpine nginx -t 2>&1 | tail -5
```

Expected: the `stream` file will be reported as invalid in this harness because `stream` cannot live in `conf.d` — that is expected and documented in the file itself. `terminal.suin.uk.conf` must not report a syntax error. If the harness is too noisy, run `sudo nginx -t` on the box during Operator Task O4 instead and record the output there.

- [ ] **Step 5: Commit**

```bash
git add docker/nginx/terminal.suin.uk.conf
git commit -m "Reject cross-origin websocket upgrades and serve the terminal vhost under the zork path"
```

---

## Task 7: The Cloudflare Worker

**Files:**
- Create: `ops/cloudflare/worker.js`
- Create: `ops/cloudflare/README.md`

**Interfaces:**
- Produces: a Worker script deployed by Operator Task O4 on route `suin.uk/zork*`.

- [ ] **Step 1: Write the Worker**

Create `ops/cloudflare/worker.js`:

```js
/*----------------------
 | worker.js
 | Description: Passes suin.uk/zork* through to the terminal origin unchanged, including websocket upgrades.
 | Author: suinevere
 | Dependencies: Cloudflare Workers runtime
 | Globals: ORIGIN_HOST, PUBLIC_HOST
 ----------------------*/

/*----------------------
 | ORIGIN_HOST
 | Description: The terminal vhost on the Oracle box, resolved DNS-only so this fetch reaches it directly.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: N/A
 | Returns: N/A
 ----------------------*/
const ORIGIN_HOST = 'terminal.suin.uk'

/*----------------------
 | PUBLIC_HOST
 | Description: The hostname the visitor sees, forwarded so the origin can build correct absolute URLs.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: N/A
 | Returns: N/A
 ----------------------*/
const PUBLIC_HOST = 'suin.uk'

export default {
  /*----------------------
   | fetch
   | Description: Rewrites the hostname and relays the response, preserving a websocket handshake if one occurred.
   | Author: suinevere
   | Dependencies: ORIGIN_HOST, PUBLIC_HOST
   | Globals: ORIGIN_HOST, PUBLIC_HOST
   | Params: request -- the inbound request on the suin.uk/zork* route
   | Returns: Promise of the origin's response, websocket included
   ----------------------*/
  async fetch(request) {
    const url = new URL(request.url)
    url.hostname = ORIGIN_HOST

    const upstream = new Request(url, request)
    upstream.headers.set('X-Forwarded-Host', PUBLIC_HOST)

    const response = await fetch(upstream)

    return new Response(response.body, {
      status: response.status,
      statusText: response.statusText,
      headers: response.headers,
      webSocket: response.webSocket,
    })
  },
}
```

The path is never rewritten — Spring owns the `/zork` prefix, so the Worker has no path logic to get wrong. **`webSocket: response.webSocket` is the line that is easy to omit**; without it the `101` collapses into an ordinary response and the terminal connects to nothing while every other page behaves normally.

- [ ] **Step 2: Document the console-side configuration**

Create `ops/cloudflare/README.md`:

```markdown
# Cloudflare configuration for suin.uk/zork

`suin.uk` is a GitHub Pages site behind Cloudflare. The Worker is what lets one path
be served by the Oracle box without moving the whole zone.

## DNS

| Record | Value | Proxy |
|---|---|---|
| `terminal.suin.uk` A | Oracle public IP | **DNS-only (grey)** |

Grey on purpose. Proxying adds a hop between Worker and origin for no benefit, and
concealing the origin IP is unachievable anyway: port 23 must be directly reachable
and Cloudflare's free plan does not proxy raw TCP.

## Worker

Deploy `worker.js`, bound to route `suin.uk/zork*`.

## Redirect Rules

| Match | Action |
|---|---|
| `suin.uk/z` | 301 → `https://suin.uk/zork` |
| `suin.uk/zaturn` | 301 → `https://suin.uk/zork` |

These are Redirect Rules rather than extra Worker routes: a `suin.uk/z*` route would
also capture `/zebra`.

## HTTPS

Enable **Always Use HTTPS** on the zone. The Worker then never receives a cleartext
request, and nothing is proxied in plaintext.

## Google Cloud Console

| Setting | Value |
|---|---|
| Authorized JavaScript origin | `https://suin.uk` |
| Authorized redirect URI | `https://suin.uk/zork/login/oauth2/code/google` |

Exactly one redirect URI, matching `spring.security.oauth2.client.registration.google.redirect-uri`.
A second URI on `terminal.suin.uk` may be added temporarily for testing before the Worker
exists — remove it afterwards, because that hostname has no `Origin` guard on its
catch-all path.
```

- [ ] **Step 3: Check the Worker parses**

```bash
node --check ops/cloudflare/worker.js && echo "syntax ok"
```

Expected: `syntax ok`.

- [ ] **Step 4: Commit**

```bash
git add ops/cloudflare/worker.js ops/cloudflare/README.md
git commit -m "Add the Cloudflare worker that serves the terminal at suin.uk zork without changing the address bar"
```

---

## Operator Task O4: Deploy the public route

**Runs on:** the Oracle box, the Cloudflare dashboard, the Google Cloud Console.
**Depends on:** Tasks 3–7 merged and pulled onto the box.

Ordering matters here. Sign-in cannot succeed until the redirect URI and the Worker both exist, because Task 3 pins the callback to `suin.uk`.

- [ ] **Step 1: Create the DNS record**

In Cloudflare, add `terminal.suin.uk` A → the Oracle public IP, **DNS-only (grey cloud)**. Confirm:

```bash
dig +short terminal.suin.uk
```

- [ ] **Step 2: Issue the certificate**

On the box:

```bash
sudo certbot certonly --webroot -w /var/www/html -d terminal.suin.uk
sudo certbot certificates | grep -A3 terminal.suin.uk
```

- [ ] **Step 2b: Confirm the box's nginx has the auth_request module**

O1 recorded nginx **1.18.0** on the box — older than the version Task 6 was validated
against, and only an Alpine image was checked locally.

```bash
nginx -V 2>&1 | tr ' ' '\n' | grep auth_request
```

Expected: `--with-http_auth_request_module`. **If it is absent, stop.** `nginx -t` will fail
with "unknown directive auth_request", and without that directive there is nothing
authenticating the WebSocket — do not deploy the vhost and report back instead of working
around it.

- [ ] **Step 3: Install the vhost and close the port-80 downgrade**

Symlink `terminal.suin.uk.conf` into `sites-enabled/`. In the existing port-80 vhost, change the catch-all redirect target from `http://suin.uk` to `https://suin.uk` — the cleartext downgrade recorded in `mem/suinevere-server-hardening.md`. Then:

```bash
sudo nginx -t && sudo systemctl reload nginx
```

- [ ] **Step 4: Add the Google credentials**

Create the OAuth client in the Google Cloud Console with the values from `ops/cloudflare/README.md`, then on the box:

```bash
cd /opt/suinevere-site-terminal/docker
umask 077
printf 'GOOGLE_CLIENT_ID=%s\nGOOGLE_CLIENT_SECRET=%s\n' "<id>" "<secret>" >> .env
chmod 600 .env
```

Append — do not overwrite; `AUTH_SECRET` is already in that file.

- [ ] **Step 5: Rebuild and restart the bridge**

```bash
docker compose build terminal
docker compose up -d terminal
docker compose logs --tail=30 terminal
```

This is a brief web-terminal outage. Port 23 is unaffected.

- [ ] **Step 6: Verify the origin before the Worker exists**

```bash
curl -sS -i https://terminal.suin.uk/zork/ | head -3
```

Expected: `302`, with `Location` pointing at Google. A `200` here means `SecurityConfig` is not active — stop and investigate rather than deploying the Worker on top of an open app.

- [ ] **Step 7: Deploy the Worker and the redirect rules**

Deploy `ops/cloudflare/worker.js` on route `suin.uk/zork*`. Add the two Redirect Rules. Enable **Always Use HTTPS**.

- [ ] **Step 8: Verify the public path**

```bash
curl -sS -i https://suin.uk/zork/ | head -3
curl -sS -o /dev/null -w '%{http_code}\n' https://suin.uk/z
curl -sS -o /dev/null -w '%{http_code}\n' https://suin.uk/zaturn
```

Expected: a `302` toward Google for the first, `301` for the other two.

- [ ] **Step 9: Sign in for real**

In a browser, open `https://suin.uk/zork`, complete Google sign-in, confirm the terminal connects and the game responds. Confirm the URL bar still reads `suin.uk/zork`.

- [ ] **Step 10: Verify both WebSocket guards, unauthenticated**

This is the step that proves the Branch B hole is actually closed. Run all three.

```bash
UP='-H "Connection: Upgrade" -H "Upgrade: websocket" -H "Sec-WebSocket-Version: 13"
    -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=="'

# a) no session, correct origin  -> auth_request must deny
curl -sS -o /dev/null -w 'no-session: %{http_code}\n' \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  -H "Origin: https://suin.uk" \
  https://suin.uk/zork/rsocket

# b) no session, hostile origin  -> origin map must deny
curl -sS -o /dev/null -w 'bad-origin: %{http_code}\n' \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  -H "Origin: https://evil.example" \
  https://suin.uk/zork/rsocket

# c) the internal endpoint must not be reachable from outside
curl -sS -o /dev/null -w 'internal: %{http_code}\n' https://suin.uk/_authcheck_internal
```

Expected: **(a) 401, (b) 403, (c) 404.**

**A `101` on (a) means the hole is still open — stop and do not announce the deployment.** A `500` on (a) means `/zork/_authcheck` answered with a redirect instead of a status, so the `@Order(1)` filter chain is not matching; check it before touching nginx.

- [ ] **Step 10b: Confirm the Worker forwards the browser's Origin unmodified**

The `Origin` guard in nginx is only as good as what actually arrives there. The Worker copies
headers via `new Request(url, request)`, but that is worth confirming rather than assuming —
if Cloudflare rewrites or drops `Origin`, every legitimate upgrade is refused with 403.

Open `https://suin.uk/zork` in a browser, sign in, and confirm the terminal connects. Then on
the box:

```bash
sudo tail -50 /var/log/nginx/access.log | grep rsocket
```

Expected: a `101` for your own session. A `403` there means the `Origin` did not survive the
Worker; a `401` means `auth_request` denied it.

**Note for scripted checks:** the `Origin` guard refuses any client that sends no `Origin`
header at all, which includes plain `curl`, uptime probes and health checks. That is intended
for this endpoint — but it means `/zork/rsocket` cannot be used as a liveness check.

- [ ] **Step 11: Remove any temporary redirect URI**

If a `terminal.suin.uk` redirect URI was added to the Google client for testing, delete it now.

- [ ] **Step 12: Record the outcome**

Update `mem/2026-08-06-oracle-deployment-decisions.md` with a short "deployed" note, add a pointer line to `mem/MEMORY.md` if the state has moved on, then:

```bash
git add mem/
git commit -m "Record that the terminal is live at suin.uk zork behind Google sign-in"
```

---

## Deferred, deliberately

- **Merging `rsocket-terminal` into master.** Still open from `mem/2026-08-05-handoff-server-hardening.md`; handle it with `superpowers:finishing-a-development-branch` once this deployment is stable.
- **Per-identity session caps.** `TERMINAL_UPSTREAM_MAXSESSIONS` stays global at 32. OAuth makes per-user limits possible; nothing today needs them.
- **The unreviewed fix wave** from the previous session. Unrelated to this plan, but still unreviewed.
