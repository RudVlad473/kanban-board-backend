# Phase 5: Infra Migration - Research

**Researched:** 2026-08-04
**Domain:** Self-hosted production deployment (Oracle Cloud A1 Flex + Docker Compose + Caddy) of an existing Spring Boot 3.5.0/Java 21 app, wired to Neon serverless Postgres and a resource-capped Redpanda broker, deployed via GitHub Actions SSH
**Confidence:** MEDIUM-HIGH (builds directly on milestone-level research from 2026-08-03/04 — SUMMARY.md/STACK.md/FEATURES.md/PITFALLS.md/ARCHITECTURE.md — which this file does not duplicate; new findings below are independently verified this session against the live codebase, a live Docker pull, and current vendor docs)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01 (Domain/HTTPS):** Use a free subdomain service (e.g. DuckDNS or equivalent) rather than a paid registered domain, since no domain is currently owned and a free cert-eligible subdomain unblocks Caddy's automatic Let's Encrypt HTTPS at zero cost. Reversible — a paid domain can be swapped in later by repointing DNS and Caddy's site block; no application code depends on the domain choice.
- **D-02 (Secrets handling):** Secrets (new SSH keypair, DB connection details, schema registry URL, etc.) are handled via **guided step-by-step execution** — as each secret is needed, the plan/executor tells the user exactly what to generate and where to paste it (GitHub repo secrets UI, Oracle console, Neon dashboard), rather than the user front-loading everything before execution starts. Claude never handles credential values directly; this only affects sequencing/guidance style, not who ultimately enters secrets.
- **D-03 (Deploy sequencing):** Get the app running **manually** on the Oracle VM first (SSH in, `docker compose up` by hand, confirm the full stack — app + Redpanda + registry cutover — actually works on real Oracle infra) **before** wiring up GitHub Actions automation. GitHub Actions CI/CD (INFRA-05) is built and does its first automated deploy only after the manual deploy is proven. Reversible — only affects build order within the phase.
- **Folded todo — architecture diagram:** Mermaid C4-style diagram(s) of the Oracle VM boundary (app + Redpanda + Caddy) + Neon + GitHub Actions, checked into `docs/`, is an early Phase 5 deliverable.
- **Folded todo — deploy-to-ec2 rewrite:** `.github/workflows/deploy.yml`'s `deploy-to-ec2` job (currently `if: false`) is rewritten, not just re-enabled, against the Oracle VM: new SSH secrets (not reused AWS-era ones), `restart: unless-stopped` + healthchecks (INFRA-01), capped log drivers (INFRA-07), Neon `DB_*` env vars (INFRA-02). Both cleanup jobs (`cleanup-old-images`, `cleanup-unused-image`) must resume once `deploy-to-ec2` reports success/failure again; the truncated `curl -X DELETE` defect in `cleanup-unused-image` should be fixed while touching that job. Stale AWS-era Docker Hub tags accumulated during the migration window should be pruned as part of cutover.

### Claude's Discretion

- Exact Oracle Cloud A1 Flex provisioning steps (OS image choice, initial SSH access setup, OCI console navigation specifics).
- Whether to verify the actual current OCI tenancy shape (2 vs 4 OCPU) as an early plan task before finalizing Redpanda's `--memory`/`--smp` values, or to plan conservatively for 2 OCPU/12GB and adjust — must not silently assume the more generous figure.
- Exact free-subdomain service choice (DuckDNS vs. alternatives) — any option that gives a real A-record-pointable hostname works for Let's Encrypt HTTP-01.
- `docker-compose.yml` structure for the production target (single compose file with prod-specific env vars, vs. a separate `docker-compose.prod.yml` overlay) — informed by keeping the existing local-dev compose file working unmodified.
- Redpanda resource-cap exact values (`--overprovisioned`/`--memory`/`--smp`) — deferred to measurement during execution, not decided here.
- GitHub Actions SSH deploy mechanics (`appleboy/ssh-action` vs. alternatives, exact `known_hosts`/fingerprint pinning approach) — STACK.md already recommends `appleboy/ssh-action`; planner confirms during planning.

### Deferred Ideas (OUT OF SCOPE)

- Full observability stack (Prometheus/Grafana), true zero-downtime blue-green deploys, multi-broker Redpanda HA — deferred to v2 (INFRA-V2-01/02/03).
- Pre-merge schema-compatibility CI check, documented compatibility-mode rationale — already deferred to v2 (SCHEMA-V2-01/02) during Phase 4.
- Reviewed-but-not-folded todos: Java 25 bump, dependency vulnerability scan, PMD/Checkstyle/SpotBugs evaluation, alert-service microservice exploration, sequence diagram — all left deferred, no topical overlap with infra deployment.

</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| INFRA-01 | App deployed on Oracle Cloud A1 Flex VM via Docker, `restart: unless-stopped` + healthchecks | See "Docker Healthcheck Without a Dockerfile Change" and "Reserved vs. Ephemeral Public IP" findings below; STACK.md/PITFALLS.md cover the two-layer firewall and resource-budget gotchas |
| INFRA-02 | Neon serverless Postgres, pooled connection string, `sslmode=require`, HikariCP sized for cold-start, zero JPA/Hibernate changes | See "Pooled Connection String vs. Architecture Research's Direct-Endpoint Caution" — resolves the tension between this locked requirement and ARCHITECTURE.md's own caution, with a concrete mitigation (`prepareThreshold=0`) |
| INFRA-03 | Self-hosted Redpanda, explicit resource caps, Phase 4's Schema Registry suite re-run green against it | See "Production Redpanda Config Must NOT Inherit `--mode dev-container`" finding; Phase 4's 12/12-verified suite is the re-run target (04-VERIFICATION.md) |
| INFRA-04 | Public HTTPS via Caddy automatic TLS | See Code Examples (Caddyfile), D-01's DuckDNS choice, "Reserved vs. Ephemeral Public IP" |
| INFRA-05 | GitHub Actions builds/deploys automatically on merge, new SSH credentials | See "Exact New GitHub Secrets Needed (D-02 Guided Sequence)" and Code Examples (ssh-action + scp-action workflow skeleton) |
| INFRA-06 | Pre-merge DDL verification step against Neon's direct connection string | See "INFRA-06's Actual Shape: No Migration Tool Exists" finding — this project has no Flyway/Liquibase, only hand-authored idempotent `.sql` bridge scripts |
| INFRA-07 | Docker log drivers capped (`max-size`/`max-file`) | See Code Examples (logging block) |
| INFRA-08 | OCI three-layer network audit, only 80/443 externally reachable, Redpanda 9092 never internet-facing | PITFALLS.md Pitfall 7 covers the model; see Code Examples (iptables commands) for the concrete Ubuntu/OCI command sequence |

</phase_requirements>

## Summary

