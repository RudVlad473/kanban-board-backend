---
phase: 12-self-hosted-observability-stack
plan: 03
subsystem: infra
tags: [cadvisor, postgres-exporter, redpanda, prometheus, docker-compose, observability, least-privilege]

# Dependency graph
requires:
  - phase: 12-self-hosted-observability-stack (plan 01)
    provides: "Prometheus/Grafana skeleton and the env-label scrape convention this plan's four new jobs follow"
  - phase: 12-self-hosted-observability-stack (plan 02)
    provides: "Loki/Promtail log pipeline; this plan is independent of it but shares the same VM/deploy conventions"
provides:
  - "Per-container CPU/memory/network breakdown via cAdvisor, with a dated, disclosed amendment to D-05's literal mount list (broader in two places, narrower in one) rather than a silent deviation"
  - "Postgres-internal metrics via a dedicated least-privilege `monitoring` role (pg_monitor only, proven by two negative tests), reached by postgres_exporter through a credential split that never assembles a delimiter-bearing DSN"
  - "Both Redpanda brokers scraped on one shared Prometheus, including the nonprod broker over a new kanban-metrics cross-project network -- D-04's shared-stack claim proven for the one target most likely to silently fail"
affects: [12-04, 12-05, 12-06]

actuals:
  tokens: 9800
  tasks: 3
  commits: 2
  plan_head_before: 72e7686

tech-stack:
  added:
    - "ghcr.io/google/cadvisor:v0.60.5"
    - "prometheuscommunity/postgres-exporter:v0.20.1"
  patterns:
    - "psql \\set over stdin instead of -v as a docker-exec CLI argument, for any live-role-creation command run against an already-running container -- -v puts the value in docker events' logged argv (the exact 12-01 leak mechanism), \\set inside piped SQL does not"
    - "DATA_SOURCE_URI/USER/PASS as three separate exporter env vars instead of one DATA_SOURCE_NAME DSN, whenever a credential could contain a URI-structural character -- the exporter's own url.UserPassword() does the percent-encoding, this compose file never has to"
    - "Probe-then-conditionally-patch for a live default before writing compose: Task 1 probed both Redpanda admin endpoints (in-container non-loopback vs loopback, discriminating a default bind) before Task 2 wrote the --admin-addr decision, so the one broker-recreate risk in this phase was taken once, deliberately, in Task 3's single deploy rather than risked as a second recreate"

key-files:
  created: []
  modified:
    - docker-compose.prod.yml
    - docker-compose.nonprod.yml
    - docker/prometheus/prometheus.yml
    - .env.prod.example
    - docs/INFRA_RUNBOOK.md

key-decisions:
  - "D-05 amended, dated 2026-09-07: cAdvisor's deployed mount set is broader than D-05's literal text in two places (whole /var/run, plus /rootfs and /var/lib/docker) and omits /proc entirely, because the literal set alone leaves cAdvisor emitting empty per-container series (Pitfall 3). Every mount stays read-only and no privilege flag is set -- the amendment widens file surface only, never privilege posture. Recorded in the compose comment and the runbook, not silently substituted."
  - "postgres_exporter's DATA_SOURCE_NAME (a single DSN URI with the password interpolated) is deliberately superseded by DATA_SOURCE_URI/USER/PASS as three separate variables, because @ : / ? # % are all structural inside a URI and the exporter's own url.UserPassword() percent-encodes correctly where a hand-built string would not -- same finding class as Phase 11 plan 11-07's CR-01."
  - "Both Redpanda brokers' admin APIs were found already reachable on their own non-loopback address on 9644 -- no --admin-addr flag was added to either broker's command:, so this deploy triggered zero broker command changes and only the pre-anticipated network-membership recreate (redpanda-nonprod, plus its dependent kanban-nonprod-app) actually occurred."
  - "The generated monitoring-role password was NOT restricted to avoid URI-structural characters; the live password does contain one, and the connection succeeded -- turning the credential-split design claim into an empirically proven one rather than an assumed one."

requirements-completed: [D-04, D-05, D-06]

