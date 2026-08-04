# Phase 5: Infra Migration - Context

**Gathered:** 2026-08-04
**Status:** Ready for planning

<domain>
## Phase Boundary

Redeploy the app on a cost-guarded, always-free/near-free stack — Oracle Cloud Always Free A1 Flex VM (Docker) + Neon serverless Postgres + a resource-capped, self-hosted single-node Redpanda broker + Caddy for automatic HTTPS + GitHub Actions CI/CD — replacing the deleted AWS EC2/RDS deployment. Covers INFRA-01 through INFRA-08. Includes Phase 4's final cutover step (repoint `schema.registry.url` from local/standalone to the production Redpanda registry and re-run Phase 4's verification suite against the real target). Also includes two folded pending todos: producing an infra architecture diagram, and rewriting the currently-disabled `deploy-to-ec2` GitHub Actions job into the new pipeline (this rewrite effectively IS INFRA-05, not separate scope). Does not cover application-level features, observability stacks, blue-green deploys, or multi-broker HA (all explicitly deferred to v2 per REQUIREMENTS.md).

</domain>

<decisions>
## Implementation Decisions

### Domain / HTTPS
- **D-01:** Use a free subdomain service (e.g. DuckDNS or equivalent) rather than a paid registered domain, since no domain is currently owned and a free cert-eligible subdomain unblocks Caddy's automatic Let's Encrypt HTTPS at zero cost. — **Reversibility:** reversible — a paid domain can be swapped in later by repointing DNS and Caddy's site block; no application code depends on the domain choice.

### Secrets handling
- **D-02:** Secrets (new SSH keypair, DB connection details, schema registry URL, etc.) are handled via **guided step-by-step execution** — as each secret is needed, the plan/executor tells the user exactly what to generate and where to paste it (GitHub repo secrets UI, Oracle console, Neon dashboard), rather than the user front-loading everything before execution starts. Claude never handles credential values directly (per platform-level restrictions); this only affects sequencing/guidance style, not who ultimately enters secrets.

### Deploy sequencing
- **D-03:** Get the app running **manually** on the Oracle VM first (SSH in, `docker compose up` by hand, confirm the full stack — app + Redpanda + registry cutover — actually works on real Oracle infra) **before** wiring up GitHub Actions automation. Isolates "does the stack work" from "does the pipeline work" — if something breaks, it's easier to tell which layer is at fault. GitHub Actions CI/CD (INFRA-05) is built and does its first automated deploy only after the manual deploy is proven. — **Reversibility:** reversible — this only affects build order within the phase, not the final architecture; the plan can still deliver both a working manual deploy and a working CI/CD pipeline by the end.

### Folded Todos
- **"Create high-level infra architecture diagram before live infra onboarding"** (`.planning/todos/pending/2026-08-04-create-high-level-infra-architecture-diagram-before-live-inf.md`, already tagged `resolves_phase: 5`) — its trigger condition ("before Phase 5's actual live infra onboarding") is now. Folded as an early Phase 5 deliverable: Mermaid C4-style diagram(s) of the Oracle VM boundary (app + Redpanda + Caddy) + Neon + GitHub Actions, checked into `docs/`.
- **"Re-enable and rewrite the disabled deploy-to-ec2 CI job once Phase 5 lands"** (`.planning/todos/pending/2026-08-04-re-enable-and-rewrite-the-disabled-deploy-job-after-phase-5.md`, already tagged `resolves_phase: 5`) — this todo's scope IS INFRA-05 (GitHub Actions builds/deploys automatically using new SSH credentials, not reused AWS secrets). Folded not as additional scope but as the concrete spec/reference for implementing INFRA-05: the todo already documents that the rewrite needs a new deploy target, new SSH secrets, no AWS OIDC, and flags a pre-existing side effect (Docker Hub tag-accumulation cleanup job also currently disabled) and a pre-existing latent bug (a truncated `curl -X DELETE` line in the old `cleanup-unused-image` job) worth being aware of when rewriting rather than reusing that file's structure uncritically.

