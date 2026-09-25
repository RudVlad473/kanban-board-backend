# Observability stack resource measurement — Plan 12-05 (2026-09-08)

The seven `mem_limit` ceilings plan 12-01 through 12-03 shipped as labelled PROVISIONAL
Iteration-0 baselines (`node-exporter`, `prometheus`, `grafana`, `loki`, `promtail`, `cadvisor`,
`postgres-exporter`) are replaced here with measured floors, using the same restart-ladder method
this repository has already applied for production Redpanda (05-04), nonprod Redpanda (08-03) and
self-hosted Postgres (11-03). This section records the full evidence trail: the workload, the
rung-by-rung ladder per service, each adopted floor and its failing rung, the day-one measurement
caveat unique to this plan (Prometheus and Loki grow over the retention window), and host
coexistence with all thirteen containers running.

## Workload used to measure

One workload cycle, fired in order, for every service measured: (1) the repository's established
54-request burst through the public HTTPS API (6 columns + 24 tasks + 24 subtasks after
signup+board) against BOTH environments, (2) a nonprod admin reset
(`POST /api/admin/reset?fullReset=true`), (3) all three Grafana dashboards loaded at once at the
WIDEST time range they offer over the data that exists, (4) a wide-range Loki query across all
containers (no label filter), (5) a post-workload settle of at least 20 seconds before the final
reading. Steps 3 and 4 are the two additions this phase's measurement needed beyond the previous
three ladders — TSDB compaction and a wide dashboard render are Prometheus's and Grafana's real
peak paths, and a broad chunk scan is Loki's; an idle or pure-burst reading never touches any of
the three. Descent for the four stateless scrapers (`node-exporter`, `cadvisor`,
`postgres-exporter`, `promtail`) was run together per rung; the three stateful services
(`prometheus`, `loki`, `grafana`) were descended one at a time to keep failure attribution
unambiguous.

A rung counted as PASSING only when: the container was running and reached `healthy` within its
`start_period` (where it has a healthcheck); `docker inspect` reported `RestartCount=0` sustained
across the WHOLE workload cycle, not a startup snapshot — this repository has a recorded incident
(plan 08-03) of a rung that passed a point-in-time check and was later found crash-looping; the
service's own function still worked at that rung (Prometheus targets `up`, Loki wide query
returning lines, Grafana dashboards rendering / data present, scrapers' own Prometheus target
staying `up`); and both public health endpoints returned 200 after the workload.

## Iteration ladder

Each service descended from its prior PROVISIONAL Iteration-0 ceiling by roughly halving, stopping
one rung after the first observed failure — a `dmesg` OOM-kill in every case (never a soft
resource-exhaustion warning), confirming each floor is a real kernel-enforced boundary.

| Service | Rungs (✓ pass / ✗ fail) | Steady/peak RSS at adopted | `RestartCount` at failing rung | Adopted |
|---|---|---|---|---|
| `node-exporter` | 128m✓ → 32m✓ → 16m✗ | ~15MiB | OOM-killed (dmesg) | **32m** |
| `postgres-exporter` | 128m✓ → 32m✓ → 16m✗ | ~12MiB | OOM-killed (dmesg) | **32m** |
| `promtail` | 256m✓ → 64m✓ → 32m✗ | ~54MiB | OOM-killed, ExitCode 137 | **64m** |
| `cadvisor` | 512m✓ → 128m✓ → 64m✓ → 32m✗ | ~48MiB | OOM-killed twice (dmesg) | **64m** |
| `prometheus` | 1g✓ → 512m✓ → 256m✓ → 192m✓ → 128m✓ → 96m✓ → 80m✓ → 64m✗ | ~108MiB | OOM-killed, `RestartCount=3` | **256m** |
| `loki` | 512m✓ → 256m✓ → 128m✓ → 96m✓ → 64m✓ → 48m✗ | ~93MiB | OOM-killed, crash-loop `RestartCount=12`; wide query itself failed at this rung | **256m** |
| `grafana` | 512m✓ (reused Iteration-0 rung) → 384m✓ → 256m✗ | ~292MiB | OOM-killed, `RestartCount=9` | **384m** |

`cadvisor`'s ladder is a genuinely different shape from the other six: its memory scales with the
number of containers it watches and its housekeeping interval, not with this workload, so its
curve was flat under the burst and its floor was found at effectively startup cost rather than
under load — recorded as such rather than reported as an anomaly.

