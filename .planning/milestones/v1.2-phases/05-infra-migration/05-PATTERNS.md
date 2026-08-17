# Phase 5: Infra Migration - Pattern Map

**Mapped:** 2026-08-04
**Files analyzed:** 9 (new + modified)
**Analogs found:** 7 / 9

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `.github/workflows/deploy.yml` (`deploy-to-oracle` job, rewrite of `deploy-to-ec2`) | CI/CD workflow job | event-driven (push-triggered, SSH deploy) | same file, `deploy-to-ec2` job (lines 85-122) | exact (same file, direct rewrite target) |
| `.github/workflows/deploy.yml` (new `ddl-verify` job, INFRA-06) | CI/CD workflow job | batch (psql script execution) | same file, `run-tests`/`cleanup-old-images` jobs (lines 22-43, 124-144) | role-match |
| `.github/workflows/deploy.yml` (`cleanup-old-images`/`cleanup-unused-image`, re-enable + fix) | CI/CD workflow job | batch (Docker Hub tag pruning) | same file, unchanged jobs (lines 124-164) | exact (already exists, just re-enable + fix truncated curl) |
| `docker-compose.prod.yml` (new, standalone) | config (deploy manifest) | event-driven / process-supervision | `docker-compose.yml` (existing, full file) | role-match (same role, prod vs. dev data flow differs — no local postgres, adds caddy) |
| `Caddyfile` (new) | config (reverse-proxy/TLS) | request-response | none in repo | no analog — use RESEARCH.md Code Examples |
| `src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java` (add Actuator `permitAll` matcher) | middleware/config | request-response | same file, existing `authorizeHttpRequests` block (lines 59-70) | exact (same file, additive change) |
| `src/main/resources/application.properties` (Actuator exposure + Neon datasource params) | config | request-response / CRUD (datasource) | same file, existing `=== datasource ===` and env-var-driven blocks (lines 12-20, 54-61) | exact (same file, additive change, matches established `${VAR:default}` convention) |
| `build.gradle` (add `spring-boot-starter-actuator` dependency) | config | N/A | same file, `dependencies {}` block (lines 47-148), specifically the plain `implementation '...'` lines (e.g. line 63, security starter) | exact (same file, one-line addition, matches existing dependency-declaration convention) |
| `docs/` Mermaid infra diagram(s) (new) | documentation | N/A | none in repo (no existing Mermaid diagrams found) | no analog — net-new documentation deliverable |

## Pattern Assignments

### `.github/workflows/deploy.yml` — `deploy-to-oracle` job (rewrite of `deploy-to-ec2`)

**Analog:** same file, `deploy-to-ec2` job, lines 85-122 (the exact job being replaced) + RESEARCH.md's `appleboy/ssh-action`/`scp-action` skeleton (Code Examples section).

**What to keep from the existing file's conventions** (imports/structure pattern, lines 1-20):
```yaml
name: CI/CD with Docker

on:
  push:
    branches:
      - master

env:
  DOCKERHUB_USER: rudenkovladimir
  DOCKERHUB_REPOSITORY: kanban-board-backend
```
Job dependency wiring convention (`needs:` + `if: success()`), lines 45-48:
```yaml
build-and-push-docker-image:
  runs-on: ubuntu-latest
  needs: [ setup, run-tests ]
  if: success()
```

**What must NOT be copied forward from the old job** (lines 90-122 — the anti-pattern being fixed):
```yaml
# OLD — do not reuse this shape:
- name: Set up SSH
  run: |
    mkdir -p ~/.ssh
    echo "${{ secrets.EC2_SSH_KEY }}" > ~/.ssh/id_rsa
    chmod 600 ~/.ssh/id_rsa
    ssh-keyscan -H ${{ secrets.EC2_HOST }} >> ~/.ssh/known_hosts   # no host-key verification at all
- name: Deploy on EC2
  run: |
    ssh ${{ secrets.EC2_USER }}@${{ secrets.EC2_HOST }} << EOF     # raw heredoc, no StrictHostKeyChecking
       ...
      sudo docker run -d --name myapp -p 80:8080 ...                # bare docker run, no compose, no
                                                                      # restart policy, no healthcheck, no log caps
    EOF
```

