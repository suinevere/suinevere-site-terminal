# Oracle Cloud deployment: verification, port-23 gate, OAuth, and suin.uk/zork

Date: 2026-08-06
Status: approved

Takes the `rsocket-terminal` branch from "works on the box" to "reachable by the public at
`https://suin.uk/zork` behind Google sign-in, with the Saturn path gated by a shared secret".

Decisions and their rationale are in `mem/2026-08-06-oracle-deployment-decisions.md`. Prior
state is in `mem/suinevere-server-hardening.md` and
`mem/2026-08-05-handoff-server-hardening.md`. This document does not restate them.

## Target architecture

```
                        ┌─ Saturn / DreamPi ──────────────────────────┐
                        │  dial 199408                                │
                        │  AUTH + <secret>  (doorman, published)      │
                        └──────────────┬──────────────────────────────┘
                                       │ TCP :23
  Browser                              ▼
     │ https://suin.uk/zork    ┌─── Oracle box ─────────────────────┐
     ▼                         │  nginx stream :23                  │
  Cloudflare Worker            │    limit_conn mud 3                │
   route suin.uk/zork*         │      ↓                             │
     │  pass-through, verbatim │  authproxy 127.0.0.1:2322          │
     ▼                         │      ↓ compose net                 │
  terminal.suin.uk (DNS-only)  │  multizork:2323 ◄──────────┐       │
     │ :443                    │                            │       │
     ▼                         │  nginx vhost :443          │       │
  nginx → 127.0.0.1:8080  ─────┼─► Spring (base-path /zork) ┘       │
                               │     Google OAuth required          │
                               └────────────────────────────────────┘
```

Two independent gates. The Saturn passes the **AUTH doorman** on port 23; the browser passes
**Google OAuth** on the web vhost. The web path never touches port 23 — the bridge reaches
`multizork` over the compose network — so Phase 1 cannot break Phase 2, or the reverse.

## Component boundaries

| Component | Purpose | Interface | Depends on |
|---|---|---|---|
| `authproxy` container | Validate the Netlink `AUTH` preamble, then relay bytes | TCP `127.0.0.1:2322` | `multizork:2323`, `AUTH_SECRET` |
| nginx `stream` :23 | Per-IP connection limit, single public entry for the Saturn | TCP `:23` | `authproxy` |
| nginx vhost `terminal.suin.uk` | TLS termination, `Origin` guard on the WebSocket | HTTPS `:443` | Spring on `127.0.0.1:8080`, certbot |
| `SecurityConfig` | Require a Google identity; refuse every other credential type | `SecurityWebFilterChain` bean | `spring-boot-starter-oauth2-client` |
| Cloudflare Worker | Keep `suin.uk` in the URL bar, pass HTTP and WebSocket through unchanged | route `suin.uk/zork*` | `terminal.suin.uk` DNS + cert |

Each is independently testable: `authproxy` with `nc` on loopback, `SecurityConfig` with
`WebTestClient`, the Worker with `curl` against both hostnames.

---

## Phase 0 — verification (read-only, no changes)

Run in order; each step's failure explains the next step's symptom.

**0.1 DNS is current.** From the laptop `dig +short suinevere.duckdns.org`; on the box
`curl -s ifconfig.me`. A mismatch means the DuckDNS updater is dead. Check
`systemctl list-timers | grep -i duck` and `crontab -l`, then read the updater's last log
line — a scheduled job existing is not evidence it succeeded.

**0.2 Oracle ingress.** Invisible from inside the box, so check both layers. From the laptop,
probe 22/23/80/443 plus two ports expected closed. On the box, `sudo iptables -S INPUT`.
Oracle's stock Ubuntu image ships `/etc/iptables/rules.v4` rejecting everything but 22 — an
open Security List with a closed host table is indistinguishable from a closed Security List
when probed from outside.

**0.3 Listener map.** `ss -ltnp` should show exactly `0.0.0.0:22,23,80,443` and
`127.0.0.1:2323,8080`. Anything else on `0.0.0.0` is the finding. `sudo ufw status verbose`
is advisory only for published container ports — Docker's iptables rules are evaluated first.

**0.4 nginx loaded the stream block.** `sudo nginx -T | grep -A6 'listen 23'`. The
characteristic failure is a correct file in `streams-enabled/` that `nginx.conf` never
includes at top level: nginx starts clean and nothing serves 23.

**0.5 Stack health.** `docker compose ps` and `docker compose logs --tail=50` in
the stack checkout's `docker/` directory.

