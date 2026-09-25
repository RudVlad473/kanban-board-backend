# Triage — dating what the Netcup SCP "Screen" console shows (2026-09-02)

The Netcup SCP "Screen" tab is a frozen tty1 framebuffer, not a live log: kernel console messages
persist on it indefinitely, and their bracketed timestamps (e.g. `[799547.643450]`) are seconds since boot,
not wall-clock time. The consequence is direct — a week-old, already-fixed incident renders
identically to an outage in progress, and nothing in the tab itself distinguishes the two.
On 2026-09-02, a screenshot of four `Memory cgroup out of memory: Killed process NNNN (postgres)
...` lines on that tab was reported as "nonprod is down"; nonprod was not down, and the kills were
seven days old.

## Triage order

Cheapest first — do not SSH until step 2 forces it.

1. Curl both public endpoints. This needs no SSH and answers the actual question:

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' https://kanban-board-rud-vlad-473.duckdns.org/api/actuator/health
   curl -s -o /dev/null -w '%{http_code}\n' https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api/actuator/health
   ```

   Drop `-o /dev/null` to see the `{"status":"UP"}` body. This is a real check, not just "the
   process is listening": Spring's aggregate health status is the worst of all contributors,
   including the auto-configured DataSource indicator, so a public `UP` covers the database. Its
   limit in the same breath: `management.endpoint.health.show-details=never` means the body carries
   no component breakdown, so a `DOWN` never names the failing subsystem. For a stricter proof that
   the query path actually executes, a `POST /api/signin` with deliberately wrong credentials should
   answer `401` — not a `500`, not a timeout.

2. `ssh netcup-prod`, then `docker ps` and, per container:

   ```bash
   docker inspect -f '{{.Name}} restarts={{.RestartCount}} started={{.State.StartedAt}} oomkilled={{.State.OOMKilled}}' <container>
   ```

3. `dmesg -T | grep -i "out of memory"` — `-T` is the whole point of this step: it renders the
   seconds-since-boot bracket as local wall-clock, which is what dates whatever the console showed.

4. Normalise the clocks before comparing anything — see "The timezone trap" below.

5. Read the per-instance kill counter for the container in question:

   ```bash
   cat /sys/fs/cgroup/system.slice/docker-$(docker inspect -f '{{.Id}}' <container>).scope/memory.events
   ```

   and, in that same directory, `memory.peak` against `memory.max` for headroom. The scoping caveat
   is what makes this counter meaningful: `oom_kill 0` means nothing has been killed since **this
   container instance started** — the counter is per-instance and resets with it, so it is evidence
   about the current instance's lifetime, never about the box's history. On 2026-09-02 this read
   `oom 0` / `oom_kill 0` with `memory.peak` 109678592 against a `memory.max` of 268435456 (~40.8%
   of cap).

## The timezone trap

| Source | Timezone reported | Example |
|--------|--------------------|---------|
| `dmesg -T` on the VM | Europe/Berlin (CEST, UTC+2) | `Wed Aug 26 20:28:31 2026` |
| `docker inspect -f '{{.State.StartedAt}}'` | UTC, always | `2026-08-26T19:24:34Z` |

Compared raw, `20:28` looks later than `19:24`, which reads as "the process was killed after the
current container started" — an open incident. Normalised, `19:24Z` is `21:24` CEST, 56 minutes
**after** the last kill, and nothing has been killed since. This exact inversion is what turned a
closed experiment into a reported outage.

## The Aug 26 2026 kills specifically

Four kills appear on the console that day; both groups are already documented elsewhere in this
same file, and a future reader should stop rather than escalate on either:

- The three kills at 16:54:06 / 16:54:17 / 16:54:32 CEST are the `32m` rung of this file's own
  "Self-hosted Postgres resource measurement — Plan 11-03" section (its "Step below the floor"
  subsection reproduces the identical kernel lines).
- The single kill at 20:28:31 CEST is the live reproduction of the unfixed `mem_limit: 64m` /
  `shared_buffers=128MB` pairing recorded under "Postgres memory profile correction — Plan 11-08"
  ("Counter-evidence: the previous configuration under the same workload").

Both were deliberate ladder experiments, both are closed, and the profile in force since is the one
in this file's Database table (`mem_limit` `256m`, `shared_buffers=64MB`, `work_mem=4MB`,
`max_connections=25`). These four are expected historical noise on that console — a kill that
cannot be dated to one of these two sections is genuinely new, and that is when to escalate.

## Standing automated check

`.github/workflows/uptime-check.yml` probes both public health endpoints on a schedule and on
demand. Its honest limit: it answers "were both endpoints answering within roughly the last quarter-
hour", not "is nonprod up right now", and it pages nobody.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
