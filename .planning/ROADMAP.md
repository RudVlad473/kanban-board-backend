# Roadmap: Kanban Board Backend — Epic 2 Completion

## Milestones

- ✅ **v1.0 Optimistic Locking** — Phase 1 (shipped 2026-08-01)
- ✅ **v1.1 Kafka Activity Feed** — Phases 2-3 (shipped 2026-08-03)
- ✅ **v1.2 Infra Migration & Schema Registry** — Phases 4, 04.1, 04.2, 5, 6, 7, 07.1 (shipped 2026-08-17)
- 🚧 **v1.3 Nonprod Environment & CI Hardening** — Phases 8-10 (in progress)

## Phases

**Phase Numbering:**

- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

<details>
<summary>✅ v1.0 Optimistic Locking (Phase 1) — SHIPPED 2026-08-01</summary>

- [x] Phase 1: Optimistic Locking (3/3 plans) — completed 2026-08-01

Full details: [milestones/v1.0-ROADMAP.md](milestones/v1.0-ROADMAP.md)

</details>

<details>
<summary>✅ v1.1 Kafka Activity Feed (Phases 2-3) — SHIPPED 2026-08-03</summary>

- [x] Phase 2: Kafka Foundation, Domain Events & Move Endpoint (3/3 plans) — completed 2026-08-01
- [x] Phase 3: Activity Log Consumer, Reliability & Read API (3/3 plans) — completed 2026-08-02

Full details: [milestones/v1.1-ROADMAP.md](milestones/v1.1-ROADMAP.md)

</details>

<details>
<summary>✅ v1.2 Infra Migration & Schema Registry (Phases 4, 04.1, 04.2, 5, 6, 7, 07.1) — SHIPPED 2026-08-17</summary>

- [x] Phase 4: Schema Registry (4/4 plans) — completed 2026-08-04
- [x] Phase 04.1: Flyway database migration implementation (INSERTED) (3/3 plans) — completed 2026-08-05
- [x] Phase 04.2: Testcontainers Postgres, drop H2 (INSERTED) (3/3 plans) — completed 2026-08-06
- [x] Phase 6: Mock-up Feature Gap Closure (7/7 plans) — completed 2026-08-09
- [x] Phase 7: Restructure test folder (7/7 plans) — completed 2026-08-09
- [x] Phase 07.1: Address hard blockers and inconsistencies from the frontend-integration-readiness audit (INSERTED) (9/9 plans) — completed 2026-08-17
- [x] Phase 5: Infra Migration (6/6 plans) — completed 2026-08-17

Full details: [milestones/v1.2-ROADMAP.md](milestones/v1.2-ROADMAP.md)

</details>

### 🚧 v1.3 Nonprod Environment & CI Hardening (In Progress)

**Milestone Goal:** Stand up a resource-shrunk, production-isolated nonprod environment on the existing Netcup VPS — its own Neon branch, its own Redpanda broker/registry, its own HTTPS hostname — deployed continuously by CI and resettable to a known state, so a future frontend repo's Playwright E2E suite has a real, non-mocked target; bundled with the CI/deploy hardening todos that v1.2's deploy.yml rewrite unblocked.

- [x] **Phase 8: Isolated Nonprod Environment, Live and Resettable** - A second, production-isolated stack (Neon branch + own Redpanda + own HTTPS hostname) running on the existing VPS with measured resource caps and a curl-driven data reset (completed 2026-08-18)
- [x] **Phase 9: Nonprod Continuous Deploy & Scoped CI Credentials** - Every push to master redeploys, re-registers Avro schemas for, and health-checks nonprod through GitHub Environments-scoped secrets, with zero ability to disturb production (completed 2026-08-19)
- [ ] **Phase 10: CI & Deploy Hardening** - The eight accumulated hardening todos: dependabot actions ecosystem, TruffleHog verification, digest-pinned actions, gradle cache, gitleaks worktree fix, security-scan cleanup, `Secure` session cookie, README architecture showcase

## Phase Details

### Phase 8: Isolated Nonprod Environment, Live and Resettable

