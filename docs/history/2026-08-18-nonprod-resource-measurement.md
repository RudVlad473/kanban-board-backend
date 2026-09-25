# Nonprod resource measurement — Plan 08-03 (2026-08-18)

`redpanda-nonprod`'s `mem_limit: 1200m` / `--memory 1G` pair, shipped provisional in plan 08-01, is
replaced in this plan by a floor established through iterative live restart cycles under a real
workload — not arithmetic, not an idle reading, not a single successful start. This section records
the full measurement: the reference baseline (Iteration 0, this task), the descent ladder, the
adopted floor and the failing step below it, host coexistence under simultaneous burst, and the D-07
decision outcome.

## Workload used to measure

Identical shape to production's own Task 3 measurement (`## Manual deploy — Plan 05-04 Task 3`
above), fired against the live **nonprod** public HTTPS API instead: sign up one user, create one
board, then create 6 columns, 24 tasks (4 per column) and 24 subtasks (1 per task) in rapid
sequential succession through `https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api` — 54
mutating requests after the initial signup+board pair (56 total HTTP calls), each producing a real
Avro-serialized Kafka publish against the nonprod registry and a real consumer-side persist. Not a
synthetic load-test tool — this app's real traffic shape (a personal/portfolio kanban board) is what
the caps need to be correct for.

## Iteration 0 — provisional caps (baseline)

**Reset first.** `POST /api/admin/reset` against nonprod with the token read directly from
`/opt/deploy/kanban-board-nonprod/.env.nonprod` over SSH (never typed into this session or written
to any local file) → `204`, confirming a known-empty baseline before measuring.

**Idle baseline** (both stacks up and quiet >= 60s), `docker stats --no-stream` for all five
containers plus `free -m`:
```
kanban-board-backend-caddy-1     19.93MiB / 7.759GiB   0.25%   0.00%
kanban-board-backend-app-1       406.6MiB / 3GiB       13.24%  0.30%
kanban-board-backend-redpanda-1  526.3MiB / 2.148GiB   23.92%  0.33%
kanban-nonprod-app                405.3MiB / 1GiB       39.58%  0.22%
kanban-nonprod-redpanda           277.7MiB / 1.172GiB    23.14%  0.34%
```
```
               total        used        free      shared  buff/cache   available
Mem:            7945        2067        1976           0        4183        5877
Swap:              0           0           0
```
`kanban-nonprod-redpanda` idle: ~277.7MiB, ~23.7% of its provisional 1172MiB (`1200m`) cgroup cap,
~27.1% of its internal 1024MiB (`1G`) Seastar request.

**Burst — three attempts, one confounded by an unrelated concurrent event.** The burst was run three
times in this session; only the third is the official record. What happened and why, in order:

1. **First run:** completed cleanly (56/56 responses 2xx, activity feed `totalElements: 55`), 9
   `docker stats` samples taken throughout — but this run did **not** interleave a production-health
   poll during the burst window (only before and well after), so it does not satisfy this task's own
   "before, during, and after" gate. Superseded, not used as the official record.
2. **Second run (nonprod reset, then re-run with production health interleaved):** while sampling,
   one poll returned production `502` (`prod health: 502` at `13:53:51Z`), immediately followed by a
   `docker stats` SSH call that itself failed with an `EOF` — both symptomatic of `caddy` briefly
   losing its upstream while `kanban-board-backend-app-1` was mid-restart. **Investigated
   immediately, before continuing:** `docker inspect kanban-board-backend-app-1` showed
   `RestartCount=0 ExitCode=0 OOMKilled=false StartedAt=2026-08-18T13:53:41Z` — a clean Compose
   recreate (image retagged to `9c89613`), not a crash, not an OOM kill, and `docker events` /
   container logs confirmed a normal Spring Boot boot sequence completing seconds later. Three
   immediate follow-up polls all returned production `200`, and `docker ps` showed every container
   `Up`/`(healthy)` within about a minute. **Root cause: an external CI/CD deploy of production's own
   `app` service, coincidentally overlapping this burst window — nothing this plan's burst script
   does touches `docker-compose.prod.yml` or any production container**, and the single-instance
   rolling recreate (no blue-green) is the documented, pre-existing behavior of `deploy.yml`, not a
   finding about nonprod resource contention. Recorded here in full rather than discarded silently,
   per this plan's own transparency prohibition — but folding a redeploy-induced blip into NONPROD-06's
   own conclusion would misattribute an unrelated event, so a third, clean run was taken as the
   official record instead of stitching this one in.
