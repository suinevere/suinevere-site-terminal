# Deploying the terminal

The bridge ships as one image: the Vite bundle is baked into the boot jar by
`processResources`, so a single container serves both the page and `/rsocket`.

```bash
docker compose build
docker compose up -d
docker compose logs -f terminal
```

The build is self-contained — `npm ci`, the frontend tests, `vite build` and `bootJar` all
run inside the image. The host needs no JDK and no Node, and `.dockerignore` keeps any
locally built `frontend/dist` or `build/` out of the context so a stale bundle cannot ship.

## Why 8080 is published to loopback

Docker writes its iptables rules ahead of ufw's, so a container published on `0.0.0.0`
stays reachable from the internet regardless of what ufw is configured to allow. Publishing
to `127.0.0.1` puts nginx back in charge of who reaches the bridge, the same shape already
used for `multizorkd`.

## nginx

Both `suinevere.duckdns.org` vhosts end in a catch-all `return 301` to suin.uk, which will
swallow the WebSocket upgrade before it ever reaches `/rsocket`. Give the terminal its own
server block rather than trying to carve a location out of a vhost that redirects:

```nginx
server {
    listen 443 ssl;
    server_name terminal.suin.uk;

    location /rsocket {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
    }

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
    }
}
```

`proxy_read_timeout` matters: a session that sits at a MultiZork prompt sends nothing, and
the default 60s would drop it mid-game.

This goes in `sites-enabled/`, unlike the port 23 `stream` block — `stream` is a sibling of
`http`, not a child, and has to be included from the top level of `nginx.conf`.

## Which upstream

`docker-compose.yml` defaults to `suinevere.duckdns.org:23`, which is what the app was
verified against. On the Oracle box that path leaves and re-enters through the public
address, so every browser session arrives at the nginx `stream` block from one source IP and
`limit_conn mud 3` refuses the fourth.

The alternative is host networking with `TERMINAL_UPSTREAM_HOST=127.0.0.1` and port `2323`,
which reaches the `multizorkd` container directly and skips both the hairpin and the
connection limit. The commented block at the bottom of `docker-compose.yml` has the exact
settings, including the `SERVER_ADDRESS=127.0.0.1` that host networking makes mandatory.

Either choice bypasses the AUTH gate — the loopback one by construction, the public one
because `UpstreamTcpClient` does not speak the preamble. Enabling `ops/authproxy` in front
of port 23 would break the public path and leave the loopback path working.

## Verifying

"It builds" and "it works in a browser" are separate claims here; the frontend has already
shipped a bundle that built cleanly and threw `Buffer is not defined` on load. Check both:

```bash
docker compose ps                              # healthy, not just running
curl -sI http://127.0.0.1:8080/ | head -1      # 200, the bundle is being served
```

Then open the page and confirm a session reaches a MultiZork prompt. If the socket connects
and nothing arrives, read the diagnosing notes in `mem/suinevere-server-hardening.md` before
concluding anything — "connect OK, 0 bytes, close" has three different causes.
