---
phase: 09-nonprod-continuous-deploy-scoped-ci-credentials
plan: 01
subsystem: infra
tags: [github-actions, github-environments, docker-hub, ssh, netcup, ci-cd]

# Dependency graph
requires:
  - phase: 08-isolated-nonprod-environment-live-and-resettable
    provides: "docker-compose.nonprod.yml, the nonprod Neon branch, the nonprod VM directory layout, and the standing precedent that root VM work runs over the operator's own authorized SSH session"
provides:
  - "GitHub Environments production and staging, both with zero protection rules"
  - "Nine identically-named deploy secrets populated in both environments (DB_HOST, DB_NAME, DB_USER, DB_PASS, NETCUP_SSH_KEY, NETCUP_DEPLOY_USER, NETCUP_HOST, NETCUP_HOST_FINGERPRINT, DOCKERHUB_TOKEN); the ten repository-level secrets remain untouched as the safety net for the first environment-scoped production run"
  - "Linux user deploy-nonprod on the Netcup VM, docker-group member, confined by Unix filesystem permissions to /opt/deploy/kanban-board-nonprod/, proven locked out of /opt/deploy/kanban-board-backend/ by measured Permission denied output"
  - "Public Docker Hub repository rudenkovladimir/kanban-board-backend-nonprod"
  - "Task 1's decision checkpoint resolved (option-a) and recorded, with both residual risks (T-09-03 docker-group root-equivalence, T-09-09 account-wide Docker Hub token) written into docs/INFRA_RUNBOOK.md"
  - "deploy.yml wired with flyway-verify-nonprod and deploy-to-nonprod, dual-tag single build, environment: retrofit on every production job (commit 58bdee9, live on master) — live end-to-end run confirmed green (run 32184033760) after two coordinator-diagnosed and coordinator-fixed defects (NETCUP_HOST_FINGERPRINT wrong key algorithm; docker-compose.nonprod.yml pointed at production's repository); this IS now a usable green-run precedent for plans 09-02/09-03"
affects: [09-02-nonprod-continuous-deploy-scoped-ci-credentials, 09-03-nonprod-continuous-deploy-scoped-ci-credentials]

# Actuals (#2632)
actuals:
  tokens: 8900
  tasks: 3
  commits: 4

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "GitHub Environments used purely as a secret-scoping mechanism (zero protection rules, D-04) rather than for approval gates"
    - "Deploy credentials recovered from the VM's own already-deployed env files where possible, rather than re-typed by the operator, wherever GitHub's write-only secret store made the original value otherwise unrecoverable"
    - "One build, two Docker Hub tags: docker/build-push-action's tags: list takes a multi-line block, avoiding a second build step for the second environment's image"

key-files:
  created: []
  modified:
    - "docs/INFRA_RUNBOOK.md — new section '## Nonprod CI deploy identity and environment-scoped secrets — Plan 09-01', corrected '### Operator note — deploying nonprod'"
    - ".github/workflows/deploy.yml — added flyway-verify-nonprod, deploy-to-nonprod; environment: production retrofit on flyway-verify/deploy-to-netcup/cleanup-old-images/cleanup-unused-image; dual-tag build-and-push-docker-image"

key-decisions:
  - "Task 1 checkpoint resolved by human operator: option-a — deploy-nonprod VM identity confined by Unix filesystem permissions only (docker group membership root-equivalence accepted as residual risk), plus one Docker Hub token duplicated into both GitHub Environments (account-wide token scope accepted as residual risk). Mechanical names confirmed as proposed: Linux user deploy-nonprod, Docker Hub repository rudenkovladimir/kanban-board-backend-nonprod, GitHub Environments production/staging, identical secret NAMES in both environments with per-environment values."
  - "Task 3 (the .github/workflows/deploy.yml edit and the live push-to-master that proves it) was executed directly on the sequential (non-worktree) working tree by explicit coordinator instruction, since it pushes to origin/master and must happen from the real working tree, not an isolated branch. The edit landed and pushed cleanly (commit 58bdee9), but the live run FAILED — see Issues Encountered. Task 3 is NOT complete; this plan remains halted pending human remediation of a Task 2 provisioning defect (wrong NETCUP_HOST_FINGERPRINT value) discovered by this run."