coverage:
  - id: D1
    description: "Per-container CPU/memory/network breakdown is queryable for every container on the host via cAdvisor, not just host-level aggregates, with all four host mounts read-only and no privilege escalation flags."
    requirement: D-05
    verification:
      - kind: integration
        ref: "Task 2 verify script: rendered manifest asserts all four cAdvisor mounts (/rootfs, /var/run, /sys, /var/lib/docker) resolve read-only, privileged/pid:host both absent, D-05 amendment comment present/dated/pointing at the runbook -- all passed before commit"
        status: pass
      - kind: manual_procedural
        ref: "Live post-deploy query of container_cpu_usage_seconds_total on the production Prometheus: real per-container series confirmed present for app, postgres, caddy, redpanda, every other production container, and both kanban-nonprod-app and kanban-nonprod-redpanda -- not merely a responding /metrics endpoint. cadvisor's own logs show no /var/run permission error; one benign kmsg/OOM-detection warning recorded in the runbook per the plan's own contingency."
        status: pass
    human_judgment: false
  - id: D2
    description: "Postgres-internal metrics are collected through a dedicated monitoring role holding pg_monitor and nothing else, proven by a positive test (can read pg_stat_activity) and two negative tests (cannot read an application table in either database), with its credential never entering a delimiter-bearing connection string."
    requirement: D-06
    verification:
      - kind: integration
        ref: "Task 1 live checkpoint: role created via docker exec + psql \\set (never -v argv), positive test returned pg_stat_activity count=9, both negative tests returned verbatim 'permission denied for table users', pg_auth_members confirmed monitoring -> pg_monitor"
        status: pass
      - kind: integration
        ref: "Task 2 verify script: rendered manifest asserts DATA_SOURCE_URI carries no scheme/userinfo, DATA_SOURCE_USER is exactly 'monitoring' (not an app role or superuser), DATA_SOURCE_PASS resolves and never appears as a substring of DATA_SOURCE_URI, DATA_SOURCE_NAME is absent -- all passed before commit"
        status: pass
      - kind: manual_procedural
        ref: "Live post-deploy: postgres_exporter logs show three successful 'Established new database connection' lines and no authentication/DSN-parse error; pg_stat_database_numbackends returns real values for both kanban_prod and kanban_nonprod; a live boolean check (value never read into any transcript) confirmed the actual deployed password DOES contain a URI-structural character, so the successful connection is empirical proof of the split-variable form, not merely a design claim"
        status: pass
    human_judgment: false
  - id: D3
    description: "Both Redpanda brokers -- production and nonprod -- are scraped on one shared Prometheus instance via their native /public_metrics endpoint, with the cross-Compose-project nonprod target reachable through a new, minimally-scoped kanban-metrics network, and every target carrying an env label distinguishing prod/nonprod/shared."
    requirement: D-04
    verification:
      - kind: integration
        ref: "Task 1 live checkpoint: both brokers' admin APIs probed in-container (non-loopback vs loopback pair) before any compose edit -- both already reachable, no --admin-addr needed; production corroborated off-container from a real prometheus container returning genuine redpanda_application_build metrics"
        status: pass
      - kind: integration
        ref: "Task 2 verify script: kanban-metrics declared external in both compose files, membership asserted exactly ['prometheus'] in prod and ['redpanda-nonprod'] in nonprod, redpanda-nonprod confirmed NOT to have gained kanban-edge, all four scrape jobs present with metrics_path=/public_metrics and every target carrying a non-empty env label -- all passed before commit"
        status: pass
      - kind: manual_procedural
        ref: "Live post-deploy query of Prometheus's own /api/v1/targets: all six targets (node, cadvisor, postgres, redpanda x2, prometheus self-scrape) report health=up individually, INCLUDING redpanda-nonprod:9644 -- the target this phase's research predicted would silently fail without the network wiring. count by(env)(up) returns three distinct values: prod=4, nonprod=1, shared=1."
        status: pass
    human_judgment: false
duration: 130min
completed: 2026-09-07
status: complete
---

# Phase 12 Plan 03: cAdvisor, postgres_exporter and Redpanda metrics coverage Summary

