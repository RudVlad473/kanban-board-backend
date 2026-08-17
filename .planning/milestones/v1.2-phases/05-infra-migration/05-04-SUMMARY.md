---
phase: 05-infra-migration
plan: 04
subsystem: infra
tags: [docker-compose, redpanda, schema-registry, netcup, neon, caddy, avro, kafka]

# Dependency graph
requires:
  - phase: 04-schema-registry
    provides: "Avro schema registry pipeline (5->14 event types), BACKWARD compatibility, AvroSchemaRegistrar, the 5 named E2E verification test classes this plan re-verifies against production"
  - phase: 05-infra-migration (05-01, 05-02, 05-03)
    provides: "Flyway V1-V7 migrations, production docker-compose.prod.yml/Caddyfile, Netcup VM provisioning and verified 4 vCPU/7.8GiB shape"
provides:
  - "A running production stack on real Netcup infrastructure: HTTPS verified end-to-end, DB persistence proven, Schema Registry cutover verified, Redpanda caps measured and corrected"
  - "docs/INFRA_RUNBOOK.md's full manual-deploy sequence (Tasks 1-3) -- the exact command sequence plan 05-05's automation must reproduce"
  - "A Docker-level cgroup mem_limit backstop on the redpanda service (previously absent, only app had one)"
affects: [05-05-ci-cd-cutover]

# Actuals (#2632) -- chars/4 over the realized diff across all 3 tasks (docker-compose.prod.yml,
# application.properties, docs/INFRA_RUNBOOK.md); pairs with the plan's 55000-token estimate.
actuals:
  tokens: 9900
  tasks: 3
  commits: 4

tech-stack:
  added: []
  patterns:
    - "Docker-level cgroup mem_limit as a backstop above a process's own internal memory accounting, sized with explicit headroom above (not equal to) the internal request -- an exactly-equal cgroup limit leaves no room for cgroup accounting overhead and breaks startup"
    - "One-off container reusing an already-built application image on the target Compose network, invoked via Spring Boot's PropertiesLauncher to run a different main class than the image's declared entrypoint -- avoids SSH tunnels/port publication for admin/verification tasks against internal-only services"
    - "Registry-level compatibility verification via the Schema Registry's own read-only /compatibility dry-run REST endpoint, as a substitute for re-running JUnit test classes whose shared test harness cannot be redirected at an external broker without code changes"

key-files:
  created: []
  modified:
    - docs/INFRA_RUNBOOK.md
    - docker-compose.prod.yml
    - src/main/resources/application.properties

key-decisions:
  - "Task 1 executed directly by the agent over the human's own SSH session (human's explicit mid-session authorization), not hand-typed -- a deliberate, scoped exception to the plan's default human-hands-on-keyboard constraint for that one tracer task"
  - "Task 2 made zero src/main changes: the registry was already effectively repointed to production as a side effect of Task 1's deploy (docker-compose.prod.yml's SCHEMA_REGISTRY_URL env var), confirmed rather than assumed"
  - "Task 2's suite-reach decision: neither of the plan's two offered options (VM-local JUnit run / SSH tunnel) was sufficient alone, because AbstractKafkaContainerTest's shared test harness always provisions its own ephemeral Testcontainers-managed registry with no external-override hook -- building that hook would mean modifying shared test infrastructure and running a JDK/Gradle build environment on the live production VM, a materially larger undertaking than the acceptance criteria's intent. Substituted direct registry-API verification (subject list, compatibility config, live reject/accept compatibility-check pair) plus a local regression run of the five named classes (28/28 green) plus a real public-API mutation through the production pipeline (BOARD_CREATED activity row confirmed) -- documented as a deviation, not silently reinterpreted"
  - "Task 3 measured under a real 54-request burst workload (6 columns + 24 tasks + 24 subtasks via the public API) rather than idle, and left --smp 1 / --memory 2G unchanged -- redpanda used under 18% of its cap and a small fraction of its single-core CPU ceiling, so there was no measured basis to tighten or loosen either value"
  - "Task 3 added a Docker-level mem_limit backstop to the redpanda service (previously absent); setting it numerically equal to --memory 2G broke every restart (Seastar's own probe needs headroom above cgroup accounting overhead) -- fixed at mem_limit: 2200m, confirmed healthy by a live restart"

requirements-completed: [INFRA-01, INFRA-02, INFRA-03, INFRA-04, INFRA-06]

