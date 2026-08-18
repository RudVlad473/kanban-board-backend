# Phase 8: Isolated Nonprod Environment, Live and Resettable - Pattern Map

**Mapped:** 2026-08-18
**Files analyzed:** 7
**Analogs found:** 5 / 7 (2 are genuinely new patterns with no in-repo analog, flagged below)

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|-----------------|---------------|
| `docker-compose.prod.yml` (extended: `app-nonprod`, `redpanda-nonprod`, `edge` network) | config | batch/infra | `docker-compose.prod.yml` (itself — extend in place) | exact (self-extension) |
| `Caddyfile` (extended: second site block) | config | request-response (reverse proxy) | `Caddyfile` (itself — extend in place) | exact (self-extension) |
| `.env.nonprod` / `.env.nonprod.example` (new) | config | file-I/O | `.env.prod` / `.env.prod.example` (structure, not literal secret values — both denied to Read tool by sandbox as secret-shaped files; infer shape from `docker-compose.prod.yml`'s `environment:` key references, e.g. `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASS`, `DB_JDBC_PARAMS`, `APP_DOMAIN`, `IMAGE_TAG`) | role-match |
| `src/main/java/.../controller/ResetController.java` (new) | controller | request-response | `src/main/java/com/vrudenko/kanban_board/controller/UserController.java` | role-match (session-free, single-purpose controller shape) |
| `src/main/java/.../service/ResetService.java` (new) | service | CRUD (Postgres truncate) + event-driven (Kafka `deleteRecords`) | `src/main/java/com/vrudenko/kanban_board/service/ActivityLogService.java` | role-match |
| `src/main/java/.../constant/ApiPaths.java` (modified — add a `RESET` constant) | config | n/a | `ApiPaths.java` (itself — extend in place) | exact (self-extension) |
| Kafka `AdminClient.deleteRecords()` call site inside `ResetService.java` | utility (embedded in service) | event-driven | `src/main/java/com/vrudenko/kanban_board/config/KafkaConsumerConfig.java` (bean-provisioning idiom, not a literal `AdminClient.create` call — none exists in repo yet) | role-match (partial — see No Analog Found) |

## Pattern Assignments

### `docker-compose.prod.yml` (config, batch/infra — extend in place)

**Analog:** the file itself. This is an additive edit, not a from-scratch file.

**Identity-pinning pattern to replicate for every new service** (lines 37-43):
```yaml
# Pinned so `docker compose` commands converge on the same project regardless of which directory
# the file happens to live in on the host...
name: kanban-board-backend
```
Nonprod services added into this same file inherit that one project name — per research Pitfall 1, the differentiator for `app-nonprod`/`redpanda-nonprod` must be explicit, distinct `container_name:` and volume keys, not a second `name:` pin (Compose doesn't support two project names in one file).

**Logging anchor to reuse verbatim** (lines 22-35):
```yaml
x-logging: &default-logging
  driver: json-file
  options:
    max-size: "10m"
    max-file: "3"
```
Apply `logging: *default-logging` to `app-nonprod` and `redpanda-nonprod` exactly as `caddy`/`app`/`redpanda` already do — the acceptance bar recorded in the anchor's own comment is "no service lacks a logging block."

**`app` service shape to clone for `app-nonprod`** (lines 65-118): mem_limit, `depends_on: redpanda: condition: service_healthy` (becomes `redpanda-nonprod`), the `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASS`/`DB_JDBC_PARAMS` environment block (values sourced from `.env.nonprod` instead of `.env.prod`), `KAFKA_BOOTSTRAP_SERVERS`/`SCHEMA_REGISTRY_URL` pointed at `redpanda-nonprod:19092`/`redpanda-nonprod:8081`, the identical healthcheck shape (`wget ... /api/actuator/health`), no `ports:` entry (Caddy reaches it only over the new `edge` network by service name).

**`redpanda` service shape to clone for `redpanda-nonprod`** (lines 120-209): same `--overprovisioned` flag, same healthcheck (`rpk cluster health`), `mem_limit` and `--memory`/`--smp` values driven by NONPROD-06's live measurement rather than copied numerically — see the extensive measured-basis comment at lines 152-180 for the methodology to mirror (idle baseline + burst workload, not arithmetic).

**New `edge` external network** — not present in this file today (no `networks:` block exists at all, lines 44-215 verified network-key-free): add a top-level `networks: edge: external: true`, join `caddy` to both `default` and `edge`, join `app-nonprod` to `edge` only. Compose allows a service to belong to multiple networks simultaneously; this does not disturb existing `app`↔`redpanda` connectivity.

**`profiles:` gate** — add `profiles: ["nonprod"]` to `app-nonprod` and `redpanda-nonprod` so a plain `docker compose up` (no `--profile nonprod`) never starts them.

---

### `Caddyfile` (config, request-response — extend in place)

**Analog:** the file itself, extended with a second site block.

**Existing site block to mirror exactly** (lines 8-13):
```caddyfile
{$APP_DOMAIN} {
	reverse_proxy app:8080
}
```

**Pattern to add**, per RESEARCH.md Pattern 3:
```caddyfile
{$APP_DOMAIN_NONPROD} {
	reverse_proxy app-nonprod:8080
}
```
`APP_DOMAIN_NONPROD` supplied the same way `APP_DOMAIN` already is — via `docker-compose.prod.yml`'s `caddy.environment` block (add `APP_DOMAIN_NONPROD: ${APP_DOMAIN_NONPROD}` next to the existing `APP_DOMAIN: ${APP_DOMAIN}` at line 55). Set to the exact D-05 hostname `kanban-board-rud-vlad-473-nonprod.duckdns.org` — never a wildcard (Pitfall 3). No `tls internal`/`auto_https off` here either, matching the existing block's comment (lines 15-19) — automatic Let's Encrypt HTTPS via HTTP-01 challenge is required for both hostnames, sharing the same already-open port 80/443 and the same named `caddy-data` volume (so recreating the container doesn't re-trigger a Let's Encrypt rate limit for either hostname).

---

### `.env.nonprod` / `.env.nonprod.example` (config, file-I/O)

**Analog:** `.env.prod`/`.env.prod.example` structurally (contents not directly readable — sandboxed as secret-shaped files). Infer required keys from `docker-compose.prod.yml`'s `${VAR}` references that any nonprod-serving compose invocation must supply:

- `DB_HOST`, `DB_PORT` (defaults `5432`), `DB_NAME`, `DB_USER`, `DB_PASS`, `DB_JDBC_PARAMS` — nonprod's Neon branch connection fields (D-04's schema-only branch), structurally separate from `.env.prod`'s production values, never merged (NONPROD-02, Security Domain).
- `IMAGE_TAG` — same image tag mechanism prod uses (pulled by tag from Docker Hub; nonprod can share the same tag as prod or track a different one, implementation detail).
- `APP_DOMAIN_NONPROD` — D-05's exact hostname, consumed by the new Caddyfile site block.
- `RESET_TOKEN` — new secret, nonprod-only, compared via `MessageDigest.isEqual` in `ResetController` (D-01). Exact generation mechanism left to Claude's discretion per CONTEXT.md.
- `SPRING_PROFILES_ACTIVE=nonprod` — activates `@Profile("nonprod")` gating; per Pitfall 4, this key must never appear in `.env.prod`.

Deploy invocation pattern to follow (mirrors production's own documented pattern, `docker-compose.prod.yml` lines 12-14): `docker compose -f docker-compose.prod.yml --env-file .env.nonprod --profile nonprod up -d`.

---

### `src/main/java/com/vrudenko/kanban_board/controller/ResetController.java` (controller, request-response)

**Analog:** `src/main/java/com/vrudenko/kanban_board/controller/UserController.java`

**Imports pattern** (lines 1-18 of UserController.java) — same package-grouped import block convention: project imports first (`constant`, `dto`, `security`, `service`), then `jakarta.validation`, then `org.springframework.*` alphabetically. `ResetController` additionally needs `org.springframework.context.annotation.Profile`, `org.springframework.beans.factory.annotation.Value`, `java.security.MessageDigest`, `java.nio.charset.StandardCharsets` — none of which appear in any existing controller (this is the "genuinely new" surface RESEARCH.md flags).

**Structural shape to copy** (UserController.java lines 32-49): `@RestController` + `@RequestMapping(ApiPaths.<CONST>)` + field-injected single service via `@Autowired`, thin handler methods that immediately delegate to the service and wrap the result in `ResponseEntity`. `ResetController` deviates from `UserController`'s `@PreAuthorize("isAuthenticated()")` — RESET-01 uses D-01's shared-secret header instead of session auth, so no `@PreAuthorize` annotation and no `@CurrentUserId` parameter resolver are used here; this is the one place in the codebase a mutating endpoint is deliberately not session-gated.

**New annotation this file introduces** — `@Profile("nonprod")` at the class level (D-02). Per RESEARCH.md: `grep -rn "@Profile" src/main/java` returns zero matches today — there is no existing in-repo example to copy verbatim; use the annotation directly per Spring's documented semantics (RESEARCH.md Pattern 4 gives the full worked example, reproduced there with `MessageDigest.isEqual` constant-time token comparison and a `403`/`204` response shape). Route constant (`ApiPaths.RESET`, new) follows the existing `ApiPaths` naming convention (see below).

**Error handling** — no controller-level try/catch, matching every other controller in this codebase; uncaught exceptions propagate to `GlobalExceptionHandler` (`src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java`), which already maps exception types to the RFC 7807 `ProblemDetail` envelope. The token-mismatch case is the one path this controller handles inline (`403 Forbidden`, not an exception) since it's a routine, expected outcome, not a domain error.

---

### `src/main/java/com/vrudenko/kanban_board/service/ResetService.java` (service, CRUD + event-driven)

**Analog:** `src/main/java/com/vrudenko/kanban_board/service/ActivityLogService.java`

**Imports/structure pattern** (ActivityLogService.java lines 1-21): `@Service` stereotype, field-injected dependencies via `@Autowired`, `jakarta.transaction.Transactional` on the mutating method. `ResetService` additionally needs `org.apache.kafka.clients.admin.AdminClient`, `org.apache.kafka.clients.admin.RecordsToDelete`, `org.apache.kafka.clients.admin.OffsetSpec`, `org.apache.kafka.common.TopicPartition`, `org.springframework.kafka.core.KafkaAdmin`, and `com.vrudenko.kanban_board.constant.KafkaTopics` — none of which any existing service imports (this service straddles two storage tiers, unlike every existing service which stays within JPA).

**Postgres-side truncate** — no existing repository method truncates a whole table; the closest structural precedent is `TaskService.deleteAllByColumn()`'s documented batch-delete + `entityManager.flush()`/`entityManager.clear()` discipline (per project CLAUDE.md's "Forgetting EntityManager.clear() After Batch Delete" anti-pattern entry) — `ResetService.resetAll()` should follow the same flush/clear discipline after any bulk JPQL delete or native `TRUNCATE`, to keep the persistence context consistent within the same `@Transactional` method. Table order per RESEARCH.md Open Question 2's recommendation: truncate every domain table (boards → columns → tasks → subtasks cascade, plus users and activity_log) for a genuinely clean baseline.

**Kafka-side truncate** — RESEARCH.md Pattern 5 (verbatim code included there) is the primary source since no `AdminClient.create()` call exists anywhere in this repo yet:
```java
@Autowired private KafkaAdmin kafkaAdmin;

public void truncateActivityTopics() {
    try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
        for (String topic : List.of(KafkaTopics.ACTIVITY, KafkaTopics.ACTIVITY_DLT)) {
            var tp = new TopicPartition(topic, 0);
            long endOffset = admin.listOffsets(Map.of(tp, OffsetSpec.latest()))
                    .partitionResult(tp).get().offset();
            admin.deleteRecords(Map.of(tp, RecordsToDelete.beforeOffset(endOffset))).all().get();
        }
    }
}
```
`KafkaTopics.ACTIVITY` / `KafkaTopics.ACTIVITY_DLT` constants already exist verbatim at `src/main/java/com/vrudenko/kanban_board/constant/KafkaTopics.java` lines 5-6; both topics are declared single-partition in `KafkaConsumerConfig.java` lines 48-62 (`TopicBuilder...partitions(1)`), which is why `TopicPartition(topic, 0)` is always correct here.

**Error handling** — `@Transactional` wraps the Postgres side the same way `ActivityLogService.findAllByBoardId()` does; the Kafka side's checked `ExecutionException` from `.get()` calls should propagate (no catch), consistent with this codebase's convention of letting `GlobalExceptionHandler` be the single point of exception-to-response mapping — though this is a service method with no direct HTTP boundary of its own beyond what `ResetController` already handles.

---

### `src/main/java/com/vrudenko/kanban_board/constant/ApiPaths.java` (config — extend in place)

**Analog:** the file itself. Add one constant following the exact existing convention (lines 27-33, e.g. `USERS`/`ME`/`THEME`, `SIGNIN`/`SIGNUP`/`LOGOUT`):
```java
public static final String RESET = "/admin/reset"; // exact path left to planner; matches
                                                     // RESEARCH.md's illustrative "/api/admin/reset"
```

## Shared Patterns

### Field injection via `@Autowired`
**Source:** every existing controller/service (e.g. `UserController.java` line 37, `ActivityLogService.java` lines 17-21)
**Apply to:** `ResetController` and `ResetService` — no constructor injection anywhere in this codebase; do not introduce it here.

### `ApiPaths` centralization
**Source:** `src/main/java/com/vrudenko/kanban_board/constant/ApiPaths.java`
**Apply to:** `ResetController`'s route — no inline string literals for the path.

### `GlobalExceptionHandler` as the single error-to-response mapping point
**Source:** `src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java`
**Apply to:** `ResetController`/`ResetService` — no local try/catch-and-format; let uncaught exceptions propagate, matching every other controller.

### `x-logging: &default-logging` anchor
**Source:** `docker-compose.prod.yml` lines 22-35
**Apply to:** both new `app-nonprod` and `redpanda-nonprod` service blocks — every service in this file carries this anchor today; a new service silently omitting it is the actual acceptance-check failure mode the anchor's own comment names.

### Environment-variable placeholder injection (Caddy)
**Source:** `Caddyfile` line 3 comment, `docker-compose.prod.yml` line 55
**Apply to:** `APP_DOMAIN_NONPROD` — same `{$VAR}` Caddyfile syntax, same `caddy.environment` wiring, no code change needed for the CORS side (`CorsConfig.java` line 29's `@Value("${app.cors.allowed-origins:...}")` already externalizes the origin list — D-06 needs only a deployment-time property/env value, zero Java change).

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `@Profile("nonprod")` annotation usage (inside `ResetController.java`) | controller (cross-cutting concern) | n/a | RESEARCH.md confirms zero existing `@Profile` usage anywhere in `src/main/java` — the only existing profile mechanism is `spring.profiles.active=test` selecting `application-test.properties` (property-file resolution, not bean-registration gating). Use RESEARCH.md's Pattern 4 worked example (Spring's own documented semantics) as the primary source instead of an in-repo analog. |
| `AdminClient.create(...)` literal call site | utility (embedded in `ResetService.java`) | event-driven | `KafkaConsumerConfig.java` demonstrates the adjacent `KafkaAdmin`-based bean-provisioning idiom but contains no literal `AdminClient.create()` call today. Use RESEARCH.md's Pattern 5 (cited against spring-kafka's own documented "Access AdminClient Directly" idiom) as the primary source. |

## Metadata

**Analog search scope:** `src/main/java/com/vrudenko/kanban_board/controller/`, `.../service/`, `.../config/`, `.../constant/`, `.../handler/`; repo root (`docker-compose.prod.yml`, `Caddyfile`, `.env.prod`/`.env.prod.example`)
**Files scanned:** 7 controllers, 7 services, `KafkaConsumerConfig.java`, `ApiPaths.java`, `CorsConfig.java`, `docker-compose.prod.yml`, `Caddyfile` (`.env.prod`/`.env.prod.example` denied to Read tool — secret-shaped file sandbox restriction; structure inferred from compose file's `${VAR}` references instead)
**Pattern extraction date:** 2026-08-18
