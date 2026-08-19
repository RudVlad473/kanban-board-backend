---
phase: 10-ci-deploy-hardening
plan: 02
subsystem: infra
tags: [github-actions, ci, secret-scanning, trufflehog, gitleaks, docker, security]

# Dependency graph
requires:
  - phase: 10-ci-deploy-hardening (plan 01)
    provides: no direct dependency — plans in this wave are independent
provides:
  - "verified-credential-scan job in .github/workflows/secret-scan.yml (TruffleHog, hard-gated, diff-scoped, digest-pinned)"
  - ".githooks/pre-commit case-based scan-path selection covering out-of-tree worktrees"
affects: [ci-deploy-hardening, secret-scanning, worktree-tooling]

# Actuals (#2632) — pairs with the plan's `estimate` to calibrate future estimates.
actuals:
  tokens: 4203
  tasks: 2
  commits: 2

tech-stack:
  added: ["ghcr.io/trufflesecurity/trufflehog:3.97.0 (digest-pinned Docker image, CI-only)"]
  patterns:
    - "Digest-pinned Docker-direct scanner invocation (mirrors the existing gitleaks pattern in the same workflow file)"
    - "jq field-ALLOWLIST scrub for a scanner report with no built-in redaction, uploaded instead of the raw stream"
    - "case \"$GIT_TOPLEVEL\" in ... esac scan-path selection in a shell pre-commit hook, covering plain checkout / nested worktree / out-of-tree worktree"

key-files:
  created: []
  modified:
    - ".github/workflows/secret-scan.yml — new verified-credential-scan job"
    - ".githooks/pre-commit — case-based bind-mount vs stdin fallback"

key-decisions:
  - "TruffleHog invoked via digest-pinned docker run (not the marketplace action), mirroring the existing gitleaks pattern in the same file — one invocation style for both scanners, and the marketplace action's own documented example uses a mutable tag, which would have contradicted this task's own thesis."
  - "--branch deliberately NOT passed to the TruffleHog git subcommand, diverging from the plan's literal suggestion: actions/checkout leaves the runner in a detached HEAD, and a branch name with no corresponding local ref would turn a legitimate push into a hard-gate outage. --since-commit alone already bounds the scan; branch is resolved and logged for traceability only."
  - "Out-of-tree worktree fallback uses gitleaks' own documented `stdin` scanning mode (git diff --cached | gitleaks stdin), per D-12 — not a novel workaround, and the accepted trade-off (loses path-based allowlist context) costs nothing today since .gitleaks.toml carries no path-scoped allowlist entries."

patterns-established:
  - "Pattern: hard-gated live-credential verification scanner as a sibling CI job to the existing pattern-match scanner, sharing on: triggers but scoped to the diff range rather than full history, because verification issues live network calls per candidate finding."

requirements-completed: [HARDEN-05]  # HARDEN-02's code (Task 1) is done and locally verified, but the
# plan gates HARDEN-02 completion on Task 3's live-CI human-verify checkpoint, which is not yet
# reached (see Next Phase Readiness) — so HARDEN-02 is deliberately NOT listed complete here.

coverage:
  - id: D1
    description: "CI runs a digest-pinned, diff-scoped TruffleHog verified-credential pass as a hard gate on every push/PR, with raw findings never uploaded (jq allowlist scrub only)"
    requirement: HARDEN-02
    verification:
      - kind: other
        ref: "Local docker run smoke test against the pinned image + plain clone of this repo (git file:///repo --since-commit HEAD~1 --results=verified --json --no-update): exit 0, 16 chunks/22068 bytes scanned, 0 verified secrets"
        status: pass
      - kind: other
        ref: "Redaction proof: a runtime-generated, never-issued AWS-shaped access-key-id/secret pair (16-char and 40-char random values, not reproduced here since gitleaks' own pre-commit gate correctly flags AWS-key-shaped strings in staged prose) scanned via `filesystem` mode with --results=verified,unknown,unverified; the workflow's own jq allowlist filter applied to the resulting raw.jsonl (1 record) produces scrubbed.jsonl (1 record, matching count) with 0 occurrences of either the secret value or the access key id"
        status: pass
      - kind: other
        ref: "grep-based structural checks against .github/workflows/secret-scan.yml (pin regex, no mutable tag, no continue-on-error beyond pre-existing comment noise, since-commit present, all-zeroes sentinel handled, 183 handled, fetch-depth: 0 x2, scrubbed-file-only upload path, rm on raw-findings, no cat/tee of raw-findings, no jq del() denylist) — all pass; see Deviations for the one pre-existing grep-imprecision caveat"
        status: pass
    human_judgment: true
    rationale: "Task 3 (this plan's own blocking checkpoint, gate=blocking, explicitly 'not auto-approvable regardless of workflow.auto_advance') requires inspecting TruffleHog's FIRST LIVE run in the actual GitHub Actions environment after this branch is pushed/merged — the pinned image, resolved range, and scrub filter were proven correct in local Docker, but the live CI path (actions/checkout's detached-HEAD behavior, the real Actions log, the real uploaded artifact) has not yet been observed and this executor cannot push to master or open a real PR from an isolated worktree."
  - id: D2
    description: "Pre-commit hook detects an out-of-tree worktree and falls back to gitleaks' stdin mode instead of silently scanning nothing"
    requirement: HARDEN-05
    verification:
      - kind: manual_procedural
        ref: "git worktree add -b harden05-verify-a4e $TEMP/harden05-outside-a4e HEAD (genuinely outside C:/Dev/Repos/kanban-board-backend, confirmed via git-common-dir); staged a runtime-generated AWS-shaped credential; ran the edited hook directly — exit 1, 'likely credential in the staged diff' refusal, values shown as REDACTED. Verification worktree/branch removed afterward; git worktree list shows only the real worktrees."
        status: pass
      - kind: manual_procedural
        ref: "Regression: ran the gitleaks-only portion of the hook from this executor's own worktree (an in-tree/nested case) against a clean staged diff — 'No secrets found in staged diff.', exit 0, unchanged from pre-fix behavior"
        status: pass
    human_judgment: false