coverage:
  - id: D1
    description: "Full production stack (caddy, app, redpanda) live on the real Netcup VM, HTTPS verified end-to-end from off-VM with a publicly trusted Let's Encrypt certificate, plain HTTP redirecting to HTTPS"
    requirement: "INFRA-04"
    verification:
      - kind: manual_procedural
        ref: "off-VM curl to https://kanban-board-rud-vlad-473.duckdns.org/api/actuator/health -> 200, Let's Encrypt issuer, no trust warning; plain HTTP -> 308 redirect (docs/INFRA_RUNBOOK.md Task 1 section)"
        status: pass
    human_judgment: false
  - id: D2
    description: "A write through the public API persists in Neon and survives an app container restart (real database connectivity, not container-memory state)"
    requirement: "INFRA-02"
    verification:
      - kind: manual_procedural
        ref: "signup + board create + docker compose restart app + re-fetch /api/boards, board still present (docs/INFRA_RUNBOOK.md Task 1 section)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Flyway's migrations applied automatically against the genuinely empty Neon database on first boot, no manual DDL step"
    requirement: "INFRA-06"
    verification:
      - kind: manual_procedural
        ref: "app boot log + flyway_schema_history row count (docs/INFRA_RUNBOOK.md Task 1 section, V1-V7 applied)"
        status: pass
    human_judgment: false
  - id: D4
    description: "The container healthcheck distinguishes JVM-alive from app-actually-serving, backing a meaningful restart policy"
    requirement: "INFRA-01"
    verification:
      - kind: manual_procedural
        ref: "docker compose ps showing app/redpanda (healthy) (docs/INFRA_RUNBOOK.md Task 1 and Task 3 sections)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Schema Registry repointed to the production Redpanda target, no hardcoded registry URL, producer/consumer subject-name-strategy symmetric, and BACKWARD compatibility enforcement re-proven live against the production registry"
    requirement: "INFRA-03"
    verification:
      - kind: other
        ref: "grep -rn schema.registry.url src/main --include=*.java (no matches); curl /config/<subject> -> BACKWARD; curl /compatibility/subjects/<subject>/versions/latest reject/accept pair (docs/INFRA_RUNBOOK.md Task 2 section)"
        status: pass
      - kind: e2e
        ref: "local regression run: SchemaCompatibilityE2ETest, ActivityLogAvroDeadLetterE2ETest, ActivityEventAvroMapperTest, SchemaRegistryOutageE2ETest, HistoricalActivityEventReconstructorTest -- 28/28, 0 failures/0 errors"
        status: pass
      - kind: manual_procedural
        ref: "public API mutation (board create) -> GET /api/boards/{id}/activity returns BOARD_CREATED row (docs/INFRA_RUNBOOK.md Task 2 section)"
        status: pass
    human_judgment: false
  - id: D6
    description: "Redpanda's memory/SMP caps derived from measured figures under real workload against the verified VM shape, with a Docker-level cgroup backstop added and confirmed healthy after restart"
    requirement: "INFRA-04"
    verification:
      - kind: manual_procedural
        ref: "docker stats samples idle + under 54-request burst; docker compose ps app/redpanda (healthy) post-restart; off-VM health check 200 (docs/INFRA_RUNBOOK.md Task 3 section)"
        status: pass
    human_judgment: false

duration: 30min
completed: 2026-08-16
status: complete
---

# Phase 05 Plan 04: TRACER — Prove the Entire Production Stack End-to-End Summary

**Full production stack live on real Netcup infrastructure (HTTPS, Neon persistence, Redpanda registry), Schema Registry cutover independently re-verified against production, and Redpanda's resource caps corrected from real measured usage rather than left as a provisional guess.**

## Performance