This phase is almost entirely infrastructure/ops work layered onto a codebase that, per Phase 4's 12/12-verified state, already runs its full Kafka/Avro/Schema-Registry pipeline correctly against a local Redpanda broker (`docker-compose.yml`, verified this session: Redpanda v26.2.1, `--smp 1`, `--mode dev-container`, `rpk cluster health` healthcheck). The milestone-level research (SUMMARY/STACK/FEATURES/PITFALLS/ARCHITECTURE.md) already covers the stack choices, the resource-contention and Neon cold-start pitfalls, and the overall two-phase build order in detail — this file does not re-derive those. What it adds is phase-specific, execution-blocking detail the milestone research left open: the exact shape of the new GitHub secrets a human must generate under D-02's guided-sequence constraint, a concrete resolution to the tension between INFRA-02's locked "pooled connection string" requirement and ARCHITECTURE.md's own caution against pooled + Hibernate, verified facts about the actual Docker runtime image and Oracle IP behavior that change what the plan needs to build, and what INFRA-06's "pre-merge DDL verification" concretely means given this codebase has no migration tool.

Three corrections/clarifications to carry into planning, all independently verified this session: (1) the runtime image (`eclipse-temurin:21-jre-jammy`) already ships `curl`, `wget`, and `bash` — a Docker healthcheck needs **no Dockerfile change**, contrary to a plausible-sounding assumption; (2) the production Redpanda config must explicitly drop the local dev compose's `--mode dev-container` preset (it silently relaxes resource discipline for developer convenience) in favor of explicit `--overprovisioned`/`--memory`/`--smp`, per PITFALLS.md Pitfalls 1–2 — a config-parity trap the "just copy docker-compose.yml" framing invites; (3) Oracle assigns an **ephemeral** public IP by default, which the DuckDNS hostname and the SSH deploy key's pinned fingerprint both depend on staying fixed — the plan should provision a **Reserved (static) Public IP** explicitly, not rely on the default.

**Primary recommendation:** Treat this phase's real engineering surface as three coupled artifacts — a production `docker-compose.yml` (or overlay), a rewritten `deploy.yml`, and a small, justified `application.properties`/`SecurityConfiguration.java` change for the Actuator health endpoint — built and manually verified on the real Oracle VM first (D-03), then wired into GitHub Actions second, with every secret introduced one at a time via D-02's guided human-checkpoint sequence documented below.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| HTTPS termination / reverse proxy (Caddy) | CDN / Static (edge) | API / Backend | Caddy sits at the network edge in front of the app, terminating TLS and forwarding plain HTTP internally — closest analog to the CDN/edge tier in a single-VM topology with no separate CDN provider |
| App process supervision (`restart: unless-stopped`, healthcheck) | API / Backend | — | Docker Compose lifecycle policy for the Spring Boot container itself |
| Production database (Neon) | Database / Storage | — | Wire-compatible Postgres; zero JPA/Hibernate code changes per INFRA-02, but connection-topology and pool-sizing decisions belong here |
| Kafka broker + Schema Registry (Redpanda) | Database / Storage | API / Backend | Durable event log + schema authority (storage concern), but its resource budget directly competes with the API/Backend tier's JVM on the same VM — the coupling itself is the risk PITFALLS.md Pitfalls 1–2 addresses |
| CI/CD build-and-deploy pipeline | N/A (build/ops, not a runtime data-flow tier) | — | GitHub Actions orchestrates artifact delivery into the API/Backend tier; it has no request-time data-flow role |
| Pre-merge DDL verification (INFRA-06) | Database / Storage | N/A (build/ops) | Schema-authority check, executed by the CI/CD pipeline before the Database/Storage tier is touched |
| Network security (OCI Security List + NSG + OS firewall) | Cross-cutting | — | Spans every tier — a single unaudited layer among the three can expose any of them (PITFALLS.md Pitfall 7) |

## Standard Stack

### Core

| Library/Tool | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Redpanda | v26.2.1 | Kafka broker + built-in Schema Registry | `[VERIFIED: docker-compose.yml:20]` — already pinned and healthchecked in the local-dev stack from Phase 4; production should match this exact version for parity, avoiding a second, undertested broker-version combination |
| Caddy | 2.x (current stable line) | Reverse proxy + automatic Let's Encrypt HTTPS | `[CITED: caddyserver.com docs, cross-checked via web search this session]` — one-line-per-domain Caddyfile, automatic cert provisioning/renewal, matches STACK.md's existing recommendation |
| `appleboy/ssh-action` | v1.2.5 (latest as of this session) | Executes remote `docker compose` commands over SSH from GitHub Actions | `[CITED: github.com/appleboy/ssh-action/releases]` — official releases page, confirmed current tag this session; STACK.md already recommended this action, this pins the exact current version to use |
| `appleboy/scp-action` | latest (pin exact tag at execution time) | Pushes the production `docker-compose.yml`/Caddyfile from the CI runner to the VM before the SSH deploy step runs | `[CITED: github.com/appleboy/scp-action]` — same author/auth pattern as `ssh-action` (raw private key or key-path), the natural pairing since the VM has no git checkout of its own |
| Neon serverless Postgres | current platform (Postgres 16/17-compatible) | Production DB | `[CITED: neon.com/docs]` — already the milestone-locked choice; see the pooled-connection finding below for phase-specific config detail |
| `spring-boot-starter-actuator` | 3.5.0 (match existing Spring Boot BOM) | Exposes `/actuator/health` for the Docker healthcheck (INFRA-01) | `[VERIFIED: build.gradle:3]` — matches the already-pinned Spring Boot version exactly via the existing dependency-management plugin; no separate version to track |

### Supporting

