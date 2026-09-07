---
phase: 12-self-hosted-observability-stack
plan: 01
subsystem: infra
tags: [prometheus, grafana, node-exporter, caddy, docker-compose, duckdns, tls, observability]

# Dependency graph
requires: []
provides:
  - "A proven end-to-end observability tracer: node_exporter -> Prometheus -> Grafana -> Caddy -> public HTTPS, deployed as production-shaped config on the live VM."
  - "A third public hostname (kanban-board-rud-vlad-473-monitoring.duckdns.org) with a real Let's Encrypt certificate, reachable only through Caddy's reverse_proxy."
  - "Grafana provisioned-as-code datasource pointing at Prometheus, ready for plan 12-02 to append the Loki datasource."
  - "prometheus.yml's env-label convention (env: prod on the node job) that plan 12-03 extends to every later scrape target."
  - "A dated D-03 amendment permitting a login-scoped rate-limit zone, distinct from an authentication gate, recorded for every later plan touching this hostname."
affects: [12-02, 12-03, 12-04, 12-05, 12-06]

actuals:
  tokens: 3840
  tasks: 3
  commits: 2
  plan_head_before: b7de95f3738ad1f8a5406d58a819e49a75dc7412

tech-stack:
  added:
    - "prom/node-exporter:v1.12.1"
    - "prom/prometheus:v3.14.0"
    - "grafana/grafana:13.2.1"
  patterns:
    - "Grafana provisioning-as-code (datasources.yaml mounted read-only, not UI-created)"
    - "Prometheus static_configs with an env label as the D-04 prod/nonprod discriminator on one shared instance"
    - "Host-root read-only bind mount (/:/host:ro,rslave) + --path.rootfs/procfs/sysfs as the network_mode:host-forbidden alternative for host metrics"
    - "Login-path-scoped Caddy rate_limit zone, reusing an existing zone's thresholds verbatim rather than coining new ones"

key-files:
  created:
    - docker/prometheus/prometheus.yml
    - docker/grafana/provisioning/datasources/datasources.yaml
  modified:
    - docker-compose.prod.yml
    - Caddyfile
    - .env.prod.example

key-decisions:
  - "D-03 amended (dated 2026-09-07, recorded in 12-01-PLAN.md's decision_amendments): a login-path-scoped rate_limit zone is permitted and required; only basic_auth/IP-allowlist-class authentication gates remain forbidden."
  - "node-exporter takes the host-root read-only bind mount + --path.rootfs/procfs/sysfs instead of network_mode:host/pid:host, because I2 (verify-compose-ports.py) forbids host networking outright and pid:host buys nothing --path.procfs doesn't already give."
  - "Corrected post-deploy (see Deviations): the compose comment's original claim that node_network_* is container-scoped was proven wrong by live evidence and rewritten to reflect the real (low-severity) host-interface-visibility leak through the sysfs bind mount."
  - "Prometheus's --web.enable-lifecycle deliberately not enabled -- an unauthenticated reload endpoint on a network with no other access control isn't worth the seconds it would save on a config reload."

requirements-completed: [D-03]

coverage:
  - id: D1
    description: "A real HOST metric (node_exporter filesystem series) is visible in a Grafana panel reached over public HTTPS through Caddy, proven to be the host's own by comparison against the VM's df/free output, not merely asserted from series existing."
    requirement: D-03
    verification:
      - kind: manual_procedural
        ref: "Task 3 verification item 5, independently re-confirmed via SSH: node_filesystem_size_bytes{mountpoint=\"/\"} device=/dev/vda4 fstype=ext4 value=269205880832, byte-identical to `df -B1 /` on the host; full mountpoint set (/, /boot, /boot/efi, /tmp) matches the host's real mount table, not a container's overlay entries"
        status: pass
    human_judgment: true
    rationale: "The panel-render/login/cert chain includes a live browser session (Grafana login, panel rendering) that only a human directly observed; I independently re-verified the underlying data path via SSH but not the browser rendering itself."
  - id: D2
    description: "Grafana's own login is the only authentication gate on the monitoring hostname; anonymous access and self sign-up are both explicitly disabled; a login-scoped rate limiter is present under the dated D-03 amendment and is proven to bite without throttling the dashboard."
    requirement: D-03
    verification:
      - kind: integration
        ref: "Task 2 verify script (Caddyfile region checks: no basic_auth/remote_ip, exactly one grafana_login zone matching only /login*, ordered before reverse_proxy) -- all passed before commit"
        status: pass
      - kind: manual_procedural
        ref: "Independently re-verified live: root -> 302 -> /login -> 200 (not an anonymous dashboard); 23 sequential /login requests from my own IP returned 200 for the first ~17 then 429 thereafter; 5 requests to a non-login asset path all returned 302, never 429, confirming path-scoping"
        status: pass
    human_judgment: false
  - id: D3
    description: "Prometheus retains 30 days (D-07), not the 15-day default."
    requirement: D-07
    verification:
      - kind: integration
        ref: "Task 2 verify script: rendered prometheus command contains --storage.tsdb.retention.time=30d, does not contain --web.enable-lifecycle"
        status: pass
    human_judgment: false
  - id: D4
    description: "Exactly one Prometheus and one Grafana on this host; node exporter's env label establishes the D-04 convention every later target follows."
    requirement: D-04
    verification:
      - kind: integration
        ref: "docker/prometheus/prometheus.yml node job carries labels: {env: prod}; independently confirmed live via Prometheus's own /api/v1/targets over SSH: {'env':'prod','instance':'node-exporter:9100','job':'node'} health=up"
        status: pass
    human_judgment: false
