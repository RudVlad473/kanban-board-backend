# Phase 9: Nonprod Continuous Deploy & Scoped CI Credentials - Context

**Gathered:** 2026-08-18
**Status:** Ready for planning

<domain>
## Phase Boundary

Nonprod stays current with master automatically — deployed, migrated, health-verified, and schema-registered by CI on every push, with the same automated step keeping production's and nonprod's Avro schema registries in step with the deployed code — through credentials scoped so the nonprod path cannot reach, overwrite, or degrade production.

Requirements in scope: CI-01, CI-02, CI-03, CI-04, CI-05 (per ROADMAP.md).

We clarified HOW to implement this. Phase 8 (isolated nonprod stack, already live) is a dependency, not part of this phase's scope. The eight CI/deploy hardening todos (digest-pinning, TruffleHog, dependabot, etc.) are explicitly Phase 10's scope — this phase must not attempt them, even though it touches the same `deploy.yml` file.

</domain>

<decisions>
## Implementation Decisions

### SSH Credential Isolation (CI-02)

- **D-01:** Nonprod's deploy path gets its own restricted deploy identity on the VM — a new, separate Linux user (distinct from the existing `deploy` user) with its own SSH keypair, confined to `/opt/deploy/kanban-board-nonprod/` via ordinary Unix file permissions (no read/write access to `/opt/deploy/kanban-board-backend/`). Chosen over reusing `NETCUP_SSH_KEY`/`deploy` because both prod and nonprod currently share that single account on the same VM (Phase 8 created the nonprod directory under it) — GitHub Environments scope which secret *values* a job can read, but a shared credential still grants full shell access to production's directory regardless of which job reads it. Rejected the further-hardened forced-command/restricted-shell option as unnecessary complexity beyond what this project's risk tolerance calls for. — **Reversibility:** costly — swapping deploy identity later means re-provisioning the VM-side user, regenerating the SSH keypair, redistributing the new secret through GitHub Environments, and updating every workflow step's credential reference; treat this as the standing nonprod deploy identity, not a placeholder.
- **D-02:** Confinement mechanism is standard Unix user/file permissions only — no SSH forced-command or restricted-shell hardening. Mirrors the effort tier already established for the existing `deploy` user (non-root, own directory, created via a one-time root `mkdir`+`chown` per Phase 5/Phase 8 precedent in `docs/INFRA_RUNBOOK.md`).
- **D-03:** The CI-05 schema-registration step for nonprod (which reaches the broker over the same SSH path the deploy job uses, per ROADMAP's phase rationale) uses this same new nonprod-scoped deploy identity — it is part of the nonprod deploy path, not a separate credential surface.

### Environment Approval Gates

- **D-04:** Neither the `production` nor the `staging` GitHub Environment gets a required-reviewer or wait-timer approval gate. Both stay fully unattended — every push to master deploys both automatically, exactly matching this project's existing always-on CI/CD posture. GitHub Environments exist here purely as the secret-scoping mechanism CI-02 requires, not as a release gate.

### Nonprod Image Retention (CI-03)

- **D-05:** Nonprod's separate Docker Hub repo gets its own `cleanup-old-images`-equivalent job — deletes every non-current tag after each successful nonprod deploy, mirroring production's existing job exactly. Rejected leaving it unbounded: nonprod deploys on every push at the same rate as production, so it would accumulate tags at an identical pace — the same unbounded-growth problem prod's job already exists to solve, just relocated to a second repo.
- **D-06:** Nonprod also gets a `cleanup-unused-image`-equivalent job (deletes the just-pushed tag by digest when the nonprod deploy itself fails), for full parity with production's retention behavior rather than partial coverage.

### Claude's Discretion

- Exact naming for the new nonprod-scoped Linux user, its SSH secret name(s) in GitHub, and the nonprod Docker Hub repository name — mechanical choices, no user preference expressed beyond "keep it clearly distinct from production's."
- Exact health-check retry/timeout parameters for CI-04 — mirrors the existing production health-check pattern from v1.2 Phase 5; no new decision needed.
- Job/step ordering details for CI-05 (schema registration completing before an environment's app serves traffic) — already fully specified by the CI-05 requirement text itself; implementation detail, not a vision decision.
- Whether the new GitHub Environments are created via `gh` CLI or the GitHub web UI — execution mechanism, not a scope decision.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Existing CI/deploy pipeline (the pattern this phase extends)
- `.github/workflows/deploy.yml` — the full current job graph (setup → run-tests → build-and-push-docker-image → flyway-verify → deploy-to-netcup → cleanup-old-images/cleanup-unused-image); CI-01/CI-03/CI-04/CI-05 all extend this file with parallel nonprod-target jobs, never gating/gated by the existing production path
- `docs/INFRA_RUNBOOK.md` — the "Repository secrets" table (10 existing secrets: `NETCUP_SSH_KEY`, `NETCUP_DEPLOY_USER`, `NETCUP_HOST`, `NETCUP_HOST_FINGERPRINT`, `DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASS`, `DOCKERHUB_TOKEN`, plus one more per the "exactly 10" note), the nonprod bring-up section (VM directory `/opt/deploy/kanban-board-nonprod/`, Compose project `kanban-board-nonprod`, profile `nonprod`, container names `kanban-nonprod-app`/`kanban-nonprod-redpanda`), and both "Manual deploy" sections documenting the exact schema-registration invocation CI-05 must automate

### Nonprod stack (Phase 8's delivered target this phase automates against)
- `docker-compose.nonprod.yml` — the nonprod service shape CI-01's deploy job must `pull`/`up -d` against (mirrors `deploy-to-netcup`'s `docker compose ... pull app && ... up -d`)
- `.env.nonprod.example` — the env-var shape nonprod's own secret set must populate
- `src/main/java/com/vrudenko/kanban_board/config/AvroSchemaRegistrar.java` — the exact mechanism CI-05 automates (`PropertiesLauncher` one-off-container invocation, documented live in `docs/INFRA_RUNBOOK.md`'s Task 1); idempotent, safe to re-run every deploy

### Requirements and roadmap
- `.planning/REQUIREMENTS.md` — CI-01..05 full text, Traceability table (confirms all eight HARDEN-* todos are Phase 10, not this phase, despite touching the same `deploy.yml`)
- `.planning/ROADMAP.md` — Phase 9 goal, 5 success criteria, dependency note on Phase 8, and the phase-mapping rationale explaining why CI-02 must sequence ahead of CI-01 and why CI-05 belongs here rather than Phase 8
- `.planning/phases/08-isolated-nonprod-environment-live-and-resettable/08-CONTEXT.md` — Phase 8's decisions (D-05 hostname, D-06 CORS origin, D-07 colocate-vs-fallback-VPS) that this phase's CI jobs must not disturb

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `deploy-to-netcup`'s job structure in `deploy.yml` (checkout → scp compose/Caddyfile → ssh pull+up, with `concurrency: { group: ..., cancel-in-progress: false }` serializing against the shared VM) — the direct template for a `deploy-to-nonprod` job, with its own concurrency group so a nonprod deploy queues against other nonprod deploys, never against production's
- `cleanup-old-images`'s Docker Hub Registry-API-v2 token-exchange-then-list-then-delete pattern (already hardened against the Basic-auth-rejection and pagination bugs found live in production) — directly reusable for nonprod's own repo, parameterized by repository name
- `flyway-verify`'s pooled-endpoint guard (`DB_HOST` inspected for the `-pooler` marker, never printed) — the same guard pattern applies to nonprod's own DB_HOST secret in a `flyway-verify-nonprod` job

### Established Patterns
- Every existing deploy job pins the SSH host key by fingerprint (`NETCUP_HOST_FINGERPRINT`) on both the scp and ssh steps — the new nonprod deploy job reuses the same fingerprint (same physical host), only the user/key differ per D-01
- `docker-compose.prod.yml`'s `x-logging` anchor and healthcheck-gated bring-up — `docker-compose.nonprod.yml` (Phase 8) already established the nonprod-side equivalent; CI-04's health check polls the same shape of endpoint Phase 5 already proved for production

### Integration Points
- GitHub Environments (`production`, `staging`) are the boundary between this phase's new nonprod jobs and the existing production jobs' secrets — every new job in `deploy.yml` must declare `environment: staging` (or `production` for the schema-registration step's production half) so scoping is enforced by GitHub itself, not just by convention
- `cleanup-old-images`/`cleanup-unused-image`'s `needs:` chains determine what "success"/"failure" means for the cleanup trigger — the nonprod-equivalent jobs need their own `needs:` chain against the nonprod deploy job specifically, not the production one

</code_context>

<specifics>
## Specific Ideas

No UI is involved — this is a backend/CI-only infrastructure phase. No specific visual/branding requirements were raised.

</specifics>

<deferred>
## Deferred Ideas

None raised beyond what ROADMAP.md already scopes to Phase 10 (the eight hardening todos).

### Reviewed Todos (not folded)

`cross_reference_todos` ran `todo.match-phase 9` and returned 24 keyword-matched candidates. The highest-scoring group (dependabot `github-actions` ecosystem, TruffleHog live-credential pass, digest-pinning, gradle cache in `run-tests`, gitleaks-in-worktree, `security-scan.yml` stale comment/actions, cookie `Secure` flag, README expansion) are all real, in-scope v1.3 work — but REQUIREMENTS.md's Traceability table already maps every one of them to Phase 10, not this phase, despite several sharing keywords ("deploy", "github", "phase") with Phase 9's CI focus. None were presented as fold candidates here: folding them into Phase 9 would blur this phase's goal exactly as ROADMAP.md's phase-mapping rationale already warns against. The remaining lower-scoring matches (Java 25 bump, NullAway, OpenAPI breaking-change detection, JavaDoc verbosity policy, virtual threads, alert-service microservice exploration, etc.) are unrelated backend/tooling debt caught by generic keyword collisions. Not individually listed; see `.planning/todos/pending/` directly if a re-triage is ever wanted.

</deferred>

---

*Phase: 9-Nonprod Continuous Deploy & Scoped CI Credentials*
*Context gathered: 2026-08-18*
