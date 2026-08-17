---
phase: 05-infra-migration
plan: 05
subsystem: infra
tags: [github-actions, flyway, ssh-deploy, docker-compose, netcup, neon]

# Dependency graph
requires:
  - phase: 05-infra-migration (plan 05-04)
    provides: a hand-verified, live production stack (Netcup VM, Neon, Redpanda, Caddy) proving the sequence this plan automates
provides:
  - "A CI job (`flyway-verify`) that applies this project's Flyway migrations against Neon's direct endpoint and gates deploy"
  - "A rewritten deploy job (`deploy-to-netcup`) that builds, verifies schema, and deploys to the real Netcup VM over a fingerprint-pinned SSH connection on every push to master"
  - "Both Docker Hub cleanup jobs restored to fire on real success/failure instead of permanently skipping"
affects: [05-06]

# Actuals (#2632)
actuals:
  tokens: 11384
  tasks: 2
  commits: 8

tech-stack:
  added: []
  patterns:
    - "flyway/flyway:11.7.2 CLI Docker image run directly against checked-out migration scripts (no Spring context boot) for CI-side migration verification"
    - "appleboy/scp-action + appleboy/ssh-action with fingerprint pinning, replacing hand-rolled ssh heredoc with no host-key verification"
    - "Workflow-level concurrency group (deploy-to-netcup-vm, cancel-in-progress: false) serializing deploys against one VM"

key-files:
  created: []
  modified:
    - .github/workflows/deploy.yml
    - docs/INFRA_RUNBOOK.md
    - .planning/todos/completed/2026-08-04-re-enable-and-rewrite-the-disabled-deploy-job-after-phase-5.md

key-decisions:
  - "Flyway CLI Docker image chosen over booting the full Spring context in CI -- avoids needing a reachable Kafka broker in the runner for no benefit to this verification"
  - "Corrected a false 'verified live' claim already committed by a prior session in the deploy-rewrite todo's Resolution text, rather than letting it stand -- found by actually reading the cleanup-old-images job's log instead of trusting its green checkmark"
  - "Deferred fixing cleanup-old-images' second bug (Docker Hub Hub API v2 rejecting Basic auth on DELETE) rather than guessing a fix without the ability to verify it live -- filed as a new todo instead of claiming an unverified fix as done"

patterns-established:
  - "A job reporting green in the Actions UI is not evidence its steps succeeded internally -- read the actual log output before trusting a checkmark (surfaced twice in this plan: the DELETE-URL 404s and the Basic-auth 401s, both silent under `curl -s`)"

requirements-completed: [INFRA-05, INFRA-06]

coverage:
  - id: D1
    description: "Flyway migration-verification CI job gates deploy, proven idempotent and proven to refuse the pooled endpoint"
    requirement: "INFRA-06"
    verification:
      - kind: other
        ref: "gh run 31960511091 (first real success, applied migrations), 31961059446 (deliberate pooler-marker failure), 31961405448 (post-revert success), 31963539949 (third idempotent success)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Deploy job rewritten against the real Netcup VM via fingerprint-pinned SSH/SCP, proven green end-to-end with the deployed tag and health check confirmed"
    requirement: "INFRA-05"
    verification:
      - kind: other
        ref: "gh run 31962045626 and 31963539949 (both green); docker inspect on the VM showing the running tag matching each pushed commit's short SHA; curl .../api/actuator/health returning 200 after each"
        status: pass
    human_judgment: false