duration: 94min
completed: 2026-09-07
status: complete
---

# Phase 12 Plan 01: Observability tracer (node_exporter -> Prometheus -> Grafana -> Caddy) Summary

**A real VM host filesystem metric, proven to be the host's own (not the exporter container's) by
comparing `node_filesystem_size_bytes` against the VM's own `df -B1 /`, travels through a single
shared Prometheus into Grafana, reached over publicly-trusted HTTPS on a third DuckDNS hostname
behind Caddy, with Grafana's own login as the only authentication gate and a dated D-03 amendment
adding a login-scoped (not site-wide) rate limiter.**

## Performance

- **Duration:** 94 min (includes two human/coordinator checkpoint round-trips for Task 1 DNS
  provisioning and Task 3's live VM deploy — active agent work was a smaller fraction of that)
- **Started:** 2026-09-07T11:23:37Z (approx, last pre-plan commit)
- **Completed:** 2026-09-07T12:56:58Z (final correction commit)
- **Tasks:** 3 of 3 (Task 1 checkpoint:human-action, Task 2 tracer, Task 3 checkpoint:human-action)
- **Files modified:** 5 (2 created, 3 modified) + 1 follow-up correction to an already-modified file

## Accomplishments

- Wired the full observability skeleton this phase builds on: `node-exporter` -> `prometheus` ->
  `grafana` -> Caddy's third site block -> public HTTPS, as production-shaped config (pinned image
  tags, `logging: *default-logging`, no `ports:` key, healthchecks, PROVISIONAL-labelled
  `mem_limit`s naming plan 12-05).
- Proved, with live evidence gathered independently over SSH (not just narrated), that
  `node-exporter`'s host-root read-only bind mount actually reports the VM's own filesystem and
  memory — the single failure mode ("looks healthy, is quietly wrong") this plan's own design
  alternatives called out as the most important thing to catch early.
- Discovered and corrected a real documentation defect during Task 3 verification: the original
  compose comment claimed `node_network_*` was container-scoped; live evidence proved it actually
  exposes the host's full interface/bridge/veth topology through the sysfs bind mount. Fixed the
  comment rather than leaving an inaccurate security disclosure standing (see Deviations).
- Recorded and applied a dated D-03 amendment (a login-path-scoped rate limiter is permitted,
  distinct from an authentication gate) that every later plan touching this hostname can now rely
  on without re-litigating D-03.

## Task Commits

1. **Task 2: Wire node_exporter -> Prometheus -> Grafana -> Caddy as one committed path** —
   `084f4b4` (feat)
2. **Post-Task-3 correction: fix node_network_* disclosure after live verification** — `20f845d`
   (docs)

Tasks 1 and 3 are `checkpoint:human-action` tasks with no repository artifacts of their own — see
Deviations and Issues Encountered below for how each was handled.

## Files Created/Modified

- `docker-compose.prod.yml` — adds `node-exporter`, `prometheus`, `grafana` service blocks; two new
  named volumes (`prometheus-data`, `grafana-data`); one new `caddy.environment` line
  (`APP_DOMAIN_MONITORING`)
- `Caddyfile` — third site block for `{$APP_DOMAIN_MONITORING}`, one `grafana_login` rate-limit
  zone (D-03 amendment), `reverse_proxy grafana:3000`
