---
name: recurring-build-hazards
description: Two failure modes that have bitten this project repeatedly and are invisible to normal review.
metadata:
  type: project
---

Both of these look correct in a diff and pass their obvious checks. Verify them the way
described, not by reading.

## The NUL escape

Writing a backslash-u-0000 escape through the editing tools silently produces a **raw NUL
byte** instead of the six-character escape. It has happened five times: twice in the plan
document, once in `terminalSession.ts`, once in a memory file, and once in a file whose only
purpose was documenting the hazard.

A raw NUL makes the file binary, so `grep` reports "Binary file matches" and refuses to show
content, while visual diffs look perfect. In Kotlin or TypeScript source the mangled literal
is a *different value*, which silently breaks the RSocket handshake sentinel.

Verify at byte level after any edit to a file containing it:

```bash
t=$(wc -c < f); s=$(tr -d '\000' < f | wc -c); echo $((t-s))   # must print 0
```

`tr -d '\000'` strips the byte for repair; a normal edit then fixes the surrounding text.
Prefer describing the character in prose over embedding it.

## Node globals in the browser bundle

The `@rsocket/*` packages reference `Buffer` as a **bare global** across 20 files and import
it in none. Vite's `resolve.alias` only rewrites explicit imports — which only first-party
code has — and `define: { global: 'globalThis' }` does not cover `Buffer`.

`frontend/src/polyfills.ts` installs it and **must remain the first import in `main.tsx`**.
A test asserts that ordering, and the guard was proven to fail when the import is moved.

Neither the Node-based RSocket gate nor `vite build` can catch this class of bug: Node has
`Buffer` global, and bundling succeeds because nothing is unresolved at build time. It
surfaced only on a real browser load. Treat "it builds" and "it runs in a browser" as
separate claims.

Relates to [[rsocket-terminal-project]].