**Replacement pattern to use** (RESEARCH.md Code Examples, "appleboy/ssh-action + scp-action skeleton"):
```yaml
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

**Comment convention** (match the existing style used to explain the disabled job, lines 74-84 — replace, don't delete, once rewritten): the old explanatory comment block above `deploy-to-ec2` should be replaced with a short note that this job now targets Oracle, referencing this phase, following the same inline-comment-before-job convention already used in this file.

---

### `.github/workflows/deploy.yml` — new `ddl-verify` job (INFRA-06)

**Analog (structural convention):** `run-tests` job (lines 22-43) for the checkout+run shape, and `cleanup-old-images` (lines 124-144) for a job that shells out to an external tool via `secrets.*`.

**Core pattern to follow** (checkout + run shape, lines 26-40):
```yaml
run-tests:
  runs-on: ubuntu-latest
  needs: setup
  if: success()
  steps:
    - name: Checkout code
      uses: actions/checkout@v3
    - name: Run tests
      run: ./gradlew test
```

**New job shape** (per RESEARCH.md Pitfall C — run each existing idempotent bridge script via `psql` against Neon's **direct**, non-pooled connection string):
```yaml
ddl-verify:
  runs-on: ubuntu-latest
  needs: [ setup, run-tests ]
  if: success()
  steps:
    - name: Checkout code
      uses: actions/checkout@v3
    - name: Apply idempotent DDL bridge scripts against Neon (direct connection)
      run: |
        for f in docs/plans/backend-modernization/02-optimistic-locking-ddl.sql \
                 docs/plans/backend-modernization/03-activity-log-ddl.sql \
                 docs/plans/backend-modernization/04-password-hash-not-null-ddl.sql; do
          psql "${{ secrets.NEON_DIRECT_DATABASE_URL }}" -f "$f"
        done
```
Existing scripts confirmed present at: `docs/plans/backend-modernization/02-optimistic-locking-ddl.sql`, `03-activity-log-ddl.sql`, `04-password-hash-not-null-ddl.sql` — do not introduce Flyway/Liquibase (RESEARCH.md Pitfall C explicit warning).

`deploy-to-oracle`'s `needs:` must include `ddl-verify` (see pattern above) so DDL correctness gates deploy.

---

### `.github/workflows/deploy.yml` — `cleanup-old-images` / `cleanup-unused-image` (re-enable + fix)

**Analog:** same file, unchanged (lines 124-164). These jobs already exist and already correctly key off `deploy-to-ec2`/`deploy-to-oracle`'s `success()`/`failure()` via `needs:`.

**Action required:** rename `needs: [ deploy-to-ec2, ... ]` → `needs: [ deploy-to-oracle, ... ]` in both jobs (lines 126, 149) so `if: success()`/`if: failure()` semantics resume firing off the rewritten job instead of the permanently-`if: false` old one.

**Known defect to fix while touching this job** — truncated `curl -X DELETE` in `cleanup-unused-image` (lines 162-163, file cuts off mid-command):
```bash
curl -s -X DELETE \
  -H "Authorization: Bearer $TOKEN" \
  # <-- truncated here in the current file; must be completed with the manifest-delete URL,
  # mirroring cleanup-old-images' complete DELETE call (lines 141-142) as the reference shape:
  # "https://hub.docker.com/v2/repositories/$DOCKERHUB_USER/tags/$TAG/"
```
Use `cleanup-old-images`'s complete, working `curl -s -X DELETE -u "$DOCKERHUB_USER:${{ secrets.DOCKERHUB_TOKEN }}" "https://hub.docker.com/v2/repositories/.../tags/$TAG/"` shape (lines 141-142) as the reference for completing this line — same DELETE-by-tag idiom, different auth style already in progress (Bearer digest-based rather than tag-based) in the unused-image job, so complete it consistently with its own existing `TOKEN`/`DIGEST` variables rather than copy-pasting `cleanup-old-images`'s auth verbatim.

---

### `docker-compose.prod.yml` (new)

**Analog:** `docker-compose.yml` (existing, full file read above).

**Imports/structure pattern to keep** (env-var-driven service definition, healthcheck idiom, named volumes):
```yaml
# app service pattern (docker-compose.yml lines 62-84) — keep this shape, drop `postgres` service,
# add `caddy`:
app:
  build: .
  depends_on:
    redpanda:
      condition: service_healthy
  environment:
    DB_HOST: ${DB_HOST}          # NEW: was hardcoded "postgres"; now Neon host via secret
    DB_NAME: ${DB_NAME}
    DB_USER: ${DB_USER}
    DB_PASS: ${DB_PASS}
    KAFKA_BOOTSTRAP_SERVERS: redpanda:19092
    SCHEMA_REGISTRY_URL: http://redpanda:8081     # unchanged — internal Docker network only
  ports:
    - "8080:8080"
  healthcheck:                                     # NEW (INFRA-01) — app had none in local dev
    test: [ "CMD-SHELL", "wget --spider -q http://localhost:8080/api/actuator/health || exit 1" ]
    interval: 10s
    timeout: 5s
    retries: 5
    start_period: 30s
  restart: unless-stopped                          # NEW (INFRA-01)
