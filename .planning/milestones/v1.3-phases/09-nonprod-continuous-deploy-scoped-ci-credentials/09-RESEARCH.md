# Phase 9: Nonprod Continuous Deploy & Scoped CI Credentials - Research

**Researched:** 2026-08-18
**Domain:** GitHub Actions CI/CD (multi-environment deploy, credential scoping, Docker Hub Registry API automation)
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**SSH Credential Isolation (CI-02)**
- **D-01:** Nonprod's deploy path gets its own restricted deploy identity on the VM — a new, separate Linux user (distinct from the existing `deploy` user) with its own SSH keypair, confined to `/opt/deploy/kanban-board-nonprod/` via ordinary Unix file permissions (no read/write access to `/opt/deploy/kanban-board-backend/`). Chosen over reusing `NETCUP_SSH_KEY`/`deploy` because both prod and nonprod currently share that single account on the same VM (Phase 8 created the nonprod directory under it) — GitHub Environments scope which secret *values* a job can read, but a shared credential still grants full shell access to production's directory regardless of which job reads it. Rejected the further-hardened forced-command/restricted-shell option as unnecessary complexity beyond what this project's risk tolerance calls for. — **Reversibility:** costly — swapping deploy identity later means re-provisioning the VM-side user, regenerating the SSH keypair, redistributing the new secret through GitHub Environments, and updating every workflow step's credential reference; treat this as the standing nonprod deploy identity, not a placeholder.
- **D-02:** Confinement mechanism is standard Unix user/file permissions only — no SSH forced-command or restricted-shell hardening. Mirrors the effort tier already established for the existing `deploy` user (non-root, own directory, created via a one-time root `mkdir`+`chown` per Phase 5/Phase 8 precedent in `docs/INFRA_RUNBOOK.md`).
- **D-03:** The CI-05 schema-registration step for nonprod (which reaches the broker over the same SSH path the deploy job uses, per ROADMAP's phase rationale) uses this same new nonprod-scoped deploy identity — it is part of the nonprod deploy path, not a separate credential surface.

**Environment Approval Gates**
- **D-04:** Neither the `production` nor the `staging` GitHub Environment gets a required-reviewer or wait-timer approval gate. Both stay fully unattended — every push to master deploys both automatically, exactly matching this project's existing always-on CI/CD posture. GitHub Environments exist here purely as the secret-scoping mechanism CI-02 requires, not as a release gate.

**Nonprod Image Retention (CI-03)**
- **D-05:** Nonprod's separate Docker Hub repo gets its own `cleanup-old-images`-equivalent job — deletes every non-current tag after each successful nonprod deploy, mirroring production's existing job exactly. Rejected leaving it unbounded: nonprod deploys on every push at the same rate as production, so it would accumulate tags at an identical pace — the same unbounded-growth problem prod's job already exists to solve, just relocated to a second repo.
- **D-06:** Nonprod also gets a `cleanup-unused-image`-equivalent job (deletes the just-pushed tag by digest when the nonprod deploy itself fails), for full parity with production's retention behavior rather than partial coverage.

### Claude's Discretion

- Exact naming for the new nonprod-scoped Linux user, its SSH secret name(s) in GitHub, and the nonprod Docker Hub repository name — mechanical choices, no user preference expressed beyond "keep it clearly distinct from production's."
- Exact health-check retry/timeout parameters for CI-04 — mirrors the existing production health-check pattern from v1.2 Phase 5; no new decision needed.
- Job/step ordering details for CI-05 (schema registration completing before an environment's app serves traffic) — already fully specified by the CI-05 requirement text itself; implementation detail, not a vision decision.
- Whether the new GitHub Environments are created via `gh` CLI or the GitHub web UI — execution mechanism, not a scope decision.

### Deferred Ideas (OUT OF SCOPE)

None raised beyond what ROADMAP.md already scopes to Phase 10 (the eight hardening todos). The highest-scoring keyword-matched todo group (dependabot `github-actions` ecosystem, TruffleHog live-credential pass, digest-pinning, gradle cache in `run-tests`, gitleaks-in-worktree, `security-scan.yml` stale comment/actions, cookie `Secure` flag, README expansion) are all real, in-scope v1.3 work already mapped to Phase 10 in REQUIREMENTS.md's Traceability table — none were folded into Phase 9, and this research does not attempt them even where they touch the same `deploy.yml` file.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| CI-01 | A CI job deploys to nonprod on every push to master, extending `deploy.yml`'s existing build -> Flyway-verify -> deploy job graph (`flyway-verify-nonprod`, `deploy-to-nonprod`), parameterized by target, running parallel to — never gating, never gated by — the existing `deploy-to-netcup` production job | Architecture Patterns (System Diagram, Recommended Job Additions, Pattern 2 concurrency groups); Common Pitfalls 3/4; Code Examples (IMAGE_TAG) |
| CI-02 | `production` and `staging` GitHub Environments are introduced as a prerequisite before the nonprod deploy job is added, so the nonprod job does not inherit full, unscoped access to all 10 existing repository secrets by default | Pattern 1 (environment-scoped job); Code Examples (`gh api` environment creation); Common Pitfalls 1 (the one credential surface — `DOCKERHUB_TOKEN` — CONTEXT.md's decisions did not resolve) |
| CI-03 | Nonprod images are pushed to a Docker Hub repository separate from production's, so the existing `cleanup-old-images` job's per-run tag-deletion sweep cannot delete nonprod's currently-running tag on the next production push | Pattern 3 (parameterized cleanup); Common Pitfalls 2 (repo must be explicitly created); Standard Stack (Hub API v2 / Registry API v2 reuse) |
| CI-04 | A readiness/health check polls the nonprod deploy's health endpoint until it returns 200 before the deploy is considered complete, mirroring the HTTPS health check already used to verify production in v1.2 Phase 5 | Common Pitfalls 3 (this is new work, not a copy); Code Examples (bounded retry loop); Environment Availability |
| CI-05 | A CI job registers the application's Avro schemas against both the production and the nonprod schema registries on every deploy, reusing the existing `AvroSchemaRegistrar`/`PropertiesLauncher` one-off-container mechanism | Don't Hand-Roll (schema registration); Code Examples (PropertiesLauncher invocation, both brokers); Open Question 2 (production ordering) |
</phase_requirements>

## Summary

This phase extends the already-proven `.github/workflows/deploy.yml` job graph with a second,
parallel deploy path for nonprod, using patterns the existing production jobs already exercise
live: `appleboy/scp-action` + `appleboy/ssh-action` over a fingerprint-pinned host, a Docker Hub
Registry-API-v2 cleanup pair, and a `PropertiesLauncher`-based one-off container for Avro schema
registration. Nothing here is a new technology — it is the same job shapes, retargeted at a second
directory/user/repo/registry, with GitHub Environments (`production`/`staging`) as the mechanism
that keeps the two secret sets from crossing.

Three things are not yet decided anywhere in CONTEXT.md/ROADMAP.md and need the planner's explicit
attention: (1) whether `DOCKERHUB_TOKEN` — currently one repository-wide secret used by
`build-and-push-docker-image` and both cleanup jobs — needs its own nonprod-scoped credential the
way the SSH identity got one (D-01), given that per-repository Docker Hub access-token scoping is a
paid-plan feature not confirmed available on this account; (2) the nonprod Docker Hub repository
does not exist yet and, per Docker's own docs, is not reliably auto-created on first push — it needs
an explicit one-time creation step; (3) CI-04's health-check poll is new work, not a copy of an
existing CI step — production's deploy job has never itself polled for health in CI (the `200`
checks in `docs/INFRA_RUNBOOK.md` were all run by hand).

**Primary recommendation:** Add two new jobs to the existing `deploy.yml` (`flyway-verify-nonprod`,
`deploy-to-nonprod`) that mirror `flyway-verify`/`deploy-to-netcup` exactly except for
target directory, user, `--env-file`, Compose file, and `environment: staging`; add a
`register-schemas` job (or two environment-scoped jobs) built directly on the documented
`PropertiesLauncher` invocation; add nonprod-scoped `cleanup-old-images-nonprod`/`cleanup-unused-image-nonprod`
jobs parameterized by a new Docker Hub repo name; give `deploy-to-nonprod` its own
`concurrency.group` distinct from `deploy-to-netcup-vm` so the two never serialize against each
other; and resolve the `DOCKERHUB_TOKEN` scoping question explicitly before writing the plan, since
it is the one credential surface CONTEXT.md's decisions did not cover.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Build/push nonprod-tagged image | CI runner (GitHub-hosted) | Docker Hub (registry) | Same build artifact as production — one `build-and-push-docker-image` job already produces the per-commit tag both paths consume; no separate nonprod build |
| Deploy orchestration (pull + up) | CI runner -> SSH -> VM (Docker Compose) | — | Mirrors `deploy-to-netcup`: CI never runs the app itself, it drives Compose over SSH on the target VM |
| Credential scoping | GitHub Environments (`production`/`staging`) | Docker Hub PAT scope (if plan tier allows) | GH Environments is the primary boundary CI-02 requires; Docker Hub-side scoping is a secondary, possibly-unavailable layer |
| Health verification | CI runner (HTTP poll against public HTTPS endpoint) | VM (Compose healthcheck, already gates `depends_on`) | CI-04 needs its own poll because Compose's own healthcheck only gates `app-nonprod`'s *start*, not the CI job's success/failure |
| Schema registration | CI runner -> SSH -> VM (one-off container on VM's own Compose network) | Redpanda's Schema Registry (both brokers) | Registry has no published port on either broker — reachable only from inside each stack's own Compose network, so registration must run as a container on the VM, not from the CI runner directly |
| Image retention (cleanup) | CI runner -> Docker Hub Registry API v2 / Hub API v2 | — | Same as production: CI calls Docker Hub's HTTPS API directly, no VM involvement |

## Standard Stack

### Core (all already in use in production — no new libraries/frameworks)

| Component | Version (pinned in repo) | Purpose | Why Standard (for this repo) |
|-----------|---------|---------|--------------|
| `appleboy/scp-action` | `v1.0.0` [VERIFIED: .github/workflows/deploy.yml:177] | Copies Compose manifest to VM before deploy | Already the production deploy's SCP mechanism; reuse verbatim for nonprod, only host-args differ |
| `appleboy/ssh-action` | `v1.2.5` [VERIFIED: .github/workflows/deploy.yml:187] | Runs the remote `docker compose pull/up` script over SSH | Same — production's proven SSH-exec action |
| `flyway/flyway` (Docker image) | `11.7.2` [VERIFIED: .github/workflows/deploy.yml:135] | Verifies migrations apply cleanly before deploy | Same Flyway CLI image `flyway-verify` already runs; nonprod needs its own job pointed at the nonprod Neon branch's `DB_*` secrets |
| Docker Hub Hub API v2 (`hub.docker.com/v2/...`) | n/a (HTTPS API, not a package) | `cleanup-old-images`-equivalent tag listing/deletion | Already hardened in production against Basic-auth rejection (fixed 2026-08-16) and pagination (fixed 2026-08-17) — reuse the fixed pattern, do not reintroduce either bug |
| Docker Registry API v2 (`registry-1.docker.io`, `auth.docker.io`) | n/a | `cleanup-unused-image`-equivalent digest-based delete on deploy failure | Same token-exchange-then-DELETE-by-digest pattern already proven in `cleanup-unused-image` |
| `com.vrudenko.kanban_board.config.AvroSchemaRegistrar` via `PropertiesLauncher` | n/a (in-repo class) | Registers all 14 Avro schemas against a registry URL | Already the sole schema-writing mechanism in this codebase (SCHEMA-01); already proven live against both brokers by hand in Phase 5/Phase 8 bring-up |

**No new packages, actions, or Docker images are introduced by this phase.** Every mechanism above
is copy-and-retarget of something already running green in `deploy-to-netcup`'s job graph.

### Supporting

| Item | Purpose | When to Use |
|------|---------|-------------|
| `jq` | JSON parsing in the Hub/Registry API cleanup scripts | Already used identically in `cleanup-old-images`/`cleanup-unused-image`; reuse the exact `jq` filter expressions, only the repo-name variable changes |
| `curl` retry/poll loop (`until`/`for` + `sleep`, no extra tool) | CI-04's new health-check step | Bash built-ins are sufficient — no need for a dedicated GitHub Action (`wait-for-it`, etc.); see Code Examples below |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Two separate `flyway-verify`/`deploy-to-nonprod` jobs mirroring production 1:1 | A single parameterized/matrixed job (`strategy.matrix: [prod, nonprod]`) | Matrix would look DRYer but collapses two independently-scoped `environment:` blocks into one job — GitHub Environments secret-scoping is applied per-job, not per-matrix-cell in a way that cleanly isolates production from nonprod secrets within the same job's log/context. Two explicit jobs keep the CI-02 boundary structurally simple and match this repo's existing convention (no matrix used anywhere in `deploy.yml` today) |
| Bash `curl` retry loop for CI-04 | A dedicated GitHub Action (e.g. a "wait for URL" marketplace action) | A marketplace action is one more third-party `uses:` to vet/pin/trust for a ~10-line loop `curl`/`sleep` already expresses; this repo's CLAUDE.md constraints (Phase 10 owns digest-pinning) argue against adding a new unpinned third-party action mid-phase |
| A single Docker Hub token reused as-is for both environments | A second Docker Hub PAT scoped to only the nonprod repo | Per-repository Docker Hub token scoping is a Pro/Team-plan-only feature (see Common Pitfalls) — not confirmed available on this account. Flagged as an open question, not silently resolved either way |

**Installation:** No new package installs. All actions/images above are already referenced by
version/tag in the existing `deploy.yml`; the new jobs reuse those same `uses:`/image references.

**Version verification:** `appleboy/scp-action@v1.0.0` and `appleboy/ssh-action@v1.2.5` were
re-confirmed against their GitHub releases as "latest" as of plan 05-05's execution
[CITED: docs/INFRA_RUNBOOK.md "Task 3 — Deploy job rewrite"] — this phase deliberately reuses the
same pinned tags rather than bumping them (digest-pinning and version bumps are explicitly Phase
10's scope per CONTEXT.md's phase boundary, not this phase's).

## Package Legitimacy Audit

**Not applicable — this phase installs no new external packages.** Every action/image/API used is
already live in production's `deploy-to-netcup` job graph, verified there through real CI runs
(see `docs/INFRA_RUNBOOK.md`'s "Automated deploy — Plan 05-05" section). No `npm view`/`pip
index`/`cargo search` step is needed because nothing new crosses the package-manager boundary.

## Architecture Patterns

### System Architecture Diagram

```
push to master
      |
      v
+-----------+
|  setup    |  (base_image_name output)
+-----------+
      |
      v
+-------------+
| run-tests   |
+-------------+
      |
      v
+---------------------------+
| build-and-push-docker-    |  --> pushes ONE image tag (short SHA)
| image                     |      to Docker Hub, shared by both paths
+---------------------------+
      |
      +-----------------------------+
      |                             |
      v                             v
+----------------+          +------------------------+
| flyway-verify  |          | flyway-verify-nonprod   |   <- environment: staging
| (production DB)|          | (nonprod Neon branch)   |      reads only staging DB_* secrets
+----------------+          +------------------------+
      |                             |
      v                             v
+----------------+          +------------------------+
| deploy-to-     |          | deploy-to-nonprod        |   <- environment: staging
| netcup         |          | (own concurrency group)  |      own SSH identity (D-01)
| environment:   |          +------------------------+
| production     |                  |
+----------------+                  v
      |                     +------------------------+
      |                     | health-check-nonprod    |   <- CI-04: poll /api/actuator/health
      |                     | (poll loop, this job's  |      until 200 or timeout; fails the
      |                     | own step, not Compose)  |      workflow if it never comes up
      |                     +------------------------+
      |                             |
      v                             v
+----------------+          +------------------------+
| register-      |          | register-schemas-       |   Both run the SAME AvroSchemaRegistrar
| schemas-prod   |          | nonprod                 |   PropertiesLauncher invocation, each
| environment:   |          | environment: staging    |   against its own broker's :8081, reached
| production     |          |                          |   only via SSH + the VM's own Compose
+----------------+          +------------------------+   network (no port published on either)
      |                             |
      v                             v
+----------------+          +------------------------+
| cleanup-old-   |          | cleanup-old-images-      |   Parameterized by repo name only --
| images         |          | nonprod                  |   nonprod's sweep can only ever see
| cleanup-unused-|          | cleanup-unused-image-     |   nonprod's own Docker Hub repo tags
| image          |          | nonprod                  |
+----------------+          +------------------------+

Neither vertical path's jobs gate, wait on, or share a `needs:`/`concurrency.group` with the
other — the only shared node in the graph is `build-and-push-docker-image`, which both paths
consume read-only (they neither modify nor re-trigger it).
```

### Recommended Job Additions to `deploy.yml`

```
jobs:
  ...existing setup/run-tests/build-and-push-docker-image/flyway-verify/deploy-to-netcup/
     cleanup-old-images/cleanup-unused-image unchanged...

  flyway-verify-nonprod:
    needs: [ setup, run-tests ]
    environment: staging
    # same guard/run shape as flyway-verify, against nonprod's own DB_HOST/DB_NAME/DB_USER/DB_PASS

  deploy-to-nonprod:
    needs: [ build-and-push-docker-image, flyway-verify-nonprod ]
    environment: staging
    concurrency:
      group: deploy-to-nonprod-vm
      cancel-in-progress: false
    # scp docker-compose.nonprod.yml to /opt/deploy/kanban-board-nonprod/
    # ssh: cd /opt/deploy/kanban-board-nonprod && export IMAGE_TAG=... &&
    #      docker compose --env-file ./.env.nonprod -f docker-compose.nonprod.yml --profile nonprod pull app-nonprod &&
    #      up -d app-nonprod

  health-check-nonprod:
    needs: [ deploy-to-nonprod ]
    environment: staging
    # curl retry loop against https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api/actuator/health

  register-schemas-nonprod:
    needs: [ health-check-nonprod ]     # or deploy-to-nonprod directly -- see Open Questions
    environment: staging
    # ssh: docker compose -f docker-compose.nonprod.yml --env-file ./.env.nonprod --profile nonprod
    #      run --rm --entrypoint java app-nonprod -Dloader.main=...AvroSchemaRegistrar
    #      -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher http://redpanda-nonprod:8081

  register-schemas-production:
    needs: [ deploy-to-netcup ]
    environment: production
    # same shape, against http://redpanda:8081, using the production SSH identity

  cleanup-old-images-nonprod:
    needs: [ deploy-to-nonprod, build-and-push-docker-image, setup ]
    environment: staging
    if: success()
    # identical Hub API v2 pattern, DOCKERHUB_REPOSITORY_NONPROD in place of DOCKERHUB_REPOSITORY

  cleanup-unused-image-nonprod:
    needs: [ deploy-to-nonprod, build-and-push-docker-image, setup ]
    environment: staging
    if: failure()
```

Note on `register-schemas-production`: CI-05's requirement text says registration must complete
"before that environment's app serves traffic" *within* each environment, but explicitly must not
gate/be gated by the *other* environment's deploy path. Since production's app already serves
traffic the instant `deploy-to-netcup`'s `up -d app` succeeds (schema registration currently happens
manually, after the fact, per the runbook), inserting `register-schemas-production` as a hard gate
*before* `deploy-to-netcup`'s `up -d` would be a behavior change beyond this phase's literal text.
**Flagged as an Open Question below** — the safer, most literal reading of the requirement is
satisfied by running `register-schemas-production` immediately after `deploy-to-netcup` (so it
races traffic by, at most, the CI job's own startup latency, matching the honesty of "the deploy
already exposes traffic before schemas are re-registered today, and this phase does not regress
that"), while nonprod, being newly automated from scratch, can be built with the schema step ahead
of (or the health check gating) the point traffic is meaningfully exercised. The planner must decide
this explicitly rather than copy the ordering unexamined.

### Pattern 1: Environment-scoped job (CI-02)

**What:** The `environment:` key on a job restricts which secrets that job's `secrets.*` context can
resolve, and (if the environment has protection rules) gates job start on those rules.
**When to use:** Every new job this phase adds that must read a nonprod-only or production-only
secret.
**Example:**
```yaml
# Source: https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/deploy-to-environment
jobs:
  deploy-to-nonprod:
    environment: staging
    steps:
      - run: echo "only staging-scoped secrets are visible here"
```
[VERIFIED: context7 /websites/github_en_actions — "Specify an environment in a workflow job"]

Per D-04, neither `production` nor `staging` gets a required-reviewer or wait-timer rule — both
environments exist purely for secret partitioning, so referencing `environment: staging` never
pauses the job for approval.

### Pattern 2: Per-target concurrency groups (CI-01 success criterion 1)

**What:** `concurrency.group` at job level serializes only jobs sharing that exact group string;
different groups run fully in parallel with no interaction.
**When to use:** `deploy-to-nonprod` needs its own group, distinct from `deploy-to-netcup-vm`
[VERIFIED: .github/workflows/deploy.yml:165-167], so a nonprod deploy never queues behind (or
blocks) a production deploy.
**Example:**
```yaml
# Source: https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax
concurrency:
  group: deploy-to-nonprod-vm
  cancel-in-progress: false
```
[VERIFIED: context7 /websites/github_en_actions — "jobs.<job_id>.concurrency"]

`cancel-in-progress: false` must be kept, mirroring `deploy-to-netcup`'s own rationale
[VERIFIED: .github/workflows/deploy.yml:156-164] — cancelling mid-SCP would leave a half-copied
Compose manifest on the VM.

### Pattern 3: Docker Hub Registry-API-v2 cleanup, parameterized by repo name

**What:** Both cleanup jobs already read `${{ needs.setup.outputs.base_image_name }}` as their only
repo-identifying variable [VERIFIED: .github/workflows/deploy.yml:245,265,286,291,299]. A
nonprod-equivalent job only needs a second `base_image_name`-shaped output
(`$DOCKERHUB_USER/kanban-board-backend-nonprod`, or whatever name is chosen) computed the same way
in `setup`, and the rest of the script is a literal copy.
**When to use:** `cleanup-old-images-nonprod`/`cleanup-unused-image-nonprod`.
**Example (list+delete, Hub API v2 — copy of the already-fixed production pattern):**
```bash
# Source: .github/workflows/deploy.yml (cleanup-old-images, lines 229-275) -- already
# hardened against the Basic-auth-rejection bug (2026-08-16) and the pagination bug (2026-08-17)
HUB_TOKEN=$(curl -s -H "Content-Type: application/json" -X POST \
  -d "{\"username\": \"$DOCKERHUB_USER\", \"password\": \"${{ secrets.DOCKERHUB_TOKEN_NONPROD || secrets.DOCKERHUB_TOKEN }}\"}" \
  "https://hub.docker.com/v2/users/login/" | jq -r .token)
# ... same pagination-following list, same tag != current-tag delete loop ...
```
[VERIFIED: .github/workflows/deploy.yml:210-276]

### Anti-Patterns to Avoid

- **A bare `docker compose up -d` without naming the service, run from the nonprod directory
  against a file that could resolve unexpected services:** `docker-compose.nonprod.yml` gates both
  services behind `profiles: ["nonprod"]` [VERIFIED: docker-compose.nonprod.yml:49,127] specifically
  so an un-profiled `up -d` creates nothing — CI's deploy step must always pass `--profile nonprod`
  explicitly, matching the operator note already in the runbook [VERIFIED:
  docs/INFRA_RUNBOOK.md:1148-1157].
- **Reusing `deploy-to-netcup-vm` (or any shared string) as the nonprod job's concurrency group:**
  would silently reintroduce cross-environment queuing/blocking, directly violating this phase's
  first success criterion.
- **Copying `cleanup-old-images`'s DELETE URL construction without re-verifying the repo-name path
  segment is present:** the exact bug already found and fixed in production
  [VERIFIED: docs/INFRA_RUNBOOK.md:578-585] (`$DOCKERHUB_USER/tags/$TAG/` missing
  `$DOCKERHUB_REPOSITORY`) is trivial to reintroduce by hand-typing a "similar" nonprod version
  instead of parameterizing the same script.
- **Letting the `needs:`/`if:` chain for `cleanup-*-nonprod` reference `deploy-to-netcup` (production)
  instead of `deploy-to-nonprod`:** would make nonprod's cleanup fire on production's success/failure
  state, defeating CI-01's parallel-independence requirement.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Waiting for a service to become healthy over HTTP | A custom polling script with ad hoc backoff | A small bounded `for`/`until` + `curl -sf -o /dev/null -w '%{http_code}'` loop with a fixed max-attempts and `sleep` (shown in Code Examples) | This is genuinely simple enough that a marketplace action or library would be over-engineering; a 10-15 line bash loop is auditable in the same file the rest of the deploy logic lives in, and needs no new `uses:` to vet |
| Docker Hub tag deletion | Any bespoke registry client | The existing, already-hardened Hub API v2 (list) + Registry API v2 (digest delete) curl sequences | Reinventing this would reintroduce the exact two bugs (Basic-auth rejection, pagination) production's version was already burned by and fixed |
| Avro schema registration | A second registration mechanism, a raw `curl` against the registry's REST API, or a Gradle-task-only path | `AvroSchemaRegistrar` via the documented `PropertiesLauncher` CLI invocation | CI-05's requirement text explicitly says to reuse this mechanism, not invent a new one; it is also the *only* place in the codebase permitted to write schemas (SCHEMA-01), and it already sets BACKWARD compatibility correctly before each subject's first registration [VERIFIED: src/main/java/com/vrudenko/kanban_board/config/AvroSchemaRegistrar.java:28-31,100-104] |

**Key insight:** every mechanism this phase needs already exists, proven live, in either
`deploy.yml` or the manual runbook sections Phase 8 executed by hand. The work is disciplined
copy-and-retarget with a new credential/target axis on each copy, not new engineering — the risk is
entirely in *forgetting to change one axis* (the "known trap" STATE.md already flags), not in
picking the wrong tool.

## Common Pitfalls

### Pitfall 1: Docker Hub token is not confirmed to support per-repository scoping on this plan tier

**What goes wrong:** CONTEXT.md's decisions (D-01 through D-03) scope the *SSH* credential per
environment but say nothing about `DOCKERHUB_TOKEN`, which today is one repository-level secret
used by `build-and-push-docker-image` and both cleanup jobs. If the nonprod jobs simply read the
same `DOCKERHUB_TOKEN`, then CI-02's "production's secrets are unreachable from [the nonprod] job"
success criterion is only true for the SSH identity, not for the Docker Hub credential — a
compromised or buggy nonprod job step could still authenticate against Docker Hub with production-repo
write/delete power.
**Why it happens:** Docker Hub access tokens *do* support scoping to a specific set of repositories
with Read / Read-Write / Read-Write-Delete permissions, but this granularity is a Pro/Team-plan
feature — [CITED: docs.docker.com/docker-hub/repos/manage/access — "Access management"] and
[CITED: docker.com/blog "Level Up Security with Scoped Access Tokens"]. This account's plan tier was
not verified in this research session — **[ASSUMED]** it is on the Free tier (consistent with the
project's "personal/portfolio, cost-conscious" posture elsewhere — e.g. the ~€4/month fallback VPS
framing in Phase 8), in which case only account-wide (all-repos) tokens are available and true
per-repo scoping is not achievable without a plan upgrade.
**How to avoid:** The planner must make this an explicit decision, not an oversight:
  - **Option A (matches this project's established risk tolerance, per D-02's "reject
    further-hardened complexity" precedent):** keep one `DOCKERHUB_TOKEN`, store it as an
    environment secret in *both* `production` and `staging` (same value, duplicated) so CI-02's
    literal requirement — every secret the nonprod job reads comes from an environment, not a bare
    repository secret — is satisfied, while documenting the residual gap (a compromised nonprod
    workflow step could theoretically call production's Docker Hub endpoints) as an accepted,
    written-down risk, the same pattern this project already uses for the session-ceiling TOCTOU
    overshoot (D-01, quick task 260811-h2v).
  - **Option B (full isolation, more setup cost):** create a second Docker Hub account solely for
    pushing/cleaning the nonprod repo (mirrors the SSH new-user pattern exactly), store its token as
    `DOCKERHUB_TOKEN_NONPROD` under the `staging` environment only. Requires `build-and-push-docker-image`
    to also push under the second account/namespace for nonprod, or a second build+push step —
    doubles the image build unless the image is copied/re-tagged post-build instead, adding real
    complexity CONTEXT.md's decisions do not currently call for.
  - **Recommendation:** Option A, flagged for explicit user confirmation before the plan locks it in
    — it is the option consistent with every other proportionality call this phase's CONTEXT.md
    already made (D-02, D-04), but it is a genuine, not-yet-user-confirmed gap in the "credentials
    scoped so the nonprod path cannot reach... production" framing of the phase goal for one
    specific credential class.
**Warning signs:** If the plan silently reuses `DOCKERHUB_TOKEN` as a bare repository secret (not
even duplicated into both GH Environments), CI-02's own literal text ("scoped through GitHub
Environments rather than shared unscoped repository secrets") is violated for that one secret.

### Pitfall 2: The nonprod Docker Hub repository does not exist yet

**What goes wrong:** `build-and-push-docker-image`'s production image already pushes into an
existing repository (`rudenkovladimir/kanban-board-backend`). A second repository for nonprod's
image (CI-03) has never been created. Docker's own documentation describes repository creation as
an explicit action (via the web UI or the Hub API's `POST /v2/repositories/`), not something that
reliably happens as a side effect of the first `docker push` [CITED: docs.docker.com/docker-hub/repos/create].
**Why it happens:** Assuming Docker Hub "just works like a filesystem `mkdir -p`" on first push is a
reasonable but incorrect mental model carried over from registries that do auto-vivify (e.g. some
cloud-provider registries do; Docker Hub's own documented flow does not rely on this).
**How to avoid:** Add an explicit one-time setup step — either a manual repository creation via the
Docker Hub web UI (mirrors D-07's "whether GitHub Environments are created via `gh` CLI or the web
UI" — execution-mechanism discretion, no vision decision needed) or a single `curl -X POST
https://hub.docker.com/v2/repositories/` call using the same JWT-login-token pattern
`cleanup-old-images` already uses, run once before `deploy-to-nonprod`'s first real invocation. This
does **not** need to be a permanent CI step — it is a one-time provisioning action, not part of the
steady-state job graph.
**Warning signs:** `build-and-push-docker-image`'s nonprod push step (or a parallel
`build-and-push-docker-image-nonprod` step, if the image needs re-tagging under a second repo name)
fails with `repository does not exist` / `denied: requested access to the resource is denied` on its
very first run.

### Pitfall 3: CI-04's health check is genuinely new work, not a copy

**What goes wrong:** CONTEXT.md's "Claude's Discretion" note says CI-04's retry/timeout parameters
"mirror the existing production health-check pattern from v1.2 Phase 5" — but Phase 5's health
checks were all run by hand (`curl https://.../api/actuator/health` typed by a human/agent during
the manual bring-up), never automated as a CI *step* with pass/fail semantics
[VERIFIED: docs/INFRA_RUNBOOK.md:189-191,550-551]. There is no existing bash retry-loop in
`deploy.yml` to literally copy.
**Why it happens:** "Mirrors the existing pattern" is true of the *target endpoint and expected
response shape* (`GET /api/actuator/health` -> `200 {"status":"UP"}`), not of an existing *CI
polling mechanism* — that part must be written new.
**How to avoid:** Write a small bounded retry loop (see Code Examples) as a new step in
`deploy-to-nonprod` or a following `health-check-nonprod` job. Fail the workflow (non-zero exit)
if the endpoint never returns `200` within the bound — this is exactly CI-04's success criterion
("a nonprod stack that fails to come up fails the workflow visibly instead of passing silently").
**Warning signs:** A plan that describes CI-04 as "reuse the health check" without specifying the
actual loop/timeout/step location is under-specified — the planner needs a concrete retry count and
interval, not a citation to a manual runbook entry.

### Pitfall 4: `IMAGE_TAG=latest` does not exist on Docker Hub (already hit once, in Phase 8)

**What goes wrong:** Phase 8's own bring-up hit this directly: the first nonprod schema-registration
attempt failed with `docker.io/rudenkovladimir/kanban-board-backend:latest: not found`, because this
repo's CI publishes only per-commit short-SHA tags, never a floating `latest`
[VERIFIED: docs/INFRA_RUNBOOK.md:1125-1133].
**Why it happens:** `.env.nonprod`'s (and any future nonprod job's) `IMAGE_TAG` must be set from the
same `needs.build-and-push-docker-image.outputs.image_tag` output `deploy-to-netcup` already
consumes [VERIFIED: .github/workflows/deploy.yml:199], not hand-typed or defaulted to `latest`.
**How to avoid:** `deploy-to-nonprod`'s `export IMAGE_TAG=...` step must reference
`${{ needs.build-and-push-docker-image.outputs.image_tag }}` exactly like `deploy-to-netcup`'s
existing step does — this is a one-line copy, but the failure mode is real and has already happened
once in this project.
**Warning signs:** A `docker compose ... pull app-nonprod` step failing with "not found" or "manifest
unknown" on the very first real nonprod CI deploy.

## Code Examples

### CI-04: bounded health-check retry loop (new — no existing CI equivalent to copy)

```bash
# Source: pattern only -- no existing deploy.yml step to cite; endpoint/response shape confirmed
# live at docs/INFRA_RUNBOOK.md:1033 ("curl .../api/actuator/health -> 200 {"status":"UP"}")
HEALTH_URL="https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api/actuator/health"
ATTEMPTS=12
SLEEP_SECONDS=5
for i in $(seq 1 "$ATTEMPTS"); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$HEALTH_URL" || echo "000")
  if [ "$STATUS" = "200" ]; then
    echo "Nonprod healthy after $i attempt(s)."
    exit 0
  fi
  echo "Attempt $i/$ATTEMPTS: got HTTP $STATUS, retrying in ${SLEEP_SECONDS}s..."
  sleep "$SLEEP_SECONDS"
done
echo "::error::Nonprod health endpoint never returned 200 after $ATTEMPTS attempts ($((ATTEMPTS * SLEEP_SECONDS))s total)."
exit 1
```
12 attempts x 5s = 60s bound, chosen to comfortably exceed `app-nonprod`'s Compose `start_period:
30s` + `interval: 10s` x `retries: 5` healthcheck window [VERIFIED: docker-compose.nonprod.yml:158-163]
plus DNS/TLS/network latency margin — the planner should size this against the same numbers, not
invent unrelated ones.

### CI-05: schema registration one-off container invocation (already proven live, both brokers)

```bash
# Source: docs/INFRA_RUNBOOK.md lines 163-165 (production) and 1018-1020 (nonprod) -- both
# already executed successfully by hand during Phase 5 / Phase 8 bring-up
docker compose -f docker-compose.nonprod.yml --env-file ./.env.nonprod --profile nonprod run --rm \
  --entrypoint java app-nonprod -Dloader.main=com.vrudenko.kanban_board.config.AvroSchemaRegistrar \
  -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher http://redpanda-nonprod:8081
```
This must run as an `appleboy/ssh-action` step (same as `deploy-to-nonprod`'s own script), since the
registry has no published host port on either broker [VERIFIED: docker-compose.nonprod.yml:62-63
"No `ports:` key at all"] — it is reachable only from inside the VM's own Compose network, which
means from a container running on the VM, not from the GitHub-hosted runner directly.

### GitHub Environments: per-job scoping (new syntax for this repo, not yet used anywhere in it)

```yaml
# Source: context7 /websites/github_en_actions -- "Specify an environment in a workflow job"
# https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/deploy-to-environment
jobs:
  deploy-to-nonprod:
    environment: staging
```

### Creating the two environments with no protection rules (D-04)

```bash
# Empty PUT body -- no reviewers, no wait_timer, no deployment_branch_policy restriction,
# matching D-04's "fully unattended" requirement exactly.
gh api --method PUT repos/RudVlad473/kanban-board-backend/environments/production
gh api --method PUT repos/RudVlad473/kanban-board-backend/environments/staging
```
[CITED: docs.github.com/en/rest/deployments/environments — PUT endpoint accepts an empty/default
body when no protection rules are wanted] -- `gh` is confirmed installed and authenticated with the
`repo` OAuth scope in this environment (`gh auth status` -> `Token scopes: 'gist', 'read:org',
'repo', 'workflow'`), sufficient for this endpoint per GitHub's own scope requirement.

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| One repository-wide secret set, implicitly available to every job | GitHub Environments scoping secrets per job via `environment:` | This phase (CI-02) is this repo's first use of GitHub Environments — nothing to migrate away from except the implicit "every job sees every repository secret" default | Establishes the pattern Phase 10's later hardening work can build on (e.g. requiring environment protection for future production-affecting jobs), though D-04 deliberately does not add protection rules now |
| Manual, by-hand schema registration after each deploy (Phase 5 Task 2, Phase 8 bring-up step 6) | CI-driven registration on every push (CI-05) | This phase | Removes the class of bug where a deploy ships a new/changed Avro schema and nobody remembers to register it before the first producer publish fails at runtime (`auto.register.schemas=false` makes this a hard failure, not a lazy self-heal) |

**Deprecated/outdated:** None — this phase does not remove or replace any existing mechanism, it
adds a second, parallel path using the same mechanisms.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | This Docker Hub account is on the Free tier (no per-repository token scoping available) | Common Pitfalls, Pitfall 1 | If actually Pro/Team, Option B (a real scoped `DOCKERHUB_TOKEN_NONPROD`) becomes cheap and should be preferred over Option A's accepted-risk duplication — verify the account's plan tier (Docker Hub account settings) before locking in the Pitfall 1 recommendation |
| A2 | Docker Hub does not reliably auto-create a repository on first `docker push` | Common Pitfalls, Pitfall 2 | If it does auto-create (behavior has been reported as inconsistent across accounts/eras in community threads), the explicit one-time creation step becomes unnecessary belt-and-suspenders rather than a hard requirement — low risk either way, since the explicit step is cheap and idempotent (a `POST` against an already-existing repo just 4xxs harmlessly, or can be preceded by a existence-check `GET`) |
| A3 | 60s (12 x 5s) is a sufficient health-check timeout bound for CI-04 | Code Examples | If nonprod's cold-start (Flyway migrations + Redpanda dependency wait) sometimes exceeds 60s under real CI-runner network conditions (as opposed to the VM-local conditions the Compose healthcheck's own 30s `start_period` was tuned against), the retry loop could report a false failure on a stack that would have come up given more time — the planner should size this against real observed CI timing once the job exists, not treat 60s as final |

## Open Questions

1. **Does `DOCKERHUB_TOKEN` need its own nonprod-scoped credential, or is duplicating the same value
   into both GH Environments an accepted, documented gap?**
   - What we know: CONTEXT.md's D-01/D-02/D-03 decided this question for the SSH identity but are
     silent on Docker Hub. Per-repo Docker Hub token scoping requires a paid plan (unconfirmed here).
   - What's unclear: The account's actual plan tier, and whether the user considers this credential
     surface in-scope for the same "cannot reach, overwrite, or degrade production" framing the
     phase goal states, or an accepted proportionality gap like D-02's SSH hardening cutoff.
   - Recommendation: Surface this explicitly during planning (or a quick discuss-phase follow-up)
     rather than let the planner pick silently — see Pitfall 1's Option A/B breakdown.

2. **Should `register-schemas-production` run immediately after `deploy-to-netcup` (as this research
   recommends) or somehow gate `up -d app` itself (a behavior change beyond today's manual-registration
   status quo)?**
   - What we know: CI-05's requirement text requires registration-before-traffic *within* an
     environment, but the production path currently has zero automated registration at all — traffic
     already flows before any registration happens today (it's done by hand, later).
   - What's unclear: Whether "matches current behavior, now automated" (register right after deploy,
     accepting the same brief unregistered window that already exists today) or "genuinely gates
     traffic" (a structural change: hold Caddy/the app's public exposure until registration
     succeeds) is what CI-05 actually wants for the *production* half specifically.
   - Recommendation: Default to the non-regressing reading (register immediately after
     `deploy-to-netcup`, not gating it) since CONTEXT.md's "Job/step ordering details for CI-05" note
     calls this "already fully specified by the CI-05 requirement text itself" — but the planner
     should re-read the exact requirement text once more before finalizing, since this research found
     the ordering less obviously settled than that note implies for production specifically (nonprod,
     having no existing traffic to protect, is unambiguous: register before/as part of considering
     the nonprod deploy "complete").

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| `gh` CLI (local, for one-time environment creation) | Creating `production`/`staging` GitHub Environments (CI-02) | ✓ [VERIFIED: `gh --version` = 2.97.0, `gh auth status` shows `repo` scope, logged in as `RudVlad473`] | 2.97.0 | GitHub web UI (Settings -> Environments) — equally valid per CONTEXT.md's own discretion note |
| `docker`/Docker Hub API reachability from `ubuntu-latest` runners | Cleanup jobs, image push | ✓ (already proven live by every existing production CI run) | n/a | — |
| A second Docker Hub repository for nonprod images | CI-03 | ✗ (does not exist yet) | — | One-time manual creation via Docker Hub web UI, or a scripted `POST /v2/repositories/` using the same JWT-login pattern `cleanup-old-images` already has |

**Missing dependencies with no fallback:** None — the one missing piece (the nonprod Docker Hub
repository) has a clear, cheap fallback (manual or scripted one-time creation).

**Missing dependencies with fallback:** The nonprod Docker Hub repository (see above).

## Security Domain

### Applicable ASVS Categories

This phase is CI/CD infrastructure, not application request-handling code — most ASVS V2-V6
categories (authentication, session management, access control, cryptography as applied to the
running app) are untouched. The categories that do apply are configuration/secrets-handling ones:

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V14 Configuration | Yes | Secrets never echoed to workflow logs (existing production jobs already avoid this — e.g. `flyway-verify`'s `DB_HOST` guard is "inspected without ever being printed" [VERIFIED: docs/INFRA_RUNBOOK.md:117-119]); the new nonprod jobs must follow the identical discipline for `DB_HOST`, `DOCKERHUB_TOKEN`(_NONPROD), and the new nonprod SSH key |
| V1 Architecture (secure design) | Yes | Least-privilege credential separation is this phase's whole point (CI-02) — GitHub Environments as the enforced boundary, not merely a naming convention |
| V7 Error Handling / Logging | Yes | The health-check and cleanup steps must fail loudly (`::error::`, non-zero exit) rather than swallow a failed poll/delete — matches this repo's own existing convention in `flyway-verify`'s guard and both cleanup jobs' explicit HTTP-status checks |

### Known Threat Patterns for GitHub Actions multi-environment CI

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| A shared/unscoped secret lets a compromised or buggy nonprod-path step reach production | Elevation of Privilege | GitHub Environments scoping (CI-02) for every secret; flagged explicitly as an open gap for `DOCKERHUB_TOKEN` specifically (Pitfall 1) if Option A is chosen |
| A copy-pasted job accidentally targets production's directory/container/volume/repo | Tampering | Every identity axis (directory, Compose project name, container names, network, Docker Hub repo name) must differ from production's — exactly the "known trap" STATE.md already names for this phase |
| Secret values leaking into CI logs via `echo`/unguarded `set -x` | Information Disclosure | Never `echo` a secret directly; guard-check patterns (like the pooler-marker check) inspect without printing; avoid `set -x` in any step handling a secret-bearing variable |
| A nonprod deploy or cleanup job silently "succeeding" while actually failing (e.g. a health check that never runs, or an unauthenticated cleanup call treated as success) | Repudiation / false confidence | Explicit HTTP-status checks and non-zero exits everywhere, matching the pattern both existing cleanup jobs already use after the 2026-08-16/17 bug fixes |

## Sources

### Primary (HIGH confidence)
- `/websites/github_en_actions` (Context7) — job `environment:` key syntax, `concurrency` syntax, environment-secret scoping semantics
- `.github/workflows/deploy.yml` (in-repo, read directly this session) — every existing job's exact structure, action versions, and script content
- `docs/INFRA_RUNBOOK.md` (in-repo, read directly this session) — nonprod identity table, bring-up sequence, repository secret inventory (exactly 10 secrets), all documented bug fixes
- `docker-compose.nonprod.yml`, `src/main/java/com/vrudenko/kanban_board/config/AvroSchemaRegistrar.java` (in-repo, read directly this session)

### Secondary (MEDIUM confidence)
- docs.github.com REST API docs (via WebSearch) — `PUT /repos/{owner}/{repo}/environments/{name}` accepts an empty body for a no-protection-rules environment
- docs.docker.com — Docker Hub repository creation is an explicit action, not guaranteed auto-created on push; Docker Hub access-token repository-scoping is a Pro/Team-plan feature

### Tertiary (LOW confidence)
- Community forum threads on Docker Hub push/repo-creation behavior — inconsistent reports across accounts/eras, not treated as authoritative; used only to corroborate the docs.docker.com finding, not as the sole basis for Pitfall 2

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — every mechanism is already live and proven in this repo's own CI history
- Architecture: HIGH — direct extension of a verified, well-documented existing job graph
- Pitfalls: MEDIUM — the two Docker Hub findings (token scoping, repo auto-creation) rest on official docs plus community corroboration, not a live test against this specific account's plan tier

**Research date:** 2026-08-18
**Valid until:** 30 days (GitHub Actions/Docker Hub API surfaces are stable; re-verify sooner if
Docker Hub's plan-tier token-scoping policy is confirmed to have changed)