**0.6 End to end.** `printf 'q\r\n' | timeout 8 nc suinevere.duckdns.org 23` → expect the
banner. "Connect OK then zero bytes" is ambiguous between a gate rejection and nothing
listening upstream; distinguish with `ss -ltnp | grep 2323` before concluding.

**0.7 DreamPi.** SSH to the home box, confirm the tunnel service is running and that 199408
still points at `suinevere.duckdns.org:23`.

**0.8 TLS inventory.** `sudo certbot certificates`. `terminal.suin.uk` is expected to be
absent; Phase 3 issues it. Confirm whether the port-80 vhost redirects to `http://suin.uk`
(a cleartext downgrade) or `https://`.

Phase 0 produces a written findings list. Anything it turns up that is not covered by Phases
1–3 is triaged separately rather than absorbed silently.

---

## Phase 1 — port-23 gate, staged

**1.1** Add `docker/.env` to `.gitignore`. This precedes secret generation; the ordering is
the point.

**1.2** `openssl rand -hex 16` on the box into `docker/.env` (mode 600) as `AUTH_SECRET`.
Thirty-two characters, well inside the protocol's one-byte length field.

**1.3** Add an `authproxy` service to `docker/docker-compose.yml`, matching the hardening the
other two services already carry (`read_only`, `no-new-privileges`, `cap_drop: ALL`, memory
limit, log rotation):

- published `127.0.0.1:2322:2322` — reachable by host nginx, not by the internet
- `UPSTREAM_HOST: multizork`, `UPSTREAM_PORT: 2323`
- `AUTH_SECRET` from `.env`, never baked into the image

`ops/authproxy/README.md` documents `UPSTREAM_HOST` defaulting to `127.0.0.1`. **That default
is wrong inside a container** — it is the proxy's own loopback, not the game. Correct the
README as part of this phase.

**1.4** Prove it on loopback before moving any traffic:

```bash
printf 'hello\r\n'         | timeout 8 nc 127.0.0.1 2322   # expect immediate close
printf 'AUTH\x20<secret>'  | timeout 8 nc 127.0.0.1 2322   # expect \x01 then the banner
```

`\x20` is 32, the secret's length. A wrong length byte fails identically to a wrong secret,
so getting a rejection here is not yet evidence the secret is wrong.

**1.5** Real Saturn against the proxy, over an SSH forward rather than a temporary firewall
hole. From the DreamPi:

```bash
ssh -N -L 2322:127.0.0.1:2322 <oracle>
```

Point 199408 at `host = 127.0.0.1, port = 2322` with `shared_secret` set and
`handler = transparent` removed, restart the tunnel, and dial from the Saturn. The two
Netlink handlers have different relay implementations, so a real dial is the only proof that
matters — a passing `nc` test does not imply a working modem session.

**1.6** Cut over: `proxy_pass 127.0.0.1:2322` in `docker/nginx/stream-multizork.conf`,
`sudo nginx -t`, `sudo systemctl reload nginx`. A reload, not a restart — nginx keeps
holding port 23 throughout, so there is no outage. Rollback is uncommenting one line and
reloading again.

**1.7** Point the DreamPi back at `suinevere.duckdns.org:23`, restart, re-dial to confirm the
full public path.

**1.8** Open the upstream PR against the Netlink tunnel repo carrying the 199408 block
**including the secret**. Publishing it is the accepted trade: the gate filters background
scanning, not readers of the repo. The web terminal (compose network) and the SSH admin path
(`nc 127.0.0.1 2323`) both bypass it by design.

### Failure modes

| Symptom | Cause | Check |
|---|---|---|
| `nc` rejected with the right secret | length byte ≠ secret length | recount; `\x20` for 32 chars |
| `nc` accepted, Saturn dial fails | handler relay difference | `docker compose logs authproxy` during the dial |
| Everything drops after 1.6 | nginx bound 23 but `authproxy` is down | `ss -ltnp \| grep 2322` |
| Web terminal breaks during Phase 1 | it should not — it uses the compose network | if it does, `TERMINAL_UPSTREAM_HOST` is wrong |

---

## Phase 2 — Google OAuth on the bridge

Adds `spring-boot-starter-oauth2-client`. New Kotlin goes in
`src/main/kotlin/.../terminal/security/SecurityConfig.kt` — a concern subfolder rather than a
sixth flat file, and carrying the standard header block.

### Configuration

```yaml
spring:
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
server:
  forward-headers-strategy: framework
```