3. **Third run (nonprod reset again, production confirmed stable and `200` beforehand) — the
   official Iteration 0 burst record:** 56/56 responses `2xx`, activity feed `totalElements: 55`
   (1 board-create + 6 column-creates + 24 task-creates + 24 subtask-creates). Production's health
   endpoint was polled immediately before, 8 times spread across the burst, and immediately after —
   `200` every single time, with no repeat of the prior run's anomaly:

| Sample | Time (UTC) | Prod health | `caddy` | `app` (prod) | `redpanda` (prod) | `app-nonprod` | `redpanda-nonprod` |
|---|---|---|---|---|---|---|---|
| pre-burst | 13:57:00 | 200 | — | — | — | — | — |
| 1 | 13:57:06 | 200 | 20.07MiB/0.25%/0.08% | 406.7MiB/13.24%/0.54% | 526.3MiB/23.92%/0.35% | 428.7MiB/41.86%/0.19% | 279.8MiB/23.32%/0.30% |
| 2 | 13:57:17 | 200 | 20.07MiB/0.25%/0.13% | 406.9MiB/13.24%/0.78% | 526.3MiB/23.92%/0.28% | 429.1MiB/41.91%/3.08% | 279.8MiB/23.32%/7.09% |
| 3 | 13:57:33 | 200 | 20.06MiB/0.25%/0.08% | 407.1MiB/13.25%/0.44% | 526.3MiB/23.92%/0.33% | 429.4MiB/41.94%/1.08% | 279.9MiB/23.32%/7.12% |
| 4 | 13:57:44 | 200 | 20.06MiB/0.25%/0.15% | 409.1MiB/13.32%/0.38% | 526.3MiB/23.92%/0.29% | 430.0MiB/41.99%/3.59% | 279.9MiB/23.32%/4.65% |
| 5 | 13:57:59 | 200 | 20.07MiB/0.25%/0.08% | 409.2MiB/13.32%/0.41% | 526.3MiB/23.92%/0.42% | 429.5MiB/41.94%/2.52% | 279.9MiB/23.33%/7.49% |
| 6 | 13:58:10 | 200 | 20.07MiB/0.25%/0.13% | 412.7MiB/13.44%/0.37% | 526.3MiB/23.92%/0.28% | 430.4MiB/42.03%/7.65% | 279.8MiB/23.32%/0.27% |
| 7 | 13:58:27 | 200 | 20.08MiB/0.25%/0.15% | 413.0MiB/13.44%/0.33% | 526.3MiB/23.92%/0.46% | 431.1MiB/42.10%/2.18% | 279.8MiB/23.32%/0.38% |
| 8 | 13:58:43 | 200 | 20.18MiB/0.25%/0.12% | 415.0MiB/13.51%/0.52% | 526.3MiB/23.92%/7.48% | 431.5MiB/42.14%/0.40% | 279.8MiB/23.32%/0.35% |
| post-burst | 13:59:33 | 200 | — | — | — | — | — |

Columns show `MemUsage / MemPerc / CPUPerc` from `docker stats --no-stream`. `free -m` bracketing the
official burst (closest available readings; only an unrelated production redeploy and one nonprod
reset call occurred between them, neither of which is host-memory-relevant beyond the container
recreate already discussed above):
```
# ~13:52:20Z (closest prior reading; before the run 2/run 3 transition, after which the confounding
# redeploy settled)
               total        used        free      shared  buff/cache   available
Mem:            7945        2075        1967           0        4183        5869
Swap:              0           0           0

# 13:59:33Z (immediately after the official run 3 burst)
               total        used        free      shared  buff/cache   available
Mem:            7945        2118        1758           0        4364        5827
Swap:              0           0           0
```
Host `available` stayed comfortably above the 1024 MiB gate throughout (5827-5877MiB across every
reading taken this session).

**Conclusion.** `kanban-nonprod-redpanda`'s peak RSS across the official burst was **279.9MiB**,
which is **~23.3% of its provisional `1200m` cgroup cap** and **~27.3% of its provisional `1G`
internal (`--memory`) request** — RSS did not move at all during the burst (277.7-279.9MiB idle vs.
under load is measurement noise, not growth), while its CPU briefly touched ~7.5% of its single
`--smp 1`-pinned core during subtask-creation bursts. This is a **starting reference point for the
descent, not a floor** — Task 2 below descends `--memory` through a ladder of lower values, restarting
only `redpanda-nonprod` each step, until it finds the step that fails and adopts the one before it.

