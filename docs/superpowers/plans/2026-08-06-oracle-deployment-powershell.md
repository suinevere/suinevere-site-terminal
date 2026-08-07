# PowerShell companion — Operator Tasks O3 and O4

Windows equivalents for the laptop-side commands in
`docs/superpowers/plans/2026-08-06-oracle-deployment.md`. The plan remains the source of
truth for *what* each step does and why; this file only restates *how* to run it from
PowerShell 7 on Windows.

**Steps that run on the Oracle box or the DreamPi stay exactly as the plan writes them.**
Those are Linux shells reached over SSH — translating them would be wrong. Each section below
says plainly which side it runs on.

Verified present on this machine: PowerShell 7.6.3, `curl.exe`, `ssh`, `gh`, `Resolve-DnsName`.

## Two Windows gotchas that bite every time

`-o /dev/null` becomes **`-o NUL`**. The Unix path silently creates a file called `dev\null`
in the working directory instead of discarding output.

`\n` inside a curl `-w` format string is interpreted by **curl**, not PowerShell, so
`-w "%{http_code}\n"` works unchanged in double quotes. Do not convert it to a backtick-n.

Avoid backtick line continuations for long curl invocations — a trailing space after the
backtick breaks them invisibly. Build the repeated arguments as an array and splat it, as the
WebSocket probes below do.

---

## Operator Task O3 — the upstream Netlink PR

**Runs on:** this machine. Fully translatable; `git` and `gh` behave identically.

You need a fork, since you are not the upstream owner:

```powershell
gh repo fork eaudunord/Netlink --clone --fork-name Netlink
Set-Location Netlink
git checkout -b multizork-auth-gate
```

Edit `tunnel/netlink_config.ini` so the 199408 block reads as the plan's O3 Step 1 specifies,
**including the secret** — that publication is the accepted trade recorded in
`mem/2026-08-06-oracle-deployment-decisions.md`.

```powershell
notepad tunnel\netlink_config.ini
```

Confirm you changed only what you meant to, then commit and open the PR:

```powershell
git diff -- tunnel/netlink_config.ini
git add tunnel/netlink_config.ini
git commit -m "Gate the MultiZork dial code behind the shared-secret handshake"
git push -u origin multizork-auth-gate
gh pr create --repo eaudunord/Netlink --title "Gate the MultiZork dial code behind the shared-secret handshake" --body "199408 now uses the authenticating handler. The server rejects connections without the preamble, which filters background scanning before it reaches the game's parser."
```

**O3 Step 2** — confirming a stock DreamPi still plays — runs on the DreamPi. Unchanged.

---

## Operator Task O4 — deploying the public route

### Step 1 — confirm the DNS record — *this machine*

```powershell
Resolve-DnsName terminal.suin.uk -Type A | Select-Object Name, IPAddress
```

Expect the Oracle public IP. `Resolve-DnsName` reads the local cache first; add
`-Server 1.1.1.1` to bypass it if you just created the record.

### Steps 2 through 5 — *on the box, unchanged*

certbot, the vhost symlink, the port-80 redirect fix, `docker/.env`, and
`docker compose build/up` are all Linux-side. Run them exactly as the plan writes them.

Step 2b's module check is also box-side and still matters — nginx there is **1.18.0**:

```bash
nginx -V 2>&1 | tr ' ' '\n' | grep auth_request
```

### Step 6 — verify the origin before the Worker exists — *this machine*

```powershell
curl.exe -sS -i https://terminal.suin.uk/zork/ | Select-Object -First 3
```

`head -3` becomes `Select-Object -First 3`. Expect a **302** toward Google. A **200** means
`SecurityConfig` is not active — stop rather than deploying the Worker over an open app.

If you would rather use a native cmdlet, note that `Invoke-WebRequest` follows redirects and
throws on error statuses, so inspecting a 302 needs both switches:

```powershell
(Invoke-WebRequest https://terminal.suin.uk/zork/ -MaximumRedirection 0 -SkipHttpErrorCheck).StatusCode
```

### Step 7 — Worker and Redirect Rules — *Cloudflare dashboard*

No shell. Follow `ops/cloudflare/README.md`, and **check its menu labels against the live
dashboard** — the structure is right but the exact wording was written without confirmation
and Cloudflare renames sections.

### Step 8 — verify the public path — *this machine*

