---
phase: 10-ci-deploy-hardening
plan: 03
subsystem: infra
tags: [github-actions, ci, security-scan, nvd-api-key, dependency-check]

# Dependency graph
requires:
  - phase: 09-nonprod-continuous-deploy-scoped-credentials
    provides: "scoped production/staging GitHub Environments, repository-secret sweep"
provides:
  - "security-scan.yml action pins bumped to match deploy.yml (HARDEN-06)"
  - "security-scan.yml preflight converted to env: indirection (script-injection surface closed)"
  - "Live diagnostic evidence naming the NVD_API_KEY root cause as a genuinely-empty stored value"
affects: [ci-deploy-hardening, security-scan]

actuals:
  tokens: 1350
  tasks: 3
  commits: 4

tech-stack:
  added: []
  patterns:
    - "env:-indirected secret references in GitHub Actions run: steps, never textual ${{ }} interpolation"
    - "Non-disclosing diagnostic probe: byte length + non-printable count + two truncated (16-hex) SHA-256 digests, never the raw value or a full digest"

key-files:
  created: []
  modified:
    - .github/workflows/security-scan.yml

key-decisions:
  - "Diagnosed before attempting a blind fix (Approach B from the plan's trade-off matrix), per RESEARCH.md Pitfall 7 -- confirmed correct: a blind re-set would have worked, but only by accident, and would have destroyed the evidence of what was actually wrong."
  - "Diagnostic step bound to a differently-named env var (SECRET_VALUE, not NVD_API_KEY) so its own env: binding is textually distinct from the two load-bearing NVD_API_KEY bindings -- keeps the plan's own verify grep (`NVD_API_KEY: \${{ secrets.NVD_API_KEY }}` must print exactly 2) meaningful."

requirements-completed: [HARDEN-06]

coverage:
  - id: D1
    description: "NVD_API_KEY root cause named on live diagnostic evidence (genuinely empty stored value, not scope/whitespace/policy), remedied by the repository owner, and confirmed by an actually-green dependencyCheckAnalyze run with the diagnostic probe removed"
    requirement: HARDEN-06
    verification:
      - kind: other
        ref: "workflow_dispatch run 32278248354 (probe still present): 'NVD_API_KEY: variable is set and non-empty', byte length 36 -- dependencyCheckAnalyze completed, dependency-check-report artifact uploaded"
        status: pass
      - kind: other
        ref: "workflow_dispatch run 32280511632 (probe removed, commit 076b729): dependencyCheckAnalyze completed in 29s, dependency-check-report artifact uploaded (41777 bytes), no diagnostic step in the job list"
        status: pass
    human_judgment: true
    rationale: "Task 3 (checkpoint:human-verify, gate=blocking) required the repository owner to supply a real, confirmed-correct NVD API key -- a credential the executor cannot fabricate or obtain -- then re-verify live. The owner re-set the secret via gh secret set on 2026-08-19; both live confirmation runs above followed."

duration: ~35min (2 tasks initially, checkpoint resumed same day)
completed: 2026-08-19
status: complete
---

# Phase 10 Plan 03: security-scan.yml Version Bump + NVD_API_KEY Diagnosis Summary

**HARDEN-06 fully shipped: pin bump verified, and the folded NVD_API_KEY bug is diagnosed live (byte length 0, digest matched SHA-256("") exactly), remedied by the repository owner re-setting the secret, and confirmed by a second live run with the diagnostic probe removed.**

## Performance

- **Duration:** ~35 min (Tasks 1-2) + checkpoint resumed same day (Task 3)
- **Started:** 2026-08-19T15:06:00Z (approx, first commit attempt)
- **Completed:** 2026-08-19
- **Tasks:** 3 of 3 completed
- **Files modified:** 1 (`.github/workflows/security-scan.yml`)

## Accomplishments

- `security-scan.yml`'s `actions/checkout` and `actions/setup-java` pins bumped from v3/v4 to v5,
  now identical to `deploy.yml`'s `run-tests` job -- asserted mechanically (cross-file `diff` on
  the extracted pin set), not just applied once. Stale "not deploy.yml's adopt" comment (that
  divergence was fixed three phases ago) replaced with an accurate one.