| Library/Tool | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| DuckDNS (or equivalent free dynamic-DNS service) | n/a (hosted service) | Free A-record-pointable subdomain for Caddy's Let's Encrypt HTTP-01 challenge | D-01's locked choice; a one-time manual A-record update in the DuckDNS dashboard suffices once a Reserved (static) OCI Public IP is provisioned — no ongoing dynamic-DNS updater/cron/token secret needed if the IP is genuinely static (see finding below) |
| `iptables`/`netfilter-persistent` (Ubuntu, pre-installed on Oracle's Ubuntu images) | OS-shipped | OS-level firewall layer, the third of OCI's three additive network layers | Required alongside the Security List/NSG per PITFALLS.md Pitfall 7 — see Code Examples for the exact command sequence |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `appleboy/scp-action` for pushing compose/Caddyfile to the VM | `git clone`/`git pull` on the VM itself | Requires provisioning a git credential (deploy key or PAT) on the VM for a private repo — more secrets to manage for no benefit at this scale; scp keeps the VM credential-free for source access |
| Ephemeral (default) OCI public IP | Reserved (static) OCI public IP | Ephemeral IP costs nothing extra and works until an instance is recreated/reclaimed, at which point DNS + SSH `known_hosts`/fingerprint pinning + firewall rules referencing the IP would all silently break; Reserved IP is the correct choice given Oracle's own documented free-tier volatility (PITFALLS.md Pitfall 8) |
| `spring-boot-starter-actuator` with only `health` exposed | A bare custom `@GetMapping` healthcheck endpoint | Actuator's `/health` already aggregates DB (HikariCP) and other `HealthIndicator`s for free, giving a real "is the app actually working" signal instead of "is the JVM alive" — worth the one small dependency addition |

**Installation:**
```gradle
// build.gradle — one new dependency, matches the existing Spring Boot BOM automatically
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
}
```

```properties
# application.properties — expose ONLY health, nothing else, over HTTP
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=never
```

**Version verification:** `spring-boot-starter-actuator` has no independent version to pin — Spring Boot's `io.spring.dependency-management` plugin (already active, `build.gradle:4`) resolves it to the exact `3.5.0` train already in use project-wide, the same mechanism that already governs every other `spring-boot-starter-*` dependency in this codebase. `appleboy/ssh-action`'s current tag (`v1.2.5`) and `appleboy/scp-action`'s current tag should be re-confirmed against their GitHub releases pages at execution time, since both ship frequent patch releases.

## Package Legitimacy Audit

> The automated `package-legitimacy check` gate (`gsd_run query package-legitimacy check`) only supports the `npm`/`pypi`/`crates` ecosystems — confirmed this session (`Error: Usage: gsd-tools package-legitimacy check --ecosystem <npm|pypi|crates> ...`). This phase introduces no npm/pypi/crates packages, so the automated gate does not apply. The one new dependency (`spring-boot-starter-actuator`) and the Docker images used are audited manually below.

| Package/Image | Registry | Age | Downloads/Popularity | Source Repo | Verdict | Disposition |
|---------|----------|-----|-----------|-------------|---------|-------------|
| `org.springframework.boot:spring-boot-starter-actuator` | Maven Central | Core Spring Boot module, co-versioned with the already-pinned 3.5.0 | N/A (first-party, part of the framework already in use) | github.com/spring-projects/spring-boot | OK | Approved — not independently versioned, resolved by the existing Spring Boot BOM |
| `docker.redpanda.com/redpandadata/redpanda:v26.2.1` | Redpanda's own registry | Already running in this repo's local-dev stack since Phase 4 | Official vendor image | github.com/redpanda-data/redpanda | OK | Approved — reuse the exact pinned version already verified locally |
| `caddy:2` (official image) | Docker Hub | Official Caddy project image | Widely used, official | github.com/caddyserver/caddy | OK | Approved |
| `docker.io/rudenkovladimir/kanban-board-backend` | Docker Hub | Existing project image, already built/pushed by CI | Project's own image | This repo | OK | Approved — unchanged, only the deploy target changes |

**Packages removed due to [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none

## Architecture Patterns

### System Architecture Diagram

```
Internet
   │  HTTPS (443) / HTTP→HTTPS redirect (80)
   ▼
┌─────────────────────────── Oracle Cloud A1 Flex VM (Reserved static IP) ───────────────────────────┐
│                                                                                                       │
│   ┌────────────┐        ┌───────────────────────┐        ┌─────────────────────────────────────┐   │
│   │   Caddy    │──HTTP──▶│  Spring Boot app       │──JDBC (TLS, pooled)──▶  (out to Neon, below)   │
│   │ (80,443)   │        │  container, port 8080  │                                                 │
│   └────────────┘        │  restart: unless-      │──Kafka wire proto (internal)──▶┌──────────────┐ │
│                          │  stopped, healthcheck  │                                │  Redpanda    │ │
│                          │  = wget /api/actuator/ │◀──Schema Registry HTTP────────│  broker +    │ │
│                          │  health                │   (internal only, 8081)       │  Schema      │ │
│                          └────────────┬───────────┘                                │  Registry    │ │
│                                       │ Docker log driver: json-file,               │  (9092       │ │
│                                       │ max-size/max-file capped                    │  internal    │ │
│                                       ▼                                             │  only)       │ │
│                          (disk, bounded)                                           └──────────────┘ │
│                                                                                                       │
│   OS firewall (iptables/netfilter-persistent): only 22 (restricted)/80/443 open; 9092/8081 NOT open  │
└───────────────────────────────────────┬─────────────────────────────────────────────────────────────┘
                                         │ (OCI Security List + NSG must independently also allow only 80/443/22)
                                         ▼
                              ┌─────────────────────┐
                              │  Neon serverless      │  (pooled -pooler endpoint, sslmode=require,
                              │  Postgres (external)  │   channel_binding=require, prepareThreshold=0)
                              └─────────────────────┘

GitHub Actions (on push to master):
  run-tests → spotlessCheck → build-and-push-docker-image (Docker Hub, unchanged)
       └─▶ ddl-verify (NEW, INFRA-06: psql against Neon DIRECT connection string)
             └─▶ deploy-to-ec2 → rewritten "deploy-to-oracle": scp-action pushes compose+Caddyfile,
                   ssh-action runs `docker compose pull && up -d` on the VM (new SSH secrets, D-05)
                       └─▶ cleanup-old-images / cleanup-unused-image (Docker Hub tag pruning, resumed)
```

### Recommended Project Structure

```
docker-compose.yml            # unchanged — local dev only (postgres, redpanda, app)
docker-compose.prod.yml       # NEW — overlay or standalone prod compose: app + redpanda + caddy,
                               #   env-var-driven (Neon DB_HOST etc., no local postgres service)
Caddyfile                     # NEW — single site block, reverse_proxy app:8080
.github/workflows/deploy.yml  # MODIFIED — deploy-to-ec2 rewritten (new job name/target),
                               #   new ddl-verify job inserted before deploy
docs/                          # NEW — Mermaid C4-style infra diagram(s) (folded todo)
```

### Pattern 1: Actuator health endpoint gated behind Spring Security, not left implicitly public

**What:** `SecurityConfiguration.java`'s `authorizeHttpRequests` block currently `permitAll()`s only `SIGNIN`, `SIGNUP`, and the Swagger docs/UI paths (`[VERIFIED: src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java:61-70]`, quoted below); everything else falls through to `auth.anyRequest().authenticated()`. Adding Actuator without an explicit permit rule means Docker's own internal healthcheck request would get a 302/401 from Spring Security, not a 200 — a very easy "looks done but isn't" trap, since a manual `curl` from a logged-in browser session would appear to work fine.
```java
// src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java:61-70 (current, before this phase's change)
auth.requestMatchers(
                ApiPaths.SIGNIN,
                ApiPaths.SIGNUP,
                SWAGGER_DOCS_PATH,
                String.format("%s/*", SWAGGER_DOCS_PATH),
                String.format("%s/*", ApiPaths.SWAGGER_UI))
        .permitAll();

auth.anyRequest().authenticated();
```
**When to use:** Add a new `requestMatchers` entry for the Actuator health path before this phase's Docker healthcheck can pass.
**Example:**
```java
// New matcher to add, following the exact same pattern as the existing SWAGGER_DOCS_PATH entry
auth.requestMatchers("/actuator/health").permitAll();
```
**Path note (verified this session via web search, `[CITED: docs.spring.io/spring-boot/reference/actuator/monitoring.html]`):** because `server.servlet.context-path=/api` is already set (`[VERIFIED: src/main/resources/application.properties:5]`, `server.servlet.context-path=/api`) and no separate `management.server.port` is planned, Spring Boot appends the Actuator base path (`/actuator`, default) AFTER the servlet context path — the real, resolvable URL is **`/api/actuator/health`**, not `/actuator/health`. The `requestMatchers` call above must match the full resolved path Spring Security sees, which already includes the context path per this class's existing convention (compare `ApiPaths.SIGNIN` matchers, which are also context-path-relative).

### Pattern 2: Production Redpanda config must explicitly drop `--mode dev-container`

**What:** The local-dev `docker-compose.yml`'s Redpanda `command:` block uses `--mode dev-container` (`[VERIFIED: docker-compose.yml:38]`, `--mode dev-container`), a Redpanda-documented convenience preset for local/dev use that relaxes several resource-discipline defaults. PITFALLS.md Pitfalls 1–2 (already-existing milestone research) call for explicit `--overprovisioned`, `--memory`, and `--smp` caps in the production target specifically because Redpanda's un-tuned defaults assume it owns the whole VM.
**When to use:** Any temptation to copy the local `docker-compose.yml`'s Redpanda block verbatim into the production compose file (a natural instinct given "keep the existing local-dev compose file working unmodified" is this phase's own stated discretion boundary) must stop short of copying `--mode dev-container` itself — that flag exists specifically to NOT be used in production.
**Example:**
```yaml
# Local dev (docker-compose.yml, unchanged) uses --mode dev-container.
# Production compose must instead use explicit flags, e.g.:
redpanda:
  image: docker.redpanda.com/redpandadata/redpanda:v26.2.1   # same pinned version as local dev
  command:
    - redpanda
    - start
    - --overprovisioned
    - --smp <N>              # value TBD by measurement (Claude's discretion, this phase)
    - --memory <N>G           # value TBD by measurement (Claude's discretion, this phase)
    - --schema-registry-addr 0.0.0.0:8081
    - --kafka-addr internal://0.0.0.0:19092
    - --advertise-kafka-addr internal://redpanda:19092
    # NOTE: no external kafka-addr/advertise-kafka-addr — 9092 must never be internet-facing (INFRA-08)
```

### Anti-Patterns to Avoid

- **Copying the local dev compose's Redpanda block byte-for-byte into production:** silently reintroduces `--mode dev-container`'s relaxed resource behavior into the one environment where PITFALLS.md's resource-contention pitfalls actually bite.
- **Exposing Redpanda's external Kafka listener (port 9092/19092) or the Schema Registry (8081) to the internet in the production compose file:** the local dev compose intentionally publishes both (`ports: - "9092:9092"` / `"8081:8081"`, `[VERIFIED: docker-compose.yml:41-43]`) for host-side tooling convenience — this must NOT carry over to production; the app talks to Redpanda over the internal Docker network only, and INFRA-08 explicitly requires 9092 never be internet-facing.
- **Assuming the Docker runtime image needs a package installed for the healthcheck to work:** verified false this session (see finding below) — do not add unnecessary `apt-get install curl` steps to `Dockerfile`.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Remote deploy over SSH from CI | A hand-rolled `ssh`/`scp` bash script in the workflow YAML (the OLD `deploy-to-ec2` job's exact pattern) | `appleboy/ssh-action` + `appleboy/scp-action` with `fingerprint`/`known_hosts` pinning | The old job's raw `ssh ... << EOF` heredoc pattern is exactly the copy-pasted-tutorial shape PITFALLS.md Pitfall 6 warns against (`StrictHostKeyChecking` not even set, defaults to prompting and hanging CI); the maintained actions expose a first-class `fingerprint` input for this |
| Detecting whether the app is actually healthy (not just "the JVM process exists") | A custom `@GetMapping("/ping")` returning `"ok"` | Spring Boot Actuator's `/health` (with `management.endpoints.web.exposure.include=health` only) | Actuator's health aggregation already includes the DataSource `HealthIndicator` (HikariCP/Neon reachability) for free — a hand-rolled endpoint would need to reimplement that or silently miss "app is up but DB is unreachable" |
| Automatic HTTPS certificate issuance/renewal | Certbot cron job + manual Nginx reload | Caddy's built-in automatic HTTPS | Already established by STACK.md; zero-maintenance renewal is the entire reason Caddy was chosen over Nginx here |

**Key insight:** every "don't hand-roll" item in this phase is really the same lesson twice — the old, disabled `deploy-to-ec2` job is a worked example of exactly the shortcuts (no host-key pinning, in-line docker run instead of compose, no log caps, no healthcheck) this phase exists to fix, not a template to lightly adapt.

## Runtime State Inventory

> Included because this phase is explicitly titled "Infra Migration" and moves the deploy target from a deleted AWS EC2/RDS pair to Oracle Cloud + Neon.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | **None to migrate.** The AWS RDS/EC2-hosted Postgres was already deleted before this milestone began (per PROJECT.md/REQUIREMENTS.md context and independently reconfirmed by Phase 4 Plan 04/Verification: "no genuinely pre-Avro-cutover `activity_log` rows survive anywhere in this environment," `[VERIFIED: .planning/phases/04-schema-registry/04-VERIFICATION.md:113]`). Neon starts as a genuinely empty database — this is a fresh provision, not a data migration | Apply the three existing DDL bridge scripts (`docs/plans/backend-modernization/02-optimistic-locking-ddl.sql`, `03-activity-log-ddl.sql`, `04-password-hash-not-null-ddl.sql`) to the new empty Neon database once, since Hibernate's `ddl-auto` is unset in production (`[VERIFIED: docker-compose.yml:76]`, local dev uses `SPRING_JPA_HIBERNATE_DDL_AUTO: update` — production has historically left this unset, matching the DDL scripts' own stated rationale) |
| Live service config | **None found requiring migration.** No UI-configured external service (no n8n-equivalent) holds config outside git for this project | — |
| OS-registered state | **None to migrate (fresh VM).** The old EC2 instance is deleted; there is nothing to re-register — this is fresh registration, not migration: OCI Security List/NSG rules, the VM's own `iptables`/`netfilter-persistent` rules, and (if the reserved-IP recommendation is followed) a Reserved Public IP object, all created new | Document as fresh-provision tasks, not migration tasks, in the plan |
| Secrets/env vars | **Stale, must-not-reuse secrets identified:** `EC2_SSH_KEY`, `EC2_HOST`, `EC2_USER`, and the AWS-era `DB_HOST`/`DB_NAME`/`DB_USER`/`DB_PASS` GitHub repo secrets all reference the deleted host (`[VERIFIED: .github/workflows/deploy.yml:90-119]`, the disabled `deploy-to-ec2` job body) | New secrets created under new names (see "Exact New GitHub Secrets Needed" below) rather than overwriting the old ones in place, so a future reader isn't confused about which secret backs which host; old secrets revoked/deleted from repo settings once the new pipeline is proven (per the folded todo) |
| Build artifacts | Docker Hub image tags accumulated unpruned since `cleanup-old-images`/`cleanup-unused-image` both silently stopped running (they `needs: deploy-to-ec2`, which is `if: false`) — every push to `master` since 2026-08-04 has left a permanent `:<sha7>` tag with nothing deleting it | Prune the accumulated tags as part of this phase's cutover (explicitly called out in the folded todo, not optional cleanup) |

## Common Pitfalls

> PITFALLS.md (milestone-level) already covers 8 infra-specific pitfalls in depth (Redpanda resource auto-detection, virtual-threads temptation, HikariCP/Neon cold-start, insecure SSH deploy defaults, OCI three-layer firewall, un-reverified tenancy shape). The pitfalls below are net-new, surfaced by this session's direct codebase/tool verification — they are not restatements.

### Pitfall A: The locked "pooled connection string" requirement (INFRA-02) collides with ARCHITECTURE.md's own caution against it

**What goes wrong:** REQUIREMENTS.md locks INFRA-02 as "pooled connection string" (`[VERIFIED: .planning/REQUIREMENTS.md:11]`, "Production database is Neon serverless Postgres (pooled connection string, `sslmode=require`, HikariCP sized for Neon's cold-start/pooling behavior)"). The milestone-level ARCHITECTURE.md research, written one day earlier, independently recommended the OPPOSITE — Neon's **direct** (non-pooled) endpoint — specifically to avoid PgBouncer transaction-mode pooling breaking Hibernate's server-side prepared-statement behavior. Following ARCHITECTURE.md's recommendation would silently violate a locked requirement; following the requirement without ARCHITECTURE.md's caveat risks the exact prepared-statement breakage it warned about.

**Why it happens:** The two research passes reached different conclusions on the same question a day apart, and neither is wrong in isolation — ARCHITECTURE.md's caution is real (confirmed independently this session via Neon's own docs, `[CITED: neon.com/docs/connect/connection-pooling]`, PgBouncer transaction pooling does not preserve session-level state across statements) but REQUIREMENTS.md's lock takes precedence as an explicit decision.

