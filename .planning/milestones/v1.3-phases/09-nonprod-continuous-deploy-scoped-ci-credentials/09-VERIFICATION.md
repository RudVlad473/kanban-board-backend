---
phase: 09-nonprod-continuous-deploy-scoped-ci-credentials
verified: 2026-08-19T12:11:51Z
status: passed
score: 6/6 must-haves verified
behavior_unverified: 0
overrides_applied: 0
---

# Phase 9: Nonprod Continuous Deploy & Scoped CI Credentials Verification Report

**Phase Goal:** Nonprod stays current with master automatically — deployed, migrated, health-verified,
and schema-registered by CI on every push, with the same automated step keeping production's and
nonprod's Avro schema registries in step with the deployed code — through credentials scoped so the
nonprod path cannot reach, overwrite, or degrade production.
**Verified:** 2026-08-19T12:11:51Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (ROADMAP Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | A push to master leaves nonprod running that commit's image, deployed within the same workflow run as production — neither job waits on, gates, or fails because of the other | VERIFIED | Live run `32248814231` (HEAD `6b907f9`): `deploy-to-netcup` and `deploy-to-nonprod` both `success` in one run. `deploy-to-nonprod.needs = [build-and-push-docker-image, flyway-verify-nonprod]`; `deploy-to-netcup.needs = [build-and-push-docker-image, flyway-verify]` — no cross-reference. Concurrency groups differ (`deploy-to-netcup-vm` vs `deploy-to-nonprod-vm`). Docker Hub confirms both repos hold exactly tag `6b907f9` (current commit) live. |
| 2 | The nonprod deploy is reported successful only after nonprod's health endpoint actually answers 200; an unreachable stack fails the workflow visibly | VERIFIED | `health-check-nonprod` job exists, no `continue-on-error`/`\|\| true` on the terminal check, `exit 1` + `::error::` after 30x10s bound. Live green path observed (`Nonprod healthy after 2/30 attempts`, runs `32233904310`/`32236428721`). Live red path deliberately induced (run `32235116988`/`32242756450` family): full bound exhausted, job+run conclusion `failure`, then reverted and re-verified green. `cleanup-old-images-nonprod` is gated on `health-check-nonprod`, not on the deploy job. `curl` to the live nonprod health endpoint returns `200` now. |
| 3 | The nonprod deploy job can read only staging-scoped credentials; production's secrets are unreachable — both environments scoped via GitHub Environments, not shared repository secrets | VERIFIED | Live `gh secret list` (repository scope) returns exactly `NVD_API_KEY` — the nine deploy secrets no longer exist at repository scope. `gh secret list --env production`/`--env staging` each return the same nine names with (by construction) per-environment values. Mechanical check: every `deploy.yml` job that interpolates `secrets.*` also declares an `environment:` (verified: `secrets-jobs` ⊆ `env-jobs`, 8/8 match). `deploy-to-nonprod` declares `environment: staging`; production jobs declare `environment: production`. |
| 4 | A production deploy and a nonprod deploy back to back leave both stacks on their own correct image; neither cleanup sweep can delete the other's currently-running tag | VERIFIED | Code read: `cleanup-old-images`/`cleanup-unused-image` interpolate only `needs.setup.outputs.base_image_name`; `cleanup-old-images-nonprod`/`cleanup-unused-image-nonprod` interpolate only `base_image_name_nonprod` — no hand-typed repository segment anywhere. Live: both Docker Hub repositories (`kanban-board-backend`, `kanban-board-backend-nonprod`) currently list exactly one tag each — `6b907f9` — proving the retention sweeps genuinely prune to the current tag only and neither has touched the other's repository. |
| 5 | A push that introduces/changes an Avro schema registers it in both registries automatically; an incompatible schema fails the deploy visibly | VERIFIED | `register-schemas-production` (own job, `environment: production`, `needs: [deploy-to-netcup, build-and-push-docker-image]`, registry URL `http://redpanda:8081`) and a registration step inside `deploy-to-nonprod`'s own script (strictly between `up -d redpanda-nonprod` and `up -d app-nonprod`, registry URL `http://redpanda-nonprod:8081`) both reuse `AvroSchemaRegistrar`/`PropertiesLauncher` verbatim (`git diff` confirms zero changes to `AvroSchemaRegistrar.java` across plan 09-03's commits). Live green (`Registered 14 Avro schemas against <url>` on both, both brokers report 14 subjects). Live red path genuinely proven, including a real bug found and fixed mid-verification: `appleboy/ssh-action` has no built-in fail-fast, so `set -e` was missing at first (two failed red-path attempts showed `app-nonprod` starting anyway despite the registrar's non-zero exit); fixed by adding `set -e` as the literal first line of all three `appleboy/ssh-action` scripts (confirmed present in the current file at lines 259, 324, 399) — third attempt correctly reddened the job and left `kanban-nonprod-app` untouched. |
| 6 | The generated OpenAPI spec declares the `ProblemDetail` envelope on every operation, enforced centrally, guarded by an automated check | VERIFIED | `ProblemDetailOpenApiCustomizer` (`@Component implements GlobalOpenApiCustomizer`) attaches all six codes to every operation via a conditional insert; schema is hand-built (never reflected), `code` enum derived from `ErrorCode.values()`. Guard test independently **re-run in this verification session** (`./gradlew test --tests ProblemDetailOpenApiCustomizerTest`): both nested classes report `tests="3" ... failures="0" errors="0"` and `tests="4" ... failures="0" errors="0"` — 7/7 pass, including the three cross-producer fidelity checks (404/400/401) and the operation-count floor. `grep` confirms no `io.swagger.v3.oas.annotations` import anywhere under `controller/`/`security/` (D-08 mechanism honored). `./gradlew spotlessCheck` passes. |

**Score:** 6/6 truths verified (0 present-but-behavior-unverified)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `.github/workflows/deploy.yml` | 13 jobs: setup, run-tests, build-and-push-docker-image, flyway-verify(-nonprod), deploy-to-netcup/nonprod, register-schemas-production, health-check-nonprod, cleanup-old-images(-nonprod), cleanup-unused-image(-nonprod) | VERIFIED | All 13 job ids present and confirmed running/skipping correctly in live run `32248814231`. |
| `docs/INFRA_RUNBOOK.md` | Sections for plans 09-01/09-02/09-03 | VERIFIED | `## Nonprod CI deploy identity...— Plan 09-01`, `## Nonprod CI health gate and image retention — Plan 09-02`, `## Automated Avro schema registration — Plan 09-03` all present (grep-confirmed). |
| GitHub Environments `production`/`staging` | Zero protection rules, 9 secrets each | VERIFIED | Live `gh api .../environments` → 2 environments, `rules: 0` each; `gh secret list --env <name>` → 9 identical names each. |
| Docker Hub repo `kanban-board-backend-nonprod` | Public, separate from production | VERIFIED | Live `is_private: false`; both repos independently hold their own current-commit tag. |
| `ProblemDetailOpenApiCustomizer.java` / `ProblemDetailOpenApiCustomizerTest.java` | Global customizer + regression guard | VERIFIED | Read in full; re-ran the guard test independently — 7/7 pass. |
| `docs/ARCHITECTURE.md` | Names the customizer + its test | VERIFIED | Both names present, adjacent to the existing 401/403 bullet. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| `deploy-to-nonprod` | `staging`-scoped `NETCUP_*` | environment declaration | WIRED | `environment: staging` on the job; live SSH deploy landed in `/opt/deploy/kanban-board-nonprod/` per SUMMARY, confirmed indirectly by nonprod's tag/health being live-correct. |
| `build-and-push-docker-image.outputs.*` | both deploy jobs' `IMAGE_TAG` | `needs.build-and-push-docker-image.outputs.image_tag` | WIRED | Present in both scripts; live tags on Docker Hub match the pushed commit SHA exactly. |
| `deploy-to-nonprod` SSH script | `AvroSchemaRegistrar` invocation | ordered between `up -d redpanda-nonprod` and `up -d app-nonprod` | WIRED | Confirmed by direct file read (lines 426-432) — registrar line sits strictly between the two `up -d` lines. |
| `health-check-nonprod` | `cleanup-old-images-nonprod` | `needs: [health-check-nonprod, ...]` | WIRED | Confirmed by file read (line 580) — not gated on `deploy-to-nonprod`. |
| `register-schemas-production` | production identity | `environment: production`, `needs: [deploy-to-netcup, build-and-push-docker-image]` | WIRED | Confirmed by file read (lines 303-310) — no nonprod node in `needs:`. |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| CI-01 | 09-01 | Parallel nonprod deploy on every push | SATISFIED | Job graph + live run |
| CI-02 | 09-01, 09-02 | GitHub Environments scoping, repo secrets swept | SATISFIED | Live `gh secret list` = `NVD_API_KEY` only; both environments populated |
| CI-03 | 09-01, 09-02 | Separate Docker Hub repository + isolated retention | SATISFIED | Live repo separation + single-current-tag proof |
| CI-04 | 09-02 | Bounded health gate | SATISFIED | Live green + red path |
| CI-05 | 09-03 | Automated dual-registry Avro schema registration | SATISFIED | Live green + red path (incl. real `set -e` bug found/fixed) |
| API-01 | 09-04 | OpenAPI declares ProblemDetail on every operation | SATISFIED | Guard test re-run, 7/7 pass |

No orphaned requirements — `REQUIREMENTS.md`'s Phase 9 row set (`CI-01..05`, `API-01`) is exactly the union of the four plans' `requirements:` frontmatter fields.

### Anti-Patterns Found

None. `grep` for `TBD|FIXME|XXX|TODO|HACK|PLACEHOLDER|not yet implemented|coming soon` across `deploy.yml` and the two new API-01 source files returned no matches. No hardcoded empty-data stubs in the customizer (schema is hand-built with real content, not a stub reflection).

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Repository secrets reduced to one entry | `gh secret list` | `NVD_API_KEY` | PASS |
| Both GitHub Environments exist, unprotected | `gh api .../environments` | 2 envs, 0 rules each | PASS |
| Latest live workflow run green with correct job set | `gh run view 32248814231` | 11 success + 2 correctly-skipped (`if: failure()` jobs) | PASS |
| Production health endpoint | `curl .../actuator/health` | 200 | PASS |
| Nonprod health endpoint | `curl .../actuator/health` | 200 | PASS |
| Docker Hub repository separation | `curl hub.docker.com/v2/repositories/.../` x2 | both public, both list exactly current-commit tag | PASS |
| API-01 guard test (independently re-run, not just SUMMARY-trusted) | `./gradlew test --tests ProblemDetailOpenApiCustomizerTest` | 7/7 pass (`failures="0" errors="0"` in both nested classes) | PASS |
| `spotlessCheck` | `./gradlew spotlessCheck` | BUILD SUCCESSFUL | PASS |
| `set -e` present in all three `appleboy/ssh-action` scripts | `grep -n "set -e" deploy.yml` | lines 259, 324, 399 (first line of each script) | PASS |
| No per-endpoint swagger annotations (D-08) | `grep -rl io.swagger.v3.oas.annotations controller/ security/` | no matches | PASS |
| No source drift on 09-03/09-04 "never touch" files | `git diff --name-only` over the relevant commit ranges | empty | PASS |

### Probe Execution

No `scripts/*/tests/probe-*.sh` convention exists in this repository and none is declared by this phase's plans. SKIPPED — not applicable to this CI/infra + API-contract phase.

### Human Verification Required

None. Every must-have truth resolved to VERIFIED with either direct live-infrastructure evidence
(current `gh`/`curl`/Docker Hub API state, matching the SUMMARY's claimed run IDs and outcomes) or an
independently re-executed test (the API-01 guard, run fresh in this verification session rather than
trusted from the SUMMARY). No truth in this phase asserts a state-transition/cancellation invariant
that live evidence could not directly confirm.

### Gaps Summary

None. All 6 ROADMAP success criteria, all 6 requirement IDs (CI-01 through CI-05, API-01), and every
plan's `must_haves.artifacts`/`key_links`/`prohibitions` checked in this pass hold against the live
codebase and live infrastructure state — not merely against SUMMARY.md prose. Two real defects that
were found and fixed *during* the phase's own live verification (the `NETCUP_HOST_FINGERPRINT`
ECDSA-vs-ED25519 mismatch in 09-01, and the missing `set -e` in `appleboy/ssh-action` scripts found in
09-03) are visibly present in their fixed form in the current file and were independently confirmed
here, not merely re-asserted from the SUMMARY text.

---

_Verified: 2026-08-19T12:11:51Z_
_Verifier: Claude (gsd-verifier)_
