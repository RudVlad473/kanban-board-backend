# Project Research Summary

**Project:** Kanban Board Backend v1.3 — Nonprod Environment & CI Hardening
**Domain:** Adding a second (nonprod/staging) deploy environment to an existing, live single-VM production system, so a future frontend repo's Playwright E2E suite has a real, non-mocked target
**Researched:** 2026-08-17
**Confidence:** HIGH on approach/architecture, MEDIUM on the one genuine open unknown (nonprod Redpanda memory floor)

## Executive Summary

The recommended approach is to **colocate nonprod alongside production on the existing Netcup VPS Lite 2 G12s**, not provision a second VM. A shrunk `app-nonprod` + `redpanda-nonprod` pair, gated behind Docker Compose `profiles:` in the same host, pairs with a dedicated Neon database branch (free, near-instant, already available on the project's plan) and a second Caddy site block on a second free DuckDNS subdomain. This costs zero application code changes — Redpanda is Kafka-wire-compatible and Neon is wire-compatible Postgres, exactly as was already proven true for production in v1.2 Phase 5.

The one place this diverges from a naive reading of "just add a second broker" is Kafka isolation: topic-name-prefixing on the *shared* production broker was considered and rejected, because this codebase's Avro Schema Registry uses `RecordNameStrategy` keyed by class name, not topic — prefixing topics would still leave the registry itself shared, so a deliberately schema-breaking nonprod test could mutate production's registered compatibility history. A second, separate Redpanda broker instance avoids this with zero `src/main` changes.

The binding constraint is host memory: production's current *reserved* caps (app `mem_limit: 3g` + redpanda `mem_limit: 2200m`) leave roughly 2.65GB unreserved on the 7.8GB host. Real measured production usage is well under those caps (~15-17%), which is genuine headroom — but the combined worst-case ceiling if every cap were maxed simultaneously is tight (~7.3-7.4GB of 7.8GB). This needs live measurement after a real nonprod deploy, the same discipline that already corrected production's own Redpanda memory cap once (a documented incident: setting `mem_limit` numerically equal to `--memory` broke every restart due to cgroup accounting overhead). A concrete, cheap fallback exists if colocation doesn't hold up under measurement: a second small Netcup VPS Lite 1 (~€4/month).

Research also surfaced four genuinely load-bearing safety findings that must gate the CI-automation work, not just the infra work: (1) the existing `deploy-to-netcup` job hardcodes its target directory and the Compose project name is pinned (`kanban-board-backend`) — a nonprod deploy job that doesn't change every one of these axes will silently converge onto and mutate the *live* production containers, not create a second stack; (2) zero GitHub Environments exist today, so every one of the 10 existing repository secrets is unscoped — a nonprod CI job inherits full production secret access unless Environments are introduced first; (3) the already-twice-buggy `cleanup-old-images` job deletes every Docker Hub tag except its own run's — reusing the same Docker Hub repository for nonprod images would let the next production push delete nonprod's live tag; (4) the original todo's own framing ("deploy nonprod → run frontend Playwright → only then promote to prod") would make this backend repo's production release gate on a *different* repository's test suite — flagged as a probable inverted-ownership anti-pattern; the frontend repo gating itself on nonprod reachability is the recommended alternative, since nonprod deploys continuously on every push and is therefore already a stable target with no cross-repo CI plumbing required from this side.

## Key Findings

### Recommended Stack

Colocated nonprod stack on the existing Netcup VPS: `app-nonprod` (suggested `mem_limit: 1g`) + `redpanda-nonprod` (suggested `mem_limit: 900m`, `--memory 700M` as a starting point — genuinely unmeasured, see Gaps), gated via Compose `profiles:` in the same `docker-compose.prod.yml` (or a sibling file with the project name explicitly pinned, matching the fix already applied to prod after its own project-name-collision incident). Neon branching provides nonprod's database with zero new infrastructure — one persistent branch off `production`, migrated by a Flyway-verify-style CI job against its own direct (non-pooler) endpoint. A second Redpanda broker instance (not topic-prefixing) provides Kafka/Schema-Registry isolation.

**Core technologies:**
- Docker Compose `profiles:` — gates the second app+redpanda pair in the same host without a second `docker-compose` invocation model
- Neon branching — free, near-instant, isolated Postgres per environment on the project's existing plan tier
- A second Redpanda broker instance — Kafka + Schema Registry isolation, avoiding shared-registry compatibility-history risk
- Caddy (existing container, unmodified) — gains a second site block; only one process can bind host 80/443, so nonprod does NOT get its own Caddy container
- A second free DuckDNS subdomain — the account supports up to 5, one more is trivial

### Expected Features

**Must have (table stakes) — buildable now, zero dependency on the frontend repo:**
- Nonprod deploy target reachable over HTTPS at a stable hostname
- Isolated database (Neon branch) and isolated Kafka/Schema Registry (second broker)
- CI job deploying to nonprod on push to master, alongside (not gating, not gated by) the existing production deploy
- A test-data reset/seed mechanism, curl-verifiable today — this needs to cover Kafka/activity-log state too, not just Postgres, which generic guidance doesn't address but this project's own architecture requires
- CORS allow-list extension is zero backend code change (`CorsConfig.java` already externalizes origins via `app.cors.allowed-origins`) — just a deployment-time env var once the frontend's nonprod origin is known

**Should have (once the frontend repo exists) — explicitly deferred, not attempted this milestone:**
- `repository_dispatch` cross-repo CI trigger — hard-blocked on the frontend repo having a workflow file to dispatch into; there is nothing to build on this side yet

**Defer/anti-features (explicitly rejected for this milestone):**
- Per-PR ephemeral nonprod environments — solves a parallel-review-contention problem a solo project structurally doesn't have
- A second Neon project (vs. a branch) — over-engineering for one persistent staging target
- Backend gating its own production promotion on the frontend repo's E2E results — inverted ownership; the frontend should gate itself on nonprod reachability instead

### Architecture Approach

Nonprod integrates into the existing single-VM, single-pipeline architecture via three changes: a second Compose project (name-pinned, on a new shared `edge` external Docker network so Caddy can reach `app-nonprod` without exposing new host ports), a second Caddy site block (no second Caddy container), and two new sibling GitHub Actions jobs (`flyway-verify-nonprod`, `deploy-to-nonprod`) extending the existing `deploy.yml` rather than a new workflow file — both depend only on the already-built `build-and-push-docker-image` output and run parallel to, not gating, `deploy-to-netcup`.

**Major components:**
1. `docker-compose.nonprod.yml` (or profile-gated block in the existing file) — nonprod `app`/`redpanda`, name-pinned, resource-capped
2. Caddy (existing container) — second site block, second DuckDNS subdomain, automatic Let's Encrypt cert for the new hostname
3. `deploy.yml` — two new jobs (`flyway-verify-nonprod`, `deploy-to-nonprod`), gated behind GitHub Environments once introduced

### Critical Pitfalls

1. **Deploy-job overwrite of live production** — the existing `deploy-to-netcup` job's hardcoded target directory and Compose's pinned project name mean a copy-pasted nonprod job that doesn't change every one of these axes converges onto and mutates production, not a second stack. This project already hit the less-severe sibling of this bug once (project-name collision via a directory move). Prevention: every identity axis (directory, Compose project name, container names, network name, volume names) must differ between prod and nonprod jobs, verified explicitly, not assumed from "it's a different job."
2. **No GitHub Environments exist today** — all secrets are plain, unscoped repository secrets. A nonprod job inherits full production secret access by default. Prevention: introduce `production`/`staging` GitHub Environments as a prerequisite step *before* the nonprod CI job is added, not bolted on after.
3. **Resource contention is a cgroup problem, not an arithmetic one** — `mem_limit` is a per-container hard cap, not a host-level reservation; summed caps across both stacks can overcommit the host even when each individually looks safe on paper. This project has already proven a cgroup-accounting surprise once at exactly this kind of boundary. Prevention: live-measure nonprod's actual Redpanda memory floor via iterative restart cycles, the same discipline v1.2 Phase 5 Task 3 already used for production — do not assume a value from arithmetic (e.g., "just halve prod's cap").
4. **`cleanup-old-images` would delete nonprod's live tag** — it deletes every Docker Hub tag except its own run's; sharing the production image repository between environments means the next production push deletes whatever tag nonprod is currently running. Prevention: separate Docker Hub repository for nonprod images, or scope the cleanup job's tag-matching explicitly.
5. **DuckDNS is a shared public suffix** — any cookie/CORS config that pattern-matches `*.duckdns.org` instead of enumerating the two full hostnames leaks scope to unrelated tenants on the same suffix, not just this project's own environments. Prevention: always enumerate exact hostnames, never wildcard-match the shared suffix.

## Implications for Roadmap

Based on research, suggested phase structure:

### Phase 1: Nonprod Infrastructure Bootstrap
**Rationale:** Standard, well-documented patterns (Neon branching, DNS, Docker networking) with no genuine unknowns — can proceed directly to planning without a research-phase detour.
**Delivers:** Neon nonprod branch + secrets, new DuckDNS subdomain, shared `edge` Docker network, Caddy second site block.
**Addresses:** Isolated database, reachable HTTPS hostname (table stakes).
**Avoids:** Pitfall 5 (DuckDNS wildcard scope leak) by enumerating exact hostnames from the start.

### Phase 2: Nonprod Stack Deployment & Resource Measurement
**Rationale:** This is the critical path and cannot be compressed — the nonprod Redpanda memory floor is a genuine unknown requiring live, iterative measurement (2-4 restart cycles), mirroring how production's own Redpanda caps were corrected in v1.2 Phase 5 Task 3. Manual deploy first, matching the project's own established tracer-before-automation pattern (Phase 5 Plan 04 did a manual deploy before Plan 05 automated it).
**Delivers:** `docker-compose.nonprod.yml` (name-pinned, Compose `profiles:`-gated), a manually-verified-healthy nonprod stack on the existing VPS, measured (not assumed) resource caps.
**Uses:** Docker Compose `profiles:`, second Redpanda broker instance.
**Implements:** The colocated-stack architecture component.

### Phase 3: Kafka Isolation & Data Reset Mechanism
**Rationale:** Buildable and fully verifiable independently of CI automation or the frontend repo — a curl-verifiable reset endpoint needs no Playwright suite to exist yet.
**Delivers:** Confirmed second-broker Kafka/Schema-Registry isolation, a test-data reset/seed mechanism covering both Postgres and activity-log/Kafka state.
**Addresses:** The data-isolation and flake-prevention table-stakes features.

### Phase 4: CI Automation & GitHub Environments
**Rationale:** Automates the now-proven manual path. GitHub Environments must land as a prerequisite *within* this phase, before the nonprod deploy job exists — sequencing this the other way around would mean the nonprod job runs unscoped-secret first and gets re-gated later, a worse rollout than doing it right the first time.
**Delivers:** `production`/`staging` GitHub Environments, `flyway-verify-nonprod` + `deploy-to-nonprod` jobs extending `deploy.yml`, a decision on separate-vs-shared Docker Hub repo for nonprod images (separate recommended, per Pitfall 4).
**Uses:** The existing `deploy.yml` job-graph conventions.
**Avoids:** Pitfalls 1, 2, and 4 (deploy-job overwrite, unscoped secrets, tag-deletion cross-contamination) — each has an explicit verification step in this phase.

### Phase 5: Cross-Repo E2E Dispatch (deferred, not this milestone)
**Rationale:** Hard-blocked on the frontend repo existing — there is no workflow file to `repository_dispatch` into yet, and no code to write on this side.
**Delivers:** Nothing in this milestone. Tracked as a follow-on todo/future milestone item.
**Note:** Not a blocker to shipping v1.3 — nonprod deploying continuously on every master push already gives the eventual frontend CI a stable, always-current target to point Playwright at directly, no cross-repo plumbing required from this side.

### Phase Ordering Rationale

- Phase 1 before Phase 2: external provisioning (Neon, DNS) has no dependency on the nonprod stack existing yet and can be verified independently, unblocking Phase 2 immediately.
- Phase 2 before Phase 3: the reset mechanism and Kafka isolation need a running, resource-verified nonprod stack to test against.
- Phase 4 last (of the buildable phases): CI automation should wrap a manually-proven-healthy target, exactly mirroring how v1.2 Phase 5 sequenced its own manual-tracer-then-automate approach.
- Phase 5 explicitly out of this milestone's scope: automating a nonexistent trigger destination is not real work, just a placeholder.

### Research Flags

Phases likely needing deeper research during planning:
- **Phase 2:** The Redpanda memory floor is a binding, currently-unmeasured unknown. Budget real iteration time; if no value under roughly 1.0-1.2GB reaches a healthy restart, the fallback (separate small VPS for nonprod) needs to be exercised, not just documented as an option.

Phases with standard patterns (skip research-phase, proceed direct to planning):
- **Phase 1:** Neon branching, DNS, and Docker networking are all officially documented with no project-specific unknowns.
- **Phase 3:** Topic/consumer-group prefixing and a reset endpoint are config-driven, low-risk once the second-broker decision is made (it already has been, in this research).
- **Phase 4:** GitHub Actions job placement and Environments are well-documented, and this repo already has deploy-job precedent to extend.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | Grounded directly in this repo's own already-proven prod stack, official Neon/Caddy docs, and this project's own measured production baseline |
| Features | MEDIUM | Table-stakes/anti-feature split is well-reasoned and grounded in this project's own PROJECT.md/deploy.yml constraints, but the general staging-environment pattern research is web-sourced, not framework-mandated |
| Architecture | HIGH | Verified against this repo's own documented incident history (Compose collision, Redpanda mem_limit) plus current official Docker/Caddy docs |
| Pitfalls | HIGH | Five of nine pitfalls sourced directly from this repo's own `docs/INFRA_RUNBOOK.md` and `.github/workflows/deploy.yml`, not inferred from general guidance |

**Overall confidence:** HIGH on approach and phase structure; MEDIUM specifically on the one genuine open unknown (nonprod Redpanda memory floor), which Phase 2 exists specifically to resolve.

### Gaps to Address

- **Redpanda `--memory` floor for nonprod** — the single binding unknown across all four research files. Not resolvable by research; requires the live iterative measurement Phase 2 is built around. If it resolves negative (no safe value fits), the documented fallback (second small VPS, ~€4/month) needs to actually be exercised, not left as an unexercised contingency.
- **Docker Hub repository decision** (separate vs. shared repo for nonprod images) — recommended separate, to avoid `cleanup-old-images` cross-contamination, but not a hard blocker either way; a scoping fix to the cleanup job's own tag-matching would also work. Decide during Phase 4 planning.
- **GitHub Environments migration scope** — which of the 10 existing repo secrets move to a `production` Environment vs. stay repo-level, and whether to add a required-approval gate on `production` as additional blast-radius defense. Not resolved by this research; a Phase 4 planning decision.
- **Ephemeral vs. persistent Neon branch** — a persistent nonprod branch (this research's recommendation) is sufficient for v1; Neon's `reset-from-parent` operation is a lighter-weight alternative to true per-run ephemeral branches if flakiness from accumulated test state ever becomes a real problem — deferred, not needed for v1.
- **Full-copy vs. schema-only Neon branch** — a genuine privacy-vs-fixture-parity tradeoff, not a research gap; this is an operator decision for Phase 1 planning.

## Sources

### Primary (HIGH confidence)
- This project's own `docs/INFRA_RUNBOOK.md`, `docker-compose.prod.yml`, `Caddyfile`, `.github/workflows/deploy.yml`, `CorsConfig.java`, `KafkaTopics.java` — read directly, not inferred
- Official Neon docs — branching mechanics, plan-tier limits
- Official Caddy docs — multi-site TLS/DNS configuration

### Secondary (MEDIUM confidence)
- Community/third-party sources on Netcup VPS Lite pricing tiers (cross-corroborated across multiple independent sources, not scraped from a live vendor pricing page)
- General GitHub Actions Environments and cross-repo `repository_dispatch` documentation and community patterns

### Tertiary (LOW confidence)
- DuckDNS account subdomain-count limit (single secondary-source corroboration — confirm directly at registration time if it becomes load-bearing)

---
*Research completed: 2026-08-17*
*Ready for roadmap: yes*
