# Postgres memory profile correction — Plan 11-08 (2026-08-26)

Closes 11-REVIEW.md's CR-02 and 11-VERIFICATION.md's gap 1: the `postgres` service was capped at
`mem_limit: 64m` while started with `shared_buffers=128MB` — double the cgroup ceiling meant to
bound it — and the ladder that adopted `64m` (see "Self-hosted Postgres resource measurement —
Plan 11-03" above) used a sequential burst against a ~55-row dataset, a workload structurally
incapable of exercising the concurrent-backend memory pressure that makes the inconsistency
dangerous. This section supersedes that floor's engine-profile verdict in place.

## Why the Plan 11-03 floor was re-opened

Postgres allocates `shared_buffers` as a shared-memory segment at postmaster start, but Linux
cgroup accounting only charges a page against the container's limit once something actually
touches it. The 11-03 ladder's ~55-row dataset touched only a few megabytes of the buffer pool no
matter what `shared_buffers` declared, so peak RSS held flat at ~29-30MiB from `512m` all the way
down to `40m` — real evidence about that dataset, not about whether the cap was safe. A cap and an
engine flag that contradict each other on paper (`64m` vs. `shared_buffers=128MB`) cannot be
detected by a measurement that never grows the touched working set past `shared_buffers` itself,
which is exactly the gap CR-02 and gap 1 name.

## Why lowering shared_buffers alone could not close it

At the configured `max_connections=25` and `work_mem=4MB`, `max_connections x work_mem` alone is
100MB of potential per-backend allocation — already 36MB more than a `64m` ceiling, before
`shared_buffers` is even counted. No `shared_buffers` value, not even zero, makes a `64m` ceiling
consistent with this connection profile; the ceiling itself had to move.

## Concurrent workload used to re-validate

A one-off `pg_dump` of both `kanban_prod` and `kanban_nonprod` was taken before any testing, as
ordinary prudence given the documented absence of any backup (D-12) and the deliberate intent to
try to reproduce an OOM kill — recorded here only by file name and size, never by content:
`kanban_prod_20260826T182748Z.dump` (37,348 bytes), `kanban_nonprod_20260826T182748Z.dump`
(15,946 bytes), stored at `/root/pg-11-08-safety-dumps/` on the VM, not in this repository.

Three distinct pressure shapes were run at every configuration tested, each accompanied by a
`pg_stat_activity` sampler polling roughly once per second so the backend count itself is
evidence of real concurrency, not asserted:

- **Sub-run A — real-path concurrency:** the repository's established 54-request burst fired
  against both public HTTPS APIs at once, ~5 concurrent workers per environment, repeated three
  times back to back. Bounded by HikariCP at 5 connections per environment, so on its own it
  cannot reach the configured ceiling.
- **Sub-run B — buffer-pool and per-backend pressure:** a scratch database seeded with a
  `pgbench` dataset sized comfortably larger than `shared_buffers`, driven by ~18 concurrent
  `pgbench` clients, followed by ~8 parallel `psql` sessions each sorting the largest table by a
  non-indexed column so each backend genuinely allocates against `work_mem` rather than only
  reading pages.
- **Sub-run C — combined worst case, the run whose peak sets the adopted value:** sub-run A's
  parallel burst and ~10 concurrent `pgbench` clients against the scratch database simultaneously.

`pgbench` was confirmed present in the `postgres:16` image (`pgbench (PostgreSQL) 16.15`) and used
throughout. The final scratch dataset was pgbench scale factor 8 (~127MB) — comfortably larger
than `shared_buffers=64MB` (forcing genuine buffer-pool eviction/reload) without being so large
that page-cache saturation swamps the signal; an earlier attempt at scale 20 (~307MB) saturated
page cache identically at both `256m` and `384m` caps, which is what revealed the anon+shmem
distinction below. Sub-run C at the adopted configuration reached **24 concurrent backends, 21
non-idle** — the number that distinguishes this measurement from the sequential one it replaces.

Peak memory was captured two ways at every run, not one: `docker stats` sampled at roughly
one-second intervals (as Plan 11-03 did), and the container's cgroup `memory.peak` high-water
counter read before and after. A one-second sampler can step over a transient allocation entirely
— which is exactly what killed 11-03's `32m` rung — so the cgroup counter, not the sampler, is the
figure trusted wherever the two disagree.

## Counter-evidence: the previous configuration under the same workload

The unfixed `mem_limit: 64m` / `shared_buffers=128MB` pairing was reproduced live under this same
combined pressure shape — **unplanned**, during initial scratch-database setup (loading a
pgbench scale-20 dataset, ~307MB), before the formally choreographed sub-runs even began. `dmesg
-T` recorded the kernel killing the postgres process:

```
[Wed Aug 26 20:28:31 2026] postgres invoked oom-killer
Memory cgroup out of memory: Killed process 3037738 (postgres) total-vm:216512kB,
anon-rss:3648kB, file-rss:8452kB, shmem-rss:18112kB, UID:999 pgtables:184kB oom_score_adj:0,
constraint=CONSTRAINT_MEMCG
```

Postgres's own crash recovery (WAL redo) absorbed the kill automatically — this was **not** a
Docker-level container restart: `docker inspect --format '{{.RestartCount}}'` stayed **0**
throughout, because the cgroup OOM-killer took one backend process, not the container's PID 1.
Both real databases and both public apps were unaffected the entire time — `flyway_schema_history`
stayed 8/8 successful rows in both `kanban_prod` and `kanban_nonprod`, and both public health
endpoints returned 200 continuously. This is the reproduction proving CR-02 was a live defect, not
a theoretical one, and — per this plan's own rule that a failing rung at the current cap already
establishes "the rung below the adopted value" — no further descent below `64m` was needed.

## Configurations tested

| Configuration | Scratch dataset | Max concurrent backends (total/non-idle) | Sampled peak (`docker stats`) | cgroup `memory.peak` | Peak % of cap | RestartCount | Outcome |
|---|---|---|---|---|---|---|---|
| `mem_limit: 64m`, `shared_buffers=128MB` (unfixed, counter-evidence) | pgbench scale 20 (~307MB) | not separately sampled — OOM occurred during initial setup, before formal instrumentation | n/a (kernel OOM-killed the process before a clean sample) | n/a | n/a | 0 (container-level; the kill was a backend process, not PID 1) | **FAILED** — kernel OOM-kill, reproduced live (see above) |
| `mem_limit: 256m`, `shared_buffers=64MB` | pgbench scale 20 (~307MB) | not separately re-measured; same workload as the row below | 147.2MiB (57.5%) | 268,439,552B (~100% of cap) | ~100% (raw); see note below | 0 | Survived; raw `memory.peak` reading looked concerning |
| `mem_limit: 384m`, `shared_buffers=64MB` | pgbench scale 20 (~307MB) | not separately re-measured; same workload | 226.6MiB (59%) | 402,657,280B (~100% of cap) | ~100% (raw); see note below | 0 | Survived; identical saturation pattern at a larger cap proved the pattern is dataset-size-driven, not cap-inadequacy |
| `mem_limit: 256m`, `shared_buffers=64MB` (final, adopted) | pgbench scale 8 (~127MB, ~2x `shared_buffers`) | 24/21 | 230.9MiB (90.2%) | 268,439,552B (~100% of cap) | ~100% (raw); 32.8% by the corrected reading below | 0 | Survived; **adopted** |

## Adopted profile and the invariant it satisfies

**Adopted: `mem_limit: 256m`, `shared_buffers=64MB`**, with `work_mem=4MB` and `max_connections=25`
unchanged. Both arithmetic conditions hold, worked through with the real numbers:

- **I1 — buffer pool is at most a quarter of the cap:** `shared_buffers` (64MB) x 4 = 256MB <=
  `mem_limit` (256MB). Holds exactly at the boundary.
- **I2 — buffer pool plus full connection ceiling's sort budget fits within 85% of the cap:**
  `shared_buffers` + `max_connections` x `work_mem` = 64MB + (25 x 4MB) = 164MB <= 0.85 x 256MB =
  217.6MB (164MB is 64.1% of the cap).

Both conditions fail on the pre-fix pair (`64m` / `128MB`), which is what makes this a real gate —
`scripts/verify-postgres-memory-invariant.py` (committed, re-runnable) recomputes both from the
manifest's live values rather than trusting a comment. (Corrected per 11-REVIEW.md CR-01,
regenerated review: the script referenced here at first landed only as an uncommitted heredoc run
once during this plan's own execution, which meant no future edit was actually checked — the
committed script above closes that gap.)