**Goal**: A second deployment of this app is live over real HTTPS at its own stable hostname, provably isolated from production at every layer that matters (database, Kafka broker, schema-registry compatibility history, container/network/volume identity, secrets), sized by live measurement rather than arithmetic, and returnable to a known-clean baseline on demand.
**Depends on**: Nothing new — builds on the production stack already live from v1.2 Phase 5 (Netcup VPS, Neon, Redpanda, Caddy)
**Requirements**: NONPROD-01, NONPROD-02, NONPROD-03, NONPROD-04, NONPROD-05, NONPROD-06, RESET-01
**Success Criteria** (what must be TRUE):

  1. An operator can reach the nonprod app over real HTTPS at its own stable hostname and get a healthy response, on a certificate issued for that exact enumerated hostname — never a wildcard match against the shared `*.duckdns.org` public suffix
  2. Data written through nonprod lands only in nonprod's Neon branch and nonprod's own Redpanda broker and schema registry; production's rows, topics, and registered compatibility history are demonstrably untouched, and nonprod's credentials live in a file/secret set structurally separate from `.env.prod`
  3. Nonprod and production coexist on the host without production degrading — nonprod's Redpanda memory floor established by iterative live restart cycles, not by halving production's cap; if no safe floor is found, nonprod instead runs on the fallback second VPS, actually provisioned and verified rather than left as a documented option
  4. An operator can return nonprod to a known-clean baseline with a single curl that clears both its Postgres state and its activity-log/Kafka state, and the same mechanism is unavailable against production
  5. A browser at the expected nonprod frontend origin completes a credentialed cross-origin request against nonprod without a CORS failure, with no application code changed to allow it

**Plans**: 3 plans

Plans:
**Wave 1**

- [x] 08-01-PLAN.md — Nonprod stack live over HTTPS: own Compose project, own Neon branch, own Redpanda broker, second Caddy site block, CORS origin, isolation audit (NONPROD-01..05)

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 08-02-PLAN.md — Profile-gated, shared-secret reset endpoint truncating both Postgres and Kafka state, verified live by curl (RESET-01)

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 08-03-PLAN.md — Live iterative Redpanda memory-floor measurement with a proven failing step below the floor, and the blocking D-07 colocate-vs-fallback-VPS decision (NONPROD-06)

### Phase 9: Nonprod Continuous Deploy & Scoped CI Credentials