## Iteration ladder

Ladder descended: `1G` (reused Iteration 0's already-proven result -- identical `--memory`/`mem_limit`
values, no need to re-recreate to reproduce the same outcome), `768M`, `512M`, `384M`, `256M`, `192M`,
`128M`. Per rung (except `1G`, reused): `docker-compose.nonprod.yml` edited, `scp`'d to the VM,
`redpanda-nonprod` force-recreated alone, health-within-`start_period` confirmed, `app-nonprod`
force-recreated so it genuinely reconnects (a bare `up -d` with no config diff left it running against
the old broker instance without this), nonprod reset, the identical 54-request burst re-run through
the public nonprod HTTPS API, `docker stats` sampled 4+ times across the run, and production's health
endpoint polled after every restart cycle.

| Step | `mem_limit` | Started healthy (within 15s) | `app-nonprod` reconnect | Burst result | `redpanda-nonprod` peak RSS | Prod health |
|---|---|---|---|---|---|---|
| `1G` | `1200m` | yes (Iteration 0) | n/a (reused) | 56/56 2xx, `totalElements: 55` | 279.9MiB (~23.3% of cap) | 200 throughout |
| `768M` | `950m` | yes, `Up 26 seconds (healthy)` | clean recreate, healthy in 15s | 56/56 2xx, `totalElements: 55` | 212.6MiB (~22.4% of cap) | 200 throughout |
| `512M` | `700m` | yes, `Up 26 seconds (healthy)` | clean recreate, healthy in 15s | 56/56 2xx, `totalElements: 55` | 149.3MiB (~21.3% of cap) | 200 throughout |
| `384M` | `550m` | yes, `Up 26 seconds (healthy)` | clean recreate, healthy in 15s | 56/56 2xx, `totalElements: 55` | 116.8MiB (~21.2% of cap) | 200 throughout |
| `256M` | `420m` | yes, `Up 26 seconds (healthy)` | clean recreate, healthy in 15s | 56/56 2xx, `totalElements: 55` | 86.4MiB (~20.6% of cap) | 200 throughout |
| `192M` | `350m` | yes, `Up 26 seconds (healthy)` | clean recreate, healthy in 15s | 56/56 2xx, `totalElements: 55` | 70.3MiB (~20.1% of cap) | 200 throughout |
| `128M` (adopted, re-verified) | `300m` | yes, `Up 26 seconds (healthy)`, `RestartCount=0` | clean recreate, `RestartCount=0` sustained | 56/56 2xx, `totalElements: 55` | 57.5MiB (~19.1% of cap) | 200 throughout |
| `96M` (below floor -- see below) | `260m` | **misleadingly** yes on a single startup check | recreate triggered a hidden crash-loop | not exercised -- broker was down | n/a | 200 throughout (nonprod, not prod, was affected) |
| `64M` (exercised for evidence) | `220m` | no -- crash-loop, `Restarting (139)` within 10s | n/a | not exercised | n/a | 200 |
| `32M` (exercised for evidence) | `200m` | no -- crash-loop, `Restarting (139)` within 10s | n/a | not exercised | n/a | 200 |

RSS is essentially flat and low at every successful rung -- this app's real traffic shape (a
personal/portfolio kanban board) never stresses Redpanda's steady-state memory. The floor found here
is set by Seastar's own minimum viable allocation during Kafka log replay / consumer-group recovery
under a real recreate cycle, not by RSS growth under load -- see the finding below.

**A methodological correction made mid-ladder, recorded because it changed how every step was
verified afterward:** the first pass at `128M` used only a single point-in-time `docker ps` check
immediately after `redpanda-nonprod`'s own restart -- the same shallow check `256M` through `192M`
had already passed cleanly. Descending further to `96M`, that same shallow check also reported
`Up 11 seconds (healthy)` -- but a `docker inspect --format 'RestartCount={{.RestartCount}}'` taken
minutes later, prompted by unrelated troubleshooting, showed `RestartCount=22`, `ExitCode=139`
(SIGSEGV), `Status=restarting`: `redpanda-nonprod` was crash-looping the entire time, restarting
into a brief `healthy` window each cycle before dying again once real Kafka log replay / consumer-group
recovery work resumed -- the single-point check had caught one of those healthy windows and reported
a false positive. Once this was found, `128M` was independently re-verified from a fresh
`--force-recreate` with `RestartCount` monitored continuously (not just checked once) through
`app-nonprod`'s own recreate, a full reset-and-burst cycle, and a 20-second post-burst delay --
`RestartCount` stayed `0` throughout. `96M` and `64M`/`32M` (already exercised as fixed rungs) all
share the identical Seastar allocation-failure signature, confirming this is a real, load-dependent
memory boundary and not a fluke.