**The absolute `redirect-uri` is load-bearing.** Behind the Worker, Spring sees
`Host: terminal.suin.uk` and would otherwise construct a callback Google rejects with
`redirect_uri_mismatch` — a failure that presents as a Google Console misconfiguration and is
not one. Credentials come from the environment via `docker/.env`, alongside `AUTH_SECRET`.

### Filter chain

- every exchange authenticated
- `oauth2Login` enabled; with a single registration Spring skips the provider picker
- `formLogin` and `httpBasic` **explicitly disabled** — this pair *is* the "deny any
  username/password login" requirement, and it gets a dedicated test so a later edit cannot
  quietly restore it
- CSRF left at Spring's default (on). Logout is a small `fetch` POST reading the
  `XSRF-TOKEN` cookie — roughly five lines of frontend, preferred over weakening CSRF to
  permit a GET logout
- session is the default in-memory reactive `WebSession`; a restart signs everyone out,
  which is acceptable for a game terminal

### WebSocket origin guard

Browsers do not apply CORS to WebSockets, so a cookie-authenticated `/zork/rsocket` is
cross-origin openable: any page could connect carrying the visitor's cookie and play as them.
This sits alongside the `auth_request` above in the same nginx block — `auth_request` answers
*whether the visitor is signed in*, the `Origin` check answers *whether this page is entitled
to use their session*. Both are needed; neither substitutes for the other. In
`terminal.suin.uk.conf`:

```nginx
map $http_origin $rsocket_origin_ok {
    default          0;
    "https://suin.uk" 1;
}
```

with a guard on `location /zork/rsocket` returning 403 when `$rsocket_origin_ok` is 0. This
is the one genuinely new attack surface OAuth introduces.

### Authorization model

**Revised 2026-08-06 after the Task 2 spike measured the original model to be unsound.**

The first version of this section said the RSocket socket inherits the HTTP handshake's
authorization. It does not. Spring Boot attaches RSocket-over-WebSocket to the Netty server
**outside the `HttpHandler`**, which is the layer where both `spring.webflux.base-path` and
the Spring Security `WebFilter` chain are applied. Measured unauthenticated, with the
security starter on the classpath: `/rsocket` returns **101 Switching Protocols** while
`/zork/` returns 401. Full measurement in
`docs/superpowers/findings/2026-08-06-rsocket-binding-spike.md`; the durable fact is in
`mem/rsocket-terminal-project.md`.

**The boundary therefore lives in nginx, not in Spring Security.**

```
Browser ──wss://suin.uk/zork/rsocket──► Worker ──► nginx
                                                    │
                                    auth_request /_authcheck
                                    (session cookie forwarded)
                                                    ▼
                                       Spring /zork/_authcheck
                                         authenticated -> 204
                                         otherwise     -> 401
                                                    │
                                    204 ─► proxy_pass 127.0.0.1:8080/rsocket
                                    401 ─► upgrade denied
```

Two consequences that are easy to get wrong:

- **The endpoint must return 401, not 302.** Under `oauth2Login` an unauthenticated request
  redirects to Google, and nginx's `auth_request` treats a 302 as an unexpected response and
  answers 500 — which fails open-looking and closed-behaving, the worst combination to
  debug. `/_authcheck` gets its own higher-precedence `SecurityWebFilterChain` with
  `HttpStatusServerEntryPoint(UNAUTHORIZED)`.
- **`/rsocket` does not move under `/zork`.** Base-path does not reach it. nginx maps the
  external `/zork/rsocket` onto the internal `/rsocket`; `spring.rsocket.server.mapping-path`
  stays `/rsocket`. Setting it to `/zork/rsocket` as well would double the prefix.

This is sufficient rather than merely convenient: the terminal container publishes
`127.0.0.1:8080` only, so nginx is the sole path from the internet to that port. Reaching
`/rsocket` around nginx requires already being on the box, where `nc 127.0.0.1 2323` reaches
the game directly anyway.

Deliberately **not** done: `spring-security-rsocket` with a token in the SETUP frame. It
would enforce authorization inside the application and survive a proxy misconfiguration, at
the cost of token minting, expiry and reconnect handling on both sides. Recorded so the
absence reads as a decision, not an oversight.

### Frontend changes

- `vite.config.ts` → `base: '/zork/'`. Without it, assets request `/assets/*` and 404 through
  the Worker.
- The RSocket URL is derived from `window.location`, including the base path, rather than
  hardcoded — so dev and production cannot disagree.
- On WebSocket handshake failure, `location.reload()` rather than retry. An expired session
  makes the upgrade a 302, which the RSocket client surfaces opaquely; the existing Reconnect
  button would otherwise retry forever against a dead session.