Verbatim failure signal, representative (identical `dmesg` shape at every failing rung across all
seven services): `Memory cgroup out of memory: Killed process ... (<binary>)`. Each was confirmed
individually against its own failing rung — `node_exporter`, `postgres_exporter`, `promtail`,
`cadvisor` (×2, independently reproduced), `prometheus`, `loki`, `grafana` — not inferred from one
service's log and assumed to generalize.

## Adopted floor

Every adopted value states its headroom above measured peak RSS as a number, per this
repository's own "cap with no headroom already broke startup once" lesson (redpanda, 05-04):

| Service | Adopted | Peak RSS | Headroom | Margin |
|---|---|---|---|---|
| `node-exporter` | 32m | ~15MiB | +17MiB | ~113% |
| `postgres-exporter` | 32m | ~12MiB | +20MiB | ~167% |
| `promtail` | 64m | ~54MiB | +10MiB | ~19% |
| `cadvisor` | 64m | ~48MiB | +16MiB | ~33% |
| `prometheus` | 256m | ~108MiB | +148MiB | ~137% (includes growth headroom, see below) |
| `loki` | 256m | ~93MiB | +163MiB | ~175% (includes growth headroom, see below) |
| `grafana` | 384m | ~292MiB | +91MiB | ~31% |

Every adopted rung was independently re-verified from a clean `--force-recreate` through a full
workload + 20-second settle cycle, `RestartCount=0` sustained throughout — specifically because
the rung immediately below it had just proven a shallow single-point check can be too shallow to
trust. A final pass then ran all seven services simultaneously at their adopted values together:
clean, `RestartCount=0` across the board, no cross-service contention observed.

## Step below the floor

Every one of the seven services has a real, disqualifying rung — none reached "practical minimum
without failing":

| Service | Failing rung | Evidence |
|---|---|---|
| `node-exporter` | 16m | `dmesg`: `Memory cgroup out of memory: Killed process ... (node_exporter)` |
| `postgres-exporter` | 16m | `dmesg`: `Memory cgroup out of memory: Killed process ... (postgres_exporter)` |
| `promtail` | 32m | OOM-killed, container `ExitCode=137` |
| `cadvisor` | 32m | OOM-killed twice independently, `dmesg` confirmed both instances |
| `prometheus` | 64m | OOM-killed, `RestartCount=3` |
| `loki` | 48m | OOM-killed and crash-looped, `RestartCount=12`; the wide Loki query itself also failed at this rung — a cap that keeps a container alive while breaking its own function would still be a failing rung, and here it failed both ways at once |
| `grafana` | 256m | OOM-killed, `RestartCount=9` |

## Day-one measurement caveat — Prometheus and Loki

Both were measured against a nearly-empty store on day one, under the 30-day retention window
(D-07) that has not yet filled:

- **Prometheus:** 18,523 series / 56,568 chunks / 128.7MB on-disk TSDB at measurement time. The
  bare passing floor was 80m; of the adopted 256m, ~80m is ladder-justified and the remaining
  ~176m is deliberate GROWTH HEADROOM for the retention window filling, not additional ladder
  evidence.
- **Loki:** 14 in-memory streams / 14 chunks / 12MB on-disk chunk store at measurement time. The
  bare passing floor was 64m; of the adopted 256m, ~64m is ladder-justified and the remaining
  ~192m is deliberate GROWTH HEADROOM.

**Falsifier:** if either container is OOM-killed weeks from now with no configuration change, this
day-one basis is why, and the correct response is a higher cap or a shorter retention window — not
a re-run of the identical ladder against what will, by then, be a materially different store size.
A future reader re-running this measurement should compare against the series/chunk/on-disk
figures recorded above, not re-derive them from scratch.

## Host coexistence

`free -m`, all THIRTEEN containers running (the production Compose project's eleven — `caddy`,
`postgres`, `app`, `redpanda`, `node-exporter`, `prometheus`, `grafana`, `loki`, `promtail`,
`cadvisor`, `postgres-exporter` — plus the nonprod project's two, `app-nonprod` and
`redpanda-nonprod`, confirmed via `docker ps` host-wide rather than `docker compose ps`, which is
project-scoped and would only show eleven), immediately after a workload cycle:

```
               total        used        free      shared  buff/cache   available
Mem:            7945        2834         390          40        5060        5110
Swap:              0           0           0
```