patterns-established: []

requirements-completed: [CI-02, CI-01, CI-03]

coverage:
  - id: D1
    description: "Two GitHub Environments (production, staging) created with zero protection rules, each holding the same nine deploy secret names with per-environment values; ten repository-level secrets left untouched"
    requirement: "CI-02"
    verification:
      - kind: other
        ref: "gh api repos/RudVlad473/kanban-board-backend/environments --jq '.total_count' == 2; gh secret list --env production/--env staging both == the nine expected names; gh secret list (repo) == 10"
        status: pass
    human_judgment: false
  - id: D2
    description: "deploy-nonprod Linux identity created on the Netcup VM, docker-group member, confined to /opt/deploy/kanban-board-nonprod/, proven locked out of production's directory"
    requirement: "D-01/D-02 (CONTEXT.md decisions)"
    verification:
      - kind: manual_procedural
        ref: "id deploy-nonprod; sudo -u deploy-nonprod sudo -n true (fails); stat modes on both directories; sudo -u deploy-nonprod ls/cat against production's directory (both Permission denied); off-VM SSH as deploy-nonprod succeeds"
        status: pass
    human_judgment: false
  - id: D3
    description: "Public Docker Hub repository rudenkovladimir/kanban-board-backend-nonprod created for CI-03's repository separation"
    requirement: "CI-03"
    verification:
      - kind: other
        ref: "curl https://hub.docker.com/v2/repositories/rudenkovladimir/kanban-board-backend-nonprod/ | jq -r .is_private == false"
        status: pass
    human_judgment: false
  - id: D4
    description: "Task 3 (.github/workflows/deploy.yml edit and live end-to-end deploy proof) executed on the sequential working tree; the workflow edit is structurally correct and live on master (commit 58bdee9). Two live-verification defects were found and fixed by the coordinator after the executor's own turn ended: (1) NETCUP_HOST_FINGERPRINT was set to the VM's ED25519 host key fingerprint, independently verified correct three ways (local ssh-keyscan, live ssh -vv negotiation, the VM's own /etc/ssh key file) — but appleboy/ssh-action's underlying easyssh-proxy/golang.org/x/crypto/ssh client ranks ECDSA above ED25519 in its default HostKeyAlgorithms preference (golang/go#51168), so it actually negotiates the VM's ECDSA key; reset to the ECDSA fingerprint after confirming via a live whoami connection test. (2) docker-compose.nonprod.yml (a Phase 8 artifact, outside this plan's files_modified scope) hardcoded the production Docker Hub repository name in its image: line, so nonprod silently pulled from production's repository despite the correct tag existing in its own repository — fixed with a one-line image-name correction. After both fixes, live run 32184033760 went fully green: all 9 jobs succeeded, both health endpoints return 200, and docker inspect confirms kanban-nonprod-app runs rudenkovladimir/kanban-board-backend-nonprod:28a438d while kanban-board-backend-app-1 runs rudenkovladimir/kanban-board-backend:28a438d — genuine repository separation, not just an unused second repository."
    verification:
      - kind: other
        ref: "gh run view 32184033760 --json conclusion == success, all jobs succeeded; curl health checks both 200; ssh netcup-prod docker ps confirms kanban-nonprod-app: rudenkovladimir/kanban-board-backend-nonprod:28a438d and kanban-board-backend-app-1: rudenkovladimir/kanban-board-backend:28a438d; curl hub.docker.com/v2/repositories/rudenkovladimir/kanban-board-backend-nonprod/tags includes 28a438d"
        status: pass
    human_judgment: true
    rationale: "Both defects were diagnosed and fixed by the coordinator directly (not re-dispatched to an executor) because each required a live production-adjacent action (resetting a GitHub Environment secret; pushing a workflow-triggering commit) that this session's own established pattern requires checking with the human operator before taking. The human operator confirmed proceeding at each step (fingerprint reset, and again for the docker-compose.nonprod.yml fix) before any live-affecting action was taken."