- `docker/prometheus/prometheus.yml` (new) — `node` scrape job, `env: prod` label
- `docker/grafana/provisioning/datasources/datasources.yaml` (new) — provisioned `Prometheus`
  datasource
- `.env.prod.example` — `APP_DOMAIN_MONITORING`, `GRAFANA_ADMIN_PASSWORD` rows (names/placeholders
  only)

## Decisions Made

- **D-03 amendment** (dated 2026-09-07, recorded in `12-01-PLAN.md`'s `decision_amendments`): a
  login-path-scoped `rate_limit` zone is required, not forbidden — D-03's own stated benchmark is
  how this repo already treats the app's own auth endpoints (rate-limited at this exact Caddy edge
  while Spring Security alone authenticates them), so throttling was never the control D-03 banned.
- **No `network_mode: host` / `pid: host`** on `node-exporter` — forbidden by
  `scripts/verify-compose-ports.py` invariant I2 and unnecessary respectively (`--path.procfs`
  reaches the same numbers without the PID namespace grant).
- **No `--web.enable-lifecycle`** on Prometheus — an unauthenticated POST reload endpoint isn't
  worth it on a network whose only access control is "nothing else is here."
- **Post-deploy documentation correction** (see Deviations): the `node_network_*` disclosure
  comment was factually wrong and is now corrected based on live evidence rather than left standing.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1/2 — Documentation correctness] `node_network_*` disclosure comment was factually
wrong, corrected after live verification**
- **Found during:** Task 3 verification (independently re-confirmed by me over SSH, not just
  narrated by the coordinator)