- The preflight's `NVD_API_KEY` reference converted from textual `${{ }}` interpolation to `env:`
  indirection -- closes a real (if low-severity) script-injection surface and makes the `[ -z ]`
  test test genuine emptiness rather than whether substituted text happened to parse as shell.
- A temporary, non-disclosing diagnostic step added and **exercised twice live** via
  `workflow_dispatch` (runs `32269729257`, `32269993494`) against this plan's own worktree branch.
- **Root cause evidence gathered and named** (see "Diagnostic Evidence" below): the stored
  `NVD_API_KEY` value resolves to a genuinely empty string inside the job -- not a whitespace/
  encoding artifact, not an environment-scope migration, not an Actions policy restriction.

## Task Commits

Each task was committed atomically:

1. **Task 1: Bring security-scan.yml's action pins and comments up to date (HARDEN-06)** - `9a7c2d5` (fix)
2. **Task 2: Diagnose why NVD_API_KEY resolves empty, without disclosing it** - `48020fd` (fix), plus a same-task auto-fix `ea2a321` (fix) -- see Deviations below
3. **Task 3: Read the diagnosis, apply the remedy, remove the probe, and prove a green run** - `076b729` (fix)

**Plan metadata:** `docs(10-03): checkpoint -- Task 3 blocked on human action` (superseded by Task 3's completion)

Task 3 (`type="checkpoint:human-verify" gate="blocking"`) is now complete. The repository owner
re-set `NVD_API_KEY` via `gh secret set` on 2026-08-19, confirmed live via `workflow_dispatch` run
`32278248354` (byte length 36, non-empty), then the temporary diagnostic step was removed
(`076b729`) and a second confirming run (`32280511632`) completed green -- `dependencyCheckAnalyze`
finished in 29s and `dependency-check-report` (41777 bytes) was uploaded, with no diagnostic step
present in the job list.

## Files Created/Modified

- `.github/workflows/security-scan.yml` - Bumped `actions/checkout`/`actions/setup-java` to v5,
  replaced the stale divergence comment, converted the preflight to `env:` indirection, and added
  (still present, NOT yet removed) a temporary diagnostic step.

## Decisions Made

- Diagnosed before fixing (plan's Approach B), rather than blindly re-setting the secret. This
  paid off: the live evidence rules out three of the four candidate root causes cleanly, and the
  fourth (org/repo Actions policy) is also ruled out by `gh api .../actions/permissions` showing
  `allowed_actions: all` with no `sha_pinning_required`. What remains is "the stored value itself
  is empty," which a blind re-set would have fixed by accident without ever being confirmed as the
  actual cause.
- Diagnostic step's `env:` binding uses a distinct variable name (`SECRET_VALUE`) from the two
  load-bearing `NVD_API_KEY` bindings, keeping the plan's own cross-check grep meaningful (exactly
  2 occurrences of the literal `NVD_API_KEY: ${{ secrets.NVD_API_KEY }}` pattern, not 3).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Diagnostic step never ran on the first dispatched run -- missing `if: always()`**
- **Found during:** Task 2, first `workflow_dispatch` run (`32269729257`)
- **Issue:** The preflight step deliberately `exit 1`s when the secret is empty (that is its whole
  purpose). GitHub Actions skips every subsequent step by default once a step fails. The diagnostic
  step, placed immediately after the preflight with no `if:` condition, therefore never executed on
  the very run it exists to diagnose -- the job failed at the preflight and stopped, exactly as it
  always had, with zero new evidence gathered.
- **Fix:** Added `if: always()` to the diagnostic step only. This is a distinct GitHub Actions
  mechanism from `continue-on-error` -- it does not mask the preflight's failure or change the
  job's overall red/green verdict (the job still fails, correctly), it only exempts this one later
  step from being skipped. The plan's prohibition on `continue-on-error` is about not swallowing a
  genuine job failure; `if: always()` on a diagnostic-only step does not do that.
- **Files modified:** `.github/workflows/security-scan.yml`
- **Verification:** Second dispatched run (`32269993494`) shows the diagnostic step's own `env:`
  group and its four output lines present in the log, confirming it now executes despite the
  preceding preflight failure.
- **Committed in:** `ea2a321`

**2. [Process] Task 1 and Task 2's commits were interleaved in the working tree and had to be
   un-interleaved before committing**
- **Found during:** Between Task 1 and Task 2
- **Issue:** The first Task 1 commit attempt was killed by a tool-level 2-minute timeout partway
  through the pre-commit hook's `fastTest` run. The underlying Gradle daemon it spawned kept
  running in the background (killing the client process does not kill the daemon), holding a file
  lock on `build/test-results/fastTest/binary/output.bin` that caused every subsequent commit
  attempt to fail with "Unable to delete directory" for several minutes, even though nothing was
  actually broken. While waiting for that orphaned daemon to finish and release the lock, Task 2's
  edits were drafted and applied on top of Task 1's still-uncommitted edits in the same file.
- **Fix:** Once the lock cleared, Task 2's edits were temporarily reverted via a targeted `Edit`
  (not `git stash`, which is prohibited in worktree mode and would have crossed sibling worktrees'
  state anyway), Task 1 was committed alone, then Task 2's edits were reapplied and committed
  separately -- preserving one-commit-per-task atomicity despite the tooling delay.
- **Files modified:** `.github/workflows/security-scan.yml` (no net content difference from doing
  it in the "normal" order; only the commit sequencing was reconstructed)
- **Verification:** `git diff --cached --stat` confirmed exactly 10 changed lines before the Task 1
  commit (matching Task 1's scope alone) and exactly 46 lines before the Task 2 commit.
- **Committed in:** `9a7c2d5` (Task 1), `48020fd` (Task 2) -- not itself a separate commit, a
  sequencing correction only.

---

**Total deviations:** 1 auto-fixed bug (Rule 1) + 1 process correction (no code content change).
**Impact on plan:** The `if: always()` fix was necessary for Task 2 to actually deliver what it
promised (working diagnostic evidence). No scope creep -- both changes stay inside
`.github/workflows/security-scan.yml`, the plan's declared file scope.

## Known False-Positive Against the Plan's Own Verify Command

The plan's Task 1 and overall `<verification>` both specify:
`grep -cE 'continue-on-error' .github/workflows/security-scan.yml` must print `0`.

This grep has printed `1` since before this plan started, and still does after Task 1 and Task 2 --
**not because a `continue-on-error:` YAML key exists** (`grep -cE '^\s*continue-on-error:'` prints
`0`, confirmed), but because the file's own pre-existing, unmodified comment on the analyze step
literally contains the prose "No continue-on-error: true." explaining *why* the step has none. The
plan's grep is a textual match against a comment that documents the absence of the thing it is
checking for. This is not a regression introduced by this plan -- it predates Task 1's first edit
and is out of this plan's declared file-content scope to reword. Flagged here rather than silently
treated as a pass, per this project's CLAUDE.md verify-before-claiming directive.

## Diagnostic Evidence

Gathered per Task 2's action steps, before and via the live dispatched runs:

**`gh secret list` (repository scope):**
```
NVD_API_KEY	2026-08-13T17:47:43Z
```

**`gh secret list --env production`:** `NVD_API_KEY` is **absent** (DB_HOST, DB_NAME, DB_PASS,
DB_USER, DOCKERHUB_TOKEN, NETCUP_DEPLOY_USER, NETCUP_HOST, NETCUP_HOST_FINGERPRINT, NETCUP_SSH_KEY
only).

**`gh secret list --env staging`:** `NVD_API_KEY` is **absent** (same 9 names as production).

**`gh api repos/:owner/:repo/actions/permissions`:**
```json
{"enabled":true,"allowed_actions":"all","sha_pinning_required":false}
```
No restrictive policy.

**Analyze step's `env:` block indentation:** visually confirmed a sibling of `run:` under the step
(2-space-consistent, matching the preflight's own `env:` block) -- not mis-scoped to the wrong step.

**Live diagnostic run 1** (`32269729257`, before the `if: always()` fix): preflight's own `env:`
group printed `NVD_API_KEY: ` (empty) and failed as designed; the diagnostic step did not run at
all (see Deviations #1).

**Live diagnostic run 2** (`32269993494`, after the fix):
```
NVD_API_KEY: variable is SET but resolves to an empty string.
NVD_API_KEY byte length: 0
NVD_API_KEY non-printable character count: 0
NVD_API_KEY truncated digest (raw value): e3b0c44298fc1c14
NVD_API_KEY truncated digest (whitespace-stripped): e3b0c44298fc1c14
```
`e3b0c44298fc1c14` is the first 16 hex characters of `sha256("")` -- the empty-string hash. Both
digests being identical and equal to the empty-string hash independently confirms zero-length,
consistent with the byte-length figure.

**Hypothesis assessment (against the plan's four named hypotheses):**
- *Environment-scope migration* -- **ruled out.** `NVD_API_KEY` does not exist at `production` or
  `staging` environment scope either; there is nothing for the job to have "missed" by not
  declaring an `environment:`.
- *Surrounding whitespace or non-printable characters* -- **ruled out.** Byte length is exactly 0
  and the non-printable count is 0; both digests match exactly, meaning stripping whitespace
  changed nothing (there was nothing to strip).
- *Genuinely empty value* -- **supported by direct evidence.** The value GitHub delivers into this
  job's shell environment is a zero-byte string, matching the empty-string hash exactly, not a
  near-miss or corrupted-but-present value.
- *Org/repo Actions policy* -- **ruled out.** `allowed_actions: all`, no `sha_pinning_required`, no
  restriction that would explain a specific secret resolving empty while every other repository
  secret (visible via `gh secret list --env production`/`--env staging`) apparently resolves fine
  for its own jobs.

**Conclusion:** the evidence points at "genuinely empty value," but with an important caveat this
plan does not resolve on its own: `gh secret list` shows `NVD_API_KEY` was last set 2026-08-13 and
has not been touched since (unchanged `updatedAt` across this entire failure window, confirmed in
the original todo). If the value set on 2026-08-13 was intended to be non-empty, then either (a)
`gh secret set` silently stored an empty value that day (a genuine GitHub-side defect for this
specific secret, matching the todo's own leading candidate), or (b) whatever was piped into
`gh secret set` on 2026-08-13 was itself already empty at that point (an operator-side mistake,
not a platform defect). This plan's diagnostic cannot distinguish (a) from (b) -- both produce
identical symptoms from inside the job. Task 3 requires the repository owner to re-set the secret
with a freshly confirmed-non-empty value and observe whether that alone resolves it, which is the
only test that can further separate these two remaining explanations.

## Issues Encountered

- A Windows-specific Gradle daemon file-lock issue (documented in Deviations #2) cost real wall-
  clock time working around, not a code problem. Recorded here rather than in Deviations because it
  produced no code content change, only a commit-sequencing correction.

## User Setup Required

**External action requires manual configuration -- this is exactly what Task 3 (blocked) covers.**
The repository owner must:
1. Read this SUMMARY's "Diagnostic Evidence" section.
2. Obtain a confirmed-correct, confirmed-non-empty NVD API key value (request a fresh one at
   https://nvd.nist.gov/developers/request-an-api-key if the original 2026-08-13 value's
   correctness cannot be independently confirmed).
3. `gh secret set NVD_API_KEY` with that value, piped in rather than pasted (per the plan's own
   Task 2 action text, to avoid shell-history/whitespace contamination).
4. Have the executor remove the temporary "TEMPORARY -- Diagnose NVD_API_KEY resolution" step from
   `.github/workflows/security-scan.yml` and commit that removal -- the fix ships, the probe does
   not.
5. Re-dispatch: `gh workflow run security-scan.yml` on this branch (or after merge, on `master`),
   then confirm `dependencyCheckAnalyze` completes and the `dependency-check-report` artifact
   uploads. A cold NVD database population can be slow on the very first successful run -- the
   job's 45-minute timeout is the bound, and a timeout there is a *different* finding from the one
   this plan diagnosed.
6. Confirm `.planning/todos/pending/2026-08-19-security-scan-yml-nvd-api-key-not-resolving.md` is
   resolvable with the actual named cause, not a workaround.

## Next Phase Readiness

**Plan complete.** All three tasks are committed to `master` (`9a7c2d5`, `48020fd`, `ea2a321`,
`076b729`) and pushed to `origin/worktree-agent-aecc1700a29b0fce5` for live verification.
`.planning/todos/pending/2026-08-19-security-scan-yml-nvd-api-key-not-resolving.md` moved to
`.planning/todos/completed/` with the named root cause and remedy recorded. HARDEN-06 marked
complete in `.planning/REQUIREMENTS.md`.

10-04 (blocked on this plan via `depends_on: [10-01, 10-03]`) is now unblocked.

---
*Phase: 10-ci-deploy-hardening*
*Completed: 2026-08-19*