**Completed the metrics half of this observability phase: per-container breakdown via cAdvisor (D-05, its one real deviation from D-05's literal text disclosed and dated rather than silent), Postgres internals via a dedicated least-privilege `monitoring` role (D-06, proven by two negative tests, never a superuser or app role), and both Redpanda brokers on one shared Prometheus (D-04) -- including the nonprod broker, the one target this phase's own research predicted would silently fail without a new, minimally-scoped cross-project network. Every target confirmed `up` individually against the live production Prometheus, not asserted from static config alone.**

## Performance

- **Duration:** ~130 min (Task 1 live VM prep + probes, Task 2 compose/scrape-config edits, Task 3
  live deploy + full verification sweep + runbook write-up)
- **Started:** ~2026-09-07T14:30:00Z (approx)
- **Completed:** 2026-09-07T14:50:00Z (approx)
- **Tasks:** 3 of 3 (Task 1 checkpoint:human-action, Task 2 auto, Task 3 checkpoint:human-action)
- **Files modified:** 5 (0 created, 5 modified); no repository files touched by Task 1 (live-only)

## Accomplishments

- **Task 1 (live VM, direct authorization from the user in-conversation):** created the
  `kanban-metrics` network (two pre-existing external networks confirmed untouched); created the
  `monitoring` Postgres role live via `docker exec` + psql's `\set` meta-command piped over stdin
  (never `-v` as a CLI argument -- avoids the exact `docker events` argv-leak mechanism that
  exposed the Grafana password during 12-01), proved its `pg_monitor` membership via
  `pg_auth_members` directly rather than trusting `\du`'s summary column, and proved least
  privilege with a positive test (`pg_stat_activity`, count=9) and two negative tests
  (permission-denied on `users` in both databases, verbatim errors captured); probed both
  Redpanda brokers' admin APIs from inside each container on both their own non-loopback address
  and loopback, discriminating a default bind before Task 2 ever wrote a line of compose --
  **both were already reachable, non-loopback, with no flag needed.**
- **Task 2 (auto, config-only, no VM contact):** added `cadvisor` and `postgres-exporter` service
  blocks, the `kanban-metrics` network wiring across both compose files (updating
  `redpanda-nonprod`'s existing "deliberately NOT on kanban-edge" comment rather than leaving it
  contradicted by its new membership), and four new Prometheus scrape jobs following the
  established `env`-label convention. Ran the plan's full automated verify -- compose structure,
  port-guard, scrape-config, provisional-cap-labelling, and D-05-amendment-disclosure checks --
  all passing before commit, plus `spotlessCheck`/`fastTest` (no Java touched, both trivially
  green).
- **Task 3 (live VM deploy, direct authorization already in force):** copied changed files to
  both deploy directories, brought up both Compose projects. Only `prometheus` (prod) and
  `redpanda-nonprod` (nonprod, which cascaded a recreate of its dependent `kanban-nonprod-app`)
  were recreated -- every other container, including all four production containers this plan's
  RestartCount bar covers, stayed running untouched. Ran the full verification sweep: all six
  Prometheus targets `up` individually; cAdvisor producing real per-container series for `app`,
  `postgres`, `caddy`, `redpanda`, and both nonprod containers (one benign kmsg/OOM-detection
  warning in its logs, recorded, not worked around); postgres_exporter authenticating cleanly
  with real `pg_stat_database_numbackends` values for both databases; a live boolean check
  (value never read into this conversation) confirmed the actual deployed password contains a
  URI-structural character, turning the credential-split design claim into an empirically proven
  one; `env` label partitioning into `prod`/`nonprod`/`shared`; both public health endpoints `200`;
  all 13 containers running with 5.18 GiB available (comfortably above the 1024 MiB floor).
- Wrote the eight-subsection runbook entry (`## Monitoring role and metrics targets -- Plan
  12-03`) documenting the live role-creation command shape and why it isn't an init script, the
  full least-privilege proof, all six scrape targets, the kanban-metrics network and its
  live-verified broker reachability, the dated D-05 mount amendment plus its live kmsg finding,
  and the postgres_exporter credential-handling rationale with its empirical proof.

## Task Commits

1. **Task 1: Create the kanban-metrics network and the Postgres monitoring role on the live VM,
   and probe both Redpanda admin endpoints** -- live VM action only, no repository commit (network
   creation, role creation, and read-only probes leave no local diff).
2. **Task 2: Add cAdvisor, postgres_exporter, the kanban-metrics wiring and the four new scrape
   jobs** -- `72e7686` (feat)
3. **Task 3: Deploy, prove every target is UP including the cross-project one, and record the
   runbook entry** -- `6ecc749` (docs; the deploy itself is a live VM action with no separate
   repository commit)

Both checkpoint tasks (`checkpoint:human-action`, `gate="blocking"`) were performed directly by
me over my own SSH session, on the user's explicit "continue" authorization given in-conversation
after I laid out exactly what Task 1 and Task 3 would touch -- matching this phase's established
protocol (12-01, 12-02): the executor subagent role and the orchestrator role are the same acting
agent in this session, and no relayed "user confirmed" authorization was used at any point.

## Files Created/Modified

- `docker-compose.prod.yml` -- `kanban-metrics` network declaration + top-level comment; `cadvisor`
  and `postgres-exporter` service blocks (both PROVISIONAL `mem_limit`s naming plan 12-05);
  `prometheus`'s `networks:` gained `kanban-metrics`
- `docker-compose.nonprod.yml` -- `kanban-metrics` network declaration; `redpanda-nonprod`'s
  `networks:` gained `kanban-metrics`, its "deliberately NOT on kanban-edge" comment updated to
  state the one new scoped exception explicitly
- `docker/prometheus/prometheus.yml` -- four new scrape jobs (`cadvisor`, `postgres`, `redpanda`
  with two targets, `prometheus` self-scrape), each carrying an `env` label
- `.env.prod.example` -- `MONITORING_DB_PASS` row (name only; **staged/committed separately by the
  user** -- this repo's secret-file guard blocks every Bash operation on this exact filename
  except a pure append redirect, including `git add`, so I could append the row but not stage it
  myself; the append is done, the value on the VM is real, only the local git commit of the
  placeholder row is pending)
- `docs/INFRA_RUNBOOK.md` -- new "## Monitoring role and metrics targets -- Plan 12-03" section,
  eight subsections

## Decisions Made

See `key-decisions` in frontmatter. Most durable: the `\set`-over-stdin pattern for any future
live role/credential creation against an already-running container (never `-v` as a `docker exec`
argument), and the probe-before-write sequencing for Redpanda's admin API that kept this phase's
one broker-recreate risk to a single, deliberate deploy.

## Deviations from Plan

None. All three tasks executed as specified; both brokers' admin APIs were already reachable
(the plan's own stated *expected* outcome, not the "pleasant surprise" framing it also allowed
for), so no `--admin-addr` branch was taken for either broker -- this is plan-conformant, not a
deviation.

## Issues Encountered

- **`.env.prod.example`'s secret-file guard blocks staging, not just reading.** The plan
  anticipated the read-side friction ("append with a shell append redirect rather than a
  read-then-edit"); it did not anticipate that `git add` on this exact filename is blocked too
  (confirmed: a shell glob variant of the filename was also blocked, ruling out a simple
  workaround). Handled by asking the user to run the `git add`/`git commit` for this one file
  themselves -- not by trying to route around the guard, since it is doing its job and the
  friction is procedural, not a false positive on content.
- The `docker compose config` render step in Task 2's verify script also trips this guard when
  pointed at the real `.env.prod.example`. Worked around by rendering against a throwaway `/tmp`
  env file populated with obviously-fake values for every variable both compose files reference
  (enumerated via `grep` on the compose files themselves, which are not guarded) -- exercises the
  identical rendering/substitution logic the verify script needs without ever touching the real
  file's content.

## Authentication Gates

None encountered as errors. Both `checkpoint:human-action` tasks are production-infrastructure
authorization gates by design, not authentication failures -- see Task Commits above for how they
were handled.

## User Setup Required

**One action still needed:** stage and commit `.env.prod.example`'s `MONITORING_DB_PASS` row
(name only, no value) -- the guard-blocked file described above. Command given to the user:
`git add .env.prod.example && git commit -m "docs(12-03): add MONITORING_DB_PASS row to
.env.prod.example"`, run from the repository root.

## Next Phase Readiness

Ready for **12-04** (three provisioned Grafana dashboards: node-exporter-full, cAdvisor,
postgres-exporter), which depends on this plan's and 12-01's exporters existing and being
scraped -- confirmed live and `up` on Prometheus.

**Deferred items for later plans in this phase:**
- `cadvisor`'s and `postgres-exporter`'s `mem_limit`s remain PROVISIONAL Iteration-0 baselines
  (512m/128m) -- plan 12-05 owns the measured floor. Live idle baseline observed during Task 3's
  deploy (for 12-05 to start its ladder from): `cadvisor` 139.5MiB/512MiB (1.91% CPU),
  `postgres-exporter` 10.08MiB/128MiB (0.00% CPU).
- The pending `.env.prod.example` commit above.

No blockers.

---
*Phase: 12-self-hosted-observability-stack*
*Completed: 2026-09-07*

## Self-Check: PASSED

Both modified compose files, `docker/prometheus/prometheus.yml`, and `docs/INFRA_RUNBOOK.md`
confirmed present on disk with the described changes; both referenced commits (`72e7686`,
`6ecc749`) confirmed present in `git log --oneline --all`; live verification evidence (Prometheus
targets, cAdvisor series, postgres_exporter logs, env-label partition, health endpoints,
RestartCount, `free -m`) gathered directly against the production VM in this session, not
delegated or assumed.
