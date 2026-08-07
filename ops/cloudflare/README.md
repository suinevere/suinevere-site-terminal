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