```powershell
curl.exe -sS -i https://suin.uk/zork/ | Select-Object -First 3
curl.exe -sS -o NUL -w "z:       %{http_code}\n" https://suin.uk/z
curl.exe -sS -o NUL -w "zaturn:  %{http_code}\n" https://suin.uk/zaturn
```

Expect a 302 toward Google for the first and 301 for the other two.

### Step 9 — sign in for real — *browser*

Open `https://suin.uk/zork/` — **with the trailing slash**, which nginx normalises anyway.
Complete Google sign-in, confirm the terminal connects and the game responds, and confirm the
address bar still reads `suin.uk`.

### Step 10 — the guards, unauthenticated — *this machine*

This is the step that proves the WebSocket hole is closed. Run all three.

```powershell
$upgrade = @(
  '-H', 'Connection: Upgrade'
  '-H', 'Upgrade: websocket'
  '-H', 'Sec-WebSocket-Version: 13'
  '-H', 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=='
)

# a) no session, correct origin -> auth_request must deny
curl.exe -sS -o NUL -w "no-session: %{http_code}\n" @upgrade -H "Origin: https://suin.uk" https://suin.uk/zork/rsocket

# b) no session, hostile origin -> the origin map must deny
curl.exe -sS -o NUL -w "bad-origin: %{http_code}\n" @upgrade -H "Origin: https://evil.example" https://suin.uk/zork/rsocket

# c) the internal endpoint must not be reachable from outside
curl.exe -sS -o NUL -w "internal:   %{http_code}\n" https://suin.uk/_authcheck_internal
```

Expect **(a) 401, (b) 403, (c) 404**.

A **101** on (a) means the hole is still open — stop and do not announce the deployment. A
**500** on (a) means `/zork/_authcheck` answered with a redirect rather than a status, so the
`@Order(1)` filter chain is not matching; check that before touching nginx.

Run these from a shell with no cookie jar. `curl.exe` sends none by default, which is what
makes this a valid unauthenticated probe.

### Step 10b — confirm the Worker forwards Origin — *box-side log read*

Unchanged from the plan; `tail` on the box.

### Steps 11 and 12 — dashboard cleanup and the memory commit

Step 11 is dashboard-only. Step 12's `git add` / `git commit` behave identically in
PowerShell.

---

## Re-verifying the port-23 gate from Windows

Not an O4 step, but `nc` does not exist here and the plan's `printf 'AUTH\x20<secret>'` has no
direct PowerShell equivalent. This reproduces the preamble byte for byte.

```powershell
function Test-MultiZorkAuth {
    param(
        [string] $TargetHost = 'suinevere.duckdns.org',
        [int]    $Port = 23,
        [string] $Secret
    )

    $client = [System.Net.Sockets.TcpClient]::new($TargetHost, $Port)
    $stream = $client.GetStream()
    $stream.ReadTimeout = 8000

    if ($Secret) {
        $body = [Text.Encoding]::ASCII.GetBytes($Secret)
        $frame = [byte[]] ([Text.Encoding]::ASCII.GetBytes('AUTH') + [byte] $body.Length + $body)
        $stream.Write($frame, 0, $frame.Length)
    } else {
        $probe = [Text.Encoding]::ASCII.GetBytes("hello`r`n")
        $stream.Write($probe, 0, $probe.Length)
    }

    $buffer = [byte[]]::new(4096)
    try {
        $read = $stream.Read($buffer, 0, $buffer.Length)
        if ($read -le 0) { 'closed with no data' }
        else { [Text.Encoding]::ASCII.GetString($buffer, 0, $read) }
    } catch [System.IO.IOException] {
        'closed with no data'
    } finally {
        $client.Close()
    }
}
```

The length byte is computed from the secret rather than typed, which removes the failure mode
the plan warns about — a wrong length byte is indistinguishable from a wrong secret.

```powershell
Test-MultiZorkAuth                              # expect: closed with no data
Test-MultiZorkAuth -Secret '<the AUTH_SECRET>'  # expect: the MultiZork banner
```

For a plain reachability check, `Test-NetConnection suinevere.duckdns.org -Port 23` reports
whether the port accepts a connection, but it sends nothing — it cannot distinguish a live
gate from a dead upstream, which is the ambiguity `mem/suinevere-server-hardening.md` warns
about.