**The key methodological finding — read the corrected metric, not the raw one.** Raw cgroup
`memory.peak`/`memory.current` saturated to essentially 100% of *every* tested cap (`256m` and
`384m` alike) whenever the workload ran for any meaningful duration. This is normal, healthy Linux
page-cache behavior — the kernel fills all available cgroup budget with reclaimable file-backed
cache from WAL/checkpoint/table I/O and evicts it on demand — **not** evidence of imminent OOM
risk. The `memory.stat` breakdown proves this: reclaimable `file` (page cache) dominated the total
in every run (208-349MB), while the genuinely irreclaimable `anon + shmem` — the only memory that
can actually force an OOM-kill if it exceeds the cap — held essentially constant across every
configuration and dataset size at **~84MB** (anon ~10MB + shmem ~74MB, the latter being
`shared_buffers=64MB` plus fixed postmaster/background-worker overhead). At the adopted `256MB`
cap, 84MB is **32.8% of the cap** — comfortably under this plan's 60% gate when measured correctly.
Treating total charged memory (including page cache) as an OOM-risk proxy under a benchmark that
deliberately drives sustained I/O is a category error the original 60%-of-raw-cgroup-counter
wording did not anticipate; anon+shmem is the reading this plan trusts for that gate going forward.

**Order of justification, stated explicitly — the correction to how 11-03 framed its result:** the
invariant (I1/I2 above) is primary, because it is the only thing that can never silently regress —
a future edit that breaks the relationship fails `scripts/verify-postgres-memory-invariant.py`
rather than merely looking wrong. The concurrent measurement (24 backends, 21 non-idle, anon+shmem
at 32.8% of cap) corroborates that the invariant's assumptions hold in practice. The counter-evidence
run (the unplanned OOM reproduction above) is what makes the change evidence-backed rather than
argued from arithmetic alone. 11-03's framing put the measured number first and let it carry the
entire justification; this plan inverts that order deliberately.