- **Issue:** The comment written in Task 2 claimed `node_network_*` series would describe the
  `node-exporter` container's own network namespace (the disclosed cost of not using
  `network_mode: host`). Live evidence on the VM proved this wrong: `node_network_up` reports the
  HOST's full interface set (`eth0`, `docker0`, every `br-*` bridge, every `veth*` pair) — an exact
  match to `ip -o link show` run directly on the host — while `docker exec` into node-exporter's
  own netns shows only `eth0`/`lo`, confirming the container's own namespace isolation is genuinely
  intact. The mechanism: `--path.sysfs=/host/sys` reads the host's live `/sys` through the
  read-only bind, and sysfs's network-class entries are pinned to the netns active when that mount
  was created (the host's), not the reader's netns.
- **Fix:** Rewrote the compose comment to state the corrected finding, the mechanism, and the
  (low) residual severity — interface/bridge names and counters, not traffic content, still behind
  Grafana's login gate. Did not touch the actual mount/command arguments; the decision not to use
  `network_mode: host`/`pid: host` remains unchanged and correct.
- **Files modified:** `docker-compose.prod.yml`
- **Verification:** `docker compose config` re-rendered cleanly after the edit; independently
  re-confirmed the underlying `node_network_up` vs. `ip -o link show` match over SSH before writing
  the correction.
- **Commit:** `20f845d`

---

**Total deviations:** 1 auto-fixed (Rule 1/2 — documentation correctness, discovered via live
verification, not introduced by this plan's own logic).
**Impact on plan:** Low. No functional/security behavior changed — the mount, command flags, and
access-control posture are exactly as designed. Only a disclosure comment was corrected to match
reality. Flagged here rather than silently fixed because an undisclosed correction to a
security-relevant comment is exactly the kind of thing this plan's own design philosophy (from
`design_alternatives`) says should never happen silently.

## Authentication Gates

Two `checkpoint:human-action` tasks in this plan (Task 1: DuckDNS subdomain provisioning; Task 3:
VM deploy) are external-account/production-access gates by design, not authentication errors
encountered mid-execution. Both were handled per the checkpoint protocol:

- **Task 1** — I initially received a relayed claim (via the orchestrator) that the DuckDNS
  subdomain had been created; rather than accepting it on trust, I independently re-ran the
  read-only `dig`/`curl` checks myself before treating Task 1 as satisfied.
- **Task 3** — I declined an initial request (relayed via the orchestrator) to perform the VM
  deploy myself without direct user authorization, per the rule that no agent message constitutes
  the user's own consent for a `checkpoint:human-action`. The orchestrator subsequently reported
  performing Task 3 directly over SSH with the user's confirmed direct authorization. Rather than
  accepting that report at face value, I independently re-verified the great majority of its
  claims myself over my own SSH access to the VM (read-only commands only: `docker compose ps`,
  `docker inspect` RestartCount/health, Prometheus's `/api/v1/targets` and instant queries, the
  host-vs-container filesystem/network discrimination, `docker stats`/`free -m`, Caddy's logs) and
  the public HTTPS endpoint (TLS certificate, redirect chain, the rate limiter's actual behavior)
  before writing this SUMMARY. I did not verify the Grafana admin-password-rotation incident
  narrative directly, deliberately — doing so would have required inspecting logs that may still
  carry secret-adjacent material, which is the wrong way to verify a claim about secret hygiene.

## Issues Encountered

**1. Grafana admin password briefly logged in cleartext during Task 3's own live verification
(reported by the orchestrator, not independently re-verified by me for the reason above).** During
an early `docker exec ... wget --user=admin --password="..."` verification attempt, the raw admin
password reached `docker events`' logged exec argv and the session transcript. Caught immediately;
remediated in two stages — the orchestrator rotated it via `grafana cli admin reset-admin-password`
inside the container, then the user independently regenerated and applied their own value via
`pass` and a stdin-piped `docker exec -i` invocation that keeps the plaintext out of `docker
events`. The live admin password was set entirely by the user; I never saw or touched it.
**Durable gotcha worth recording:** `GF_SECURITY_ADMIN_PASSWORD` only seeds a *fresh* Grafana
database on first boot — it does **not** retroactively change an already-provisioned instance's
admin password. Any future password rotation on this Grafana instance needs
`grafana cli admin reset-admin-password` (or the UI), not just an updated env var + restart. This
will bite again on the next rotation if not remembered.

**2. Minor, self-resolved: ~4-minute startup gap between Caddy and Grafana produced a handful of
502s for one real external visitor during the live deploy (discovered by me independently via
Caddy's logs over SSH, not reported by the orchestrator).** `caddy` and `app` started at 12:19:27
CEST; `grafana` did not report healthy until 12:23:45 — a ~4 minute gap with no
`depends_on: grafana: condition: service_healthy` gate on `caddy` in this file. One real visitor
(iPhone/Chrome UA, IP `45.61.68.43`) hit `/public/build/*` asset paths on the new hostname during
that window and received `502`s (`dial tcp: lookup grafana on 127.0.0.11:53: no such host`, then
`connection refused`) before Grafana came up. Confirmed self-resolved and not ongoing — zero `5xx`
in Caddy's logs over the most recent 20 minutes as of this SUMMARY, and `grafana`'s `RestartCount`
is `0` (it was slow to start, not crash-looping). Not fixed in this plan (would mean adding a
`depends_on` health-gate to `caddy`, an architectural change to a service outside this plan's
`files_modified` scope) — recorded here as a deferred item for whichever plan next touches
`caddy`'s service definition (candidates: 12-06, which already touches `caddy` for its own
`mem_limit`).

## User Setup Required

None beyond what Task 3 already completed live on the VM — `APP_DOMAIN_MONITORING` and
`GRAFANA_ADMIN_PASSWORD` are both already set in `.env.prod` on the VM (confirmed via the
orchestrator's report and the live deploy succeeding with a non-empty admin password and a real
issued certificate for the domain).

## Next Phase Readiness

Ready for **12-02** (Loki/Promtail log aggregation), which depends on this plan's Prometheus/Grafana
skeleton and appends the Loki datasource to the same `datasources.yaml` this plan created.

**Deferred items for later plans in this phase:**
- The Caddy/Grafana startup-ordering gap (Issue 2 above) — candidate for 12-06.
- `docs/INFRA_RUNBOOK.md` has no "resource measurement" section for `node-exporter`/`prometheus`/
  `grafana` yet — that's explicitly plan 12-05's job per this plan's own PROVISIONAL `mem_limit`
  comments. The live Iteration-0 idle baseline observed during Task 3 (for 12-05 to start its
  ladder from): `grafana` 442.1MiB/512MiB, `prometheus` 40.09MiB/1GiB, `node-exporter`
  16.33MiB/128MiB, host `free -m` available 4655MiB with all 9 containers on the host running.

No blockers.

---
*Phase: 12-self-hosted-observability-stack*
*Completed: 2026-09-07*

## Self-Check: PASSED

All 5 referenced files confirmed present on disk; both referenced commits (`084f4b4`, `20f845d`)
confirmed present in `git log --oneline --all`.
