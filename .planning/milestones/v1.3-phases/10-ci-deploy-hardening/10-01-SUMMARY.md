---
phase: 10-ci-deploy-hardening
plan: 01
subsystem: infra
tags: [github-actions, dependabot, supply-chain, digest-pinning, gradle, docker-hub, ci-cd]

# Dependency graph
requires:
  - phase: 09-nonprod-continuous-deploy
    provides: settled deploy.yml job graph (production + nonprod jobs, environments, concurrency groups) this plan edits in place
provides:
  - Six appleboy/* uses: sites in deploy.yml -- 5 actually present, all digest-pinned to immutable 40-hex commit SHAs with version comments
  - First-party-action risk-acceptance comment block in deploy.yml (D-05)
  - .github/dependabot.yml github-actions ecosystem entry composing with the digest pins (D-07)
  - cache: 'gradle' on run-tests' Set up Java step
  - HTTP-status-checked, warn-only DELETE in production's cleanup-unused-image job
affects: [10-04-gradle-supply-chain-hardening]

# Actuals (#2632) — pairs with the plan's `estimate` to calibrate future estimates.
actuals:
  tokens: 1894
  tasks: 3
  commits: 3

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Digest-pin third-party GitHub Actions (@<40-hex-sha>  # v<version>) that hold production credentials; leave first-party GitHub/Docker actions tag-trusted with an explicit in-file risk-acceptance comment"
    - "curl -w '%{http_code}' + case status-branch pattern for warn-only observability on a destructive HTTP call inside an if: failure() cleanup job"

key-files:
  created: []
  modified:
    - .github/workflows/deploy.yml
    - .github/dependabot.yml

key-decisions:
  - "Pinned only the 5 appleboy/* call sites that actually exist in deploy.yml (2 scp-action + 3 ssh-action), not the 6 the plan's must_haves/verify text expected -- the plan's own task prose correctly enumerated 2+3=5; only the aggregate/grep target number was wrong. Documented as a deviation rather than fabricating a 6th call site."
  - "Live re-verified both appleboy SHAs via gh api at execution time (not copied from RESEARCH.md) -- both matched the plan's recorded values exactly, confirming no drift since research."
  - "Deferred Task 1's real-push-to-master + gh run watch end-to-end proof to post-merge: this plan executes in an isolated worktree with no push-to-master authority (Plan 10-04, wave 2, further edits deploy.yml and depends on this plan), so a meaningful end-to-end deploy proof can only run once all deploy.yml changes across the phase are merged."
  - "Task 4's Dependabot-UI checkpoint (gate=\"blocking\", not \"blocking-human\") auto-approved per workflow.auto_advance=true in config.json -- the GitHub Dependabot 'Check for updates' UI flow has no CLI/API equivalent this agent can drive, so its D-07 composition proof also remains a post-merge follow-up (both edge-case truths for this were themselves tagged 'verification: backstop' in the plan's own must_haves)."

requirements-completed: [HARDEN-01, HARDEN-03, HARDEN-04]

coverage:
  - id: D1
    description: "All 5 real appleboy/scp-action and appleboy/ssh-action uses: sites in deploy.yml pinned to live-verified 40-hex commit digests with version comments; zero tag-referenced appleboy actions remain"
    requirement: "HARDEN-03"
    verification:
      - kind: other
        ref: "grep -cE 'uses: appleboy/(scp|ssh)-action@[0-9a-f]{40}' .github/workflows/deploy.yml -> 5; grep -cE 'uses: *appleboy/[a-z-]+-action@v' .github/workflows/deploy.yml -> 0"
        status: pass
    human_judgment: false
  - id: D2
    description: "First-party GitHub/Docker actions left tag-trusted with an explicit, reasoned risk-acceptance comment block citing HARDEN-03/D-05"
    requirement: "HARDEN-03"
    verification:
      - kind: other
        ref: "grep -cE 'HARDEN-03' .github/workflows/deploy.yml -> 1 (comment only, verified outside-comment count is 0)"
        status: pass
    human_judgment: false
  - id: D3
    description: "A real master push deploys production and nonprod green through the pinned actions"
    requirement: "HARDEN-03"
    verification: []
    human_judgment: true
    rationale: "This worktree-isolated agent has no authority to push directly to master (Plan 10-04, wave 2, depends on this plan and further edits deploy.yml) -- a meaningful end-to-end deploy proof requires the full phase's deploy.yml changes to be merged first. Deferred to a post-merge verification pass."
  - id: D4
    description: "Dependabot's github-actions ecosystem entry added, mirroring the existing gradle entry's noise-bound convention; stale Phase-5-deferral comment retired"
    requirement: "HARDEN-01"
    verification:
      - kind: other
        ref: "grep -cE '^ *- package-ecosystem:' .github/dependabot.yml -> 2; grep -cE 'package-ecosystem: \"github-actions\"' .github/dependabot.yml -> 1; grep -cE 'Phase 5' .github/dependabot.yml -> 0"
        status: pass
    human_judgment: false
  - id: D5
    description: "Dependabot's github-actions ecosystem entry correctly parses the digest-pinned appleboy/* references (D-07 composition) -- observed live via the GitHub UI's Check for updates log"
    requirement: "HARDEN-01"
    verification: []
    human_judgment: true
    rationale: "GitHub's Dependabot 'Check for updates' flow and its per-run log are UI-only with no CLI/API equivalent this agent can drive. The plan's own must_haves tag this specific edge case 'verification: backstop'. Auto-approved per workflow.auto_advance=true (gate=\"blocking\", not \"blocking-human\"); genuine human/UI confirmation remains a recommended follow-up."
  - id: D6
    description: "run-tests' Set up Java step caches Gradle dependencies (cache: 'gradle'), setup-java@v5 pin unchanged"
    requirement: "HARDEN-04"
    verification:
      - kind: other
        ref: "grep -cE \"cache: 'gradle'\" .github/workflows/deploy.yml -> 1; grep -v '^ *#' .github/workflows/deploy.yml | grep -cE 'setup-java@v5' -> 1"
        status: pass
    human_judgment: false
  - id: D7
    description: "Production's cleanup-unused-image job captures and branches on the manifest DELETE's HTTP status, warning (never erroring) on non-2xx"
    verification:
      - kind: other
        ref: "grep -cE 'DELETE_HTTP_STATUS' .github/workflows/deploy.yml -> 14 (>= 4 required); grep -cE '::error::.*manifest delete' .github/workflows/deploy.yml -> 0"
        status: pass
    human_judgment: false

# Metrics
duration: 40min
completed: 2026-08-19
status: complete
---

# Phase 10 Plan 01: Digest-Pin, Dependabot, Gradle Cache, Cleanup Status Summary

**Digest-pinned all 5 real `appleboy/*` deploy-workflow call sites to live-verified commit SHAs, documented the deliberate first-party tag-trust non-pin, wired Dependabot's `github-actions` ecosystem to keep those pins maintained, added a Gradle build cache to `run-tests`, and gave production's image-cleanup DELETE a warn-only HTTP status check.**

## Performance

- **Duration:** ~40 min
- **Tasks:** 3 of 4 (Task 4 was a checkpoint, auto-approved per auto mode)
- **Files modified:** 2 (`.github/workflows/deploy.yml`, `.github/dependabot.yml`)

## Accomplishments
- All 5 real `appleboy/scp-action`/`appleboy/ssh-action` `uses:` sites in `deploy.yml` (`deploy-to-netcup`, `register-schemas-production`, `deploy-to-nonprod`) now pin to a live `gh api`-verified 40-hex commit SHA with a `# v<version>` comment, matching `secret-scan.yml`'s existing pinned-gitleaks precedent exactly.
- A first-party risk-acceptance comment block near the top of `deploy.yml` names all six tag-trusted first-party actions (`actions/checkout`, `actions/setup-java`, `actions/cache`, `actions/upload-artifact`, `docker/setup-buildx-action`, `docker/build-push-action`), cites HARDEN-03/D-05, and states the SSH-key blast-radius reasoning for the narrower pin scope.
- `.github/dependabot.yml` gained a second `updates:` entry for `package-ecosystem: "github-actions"` (weekly, `open-pull-requests-limit: 5`, matching the existing `gradle` entry's shape), and its three-phases-stale "Phase 5 is rewriting deploy.yml" deferral comment was replaced with one explaining the entry now exists to keep the new digest pins maintained (D-07).
- `run-tests`' `Set up Java` step gained `cache: 'gradle'` (matching `security-scan.yml`'s existing usage), with a comment recording why the default cache-key inputs already cover this repo's dependency graph.
- Production's `cleanup-unused-image` job now captures its manifest-DELETE HTTP status and branches on it with a `case`, mirroring `cleanup-unused-image-nonprod`'s already-fixed shape exactly (distinct `/tmp` response-body filename) -- warn-only, since this job runs under `if: failure()` and turning it red would mask the original deploy failure it exists to clean up after.

## Task Commits

Each task was committed atomically:

1. **Task 1: Digest-pin both appleboy actions end-to-end, proven by a real deploy** - `0bc1ca4` (feat)
2. **Task 2: Add the github-actions ecosystem to Dependabot and retire its stale deferral comment** - `ca81a21` (feat)
3. **Task 3: Gradle cache on run-tests, and a DELETE status check in cleanup-unused-image** - `5cee51d` (feat)

Task 4 (checkpoint:human-verify, gate="blocking") was auto-approved per `workflow.auto_advance=true` -- see Deviations below.

## Files Created/Modified
- `.github/workflows/deploy.yml` - Digest-pinned 5 `appleboy/*` call sites, added first-party risk-acceptance comment, `cache: 'gradle'` on `run-tests`, DELETE status check on `cleanup-unused-image`
- `.github/dependabot.yml` - Added `github-actions` ecosystem entry, replaced stale deferral comment

## Decisions Made
- Pinned all 5 real `appleboy/*` call sites (not the plan's stated 6 -- a plan arithmetic defect, see Deviations).
- Live-verified both commit SHAs via `gh api` rather than trusting RESEARCH.md's recorded values (they matched exactly: `ff85246acaad7bdce478db94a363cd2bf7c90345` for `scp-action@v1.0.0`, `0ff4204d59e8e51228ff73bce53f80d53301dee2` for `ssh-action@v1.2.5`).
- Deferred the tracer's real-push-to-master end-to-end deploy proof to a post-merge verification pass -- this worktree-isolated agent has no authority to push to `master`, and Plan 10-04 (wave 2) further edits `deploy.yml`, so a meaningful proof needs the full phase's changes merged first.
- Auto-approved Task 4's Dependabot-UI checkpoint per `workflow.auto_advance=true` (its `gate="blocking"`, not `"blocking-human"`, is not in the auto-approval carve-out).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Plan's `appleboy/*` call-site count was internally inconsistent (6 stated, 5 real)**
- **Found during:** Task 1
- **Issue:** The plan's `must_haves.truths`, `<action>` prose, and `<verify>` grep target all assert "six" `appleboy/*` call sites, but the task's own enumeration ("scp-action appears at two call sites... ssh-action appears at three call sites") sums to 5, and a fresh grep of the actual file confirmed exactly 5 (`deploy-to-netcup`: 1 scp + 1 ssh; `register-schemas-production`: 1 ssh; `deploy-to-nonprod`: 1 scp + 1 ssh).
- **Fix:** Pinned all 5 real call sites to live-verified digests; did not fabricate a nonexistent 6th site. Adjusted the acceptance-check expectation from 6 to 5 when running the plan's own verify grep.
- **Files modified:** `.github/workflows/deploy.yml`
- **Verification:** `grep -cE 'uses: appleboy/(scp|ssh)-action@[0-9a-f]{40}' .github/workflows/deploy.yml` prints `5`; `grep -cE 'uses: *appleboy/[a-z-]+-action@v' .github/workflows/deploy.yml` prints `0` (zero tag-referenced appleboy actions survive).
- **Committed in:** `0bc1ca4` (Task 1 commit)

**2. [Rule 1 - Bug] First dependabot.yml comment draft accidentally doubled the "github-actions" ecosystem grep match**
- **Found during:** Task 2
- **Issue:** The first replacement comment for the stale Phase-5 deferral text quoted the literal string `` `package-ecosystem: "github-actions"` `` inline, which made `grep -cE 'package-ecosystem: "github-actions"' .github/dependabot.yml` print `2` (comment + real entry) instead of the plan's expected `1`.
- **Fix:** Reworded the comment to say "the github-actions ecosystem entry below" instead of repeating the literal YAML key/value string.
- **Files modified:** `.github/dependabot.yml`
- **Verification:** `grep -cE 'package-ecosystem: "github-actions"' .github/dependabot.yml` now prints `1`.
- **Committed in:** `ca81a21` (Task 2 commit)

**3. [Rule 3 - Blocking] Two orphaned Gradle test-worker JVMs from an earlier killed commit attempt held a file lock, failing the pre-commit hook's `fastTest`**
- **Found during:** Task 1's first two commit attempts
- **Issue:** An initial `git commit` invocation timed out (2 min) while Gradle was still cold-starting its daemon for the pre-commit hook's `spotlessCheck`/`fastTest`. The killed shell left two orphaned `java.exe` processes (`gradlew` wrapper main + a `Gradle Test Executor`) holding an open handle on `build/test-results/fastTest/binary/output.bin`, causing every subsequent commit attempt to fail with `Unable to delete directory ... Failed to delete some children` (Windows file-lock, not a code/test correctness issue).
- **Fix:** Identified the orphaned PIDs via `wmic process where "name='java.exe'" get processid,commandline` (matching this worktree's path in the command line), terminated them with `taskkill /F`, confirmed the lock cleared, then retried the commit in the background with a generous timeout -- consistent with `docs/SESSION_LESSONS.md` lessons 2 and 5 (give cold Gradle daemon/Testcontainers runs a generous timeout; verify a slow run by direct process/container inspection rather than assuming a hang).
- **Files modified:** none (environment cleanup only)
- **Verification:** `rm -f build/test-results/fastTest/binary/output.bin` succeeded after the kill (previously "Device or resource busy"); the retried commit's `fastTest` run completed successfully, confirmed live by `docker ps --filter label=org.testcontainers` showing real Postgres/Redpanda/Ryuk containers up during the run.
- **Committed in:** `0bc1ca4` (Task 1 commit, after the environment fix)

---

**Total deviations:** 3 auto-fixed (2 plan-defect corrections under Rule 1, 1 blocking-environment fix under Rule 3)
**Impact on plan:** All three were necessary to complete the plan correctly; none changed the plan's intent or scope. The 6-vs-5 count correction is the only one with lasting documentation impact (the plan's own `must_haves`/`<verification>` text should be read as "5", not "6", for this repo).

## Issues Encountered

**Task 1's tracer end-to-end proof (real push to `master` + `gh run watch` confirming `deploy-to-netcup`/`deploy-to-nonprod`/`register-schemas-production` all succeed) was not executed.** This plan runs in a git worktree isolated from `master` with no push authority of its own -- pushing directly to `master` from a wave-1 worktree agent would bypass the orchestrator's merge process and risk colliding with the other wave-1 plans (10-02, 10-03) also editing adjacent CI files. Plan 10-04 (wave 2) depends on this plan and further edits `deploy.yml`, so a genuinely meaningful end-to-end deploy proof can only run once the full phase's `deploy.yml` changes are merged to `master`. All of Task 1's *source* assertions (digest format, no remaining tag refs, diff scope, risk-acceptance comment presence) were run and passed; the live-deploy proof is deferred to a post-merge verification pass, consistent with this same plan's own "backstop"-tagged edge-case truths for HARDEN-04.

**Task 4's checkpoint (confirming Dependabot's `github-actions` "Check for updates" log parses the new digest pins without error, D-07) was auto-approved rather than manually verified.** `workflow.auto_advance=true` in `.planning/config.json` puts this session in auto mode; per the executor's checkpoint protocol, a `checkpoint:human-verify` task auto-approves unless its `gate` is `"blocking-human"` or its purpose is package-legitimacy verification -- this task's `gate="blocking"` does not meet either carve-out. Separately, GitHub's Dependabot "Check for updates" UI flow and its per-run log have no CLI/API equivalent this agent could drive even outside auto mode. Both `must_haves.truths` entries covering this composition (D-07's PR-bump behavior, and both HARDEN-04 cache edge cases) are themselves tagged `verification: backstop` in the plan, i.e. expected to be confirmed by a later pass rather than synchronously by this executor. Recorded as a recommended follow-up: after merge, open GitHub -> Insights -> Dependency graph -> Dependabot, confirm a `github-actions` row appears, click "Check for updates" on it, and confirm the log resolves both `appleboy/scp-action` and `appleboy/ssh-action` to their pinned SHA without a parse error.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Ready for merge into the wave-1 set (alongside 10-02, 10-03). Plan 10-04 (wave 2) depends on this plan and on 10-03, and further edits `deploy.yml` (wrapper-validation, verification-metadata) -- once merged, that is also the right point to run this plan's two deferred live-verification items (real master-push deploy proof; Dependabot UI check-for-updates confirmation).
- No blockers to merge: all 3 code tasks committed cleanly, pre-commit hook (gitleaks scan, `spotlessCheck`, `fastTest`) passed on every commit, and all of this plan's automated source assertions pass against the actual file contents (adjusted for the 5-vs-6 count correction documented above).

## Self-Check: PASSED

- FOUND: `.github/workflows/deploy.yml`
- FOUND: `.github/dependabot.yml`
- FOUND: `.planning/phases/10-ci-deploy-hardening/10-01-SUMMARY.md`
- FOUND commit `0bc1ca4` (Task 1)
- FOUND commit `ca81a21` (Task 2)
- FOUND commit `5cee51d` (Task 3)

---
*Phase: 10-ci-deploy-hardening*
*Completed: 2026-08-19*
