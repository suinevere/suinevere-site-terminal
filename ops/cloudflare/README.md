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

Use one rule with this expression, and note the `ssl` scoping — without it the rule fires
on plain HTTP too and force-upgrades paths the zone deliberately serves over HTTP:

```
(ssl) and (http.request.uri.path in {"/z" "/z/" "/zaturn" "/zaturn/"})
```

This rule must sit **below** the zone's existing `(not ssl)` rule in the rule order, so the
HTTP-only paths are claimed first.

These are Redirect Rules rather than extra Worker routes: a `suin.uk/z*` route would
also capture `/zebra`. The targets carry a trailing slash because `spring.webflux.base-path`
plus `oauth2Login` mishandles the slashless form (spring-projects/spring-security#8967);
nginx also normalises this at the origin, so the Worker route itself needs no change.

## HTTPS

**Do not enable zone-wide *Always Use HTTPS*.** This zone serves HTTP-only content at
`/0`, and that setting would upgrade it and break it.

Scheme handling is per-path instead. Every Redirect Rule that targets an HTTPS
destination must be scoped with `ssl`, so plain-HTTP requests fall through to the
zone's existing `(not ssl)` rules rather than being force-upgraded.

## Google Cloud Console

| Setting | Value |
|---|---|
| Authorized JavaScript origin | `https://suin.uk` |
| Authorized redirect URI | `https://suin.uk/zork/login/oauth2/code/google` |

Exactly one redirect URI, matching `spring.security.oauth2.client.registration.google.redirect-uri`.
A second URI on `terminal.suin.uk` may be added temporarily for testing before the Worker
exists — remove it afterwards, because that hostname has no `Origin` guard on its
catch-all path.
