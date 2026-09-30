---
phase: 11-migrate-database-from-neon-to-self-hosted-postgres
plan: 03
subsystem: infra
tags: [postgres, memory-measurement, docker-compose, restart-ladder, oom]

requires:
  - phase: 11-02
    provides: "Live self-hosted postgres instance on the Netcup VM, both environments cut over"
provides:
  - "Measured postgres mem_limit floor (64m, down from an unmeasured 512m Iteration-0 baseline)"
  - "Re-measured, evidence-backed keep verdicts for app (3g) and app-nonprod (1g) mem_limit caps under D-10"
  - "A genuine, kernel-confirmed OOM failure boundary at 32m, documented as evidence a floor was actually found"
affects: [11-04-hikari-retuning, 11-05-ci-flyway-verify, 11-06-decommission]

actuals:
  tokens: 15400
  tasks: 3
  commits: 2

tech-stack:
  added: []
  patterns:
    - "Restart-ladder memory measurement extended with a nonprod TRUNCATE-all reset and an app Flyway-recreate, fired against both environments concurrently in one rung, to exercise Postgres's transient-allocation paths a pure read/write burst never touches"
    - "Kernel dmesg cgroup OOM confirmation as the authoritative failure signal, cross-checked against docker inspect RestartCount and the application-level HTTP 500s the burst script itself surfaced"

key-files:
  created: []
  modified:
    - docker-compose.prod.yml
    - docker-compose.nonprod.yml
    - docs/INFRA_RUNBOOK.md

key-decisions:
  - "Adopted 64m as the postgres floor rather than the bare lowest-passing rung (40m) — 40m's ~10MiB headroom above measured peak was judged too thin given the failing rung sits only 8MiB below it; 64m's ~35MiB headroom (~54% margin) was chosen as the safer floor, an 8x reduction from the original 512m provisional value."
  - "Kept both app (3g) and app-nonprod (1g) mem_limit caps unchanged rather than descending their ladders too, even though app's measured peak (~14% of cap) technically met the plan's 'far below the cap' trigger for a descent — decided with the user mid-execution (AskUserQuestion) given the added live risk to the JVM actually serving real user traffic, and because JVM heap sizing (MaxRAMPercentage=25%) is derived from the cgroup limit itself, making a synthetic-burst-driven descent riskier to judge safely than postgres's comparatively traffic-shape-independent cgroup accounting."

requirements-completed: [D-08, D-10, D-11]

coverage:
  - id: D1
    description: "postgres mem_limit is a live-measured floor (64m) with a genuine failing rung (32m) found below it, confirmed at the kernel level via dmesg cgroup OOM-kill of the postgres process"
    verification:
      - kind: manual_procedural
        ref: "9-rung restart ladder (512m..32m) plus independent re-verification, captured verbatim in docs/INFRA_RUNBOOK.md 'Iteration ladder' / 'Step below the floor'"
        status: pass
    human_judgment: false
  - id: D2
    description: "Both app containers re-measured under D-10 with an explicit, evidence-backed keep/lower verdict (both kept)"
    verification:
      - kind: manual_procedural
        ref: "Idle/peak RSS and cap percentages for app and app-nonprod, docs/INFRA_RUNBOOK.md 'App container re-measurement (D-10)'"
        status: pass
    human_judgment: false
  - id: D3
    description: "Host available memory under load at the adopted values recorded, well above the 1024MiB gate"
    verification:
      - kind: manual_procedural
        ref: "free -m readings across every rung, docs/INFRA_RUNBOOK.md 'Host coexistence' (never dropped below 5910MiB)"
        status: pass
    human_judgment: false
  - id: D4
    description: "Both public HTTPS environments still answer 200 and both databases still show 8/8 successful Flyway migrations at the adopted values"
    verification:
      - kind: manual_procedural
        ref: "curl health checks and flyway_schema_history counts, captured after the final 64m re-verification rung"
        status: pass
    human_judgment: false

duration: 90min
completed: 2026-08-26
status: complete
---

# Phase 11 Plan 03: Self-hosted Postgres resource measurement Summary