### Claude's Discretion
- Exact Oracle Cloud A1 Flex provisioning steps (OS image choice, initial SSH access setup, OCI console navigation specifics) — planner/researcher's call; research (STACK.md/PITFALLS.md) already covers the two-layer-firewall gotcha and the resource-shape re-verification need.
- Whether to verify the actual current OCI tenancy shape (2 vs 4 OCPU, given Oracle's undocumented June 2026 halving) as an early plan task before finalizing Redpanda's `--memory`/`--smp` values, or to plan conservatively for 2 OCPU/12GB and adjust if the console shows more — planner's call, but must not silently assume the more generous figure.
- Exact free-subdomain service choice (DuckDNS vs. alternatives) — Claude's call at execution time; any option that gives a real A-record-pointable hostname works for Let's Encrypt HTTP-01.
- `docker-compose.yml` structure for the production target (single compose file with prod-specific env vars, vs. a separate `docker-compose.prod.yml` overlay) — planner's call, informed by keeping the existing local-dev compose file working unmodified per the project's established convention.
- Redpanda resource-cap exact values (`--overprovisioned`/`--memory`/`--smp`) — deferred to measurement during execution per research's own recommendation, not decided here.
- GitHub Actions SSH deploy mechanics (`appleboy/ssh-action` vs. alternatives, exact `known_hosts` pinning approach) — research (STACK.md) already recommends `appleboy/ssh-action`; planner confirms during planning.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project-level requirements
- `.planning/PROJECT.md` — Core Value, Current Milestone (v1.2), Validated requirements from v1.0/v1.1/Phase 4
- `.planning/REQUIREMENTS.md` — INFRA-01 through INFRA-08 (this phase's requirements); SCHEMA-01..06 (Phase 4, complete — the cutover target); v2-deferred INFRA-V2-01/02/03 (observability, blue-green, multi-broker HA — explicitly out of scope here)
- `.planning/ROADMAP.md` — Phase 5 goal, success criteria, explicit dependency on Phase 4's schema-registry-URL cutover step

### Milestone-level research (grounded findings)
- `.planning/research/SUMMARY.md` — executive summary, the two flagged risk clusters (Redpanda resource contention, Avro cutover correctness — the latter already resolved by Phase 4)
- `.planning/research/STACK.md` — Oracle A1 Flex (2 OCPU/12GB post-June-2026-halving, needs in-console re-verification), Redpanda v26.2.x resource tuning, Neon pooled-vs-direct connection guidance, Caddy recommendation, `appleboy/ssh-action` deploy pattern
- `.planning/research/FEATURES.md` — table stakes (public HTTPS, restart/healthchecks, automated CI/CD, log rotation, firewall audit) vs. anti-features (blue-green, full observability stack, multi-broker HA — all correctly out of scope)
- `.planning/research/PITFALLS.md` — Redpanda default resource auto-detection (Pitfall 1-4), Neon/HikariCP cold-start pool sizing, GitHub Actions/OCI two-layer-firewall gotcha, Oracle free-tier volatility, the explicit warning against enabling virtual threads to compensate for fewer OCPUs (hits this project's own documented unfixed HikariCP+virtual-threads bug)

### Prior phase context (established conventions this phase must not break)
- `.planning/phases/04-schema-registry/04-CONTEXT.md` — D-01 (registry-down = broker-down resilience precedent), D-02 (BACKWARD compatibility), D-03 (one schema per event type) — all already implemented; this phase's cutover step must not regress any of them
- `.planning/phases/04-schema-registry/04-VERIFICATION.md` — the 12/12 verified must-haves and the exact live-registry verification approach (curl the registry API directly, don't trust prose) to replicate against the production target
- `.planning/quick/260804-nd3-remap-docker-compose-yml-postgres-host-p/260804-nd3-SUMMARY.md` — `DB_PORT` is now a parameterized property in `application.properties` (default 5432); relevant if local-vs-prod Postgres port conventions interact during this phase

### Folded todos (now this phase's concrete scope for the diagram and INFRA-05)
- `.planning/todos/pending/2026-08-04-create-high-level-infra-architecture-diagram-before-live-inf.md`
- `.planning/todos/pending/2026-08-04-re-enable-and-rewrite-the-disabled-deploy-job-after-phase-5.md`

### Current CI/CD state (must be understood before rewriting)
- `.github/workflows/deploy.yml` — `deploy-to-ec2` job currently has `if: false` (disabled during migration, per quick task 260804-p7a) with an explanatory comment; `run-tests` and `build-and-push-docker-image` remain active; `cleanup-old-images`/`cleanup-unused-image` both correctly skip when `deploy-to-ec2` skips (documented GitHub Actions `needs`/`success()`/`failure()` semantics, not empirically re-verified)
- `docs/SESSION_LESSONS.md` — git-hygiene lessons from Phase 4's execution session (push periodically to avoid worktree fork-base divergence; never git-commit on the main tree while a sequential executor is mid-task) — apply during this phase's execution too

### Codebase maps
- `Dockerfile` — existing two-stage build (gradle:8.7-jdk21 build stage, eclipse-temurin:21-jre-jammy runtime stage), already fixed in Phase 2 from a retired base image; this phase's production compose/deploy config builds on this unchanged image
- `docker-compose.yml` — existing local-dev stack (postgres, Redpanda, app) from Phase 4; this phase adapts/extends it for a production target rather than replacing it

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `Dockerfile` — production-ready two-stage build, no changes needed for the deploy target itself (same image runs locally and in production)
- `docker-compose.yml` — Phase 4 already has app + Redpanda + registry wiring proven locally; production compose config is an adaptation, not a rewrite from scratch
- `application.properties`'s `DB_PORT`-parameterized datasource config (from quick task 260804-nd3) — the same parameterization pattern should extend naturally to Neon's connection string via env vars

### Established Patterns
- Env-var-driven config via `${VAR:default}` placeholders in `application.properties` (already used for `KAFKA_BOOTSTRAP_SERVERS`, `DB_HOST`, `DB_PORT`) — the production Neon/Redpanda endpoints should follow this same pattern, not hardcoded values
- GitHub Actions secrets-based deploy (existing `deploy.yml` pattern: `secrets.EC2_SSH_KEY`/`EC2_HOST`/`EC2_USER`/`DB_HOST`/etc.) — the rewritten job reuses this secrets-injection shape with new secret names, not a structurally different approach

### Integration Points
- `.github/workflows/deploy.yml`'s `deploy-to-ec2` job — rewritten (not just re-enabled) to target the Oracle VM via `appleboy/ssh-action`, with new secrets
- New Caddy service/config — added to the production compose setup for reverse-proxy + automatic TLS
- New pre-merge DDL verification step (INFRA-06) — a new CI job or script, analogous in spirit to the old manual pre-merge DDL runbook step from v1.0/v1.1 but automated this time, running against Neon's direct connection string

</code_context>

<specifics>
## Specific Ideas

User clarified mid-discussion that the deployment platform decision (Oracle Cloud, self-managed VM — not a PaaS like Railway/Render/Fly.io) was already made earlier in this project's history, and that the domain/HTTPS gap discussed here (D-01) is a direct, understood consequence of choosing self-managed IaaS over a PaaS that bundles free subdomains — not a reason to reconsider the platform choice.

</specifics>

<deferred>
## Deferred Ideas

- Full observability stack (Prometheus/Grafana), true zero-downtime blue-green deploys, multi-broker Redpanda HA — already explicitly deferred to v2 in REQUIREMENTS.md (INFRA-V2-01/02/03), re-confirmed here as out of scope
- Pre-merge schema-compatibility CI check, documented compatibility-mode rationale — already deferred to v2 (SCHEMA-V2-01/02) during Phase 4, unaffected by this phase

### Reviewed Todos (not folded)
- "Bump Java version from 21 to 25", "Add dependency vulnerability scan", "Evaluate PMD/Checkstyle/SpotBugs" — all matched Phase 5 with low-confidence generic keyword overlap (build/deploy/stack), none topically about infra deployment. Left deferred.
- "Account for schema evolution risk when changing ActivityEvent shapes", "Explore an alert-service integration as a separate microservice", "Create sequence diagram documenting full system flow", "Use Snowflake ID generator for activity log events" — all Kafka/schema-adjacent but not infra-migration scope. Left deferred.

</deferred>

---

*Phase: 5-Infra Migration*
*Context gathered: 2026-08-04*
</content>