# Metrics
duration: ~2h40min (across four halts/resumes/attempts plus coordinator-led live-run diagnosis and remediation; see Performance below)
completed: 2026-08-18
status: complete
---

# Phase 09 Plan 01: Nonprod CI deploy identity and scoped secrets — Summary

**Two GitHub Environments (production/staging, zero protection rules) populated with nine scoped deploy secrets each, a filesystem-confined deploy-nonprod VM identity proven locked out of production's directory, a public Docker Hub repository for nonprod, and both nonprod CI jobs wired into deploy.yml — the first live run failed on an SSH host-key fingerprint mismatch, and the fix for that then surfaced a second, unrelated defect (nonprod silently pulling production's Docker Hub image). Both were diagnosed and fixed by the coordinator with the human operator's confirmation at each live-affecting step; the live run is now fully green with genuine cross-repository separation confirmed on the VM. Plan complete, 3/3 tasks.**

## Coordinator Remediation (post-executor)

After this plan's executor turn ended (halted per its own instructions rather than self-healing a live-run failure), the coordinator diagnosed and fixed two defects directly, with the human operator's explicit go-ahead before each live-affecting action:

1. **NETCUP_HOST_FINGERPRINT wrong key algorithm.** The value set in Task 2 was the VM's ED25519 host key fingerprint — independently verified correct three separate ways (a remote `ssh-keyscan`, a live `ssh -vv` negotiation, and reading the VM's own `/etc/ssh/ssh_host_ed25519_key.pub` directly). A hash-comparison probe (a temporary, since-deleted `workflow_dispatch` diagnostic workflow printing `sha256sum` of the secret rather than the secret itself) further confirmed GitHub's stored value byte-matched the intended one exactly. Yet the live connection still failed — because `appleboy/ssh-action`'s underlying Go SSH client (`easyssh-proxy` → `golang.org/x/crypto/ssh`) ranks ECDSA above ED25519 in its default `HostKeyAlgorithms` preference order (a documented Go quirk, [golang/go#51168](https://github.com/golang/go/issues/51168)), so it negotiates and checks against the VM's ECDSA key regardless of which key a normal SSH client would prefer. Reset to the ECDSA fingerprint in both environments, confirmed via a live `whoami` connection test in the same diagnostic workflow before touching the real deploy secret.
2. **docker-compose.nonprod.yml pointed at production's Docker Hub repository.** A Phase 8 artifact, outside this plan's `files_modified` scope — Task 3 correctly built and pushed a second image tag to the new `kanban-board-backend-nonprod` repository, but the nonprod Compose file's `image:` line had never been updated to pull from it, so `deploy-to-nonprod` silently deployed production's repository content instead (byte-identical either way, since both tags came from one build, but architecturally wrong and a direct violation of Task 3's own acceptance criteria and CI-03's repository-separation intent). Fixed with a one-line image-name correction.

Both fixes required a live GitHub Actions run to verify; the human operator confirmed proceeding before each push. Final state: run `32184033760` green across all 9 jobs, both health endpoints `200`, and `docker inspect` on the VM confirms `kanban-nonprod-app` runs `rudenkovladimir/kanban-board-backend-nonprod:28a438d` while `kanban-board-backend-app-1` runs `rudenkovladimir/kanban-board-backend:28a438d` — genuine separation, not merely a provisioned-but-unused second repository.

## Performance

- **Duration:** ~1h50min total across four phases: Task 1 checkpoint reached and halted (~10 min, including Windows Gradle/pre-commit-hook file-lock recovery), checkpoint resolved and Task 2 precondition re-checked and found unmet (~5 min, second halt), Task 2 fully provisioned and committed after the operator supplied SSH access and recoverable-secret guidance (~55 min, including a mid-task key-rotation fix), then Task 3 executed on the sequential working tree — deploy.yml edited, verified statically, committed (~15 min, including two Windows Gradle/Testcontainers pre-commit-hook file-lock recoveries), pushed to master, and the live run diagnosed after both deploy jobs failed (~25 min)
- **Started:** 2026-08-18T18:19:14Z (approx, per STATE.md)
- **Completed:** 2026-08-18 (Tasks 1-2 only; Task 3 attempted but not verified complete)
- **Tasks:** 2 of 3 fully completed and verified (Task 1's checkpoint resolved, Task 2 fully provisioned and verified); Task 3 attempted — workflow edit committed and pushed to master, but the live run it requires failed and remains unresolved
- **Files modified:** 2 (`docs/INFRA_RUNBOOK.md`, `.github/workflows/deploy.yml`) plus this SUMMARY.md

## Accomplishments

- **Task 1 resolved:** human operator selected option-a (deploy-nonprod confined by Unix permissions only; one Docker Hub token duplicated into both environments) and confirmed all mechanical resource names.
- **Task 2 fully provisioned and independently verified:**
  - Created GitHub Environments `production` and `staging`, both with zero protection rules (`gh api .../environments --jq '.total_count'` → `2`, both `protection_rules: []`).
  - Created Linux user `deploy-nonprod` on the Netcup VM, `docker`-group member, no `sudo`; generated a new ed25519 keypair (persisted locally, mirroring production's `netcup_deploy_key` precedent); re-owned `/opt/deploy/kanban-board-nonprod/` to `deploy-nonprod:deploy-nonprod` mode `750`; tightened `/opt/deploy/kanban-board-backend` to `750` to close the reverse read path. Proved the boundary by measurement in both directions (`Permission denied` against production's directory and env file; a successful `docker compose ps` in nonprod's own directory; a successful off-VM SSH as `deploy-nonprod`).
  - Created the public Docker Hub repository `rudenkovladimir/kanban-board-backend-nonprod` (HTTP `201`, `is_private: false`) via the same JWT-login pattern `cleanup-old-images` already uses.
  - Populated all nine deploy secrets in both environments, recovering `DB_*` values as root from the VM's own already-deployed `.env.prod`/`.env.nonprod` files, reusing the operator's existing local `netcup_deploy_key` for production, and piping `DOCKERHUB_TOKEN` directly from a local file into `gh secret set` — no secret value was ever printed, echoed, or written into this repository.
  - Recorded the full provisioning, every verbatim proof output, and both accepted residual risks (T-09-03 docker-group root-equivalence, T-09-09 account-wide Docker Hub token) in `docs/INFRA_RUNBOOK.md`.
  - Corrected the pre-existing `### Operator note — deploying nonprod` section, which previously instructed running nonprod Compose commands as `deploy` — now correctly says `deploy-nonprod`.
- **Task 3 executed on the sequential (non-worktree) working tree**, per explicit coordinator instruction: `.github/workflows/deploy.yml` edited per the task's action spec (A-F), all static acceptance criteria verified locally, committed (`58bdee9`), and pushed to `origin/master`. The push triggered a real GitHub Actions run (`32179763451`) that built and pushed both image tags from one build and ran both new Flyway-verify jobs successfully, but **both deploy jobs (`deploy-to-netcup`, `deploy-to-nonprod`) failed** with `ssh: handshake failed: ssh: host key fingerprint mismatch`. Diagnosed as a Task 2 provisioning defect (wrong `NETCUP_HOST_FINGERPRINT` value in one or both GitHub Environments), not a bug in Task 3's own workflow edit — see Issues Encountered. Production and nonprod were independently confirmed unaffected: both health endpoints return `200`, both containers are still running their pre-run images (`docker inspect` on the VM: production `8d8f046`, nonprod `777cb27`), because the SCP step failed before touching either VM directory.

## Task Commits

Each halt/resume/attempt step was committed atomically:

1. **Checkpoint halt (Task 1 unresolved)** - `8e2bafd` (docs)
2. **Checkpoint resolution recorded, second halt (Task 2 precondition unmet)** - `d52db5a` (docs)
3. **Task 2: provision GitHub Environments, deploy-nonprod VM identity, Docker Hub repository, runbook documentation** - `d15e37e` (feat)
4. **Task 3: wire flyway-verify-nonprod and deploy-to-nonprod into deploy.yml, retrofit `environment:` onto every production job, dual-tag the one build** - `58bdee9` (feat) — edit is correct and live on `master`; the live run it enables still fails (see above), so this task is not marked complete

**Plan metadata:** this SUMMARY.md update (committed immediately after `58bdee9`)

_Note: no `test`/`refactor` commits — this plan is infra provisioning and CI configuration, not application code; the project's own `spotlessCheck`+`fastTest` pre-commit hook ran clean on every commit regardless (twice recovering from a Windows Gradle/Testcontainers file-lock, see Issues Encountered)._

## Files Created/Modified

- `docs/INFRA_RUNBOOK.md` - new section "Nonprod CI deploy identity and environment-scoped secrets — Plan 09-01" (identities table, verbatim proof outputs, secret inventory, accepted residuals); corrected "Operator note — deploying nonprod"
- `.github/workflows/deploy.yml` - added `flyway-verify-nonprod` and `deploy-to-nonprod` jobs; extended `build-and-push-docker-image` to push a second `kanban-board-backend-nonprod`-repository tag from the one build; retrofitted `environment: production` onto `flyway-verify`, `deploy-to-netcup`, `cleanup-old-images`, `cleanup-unused-image`; added `DOCKERHUB_REPOSITORY_NONPROD` env var and `setup`'s `base_image_name_nonprod` output
- `.planning/phases/09-nonprod-continuous-deploy-scoped-ci-credentials/09-01-SUMMARY.md` - this file

**External resources created (not repository files, but part of this plan's deliverable):**
- GitHub Environments `production` and `staging` (repository `RudVlad473/kanban-board-backend`)
- Nine environment secrets in each (`DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASS`, `NETCUP_SSH_KEY`, `NETCUP_DEPLOY_USER`, `NETCUP_HOST`, `NETCUP_HOST_FINGERPRINT`, `DOCKERHUB_TOKEN`)
- VM Linux user `deploy-nonprod` (`159.195.114.230`), `/home/deploy-nonprod/.ssh/authorized_keys`
- Docker Hub repository `rudenkovladimir/kanban-board-backend-nonprod` (public)

## Decisions Made

**Task 1 checkpoint resolved by the human operator:**
- **Selected: option-a** — confirm as planned: `deploy-nonprod` VM identity confined by standard Unix filesystem permissions only (`docker` group membership's root-equivalence explicitly accepted as a residual risk, per D-02's rejection of forced-command/restricted-shell hardening), plus one Docker Hub token duplicated into both GitHub Environments (account-wide token scope explicitly accepted as a residual risk, since per-repository Docker Hub token scoping is a paid-plan feature). Both residuals recorded in `docs/INFRA_RUNBOOK.md` (T-09-03, T-09-09 in the plan's threat register).
- **Mechanical resource names confirmed as proposed:** Linux user `deploy-nonprod`; Docker Hub repository `rudenkovladimir/kanban-board-backend-nonprod`; GitHub Environments `production` and `staging`; identical secret NAMES in both environments with per-environment values.

**Task 3 executed on the sequential working tree, per explicit coordinator instruction:** Task 3 pushes directly to `origin/master` and triggers a real, production-impacting GitHub Actions deploy run, which the coordinator determined must happen from the real working tree (not an isolated worktree/branch) so hooks and the push target are the genuine ones. `.github/workflows/deploy.yml` was edited, verified statically, committed and pushed. The live run it enables failed — see Issues Encountered for full diagnosis. Per the coordinator's explicit instruction for this exact scenario ("do NOT attempt undocumented recovery — halt with a clear report ... so the human operator can assess"), no attempt was made to reset the failing secret or otherwise self-heal the live run.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Deleted the only local copy of the new nonprod private key, then rotated to fix it**
- **Found during:** Task 2, Part C (secret population), immediately after cleaning up scratch files
- **Issue:** After successfully populating all nine secrets, the freshly-generated `netcup_deploy_nonprod_key` private half was deleted from the session scratch directory as part of routine cleanup. This broke the project's own established precedent (documented in `docs/INFRA_RUNBOOK.md`'s "Deploy user setup — Plan 05-05 Task 1"): production's `netcup_deploy_key` is kept permanently at the operator's local `~/.ssh/`, giving a durable copy for future manual/debugging access outside CI. GitHub Environment secrets are write-only, so once a key exists only there, it is unrecoverable by any means — the deleted key would have been permanently lost for any purpose other than what CI already does with it.
- **Fix:** Caught before this document was finalized. Generated a second `netcup_deploy_nonprod_key`, this time persisted at the operator's local `~/.ssh/netcup_deploy_nonprod_key` (mirroring `netcup_deploy_key`'s location convention). Replaced (not appended — verified still exactly one key via `wc -l`) the public half in `/home/deploy-nonprod/.ssh/authorized_keys` on the VM, re-verified the new fingerprint both locally and on the VM and confirmed off-VM SSH access still works, then overwrote the `staging` environment's `NETCUP_SSH_KEY` secret with the new private half.
- **Files modified:** `docs/INFRA_RUNBOOK.md` (fingerprint value corrected to match the final key; an explicit "Deviation found and fixed" note added inline, consistent with this runbook's existing style of documenting real incidents rather than silently correcting them)
- **Verification:** Re-ran the plan's full Task 2 automated `<verify>` block after the rotation — all checks passed (`gh api .../environments` → `2`; both environments' secret name lists match exactly; repository secrets still `10`; Docker Hub `is_private: false`; runbook heading present). Fingerprints re-confirmed distinct from production's key and matching between the local file and the VM's `authorized_keys`.
- **Committed in:** `d15e37e` (Task 2 commit — the rotation happened before the commit, so the final committed runbook state already reflects the corrected key)

**2. [Rule 3 - Blocking, discovered not fixed] Task 3's own `<verify>` automated block has an awk range-extraction bug**
- **Found during:** Task 3, local static verification before push
- **Issue:** The plan's automated verify command extracts the `deploy-to-nonprod` job body with `awk '/^  deploy-to-nonprod:$/,/^  [a-z0-9-]+:$/'`. Because the job-name line itself (`  deploy-to-nonprod:`) also matches the range's own end pattern, GNU awk closes the range on that same line — the "extracted block" is just the one-line job header, so every subsequent `grep -q` against `$B` fails, not because the workflow is wrong but because the extraction never captured the job body at all. Reproduced identically against the pre-existing `deploy-to-netcup` job with the analogous pattern, confirming this is a property of the awk pattern itself, not something introduced by this task's edits.
- **Fix:** Not applied to the plan text (out of this task's file scope: `<files>` is `.github/workflows/deploy.yml` only). Verified the same acceptance criteria manually instead, using `sed -n "${START},${END}p"` with line numbers from `grep -n`: all six checks (`--profile nonprod`, `--env-file ./.env.nonprod`, `needs.build-and-push-docker-image.outputs.image_tag`, `app-nonprod`, `redpanda-nonprod`, single `/opt/deploy/kanban-board-nonprod` path) pass against the real job body. This is a documentation-only finding — recorded here so a future planner does not re-diagnose the same awk quirk as a workflow bug.
- **Files modified:** None (verification-only finding).
- **Verification:** Corrected extraction command run and all six criteria confirmed passing; also independently confirmed via full-file `grep`/manual read that the job body is correct.
- **Committed in:** n/a (no code change; documented here only)

---

**Total deviations:** 2 (1 auto-fixed — Rule 1, bug in the executor's own cleanup step during Task 2; 1 discovered-not-fixed — Rule 3 class, a pre-existing bug in the plan's own verify-script text, worked around by manual verification rather than edited since it is outside Task 3's file scope)
**Impact on plan:** Neither affects the shipped `deploy.yml`. The Rule 1 fix was necessary to match this project's established credential-retention precedent. The awk quirk cost verification time but the underlying acceptance criteria are genuinely met, confirmed by an equivalent manual extraction.

## Issues Encountered

**Windows Gradle/pre-commit-hook file-lock (recurred four times this session, not a Task 2/3 issue):** the Bash tool's default 2-minute timeout repeatedly killed the top-level `git commit` process mid-`fastTest` while the Testcontainers-backed suite was still genuinely running (confirmed live via `docker ps` — a real Postgres + Redpanda container pair, not a hang), leaving orphaned `Gradle Test Executor` JVMs holding Windows file handles open in `build/test-results/fastTest/binary/output.bin`. Each retried commit's `fastTest` task then failed immediately with "Unable to delete directory." Resolved each time by identifying and killing the orphaned `java.exe` worker processes (confirmed via command-line inspection referencing this exact worktree path), removing the now-unlocked, gitignored `build/test-results/fastTest` directory, then retrying the commit with an explicit longer `timeout` (480000ms) so the ~7-minute real test run could complete inside one Bash call. Task 3's commit (`58bdee9`) succeeded on the attempt that used the explicit long timeout — `BUILD SUCCESSFUL in 6m 53s`. Not a plan or implementation issue — a Windows process-lifecycle artifact of the harness's own default timeout colliding with a multi-minute Testcontainers-backed test suite, now documented a second time (see Task 1/2's own recurrence above) for a future session to recognize immediately and skip straight to the long-timeout retry.

**Operator error — accidentally stopped two unrelated long-running Docker containers, self-corrected within seconds:** while clearing orphaned Testcontainers state during the file-lock recovery above, a `docker stop $(docker ps -q --filter status=running | grep -v <name> | grep -v <name>)` command was run to spare two unrelated containers (`bitmagnet-gluetun`, `bitmagnet-pyroscope`, both unrelated to this project). The `grep -v` filters were applied to container IDs (`docker ps -q` output), not names, so they matched nothing and every running container — including those two — was stopped. Caught immediately by re-checking `docker ps`; both containers were restarted (`docker start bitmagnet-gluetun bitmagnet-pyroscope`) within seconds and confirmed back up (`bitmagnet-gluetun` health-check passed on restart). No data loss — these are long-running service containers, not one-shot jobs. Recorded here as a real incident, not silently corrected: a future session should filter by name against `docker ps --format` output, not by piping `-q` IDs through a name-based grep.

**Task 2 precondition genuinely unmet on first pass, correctly halted rather than proceeding:** per the coordinator's explicit second-turn instruction, this agent checked Task 2's own `<precondition>` (authorized root SSH session + nine secret values) before attempting any provisioning, found it unmet (no SSH session, no secret values supplied yet), and halted with a second checkpoint rather than attempting to open a session or request values itself. This was the correct, designed behavior for a background agent with no real-time operator channel — not an error.

**Task 3's live run failed — both deploy jobs, identical root cause, diagnosed but not fixed (per explicit instruction):** after `58bdee9` pushed cleanly to `master`, GitHub Actions run `32179763451` ran `setup`, `run-tests`, `build-and-push-docker-image`, `flyway-verify`, and `flyway-verify-nonprod` all successfully (dual-tag build confirmed: both `actual_image_name` and `actual_image_name_nonprod` outputs populated, one `docker/build-push-action` invocation). Both `deploy-to-netcup` and `deploy-to-nonprod` then failed identically at their first step (`Copy Compose manifest ... to the VM`) with `ssh: handshake failed: ssh: host key fingerprint mismatch`. Diagnosis: this is the **first run** in which these jobs resolve `secrets.NETCUP_HOST_FINGERPRINT` through a declared `environment:` (Task 3's own retrofit) rather than the repository-level secret that had been working correctly in every prior run — so the environment-scoped value populated into `production`/`staging` during Task 2 does not match the VM's actual current host key. Independently reproduced the exact command the runbook documents for re-deriving this value (`ssh-keyscan 159.195.114.230 | ssh-keygen -lf -`) and found it returns **three** fingerprint lines (RSA, ECDSA, ED25519) — `256 SHA256:h9aO7t/x4mcCQAFEyDd1ctr2JJ4LKwYZGKEFX0F9N1Q ... (ED25519)` is the one an OpenSSH-compatible client (which `appleboy/scp-action`'s Go SSH library is) will actually negotiate by default, since ED25519 is preferred over RSA/ECDSA in modern default host-key-algorithm ordering. The most likely root cause is that whichever line was captured into the `NETCUP_HOST_FINGERPRINT` secret during Task 2 was not this one, or was malformed in some other way — GitHub environment secrets are write-only, so the actual stored value cannot be inspected to confirm which. **Explicitly NOT auto-fixed**, per this session's own instruction covering exactly this scenario ("If the live run fails or acceptance criteria are not met, do NOT attempt undocumented recovery — halt with a clear report ... so the human operator can assess"). Confirmed both production and nonprod are unaffected by the failed run: both health endpoints return `200`; `docker inspect` on the VM (over the operator's already-authorized `netcup-prod` SSH session, read-only) shows production still on `rudenkovladimir/kanban-board-backend:8d8f046` and nonprod still on `rudenkovladimir/kanban-board-backend-nonprod:777cb27` — the SCP step fails before writing anything to either VM directory. `cleanup-unused-image` then ran (its `if: failure()` condition matched) and deleted the just-pushed `58bdee9` tag's manifest from the **production** Docker Hub repository only, per its existing, unmodified design ("if deployment failed, we need to cleanup the image we pushed, because it won't be used") — this plan adds no equivalent cleanup job for the nonprod repository (that is explicitly out of scope, deferred to a later plan), so the `58bdee9` tag likely still exists in `kanban-board-backend-nonprod` on Docker Hub even though its own deploy also failed; this asymmetry is expected given this plan's declared scope, not a new bug.

## User Setup Required

None outstanding for Task 1/Task 2 — the operator has already supplied everything needed (SSH session access, guidance on recoverable secrets, the Docker Hub token file path) and it has all been consumed and verified.

**Outstanding for Task 3 — human remediation required before this plan can be marked complete:**
1. Confirm the correct current SSH host fingerprint for `159.195.114.230` — most likely `SHA256:h9aO7t/x4mcCQAFEyDd1ctr2JJ4LKwYZGKEFX0F9N1Q` (ED25519), reproducible via `ssh-keyscan -t ed25519 159.195.114.230 | ssh-keygen -lf -`.
2. Reset `NETCUP_HOST_FINGERPRINT` in **both** GitHub Environments: `gh secret set NETCUP_HOST_FINGERPRINT --env production` and `--env staging`, each fed the confirmed value on stdin (never `--body`).
3. Re-run the workflow against the already-pushed `58bdee9` (e.g. `gh workflow run "CI/CD with Docker" --ref master` or `gh run rerun 32179763451 --failed`) and confirm `deploy-to-netcup` and `deploy-to-nonprod` both go green in the same run.
4. Re-verify Task 3's live acceptance criteria: `docker inspect kanban-nonprod-app` on the VM shows the `58bdee9`-tagged nonprod image, both public health endpoints return `200`, and production's containers show only the expected `app` recreate.
5. Once green, update this SUMMARY's `status` to `complete` and D4's `verification[].status` to `pass`.

## Next Phase Readiness

**Blocked on human remediation of the Task 2 fingerprint defect, not on new work.** Tasks 1 and 2 remain complete and independently verified. Task 3's workflow edit is itself correct and live on `master` (`58bdee9`) — every static acceptance criterion passes — but its required live proof failed for a reason outside this task's own file scope (a wrong secret value set during Task 2, only exposed now that Task 3's `environment:` retrofit makes that secret load-bearing for the first time). Plans 09-02 (repository-secret sweep) and 09-03 both depend on a green run of this workflow and must not start until the fingerprint is corrected and the run is re-verified green.

---
*Phase: 09-nonprod-continuous-deploy-scoped-ci-credentials*
*Completed: 2026-08-18 (halted — Task 3's workflow edit is live, but its live run failed on a Task 2 provisioning defect; awaiting human remediation)*
