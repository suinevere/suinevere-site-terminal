# Deploying the terminal

Two services in one stack. `multizork` builds `multizorkd` from the zaturn repo and listens
on loopback; `terminal` bakes the Vite bundle into the boot jar and serves both the page and
`/rsocket`, reaching the game over the internal compose network.

```bash
docker compose build
docker compose up -d
docker compose logs -f
```

The terminal build is self-contained — `npm ci`, the frontend tests, `vite build` and
`bootJar` all run inside the image, so the host needs no JDK and no Node. `.dockerignore`
keeps any locally built `frontend/dist` or `build/` out of the context, so a stale bundle
cannot ship. The multizork build clones its source, so its context holds only the Dockerfile.

## Why nothing is published beyond loopback

Docker writes its iptables rules ahead of ufw's, so a container published on `0.0.0.0` stays
reachable from the internet regardless of what ufw is configured to allow. Both services
publish to `127.0.0.1` and nginx decides who reaches them.

That is also why `multizork` no longer publishes 23 the way the zaturn compose file does —
nginx binds 23 now, and a Docker publish would take it back and put the C parser directly on
the internet.

## Hardening flags on multizorkd

`ops/multizork/Dockerfile` compiles with `-D_FORTIFY_SOURCE=2`, `-fstack-protector-strong`,
`-fstack-clash-protection`, `-Wl,-z,relro,-z,now` and architecture-appropriate control-flow
protection — `-fcf-protection=full` on x86_64, `-mbranch-protection=standard` on aarch64,
picked at build time because Oracle's free tier is ARM as often as x86.

`-Werror=format-security` is deliberate. If the build fails there it has found a real
format-string bug in a hand-written parser that faces the internet; read it rather than
removing the flag. `-Wall -Wextra` produces a wall of output on a file never compiled with
them, which is expected and non-fatal — the current crop is all `-Wsign-compare`.

Measured on the built binary, not assumed: PIE, full RELRO with `BIND_NOW`, non-executable
stack, `__stack_chk_fail`, and fortified `printf`/`snprintf`/`vsnprintf` are all present.

**Control-flow protection is instrumented but not enforced.** `-fcf-protection=full` does
emit the `endbr64` landing pads, but the gcc in bookworm never writes the `IBT`/`SHSTK`
entries into `.note.gnu.property`, and without that marking the loader will not turn IBT on.
The flag is kept because it costs nothing and starts working if the base image ever moves to
a distribution that marks the property; it is not doing anything today. Re-check with:

```bash
docker build --target build -t mz-check ops/multizork
docker run --rm --entrypoint sh mz-check -c 'readelf -nW /multizorkd | grep -i properties'
```

Two tightenings left off to keep the change reviewable: `-D_FORTIFY_SOURCE=3`, which the
gcc in bookworm supports and which is strictly stronger, and pinning `REPO_REF` to a commit
sha instead of `main` so the image is reproducible.

## nginx

### Port 23

`stream` is a sibling of `http`, not a child, so this cannot live in `sites-enabled/` or
`conf.d/` — both are included from inside `http`. It goes at the top level of `nginx.conf`,
and the module is a separate package on Ubuntu, `libnginx-mod-stream`.

```nginx
stream {
    limit_conn_zone $binary_remote_addr zone=mud:10m;

    server {
        listen 23;
        limit_conn mud 3;

        # The stream default is 10m; a Saturn sitting at a prompt sends nothing
        # and would be dropped mid-game.
        proxy_timeout 1h;

        proxy_pass 127.0.0.1:2323;
        # proxy_pass 127.0.0.1:2322;   # authproxy, once AUTH_SECRET exists
    }
}
```

### The terminal

Both `suinevere.duckdns.org` vhosts end in a catch-all `location /` that will swallow the
WebSocket upgrade before it ever reaches `/rsocket`. Give the terminal its own server block
rather than carving a location out of a vhost that redirects:

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

`proxy_read_timeout` matters for the same reason `proxy_timeout` does above: a session idling
at a prompt sends nothing, and the 60s default would drop it.

The copy of this vhost checked into `zaturn/docker/nginx/` proxies to GitHub Pages and does
not match what is live on the box. Treat it as stale; do not apply it wholesale.

## Consequences of the internal network path

The terminal reaches multizorkd directly, which means browser sessions skip `limit_conn mud 3`
entirely — `terminal.upstream.max-sessions` is the only cap on them. It also means they skip
the AUTH gate by construction, so enabling `ops/authproxy` in front of port 23 would gate the
Saturn path and leave the web path untouched.

Both are overridable without a rebuild: set `TERMINAL_UPSTREAM_HOST` and
`TERMINAL_UPSTREAM_PORT` to send the bridge back out through nginx instead.

## Cutover from the zaturn compose file

The existing `multizork` container holds both the name and port 23, so it goes first. The
syntax check binds nothing and is safe to run before anything is torn down; the outage window
should be seconds.

```bash
sudo nginx -t                                     # does not bind
cd /path/to/zaturn/docker && docker compose down  # frees 23 and the name   <- outage starts
cd /path/to/suinevere-site-terminal
docker compose build
docker compose up -d
sudo systemctl reload nginx                       # binds 23                <- outage ends
```

Volumes are namespaced by compose project, so this creates a **new empty** `multizork-data`
rather than reusing zaturn's. If the save database matters:

```bash
docker volume ls | grep multizork
docker run --rm -v <old>:/from -v <new>:/to alpine sh -c 'cp -a /from/. /to/'
```

## Verifying

"It builds" and "it works in a browser" are separate claims here; the frontend has already
shipped a bundle that built cleanly and threw `Buffer is not defined` on load. Check all
three legs:

```bash
docker compose ps                                                      # both healthy
docker compose exec terminal bash -c 'exec 3<>/dev/tcp/multizork/2323' # internal path
printf 'hello\r\n' | timeout 8 nc <host> 23                            # Saturn path
```

Then open the page and confirm a session reaches a MultiZork prompt. If the socket connects
and nothing arrives, read the diagnosing notes in `mem/suinevere-server-hardening.md` before
concluding anything — "connect OK, 0 bytes, close" has three different causes.