**How to avoid:** Use the pooled (`-pooler`) connection string as INFRA-02 requires, AND add `prepareThreshold=0` (optionally `&preparedStatementCacheQueries=0`) to the JDBC URL — the standard, `[CITED: multiple sources including Hibernate's own community forum and Crunchy Data's blog]` mitigation for PgJDBC's server-side prepared-statement caching under PgBouncer transaction pooling. This disables PgJDBC's own opportunistic server-side `PREPARE` reuse (which assumes a stable backend session PgBouncer transaction mode does not guarantee), while Neon's PgBouncer (confirmed to run ≥1.21 with `max_prepared_statements` configured, `[CITED: neon.com/blog/pgbouncer-the-one-with-prepared-statements]`) provides its own protocol-level prepared-statement tracking as a second layer of defense. Note that Neon's docs also flag `SET`-scoped session state, temp tables, session-level advisory locks, and `LISTEN`/`NOTIFY` as unsupported under the pooled endpoint (`[CITED: neon.com/docs/connect/connection-pooling]`) — none of these appear to be used by this codebase based on the existing datasource config, but this should be spot-checked (grep for `LISTEN`, advisory-lock usage, or session-scoped Hibernate settings) rather than assumed during planning.

**Warning signs:** Intermittent `PSQLException: prepared statement "..." already exists` or `does not exist` errors under concurrent load against the deployed Neon instance — these would not appear in local dev (no PgBouncer in the local compose stack) or in the H2 test profile, making them specifically a production-only failure mode invisible to the existing test suite.