Verbatim failure output, `64M` (identical pattern independently reproduced at `32M`):
```
ERROR 2026-08-18 14:35:28,260 [shard 0:main] seastar - Failed to allocate 3663393 bytes
Skipping recording crash reason to crash file on shard 0 (the writer has already been consumed by another crash)
Aborting on shard 0, in scheduling group main.
...
Segmentation fault: si_code: 4294967290, ip: 00007f6a8d610898
Segmentation fault: resolved ip: 0x0000000000028898 in /opt/redpanda/lib/libc.so.6[...]
Segmentation fault on shard 0, in scheduling group main.
```
`96M`'s failure was captured as `docker inspect` evidence (`RestartCount=22`, `ExitCode=139`,
`Status=restarting`) rather than a second verbatim log dump of the identical signature already shown
above -- the crash mechanism is the same Seastar allocation failure, reproduced independently at three
adjacent rungs (`96M`, `64M`, `32M`).

## Adopted floor

**`--memory 128M` / `mem_limit 300m`.** Evidence: fresh `--force-recreate` reached `(healthy)` within
the 15s `start_period` with `RestartCount=0`; `app-nonprod` was independently `--force-recreate`d and
reached `(healthy)`, with `redpanda-nonprod`'s `RestartCount` still `0`; the full 54-request burst
through the public nonprod HTTPS API completed 56/56 `2xx` with `totalElements: 55` on the activity
feed; `RestartCount` was checked immediately post-burst (`0`) and again after a 20-second delay (`0`)
to rule out the exact deferred-crash pattern `96M` exhibited. `redpanda-nonprod`'s RSS held steady at
57.3-57.5MiB (~19.1% of the 300m cap) throughout. `mem_limit: 300m` exceeds `--memory 128M` by 172MiB
(the plan's own minimum margin is 150MiB) and carries an explicit `m` suffix; `--memory 128M` carries
an explicit `M` suffix.

## Step below the floor

**`96M`.** This is the genuinely adjacent, disqualifying rung -- not `192M` or a rung further above.
It passed a shallow single-point health check (the same class of check every higher rung had also
passed) but was found crash-looping once monitored continuously: `docker inspect` showed
`RestartCount=22`, `ExitCode=139` (SIGSEGV), `Status=restarting`, recurring every few seconds as the
broker restarted into a brief healthy window and then died again during Kafka log replay /
consumer-group recovery. `64M` and `32M` were additionally exercised and crash-looped immediately and
consistently, with the verbatim Seastar `Failed to allocate N bytes` -> `Segmentation fault` signature
captured above -- confirming `96M`'s failure is the same underlying mechanism, not an unrelated fluke,
and that the boundary is real rather than a single unlucky sample.

## Host coexistence

`free -m` `available` was recorded before and after every iteration's burst in this session (12
readings across Iteration 0 and the seven exercised ladder rungs) and never dropped below 5.8GiB --
comfortably clear of this plan's own 1024MiB gate at every step, including the adopted floor
(`6148MiB` before -> `6084MiB` after the final 128M-re-test burst). Production's health endpoint was
polled continuously throughout every restart cycle and burst in this session (prod's own request
traffic overlapping with each nonprod burst window) and returned `200` on every poll except one
transient `502` documented in Iteration 0 above, which was investigated and attributed to an unrelated
concurrent CI/CD redeploy of production's own `app` container -- not to any part of this
measurement's restart cycles or memory pressure. Production's own caps (`app: mem_limit: 3g`,
`redpanda: mem_limit: 2200m`, unchanged) were never modified by this task --
`git diff --name-only HEAD -- docker-compose.prod.yml` is empty.

## D-07 decision outcome

**Selected: `stay-colocated`.** Decided 2026-08-18 by the developer (not the agent) at the
`checkpoint:decision` this plan's Task 3 inserted specifically to keep this call out of unattended
execution, per `08-CONTEXT.md`'s D-07. Reasoning given: no new recurring cost; one host, one firewall
posture, one Caddy, one deploy user; the measured floor (`128M`/`300m`) is backed by a failing step
below it -- three independently confirmed crash-looping rungs at `96M`, `64M` and `32M` (Seastar
allocation failure -> SIGSEGV, `96M`'s failure specifically caught only once monitored continuously
past a false-positive startup check) -- so the floor is a real boundary, not a guess.

