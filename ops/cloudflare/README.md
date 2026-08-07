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

In the Cloudflare dashboard: **Workers & Pages → Create → Workers → deploy `worker.js`**,
then open the Worker's **Settings → Triggers → Routes → Add route** and bind it to
`suin.uk/zork*` against the `suin.uk` zone.

## Redirect Rules

Dashboard path: **suin.uk zone → Rules → Redirect Rules → Create rule**. For each row
below, set the rule to match **"URI Path"** with operator **"equals"** against the match
value shown (not the "URI Full" hostname field), and the target to a **Static URL**, 301,
with **"Preserve query string"** off.

| Match | Action |
|---|---|
| `/z` | 301 → `https://suin.uk/zork/` |
| `/zaturn` | 301 → `https://suin.uk/zork/` |

These are Redirect Rules rather than extra Worker routes: a `suin.uk/z*` route would
also capture `/zebra`. The targets carry a trailing slash because `spring.webflux.base-path`
plus `oauth2Login` mishandles the slashless form (spring-projects/spring-security#8967);
nginx also normalises this at the origin, so the Worker route itself needs no change.

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