# Metrics
duration: ~50min
completed: 2026-08-19
status: halted
---

# Phase 10 Plan 02: TruffleHog CI gate + out-of-tree worktree hook fix Summary

**Digest-pinned TruffleHog verified-credential CI gate added alongside gitleaks in secret-scan.yml, plus a `case`-based pre-commit hook fix so an out-of-tree worktree refuses a staged credential instead of silently reporting clean — Task 3's live-CI human-verify checkpoint is still pending.**

## Performance

- **Duration:** ~50 min
- **Tasks:** 2 of 3 completed (Task 3 is a blocking checkpoint, not yet reached)
- **Files modified:** 2

## Accomplishments

- `.github/workflows/secret-scan.yml` gained a second job, `verified-credential-scan`, running a hard-gated TruffleHog pass on the pushed/PR'd commit range only (never full history), pinned to `ghcr.io/trufflesecurity/trufflehog:3.97.0@sha256:ff4c95e9df7d645daf2140e3ca1039031c63106268d5fbb25feb43ceca1bcc33` — re-resolved live against ghcr.io during authoring and confirmed to match the plan's recorded value exactly (no discrepancy to record).
- Confirmed empirically (not assumed) that this pinned TruffleHog version ships no `--redact` equivalent, by grepping its own `--help` output for both the top-level and `git` subcommand — no match either place.
- Built and proved the report-scrub path: a throwaway, never-issued AWS-shaped credential appears in TruffleHog's raw JSON record (`Raw`/`RawV2`/`Redacted`/`SecretParts` all carry the value) and is completely absent from the same jq allowlist filter embedded in the workflow, with matching non-empty record counts on both sides.
- `.githooks/pre-commit` now branches on `case "$GIT_TOPLEVEL" in "$MOUNT_ROOT"|"$MOUNT_ROOT"/* ... *) ... esac` — the existing bind-mount path is byte-for-byte unchanged for plain checkouts and this repo's nested `.claude/worktrees/<name>` convention; a genuinely out-of-tree worktree now falls back to `git diff --cached | gitleaks stdin`, gitleaks' own documented pre-commit mode.
- Live-verified both hook paths: an out-of-tree worktree with a staged fake credential is refused (exit 1, correct message, redacted values); the in-tree/nested path still passes cleanly on an empty diff (exit 0).

## Task Commits

1. **Task 1: Add a digest-pinned, range-scoped, hard-gated TruffleHog job to secret-scan.yml** - `618f56a` (feat)
2. **Task 2: Make the pre-commit gitleaks hook scan correctly from an out-of-tree worktree** - `b96bd48` (fix)
3. **Task 3: Package-legitimacy gate — inspect TruffleHog's first live run before trusting it on master** - NOT STARTED (blocking checkpoint, see below)

## Files Created/Modified

- `.github/workflows/secret-scan.yml` - New `verified-credential-scan` job: range resolution, digest-pinned `docker run` TruffleHog invocation, three-way exit-code branch (0/183/other), jq allowlist scrub, scrubbed-file-only artifact upload
- `.githooks/pre-commit` - `case "$GIT_TOPLEVEL" in` branch selection; unchanged bind-mount path plus new `stdin`-mode fallback for out-of-tree worktrees; extended mount-strategy comment

## Decisions Made