```

**Healthcheck idiom to mirror** (Redpanda's existing healthcheck, docker-compose.yml lines 51-60 — same `CMD-SHELL` + interval/timeout/retries/start_period shape, reused for the app service above):
```yaml
healthcheck:
  test: [ "CMD-SHELL", "rpk cluster health | grep -E 'Healthy:.+true' || exit 1" ]
  interval: 5s
  timeout: 5s
  retries: 8
  start_period: 15s
```

**Redpanda block — explicit anti-pattern warning:** do NOT copy `docker-compose.yml` lines 30-43 verbatim (`--mode dev-container`, host-published `9092`/`8081` ports). Production must use explicit `--overprovisioned --smp <N> --memory <N>G` (RESEARCH.md Pattern 2) and drop both `ports:` host-publish lines entirely (INFRA-08).

**Named-volume pattern to keep** (docker-compose.yml lines 44-50, 85-88 — apply to Redpanda's data dir in prod too):
```yaml
volumes:
  - redpanda-data:/var/lib/redpanda/data
# ...
volumes:
  redpanda-data:
```

**What is dropped entirely from the analog:** the `postgres` service (lines 2-17) and its `postgres-data` volume — Neon replaces it; `SPRING_JPA_HIBERNATE_DDL_AUTO: update` (line 76) must also NOT appear in prod (production leaves `ddl-auto` unset per RESEARCH.md's Runtime State Inventory — DDL bridge scripts are the schema-creation mechanism instead).

---

### `Caddyfile` (new)

**No repo analog found.** Use RESEARCH.md's Code Examples verbatim as the starting pattern:
```caddyfile
your-subdomain.duckdns.org {
    reverse_proxy app:8080
}
```
Note from research: the Caddy `/data` volume must be a named volume in `docker-compose.prod.yml` (not ephemeral) to avoid Let's Encrypt rate-limiting on container recreation.

---

### `SecurityConfiguration.java` (modify — add Actuator health matcher)

**Analog:** same file, existing `authorizeHttpRequests` block, lines 59-70.

**Exact insertion point and pattern to follow** (add a sibling `requestMatchers` entry, same method chain, same `.permitAll()` idiom already used for Swagger):
```java
// current (lines 61-70) — new matcher goes alongside the existing ones:
auth.requestMatchers(
                ApiPaths.SIGNIN,
                ApiPaths.SIGNUP,
                SWAGGER_DOCS_PATH,
                String.format("%s/*", SWAGGER_DOCS_PATH),
                String.format("%s/*", ApiPaths.SWAGGER_UI),
                "/actuator/health")           // NEW — resolves to /api/actuator/health at runtime
        .permitAll();

auth.anyRequest().authenticated();
```
Path note (from RESEARCH.md, verified against Spring Boot docs): because `server.servlet.context-path=/api` is already set, the actual resolvable/matched path is `/api/actuator/health` — but Spring Security's `requestMatchers` here is context-path-relative already (compare how `ApiPaths.SIGNIN` is used unprefixed), so the literal string added should match however `ApiPaths`-style constants are declared elsewhere in this codebase (check `ApiPaths.java` for a consistent constant vs. inline string decision before hardcoding `"/actuator/health"`).

---

### `application.properties` (modify — Actuator exposure + Neon datasource additions)

**Analog:** same file, existing sections (full file read above).

**Section-header comment convention to follow** (`# === ... ===` pattern, used throughout, e.g. lines 1, 4, 7, 12, 22, 26, 44, 54, 81, 107):
```properties
# === actuator ===
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=never
```

**Env-var-driven datasource convention to extend, not replace** (lines 12-20 — this is the established pattern the Neon pooled connection string params must follow):
```properties
spring.datasource.url=jdbc:postgresql://${DB_HOST}:${DB_PORT:5432}/${DB_NAME}
spring.datasource.username=${DB_USER}
spring.datasource.password=${DB_PASS}
```
Per RESEARCH.md Pitfall A, the pooled connection needs `prepareThreshold=0` — following this file's existing `${VAR:default}` idiom, the JDBC URL query string (`sslmode=require&channel_binding=require&prepareThreshold=0`) should be appended either directly in the URL property or via a documented inline comment explaining why (matching this file's own established practice of a multi-line comment above non-obvious property blocks, e.g. lines 13-17, 29-41).

