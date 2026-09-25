# Self-hosted Postgres resource measurement — Plan 11-03 (2026-08-26)

The `postgres` service's `mem_limit`, shipped as an unmeasured `512m` Iteration-0 baseline in plan
11-01, is replaced here by a floor found through iterative live restart cycles under a real burst
workload — not arithmetic, not an idle reading, not a single successful start. Both application
containers' caps were also re-examined now that the datasource is a Docker bridge hop instead of a
public-internet round trip.

## Workload used to measure

The repo's established 54-request burst (6 columns + 24 tasks + 24 subtasks, after a signup+board
pair — 57 mutating requests total including signup+signin+board) fired against **both**
environments concurrently in the same rung, so both HikariCP pools are open at once — the
connection high-water mark is what per-backend private memory scales with, and a single-environment
burst would understate it. Two additions beyond the burst plan 08-03 used, specifically chosen
because they exercise transient-allocation paths a pure read/write burst never touches (which is
where a Postgres memory floor is actually set):
- a nonprod admin reset (`POST /api/admin/reset`, TRUNCATEs every table), and
- a full `app` container `--force-recreate`, which re-runs Flyway's V1..V8 migration set against
  the live database at boot.

## Iteration ladder

Ladder descended: `512m` (Iteration 0, reused the just-cutover state) → `384m` → `320m` → `256m` →
`192m` → `128m` → `64m` → `40m` → `32m`. Per rung: `postgres` force-recreated alone, health-within-
`start_period` confirmed, the full burst fired against both environments, the nonprod reset
triggered, `app` force-recreated to re-run Flyway, a 20-second settle, then `RestartCount` and
health re-checked (not a single startup snapshot).

| Rung | Started healthy | Burst result (both envs) | Peak RSS (postgres) | % of cap | RestartCount |
|---|---|---|---|---|---|
| `512m` | yes | 57/57 2xx both | 29.82MiB | 5.82% | 0 |
| `384m` | yes | 57/57 2xx both | 29.71MiB | 7.74% | 0 |
| `320m` | yes | 57/57 2xx both | 29.86MiB | 9.33% | 0 |
| `256m` | yes | 57/57 2xx both | 29.78MiB | 11.63% | 0 |
| `192m` | yes | 57/57 2xx both | 29.83MiB | 15.54% | 0 |
| `128m` | yes | 57/57 2xx both | 29.84MiB | 23.32% | 0 |
| `64m` | yes | 57/57 2xx both | 29.99MiB | 46.86% | 0 |
| `40m` (evidence only, not adopted) | yes | 57/57 2xx both | 29.72MiB | 74.31% | 0 |
| `32m` — **FAILED** | misleadingly yes on the initial health check | 55/57 prod, 55/57 nonprod (real HTTP 500s during the outage window) | n/a | n/a | **3** |
| `64m` (adopted, independently re-verified) | yes, fresh `--force-recreate` | 57/57 2xx both | 29.19MiB | 45.61% | 0 sustained |

Peak RSS held essentially flat (~29-30MiB) at every rung down to `40m`, regardless of the cap —
this app's real traffic shape (a personal/portfolio kanban board, ~55 rows created per burst) never
grows Postgres's touched working set anywhere near `shared_buffers`' own 128MB allocation. The
floor found here is a hard cliff at `32m`, not a gradual RSS increase.

## Adopted floor

**`mem_limit: 64m`**, NOT the bare lowest-passing rung. `40m` passed cleanly (`RestartCount=0`,
57/57 2xx) but left only ~10MiB headroom above its own ~29.7MiB measured peak — thin, given `32m`'s
cliff sits just 8MiB below that. `64m` leaves **~35MiB headroom above the measured ~29.2MiB peak
(~54% margin)**, applying this repository's own "deliberately NOT set equal to the measured
high-water mark" lesson (a cap with zero headroom budgets nothing for cgroup accounting overhead —
already observed to break startup once on this box, for `redpanda-nonprod` at its `96M` rung, plan
08-03).

**Independent re-verification from a clean slate**, run specifically because `32m`'s initial health
check was itself misleading (see below): fresh `docker compose up -d --force-recreate postgres` at
`64m` → healthy within the `start_period` → full burst fired against both environments (57/57 2xx
each) → nonprod reset → `app` force-recreated (re-running Flyway) → 20-second settle →
`RestartCount=0` sustained throughout, peak RSS 29.19MiB (~45.6% of cap), both public health
endpoints `200`, both databases still 8/8 successful Flyway migrations.

## Step below the floor

**`32m`.** This is the genuinely adjacent, disqualifying rung. `docker inspect` reported
`RestartCount=3` after the workload — the postgres process was killed and the whole cluster forced
through crash recovery three times within roughly 30 seconds. Confirmed at the kernel level via
`dmesg -T`:

```
[Wed Aug 26 16:54:06 2026] runc:[2:INIT] invoked oom-killer: gfp_mask=0xcc0(GFP_KERNEL), order=0, oom_score_adj=0
[Wed Aug 26 16:54:06 2026] oom-kill:constraint=CONSTRAINT_MEMCG,...,task=postgres,pid=2787343,uid=999
[Wed Aug 26 16:54:06 2026] Memory cgroup out of memory: Killed process 2787343 (postgres) total-vm:213260kB, anon-rss:2176kB, file-rss:15632kB, shmem-rss:8320kB, UID:999 pgtables:148kB oom_score_adj:0
```

