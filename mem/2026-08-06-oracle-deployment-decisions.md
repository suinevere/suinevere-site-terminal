---
name: 2026-08-06-oracle-deployment-decisions
description: Decisions taken 2026-08-06 for the Oracle Cloud deployment — access model, routing, base path — closing two of the three open questions from the previous handoff.
metadata:
  type: project
---

Design session for the production deployment: verify the box, gate port 23, put Google
OAuth on the browser terminal, and reach it at `suin.uk/zork`. The spec lives at
`docs/superpowers/specs/2026-08-06-oracle-deployment-design.md`; this records only the
decisions and the corrections, not the plan.

## Decisions

**MultiZork is publicly playable; the AUTH gate is a doorman.** One shared secret, published
in an upstream PR to the Netlink tunnel repo so any DreamPi can dial 199408. It filters
background internet scanning before the v0.0.9 C parser and stops nobody who reads the repo.
This closes decision 1 of [[2026-08-05-handoff-server-hardening]] — the two-secret variant
(public plus a private one for us) was considered and rejected as unearned complexity, since
`ops/authproxy` accepts exactly one secret today.

**The gate goes up staged, over an SSH forward, not a temporary firewall hole.** The Saturn
is tested against `authproxy` through `ssh -N -L 2322:127.0.0.1:2322` from the DreamPi before
the nginx `proxy_pass` is moved. The two Netlink handlers have different relay
implementations, so a real dial is the only proof that matters.

**The Spring bridge runs on the Oracle box and reaches `multizork` over the compose network.**
This closes decision 2 of the handoff: no `AUTH` preamble is ever needed in
`UpstreamTcpClient`, and the Kotlin change that was offered should not be written.

**Web access is Google OAuth, any Google account.** A speed bump that removes anonymous
sessions and puts an identity in the logs, not an access control. No allowlist.

**`suin.uk/zork` stays in the URL bar via a Cloudflare Worker,** not a redirect. The Worker is
a verbatim pass-through to `terminal.suin.uk`; `/z` and `/zaturn` are separate Redirect Rules
to `/zork`, because a `suin.uk/z*` Worker route would also swallow `/zebra`.

**Spring owns the prefix: `spring.webflux.base-path: /zork`.** The Worker has no path logic.
One source of truth means assets, `/rsocket` and the OAuth callback cannot disagree.

## Corrections to earlier memories

**`multizorkd` hardening flags are applied.** `docker/multizork/Dockerfile` carries
`-D_FORTIFY_SOURCE=2`, `-Wl,-z,relro,-z,now`, `-fstack-clash-protection`, arch-conditional
CFI and `-Werror=format-security`. [[suinevere-server-hardening]] said "not yet applied";
that line is now fixed.

**Dial code 199408 is `handler = transparent` with no secret today.** There is no
username/password on the Saturn path to revoke — the work is to *introduce* one. Anyone's
DreamPi currently reaches the game unauthenticated.

## Traps found while designing

- **`.gitignore` has no rule for `.env`.** A secret is about to exist in `docker/.env`. The
  ignore rule goes in before `openssl rand` runs, not after.
- **`ops/authproxy`'s documented `UPSTREAM_HOST=127.0.0.1` default is wrong in a container** —
  that is the proxy's own loopback. It must be `multizork`.
- **Behind the Worker, Spring sees `Host: terminal.suin.uk`** and would build an OAuth
  callback Google rejects. The `redirect-uri` is pinned absolute rather than derived.
- **Cookie-authenticated WebSockets are not covered by CORS.** `/zork/rsocket` needs an
  `Origin` check in nginx; it is the one new attack surface OAuth introduces.

Relates to [[rsocket-terminal-project]], [[suinevere-server-hardening]],
[[2026-08-05-handoff-server-hardening]].