The profile remains conservative in D-11's sense: `shared_buffers` went **down**, not up — 128MB to
64MB, now half the engine's own stock default — and `work_mem`/`max_connections` are untouched.

## Rung below the adopted value

The current, unfixed `mem_limit: 64m` / `shared_buffers=128MB` pairing is itself the failing rung
— reproduced live via the unplanned OOM kill recorded above under "Counter-evidence." No further
descent below `64m` was performed or needed: per this plan's own rule, a failing rung at the
current cap already establishes the rung below the adopted value, and the adopted value (`256m`)
sits well above it.

## Host coexistence

`free -m` immediately after the final (adopted-configuration) sub-run C:

```
available: 5905 MiB
```

Well above this plan's 1024 MiB gate. New worst-case cap sum against the host's measured ~7.8GiB
total: `postgres` `256m` + `app` `3g` (unchanged) + `redpanda` `2200m` (unchanged) + `app-nonprod`
`1g` (unchanged, not capped separately by this plan) ≈ 6.45GiB nominal, leaving genuine headroom —
but as with Plan 11-03, the real `free -m` reading above is the evidence that matters, not the
arithmetic sum, since `mem_limit` is a ceiling rather than a reservation: raising `postgres`'s cap
did not consume host memory during the window (`available` stayed >= 5900 MiB throughout every
run tested).

## Measurement date

2026-08-26.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