**Goal**: Nonprod stays current with master automatically — deployed, migrated, health-verified, and schema-registered by CI on every push, with the same automated step keeping production's and nonprod's Avro schema registries in step with the deployed code — through credentials scoped so the nonprod path cannot reach, overwrite, or degrade production.
**Depends on**: Phase 8 (a manually-proven-healthy nonprod stack to automate against, matching this project's own tracer-then-automate precedent from v1.2 Phase 5)
**Requirements**: CI-01, CI-02, CI-03, CI-04, CI-05, API-01
**Success Criteria** (what must be TRUE):

  1. A push to master leaves nonprod running that commit's image, deployed within the same workflow run as production's deploy — neither job waits on, gates, nor fails because of the other
  2. The nonprod deploy is reported successful only after nonprod's health endpoint actually answers 200; a nonprod stack that fails to come up fails the workflow visibly instead of passing silently
  3. The nonprod deploy job can read only staging-scoped credentials — production's secrets are unreachable from it, because both environments' secrets are scoped through GitHub Environments rather than shared unscoped repository secrets
  4. Running a production deploy and a nonprod deploy back to back leaves both stacks running their own correct image: neither deploy converges onto the other's directory, containers, network, or volumes, and neither run's image-cleanup sweep deletes the tag the other environment is currently running
  5. A push to master that introduces or changes an Avro schema leaves that schema present in both the production and the nonprod registry with no operator running the registrar by hand; a schema change the registry rejects as incompatible fails the deploy visibly rather than surfacing later as a runtime publish failure
  6. The generated OpenAPI spec (`/v3/api-docs`) declares the `ProblemDetail` error envelope on every operation that can produce one, enforced centrally (not per-endpoint annotation) and guarded by an automated check so the gap cannot silently reopen — *(API-01, folded in as an explicit scope exception, 2026-08-18: not CI/deploy work, added anyway by user decision after being discovered live by a downstream frontend consumer during this phase's planning)*

**Plans**: 4/4 plans executed

Plans:
**Wave 1**

- [x] 09-01-PLAN.md — GitHub Environments, the confined `deploy-nonprod` VM identity, the nonprod Docker Hub repository, and an end-to-end tracer deploy (CI-01, CI-02, CI-03)

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 09-02-PLAN.md — repository-secret sweep, `health-check-nonprod` bounded poll, and the nonprod image-retention pair (CI-02, CI-03, CI-04)

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 09-03-PLAN.md — automated Avro schema registration against both registries, ordered ahead of nonprod's app start (CI-05)

**Wave 4** *(independent — no file overlap with Waves 1-3; touches OpenAPI/springdoc config, not deploy.yml)*

- [x] 09-04-PLAN.md — global OpenAPI customizer for the `ProblemDetail` error envelope, plus a spec-completeness regression guard (API-01)

### Phase 10: CI & Deploy Hardening

**Goal**: The repository's CI, secret-scanning, and session-cookie configuration close the eight hardening gaps accumulated across v1.2, and the README stands on its own as a full architecture showcase.
**Depends on**: Phase 9 (deploy.yml's nonprod jobs should exist before every `uses:` reference in it is digest-pinned, so the pinning covers the final job graph rather than being redone) and Phase 8 (every deployed environment reachable over TLS before the session cookie is forced `Secure`)
**Requirements**: HARDEN-01, HARDEN-02, HARDEN-03, HARDEN-04, HARDEN-05, HARDEN-06, HARDEN-07, HARDEN-08
**Success Criteria** (what must be TRUE):

  1. Dependabot raises update PRs for outdated GitHub Actions, not only Gradle dependencies
  2. CI's secret scanning distinguishes a live, currently-exploitable credential from a merely pattern-matched string, and the pre-commit gitleaks hook scans a staged diff correctly when invoked from a worktree created outside the main repo tree
  3. Every third-party `uses:` reference in `deploy.yml` resolves to an immutable commit digest rather than a mutable tag, `security-scan.yml` no longer carries its stale comment or outdated `checkout`/`setup-java` versions, and `deploy.yml`'s `run-tests` job reuses a Gradle cache between runs — *(narrowed 2026-08-19 by CONTEXT.md D-05: digest-pinning is scoped to the two `appleboy/*` actions that hold real production/staging SSH keys; first-party GitHub/Docker actions keep tag-only trust behind an explicit, in-file risk-acceptance comment. The original "every `uses:` reference" wording predates that discussion and is superseded by it.)*
  4. Session cookies carry the `Secure` flag in both `application.properties` and `application-test.properties`, and authenticated flows still pass end-to-end against a TLS-served environment
  5. A newcomer reading only the README can see the system's architecture, stack, and deployment shape without opening `docs/`

**Plans**: 3/6 plans executed

Plans:
**Wave 1** *(no file overlap — parallelisable)*

- [x] 10-01-PLAN.md — Action supply-chain: digest-pin both `appleboy/*` actions end-to-end (tracer), Dependabot `github-actions` ecosystem, Gradle cache on `run-tests`, production image-cleanup DELETE status check (HARDEN-01, HARDEN-03, HARDEN-04)
- [x] 10-02-PLAN.md — Secret-scanning gates: digest-pinned, range-scoped, hard-gated TruffleHog job in `secret-scan.yml`, and the pre-commit hook's out-of-tree-worktree stdin fallback (HARDEN-02, HARDEN-05)
- [x] 10-03-PLAN.md — `security-scan.yml` hygiene: bring action pins level with `deploy.yml`, retire the stale divergence comment, and diagnose/fix the NVD_API_KEY resolution failure (HARDEN-06 + folded todo)

**Wave 2** *(blocked on Wave 1 — file overlap on `deploy.yml`/`security-scan.yml`, and a settled CI baseline for the live cookie verification)*

- [ ] 10-04-PLAN.md — Gradle build-tooling integrity: `distributionSha256Sum` pin, `gradle/actions/wrapper-validation` ahead of every `./gradlew`, and dependency-verification metadata (folded todos only — a trust boundary CONTEXT.md defines as distinct from HARDEN-03, which plan 10-01 owns in full; no formal HARDEN-* ID)
- [ ] 10-05-PLAN.md — `Secure` session cookie in both profiles, guarded by a new real-socket assertion over the actual `Set-Cookie`, verified live against TLS-served nonprod (HARDEN-07)

**Wave 3** *(blocked on Waves 1-2 — the README must describe the gates that actually shipped, after every checkpoint resolves)*

- [ ] 10-06-PLAN.md — README restructured into a production-reality-first architecture showcase with one embedded Mermaid diagram and inline stack rationale (HARDEN-08)

## Progress

**Execution Order:**
Phases execute in numeric order: 8 → 9 → 10

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 8. Isolated Nonprod Environment, Live and Resettable | v1.3 | 3/3 | Complete    | 2026-08-18 |
| 9. Nonprod Continuous Deploy & Scoped CI Credentials | v1.3 | 4/4 | Complete    | 2026-08-19 |
| 10. CI & Deploy Hardening | v1.3 | 3/6 | In Progress|  |

| Milestone | Phases | Plans | Status | Shipped |
|-----------|--------|-------|--------|---------|
| v1.0 Optimistic Locking | 1 | 3/3 | Complete | 2026-08-01 |
| v1.1 Kafka Activity Feed | 2 | 6/6 | Complete | 2026-08-03 |
| v1.2 Infra Migration & Schema Registry | 7 | 39/39 | Complete | 2026-08-17 |
| v1.3 Nonprod Environment & CI Hardening | 3 | 7/13 | In progress | - |

## Deferred (not this milestone)

- **FRONTEND-DISPATCH-V2** — `repository_dispatch` from this repo's nonprod-deploy job into the frontend repo's Playwright workflow. Hard-blocked: that repo has no workflow file to dispatch into, so there is no code to write on this side. Nonprod deploying continuously on every master push already gives the eventual frontend CI a stable, always-current target to point Playwright at directly.
- **FRONTEND-COUPLING-V2** — whether any backend-side waiting/gating on the frontend's E2E result is warranted. Default decision this milestone: no (inverted ownership — see REQUIREMENTS.md Out of Scope).
