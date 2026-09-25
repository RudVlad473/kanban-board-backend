# Manual deploy — Plan 05-04 Task 3 (2026-08-16) — Redpanda resource caps, measured

## Workload used to measure

A 54-request burst fired in rapid sequential succession through the public HTTPS API against the
live production stack: 6 columns, 24 tasks (4 per column), 24 subtasks (1 per task) — each
mutation producing a real Avro-serialized Kafka publish against the production registry and a
real consumer-side persist. Not an idle-stack measurement; not a synthetic load-test tool, since
this app's real traffic shape (a personal/portfolio kanban board) is what the caps need to be
correct for, not a stress-test peak.

## Measured figures

| | Idle baseline | Under burst (6 samples) |
|---|---|---|
| `redpanda` RSS | 347.8MiB (~17% of its 2G internal cap) | unchanged at 347.8MiB |
| `redpanda` CPU | 0.43% | peaked 7.3-8.6% (of one `--smp 1`-pinned core, not the host's 4) |
| `app` RSS | 458.6MiB (~14.9% of its 3g `mem_limit`) | grew ~12MiB to 471.1MiB (~15.3%) |
| `app` CPU | 0.26% | peaked 3.5-6.2% |
| `caddy` RSS/CPU | 19.9MiB / 0.00% | unchanged (~20MiB, <0.2%) |
| Host memory | 1.3GiB used / 6.4GiB available of 7.8GiB total | unchanged — this workload does not move the needle at the host level |

Shape measured against: Netcup VPS Lite 2 G12s, 4 vCPU / 7.8GiB RAM (05-03's verified figure).

## Correction made

The `--smp 1` / `--memory 2G` values themselves were **left unchanged** — the measurement showed
redpanda using under 18% of its memory cap and a small fraction of its single-core CPU ceiling even
under this burst, so there was no measured basis to tighten or loosen either value. The prior
budget math (redpanda 2G + app 3G = 5G, leaving 2.8G of the 7.8GiB host for Caddy + the OS) is now
measurement-verified rather than merely asserted; `docker-compose.prod.yml`'s comment above the
broker command block was rewritten from "floor pending Task 3" to the measured basis above.

One genuine gap was found and closed: the `redpanda` service had no Docker-level cgroup `mem_limit`
at all — only Redpanda's own internal `--memory 2G` (Seastar's own allocator accounting), with no
second backstop the way the `app` service already has (`mem_limit: 3g`). 05-RESEARCH.md's Pitfall 1
explicitly recommends cgroup limits on *both* co-resident containers. Added `mem_limit: 2200m`.

**Found live, by trying the obvious value first and it breaking startup:** setting `mem_limit`
numerically equal to `--memory 2G` (i.e. `mem_limit: 2g`) made the container fail every restart
attempt: `Could not initialize seastar: std::runtime_error (insufficient physical memory: needed
2147483648 available 2078277632)`. A cgroup limit exactly equal to Seastar's own requested usable
memory leaves no room for cgroup accounting overhead — Seastar's own probe saw ~66MiB less than the
2048MiB it asked for at an exactly-2GiB cgroup ceiling, and refused to start smaller rather than
silently degrade. `2200m` (~150MiB of headroom above the internal request) fixed it — confirmed by
a live restart reaching `(healthy)` within the existing 15s `start_period`. This is exactly the "a
cap that makes the broker unhealthy is a failed correction, not a tighter one" case the plan's own
`<done>` criterion names — caught and fixed before being left in that state, not shipped broken.

Real worst-case host budget with the new backstop in place: `app` 3G + `redpanda` 2200m = ~5.15G,
leaving ~2.65GiB of the measured 7.8GiB host for Caddy + the OS.

## Re-verification after the fix

- `docker compose -f docker-compose.prod.yml ps`: `app` `Up (healthy)`, `caddy` `Up`, `redpanda`
  `Up 21s (healthy)` — recreated cleanly on the corrected `mem_limit`.
- Off-VM: `curl -o /dev/null -w '%{http_code}' https://kanban-board-rud-vlad-473.duckdns.org/api/actuator/health`
  → `200`, confirming the end-to-end HTTPS path from Task 1 still works after the restart.
- `rpk registry subject list` after the restart still returns 14 subjects — the named Docker volume
  (`redpanda-data`) persisted the registry's data across the container recreation, not just the
  broker process restarting clean.
- Post-fix `docker stats`: `redpanda` `345.5MiB / 2.148GiB` (`MEM USAGE / LIMIT` now correctly
  shows the new cgroup ceiling instead of the host's full 7.759GiB, confirming the limit is
  actually in effect, not merely present in the YAML).
- No memory- or CPU-constrained finding surfaced that the caps couldn't resolve — there was nothing
  to work around beyond the `mem_limit`-value fix above.
- `grep -v '^\s*#' docker-compose.prod.yml` shows explicit `mem_limit`, `--smp`, `--memory` and
  `--overprovisioned` values for the broker.
- `grep -rc "spring.threads.virtual" src/main/resources/` → 0 across every file; no
  virtual-threads setting was introduced to compensate for anything.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