---

### `build.gradle` (modify — add Actuator dependency)

**Analog:** same file, `dependencies {}` block — plain `implementation '...'` lines already present for other Spring Boot starters, e.g. line 63:
```gradle
// https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-security
implementation 'org.springframework.boot:spring-boot-starter-security'
```
**Pattern to follow for the new line:**
```gradle
implementation 'org.springframework.boot:spring-boot-starter-actuator'
```
No version pin needed — matches this file's existing convention of leaving Spring Boot BOM-managed starters unversioned (see comment context around lines 47-71 for the established rationale style, e.g. the `spring-session-jdbc` comment block explaining why a dependency is present).

---

## Shared Patterns

### Env-var-driven config (`${VAR:default}` placeholders)
**Source:** `docker-compose.yml` (`app` service, lines 69-76) and `application.properties` (lines 18, 55, 61, 96)
**Apply to:** `docker-compose.prod.yml`'s `app`/`caddy` services and any new `application.properties` additions — Neon/Redpanda-prod endpoints must be injected via env vars, never hardcoded, exactly matching the existing `KAFKA_BOOTSTRAP_SERVERS`/`DB_HOST`/`DB_PORT` precedent.

### GitHub Actions secrets-injection shape
**Source:** `.github/workflows/deploy.yml`, existing `secrets.DOCKERHUB_TOKEN`/`secrets.EC2_*`/`secrets.DB_*` usage throughout
**Apply to:** All new jobs (`deploy-to-oracle`, `ddl-verify`) — reuse the identical `${{ secrets.NAME }}` interpolation style, just with new secret names (`ORACLE_*`, `NEON_*`) per RESEARCH.md's "Exact New GitHub Secrets Needed" table. Do not introduce a different secrets-handling mechanism (e.g. `.env` file checked in, or a different action's secret-passing convention).

### Job dependency/gating convention (`needs:` + `if: success()`/`if: failure()`)
**Source:** `.github/workflows/deploy.yml`, all existing jobs (lines 24-25, 47-48, 86-88, 126-127, 149-150)
**Apply to:** `ddl-verify` (needs `run-tests`), `deploy-to-oracle` (needs `build-and-push-docker-image` + `ddl-verify`), `cleanup-old-images`/`cleanup-unused-image` (needs updating to point at `deploy-to-oracle` instead of `deploy-to-ec2`).

### Docker healthcheck idiom (`CMD-SHELL` + interval/timeout/retries/start_period)
**Source:** `docker-compose.yml`, Redpanda service (lines 51-60)
**Apply to:** New `app` service healthcheck in `docker-compose.prod.yml` (INFRA-01) — same field shape, different check command (`wget --spider` against `/api/actuator/health` instead of `rpk cluster health`).

### Section-header comment convention (`# === name ===`)
**Source:** `application.properties`, used consistently throughout
**Apply to:** Any new property block added to this file (e.g. `# === actuator ===`).

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `Caddyfile` | config (reverse-proxy/TLS) | request-response | No reverse-proxy config exists anywhere in this repo — use RESEARCH.md's Code Examples section verbatim as the base pattern |
| `docs/` Mermaid C4-style infra diagram(s) | documentation | N/A | No existing Mermaid or architecture diagrams found in `docs/` — net-new deliverable (folded todo), no in-repo precedent to follow; use RESEARCH.md's own "System Architecture Diagram" ASCII sketch (lines under "Architecture Patterns") as the content basis, translated to Mermaid syntax |

## Metadata

**Analog search scope:** repo root (`.github/workflows/`, `docker-compose.yml`, `Dockerfile`, `build.gradle`, `src/main/java/com/vrudenko/kanban_board/security/`, `src/main/resources/application.properties`, `docs/plans/backend-modernization/*.sql`, `docs/` for existing diagrams)
**Files scanned:** 6 read in full (`deploy.yml`, `docker-compose.yml`, `SecurityConfiguration.java`, `application.properties`, `build.gradle`), 1 glob (`docs/plans/backend-modernization/*.sql` — 3 matches, referenced not re-read in full)
**Pattern extraction date:** 2026-08-04
</content>