- **Duration:** ~30 min for Tasks 2-3 this session (Task 1 was a prior session, fully documented in `docs/INFRA_RUNBOOK.md`'s own Task 1 section and `.planning/phases/05-infra-migration/.continue-here.md`)
- **Completed:** 2026-08-16
- **Tasks:** 3 (1 tracer + 2 auto)
- **Files modified:** 3 (`docker-compose.prod.yml`, `src/main/resources/application.properties`, `docs/INFRA_RUNBOOK.md`)

## Accomplishments

- **Task 1 (prior session, tracer):** Full production stack (`caddy`, `app`, `redpanda`) brought up by hand on the real Netcup VM. Redpanda's 14 Avro subjects registered via a one-off container on the VM's own Compose network. Flyway applied V1-V7 against the genuinely empty Neon database with no manual DDL step. Caddy obtained its Let's Encrypt certificate on the first attempt. HTTPS verified from off-VM (200, trusted certificate, HTTP redirects). A signup + board create through the public API survived an app container restart, proving Neon persistence and Spring Session JDBC persistence both real.
- **Task 2 (this session, auto):** Confirmed the Schema Registry was already effectively repointed to production as a side effect of Task 1's deploy — zero `src/main` changes needed. Independently re-verified the production registry directly (14 subjects, BACKWARD compatibility at the subject level, live reject/accept compatibility-enforcement pair via the registry's own REST API) and exercised the live pipeline end-to-end through a real public-API mutation producing a real `activity_log` row. Ran the five named Phase 4 verification test classes locally as a regression check (28/28 green). Documented, as a deviation, why neither of the plan's two offered "reach the registry" options could literally point the existing JUnit classes at production without a test-harness code change.
- **Task 3 (this session, auto):** Measured real per-container CPU/memory under a 54-request burst workload (not idle) against the live production stack. Confirmed the existing `--smp 1` / `--memory 2G` values already had large measured headroom and left them unchanged, rewriting the comment from a provisional floor to the measured basis. Found and closed a real gap — redpanda had no Docker-level `mem_limit` cgroup backstop at all (unlike the app's existing `mem_limit: 3g`) — and, while adding one, discovered live that setting it numerically equal to `--memory 2G` breaks Redpanda's own startup (cgroup accounting overhead leaves less usable memory than the internal request), fixed with `mem_limit: 2200m` and confirmed healthy by a live restart.

## Task Commits

Each task was committed atomically:

1. **Task 1: Bring up the full production stack and prove one HTTPS request end-to-end** — `988ae82` (docs) — prior session; JVM heap/graceful-shutdown pre-scope addition at `08feddb` (fix)
2. **Task 2: Repoint the Schema Registry to production and re-run Phase 4's verification suite** — `5a0f5b4` (docs)
3. **Task 3: Measure actual resource usage and correct Redpanda's caps** — `85a4cf1` (fix)

_Note: no plan-metadata-only commit was made separately; STATE.md/ROADMAP.md/REQUIREMENTS.md updates land in the final commit below._

## Files Created/Modified

- `docs/INFRA_RUNBOOK.md` — manual-deploy sections for all three tasks: Task 1's full command sequence and deviations, Task 2's registry-verification evidence and suite-reach decision, Task 3's measured figures and the `mem_limit` fix
- `docker-compose.prod.yml` — `redpanda` service gained `mem_limit: 2200m` and its command-block comment was rewritten from a provisional budget-math floor to a measured basis (Task 3); Task 1's pre-scope addition also bound the app's JVM heap sizing and graceful shutdown here
- `src/main/resources/application.properties` — Task 1's pre-scope addition bound JVM heap sizing (no Task 2/3 changes — confirmed the existing `SCHEMA_REGISTRY_URL` env-var resolution needed no edit)

## Decisions Made

See `key-decisions` in frontmatter above — summarized: Task 1 executed directly by the agent per explicit human authorization; Task 2 required no production code change and substituted direct registry-API verification plus a local regression run for the plan's literal (but architecturally infeasible without test-harness changes) "point JUnit at production" instruction; Task 3 left the broker's core resource values unchanged (measurement showed ample headroom) but added a missing cgroup backstop, discovering and fixing a real startup-breaking edge case in the process.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 — Missing Critical] Added a Docker-level cgroup `mem_limit` backstop to the `redpanda` service**
- **Found during:** Task 3
- **Issue:** `redpanda` had no cgroup memory ceiling at all — only its own internal `--memory 2G` (Seastar allocator) accounting, unlike the `app` service's existing `mem_limit: 3g`. 05-RESEARCH.md's Pitfall 1 explicitly recommends cgroup limits on both co-resident containers; a leak or accounting gap inside Redpanda's own self-cap had no second backstop.
- **Fix:** Added `mem_limit: 2200m` to the `redpanda` service.
- **Files modified:** `docker-compose.prod.yml`
- **Verification:** Live restart confirmed `redpanda (healthy)` within the existing 15s `start_period`; `docker stats` shows the new limit actually in effect (`345.5MiB / 2.148GiB`, not the host's full 7.759GiB).
- **Committed in:** `85a4cf1`

**2. [Rule 1 — Bug] Fixed a startup-breaking `mem_limit` value discovered live**
- **Found during:** Task 3, immediately after deploying the fix above
- **Issue:** The first attempt set `mem_limit` numerically equal to `--memory 2G` (`mem_limit: 2g`). Redpanda failed every restart: `Could not initialize seastar: std::runtime_error (insufficient physical memory: needed 2147483648 available 2078277632)` — cgroup accounting overhead left ~66MiB less usable memory than the exactly-2GiB limit implied, and Seastar refused to start smaller rather than silently degrade.
- **Fix:** Changed `mem_limit` to `2200m` (~150MiB of headroom above the internal `--memory 2G` request).
- **Files modified:** `docker-compose.prod.yml`
- **Verification:** Live restart reached `(healthy)`; off-VM HTTPS health check still returned 200; registry's 14 subjects survived the container recreation via the named volume.
- **Committed in:** `85a4cf1` (folded into the same commit as Fix 1 — both are part of the same corrective action, not two separate deploys)

**3. [Documented interpretation, not a Rule 1-3 fix] Task 2's suite-reach method**
- **Found during:** Task 2
- **Issue:** The plan's action text asked to re-run the five named Phase 4 JUnit test classes "against the production registry," offering two reach mechanisms (VM-local run / SSH tunnel). Reading `AbstractKafkaContainerTest` in full showed both options are insufficient alone: the shared harness's `@ServiceConnection`/`@DynamicPropertySource` wiring always provisions and points at its own ephemeral Testcontainers-managed Redpanda per test-class JVM run, with no existing hook to redirect at an external registry regardless of where the JVM executes or what network path is available.
- **Resolution:** Rather than modifying shared test infrastructure (outside this task's declared file scope) and standing up a JDK/Gradle build environment on the live production VM for a one-time verification, substituted three verifications that together prove what the JUnit-against-production requirement was meant to prove: direct registry-API queries (subjects, compatibility config, live reject/accept enforcement pair) from inside the VM; a local regression run of the same five classes against a fresh Testcontainers registry (28/28 green, confirming no code regression); and a real public-API mutation through the live production pipeline (`BOARD_CREATED` activity row confirmed).
- **Files modified:** None (verification-methodology decision, documented in `docs/INFRA_RUNBOOK.md`'s Task 2 section)
- **Committed in:** `5a0f5b4`

---

**Total deviations:** 3 (1 missing-critical auto-fix, 1 bug auto-fix, 1 documented methodology substitution)
**Impact on plan:** The two auto-fixes strengthen the production deployment's resilience (a real defense-in-depth gap closed, a real startup-breaking misconfiguration caught before being left broken). The methodology substitution delivers equivalent — arguably more direct — evidence than the plan's literal instruction without incurring the risk of modifying shared test infrastructure or building a toolchain on the live production box. No scope creep; no production code beyond the two `docker-compose.prod.yml` lines and one comment rewrite.

## Issues Encountered

- `DisplayName`'s validation pattern (`^[a-zA-Z ]*$`, letters and spaces only) rejected the first tracer display-name attempt containing a digit — corrected immediately, not a code issue, just a test-data mistake caught by the DTO's own validation working as designed.

## User Setup Required

None — no external service configuration required. Production credentials in `.env.prod` on the VM were never read or requested during either task, per the plan's own execution constraint.

## Next Phase Readiness

- Plan 05-05 (CI/CD cutover) can now automate exactly the sequence `docs/INFRA_RUNBOOK.md` records across all three tasks — every command, every deviation, and every measured figure is written down.
- Redpanda's resource caps are measurement-verified against the real deploy target and a real workload, not a guess — plan 05-05 does not need to re-derive them.
- The Schema Registry cutover is independently confirmed at the API level and the application-pipeline level; no residual doubt about whether production is really talking to its own registry.
- Two harmless tracer data artifacts remain in production (a board from Task 1, a board plus 6 columns/24 tasks/24 subtasks from Task 3's burst workload, and their creating users) — consistent with Task 1's own precedent of leaving tracer data in place; fine to delete before real users onboard, not blocking.
- `.planning/phases/05-infra-migration/.continue-here.md` is deleted as part of this plan's closeout (one-shot checkpoint artifact, no longer needed now that the plan is fully complete).

---
*Phase: 05-infra-migration*
*Completed: 2026-08-16*

## Self-Check: PASSED

- FOUND: docs/INFRA_RUNBOOK.md
- FOUND: docker-compose.prod.yml
- FOUND commit: 988ae82 (Task 1 close-out)
- FOUND commit: 08feddb (Task 1 pre-scope addition)
- FOUND commit: 5a0f5b4 (Task 2)
- FOUND commit: 85a4cf1 (Task 3)