Figures the decision was made on (all recorded above in "Iteration ladder" / "Adopted floor" /
"Host coexistence"): the adopted `--memory 128M` / `mem_limit 300m` pair (172MiB margin);
`kanban-nonprod-redpanda`'s peak RSS at ~19.1% of the `mem_limit` cap and ~44.9% of the `--memory`
request under a real 54-request burst; host `free -m` `available` never below 5.8GiB across every
iteration in this session, against the plan's own 1024MiB gate; production's health endpoint
returning 200 across every one of the roughly 15 restart cycles in the descent (the one transient
`502` recorded in Iteration 0 was independently attributed to an unrelated, concurrent external CI/CD
redeploy of production's own `app` container, confirmed via `docker inspect` showing a clean
`RestartCount=0`/`ExitCode=0`/`OOMKilled=false` recreate rather than a crash); and production's own
`mem_limit` values (`app: 3g`, `redpanda: 2200m`) confirmed unmodified throughout
(`git diff --name-only HEAD -- docker-compose.prod.yml` empty for every task in this plan).

**Nothing was provisioned.** No second VPS, no new DNS record, no new recurring cost. Both stacks
remain on the single existing Netcup VPS Lite 2 G12s host.

**End-state re-confirmation** (both stacks at rest, after the decision, 2026-08-18 14:50 UTC):

Both public health endpoints:
```
curl https://kanban-board-rud-vlad-473.duckdns.org/api/actuator/health          -> 200
curl https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api/actuator/health  -> 200
```

`docker stats --no-stream` for all five containers:
```
kanban-board-backend-caddy-1     20.28MiB / 7.759GiB   0.26%   0.00%
kanban-board-backend-app-1       427.3MiB / 3GiB       13.91%  3.27%
kanban-board-backend-redpanda-1  526.3MiB / 2.148GiB   23.92%  0.29%
kanban-nonprod-app                389.2MiB / 1GiB       38.01%  4.04%
kanban-nonprod-redpanda           57.3MiB / 300MiB      19.10%  0.38%
```

`free -m`:
```
               total        used        free      shared  buff/cache   available
Mem:            7945        1876        1992           0        4373        6069
Swap:              0           0           0
```

`docker ps` (uptimes, images):
```
kanban-nonprod-app                Up 9 minutes  (healthy)  rudenkovladimir/kanban-board-backend:777cb27
kanban-nonprod-redpanda           Up 10 minutes (healthy)  docker.redpanda.com/redpandadata/redpanda:v26.2.1
kanban-board-backend-app-1        Up 56 minutes (healthy)  rudenkovladimir/kanban-board-backend:9c89613
kanban-board-backend-caddy-1      Up 3 hours               caddy:2
kanban-board-backend-redpanda-1   Up 30 hours   (healthy)  docker.redpanda.com/redpandadata/redpanda:v26.2.1
```

`docker-compose.nonprod.yml`'s `redpanda-nonprod` carries the adopted caps as committed:
`--memory 128M`, `mem_limit: 300m`.

**Operator note -- what would re-open this decision.** This colocation call is not permanent. Any of
the following should trigger re-running Task 2's ladder from scratch, not adjusting `--memory` or
`mem_limit` by judgement:
- **A materially heavier nonprod workload** (e.g. a real Playwright E2E suite driving sustained
  concurrent traffic, not the occasional manual/CI burst this measurement used) -- the floor found
  here is specific to this app's light real traffic shape (see the backstop `must_haves` truth in
  `08-03-PLAN.md`), not to an arbitrary Kafka workload.
- **A production cap increase** (`app: mem_limit` or `redpanda: mem_limit` raised above their current
  `3g` / `2200m`) -- this measurement's host-headroom conclusion assumed production's caps as they
  stand today.
- **A Redpanda version bump** on either broker -- Seastar's own minimum viable allocation for log
  replay/consumer-group recovery (the actual mechanism that set this floor, not steady-state RSS) is
  an internal implementation detail of the Redpanda version in use, not a guaranteed-stable constant
  across versions.

Re-opening this decision means re-running Task 2's live iterative ladder against the real deploy
target and a real workload -- exactly as this plan itself was required to do rather than accept an
arithmetic derivation. A value chosen by judgement instead of measurement is precisely what
NONPROD-06 and this plan's own prohibitions forbid.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