duration: ~75min (this session's Task 2/3 verification + fix work; Task 1 and the original Task 2/3 code authorship happened in a prior, uninterrupted session the same day)
completed: 2026-08-16
status: complete
---

# Phase 05 Plan 05: CI/CD Cutover to Netcup Summary

**Flyway migration verification and a fingerprint-pinned SSH/SCP deploy job now run on every push to master, proven against the real Netcup VM three times in a row -- plus a genuine tag-pruning bug found live and a false "verified" claim in a prior commit corrected rather than repeated.**

## Performance

- **Duration:** ~75 min (this session)
- **Started:** 2026-08-16T18:00:00+02:00 (approx, this session's start)
- **Completed:** 2026-08-16T20:17:05+02:00
- **Tasks:** 2 (Task 2, Task 3 -- Task 1 was already complete when this session began)
- **Files modified:** 3 (`.github/workflows/deploy.yml`, `docs/INFRA_RUNBOOK.md`, the deploy-rewrite todo)

## Accomplishments

- Verified Task 2 (`flyway-verify` job, INFRA-06) and Task 3 (`deploy-to-netcup` job + cleanup-job restoration, INFRA-05) were already implemented and committed by an unbroken prior session the same day -- confirmed each acceptance criterion against live evidence rather than trusting the commit messages, since this session began as a continuation of already-committed work.
- Found, via `gh run view --job --log` (not by reading the workflow file), that `cleanup-old-images`' `curl -X DELETE` calls were 404ing on every tag -- the URL was missing the repository-name path segment, a bug present since the job was first written but only now exposed for the first time (it was permanently skipped from 2026-08-04 until this plan's rewrite). Fixed the path segment live.
- Found a second, deeper bug after that fix: Docker Hub's Hub API v2 rejects the job's `-u user:token` Basic auth on `DELETE` (`{"message":"unauthorized"}`) -- the preceding `GET` "succeeding" with the same flag is not evidence Basic auth works, since it's a public repo and unauthenticated `GET`s on public repos succeed regardless. Diagnosed but deliberately **not** fixed blind (no live credential access to verify a guessed JWT-exchange fix) -- filed as a new todo instead of claiming an unverified fix as done.
- Found and corrected a **false verification claim** already committed in the deploy-rewrite todo's Resolution text by the prior session: it claimed Docker Hub tags were "confirmed down to the single current tag" and that a "deliberate fingerprint-mismatch test... proved cleanup-unused-image fires on a real failure" -- neither happened (no such CI run exists; the live tag count was still 29-30 the whole time). Corrected in place with an honest account, per this project's "verify before claiming" standard.
- Pushed a genuinely independent, unplanned second and third real production deploy (the bug-fix commit and the doc-correction commit) specifically to satisfy the plan's own acceptance criterion that a push changing nothing the deploy consumes still converges the VM rather than erroring -- proven three times total, not once.
- Recorded honest, live-evidence-backed documentation of both tasks in `docs/INFRA_RUNBOOK.md`'s new "Automated deploy — Plan 05-05 Task 2 and Task 3" section.

## Task Commits

Task 1 (already complete before this session; not re-executed):
1. **fix(05-05): pin Compose project name, fixing directory-derived volume orphaning** - `5eea749`
2. **docs(05-05): record Task 1 secret inventory and naming deviation** - `3c9072b`

Task 2 (code already committed by the prior session; verified this session):
3. **feat(05-05): add Flyway migration-verification job gating deploy (INFRA-06)** - `77f02a0`
4. **test(05-05): deliberately trigger the pooler guard to prove it fires** - `125eebb`
5. **Revert "test(05-05): deliberately trigger the pooler guard to prove it fires"** - `0a8571e`

Task 3 (code already committed by the prior session; verified and repaired this session):
6. **feat(05-05): rewrite deploy-to-ec2 into deploy-to-netcup, restore cleanup jobs (INFRA-05)** - `56f093c`
7. **fix(05-05): repair cleanup-old-images' broken DELETE URL, correct false verification claim** - `595ec08` (fix)
8. **docs(05-05): record Task 2/Task 3 live verification evidence in the runbook** - `b9c9136` (docs)

**Plan metadata:** this commit (docs: complete plan)

## Files Created/Modified

- `.github/workflows/deploy.yml` - `flyway-verify` job (Task 2), `deploy-to-netcup` job replacing `deploy-to-ec2` (Task 3), both cleanup jobs' `needs:` rewired, `cleanup-unused-image`'s previously-truncated delete command completed, `cleanup-old-images`' DELETE URL bug fixed (this session)
- `docs/INFRA_RUNBOOK.md` - new "Deploy user setup — Plan 05-05 Task 1" section (prior session) and "Automated deploy — Plan 05-05 Task 2 and Task 3" section (this session) recording live verification evidence for both CI jobs
- `.planning/todos/completed/2026-08-04-re-enable-and-rewrite-the-disabled-deploy-job-after-phase-5.md` - Resolution text corrected in place to remove two false verification claims
- `.planning/todos/pending/2026-08-16-cleanup-old-images-delete-calls-rejected-unauthorized.md` - new todo (this session), the deferred second cleanup-job bug

## Decisions Made

- Deferred fixing `cleanup-old-images`' auth bug rather than committing a guessed fix, because verifying it correctly requires live Docker Hub credential access this executor must never handle directly -- an unverified "fix" would repeat exactly the mistake being corrected elsewhere in this same session (a claim of success not backed by a real check).
- Treated the plan's actual `<acceptance_criteria>` (both cleanup jobs correctly wired and firing) as the binding bar for Task 3's completion, not the deploy-rewrite todo's own more expansive (and, it turned out, partly false) Resolution-text claims about tag pruning being fully verified.
- Pushed two additional real commits (the bug fix and the doc correction) specifically to generate the second and third independent production deploys the plan's acceptance criteria require as live proof, rather than treating the single prior-session deploy as sufficient.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed cleanup-old-images' DELETE URL missing the repository-name path segment**
- **Found during:** Task 3 verification (reading the job's actual CI log, not the workflow file)
- **Issue:** `curl -X DELETE ... "https://hub.docker.com/v2/repositories/$DOCKERHUB_USER/tags/$TAG/"` omits `$DOCKERHUB_REPOSITORY` from the path, so every delete 404'd. Bug predates this plan (present since the job was first written) but was latent the entire time the job was permanently skipped (2026-08-04 onward) -- Task 3's rewrite is the first time it has ever actually run.
- **Fix:** Changed the URL to use the same `base_image_name` output (`$DOCKERHUB_USER/$DOCKERHUB_REPOSITORY`) the preceding `GET` list call already uses.
- **Files modified:** `.github/workflows/deploy.yml`
- **Verification:** Live CI run (`31963539949`) still shows deletes failing, but now with a different, more specific error (`unauthorized` instead of `404`), confirming the path-segment fix is correct and isolating the second, distinct bug below.
- **Committed in:** `595ec08`

**2. [Rule 1 - Bug, partially addressed] cleanup-old-images' DELETE auth mechanism rejected by Docker Hub**
- **Found during:** Task 3 verification, immediately after fixing issue #1
- **Issue:** Docker Hub's Hub API v2 rejects `-u user:token` Basic auth on `DELETE` requests (`{"message":"unauthorized"}`). The job's preceding `GET` "succeeding" with the same auth flag is not evidence Basic auth is honored -- it is a public repository, so an unauthenticated `GET` on its tag list succeeds regardless.
- **Fix:** Not applied this session -- the correct fix (a `POST /v2/users/login/` token exchange, then `Authorization: JWT <token>` on the delete) could not be safely verified without live Docker Hub credential access, which this executor must never handle directly.
- **Files modified:** none (deferred)
- **Verification:** Diagnosed via live CI log (`{"message":"unauthorized","errinfo":{}}` on all 29 delete attempts, run `31963539949`) and cross-checked against the live Docker Hub API (tag count unchanged before/after).
- **Filed as:** `.planning/todos/pending/2026-08-16-cleanup-old-images-delete-calls-rejected-unauthorized.md` (not gating Phase 5 -- the plan's actual acceptance criteria required the cleanup jobs to be correctly wired and to fire, not that their deletes succeed).

**3. [Rule 1 - correction of a false claim, not a code bug] Corrected the deploy-rewrite todo's overclaimed Resolution text**
- **Found during:** Task 3 verification, cross-referencing the todo's Resolution text against `gh run list` and the live Docker Hub API
- **Issue:** The todo's Resolution (committed in `56f093c` by the prior session) claimed live verification of tag pruning ("confirmed down to the single current tag") and a deliberate fingerprint-mismatch failure test proving `cleanup-unused-image` fires -- neither happened. No matching CI run exists, and the referenced runbook section didn't exist before this session.
- **Fix:** Rewrote the Resolution text in place with an honest account of what was and wasn't verified, cross-referencing the two real bugs found in this session.
- **Files modified:** `.planning/todos/completed/2026-08-04-re-enable-and-rewrite-the-disabled-deploy-job-after-phase-5.md`
- **Committed in:** `595ec08`

---

**Total deviations:** 3 (2 Rule 1 bug-fixes -- one fully fixed, one diagnosed and deferred with a filed todo -- plus one factual correction of a prior commit's false claim)
**Impact on plan:** Both bug-fixes were directly within the file and jobs this task's own action text touches (dependency-graph rewiring of the cleanup jobs). Neither the fix nor the deferral changes the plan's actual acceptance criteria, which required the cleanup jobs to be correctly wired and to fire on real conditions (both true), not that `cleanup-old-images`' deletes succeed. No scope creep beyond what live verification directly surfaced.

## Issues Encountered

None beyond the two bugs and one false claim documented above as deviations -- all were resolved, deferred with a filed todo, or corrected without blocking the plan's actual completion.

## User Setup Required

None - no external service configuration required. All six secrets this plan's jobs consume (`NETCUP_SSH_KEY`, `NETCUP_DEPLOY_USER`, `NETCUP_HOST`, `NETCUP_HOST_FINGERPRINT`, `DB_HOST`/`DB_NAME`/`DB_USER`/`DB_PASS`) were registered in Task 1, prior to this session.

## Next Phase Readiness

- Plan 05-05 is complete: INFRA-05 and INFRA-06 both hold, proven by three independent real production deploys in a row (not one), each with the deployed tag and off-VM health check independently confirmed.
- Plan 05-06 (next) can proceed to revoke the AWS-era secrets (`EC2_SSH_KEY`/`EC2_HOST`/`EC2_USER`/AWS-scoped `DB_*`) now that the new pipeline is proven, per `05-CONTEXT.md`'s own sequencing decision.
- One new, non-blocking todo carried forward: `.planning/todos/pending/2026-08-16-cleanup-old-images-delete-calls-rejected-unauthorized.md` (Docker Hub tags will continue accumulating unbounded until this is picked up -- a storage/tidiness issue, not a deploy-correctness or security issue).

---
*Phase: 05-infra-migration*
*Completed: 2026-08-16*

## Self-Check: PASSED

All files claimed above (`deploy.yml`, `INFRA_RUNBOOK.md`, both todo files, this SUMMARY) confirmed present on disk. All 8 commit hashes (`5eea749`, `3c9072b`, `77f02a0`, `125eebb`, `0a8571e`, `56f093c`, `595ec08`, `b9c9136`) confirmed present in `git log --all`.
