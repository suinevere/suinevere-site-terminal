---
name: 2026-08-05-handoff-server-hardening
description: Session handoff — terminal shipped and browser-verified on an unmerged branch; server hardening partly applied with three decisions open.
metadata:
  type: project
---

> **Largely superseded by [[2026-08-06-handoff-oracle-deployment]].** Decisions 1 and 2 below
> are closed; decision 3, the merge, is still open. The claim that gcc hardening flags are
> unapplied was already stale and is corrected in [[suinevere-server-hardening]]. Read the
> newer handoff first and treat this one as history.

Continues work on the browser terminal and the hardening of the box it talks to.

## Where things stand

**The terminal is built and works.** Nine planned tasks complete, each independently
reviewed, plus a whole-branch review and its fix wave. The user confirmed it running in a
browser after the final `Buffer` polyfill fix.

Branch `rsocket-terminal`, **33 commits, not merged to master**. Full history, per-task
review findings and every deferred item are in
`.superpowers/sdd/2026-08-04-rsocket-terminal/progress.md` — read that before re-deriving
anything.

**Server hardening is partly applied.** See [[suinevere-server-hardening]] for what is done,
what is not, and why. Short version: rpcbind removed, nginx `stream` fronting port 23 with
per-IP connection limits, auth proxy written but deliberately bypassed, gcc hardening flags
not yet applied.

## Three open decisions, all blocking

1. **Is MultiZork meant to be publicly playable, or private?** This decides whether the AUTH
   gate is worth enabling at all. Public means the secret must ship in the public Netlink
   config, reducing it to scanner-filtering. Private means it can be distributed by hand and
   is a real access control.

2. **Where does the Spring bridge run?** If on the Oracle box, point
   `terminal.upstream.host` at `127.0.0.1:2323` and it bypasses any gate with no code change.
   If off-box, `UpstreamTcpClient` must send the `AUTH` preamble and consume the `\x01`
   before relaying. The Kotlin change was offered and not yet written.

3. **Merge the branch?** Nothing has been integrated to master. Use
   superpowers:finishing-a-development-branch when the user is ready.

## Caveats worth carrying forward

**The final fix wave was never independently reviewed.** Tasks 1-9 each got a fresh reviewer
plus a scoped re-review of every fix. The whole-branch fix wave was applied directly by the
controller because subagent dispatch was blocked by the permission classifier, and it got
self-verification only. Across this run independent review caught three tests that passed
with their feature deleted, a build that could ship a stale bundle, and a charset bug that
nine task reviews missed — so that gap is not a formality.

**One finding was deliberately left unfixed.** The session-cap path does not drain its
inbound `Flux`. Every way to drain it gates completion on the client's outbound completing,
which the browser never does — that would suppress `onClose` and therefore the Reconnect
button. Reasoning is recorded in the ledger; do not "fix" it without re-reading that.

**`git add -A` swept in an unrelated IntelliJ edit** to `.run/SuinevereSiteTerminalApplication.run.xml`
during one commit. Stage explicit paths. `Netlink/` is an embedded git repo, now gitignored
after being briefly committed as a broken gitlink.

**Do not reproduce the Netlink shared secrets** in any file here. They exist in
`Netlink/tunnel/netlink_config.ini`, which is committed to a public upstream repo.

## Suggested skills

- **superpowers:finishing-a-development-branch** — for the merge decision on `rsocket-terminal`.
- **superpowers:systematic-debugging** — before proposing any fix if the terminal or the
  tunnel misbehaves. Both recent bugs were misdiagnosed on first read from an ambiguous
  symptom; see the diagnosing section of [[suinevere-server-hardening]].
- **superpowers:requesting-code-review** — if the unreviewed fix wave should get the
  independent pass it never had.
- **superpowers:brainstorming** — before any new feature work, per the standing convention.
- **superpowers:writing-plans** then **superpowers:subagent-driven-development** — the
  workflow this project already uses; the existing plan and ledger show the expected shape.

Relates to [[rsocket-terminal-project]], [[recurring-build-hazards]],
[[suinevere-server-hardening]].