- TruffleHog invoked directly via digest-pinned `docker run`, mirroring the existing gitleaks invocation shape in the same file (Trade-off Matrix in PLAN.md, approach B over the marketplace action or a release-binary download).
- `--branch` omitted from the TruffleHog `git` subcommand invocation despite the plan's literal text naming it as a candidate parameter — see Deviations below.
- Out-of-tree worktree fallback uses gitleaks' own `stdin` mode (D-12), accepting the documented path-based-allowlist-context trade-off, which costs nothing today since `.gitleaks.toml` carries only rule-scoped allowlists.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Omitted `--branch` from the TruffleHog invocation to avoid a real hard-gate outage risk**
- **Found during:** Task 1, while designing the `Scan for verified live credentials` step
- **Issue:** The plan's `<action>` text suggested passing `--branch` (PR head ref for `pull_request`, `github.ref_name` for `push`) to TruffleHog's `git` subcommand. `actions/checkout` leaves the runner's local clone in a detached HEAD state (this is standard, documented behavior, not specific to this repo), and TruffleHog's `git` subcommand does its own `git clone` of the local checkout and then would attempt to resolve that branch name — which has no corresponding local ref in a detached-HEAD source, especially for a `pull_request` head ref on a fork. Passing an unresolvable `--branch` would turn a legitimate push or PR into a hard-gate failure on every run, not just a mis-scoped scan.
- **Fix:** `--branch` is not passed to TruffleHog at all. `--since-commit` alone already bounds the scan to the intended range (proven correct via the local smoke test, which used only `--since-commit` and correctly scanned exactly the intended range). The branch name is still resolved in the `Resolve scan range` step and echoed to the log for traceability, satisfying the plan's "echo the resolved range" must-have without introducing the flag that risked the outage.
- **Files modified:** `.github/workflows/secret-scan.yml`
- **Verification:** Local `docker run` smoke test against a plain (non-detached) clone of this repo, using the exact `--since-commit`-only invocation shape now in the workflow: exit 0, 16 chunks/22068 bytes scanned in range, matching the expected single-commit diff.
- **Committed in:** `618f56a` (Task 1 commit) — documented inline in the job's load-bearing comment block (`--branch is deliberately NOT passed...`)

**2. [Rule 1 - Bug] Reduced literal "continue-on-error" / "fetch-depth: 0" repetition in new prose comments**
- **Found during:** Task 1, running the plan's own `<verify>` grep checks against the edited file
- **Issue:** The plan's acceptance criteria assert `grep -cE 'continue-on-error' .github/workflows/secret-scan.yml` prints `0` and `grep -cE 'fetch-depth: 0' .github/workflows/secret-scan.yml` prints `2`. Both greps are comment-blind — they also match the string inside explanatory prose, not just real YAML directive usage. The ORIGINAL file (before this task) already had one comment-only match of `continue-on-error` (line 15, the file's pre-existing header) and one comment-only match of `fetch-depth: 0` (its own pre-existing sibling-job comment) — i.e. the literal grep target was already unsatisfiable before this task started, independent of anything I wrote.
- **Fix:** Reworded my own two new comment mentions (a `continue-on-error` reference and two `fetch-depth: 0` references) to describe the same rationale without repeating the literal token — e.g. "no error-swallowing override" instead of the literal flag name. This does not fix the pre-existing count (still 1 for `continue-on-error`, not the literally-stated `0`; 3 for `fetch-depth: 0` including 1 pre-existing comment, not literally `2`), but eliminates every match this task itself introduced. Confirmed via `awk '/^jobs:/{f=1} f'` scoping that the SEMANTIC intent (no real `continue-on-error:` YAML key anywhere; exactly 2 real `fetch-depth: 0` key usages, one per job) is fully satisfied.
- **Files modified:** `.github/workflows/secret-scan.yml`
- **Verification:** `grep -nE 'continue-on-error'` and `grep -nE 'fetch-depth: 0'` re-run after the reword, confirming the only remaining "excess" matches over the plan's literal expectation are pre-existing, not new.
- **Committed in:** `618f56a` (Task 1 commit)

**3. [Rule 3 - Blocking] Job-count grep also matches `on: push:`, not just the two job keys**
- **Found during:** Task 1 verification
- **Issue:** `grep -cE '^  [a-z-]+:$' .github/workflows/secret-scan.yml` prints `3`, not the plan's stated `2` — because `on: push:` (a 2-space-indented, lowercase, colon-terminated key under the top-level `on:` block, pre-existing in the file before this task) also matches the same pattern. `pull_request:` does not match (contains an underscore, outside `[a-z-]+`), which is why the original file's own count was 2, not 1, before this task even started.
- **Fix:** No file change — this is a verification-script limitation, not an implementation defect. Confirmed the semantically correct check instead: `awk '/^jobs:/{f=1} f' .github/workflows/secret-scan.yml | grep -cE '^  [a-z-]+:$'` scoped to the `jobs:` block only prints `2` (`secret-scan:` + `verified-credential-scan:`), matching the plan's actual intent.
- **Files modified:** none (verification-only finding)
- **Verification:** `awk`-scoped grep as above.
- **Committed in:** N/A (no code change required)

