# Phase 8: Isolated Nonprod Environment, Live and Resettable - Research

**Researched:** 2026-08-18
**Domain:** Adding a second, colocated Docker Compose deployment (app + Redpanda broker) alongside an existing live single-VM production stack, with its own DNS/TLS hostname, its own Neon database branch, its own Kafka/Schema-Registry instance, and a profile-gated data-reset endpoint
**Confidence:** HIGH on architecture/mechanics (grounded directly in this repo's own production configs, read this session), MEDIUM on the one genuine open unknown this phase exists to close (nonprod Redpanda's live memory floor)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** The reset endpoint authenticates via a shared-secret header (a nonprod-only env var, e.g. `RESET_TOKEN`, checked against the request), not session auth, an IP allowlist, or reliance on hostname obscurity. Chosen because it's cheap, doesn't touch user accounts, and works identically for a manual `curl` today and Playwright's `beforeEach` later.
- **D-02:** The reset endpoint must be **profile-gated**, not merely auth-gated — the controller/bean only registers under a nonprod-only Spring profile, so it cannot exist in production at all, regardless of whether the auth check is ever misconfigured. — **Reversibility:** costly — removing the profile gate later would mean the endpoint could physically exist in a production build; treat "does not exist outside nonprod" as a standing invariant for this endpoint, not a preference to revisit casually.
- **D-03:** Reset target state is **genuinely empty** — truncate both Postgres and the activity-log/Kafka state to zero rows, no reseed/fixture data. Each E2E test is responsible for creating its own fixtures via the real API. (A seeded-fixture option was considered and explicitly rejected.)
- **D-04:** The nonprod Neon branch is created **schema-only/empty** (Flyway migrations applied fresh), not as a data copy of production. Consistent with D-03 — nonprod never holds a snapshot of production's real rows.
- **D-05:** Nonprod's DuckDNS hostname is `kanban-board-rud-vlad-473-nonprod.duckdns.org` — a `-nonprod` suffix on production's existing subdomain, matching this milestone's own "nonprod" terminology exactly (not `-staging`, to avoid the one place project docs and the live hostname would otherwise diverge). Must be enumerated exactly in CORS/session-cookie config, never wildcard-matched against the shared `*.duckdns.org` suffix (research Pitfall 5).
- **D-06:** NONPROD-05's CORS placeholder origin is the **same local-dev value** `CorsConfig.java` already defaults to (`http://localhost:5173,http://localhost:3000`), not a guessed future frontend hosting URL. A frontend dev pointing a local dev server at the nonprod API works immediately with zero config; no code change needed either way since the origin list is externalized via `app.cors.allowed-origins`.
- **D-07:** If the live Redpanda memory-floor measurement (NONPROD-06) shows colocation on the existing Netcup VPS doesn't hold, **stop and report before provisioning the fallback second VPS** (~€4/month) — do not provision it automatically. This is a new recurring real-money cost contingent on a measurement outcome that doesn't exist yet, and the user explicitly wants to approve it, not have it happen unattended. — **Reversibility:** one-way — provisioning a second paid VPS is a real recurring-cost commitment; the planner MUST insert a `checkpoint:decision` immediately before whichever task would provision the fallback VPS, framing "measured floor found, stay colocated" vs. "no safe floor found, provision fallback" as the two doors, even though the technical act of provisioning is not itself irreversible.

### Claude's Discretion

