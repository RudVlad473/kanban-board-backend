---
quick_id: 260905-qxi
status: complete
subsystem: infra
tags: [ci, docker-compose, iptables, security-gate, pyyaml, github-actions]
dependency-graph:
  requires: []
  provides: [compose-published-ports-gate, compose-published-ports-selftest, invariant-checks-second-job]
  affects: [docker-compose.prod.yml, docker-compose.nonprod.yml, .github/workflows/invariant-checks.yml, docs/INFRA_RUNBOOK.md]
tech-stack:
  added: []
  patterns: [pure-check-function-plus-thin-driver, committed-self-test-proving-invariants-can-fire]
key-files:
  created:
    - scripts/verify-compose-ports.py
    - scripts/verify-compose-ports-selftest.py
    - .planning/todos/pending/2026-09-05-docker-user-chain-empty-on-the-vm.md
  modified:
    - .github/workflows/invariant-checks.yml
    - docker-compose.prod.yml
    - docker-compose.nonprod.yml
    - docs/INFRA_RUNBOOK.md
decisions:
  - "D-01: gate covers docker-compose.prod.yml + docker-compose.nonprod.yml only; docker-compose.yml (local dev) excluded and the exclusion is stated in the gate's own docstring."
  - "D-02: allowed-publisher set is PER FILE -- prod maps to {caddy}, nonprod maps to the empty set."
  - "D-03: non-allowed services checked by PRESENCE of ports: only, no entry parsing, no bind-address exemption, no empty-list exemption."
  - "D-04: network_mode: host is a first-class invariant (I2), applied to caddy too -- the allowed set does not exempt it."
  - "D-05: a second job in the existing invariant-checks.yml, not a new workflow file or a step inside caddy-image-tag."
  - "D-06: expose: is explicitly out of scope -- it publishes nothing to the host."
  - "D-07: the runbook's 'identical policy' claim corrected with dated evidence, not softened; runtime DOCKER-USER work filed as a tracked todo, no VM change made."
  - "D-08: both proofs required and both done -- a committed self-test (logic) plus a one-off in-tree red/green run against real files (wiring)."
metrics:
  duration: "~55 minutes"
  completed: "2026-09-05"
actuals:
  tokens: 6800
  tasks: 3
  commits: 3
---

# Quick Task 260905-qxi: CI invariant that only Caddy may publish host ports Summary

Turned three prose "publishes nothing" comments in `docker-compose.prod.yml` into a CI-enforced
invariant (`scripts/verify-compose-ports.py` + a committed self-test, wired into
`invariant-checks.yml` as a second job), and corrected `docs/INFRA_RUNBOOK.md`'s false claim that
the OS iptables layer and the Netcup Cloud Firewall enforce identical policy -- verified live on
the VM that Docker's DNAT bypasses `INPUT` entirely and `DOCKER-USER` is empty.

## What Was Built

**Task 1 (`3f61d12`)** -- the gate and its self-test:
- `scripts/verify-compose-ports.py`: `find_violations(compose, allowed, label)` is a pure function
  (no file I/O) checking four invariants -- I1 (no `ports:` key on a non-allowed service), I2
  (no `network_mode: host` anywhere, including on the allowed service), I3 (every allowed name
  actually exists as a service), I4 (the allowed service's published set is exactly
  `{"80:80", "443:443"}`, checked as a true set-equality, not a list/sequence comparison). `main()`
  loads both real files with `yaml.safe_load` and drives the pure function.
- `scripts/verify-compose-ports-selftest.py`: loads the gate module by file path via
  `importlib.util` (the gate's filename carries hyphens, matching this repo's other
  `scripts/verify-*.py` naming, which is not a valid Python `import` name), then feeds
  `find_violations` one in-memory violating document per invariant plus two clean documents and two
  malformed-document cases (missing `services:` key; a non-mapping service value).

**Task 2 (`6c597a2`)** -- wired into CI, comments point at the gate:
- `.github/workflows/invariant-checks.yml`: new `compose-published-ports` job, following
  `caddy-image-tag`'s shape exactly (checkout, explicit `pip install pyyaml`, then the check) --
  self-test step runs before the gate step, so a logic regression is what CI reports, not a false
  green. No change to the workflow's `on:` block; its `paths-ignore` was confirmed (not assumed) to
  not match either compose file.