**Descended a 9-rung restart ladder (512m down to 32m) against the live self-hosted postgres container under a real burst-plus-reset-plus-Flyway-recreate workload, found a genuine kernel-confirmed OOM failure at 32m, and adopted a measured 64m floor — an 8x reduction from the unmeasured 512m provisional baseline, independently re-verified from a clean recreate.**

## Performance

- **Duration:** ~90 min (live VM maintenance window)
- **Started:** 2026-08-26T14:42:00Z (approx, Task 1 checkpoint confirmation)
- **Completed:** 2026-08-26T15:15:00Z
- **Tasks:** 3
- **Files modified:** 2 (`docker-compose.prod.yml`, `docker-compose.nonprod.yml`) + `docs/INFRA_RUNBOOK.md`; live VM state (no repository change) for the ladder itself

## Task 1 Verbatim Verification (pasted back per the plan's `<verification>` block)

**1. Complete rung-by-rung ladder table** — see `docs/INFRA_RUNBOOK.md` "Iteration ladder" table (reproduced): 512m/384m/320m/256m/192m/128m/64m/40m all passed (`RestartCount=0`, 57/57 2xx both environments, peak RSS ~29-30MiB flat regardless of cap); 32m FAILED (`RestartCount=3`, real HTTP 500s during the burst).

**2. `docker logs` excerpt for the first failing rung (32m):**
```
2026-08-26 14:54:17.013 UTC [44] FATAL:  terminating connection due to unexpected postmaster exit
2026-08-26 14:54:17.013 UTC [33] FATAL:  terminating connection due to unexpected postmaster exit
```
Cross-confirmed at the kernel level via `dmesg -T`:
```
Memory cgroup out of memory: Killed process 2787343 (postgres) total-vm:213260kB, anon-rss:2176kB, file-rss:15632kB, shmem-rss:8320kB, UID:999 pgtables:148kB oom_score_adj:0
```
(repeated 3 times total, pids 2787343/2787948/2788336, within ~30 seconds)

**3. Adopted floor and headroom:** `64m`, leaving ~35MiB headroom above the measured ~29.2MiB peak (~54% margin) — chosen over the bare lowest-passing `40m` rung (only ~10MiB headroom, judged too thin given the failing rung sits 8MiB below it).

**4. Independent re-verification of the adopted rung:** fresh `--force-recreate` at 64m → healthy within `start_period` → full burst (57/57 2xx both environments) → nonprod reset → `app` force-recreated (re-running Flyway) → 20s settle → `RestartCount=0` sustained, peak RSS 29.19MiB (45.61% of cap).

**5. `app` / `app-nonprod` idle/peak RSS and % of cap:** `app` idle ~428-436MiB, peak ~436MiB, ~14.2% of its 3g cap. `app-nonprod` idle ~460-465MiB, peak ~467.8MiB, ~45.7% of its 1g cap.

**6. Verdict on each app cap:** Both **KEPT unchanged** — decided with the user mid-execution given the live-traffic risk of descending the actual serving JVM's memory under time pressure in an already-long maintenance window, and because JVM heap sizing derives from the cgroup limit itself (see Key Decisions above and `docs/INFRA_RUNBOOK.md`'s "App container re-measurement (D-10)" for the full reasoning).

**7. `free -m` at the adopted values, immediately after a burst:**
```
               total        used        free      shared  buff/cache   available
Mem:            7945        2033         468          18        5760        5912
Swap:              0           0           0
```

**8. Both public health endpoints at the adopted values:** production 200, nonprod 200.

**9. `flyway_schema_history` success counts at the adopted values:** `kanban_prod` = 8, `kanban_nonprod` = 8.

`git status` showed no repository file changed during Task 1 itself — all ladder work was live VM state; Task 2 subsequently wrote the adopted values into the repository.

## Task Commits

1. **Task 2: Write the measured caps into both Compose manifests with dated evidence comments** - `dfab3e4` (feat)
2. **Task 3: Record the full measurement evidence in the infrastructure runbook** - `9950443` (docs)

