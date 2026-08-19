---
created: 2026-08-16T12:13:00.000Z
resolved: 2026-08-19
resolves_phase: 10
title: gitleaks pre-commit hook cannot scan a git worktree created outside the main repo tree
area: tooling
severity: minor
files:
  - .githooks/pre-commit
---

## Problem

`.githooks/pre-commit`'s secret-scanning step (quick task 260816-hn1) mounts the single
directory that is the common ancestor of a worktree's private git-dir
(`.git/worktrees/<name>/`) and its work tree, computed as
`dirname "$(git rev-parse --path-format=absolute --git-common-dir)"`. That works for every
worktree created under this repo's own convention (`.claude/worktrees/<name>`, per
`docs/SESSION_LESSONS.md`) because the common ancestor is still inside the main repository
directory tree.

It does **not** work for a worktree created entirely outside the main repo's directory tree — a
different drive, or a sibling directory reached only by `..` (e.g. `git worktree add
../some-other-dir`). In that case the common-ancestor directory computed above would be a
filesystem location that does not contain both the worktree's git-dir and its work tree in a
single mountable subtree (or, worse, could resolve to something unexpectedly broad, like a
drive root). No developer has hit this yet — this repo's own worktree convention keeps every
worktree nested under `.claude/worktrees/`, and 260816-hn1-MEASUREMENTS.md's empirical testing
only covered that nested case — so this is a known limit, not an observed failure.

## Recommended approach

Not urgent: fix if/when this repo's worktree convention ever changes, or if a developer reports
hitting it. If fixed, the fix likely needs either (a) detecting the outside-main-tree case and
falling back to a `stdin`-mode scan (loses path-based allowlist context, per
260816-hn1-MEASUREMENTS.md's own documented trade-off) or (b) mounting two separate volumes
(work tree + git-dir) and reconstructing the relative `commondir` link inside the container.

## Resolution

Closed by **Phase 10, Plan 02**, approach (a): `.githooks/pre-commit` now branches on
`case "$GIT_TOPLEVEL" in "$MOUNT_ROOT"|"$MOUNT_ROOT"/* ... *) ... esac`. The existing bind-mount
path is unchanged for plain checkouts and this repo's nested `.claude/worktrees/<name>`
convention; a genuinely out-of-tree worktree now falls back to gitleaks' own documented `stdin`
mode (`git diff --cached | gitleaks stdin`), accepting the documented path-based-allowlist-context
trade-off — which costs nothing today since `.gitleaks.toml` carries only rule-scoped allowlists,
no path-scoped ones.

Verified against a real out-of-tree worktree (`git worktree add` to a path genuinely outside this
repo's directory tree, confirmed via `git-common-dir`) with a real staged synthetic credential:
the hook correctly refused (exit 1, redacted values in the message). The nested-worktree/plain-
checkout regression path was re-run on a clean diff and still passes.
