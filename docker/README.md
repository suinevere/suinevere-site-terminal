# Deploying the terminal

Two services in one stack. `multizork` builds `multizorkd` from the zaturn repo and listens
on loopback; `terminal` bakes the Vite bundle into the boot jar and serves both the page and
`/rsocket`, reaching the game over the internal compose network.

```
docker/
  docker-compose.yml          the stack
  Dockerfile                  terminal — built from the repo root, one stage up
  multizork/Dockerfile        multizorkd — clones its own source, context is this folder
  nginx/                      configs to copy onto the host
```

`.dockerignore` stays at the **repo root**, not here. Docker reads it from the build context
root, and the terminal's context is the repo root because the jar is built from source.
Moving it into `docker/` silently disables it, which is how a stale `frontend/dist` ends up
in an image.

## Installing on the server

Prerequisites, once:

```bash
sudo apt-get update
sudo apt-get install -y docker.io docker-compose-v2 git libnginx-mod-stream
sudo usermod -aG docker "$USER"     # log out and back in for this to take effect
```

Then clone and bring it up:

```bash
sudo git clone -b rsocket-terminal \
  https://github.com/suinevere/suinevere-site-terminal.git /opt/suinevere-site-terminal
sudo chown -R "$USER:$USER" /opt/suinevere-site-terminal
cd /opt/suinevere-site-terminal/docker
docker compose build
docker compose up -d
```

`rsocket-terminal` is deliberate — the branch is not merged to master yet. Drop the `-b` once
it is. If the repo is private, use the SSH remote with a deploy key instead of HTTPS.

Updating later is the same three commands:

```bash
cd /opt/suinevere-site-terminal && git pull && cd docker
docker compose build && docker compose up -d
```

### If the box is the 1 GB AMD micro instance

Gradle and Vite both want more memory than that leaves. Either give it swap:

```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

or build the terminal image on a workstation and ship it, skipping the build on the server
entirely:

```bash
docker compose build terminal
docker save suinevere-site-terminal:0.0.1 | gzip | ssh <host> 'gunzip | docker load'
```

The ARM instances have 24 GB and need neither.

## nginx

Copy both files out of `nginx/` and check the syntax before reloading.

```bash
sudo mkdir -p /etc/nginx/streams-enabled
sudo cp nginx/stream-multizork.conf /etc/nginx/streams-enabled/
sudo cp nginx/terminal.suin.uk.conf /etc/nginx/sites-available/
sudo ln -s /etc/nginx/sites-available/terminal.suin.uk.conf /etc/nginx/sites-enabled/
sudo nginx -t
```

The stream include has to go at the **top level** of `/etc/nginx/nginx.conf`, outside the
`http` block:

```nginx
include /etc/nginx/streams-enabled/*.conf;
```

`sites-enabled/` and `conf.d/` are both included from inside `http`, and `stream` is a
sibling of `http`, not a child — putting it in either is the mistake that looks correct and
fails at reload. The module is also a separate package on Ubuntu, `libnginx-mod-stream`.

`terminal.suin.uk.conf` expects a certificate. Get one before enabling the vhost:

```bash
sudo certbot certonly --webroot -w /var/www/html -d terminal.suin.uk
```

The copy of the duckdns vhost checked into `zaturn/docker/nginx/` proxies to GitHub Pages and
does not match what is live on the box. Treat it as stale; do not apply it wholesale.

## Cutover from the zaturn compose file

The existing `multizork` container holds both the name and port 23, so it goes first. The
syntax check binds nothing and is safe to run before anything is torn down; the outage window
should be seconds.

```bash
sudo nginx -t                                     # does not bind
cd /path/to/zaturn/docker && docker compose down  # frees 23 and the name   <- outage starts
cd /opt/suinevere-site-terminal/docker
docker compose build
docker compose up -d
sudo systemctl reload nginx                       # binds 23                <- outage ends
```

This stack pins its compose project name to `suinevere-terminal`, so it starts on a **new,
empty** `multizork-data` volume rather than adopting zaturn's. If the save database matters:

```bash
docker volume ls | grep multizork                 # find the old one
docker run --rm -v <old>:/from -v suinevere-terminal_multizork-data:/to \
  alpine sh -c 'cp -a /from/. /to/'
```

## Why nothing is published beyond loopback

Docker writes its iptables rules ahead of ufw's, so a container published on `0.0.0.0` stays
reachable from the internet regardless of what ufw is configured to allow. Both services
publish to `127.0.0.1` and nginx decides who reaches them.

That is also why `multizork` no longer publishes 23 the way the zaturn compose file does —
nginx binds 23 now, and a Docker publish would take it back and put the C parser directly on
the internet.

## Hardening flags on multizorkd

`multizork/Dockerfile` compiles with `-D_FORTIFY_SOURCE=2`, `-fstack-protector-strong`,
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
docker build --target build -t mz-check multizork
docker run --rm --entrypoint sh mz-check -c 'readelf -nW /multizorkd | grep -i properties'
```

Two tightenings left off to keep the change reviewable: `-D_FORTIFY_SOURCE=3`, which the
gcc in bookworm supports and which is strictly stronger, and pinning `REPO_REF` to a commit
sha instead of `main` so the image is reproducible.

## Consequences of the internal network path

The terminal reaches multizorkd directly, which means browser sessions skip `limit_conn mud 3`
entirely — `terminal.upstream.max-sessions` is the only cap on them. It also means they skip
the AUTH gate by construction, so enabling `ops/authproxy` in front of port 23 would gate the
Saturn path and leave the web path untouched.

Both are overridable without a rebuild: set `TERMINAL_UPSTREAM_HOST` and
`TERMINAL_UPSTREAM_PORT` to send the bridge back out through nginx instead.

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