---

**Total deviations:** 3 (1 correctness/security fix to a plan-suggested invocation shape, 2 documented verify-script imprecisions with no implementation defect)
**Impact on plan:** The `--branch` omission is a genuine improvement over the plan's literal suggestion — it removes a real hard-gate-outage risk while still satisfying every stated must-have truth (range resolved and echoed per trigger shape, degenerate-range fallback, three-way exit branch). The other two are pre-existing grep-pattern limitations in the plan's own `<verify>` block (comment-text false positives), not scope creep or missed requirements — the semantically-scoped equivalents were run and pass cleanly.

## Issues Encountered

- **Gradle daemon file lock after a bash-tool timeout:** the first `git commit` attempt for Task 1 was killed by the harness's default ~6m40s Bash timeout partway through `./gradlew fastTest` (Testcontainers Postgres + Redpanda cold-starting). The underlying Gradle daemon process kept running after the harness killed the foreground call, leaving `build/test-results/fastTest/binary/output.bin` locked; a subsequent direct `./gradlew fastTest` run failed with `Unable to delete directory`. Resolved by `./gradlew --stop` (stopped the orphaned daemon) followed by a clean `./gradlew fastTest` run (`BUILD SUCCESSFUL in 6m 29s`), then committing both tasks via `run_in_background: true` so the ~7-minute pre-commit hook chain (gitleaks + spotlessCheck + fastTest) could complete without hitting the same foreground timeout. Both commits landed cleanly with hooks fully passing (not bypassed with `--no-verify`).
- **First out-of-tree worktree probe took the wrong branch:** the initial behavioral test for Task 2 ran `git worktree add ... HEAD` from a genuinely out-of-tree path, but `HEAD` checks out the last *committed* state, not this session's then-uncommitted `.githooks/pre-commit` edits — so the probe ran the OLD (pre-fix) hook and, unsurprisingly, took the old bind-mount code path with a `stat /repo/C:/...` error. Fixed by copying the edited hook file directly into the probe worktree (`cp`, not a git operation) before re-running the test; the second run correctly took the new `stdin` fallback branch and refused the staged credential.

## User Setup Required

None - no external service configuration required for the two completed tasks. Task 3 (below) requires this branch to actually be pushed/merged before it can be attempted.

## Next Phase Readiness

**Task 3 is NOT complete.** It is a `type="checkpoint:human-verify"` task with `gate="blocking"`, and its own `<what-built>` text is explicit: *"It is not auto-approvable regardless of `workflow.auto_advance`."* This project's config (`.planning/config.json`) has `workflow.auto_advance: true`, which would normally auto-approve a `checkpoint:human-verify` — but per the executor's own auto-mode rules, a checkpoint whose purpose is package-legitimacy verification for a tool newly introduced to the repo (TruffleHog, confirmed via the plan's own threat register `T-10-SC`) is excluded from auto-approval, and this task explicitly reinforces that exclusion in its own text.

Substantively, Task 3 requires observing TruffleHog's actual FIRST LIVE run inside real GitHub Actions: the pushed commit's Actions log (confirming the pulled image shows a digest, the scan range is not full-history, and a non-zero commit/chunk count was actually scanned), plus a live throwaway-credential PR test to confirm the verified-vs-pattern-matched asymmetry and that no value leaks into the log or the uploaded artifact on the real CI path. None of that evidence can be produced from this isolated worktree — it requires this branch to be merged/pushed to a remote where the workflow actually triggers, and requires `gh` commands operating against the real repository, which this parallel worktree executor should not do unilaterally.

**Blocked by:** Task 3's precondition — the code must be live in GitHub Actions (pushed to `master` or opened as a PR) before its `<how-to-verify>` steps (inspecting `gh run view --log`, opening a throwaway-credential PR, confirming no leak in the live log/artifact) can be executed. This did not exist at the time this worktree ran, since worktree-executor commits stay on the per-agent branch until the orchestrator merges the wave.

**What's ready:** Both code changes (Task 1's TruffleHog job, Task 2's hook fix) are complete, committed, and locally verified as thoroughly as this isolated environment allows — the workflow YAML passes every structural `<verify>` grep (semantically, per the two documented pre-existing-imprecision caveats above), the redaction scrub is proven against a real TruffleHog record, and the hook fix is proven against a real out-of-tree worktree with a real (throwaway) staged credential. Once merged and pushed, Task 3 can be picked up by re-running this plan (or a dedicated checkpoint-resume flow) against the live branch.

---
*Phase: 10-ci-deploy-hardening*
*Completed: 2026-08-19 (Tasks 1-2 only; Task 3 pending)*