- All five "this service publishes nothing" comments across both compose files (prod's `postgres`,
  `app`, `redpanda`; nonprod's `redpanda-nonprod`, `app-nonprod`) gained one line each naming
  `scripts/verify-compose-ports.py` as the mechanical enforcer. Every existing sentence in those
  comments (the `app` TLS-bypass note, the "admin access is SSH + docker exec" notes) was left
  untouched.
- Self-caught deviation (see below): dropped two planning-system-token citations
  (`quick task 260905-qxi`, `D-08`) that had leaked into Task 1's two new script docstrings,
  discovered rereading the plan's own `<comment_conventions>` before this task's diff was reviewed.

**Task 3 (`566e725`)** -- runbook correction and the tracked runtime follow-up:
- `docs/INFRA_RUNBOOK.md`'s "Firewall — two independent layers" section: replaced the opening
  "Both layers enforce the identical policy" with a dated correction table (the three `iptables`
  commands and their consequences), an operational paragraph naming the new gate, and a pointer to
  the tracked `DOCKER-USER` follow-up. The section heading itself was left unchanged (only the body
  was rewritten) specifically so the existing cross-reference at the "External Network Audit" pass
  further down the file (`see "Firewall — two independent layers" above`) stays exact rather than
  going stale.
- `.planning/todos/pending/2026-09-05-docker-user-chain-empty-on-the-vm.md`: filed in the
  established pending-todo format (frontmatter + `## Problem` + `## Solution`), explicitly recording
  why this is split from the CI gate rather than folded into it -- the gate reads the committed
  file, this item is about the running host, and nothing in this repository can reach a live VM's
  `iptables` state.

## Red/Green Proof (verbatim, per the constraint)

PyYAML version confirmed: **6.0.3**. Merge-key flattening re-confirmed independently (not trusted
from the plan's note):

```
$ python3 -c "import yaml; d=yaml.safe_load('...<<: *a...'); assert 'ports' in d['services']['app']; print(...)"
merge-key flattening confirmed, PyYAML 6.0.3
```

**I1 -- `ports:` on a non-allowed service (`app` in `docker-compose.prod.yml`), required proof:**

- Confirmed clean before mutating: `git status --porcelain docker-compose.prod.yml
  docker-compose.nonprod.yml` printed nothing.
- Injected `ports:\n  - "8080:8080"` under the `app` service. Ran the gate:

```
FAIL: I1 violated in docker-compose.prod.yml: service `app` carries a `ports:` key but is not in this file's allowed set ['caddy']
EXIT_CODE=1
```

- Reverted with `git checkout -- docker-compose.prod.yml`, confirmed `git diff --quiet` passed. Ran
  the gate again:

```
invariants OK: only caddy publishes 80/443 in docker-compose.prod.yml; no service in docker-compose.nonprod.yml publishes anything; no network_mode: host anywhere (docker-compose.prod.yml, docker-compose.nonprod.yml)
EXIT_CODE=0
```

**I2 -- `network_mode: host` (on `postgres` in `docker-compose.prod.yml`), additionally verified
against the real file per the constraint (not just the self-test):**

- Injected `network_mode: host` under `postgres`. Ran the gate:

```
FAIL: I2 violated in docker-compose.prod.yml: service `postgres` sets `network_mode: host`, which publishes every listening port on the host and bypasses `ports:` entirely
EXIT_CODE=1
```

- Reverted, confirmed `git diff --quiet` passed, re-ran clean (same `invariants OK:` line as above).

**I4 -- caddy publishing an unexpected port beyond 80/443, additionally verified against the real
file:**

- Injected a third port (`"8443:8443"`) onto `caddy`'s existing `ports:` list. Ran the gate:

```
FAIL: I4 violated in docker-compose.prod.yml: `caddy`'s published set is ['80:80', '443:443', '8443:8443'], not exactly ['443:443', '80:80']
EXIT_CODE=1
```

- Reverted, confirmed `git diff --quiet` passed, re-ran clean.

**Both directions checked for all three invariants exercised against the real files.** Final state:
`git diff --quiet docker-compose.prod.yml docker-compose.nonprod.yml` passes -- both files are
byte-identical to their committed state.

**Self-test and gate, final run from `HEAD` (`566e725`):**

```
$ python3 scripts/verify-compose-ports-selftest.py
invariants OK: every invariant (I1-I4) proven to fire, plus malformed-document and clean-document cases
$ python3 scripts/verify-compose-ports.py
invariants OK: only caddy publishes 80/443 in docker-compose.prod.yml; no service in docker-compose.nonprod.yml publishes anything; no network_mode: host anywhere (docker-compose.prod.yml, docker-compose.nonprod.yml)
```

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 -- self-inflicted convention violation, caught and fixed same-session] Dropped
planning-system-token citations from Task 1's own new files.**
- **Found during:** Task 2, rereading the plan's `<comment_conventions>` before committing.
- **Issue:** `scripts/verify-compose-ports.py`'s docstring cited `(quick task 260905-qxi)` and
  `D-02`; `scripts/verify-compose-ports-selftest.py`'s docstring cited
  `(quick task 260905-qxi, D-08)`. The plan explicitly forbids exactly this shape of citation in
  any new committed comment (`"No planning-system tokens in any committed comment -- no D-01, no
  F-01, no 260905-qxi"`), scoping the one stated exemption to pre-existing citations already inside
  `invariant-checks.yml`'s header -- which does not cover brand-new text I was writing in that same
  file, and does not cover the two new scripts at all.
- **Fix:** Rewrote all four sites to state the same fact without the token -- e.g. "The allowed set
  is PER FILE (each covered path maps to its own set), never one global set shared across both
  files" in place of "D-02: PER FILE, not one global set." Also fixed the new `invariant-checks.yml`
  header addition itself, which had used the same forbidden shape (`(quick task 260905-qxi,
  2026-09-05)` narrowed to a bare date).
- **Files modified:** `scripts/verify-compose-ports.py`, `scripts/verify-compose-ports-selftest.py`,
  `.github/workflows/invariant-checks.yml`.
- **Verification:** `grep -n "260905-qxi\|\bD-0[0-9]\b"` over all five touched non-doc files
  returned nothing after the fix (excluding pre-existing tokens the plan explicitly left alone);
  full gate + self-test re-run confirmed green after the edit.
- **Committed in:** `6c597a2` (Task 2 commit -- the fix landed alongside Task 2's own file set
  rather than as an amend to Task 1's already-landed commit, since amending a prior commit is
  against this repo's git discipline; the fix is called out here rather than folded silently into
  Task 2's own description).

**Note:** `docs/INFRA_RUNBOOK.md` (Task 3) DOES cite `quick task 260905-qxi` in prose -- this is
correct and not a violation. The plan's comment-convention prohibition is scoped to code comments
(the gate's docstring, the compose files' pointer comments), matching `CODE_COMMENTS.md`'s own
scope; `INFRA_RUNBOOK.md` already cites plan/task IDs pervasively throughout as its established
documentation convention (e.g. "Postgres memory profile correction — Plan 11-08"), and this
citation follows that existing house style, not the code-comment rule.

---

**Total deviations:** 1 auto-fixed (Rule 1, self-corrected before external review).
**Impact on plan:** No scope creep -- the fix only removed noise from comments this same task wrote
moments earlier; nothing about the gate's logic, coverage, or the compose files' structural content
changed.

## Issues Encountered

None beyond the deviation above. The pre-commit hook's `spotlessCheck` + `fastTest` pass (~85s
first commit while Gradle's cache was cold, ~1-2s on the following two commits since nothing Java
changed) ran as expected on all three commits; `git commit` was long-running enough on the first
commit to move to background, which is the hook working as documented, not a failure.

One incidental discovery, not a deviation: `scripts/__pycache__/verify-caddy-image-tag.cpython-314.pyc`
is a pre-existing TRACKED file in this repository (predates this task, committed in `3a831c3`).
Running this task's self-test (which imports `verify-compose-ports.py` via `importlib`) generates
its own untracked `.pyc` alongside it in the same directory; a blanket `rm -rf scripts/__pycache__`
during cleanup twice deleted the pre-existing tracked file as a side effect, both times caught by
`git status` before committing and restored with `git checkout --`. Neither commit contains the
deletion. Not filed as a todo -- flagging it here is sufficient since it is a one-line awareness
note for future cleanup in this directory, not a defect this task introduced.

## Known Stubs

None.

## Threat Flags

None -- this task's own STRIDE register (T-qxi-01 through T-qxi-06, T-qxi-SC) was implemented and
verified exactly as specified; no new surface was introduced beyond what the plan already covers.

## Self-Check: PASSED

- FOUND: scripts/verify-compose-ports.py
- FOUND: scripts/verify-compose-ports-selftest.py
- FOUND: .planning/todos/pending/2026-09-05-docker-user-chain-empty-on-the-vm.md
- FOUND: .github/workflows/invariant-checks.yml (compose-published-ports job present)
- FOUND: docker-compose.prod.yml (pointer comments present, byte-identical to committed state)
- FOUND: docker-compose.nonprod.yml (pointer comments present, byte-identical to committed state)
- FOUND: docs/INFRA_RUNBOOK.md (Firewall section corrected)
- FOUND commit: 3f61d12 (Task 1 -- feat: the gate and self-test)
- FOUND commit: 6c597a2 (Task 2 -- feat: CI wiring + compose pointer comments + self-fix)
- FOUND commit: 566e725 (Task 3 -- docs: runbook correction + tracked todo)

## Next Steps (merge gate)

Per the plan's `<merge_gate>`: work stays on `quick/260905-qxi-compose-ports-invariant`. **Not
merged, and the merge was not opened.** A three-way review (Claude, Gemini, Codex -- one at a time,
one worktree each) is the next step before the human sanctions the merge. Point reviewers at: (1)
whether any invariant can be satisfied by something the gate should have caught, and (2) whether the
gate's KNOWN HOLES list is honest or flattering.