`available: 5110MiB`, well clear of the 1024MiB stop condition used by plans 08-03 and 11-03
alike. The real worst-case host budget as the sum of every cap on this box (`postgres` 256m +
`app` 3g + `redpanda` 2200m + the seven new caps totaling 1088m ≈ ~6.4GiB) still leaves genuine
headroom against the measured 7.8GiB total — but the reading above is the primary evidence, not
this arithmetic. Both public health endpoints returned 200 throughout every rung of every service;
`app`, `postgres`, `redpanda` and `caddy` — none of which this plan touched — all held
`RestartCount=0` the entire session. All six Prometheus scrape targets stayed `up`, the wide Loki
query returned lines, and direct data-presence checks against all three dashboards' key metrics
(`container_memory_rss`, `pg_settings_server_version_num`, `node_load1`) all returned non-empty
results at the adopted values.

**Combined-floor finding:** no host-headroom problem exists at the adopted values — cAdvisor
(named in `12-CONTEXT.md`/RESEARCH as the first thing to drop if headroom were tight) does not
need to be considered for removal.

**Known gap, not fixed by this plan:** a full authenticated Grafana UI dashboard render could not
be completed during this measurement — the VM's deployed `GRAFANA_ADMIN_PASSWORD` does not match
the value currently in `.env.prod` (differing length, 18 vs. 16 characters), which predates this
plan and is out of its scope. Dashboard function at each rung was instead confirmed via direct
data-presence checks against each dashboard's key metrics through the Prometheus/Loki APIs
directly. This is recorded as a finding for a follow-up credential-sync fix, not treated as
resolved.

## Measurement date

2026-09-08.

## Addendum — same-day correction (2026-09-08, phase-close verification)

The burst-ladder measurements above for `cadvisor` and `grafana` used a short synthetic workload
cycle (burst + dashboard load + settle, each a few minutes at most). Real production usage — this
phase's own goal-verification pass, running hours after deployment against all 13 live containers
— found BOTH caps insufficient under sustained real conditions, discovered via `dmesg` on
`netcup-prod`:

- **`cadvisor` (adopted 64m):** SIX separate OOM kills across roughly 40 minutes of real
  operation, each showing anon-rss climbing toward (and past) the cap before being killed —
  a slow creep the short synthetic burst never ran long enough to observe. The "flat under the
  burst, floor found at startup" characterization above held for that test but not for sustained
  real usage.
- **`grafana` (adopted 384m):** TWO separate OOM kills within roughly 30 minutes, the second at
  ~446MiB actual usage (254MiB anon-rss + 192MiB file-rss) — nearly double the burst-ladder's
  measured ~292MiB peak. Real concurrent dashboard/API usage exceeded what one synthetic burst
  captured.

**Corrective action, same day:** `cadvisor` raised to 128m (the already-proven-passing rung one
step above 64m in the original ladder) and `grafana` raised to 768m (double both the original
adopted value and the highest real-world peak observed before the second kill). Both applied live
on `netcup-prod`, force-recreated, and confirmed stable — `RestartCount=0`, all 13 containers
healthy, both public health endpoints 200, `free -m available: 5025MiB` — before being written
back into this file and `docker-compose.prod.yml`. Neither `app`, `postgres`, `redpanda`, nor
`caddy` was touched or disrupted by this correction.

**What this addendum does NOT claim:** these are conservative corrective values, not a
re-run ladder — a genuine restart-ladder descent against a LONGER observation window (real
sustained usage, not a multi-minute burst) is the correct follow-up to find each service's actual
measured floor at this scale, and is recorded as an open item rather than silently treated as
already done. Filed as a follow-up: `.planning/todos/pending/2026-09-08-cadvisor-and-grafana-need-a-longer-observation-window-re-ladder.md`.

**Process note, disclosed rather than glossed over:** during this same verification pass, the
deployed `/opt/deploy/kanban-board-backend/docker-compose.prod.yml` working file on the VM (a
hand-edited copy used to drive live restart-ladder testing, distinct from the git-committed
source of truth CI deploys from) was found with EVERY service's `mem_limit` flattened to a
uniform `32m` — including `postgres`, `prometheus`, `loki` and `promtail`, none of which this
correction was investigating. The running containers were unaffected (each still bound to its
last-good limit from its own last real recreate — confirmed via `docker inspect
--format='{{.HostConfig.Memory}}'` against each one individually), so there was no live incident
for those four services. Root cause could not be conclusively determined — no shell history was
captured on the VM for the session that made the edit. The file was restored to the correct
values for all eleven production services before any further recreate was attempted.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