**Phase to address:** Infra-migration phase, Neon connection-string configuration step — a one-line JDBC URL parameter, but only if someone connects INFRA-02's locked wording to ARCHITECTURE.md's caveat.

---

### Pitfall B: Oracle's default ephemeral public IP breaks every fixed-address assumption this phase makes

**What goes wrong:** By default, an OCI compute instance (including A1 Flex) is assigned an **ephemeral** public IP (`[CITED: docs.oracle.com/en-us/iaas/Content/Network/Tasks/managingpublicIPs.htm]`), which persists only for the life of that specific instance object — if the instance is ever recreated (a real possibility given Oracle's own documented free-tier volatility, PITFALLS.md Pitfall 8), the IP changes. This phase pins three things to the VM's IP: the DuckDNS A-record (D-01), the SSH deploy key's `known_hosts`/`fingerprint` pinning (INFRA-05), and the OCI Security List/NSG ingress rules (INFRA-08) — an IP change silently breaks all three simultaneously, and the failure mode (SSH host-key mismatch, DNS pointing at a dead IP) looks like several unrelated problems at once rather than one root cause.

**Why it happens:** The ephemeral-vs-reserved distinction is not surfaced anywhere in the OCI console's default VM-creation flow in a way that flags it as a decision — the default "just works" for a first deploy and only becomes visible as a problem after an instance recreation event, which may be weeks later.

**How to avoid:** Explicitly provision a Reserved (static) Public IP (`[CITED: docs.oracle.com/en-us/iaas/Content/Network/Tasks/managingpublicIPs.htm]`, `[CITED: medium.com/@harjulthakkar - Replace Ephemeral Public IP with Reserved Public IP]`) and assign it to the VM at creation time (or immediately after), rather than relying on the default ephemeral one. This is a small additional console step, not a different architecture.

**Warning signs:** Any plan step that says "note the VM's public IP" without first confirming whether it's ephemeral or reserved.

**Phase to address:** Infra-migration phase, VM provisioning step — before DNS, SSH keys, or firewall rules reference the IP.

---

### Pitfall C: INFRA-06's "pre-merge DDL verification" has no ready-made tool to reach for, because this codebase has no migration framework

**What goes wrong:** A natural instinct given INFRA-06's wording ("pre-merge DDL verification step") is to reach for a migration-checking tool (Flyway's `validate`, a schema-diff action, etc.) — but this codebase has no migration framework. `docs/plans/backend-modernization/03-flyway-openapi.md` documents Flyway as an explicitly future, not-yet-started epic (`[VERIFIED: docs/plans/backend-modernization/ directory listing, this session]` — `03-flyway-openapi.md` exists alongside three hand-authored, standalone `*.sql` bridge scripts: `02-optimistic-locking-ddl.sql`, `03-activity-log-ddl.sql`, `04-password-hash-not-null-ddl.sql`). Each of these is a manually-run, `[VERIFIED: docs/plans/backend-modernization/04-password-hash-not-null-ddl.sql:20-28]` "run this manually via psql against the REAL Postgres database, immediately before merging/deploying" script, explicitly designed to be safely re-runnable (idempotent guards via `IF NOT EXISTS` or a pre-check `SELECT COUNT(*)` + `RAISE EXCEPTION`). There is no schema-version tracking table anywhere in this project.