Repeated twice more (pids `2787948`, `2788336`) within the same window — the same underlying
mechanism, not an unrelated fluke. Postgres's own log shows the resulting behavior from inside the
container — the postmaster itself was the process killed, forcing a full crash-recovery restart
each time:

```
2026-08-26 14:54:17.013 UTC [44] FATAL:  terminating connection due to unexpected postmaster exit
2026-08-26 14:54:17.013 UTC [33] FATAL:  terminating connection due to unexpected postmaster exit
```

This is exactly the failure signature this plan's own instructions named as typical for Postgres —
an out-of-memory kill of the postmaster or a backend process, not a Seastar-style allocation
message (that pattern belongs to Redpanda, plan 08-03). It also produced real, user-visible
failures during the live burst: two mutating requests per environment returned HTTP 500 (a column
create) and a cascaded HTTP 400 (a subtask create against a task ID that never got created because
its parent request failed) — not merely a measurement artifact, a genuine partial outage at this
rung.

## App container re-measurement (D-10)

Both application containers were re-measured at the adopted `postgres` floor because their original
caps were sized partly around a remote database's latency assumptions (Neon, a public-internet
round trip) that no longer hold now that the datasource is a Docker bridge hop.

| Container | Cap | Idle RSS | Peak RSS (during burst+recreate) | % of cap | Verdict |
|---|---|---|---|---|---|
| `app` (production) | `3g` | ~428-436MiB | ~436MiB | ~14.2% | **KEPT** |
| `app-nonprod` | `1g` | ~460-465MiB | ~467.8MiB | ~45.7% | **KEPT** |

Neither cap was lowered, by deliberate choice rather than oversight. `app`'s measured peak sits far
below its cap under this synthetic burst — the plan's own instructions frame that as grounds to
consider a descent — but the decision here was to keep both caps and record the evidence instead,
for a reason specific to how these caps work: the JVM's own heap sizing (`MaxRAMPercentage=25%`,
container-aware since JDK 10, no explicit `-Xmx` set) is derived **from** this cgroup limit, so
lowering it would shrink the JVM's usable heap under real, non-synthetic traffic patterns this
measurement's light dataset (~55 rows created per burst) cannot exercise — unlike Postgres's own
cgroup accounting, which held essentially flat regardless of cap and is comparatively
traffic-shape-independent. Descending either app container's cap would also mean repeatedly
shrinking the memory of the JVM actually serving live public traffic, in the same maintenance
window that had already forced the shared database through nine restart cycles. This is a
deliberate, evidence-backed decision recorded per D-10's own standard — "re-measured, unchanged,
here is the evidence" — not an unexamined value.

## Engine profile after measurement (D-11)

**Unchanged.** `shared_buffers=128MB`, `work_mem=4MB`, `max_connections=25` all stand exactly as
plan 11-01 set them. The ladder gave no evidence to change any of them — peak RSS never approached
`shared_buffers`' own 128MB allocation at any passing rung (the highest observed peak across the
entire ladder was 29.99MiB, at the `64m` rung), and the failure mode found (`32m`'s kernel OOM-kill)
is a cgroup ceiling problem, not an engine-tuning problem. An explicitly unchanged value with a
stated reason is not the same as an unexamined one.

**CORRECTED by Plan 11-08 (2026-08-26).** This "unchanged" verdict held only under this section's
own sequential, ~55-row-dataset workload, which never grew Postgres's touched working set anywhere
near `shared_buffers`. A concurrent-backend re-validation found the pairing adopted here
(`mem_limit: 64m` with `shared_buffers=128MB`) internally inconsistent — see "Postgres memory
profile correction — Plan 11-08" below for the corrected profile (`mem_limit: 256m`,
`shared_buffers=64MB`), the invariant now enforced mechanically, and the concurrent-load evidence
this section's workload could not produce.

## Host coexistence

`free -m` `available` was recorded after every rung's burst in this session (10 readings across
Iteration 0 and the eight descended/re-verified rungs) and never dropped below **5910MiB** —
comfortably clear of this plan's own 1024MiB gate at every step, including the adopted floor
(`5912MiB` immediately after the independent `64m` re-verification burst). Even the failing `32m`
rung's own reading stayed at `5942MiB` — the failure was a per-container cgroup ceiling, not host
memory exhaustion; the box as a whole never came close to running out of memory.

```
               total        used        free      shared  buff/cache   available
Mem:            7945        2033         468          18        5760        5912
Swap:              0           0           0
```

Worst-case host budget as the sum of all four caps against the measured 7.8GiB total: `postgres`
`64m` + `app` `3g` + `redpanda` `2200m` + `app-nonprod` `1g` ≈ 6.3GiB of 7.8GiB nominal — but the
real reading above is the evidence that matters, not the arithmetic sum, since every container's
real usage sits far below its own cap.

## Measurement date

2026-08-26.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
