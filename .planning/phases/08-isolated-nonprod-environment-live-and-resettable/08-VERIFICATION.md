---
phase: 08-isolated-nonprod-environment-live-and-resettable
verified: 2026-08-18T15:20:00Z
status: passed
score: 5/5 must-haves verified
behavior_unverified: 0
overrides_applied: 0
---

# Phase 8: Isolated Nonprod Environment, Live and Resettable Verification Report

**Phase Goal:** A second deployment of this app is live over real HTTPS at its own stable hostname,
provably isolated from production at every layer that matters (database, Kafka broker,
schema-registry compatibility history, container/network/volume identity, secrets), sized by live
measurement rather than arithmetic, and returnable to a known-clean baseline on demand.

**Verified:** 2026-08-18
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (ROADMAP.md Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Operator reaches nonprod over real HTTPS at its own stable hostname, on a certificate issued for that exact hostname (not a wildcard) | VERIFIED | `Caddyfile` second site block (`{$APP_DOMAIN_NONPROD}` → `reverse_proxy app-nonprod:8080`), no wildcard pattern (`grep -c '[*]\.duckdns\.org' Caddyfile` gate). `docs/INFRA_RUNBOOK.md` "Nonprod bring-up" records `openssl x509` output: issuer `O=Let's Encrypt, CN=YE2`, SAN `DNS:kanban-board-rud-vlad-473-nonprod.duckdns.org` only, no wildcard. `curl` → `200 {"status":"UP"}` with no `-k`. |
| 2 | Data written through nonprod lands only in nonprod's Neon branch and its own Redpanda broker/schema registry; production's rows/topics/compatibility history demonstrably untouched; nonprod's credentials live structurally separate from `.env.prod` | VERIFIED | Runbook records a real signup+board write: nonprod `users`/`boards` `0/0 → 1/1`; production's branch unchanged `3/2` across the same write, queried via separate `--env-file` substitution on the VM. `kanban.activity` watermark advanced only on nonprod (bracketed reading); production's stayed flat. Registry: 14 subjects on both brokers, independent per-subject version counts (`RecordNameStrategy` keys by class name). `.env.nonprod` (`/opt/deploy/kanban-board-nonprod/`) vs `.env.prod` (`/opt/deploy/kanban-board-backend/`) are separate files/directories with different `DB_HOST` compute endpoints (`ep-wild-mode-b2atsqpx` vs `ep-delicate-bird-b2lni8pr`). |
| 3 | Nonprod and production coexist without production degrading — Redpanda's memory floor established by iterative live restart cycles (not arithmetic); fallback VPS actually provisioned if no safe floor found | VERIFIED | `docker-compose.nonprod.yml`'s `MEASURED BASIS` comment and `docs/INFRA_RUNBOOK.md`'s "Nonprod resource measurement" section record a genuine descent ladder (1G→768M→512M→384M→256M→192M→128M, all healthy+burst-surviving) with the step below the floor (96M) proven to crash-loop (`RestartCount=22`, `ExitCode=139`) and 64M/32M independently reproducing verbatim Seastar `Failed to allocate N bytes → Segmentation fault`. `mem_limit: 300m` vs `--memory 128M` = 172MiB margin (≥150MiB required). Host `free -m available` never below 5.8GiB; production's own caps (`git diff --name-only HEAD -- docker-compose.prod.yml` empty) untouched; production health 200 across ~15 restart cycles. D-07 decision (`stay-colocated`) was made by the developer at a blocking `checkpoint:decision`, not the agent — recorded with full supporting figures. No fallback needed since a safe floor was found; nothing left as merely "documented." |
| 4 | A single curl clears both nonprod's Postgres and activity-log/Kafka state to a known-clean baseline; the same mechanism is unavailable against production | VERIFIED | `ResetController`/`ResetService`/`ResetTruncateService`/`NonprodResetSecurityConfiguration`, all `@Profile("nonprod")`, exist in source and are covered by passing tests (`ResetEndpointProfileGatingTest` re-run live during this verification: 2/2 pass, 0 failures — confirms zero reset beans register outside the `nonprod` profile). Runbook records a live `POST /api/admin/reset` → `204`, all 8 tables 0 afterward, `flyway_schema_history` unchanged at 7, both Kafka topics' log-start offset == high-watermark. Wrong-token and absent-header calls both return byte-identical `403 ACCESS_DENIED` bodies. The same path against production returns `401 UNAUTHENTICATED` (not 204, not even 403) because no reset bean exists in that context — production's pre-existing catch-all security chain answers instead. Idempotent (repeated 204s), consumer survives (post-reset write reappears in the activity feed). |
| 5 | A credentialed cross-origin request from the expected nonprod frontend origin succeeds against nonprod, with zero application code changed to allow it | VERIFIED | Runbook records a real preflight (`Origin: http://localhost:5173`) → `200` with `Access-Control-Allow-Origin: http://localhost:5173` + `Access-Control-Allow-Credentials: true`; the identical preflight with `Origin: https://evil.example` → `403` with no `Access-Control-Allow-Origin` header at all (exact-match allow-list, not prefix/suffix). Binding is purely `APP_CORS_ALLOWED_ORIGINS` env var → pre-existing `app.cors.allowed-origins` `@Value` placeholder in `CorsConfig.java`; `08-01-PLAN.md`'s Task 2 verify gate (`git diff --name-only HEAD~1 -- src/main/java` = 0 lines) confirms zero Java changed for this plan. |

**Score:** 5/5 truths verified (0 present-but-behavior-unverified)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `docker-compose.nonprod.yml` | Standalone Compose project, own identity axes, measured Redpanda caps | VERIFIED | `name: kanban-board-nonprod`; both services `profiles: ["nonprod"]`; container names `kanban-nonprod-app`/`kanban-nonprod-redpanda`; no `ports:` key; `--memory 128M`/`mem_limit: 300m` with `MEASURED BASIS` comment citing the runbook log. |
| `docker-compose.prod.yml` | Additive-only: `kanban-edge` network, `caddy.networks`, `caddy.environment.APP_DOMAIN_NONPROD` | VERIFIED | Reviewed file directly — three additive regions only; `app`/`redpanda` blocks, `name:`, `volumes:` untouched. |
| `Caddyfile` | Second exact-hostname site block | VERIFIED | `{$APP_DOMAIN_NONPROD} { reverse_proxy app-nonprod:8080 }`; no wildcard. |
| `.env.nonprod.example` | Committed placeholder shape, every key the compose file dereferences | VERIFIED | Contains `IMAGE_TAG`, `APP_DOMAIN_NONPROD`, `DB_*`, `APP_CORS_ALLOWED_ORIGINS`, `APP_RESET_TOKEN`, `SPRING_PROFILES_ACTIVE`; no real secret values (read via `git show HEAD:...` since the live filesystem read tool denies `.env*` paths). |
| `.gitignore` | Re-includes the example, keeps `.env.nonprod` ignored | VERIFIED | `!.env.nonprod.example` present alongside the blanket `.env*` deny. |
| `src/main/java/.../controller/ResetController.java` | Profile-gated, constant-time-compared reset endpoint | VERIFIED | `@Profile("nonprod")`, `MessageDigest.isEqual`, `required = false` header, `@PostConstruct` ≥32-char guard, throws `AppAccessDeniedException` on mismatch. No `.equals(` present. |
| `src/main/java/.../service/ResetService.java` | Kafka-topic trim + listener pause/resume orchestration | VERIFIED | `@Profile("nonprod")`, stop→try{trim+truncate}finally{restart} shape exactly as specified; `UnknownTopicOrPartitionException` tolerated as already-empty. |
| `src/main/java/.../service/ResetTruncateService.java` | Transactional native TRUNCATE, `flyway_schema_history` excluded | VERIFIED | `@Transactional` on `truncateAll()`; 8-table TRUNCATE; `flush()`/`clear()` present; zero occurrences of the Flyway history table name (negative gate). |
| `src/main/java/.../security/NonprodResetSecurityConfiguration.java` | Profile-gated, path-scoped, `@Order(1)` filter chain | VERIFIED | `@Profile("nonprod")`, `securityMatcher(ApiPaths.RESET)`, `permitAll`, `SessionCreationPolicy.STATELESS`. `SecurityConfiguration.java` confirmed untouched by this phase. |
| Three new test classes | Real-broker/real-Postgres/real-socket proofs | VERIFIED | All 7 named `ResetServiceE2ETest` cases and 6 `ResetControllerE2ETest` cases match the plan's `<behavior>` spec exactly (method-name grep). `ResetEndpointProfileGatingTest` independently re-run during this verification: 2/2 pass. |
| `docs/INFRA_RUNBOOK.md` | Three new sections with live command evidence | VERIFIED | "Nonprod bring-up — Plan 08-01", "Nonprod reset endpoint — Plan 08-02", "Nonprod resource measurement — Plan 08-03" all present, each carrying verbatim command output, not summary prose. |

### Key Link Verification

| From | To | Via | Status |
|------|----|----|--------|
| `Caddyfile` | `docker-compose.nonprod.yml` | `reverse_proxy app-nonprod:8080` over shared `kanban-edge` network | WIRED |
| `docker-compose.prod.yml` | `docker-compose.nonprod.yml` | shared external `kanban-edge` network, joined only by `caddy` and `app-nonprod` | WIRED — confirmed live: `docker network inspect kanban-edge` lists exactly those two containers |
| `docker-compose.nonprod.yml` | `CorsConfig.java` | `APP_CORS_ALLOWED_ORIGINS` → `app.cors.allowed-origins` | WIRED — proven from real response headers, not inferred |
| `ResetController` | `ResetService` | field-injected `resetService.resetAll()` | WIRED |
| `ResetService` | `KafkaTopics.ACTIVITY`/`ACTIVITY_DLT` | `AdminClient.deleteRecords` | WIRED |
| `NonprodResetSecurityConfiguration` | `ApiPaths.RESET` | `securityMatcher(ApiPaths.RESET)` | WIRED |
| `docker-compose.nonprod.yml` | `ResetController` | `APP_RESET_TOKEN` env var → `app.reset.token` placeholder | WIRED — live 204 confirms the relaxed binding actually resolved |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Reset endpoint is genuinely absent from a non-nonprod context (D-02 standing invariant) | `./gradlew test --tests 'com.vrudenko.kanban_board.security.ResetEndpointProfileGatingTest'` (re-run live during this verification, Docker/Testcontainers available in this environment) | `tests="2" failures="0" errors="0"` (verified from `build/test-results/test/TEST-...BeanRegistration.xml`) | PASS |
| Reset test suite exists and every claimed behavior has a named test method | `grep -n "void should_" ResetServiceE2ETest.java` / `ResetControllerE2ETest.java` | 7 and 6 method names respectively, matching the plan's `<behavior>` spec 1:1 | PASS |
| No debt markers / stub patterns in phase artifacts | grep for `TBD\|FIXME\|XXX\|TODO\|HACK\|PLACEHOLDER` across all new/modified files | Zero hits (one benign match in `Caddyfile`'s prose describing Caddy's own `{$VAR}` "placeholder syntax", not a debt marker) | PASS |
| Constant-time comparison enforced | `grep -Eq '\.equals\('` over `ResetController.java` | No match — only `MessageDigest.isEqual` present | PASS |
| Live-infra claims (HTTPS certs, DB isolation, broker isolation, memory ladder, D-07 checkpoint) | Not independently re-executable from this sandboxed environment (real VM/Neon/DuckDNS/Redpanda) | Evaluated on recorded-evidence quality per the task's own instruction | See "Live-Infrastructure Evidence Assessment" below — evidence is specific, verbatim, and internally consistent, not vague or asserted |

### Live-Infrastructure Evidence Assessment

This phase's claims split into two categories: (a) code/config present in the checked-out repo,
independently verified above by direct inspection and a live local test re-run, and (b) live
infrastructure state (VM, Neon, DuckDNS, Redpanda memory ladder) that cannot be re-executed from
this sandbox. For category (b), the recorded evidence in `docs/INFRA_RUNBOOK.md` was evaluated
against these criteria, and passes all of them:

- **Specific, not vague:** every claim carries verbatim command output (`docker ps`, `docker stats`,
  `openssl x509`, `rpk registry subject list`, `curl -isS`, SQL row counts), not paraphrased summary.
- **Internally consistent:** the container image tags, row counts, and Neon endpoint IDs referenced
  across the three runbook sections agree with each other and with the compose files' committed
  values (e.g. `kanban-nonprod-redpanda`'s adopted 128M/300m pair matches exactly between the
  compose file's `MEASURED BASIS` comment and the runbook's "Adopted floor" section).
  Production's row counts (`3 users / 2 boards`) are cited identically in both the 08-01 and 08-02
  runbook sections as the same, unchanged baseline.
  Production's health/container-id checkpoints recur at every phase boundary and are always 200/unchanged.
- **Honest about anomalies rather than silent:** the 502 blip during Iteration 0's second burst run
  was investigated in-line, attributed to an unrelated concurrent CI/CD redeploy of production's
  `app` container (via `docker inspect RestartCount=0/ExitCode=0/OOMKilled=false`), and a clean
  third run was used as the official record rather than stitching the ambiguous run in. The 96M
  false-positive health check (later found to be a hidden crash-loop) is documented as a
  methodological correction that changed how every subsequent rung was verified, not glossed over.
- **A genuine failing boundary, not an assumed one:** the memory floor claim is backed by three
  independently reproduced crash signatures (96M/64M/32M) with the Seastar allocation-failure →
  SIGSEGV log captured verbatim, satisfying NONPROD-06's explicit prohibition against presenting an
  idle reading or a single successful start as a measured floor.
- **The one-way, real-money decision (D-07) was routed to a human**, not decided or guessed by the
  agent — `08-03-SUMMARY.md`'s own coverage entry for D5 marks `human_judgment: true` and the runbook
  records the developer's selection with the supporting figures, consistent with `08-CONTEXT.md`'s
  explicit exemption from `auto_advance`.

No category-(b) claim reads as merely asserted; each is falsifiable from the evidence given (wrong
row counts, wrong offsets, or a healthy start at a claimed-failing rung would all have been
detectable from what's recorded).

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|--------------|--------|----------|
| NONPROD-01 | 08-01 | Colocated, name-pinned, profile-gated nonprod Compose stack | SATISFIED | `docker-compose.nonprod.yml` identity axes + live isolation audit (Task 2) |
| NONPROD-02 | 08-01 | Isolated Neon branch, structurally separate env file | SATISFIED | Schema-only branch, `DROP SCHEMA`/`CREATE SCHEMA`, live write-isolation proof |
| NONPROD-03 | 08-01 | Second, separate Redpanda broker (not topic-prefixing) | SATISFIED | Own `redpanda-nonprod` service, empty-then-14-subject registry proof, `RecordNameStrategy` independence proof |
| NONPROD-04 | 08-01 | Real HTTPS at own stable hostname, exact-hostname cert | SATISFIED | Second Caddy site block, live `openssl x509` SAN proof, no wildcard |
| NONPROD-05 | 08-01 | CORS configured, zero code change | SATISFIED | `APP_CORS_ALLOWED_ORIGINS` env var, live preflight proof, `git diff` gate on `src/main/java` |
| NONPROD-06 | 08-03 | Live-measured memory floor, exercised fallback if needed | SATISFIED | Iteration ladder with failing step proven, D-07 human checkpoint resolved `stay-colocated` on measured evidence |
| RESET-01 | 08-02 | curl-driven two-store reset mechanism | SATISFIED | `ResetController`/`ResetService`/`ResetTruncateService`, profile+token defense in depth, live curl contract proof, production-absence proof |

No orphaned requirements: `.planning/REQUIREMENTS.md`'s traceability table maps exactly these seven
IDs to Phase 8, and all seven appear in exactly one plan's `requirements`/`requirements-completed`
frontmatter across 08-01/08-02/08-03, with no gaps and no duplicates.

### Anti-Patterns Found

None. No `TBD`/`FIXME`/`XXX`/`TODO`/`HACK`/`PLACEHOLDER` debt markers, no empty implementations, no
hardcoded-empty stub patterns, and no variable-time secret comparison in any file this phase created
or modified.

### Human Verification Required

None. Every must-have truth resolved to VERIFIED against either direct repo inspection, a live
local test re-run, or evidence meeting the phase's own live-infrastructure evidence bar (see above).

### Gaps Summary

No gaps. All three plans' commits (`e229ed2`, `8a5e5c2`, `65e3370`, `818c14a`, `c83d36e`, `6e492b9`,
`123e3b7`, `6c82647`) exist in git history; all claimed artifacts exist in the checked-out tree with
substantive, wired implementations; all seven Phase 8 requirement IDs are satisfied with specific,
falsifiable, internally-consistent evidence; the one human-decision gate (D-07) was correctly routed
to and resolved by the developer rather than the agent.

---

*Verified: 2026-08-18*
*Verifier: Claude (gsd-verifier)*