**Why it happens:** "DDL verification" sounds like it should map to an off-the-shelf migration tool, but the actual artifact to verify against is this project's own hand-authored, idempotent bridge-script convention — a genuinely different (and simpler) problem.

**How to avoid:** Design INFRA-06 as a new CI job that runs `psql -f <script>.sql "$NEON_DIRECT_CONNECTION_STRING"` for each existing bridge script (relying on each script's own documented idempotency, exactly as the manual runbook already did), rather than introducing a generic migration-diff tool this codebase has no other infrastructure for. This automates the exact "human runs this via psql immediately before merge" step the existing scripts' own comments already describe as their intended usage — it is not adding new process, it is automating existing, already-documented process. Must run against Neon's **direct** (non-pooled) connection string, per both this phase's own success criteria and PITFALLS.md Pitfall 4's warning that PgBouncer transaction-mode pooling doesn't support the DDL-verification step's needs.

**Warning signs:** A plan step that introduces Flyway, Liquibase, or a generic schema-diff GitHub Action as a dependency for this phase — that would be scope creep into the explicitly-future Flyway epic, not what INFRA-06 asks for.

**Phase to address:** Infra-migration phase, CI/CD pipeline step — should land before the deploy step in the workflow's `needs:` graph, gating deploy on DDL correctness.

## Code Examples

### Caddyfile (INFRA-04)
```caddyfile
# Source: pattern confirmed via web search this session, cross-checked against Caddy's own
# documented automatic-HTTPS behavior (docs.caddyserver.com)
your-subdomain.duckdns.org {
    reverse_proxy app:8080
}
```
The `/data` volume MUST be a named volume, not ephemeral — repeatedly destroying/recreating the Caddy container without persisting `/data` causes a fresh Let's Encrypt certificate request on every restart, which Let's Encrypt will rate-limit/ban for a week after enough repeats (`[CITED: web search this session, cross-referenced against Let's Encrypt's own published rate limits]`).

### Docker Compose log driver caps (INFRA-07)
```yaml
# Source: docs.docker.com/engine/logging/drivers/json-file/, confirmed via web search this session
x-logging: &default-logging
  driver: json-file
  options:
    max-size: "10m"
    max-file: "3"

services:
  app:
    logging: *default-logging
  redpanda:
    logging: *default-logging
```

### appleboy/ssh-action + scp-action skeleton with fingerprint pinning (INFRA-05)
```yaml
# Source: pattern synthesized from appleboy/ssh-action and appleboy/scp-action's documented
# inputs (github.com/appleboy/ssh-action, github.com/appleboy/scp-action), confirmed this session
deploy-to-oracle:
  needs: [ build-and-push-docker-image, ddl-verify ]
  runs-on: ubuntu-latest
  if: success()
  steps:
    - name: Checkout code
      uses: actions/checkout@v4

    - name: Copy compose + Caddyfile to the VM
      uses: appleboy/scp-action@v1
      with:
        host: ${{ secrets.ORACLE_HOST }}
        username: ${{ secrets.ORACLE_USER }}
        key: ${{ secrets.ORACLE_SSH_KEY }}
        fingerprint: ${{ secrets.ORACLE_HOST_FINGERPRINT }}
        source: "docker-compose.prod.yml,Caddyfile"
        target: "~/kanban-board-backend/"

    - name: Deploy via docker compose
      uses: appleboy/ssh-action@v1.2.5
      with:
        host: ${{ secrets.ORACLE_HOST }}
        username: ${{ secrets.ORACLE_USER }}
        key: ${{ secrets.ORACLE_SSH_KEY }}
        fingerprint: ${{ secrets.ORACLE_HOST_FINGERPRINT }}
        script: |
          cd ~/kanban-board-backend
          export IMAGE_TAG=${{ needs.build-and-push-docker-image.outputs.image_tag }}
          docker compose -f docker-compose.prod.yml pull
          docker compose -f docker-compose.prod.yml up -d
```

### iptables commands for Oracle Ubuntu OS-level firewall (INFRA-08)
```bash
# Source: cross-checked across multiple independent guides this session (gist.github.com/mrladeia,
# dev.to/armiedema, syncbricks.com); Oracle's Ubuntu images ship netfilter-persistent pre-installed
sudo iptables -P INPUT DROP
sudo iptables -A INPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
sudo iptables -A INPUT -i lo -j ACCEPT
sudo iptables -A INPUT -p tcp --dport 22 -j ACCEPT
sudo iptables -A INPUT -p tcp --dport 80 -j ACCEPT
sudo iptables -A INPUT -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
# Deliberately NOT opened: 9092 (Redpanda Kafka), 8081 (Schema Registry) — internal-network-only
```
This is layer 3 of 3 (PITFALLS.md Pitfall 7) — the OCI Security List and NSG must independently permit only the same three ports; all three layers must be verified together, and reachability confirmed externally (e.g. `curl`/port scan from outside the VM), not just by reading console rule lists.

### Docker Compose healthcheck for the app container (INFRA-01)
```yaml
# Verified this session: `docker run --rm eclipse-temurin:21-jre-jammy sh -c "which curl wget bash"`
# returns /usr/bin/curl, /usr/bin/wget, /usr/bin/bash — no Dockerfile change needed.
app:
  # ...
  healthcheck:
    test: [ "CMD-SHELL", "wget --spider -q http://localhost:8080/api/actuator/health || exit 1" ]
    interval: 10s
    timeout: 5s
    retries: 5
    start_period: 30s
  restart: unless-stopped
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| Raw `ssh ... << EOF` heredoc + `docker run` in `deploy-to-ec2` | `appleboy/ssh-action`/`scp-action` + `docker compose` | This phase (INFRA-05) | Host-key pinning becomes possible (was entirely absent before — no `StrictHostKeyChecking` setting at all in the old job, `[VERIFIED: .github/workflows/deploy.yml:90-95]`); compose-based deploy gets restart policies, healthchecks, and log caps for free instead of a bare `docker run -d` |
| Local-only Kafka pipeline (Phase 4, fully verified) | Same pipeline, repointed at Redpanda's built-in registry on the Oracle VM | This phase (final cutover step, per ROADMAP's stated Phase 4→5 dependency) | No new code — Phase 4's own 12/12-verified test suite is the re-run target; this phase's only obligation is repointing `SCHEMA_REGISTRY_URL`/`KAFKA_BOOTSTRAP_SERVERS` and re-running that suite against the real target |

**Deprecated/outdated:** The AWS-era GitHub secrets (`EC2_SSH_KEY`, `EC2_HOST`, `EC2_USER`, AWS-scoped `DB_*`) are dead and must be revoked, not merely superseded — see the Runtime State Inventory above.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Caddy 2.x is still the current stable major line and its Docker Compose + automatic-HTTPS pattern is unchanged from what STACK.md already documented | Standard Stack | Low — Caddy's Caddyfile syntax has been stable for years; if wrong, only the exact image tag needs adjusting at execution time |
| A2 | `appleboy/scp-action`'s exact current tag was not independently confirmed this session (only `ssh-action`'s `v1.2.5` was verified via the official releases page) | Standard Stack, Code Examples | Low — action still functions if an older/different tag is used; plan should re-check the current tag at execution time rather than trust `@v1` floating |
| A3 | This codebase has no `LISTEN`/`NOTIFY`, session-level advisory lock, or temp-table usage that would break under Neon's pooled endpoint | Pitfall A | Medium — not exhaustively grepped this session; if such usage exists and was missed, it would surface as a production-only failure the local H2 test suite cannot catch |
| A4 | Oracle Always Free tier includes a Reserved Public IP at no additional cost (not just ephemeral IPs) | Pitfall B, Standard Stack | Low-Medium — if Reserved IPs require a paid tier or are capacity-constrained on Always Free, the plan needs a fallback (e.g. a DuckDNS updater cron reacting to IP changes) instead |
| A5 | The Docker Hub repository (`rudenkovladimir/kanban-board-backend`) is public, so the Oracle VM's `docker compose pull` needs no `docker login` credential | Code Examples, Runtime State Inventory | Medium — if the repo is private, the deploy step needs an additional `docker login` step on the VM using `DOCKERHUB_TOKEN`, not yet included in the sketched workflow above |

**If this table is empty:** N/A — see entries above.

## Open Questions

1. **Is `docker-compose.prod.yml` a standalone file or an overlay on the existing `docker-compose.yml`?**
   - What we know: CONTEXT.md leaves this as Claude's discretion; the local compose file's `postgres` service must NOT appear in production (Neon replaces it entirely), which argues for a standalone file (an overlay would need to explicitly remove a service, which Compose overlays cannot do — only add/modify).
   - What's unclear: whether shared fragments (e.g. the `app` service's build context) are worth extracting to avoid drift between the two files.
   - Recommendation: standalone `docker-compose.prod.yml`, since Compose has no clean "remove a service via overlay" mechanism and the two files' service sets genuinely differ (prod has no `postgres`, adds `caddy`).

2. **Is the Docker Hub repository public or private?**
   - What we know: the existing `build-and-push-docker-image` job authenticates with `DOCKERHUB_TOKEN` to push; nothing in the codebase confirms whether the resulting image is publicly pullable.
   - What's unclear: whether the deploy step on the Oracle VM needs its own `docker login` step.
   - Recommendation: check the Docker Hub repo's visibility setting as an early plan task (or add a defensive `docker login` step regardless — low cost, removes the ambiguity).

3. **Does Oracle Always Free actually include a no-cost Reserved Public IP for A1 Flex, given the June 2026 undocumented tier reduction (PITFALLS.md Pitfall 8)?**
   - What we know: Reserved Public IPs are a standard OCI feature, generally included in Always Free based on official docs.
   - What's unclear: whether this specific tenancy's (possibly already-reduced) Always Free allocation still includes one, given Oracle's documented pattern of undocumented changes.
   - Recommendation: verify in-console during the same early provisioning check already planned for confirming the OCPU/RAM shape (Claude's discretion item, CONTEXT.md).

## Exact New GitHub Secrets Needed (D-02 Guided Sequence)

D-02 requires the plan to tell the human exactly what to generate and where to paste it, one secret at a time, rather than front-loading. This is the concrete list the plan's checkpoints should walk through, in a sensible dependency order:

| # | Secret name | What the human generates/copies | Where from | Where it's pasted |
|---|---|---|---|---|
| 1 | `ORACLE_SSH_KEY` | A **locally-generated** (not VM-generated, per PITFALLS.md Pitfall 6) ed25519 keypair: `ssh-keygen -t ed25519 -f oracle_deploy_key -N ""` | Human's own machine | Private key (`oracle_deploy_key`) content → GitHub repo secret. Public key (`oracle_deploy_key.pub`) → appended to a dedicated, minimally-privileged deploy user's `~/.ssh/authorized_keys` on the VM (not `ubuntu`/root) |
| 2 | `ORACLE_USER` | The name of the dedicated deploy user created on the VM (e.g. `deploy`) | Created by the human during VM setup | GitHub repo secret |
| 3 | `ORACLE_HOST` | The VM's **Reserved** (static) public IP, or the DuckDNS hostname once DNS is pointed at it | OCI console, after Reserved IP assignment | GitHub repo secret |
| 4 | `ORACLE_HOST_FINGERPRINT` | Output of `ssh-keyscan -t ed25519 <ORACLE_HOST>` piped to `ssh-keygen -lf -`, run once by the human from their own machine after the VM is reachable | Human's own machine, against the real VM | GitHub repo secret (used by `ssh-action`/`scp-action`'s `fingerprint` input) |
| 5 | `NEON_POOLED_DATABASE_URL` (or split into `DB_HOST`/`DB_NAME`/`DB_USER`/`DB_PASS` matching the existing `application.properties` env-var convention) | The `-pooler` connection string from the Neon dashboard, with `?sslmode=require&channel_binding=require&prepareThreshold=0` appended | Neon dashboard | GitHub repo secret; also becomes the app container's runtime `DB_*` env vars in `docker-compose.prod.yml` |
| 6 | `NEON_DIRECT_DATABASE_URL` | The direct (non-`-pooler`) connection string from the Neon dashboard | Neon dashboard | GitHub repo secret, used only by the new `ddl-verify` CI job (INFRA-06) |

Not a GitHub secret: `SCHEMA_REGISTRY_URL` for production resolves to an internal Docker-network address (`http://redpanda:8081`, mirroring the local compose pattern `[VERIFIED: docker-compose.yml:75]`) — it is baked into `docker-compose.prod.yml` directly, not injected as a CI secret, since it never leaves the VM's internal network.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker Engine (local dev machine, for pre-deploy image testing) | Verifying `eclipse-temurin:21-jre-jammy` runtime tooling | ✓ | 29.4.2 (confirmed this session) | — |
| Oracle Cloud A1 Flex VM | INFRA-01 through INFRA-04, INFRA-07, INFRA-08 | ✗ (not yet provisioned) | — | No fallback — this is the deploy target itself; D-03 requires it stood up manually before CI/CD wiring |
| Neon Postgres project | INFRA-02, INFRA-06 | ✗ (not yet provisioned) | — | No fallback — locked stack choice |
| GitHub repo secrets access | INFRA-05, INFRA-06 | Presumed ✓ (existing repo already uses secrets for Docker Hub) | — | — |

**Missing dependencies with no fallback:**
- Oracle Cloud VM and Neon project must both be provisioned during this phase's execution — this is the phase's core deliverable, not a pre-condition to check for.

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | No (unchanged by this phase — existing session-based auth is untouched) | — |
| V3 Session Management | No (unchanged) | — |
| V4 Access Control | Yes | New Actuator health endpoint must be explicitly `permitAll()`-scoped to `/actuator/health` only (`management.endpoints.web.exposure.include=health`) — never the unrestricted `*` wildcard, which would expose `/env`, `/beans`, `/heapdump`, etc. publicly |
| V5 Input Validation | No new user input surface introduced by this phase | — |
| V6 Cryptography | Yes | TLS termination via Caddy's automatic Let's Encrypt HTTPS (never hand-rolled cert management); Neon connection requires `sslmode=require` + `channel_binding=require` (SCRAM-SHA-256-PLUS, hardened against MITM per Neon's own security guidance, `[CITED: neon.com/blog/postgres-needs-better-connection-security-defaults]`) |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| SSH MITM on the GitHub Actions → Oracle VM deploy connection | Spoofing/Tampering | `fingerprint`/`known_hosts` pinning via `appleboy/ssh-action`'s `fingerprint` input (never `StrictHostKeyChecking=no`) — PITFALLS.md Pitfall 6 |
| Kafka broker (Redpanda) or Schema Registry publicly reachable due to OCI's additive multi-layer firewall model | Information Disclosure/Tampering | Bind Redpanda's Kafka/Registry listeners to the internal Docker network only; audit Security List + NSG + OS firewall together, verify externally — PITFALLS.md Pitfall 7 |
| Actuator endpoint over-exposure (e.g. `/env`, `/heapdump` reachable if `exposure.include=*` is used instead of `=health`) | Information Disclosure | `management.endpoints.web.exposure.include=health` explicitly, `management.endpoint.health.show-details=never` |
| Deploy SSH private key generated on a shared/less-trusted machine (the VM itself) | Spoofing | Generate the keypair locally on the human's own machine, never on the VM — PITFALLS.md Pitfall 6 |
| Stale AWS-era secrets left in GitHub repo settings after cutover | Elevation of Privilege (dead credential surface) | Explicitly revoke `EC2_SSH_KEY`/`EC2_HOST`/`EC2_USER` and the AWS-scoped `DB_*` secrets once the new pipeline is proven, per the folded todo |

## Sources

### Primary (HIGH confidence)
- `docker-compose.yml`, `build.gradle`, `application.properties`, `SecurityConfiguration.java`, `ApiPaths.java`, `.github/workflows/deploy.yml`, `Dockerfile`, `04-VERIFICATION.md` — all read directly this session (see inline `[VERIFIED: ...]` tags for exact line ranges and quotes)
- `docker run --rm eclipse-temurin:21-jre-jammy sh -c "which curl wget bash"` — executed directly this session, confirmed all three present
- `gsd_run query package-legitimacy check` — executed this session, confirmed npm/pypi/crates-only scope
- [appleboy/ssh-action releases](https://github.com/appleboy/ssh-action/releases) — official GitHub releases page, fetched this session, confirmed `v1.2.5` current
- [Neon: Connection pooling](https://neon.com/docs/connect/connection-pooling) — official docs, fetched this session for PgBouncer/prepared-statement caveats
- [Oracle: Public IP Addresses](https://docs.oracle.com/en-us/iaas/Content/Network/Tasks/managingpublicIPs.htm) — official docs
- [Docker: JSON File logging driver](https://docs.docker.com/engine/logging/drivers/json-file/) — official docs
- [Spring Boot: Monitoring and Management Over HTTP](https://docs.spring.io/spring-boot/reference/actuator/monitoring.html) — official docs, confirms context-path + actuator base-path composition

### Secondary (MEDIUM confidence)
- [Neon: PgBouncer — The one with prepared statements](https://neon.com/blog/pgbouncer-the-one-with-prepared-statements) — vendor blog, corroborates the `max_prepared_statements` mitigation
- [Hibernate ORM Discourse: disable prepared statements for pgbouncer transaction pooling](https://discourse.hibernate.org/t/how-can-i-disable-prepared-statements-for-using-pgbouncer-with-transaction-pooling/6236) — community forum, cross-checked against Crunchy Data's blog on the same topic
- Multiple independently-converging Oracle-Cloud-iptables setup guides (gist.github.com/mrladeia, dev.to/armiedema, syncbricks.com) — LOW-MEDIUM individually, converged on the same `iptables`/`netfilter-persistent` command sequence
- [Medium: Replace Ephemeral Public IP with Reserved Public IP in OCI](https://medium.com/@harjulthakkar/replace-ephemeral-public-ip-with-reserved-public-ip-to-compute-vm-in-oracle-cloud-4bde0d9893d2) — web, cross-checked against official OCI docs

### Tertiary (LOW confidence)
- General Caddy/Docker Compose setup blog posts (oneuptime.com, nerdleveltech.com, etc.) — used only for Caddyfile syntax confirmation, cross-checked against Caddy's own documented behavior already cited in STACK.md
- DuckDNS setup blogs — noted the DNS-01-vs-HTTP-01 nuance (DuckDNS's own docs favor DNS-01 for wildcard use cases) but this phase only needs a single A-record + HTTP-01, which DuckDNS supports trivially as a plain dynamic-DNS host

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — Redpanda version and Actuator dependency directly verified against this repo's own files; `appleboy/ssh-action` version verified via official releases page this session
- Architecture: MEDIUM-HIGH — new findings (healthcheck tooling, IP behavior, pooled-connection mitigation) independently verified this session; overall system shape inherited from already-cross-checked milestone ARCHITECTURE.md
- Pitfalls: MEDIUM-HIGH — Pitfalls A-C are net-new, each backed by a direct codebase read or an official-docs fetch this session, not general web search alone
- Security: MEDIUM — ASVS mapping is straightforward given this phase's narrow surface (one new endpoint, TLS termination, SSH deploy); no new authentication/authorization logic introduced

**Research date:** 2026-08-04
**Valid until:** 2026-09-03 (30 days) — shorter validity recommended specifically for the `appleboy/ssh-action` version pin and Oracle's free-tier terms (PITFALLS.md Pitfall 8's precedent of undocumented mid-flight changes), both of which should be re-checked at execution time regardless of this file's age
</content>