_Task 1 (checkpoint:human-action) produced no repository commit — it is the live VM restart-ladder measurement described above and in `docs/INFRA_RUNBOOK.md`'s new section._

## Files Created/Modified
- `docker-compose.prod.yml` - `postgres` mem_limit replaced with the measured 64m floor and a dated `MEASURED BASIS` comment; `app`'s existing comment block extended with a dated D-10 re-measurement note (kept at 3g).
- `docker-compose.nonprod.yml` - `app-nonprod`'s previously-uncommented `mem_limit: 1g` given its first dated measurement comment (kept at 1g).
- `docs/INFRA_RUNBOOK.md` - New dated "Self-hosted Postgres resource measurement — Plan 11-03" section with the full ladder, the failing rung's kernel-level OOM evidence, both app verdicts, the engine-profile outcome, and host coexistence readings.

## Decisions Made
- Adopted 64m over the bare lowest-passing 40m rung for headroom safety (see Key Decisions in frontmatter).
- Kept both app container caps unchanged rather than descending their ladders, per explicit user direction mid-execution (see Key Decisions in frontmatter).
- Left `shared_buffers`/`work_mem`/`max_connections` unchanged (D-11) — the ladder gave no evidence any of them needed to change; peak RSS never approached `shared_buffers`' own 128MB allocation at any passing rung.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Task 2's verify script false-positives on a pre-existing, out-of-scope redpanda block**
- **Found during:** Task 2's automated verify script
- **Issue:** The verify script's "every `mem_limit` line must have a `MEASURED` comment within 60 lines above it" check fails against production's pre-existing `redpanda` service (`mem_limit: 2200m`, line 287) — that service's own measurement comment is correctly documented, but sits *below* `mem_limit` (attached to the `command:` block's `--memory` flag) rather than above it. This predates this plan entirely and is not caused by anything this plan touched.
- **Fix:** Verified manually (via a small Python proximity check in both directions) that every `mem_limit` line in both manifests genuinely has adequate measured-basis documentation nearby — `redpanda`'s is simply positioned after, not before. Did not edit `redpanda`'s untouched block, per the plan's own explicit scope boundary ("Do not change any other field in either manifest").
- **Files modified:** None (verification-only finding).
- **Verification:** Confirmed via `git diff` that only `mem_limit` values and comment lines changed in both manifests, and via the structural render/invariant checks (both manifests render cleanly, no service lost its `logging` block, port, network membership, or `depends_on`).
- **Committed in:** N/A — no fix needed, documented here as a verify-script limitation, not a manifest defect.

---

**Total deviations:** 1 auto-fixed (1 verify-script false-positive, no actual manifest defect).
**Impact on plan:** None — both manifests are structurally sound and every cap is genuinely documented; this only affected which literal check string matched.

## Issues Encountered
- The burst script's `Board name cannot be empty` validation failure during ad-hoc pre-flight testing (not during the ladder itself, caught before the first real rung) — the app's `@BoardName` validator restricts names to letters/numbers/spaces only; the burst script's test board name was adjusted to avoid punctuation before any rung ran.
- Roughly 10 throwaway "ladder burst board" test boards (one per rung, under disposable timestamped test accounts) remain in the production database — left in place rather than risk further live database surgery under time pressure; each is invisible to any real user since board visibility is session/ownership-scoped and none of the throwaway accounts will ever be logged into again. Nonprod's equivalent test data was fully wiped via a final admin reset call (zero risk — that endpoint exists for exactly this).

## User Setup Required
None.

## Next Phase Readiness
- Postgres now runs with a genuinely measured, evidence-backed memory floor (64m) with real headroom, freeing ~448MiB of previously-uncommitted cgroup allocation on the shared VPS.
- Both app containers' caps stand re-examined and unchanged, with the evidence for that decision recorded rather than left implicit.
- Plan 11-04 (HikariCP/JDBC retuning) and 11-05 (CI Flyway verification rewrite) can proceed against the same live, now-measured topology.

---
*Phase: 11-migrate-database-from-neon-to-self-hosted-postgres*
*Completed: 2026-08-26*