### Tests

The ledger records three tests that once passed with their feature deleted, so **each test
below is verified to fail when its configuration is removed** before it is considered done.

- unauthenticated `GET /zork/` → 302 toward `accounts.google.com`
- with `mockOAuth2Login()` → 200
- `POST /zork/login` does not behave as a form-login endpoint
- vitest: the RSocket URL builder carries the base path and selects `ws`/`wss` from the page
  protocol

---

## Phase 3 — Cloudflare and public routing

**3.1 DNS.** `terminal.suin.uk` A record → Oracle public IP, **DNS-only (grey cloud)**.
Proxying it adds a hop between Worker and origin for no benefit, and concealing the origin IP
is not achievable regardless: port 23 must be directly reachable and Cloudflare's free plan
does not proxy raw TCP.

**3.2 Certificate and vhost.** certbot for `terminal.suin.uk`;
`docker/nginx/terminal.suin.uk.conf` already references that path. The vhost keeps its
catch-all `location /` proxying to `127.0.0.1:8080` — Spring answers `/zork/*` and 404s
anything else, so no path rewriting is needed. The existing `location /rsocket` block is
**renamed to `/zork/rsocket`** and gains the `Origin` guard from Phase 2; it must stay
declared ahead of the catch-all, which is why it exists as a separate block at all.

**3.3 Worker**, bound to route `suin.uk/zork*`:

```js
const url = new URL(request.url);
url.hostname = "terminal.suin.uk";
const upstream = new Request(url, request);
upstream.headers.set("X-Forwarded-Host", "suin.uk");
const resp = await fetch(upstream);
return new Response(resp.body, {
  status: resp.status, statusText: resp.statusText,
  headers: resp.headers, webSocket: resp.webSocket,
});
```

The path passes through verbatim — Spring owns the `/zork` prefix, the Worker has no path
logic. **`webSocket: resp.webSocket` is the line that is easy to omit**; without it the 101
collapses into an ordinary response and the terminal connects to nothing while every other
page works normally.

**3.4 Aliases.** `/z` and `/zaturn` are Cloudflare **Redirect Rules** (301 → `/zork`), not
Worker routes. A `suin.uk/z*` route would also capture `/zebra`.

**3.5 HTTPS scoping.** Zone-wide *Always Use HTTPS* is NOT used — the zone serves
HTTP-only content at `/0`. Superseded text follows: Cloudflare *Always Use HTTPS* upgrades :80 → :443 at the edge, so the
Worker never receives a cleartext request and nothing is proxied in plaintext. Separately,
change the box's port-80 vhost to `301 https://suin.uk`, closing the downgrade recorded in
`mem/suinevere-server-hardening.md`.

**3.6 Google Cloud Console.** Authorized JavaScript origin `https://suin.uk`; authorized
redirect URI `https://suin.uk/zork/login/oauth2/code/google` — exactly one, matching the
pinned configuration.

### Ordering

Phase 2 is testable at `https://terminal.suin.uk/zork/` before the Worker exists, but sign-in
will bounce to `suin.uk` because the redirect URI is pinned. Either create the DNS record and
Worker before the first login attempt, or temporarily authorize
`https://terminal.suin.uk/zork/login/oauth2/code/google` as a second URI and remove it
afterwards. Leaving it authorized permanently would allow a session on a hostname with no
`Origin` guard.

---

## Cross-cutting

**Secrets.** `AUTH_SECRET`, `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` live only in
`docker/.env` (mode 600, gitignored). None is baked into an image. `AUTH_SECRET` is
additionally published upstream by design; the Google credentials are not, and must never
reach the Netlink repo or this one.

**Outage windows.** Phase 1's cutover is an nginx reload — zero. Phase 2 rebuilds the
terminal image, briefly interrupting web sessions; port 23 is unaffected. Phase 3 adds a
certificate and a vhost and touches nothing already serving.

**Rollback.** Phase 1: uncomment `proxy_pass 127.0.0.1:2323`, reload. Phase 2: redeploy the
previous image tag. Phase 3: delete the Worker route; `suin.uk/zork` 404s and
`terminal.suin.uk/zork/` keeps working.

**Out of scope.** Merging `rsocket-terminal` to master — still open from the previous handoff,
and handled separately with `superpowers:finishing-a-development-branch`. Per-user session
caps: `TERMINAL_UPSTREAM_MAXSESSIONS` stays global at 32; OAuth makes per-identity limits
possible but nothing today needs them.