- Exact directory/Compose-project-name/container-name/network-name/volume-name choices for the nonprod stack — must all differ from production's per research Pitfall 1, but the specific names are an implementation detail, not a vision decision.
- Iteration procedure and starting values for the Redpanda `--memory`/`--smp` live-measurement pass (NONPROD-06) — mirrors the methodology already used for production (`docs/INFRA_RUNBOOK.md`'s Task 3 measurement), Claude's judgment on iteration count/workload shape.
- Exact shape of the `RESET_TOKEN` value and where it's generated/stored (`.env.nonprod` alongside DB creds) — mechanical, no user preference expressed.

### Deferred Ideas (OUT OF SCOPE)

- CI automation (Phase 9) and the hardening todos (Phase 10) are explicitly out of this phase's scope.
- No UI is involved — this is a backend-only infrastructure phase.
- Beyond the one folded todo (`2026-08-12-add-nonprod-staging-environment-and-playwright-e2e-ci-gate.md`, already tagged `resolves_phase: 8`), no other todos matched this phase's scope after review.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-------------------|
| NONPROD-01 | A nonprod Compose stack (app + Redpanda) is colocated on the existing Netcup VPS Lite 2, name-pinned (directory, Compose project name, container names, network name, volume names all distinct from production) and gated via Docker Compose `profiles:` | Architecture Pattern 1 (shared external network), Pitfall 1 (identity-collision precedent), Standard Stack (Compose `profiles:` mechanics) |
| NONPROD-02 | Nonprod's database is an isolated Neon branch, wired via its own env file/secrets structurally separate from `.env.prod` | Architecture Pattern 2 (`init_source: schema-only`), Environment Availability (Neon API key gap), Assumption A3 |
| NONPROD-03 | Nonprod's Kafka/Schema Registry isolation is a second, separate Redpanda broker instance | Already locked by prior research (RecordNameStrategy rationale); this phase's Standard Stack/Architecture confirm the second-broker mechanics carry no new library needs |
| NONPROD-04 | Nonprod is reachable over real HTTPS at its own stable hostname, via a second Caddy site block and a second DuckDNS subdomain | Architecture Pattern 3 (multi-site-block Caddyfile), Pitfall 3 (exact-hostname enumeration), Environment Availability (DuckDNS quota) |
| NONPROD-05 | CORS is configured for the expected nonprod frontend origin, reusing the existing externalized `app.cors.allowed-origins` config with zero code change | Standard Stack / Architectural Responsibility Map — confirms `CorsConfig.java` needs no code change, only a deployment-time env var |
| NONPROD-06 | Nonprod's actual Redpanda memory floor is measured live via iterative restart cycles, with a documented, exercised fallback to a second small VPS if no safe value is found | Summary (2GB/core official minimum vs. `--overprovisioned` measured reality), Pitfall 2 (cgroup-ceiling precedent), Environment Availability (fallback VPS gating) |
| RESET-01 | A test-data reset/seed mechanism exists for nonprod, reachable and manually verifiable via curl, covering both Postgres state and Kafka/activity-log state | Architecture Pattern 4 (`@Profile`-gated controller), Pattern 5 (`AdminClient.deleteRecords()`), Security Domain (constant-time token check), Open Question 2 (table scope) |
</phase_requirements>

## Summary

This phase colocates a shrunk second stack (`app` + `redpanda`, no second Caddy) on the same Netcup VPS that already runs production, reusing every pattern v1.2 Phase 5 already proved live: Docker Compose `profiles:` gates the new services, a Neon branch created with `init_source: schema-only` gives nonprod an empty, Flyway-migrated database with zero production rows, a second Caddy site block in the *same* Caddyfile picks up automatic Let's Encrypt HTTPS for the new exact hostname, and `CorsConfig.java`'s already-externalized `app.cors.allowed-origins` needs no code change. None of this requires a new library — `build.gradle` gains nothing.

Three things in this phase are genuinely new work, not copy-paste of an existing pattern, and the planner should treat them as such: (1) `docker-compose.prod.yml` currently declares no explicit `networks:` block at all (verified this session, lines 44-215) — Caddy and the new nonprod `app` container will be in two different Compose projects by design (NONPROD-01's identity-isolation requirement), so they cannot reach each other over Docker's implicit per-project default network; a new external network must be created and joined by both Caddy and nonprod's `app` service. (2) `@Profile`-gated bean/controller registration (D-02) does not exist anywhere in `src/main` today — the codebase's only existing profile usage is `spring.profiles.active=test` selecting `application-test.properties`, a different mechanism (property-file selection, not bean-registration gating). (3) Spring Kafka's `KafkaAdmin.deleteTopics()` runtime method — the obvious-looking way to implement RESET-01's Kafka-side truncation — was only added in spring-kafka 4.0; this project is pinned to Spring Boot 3.5.16's BOM, which manages spring-kafka 3.3.x. The reset endpoint must reach for the raw `AdminClient` (`AdminClient.create(admin.getConfigurationProperties())`) and call `deleteRecords()`, not `KafkaAdmin`.

The binding unknown is still NONPROD-06's Redpanda memory floor. Redpanda's own official docs recommend a **2 GB-per-core minimum for production** — well above the ~700MB starting point this project's own prior research suggested — but that guidance assumes dedicated hardware. Production's `docker-compose.prod.yml` already runs with `--overprovisioned` (a Seastar flag that relaxes exactly this assumption for shared/virtualized hosts) and measures real usage at ~348MiB, a small fraction of even its own 2G `--memory` cap. This tension — official minimum vs. measured reality under `--overprovisioned` — is exactly why D-07/NONPROD-06 calls for live iterative measurement rather than trusting either the doc'd minimum or a naive "half of prod" calculation.

**Primary recommendation:** Extend `docker-compose.prod.yml` (not a wholly separate file) with `profiles: ["nonprod"]`-gated `app-nonprod`/`redpanda-nonprod` services on a new named external network shared with `caddy`; add a second Caddyfile site block for the enumerated nonprod hostname; provision the Neon branch via `init_source: schema-only`; add a `@Profile("nonprod")`-gated `ResetController` using `AdminClient.deleteRecords()` for the Kafka side and a plain repository/JDBC truncate for the Postgres side.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Second HTTPS hostname (NONPROD-04) | API/Backend (edge: Caddy reverse proxy) | — | Caddy is the sole TLS-terminating process on the host; a second site block extends it, no new tier |
| Nonprod database isolation (NONPROD-02) | Database/Storage | API/Backend (env/secrets wiring) | Neon branch is the isolation boundary; the app only needs a different connection string |
| Kafka/Schema-Registry isolation (NONPROD-03) | Database/Storage | API/Backend (broker is a durable, stateful service co-managed like a datastore in this project's own architecture docs) | A second broker instance, not topic prefixing, per the already-locked decision |
| Resource sizing / colocation (NONPROD-01, NONPROD-06) | API/Backend (Compose/deploy infra) | Database/Storage (Redpanda's own memory floor is what's being measured) | Host-level cgroup budgeting is a deploy-infra concern; the broker is what consumes the budget |
| CORS placeholder origin (NONPROD-05) | API/Backend (Spring Security/CorsConfig) | Browser/Client (CORS is ultimately enforced by the browser) | Zero-code-change externalized config; browser is the actual enforcement point |
| Reset endpoint (RESET-01) | API/Backend (new controller) | Database/Storage (truncates Postgres tables + Kafka topic records) | A REST endpoint that fans out to two storage tiers it does not own the data model of |
| Secrets separation (`.env.nonprod`, part of NONPROD-02) | API/Backend (deploy-time env wiring) | — | Mirrors the existing `.env.prod` pattern exactly; no new mechanism |

## Package Legitimacy Audit

Not applicable — this phase introduces zero new external dependencies. `build.gradle` is unchanged; every mechanism used (Docker Compose `profiles:`, Neon branching, Caddy multi-site, `AdminClient`, `@Profile`) is either infrastructure config or already-present transitive code (`org.apache.kafka:kafka-clients`, pulled in via the existing `org.springframework.kafka:spring-kafka` dependency in `build.gradle`).

## Standard Stack

### Core

| Component | Version (verified) | Purpose | Why Standard |
|-----------|---------|---------|--------------|
| Docker Compose `profiles:` | Compose Spec v2+ (already in use: `docker-compose.prod.yml` pins `name: kanban-board-backend`) [VERIFIED: docker-compose.prod.yml:37-42, quoted: `name: kanban-board-backend`] | Gates the nonprod services so a plain `docker compose up` on the host does not start them | Native Compose mechanism, zero extra tooling — the exact feature NONPROD-01 names |
| Neon branching, `init_source: schema-only` | Console API v2 [CITED: neon.com/docs/guides/branching-neon-api] | Creates nonprod's database with production's schema shape but zero rows | Purpose-built for exactly this "isolated staging DB, no real data" use case |
| Caddy 2, multi site-block Caddyfile | `caddy:2` (already in use) [VERIFIED: docker-compose.prod.yml:46, quoted: `image: caddy:2`] | Second automatic-HTTPS hostname, no second container | One Caddy process already terminates 80/443 on the host; a second bound process is impossible without freeing those ports first |
| `org.apache.kafka.clients.admin.AdminClient` | Transitive via `org.springframework.kafka:spring-kafka` (unversioned, BOM-managed — Spring Boot 3.5.16 manages spring-kafka in the 3.3.x line) [CITED: docs.spring.io/spring-boot/appendix/dependency-versions] | `deleteRecords()` truncates Kafka topic messages for RESET-01 | Already on the classpath; `KafkaConsumerConfig.java` already demonstrates the `AdminClient.create(admin.getConfigurationProperties())` idiom this codebase uses elsewhere for admin operations [VERIFIED: src/main/java/com/vrudenko/kanban_board/config/KafkaConsumerConfig.java — no literal AdminClient.create call in this file today, but its Javadoc at lines 30-41 documents the KafkaAdmin-based bean-provisioning pattern this new code should follow] |
| `@Profile` (Spring Framework) | Already a transitive Spring Framework annotation, no new dependency | Physically excludes the reset controller from any non-`nonprod` Spring context | [CITED: docs.spring.io/spring-boot/3.5/reference/features/profiles.html] — "restrict a configuration class to a specific environment" |

### Supporting

| Component | Purpose | When to Use |
|-----------|---------|-------------|
| `rpk topic trim-prefix <topic> --offset end` | CLI-level equivalent of the reset endpoint's `deleteRecords()` call — useful for manual verification during Phase 8's own testing, not for the endpoint itself | Verifying the reset endpoint's Kafka-side effect from inside the `redpanda-nonprod` container, mirroring how `docs/INFRA_RUNBOOK.md` already verifies things live via `rpk registry subject list` |
| `rpk group describe <group>` | Confirms consumer offsets after a reset | Same verification pass |
| Neon CLI (`neonctl`) or direct `curl` against the Console API | Alternative to the dashboard for scripted/repeatable branch creation | If the operator wants the branch-creation step to be reproducible rather than a one-off dashboard click |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Extending `docker-compose.prod.yml` with `profiles:` | A wholly separate `docker-compose.nonprod.yml` | Research/SUMMARY.md's original suggestion; either satisfies NONPROD-01's literal "gated via Docker Compose `profiles:`" wording only if the profile-gated services live in the *same* Compose invocation prod's file already uses. A separate file with its own `profiles:` key works too but then needs its own explicit `name:` pin and its own decision about which network Caddy joins — no simpler, and splits the identity-isolation story across two files instead of one. Either is workable; a single extended file keeps one source of truth for "what's on this host." |
| `deleteRecords()` on the raw `AdminClient` | `KafkaAdmin.deleteTopics()` + recreate via the existing `NewTopic` beans | Deleting and recreating the topic also resets the consumer group's committed offsets and any partition-level config drift, which `deleteRecords()` alone does not — but topic delete/recreate is unavailable on `KafkaAdmin` at this project's spring-kafka version (3.3.x, not 4.0+) and topic-level delete/create carries its own timing risk (the `@KafkaListener` container must not be mid-poll when the topic disappears). `deleteRecords()` is the narrower, safer primitive for "zero rows," matching D-03's literal requirement without touching topic existence. |
| Branch via `init_source: schema-only` | Branch normally (copy-on-write full data copy) then `TRUNCATE ... CASCADE` every table | Both reach the same end state (empty). `schema-only` reaches it in one step with no intermediate window where a real data copy briefly exists in nonprod's branch; truncate-after-branch is a fallback if the Console UI doesn't expose the `schema-only` option directly (API-confirmed, UI equivalent unverified this session — see Open Questions). |

**Installation:** None — no new Gradle dependency, no new Docker image beyond what's already pinned (`caddy:2`, `docker.redpanda.com/redpandadata/redpanda:v26.2.1`, the existing `rudenkovladimir/kanban-board-backend` app image).

## Architecture Patterns

### System Architecture Diagram

```
Internet
  │
  ├── HTTPS :443 (SNI: kanban-board-rud-vlad-473.duckdns.org)          ─┐
  ├── HTTPS :443 (SNI: kanban-board-rud-vlad-473-nonprod.duckdns.org)  ─┤  same Caddy process,
  └── HTTP  :80  (ACME HTTP-01 challenges for both hostnames)          ─┘  two site blocks
        │
        ▼
  ┌─────────────────────────── Caddy (single container) ───────────────────────────┐
  │  site block 1: reverse_proxy app:8080          (prod default network)          │
  │  site block 2: reverse_proxy app-nonprod:8080  (new shared "edge" network)     │
  └──────────────┬───────────────────────────────────────┬─────────────────────────┘
                 │                                        │
     [prod default network]                    [edge external network]
                 │                                        │
         ┌───────▼────────┐                        ┌──────▼───────────┐
         │  app (prod)     │                        │  app-nonprod      │
         │  mem_limit: 3g  │                        │  mem_limit: ~1g    │
         └───────┬────────┘                        └──────┬───────────┘
                 │ Kafka+SR (internal-only)                │ Kafka+SR (internal-only, own broker)
         ┌───────▼────────┐                        ┌──────▼───────────┐
         │ redpanda (prod) │                        │ redpanda-nonprod  │
         │ 2200m/--memory 2G│                       │ measured floor    │
         └────────────────┘                        └───────────────────┘
                 │                                        │
         Neon: production branch                  Neon: nonprod branch
         (real user data)                          (schema-only, empty,
                                                      Flyway-migrated)

  Reset path (nonprod only):
  curl -H "X-Reset-Token: ..." https://…-nonprod…/api/admin/reset
    → ResetController (@Profile("nonprod"))
        → truncates Postgres tables via repositories/JDBC
        → AdminClient.deleteRecords() on kanban.activity / kanban.activity.dlt
```

### Recommended Project Structure

No new top-level directories. Additions land in existing package locations, following this codebase's own naming conventions:

```
docker-compose.prod.yml       # extended: app-nonprod, redpanda-nonprod services, profiles: ["nonprod"], new external "edge" network
Caddyfile                     # extended: second site block for {$APP_DOMAIN_NONPROD}
.env.nonprod(.example)        # new, sibling to .env.prod(.example) — structurally separate secret file (NONPROD-02)
src/main/java/.../controller/ # ResetController.java — @Profile("nonprod")-gated, follows {ObjectName}Controller.java naming
src/main/java/.../service/    # ResetService.java (if the truncate/deleteRecords logic is nontrivial enough to warrant a service, per existing controller→service layering)
```

### Pattern 1: Shared external Docker network so one Caddy container reaches two Compose projects

**What:** Prod's Caddy and nonprod's `app-nonprod` are deliberately in *different* Compose projects (NONPROD-01's own identity-isolation requirement forbids sharing a project). Docker Compose's implicit per-project default network therefore will NOT let Caddy resolve `app-nonprod` by service name. A user-defined network created with `external: true` and joined by both `caddy` (prod's compose file) and `app-nonprod` (nonprod's services, whether in the same file under a different `networks:` key or a sibling file) bridges the two projects.

**When to use:** Any time a single reverse proxy in one Compose project must reach a service defined in another Compose project on the same host.

**Verification needed before planning locks this in:** `docker-compose.prod.yml` currently has **no `networks:` block at all** [VERIFIED: docker-compose.prod.yml:44-215 — every service (`caddy`, `app`, `redpanda`) omits a `networks:` key entirely; the file's only network-adjacent content is the `volumes:` block at lines 211-215], meaning `caddy` and `app` currently share only Compose's implicit default network. Adding `caddy` to a second, external network is an additive change (Caddy can belong to multiple networks simultaneously) and should not disturb the existing `app`↔`redpanda` connectivity, but this needs to be an explicit task, not an assumption.

```yaml
# Source: this repo's own docker-compose.prod.yml pattern, extended per Context7-confirmed
# Docker Compose networks: external: true semantics (docker/docs)
networks:
  edge:
    external: true   # created once via `docker network create edge`, shared by both Compose projects

services:
  caddy:
    networks:
      - default        # existing implicit network, unchanged — reaches prod's `app`
      - edge            # new — reaches nonprod's `app-nonprod`
  app-nonprod:
    networks:
      - edge
    profiles: ["nonprod"]
```

### Pattern 2: Neon schema-only branch for a data-isolated nonprod database

**What:** Create nonprod's branch with `init_source: schema-only` so Neon copies the parent's schema shape but attaches zero data pages — no production row ever exists in the branch, even transiently.

**When to use:** Whenever a staging/nonprod branch must never see real user data (NONPROD-02, D-04).

```bash
# Source: neon.com/docs/guides/branching-neon-api [CITED]
curl --request POST \
     --url https://console.neon.tech/api/v2/projects/floral-union-23715140/branches \
     --header 'accept: application/json' \
     --header 'authorization: Bearer $NEON_API_KEY' \
     --header 'content-type: application/json' \
     --data '{
       "branch": {
         "parent_id": "<production-branch-id>",
         "name": "nonprod",
         "init_source": "schema-only"
       }
     }'
```

Project ID (`floral-union-23715140`) and the production branch's role in this call are already recorded in `docs/INFRA_RUNBOOK.md`'s Database section [VERIFIED: docs/INFRA_RUNBOOK.md:104-105, quoted: `| Project name | \`kanban-board-db\` |` / `| Project ID | \`floral-union-23715140\` |`]. This call requires a **Neon API key**, which is a credential this project does not yet have registered anywhere (the existing `DB_HOST`/`DB_NAME`/`DB_USER`/`DB_PASS` secrets are direct Postgres connection fields, not a Neon account-level API token) — a new secret to provision, distinct from every existing one.

After the branch exists, Flyway applies the checked-in migrations fresh against it — the same `flyway-verify`-style invocation `docs/INFRA_RUNBOOK.md` already documents for production (Task 2, "Automated deploy" section), just pointed at nonprod's direct (non-pooler) endpoint.

### Pattern 3: Second Caddy site block, same container, exact-hostname automatic HTTPS

**What:** Caddy natively supports multiple site blocks in one Caddyfile, each independently obtaining its own Let's Encrypt certificate via SNI — no plugin, no second listener needed.

```caddyfile
# Source: caddyserver.com/docs/caddyfile/concepts [CITED], extending this repo's existing
# {$APP_DOMAIN} pattern (Caddyfile, this repo)
{$APP_DOMAIN} {
	reverse_proxy app:8080
}

{$APP_DOMAIN_NONPROD} {
	reverse_proxy app-nonprod:8080
}
```

Both env vars are supplied the same way `APP_DOMAIN` already is today — via `docker-compose.prod.yml`'s `caddy.environment` block [VERIFIED: Caddyfile:3-7, quoted: `{$APP_DOMAIN} is Caddy's own environment-variable placeholder syntax (not a hardcoded literal) -- injected by docker-compose.prod.yml's \`caddy.environment.APP_DOMAIN\`]. `APP_DOMAIN_NONPROD` should be set to the exact enumerated hostname from D-05: `kanban-board-rud-vlad-473-nonprod.duckdns.org` — never a wildcard pattern (research Pitfall 5, reinforced by Caddy's own SNI-per-hostname certificate model: a wildcard match here would be a self-inflicted scope leak, not something Caddy forces).

### Pattern 4: `@Profile`-gated reset controller, genuinely new to this codebase

**What:** `@Profile("nonprod")` on the controller class means Spring never instantiates the bean outside a context where `nonprod` is an active profile — the endpoint does not exist in the deployed production JAR's runtime object graph at all, regardless of any auth-check bug (D-02's "does not exist outside nonprod" invariant).

**Genuinely new pattern for this codebase:** `grep -rn "@Profile" src/main/java` returns zero matches today [VERIFIED: session grep, zero results across src/main/java]. The only existing profile mechanism is `spring.profiles.active=test` selecting `application-test.properties` at property-file resolution time — a different Spring feature (profile-specific property files) than `@Profile`-gated bean registration. The planner should not assume there is an existing in-repo example to copy; this is the first use of `@Profile` on a component.

```java
// Source: docs.spring.io/spring-boot/3.5/reference/features/profiles.html [CITED],
// applied to this codebase's own controller-naming convention ({ObjectName}Controller.java)
// and RFC 7807 ProblemDetail error-handling convention (GlobalExceptionHandler already
// covers uncaught exceptions from any controller — no new exception-handling code needed
// here unless the reset flow needs a distinct error shape).
@Profile("nonprod")
@RestController
public class ResetController {

    @Autowired private ResetService resetService;

    @Value("${app.reset.token}")
    private String resetToken;

    @PostMapping(ApiPaths.RESET) // constant lives in ApiPaths.java per existing convention
    public ResponseEntity<Void> reset(@RequestHeader("X-Reset-Token") String suppliedToken) {
        // Constant-time comparison — a naive String.equals() is a timing side-channel on a
        // shared-secret check (ASVS V6 Cryptography concern, see Security Domain below).
        if (!MessageDigest.isEqual(
                suppliedToken.getBytes(StandardCharsets.UTF_8),
                resetToken.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        resetService.resetAll();
        return ResponseEntity.noContent().build();
    }
}
```

### Pattern 5: Truncating Kafka topic messages via `AdminClient.deleteRecords()`

**What:** `deleteRecords()` moves a partition's `LogStartOffset` forward, deleting every record before it — for RESET-01's "zero rows" requirement, delete to the partition's own current end offset (`RecordsToDelete.beforeOffset(<high-watermark>)`), which the app must first look up via the same `AdminClient`.

```java
// Source: pattern confirmed via spring-kafka's own documented AdminClient-access idiom
// (github.com/spring-projects/spring-kafka .../kafka/configuring-topics.adoc, "Access
// AdminClient Directly") [CITED] -- KafkaAdmin.deleteTopics() is NOT usable here: that
// runtime method was added in spring-kafka 4.0, and this project's Spring Boot 3.5.16 BOM
// manages spring-kafka 3.3.x [CITED: docs.spring.io/spring-boot/appendix/dependency-versions].
@Autowired private KafkaAdmin kafkaAdmin;

public void truncateActivityTopics() {
    try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
        for (String topic : List.of(KafkaTopics.ACTIVITY, KafkaTopics.ACTIVITY_DLT)) {
            var tp = new TopicPartition(topic, 0); // both topics are single-partition
                                                     // [VERIFIED: KafkaConsumerConfig.java:49-62,
                                                     // quoted: ".partitions(1)\n.replicas(1)"]
            long endOffset = admin.listOffsets(Map.of(tp, OffsetSpec.latest()))
                    .partitionResult(tp).get().offset();
            admin.deleteRecords(Map.of(tp, RecordsToDelete.beforeOffset(endOffset))).all().get();
        }
    }
}
```

`KafkaTopics.ACTIVITY` / `KafkaTopics.ACTIVITY_DLT` are the exact two topic name constants this codebase already defines [VERIFIED: src/main/java/com/vrudenko/kanban_board/constant/KafkaTopics.java:5-6, quoted: `public static final String ACTIVITY = "kanban.activity";` / `public static final String ACTIVITY_DLT = "kanban.activity.dlt";`]. Both are declared single-partition via `TopicBuilder...partitions(1)` [VERIFIED: src/main/java/com/vrudenko/kanban_board/config/KafkaConsumerConfig.java:48-62].

### Anti-Patterns to Avoid

- **Deleting and recreating the Kafka topic instead of truncating records:** breaks the `@KafkaListener`'s active subscription mid-flight if the reset endpoint fires while the consumer container is polling, and additionally isn't available as a `KafkaAdmin` runtime method at this project's spring-kafka version anyway. `deleteRecords()` is narrower and safer.
- **Wildcard-matching `*.duckdns.org` in CORS or cookie config:** leaks scope to every other DuckDNS tenant on the shared public suffix, not just this project's two subdomains (research Pitfall 5, already reflected in D-05). Note this project's session cookie currently sets **no explicit `Domain` attribute** [VERIFIED: src/main/resources/application.properties:129-135 — `server.servlet.session.cookie.*` keys present are `tracking-modes`, `http-only`, `secure`, `name`, `path`, `max-age`, `same-site`; no `domain` key exists], so the cookie is already host-only-scoped by default and structurally cannot leak across the two subdomains on its own — the wildcard risk is specifically in `app.cors.allowed-origins`, not the session cookie.
- **Assuming a copy-pasted nonprod Compose service inherits isolation "for free":** this project has already hit the less-severe sibling of this bug once (the `root`-vs-`kanban-board-backend` Compose project-name collision, `docs/INFRA_RUNBOOK.md`'s "Deviation found and fixed" section) — every identity axis (directory, project name, container names, network name, volume names) must be independently verified different, not assumed from "it's a different service block."
- **Trusting `String.equals()` for the `RESET_TOKEN` comparison:** a variable-time string comparison on a shared secret is a textbook timing side-channel (ASVS V6). Use `MessageDigest.isEqual()`.

## Runtime State Inventory

Not applicable — this phase is new infrastructure (a second, additive deployment), not a rename/refactor/migration of existing production state. No existing string, ID, or resource name is being changed; production's own containers, volumes, network, and DNS record are untouched by this phase's scope.

## Common Pitfalls

### Pitfall 1: Deploy identity collision between prod and nonprod

**What goes wrong:** A nonprod Compose service block that doesn't differ from production on every one of directory / Compose project name / container names / network name / volume names silently converges onto or corrupts the live production stack instead of creating a second one.
**Why it happens:** Docker Compose derives several of these from context (CWD basename → project name, by default) unless pinned explicitly — exactly the mechanism that already bit this project once.
**How to avoid:** Explicit `profiles: ["nonprod"]` plus explicit, distinctly-named `container_name:`/volume keys for every nonprod service; the shared `name: kanban-board-backend` at the top of `docker-compose.prod.yml` [VERIFIED: docker-compose.prod.yml:42, quoted: `name: kanban-board-backend`] already covers this file's *own* project identity — nonprod services added into this same file inherit that project name too, so their container/volume/network names are the actual differentiator, not a second `name:` pin (Compose does not support two project names in one file).
**Warning signs:** `docker compose ps` from the nonprod service definitions showing production's existing containers, or a fresh empty volume where a persistent one was expected.

### Pitfall 2: Resource contention is a cgroup ceiling problem, not host-level arithmetic

**What goes wrong:** Summing `mem_limit` values across both stacks can look safe on paper while still risking simultaneous-peak overcommit, because `mem_limit` is a per-container hard cap, not a host reservation guarantee.
**Why it happens:** This project has already hit exactly this category of surprise once — setting `mem_limit` numerically equal to Redpanda's own `--memory` broke every restart with `insufficient physical memory`, because a cgroup-equal cap leaves no headroom for cgroup accounting overhead [VERIFIED: docker-compose.prod.yml:133-141, quoted: "Could not initialize seastar: std::runtime_error (insufficient physical memory: needed 2147483648 available 2078277632)"].
**How to avoid:** Live iterative restart-cycle measurement (NONPROD-06, D-07's already-locked methodology) — same discipline as production's own Task 3 correction, not arithmetic. Redpanda's own official docs recommend 2 GB/core minimum for *production* deployments [CITED: redpanda-data/docs, "Each Redpanda broker requires a minimum of 2 GB of memory per core"], well above a naive nonprod starting guess — but that figure assumes dedicated capacity; production's own `--overprovisioned` flag [VERIFIED: docker-compose.prod.yml:181, quoted: `- --overprovisioned`] is precisely the documented mechanism for relaxing that assumption on a shared/virtualized host, and production's own measured real usage (~348MiB) sits far below even its own 2G cap. Iterate cautiously — a value that makes the broker fail its healthcheck is a failed correction, not a tighter one (the same standard this project's own Task 3 already applied).
**Warning signs:** `redpanda-nonprod` failing its `rpk cluster health` healthcheck on restart, or host `free`/`docker stats` showing near-zero headroom under simultaneous prod+nonprod load.

### Pitfall 3: DuckDNS is a shared public suffix — never wildcard-match it

**What goes wrong:** Any cookie/CORS/cert config that pattern-matches `*.duckdns.org` instead of the two full exact hostnames leaks scope to unrelated tenants on the same public suffix.
**Why it happens:** `*.duckdns.org` is a real public DNS suffix shared by every DuckDNS user, not a domain this project owns.
**How to avoid:** D-05 already locks the exact hostname (`kanban-board-rud-vlad-473-nonprod.duckdns.org`); enforce this in both the Caddyfile site-block address and `app.cors.allowed-origins`, never a wildcard. The session cookie is unaffected (no `Domain` attribute is set — see Anti-Patterns above), but this pitfall remains live for CORS.
**Warning signs:** A CORS config or Caddyfile address containing a literal `*` before `.duckdns.org`.

### Pitfall 4: `@Profile` alone doesn't stop the endpoint if the profile is accidentally active in prod

**What goes wrong:** `@Profile("nonprod")` only excludes the bean when `nonprod` is NOT an active profile — if a deploy script or `.env.prod` ever accidentally sets `SPRING_PROFILES_ACTIVE=nonprod` (or includes it in a comma-separated list) on the production container, the endpoint reappears.
**Why it happens:** Profile activation is an environment-variable/property value, not a compile-time constant — a copy-paste or typo in deploy tooling is a real failure mode, not a hypothetical.
**How to avoid:** This is why D-01's shared-secret token check is *defense in depth*, not redundant with D-02 — the two controls address different failure modes (D-02: the bean doesn't exist; D-01: even if it somehow did, the caller still needs the secret). Document this explicitly in the plan rather than treating either control as sufficient alone.
**Warning signs:** `SPRING_PROFILES_ACTIVE` in `.env.prod` containing anything other than the empty/default value.

## Code Examples

Verified/cited patterns from official sources — see Architecture Patterns section above for the full, annotated versions of:
- Docker Compose `profiles:` + external shared network (Pattern 1)
- Neon `init_source: schema-only` branch creation (Pattern 2)
- Caddy multi-site-block Caddyfile (Pattern 3)
- `@Profile`-gated reset controller with constant-time token check (Pattern 4)
- `AdminClient.deleteRecords()` topic truncation (Pattern 5)

### Verifying the reset endpoint's Kafka-side effect manually (for Phase 8's own testing pass)

```bash
# Source: redpanda-data/docs rpk reference [CITED]
docker compose --env-file ./.env.nonprod -f docker-compose.prod.yml exec redpanda-nonprod \
  rpk topic trim-prefix kanban.activity --offset end --no-confirm
docker compose --env-file ./.env.nonprod -f docker-compose.prod.yml exec redpanda-nonprod \
  rpk group describe activity-log
```

`activity-log` is this codebase's own exact consumer group ID constant [VERIFIED: src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumer.java:49, quoted: `public static final String GROUP_ID = "activity-log";`].

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| Manual `docker run` / single-environment deploys | Compose `profiles:`-gated multi-environment single-host colocation | Standard Compose Spec v2+ feature, already stable | Enables this phase's whole approach without a second host |
| Full-copy staging database branch, manually scrubbed | Neon `init_source: schema-only` at creation time | Available in Neon's current Branches API | Removes the "did we actually scrub every PII column" question entirely — no data ever lands in the branch |

**Deprecated/outdated:** None identified specific to this phase's stack — Compose `profiles:`, Caddy multi-site, and Neon branching are all current, stable, first-class features of their respective tools, not superseded patterns.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | The Neon Console (web UI) exposes a schema-only/no-data toggle equivalent to the API's `init_source: schema-only`, not just the API | Architecture Patterns, Pattern 2 | If the UI lacks this option, the operator must either use the API/CLI directly (a documented, still-viable path) or fall back to branch-then-truncate (documented as the fallback in Alternatives Considered) — not a blocker, but changes which exact steps the plan should specify |
| A2 | `redpanda-nonprod`'s live-measured memory floor will land meaningfully below production's own 2200m `mem_limit`, leaving genuine headroom on the 7.8GiB host | Summary, Pitfall 2 | If measurement instead shows nonprod needs close to production's own floor, the combined worst case approaches the host's real ceiling and D-07's fallback-VPS decision point triggers sooner than expected — this is exactly what NONPROD-06's live measurement exists to resolve, not a planning defect |
| A3 | A second Neon API key (distinct from the existing `DB_HOST`/`DB_NAME`/`DB_USER`/`DB_PASS` secrets) is not yet provisioned anywhere in this project's secret stores | Architecture Patterns, Pattern 2 | If wrong (a Neon API key already exists somewhere unaudited), the plan would needlessly gate branch creation behind a new-credential-provisioning step; low risk either way since provisioning one is cheap |

**If this table is empty:** N/A — see above; three claims flagged.

## Open Questions

1. **Does the Neon Console UI offer a literal "schema-only" branch-creation option, or is the API the only path?**
   - What we know: the Console API's `init_source: schema-only` parameter is documented and current [CITED: neon.com/docs/guides/branching-neon-api].
   - What's unclear: whether the dashboard UI (as opposed to the API/CLI) surfaces the same option under a labeled toggle, since this project's prior manual-deploy sessions have consistently used the Neon dashboard for account-level actions.
   - Recommendation: the planner should specify the API/curl path as the primary mechanism (it's confirmed to exist) and note the UI as "check first, may be faster" rather than assuming either.

2. **Should the reset endpoint also truncate the domain tables (boards/columns/tasks/subtasks/users) or only the activity-log/Kafka side?**
   - What we know: D-03 says "truncate both Postgres and the activity-log/Kafka state to zero rows" and "each E2E test is responsible for creating its own fixtures via the real API" — implying the whole nonprod database goes to zero, not just the activity log table.
   - What's unclear: whether `UserEntity` rows (test accounts) are also expected to be wiped, given some future Playwright suite might want to sign up fresh test users on every reset, or might prefer a small set of persistent test accounts to survive resets.
   - Recommendation: default to truncating every domain table (boards → columns → tasks → subtasks cascade, plus users and activity_log) for a genuinely clean baseline, consistent with D-03's "no reseed/fixture data" framing — but flag this as a planning-time confirmation point since CONTEXT.md doesn't explicitly name which tables.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|--------------|-----------|---------|----------|
| Docker Compose (host) | NONPROD-01 | Yes | Compose V2 plugin `v5.4.0` [VERIFIED: docs/INFRA_RUNBOOK.md:93-94] | — |
| Docker (host) | NONPROD-01 | Yes | `29.7.2` [VERIFIED: docs/INFRA_RUNBOOK.md:93] | — |
| DuckDNS spare subdomain quota | NONPROD-04 | Likely (1 of a commonly-documented 5-subdomain limit already used by production) | — [source: web search, MEDIUM confidence, single account type not independently confirmed against this project's actual account] | Confirm directly at registration time — low risk, DuckDNS subdomains are free and instant to register |
| Neon API key (for `init_source: schema-only` branch creation) | NONPROD-02 | Not yet provisioned (no existing secret matches this purpose — see Assumption A3) | — | Manual branch creation via Neon dashboard if the UI supports it (Open Question 1); otherwise generate an API key from the Neon account settings |
| `rpk` (inside the Redpanda image) | RESET-01 verification, NONPROD-06 measurement | Yes — ships inside `docker.redpanda.com/redpandadata/redpanda:v26.2.1`, already used for `rpk cluster health` healthchecks and `rpk registry subject list` verification | v26.2.1 (pinned, matches local dev and production exactly) [VERIFIED: docker-compose.prod.yml:123] | — |
| A second small Netcup VPS (fallback only) | NONPROD-06 fallback path, D-07 | Not provisioned — deliberately not provisioned up front per REQUIREMENTS.md's Out of Scope and D-07's explicit "stop and report before provisioning" instruction | — | This *is* the fallback; provisioning it is itself the fallback action, gated behind a `checkpoint:decision` per D-07 |

**Missing dependencies with no fallback:** None — every dependency above either already exists or has a documented, low-risk path to obtain it.

**Missing dependencies with fallback:** Neon API key (generate one); DuckDNS quota (confirm at registration, has historically had headroom).

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|----------------|---------|-------------------|
| V2 Authentication | Yes (for the reset endpoint) | Shared-secret header compared via `MessageDigest.isEqual` (constant-time) — a lighter-weight control than session auth, deliberately chosen per D-01 for a non-user-facing, nonprod-only operational endpoint |
| V4 Access Control | Yes | `@Profile("nonprod")` bean-registration gating (physical non-existence outside nonprod) as the primary control; the token check is defense-in-depth (Pitfall 4) |
| V5 Input Validation | Minimal — the reset endpoint takes no user-supplied payload beyond the header token itself | N/A beyond the header presence/format check |
| V6 Cryptography | Yes | Constant-time comparison for the shared-secret token (`MessageDigest.isEqual`, not `String.equals`) — avoids a timing side-channel on a security-relevant string comparison |
| V9 Communications | Yes (already covered by NONPROD-04) | All nonprod traffic, including the reset endpoint, is served exclusively over the same real Let's Encrypt HTTPS this phase establishes — no plaintext path exists |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|----------------------|
| Reset endpoint reachable in production due to profile misconfiguration | Elevation of Privilege / Tampering | `@Profile("nonprod")` (physical non-existence) + shared-secret token (defense in depth) — Pitfall 4 |
| Timing attack against the `RESET_TOKEN` comparison | Information Disclosure (enabling brute-force) | `MessageDigest.isEqual`, never `String.equals` or `.contentEquals` |
| DuckDNS wildcard-suffix scope leak in CORS config | Spoofing (cross-tenant origin) | Exact hostname enumeration only, never `*.duckdns.org` (D-05, Pitfall 3) |
| Shared Kafka broker/Schema-Registry between prod and nonprod | Tampering (nonprod could corrupt prod's registered compatibility history) | Already resolved by the locked NONPROD-03 decision — a fully separate second broker instance, not topic-prefixing on a shared one |
| Nonprod credentials leaking into or being confused with production's `.env.prod` | Information Disclosure | Structurally separate `.env.nonprod` file (NONPROD-02), mirroring the existing `.env.prod` pattern exactly, never merged |
| Deploy-job/Compose identity collision silently mutating live production | Tampering (unintended) | Explicit, independently-verified distinct directory/project/container/network/volume names (Pitfall 1) — this is a Phase 9 CI concern too, but the underlying Compose file structure this phase establishes is what Phase 9's job graph will build on, so getting the identity axes right here matters beyond just this phase |

## Sources

### Primary (HIGH confidence)
- This repository's own `docs/INFRA_RUNBOOK.md`, `docker-compose.prod.yml`, `Caddyfile`, `CorsConfig.java`, `KafkaTopics.java`, `KafkaConsumerConfig.java`, `ActivityLogConsumer.java`, `ActivityLogRepository.java`, `build.gradle`, `application.properties`, `.planning/REQUIREMENTS.md`, `.planning/research/SUMMARY.md` — read directly this session
- Context7 `/docker/docs` — Compose `profiles:` semantics
- Context7 `/websites/neon` — branch creation, `init_source: schema-only`, copy-on-write model
- Context7 `/websites/caddyserver_caddyfile` — multi-site-block Caddyfile, automatic HTTPS
- Context7 `/redpanda-data/docs` — `rpk topic trim-prefix`, `rpk group` commands, official memory-per-core guidance
- Context7 `/spring-projects/spring-kafka` — `AdminClient` access pattern, `KafkaAdmin` version history
- Context7 `/websites/spring_io_spring-boot_3_5` — `@Profile` annotation semantics

### Secondary (MEDIUM confidence)
- WebSearch: Spring Boot 3.5's BOM-managed spring-kafka version line (3.3.x, not 4.0) — cross-corroborated against spring.io blog posts and the official dependency-versions page
- WebSearch: DuckDNS's commonly-documented 5-subdomain-per-account limit — multiple independent secondary sources, not a primary DuckDNS-published spec page

### Tertiary (LOW confidence)
- None used without at least secondary corroboration in this research pass.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — every core mechanism is either already live in this repo (Compose, Caddy, Kafka topics) or confirmed via current official docs (Neon API, Spring profiles)
- Architecture: HIGH for the network/Compose/DNS/TLS mechanics (grounded in files read directly this session); MEDIUM for the exact Redpanda memory floor, which this phase's own NONPROD-06 work exists to resolve empirically
- Pitfalls: HIGH — four of five sourced directly from this repo's own documented incident history (`docs/INFRA_RUNBOOK.md`) or from a version mismatch this session verified against `build.gradle`

**Research date:** 2026-08-18
**Valid until:** 30 days (stable infra tooling — Compose, Caddy, Neon, Redpanda APIs are not fast-moving) — re-verify sooner if Spring Boot or spring-kafka are bumped before this phase executes, since the `AdminClient`-vs-`KafkaAdmin.deleteTopics()` finding is version-sensitive
