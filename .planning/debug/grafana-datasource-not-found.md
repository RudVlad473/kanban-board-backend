---
status: awaiting_human_verify
trigger: "shared links for observability from this repo aren't working: https://github.com/RudVlad473/kanban-board-backend (check readme). Screenshot of Grafana 'VM Host Metrics' dashboard: every panel shows No data/N/A, tooltip 'Datasource was not found'."
created: 2026-09-12T00:00:00Z
updated: 2026-09-12T00:00:00Z
---

## Symptoms

- Expected: README's "Live" section public Grafana dashboard links show live VM host metrics to any visitor without login.
- Actual: opening the public dashboard link renders the dashboard shell (panel titles, layout) but every panel shows "No data" / "N/A", and hovering a panel shows tooltip "Datasource was not found".
- Errors: Grafana UI tooltip "Datasource was not found" (screenshot provided by user, not pasted as text).
- Timeline: unknown when it broke; discovered today (2026-09-12) via user report. Public links were added to README recently (git log shows "docs: add public Grafana dashboard links to README's Live section").
- Reproduction: open either link from README's Live section:
  - https://kanban-board-rud-vlad-473-monitoring.duckdns.org/public-dashboards/7db62007a2114c988c21c4cf6eac9776
  - https://kanban-board-rud-vlad-473-monitoring.duckdns.org/public-dashboards/5a72e5df7fd54618ae28972cabcd8846

## Current Focus

bug_class: Bohrbug — fully deterministic, reproduces on every request to both public dashboards.

hypothesis (CONFIRMED for the datasource error): The provisioned dashboard JSONs never carried a
resolvable datasource **UID**. `node-exporter-full.json` references
`{"type":"prometheus","uid":"${ds_prometheus}"}` (a `datasource`-type TEMPLATE VARIABLE) and
`cadvisor.json` references the legacy NAME string `"Prometheus"`. Grafana's public-dashboard query
backend resolves datasources strictly by UID and does not interpolate template variables, so both
forms fail lookup with `data source not found` → HTTP 500 → the UI's "Datasource was not found"
tooltip. This is NOT UID drift: no concrete UID was ever stored to drift from, and the UID Grafana
derives for an unpinned provisioned datasource turned out to be DETERMINISTIC from its name (a
fresh local container produced byte-identical UIDs to production's) — so drift was never possible
either. Superseded an earlier note in this block claiming the UID was random per provision.

next_action: fix applied and verified locally + against live production Prometheus; committed
locally, NOT pushed. Awaiting human review of the diff before push (standing "ask before merging to
main" preference). Nothing deploys until then.

reasoning_checkpoint:
  hypothesis: "Two independent conditions must BOTH hold for a panel to render on these public
    dashboards, and neither holds today: (1) the panel's datasource ref must be a literal UID that
    exists — `${ds_prometheus}` and `\"Prometheus\"` are not; (2) the panel's query must contain no
    dashboard template variables — the public backend interpolates only built-in macros
    ($__rate_interval), never query/datasource variables."
  confirming_evidence:
    - "Production Grafana logs, verbatim: `uid=${ds_prometheus} ... error=\"invalid UID\"` then
       `Error querying datasources for public dashboard error=\"data source not found\"` → HTTP 500."
    - "Local Grafana 13.2.1 with the repo's real provisioning reproduces the identical 500 on the
       identical panel ids (323, 15)."
    - "Pinning the datasource uid alone flips 500 → 200, and the recording stub shows the PromQL
       that arrives is `instance=\"$node\",job=\"$job\"` — literal, uninterpolated."
    - "Pinning each variable's saved `current`/`options` and setting refresh:0 changed nothing —
       the same literal `$node` still reached the datasource. Variables are never interpolated."
  falsification_test: "If the public backend did interpolate query variables, the recording stub
    would have logged `instance=\"node-exporter:9100\"`. It logged `instance=\"$node\"` in both the
    unset-current and saved-current runs. Hypothesis survived."
  fix_rationale: "Pin every datasource ref to the UID Grafana ALREADY assigned
    (PBFA97CFB590B2093), and replace each template variable in every query with the single
    concrete value it can resolve to on this one-host deployment. This addresses both conditions
    at their source rather than re-sharing the dashboards (which would change nothing)."
  blind_spots: "Panels may still be empty for reasons unrelated to this defect (metric renames,
    missing exporters). Verified per-panel after the fix rather than assumed."
  candidate_causes:
    - "config: datasource UID unresolvable in dashboard JSON (`${ds_prometheus}` / `\"Prometheus\"`)"
    - "config/product-constraint: public dashboards do not interpolate template variables, and
       100% of panel queries depend on them"
    - "environment: eliminated — all exporters healthy, all Prometheus targets up=1"
  and_gate: "YES. Fixing only the datasource ref changes the symptom from 'Datasource was not
    found' to 'No data' — measured, not assumed (STEP1 probe returned HTTP 200 with empty frames).
    Both conditions must be fixed for a panel to render."

## Evidence

- timestamp: 2026-09-12T07:10Z
  checked: `docker/grafana/provisioning/datasources/datasources.yaml`
  found: Two datasources (Prometheus, Loki), neither declares a `uid:` field.
  implication: Grafana generates a RANDOM uid at first provision; nothing in git pins it.

- timestamp: 2026-09-12T07:12Z
  checked: datasource refs inside the three committed dashboard JSONs
  found: node-exporter-full.json ("VM Host Metrics", uid rYdddlPWk) — 127 refs to
    `{"type":"prometheus","uid":"${ds_prometheus}"}`; cadvisor.json ("CPU/Memory & Network Usage -
    cAdvisor", uid pMEd7m0Mz) — 12 refs to the bare string `"Prometheus"`; postgres-exporter.json
    ("Postgres Internals", uid v5ciIbUZz) — 43 refs to the bare string `"Prometheus"`.
  implication: No dashboard references a datasource by real UID. Two distinct unresolvable forms.

- timestamp: 2026-09-12T07:14Z
  checked: `GET /api/public/dashboards/{token}` for both README links (live, unauthenticated)
  found: Both return 200 with the dashboard JSON; the served JSON carries the SAME unresolvable
    datasource refs. `templating.list` for VM Host Metrics includes `ds_prometheus` (type
    `datasource`) with `current.value` = null.
  implication: The public share itself is healthy; the shell renders because the layout resolves.
    Only the per-panel query path fails.

- timestamp: 2026-09-12T07:16Z
  checked: `POST /api/public/dashboards/{token}/panels/{id}/query` on both links + Grafana container
    logs on netcup-prod (grafana/grafana:13.2.1)
  found: Both return HTTP 500 `{"message":"Internal Server Error"}`. Grafana logs, verbatim:
    `logger=datasources level=warn msg="Invalid datasource uid ..." uid=${ds_prometheus}
     action=read name= error="invalid UID"`
    `logger=publicdashboards.service level=error msg="Error querying datasources for public
     dashboard" error="data source not found" datasources=[public-ds]`
    The cAdvisor request logs only the second line (no "invalid UID" warning) — its `"Prometheus"`
    name string is syntactically a valid uid, it just matches no datasource.
  implication: ROOT CAUSE DIRECTLY OBSERVED. Two mechanisms, one class: a template-variable ref and
    a legacy name ref, neither resolvable by the UID-only public-dashboard lookup.

- timestamp: 2026-09-12T07:30Z
  checked: Built a local repro harness (.debug-grafana-probe/) — grafana/grafana:13.2.1 with the
    repo's real provisioning mounted, its Prometheus datasource pointed at a recording HTTP stub
    that logs the verbatim PromQL and returns a valid empty result. A real Prometheus cannot
    distinguish an uninterpolated query from a correct query matching nothing; the stub can.
  found: The harness reproduces production exactly — HTTP 500 on panel 323 (VM Host Metrics) and
    panel 15 (cAdvisor), with NO query reaching the datasource.
  implication: Faithful minimal reproduction, off production. Safe to test fixes against.

- timestamp: 2026-09-12T07:33Z
  checked: STEP1 — pinned the datasource uid in datasources.yaml and rewrote all 182 dashboard
    datasource refs to that uid, changing nothing else.
  found: Both panels flipped HTTP 500 → HTTP 200. The PromQL reaching the stub was verbatim
    `rate(node_pressure_cpu_waiting_seconds_total{instance="$node",job="$job"}[1m15s])` and
    `sum(rate(container_cpu_usage_seconds_total{instance=~"$host",name=~"$container",...}[5m]))`.
  implication: SECOND BLOCKER PROVEN. `$__rate_interval` interpolated (→ `1m15s`) but `$node`,
    `$job`, `$host`, `$container` did NOT. Fixing the datasource ref alone converts the symptom
    from "Datasource was not found" to a silent "No data" — it is not a fix.

- timestamp: 2026-09-12T07:35Z
  checked: STEP2 — additionally gave every query variable a saved `current`/`options` value and set
    `refresh: 0`, testing whether a saved value is interpolated server-side (the cheap fix).
  found: Identical literal `$node`/`$job`/`$host`/`$container` reached the datasource.
  implication: The cheap fix is dead. Grafana's public-dashboard backend never interpolates
    dashboard variables regardless of saved value. Queries must be rewritten to concrete values.
    Corroborated by grafana/grafana#67346 (template-variable support still open).

- timestamp: 2026-09-12T07:38Z
  checked: MIGRATION TEST — booted Grafana on a PERSISTENT volume with the old provisioning (so
    "Prometheus" already exists with its auto-assigned uid), then re-provisioned with a NEW uid
    (`uid: prometheus`) on the same volume, simulating deploying that fix to production.
  found: Grafana FAILED TO START, exit code 1:
    `logger=provisioning level=error msg="Failed to provision data sources" error="Datasource
     provisioning error: data source not found"` →
    `Error: ✗ invalid service state: Failed ... failure: Datasource provisioning error`
  implication: LANDMINE AVOIDED. Assigning a *new* uid to an already-provisioned datasource
    hard-fails startup. With `restart: unless-stopped` in docker-compose.prod.yml this would have
    crash-looped Grafana and taken the whole monitoring stack down. A fresh-container test would
    never have revealed this.

- timestamp: 2026-09-12T07:41Z
  checked: Production's real datasource uids, via the Grafana admin API inside the container.
  found: `Prometheus uid=PBFA97CFB590B2093 isDefault=True`, `Loki uid=P8E80F9AEF21F6940` — byte for
    byte identical to the uids my FRESH local container generated from the same YAML.
  implication: Grafana derives the uid deterministically from an unpinned provisioned datasource.
    Independently confirms UID drift was never possible here, AND supplies a zero-migration fix:
    pin the dashboards to the uid Grafana already assigned rather than inventing a new one.

- timestamp: 2026-09-12T07:43Z
  checked: Re-ran the migration test pinning `uid: PBFA97CFB590B2093` (the value already in the DB)
    on the same persistent volume.
  found: Container state `running exit=0`; both datasources present with unchanged uids; no
    provisioning errors beyond the repo's known-benign missing plugins/alerting directories.
  implication: Fix design settled — pin to the EXISTING uid. No migration, no restart risk.

- timestamp: 2026-09-12T07:41Z
  checked: `GET /api/dashboards/public-dashboards` on production (admin).
  found: THREE public dashboards are enabled, not two: VM Host Metrics (7db6…), cAdvisor (5a72…)
    and **Postgres Internals** (token 8939df8f9d0d4c358f9a0fd422d1f385), which the README does not
    link. All three share the same defect.
  implication: Scope is three dashboards. A publicly-reachable dashboard absent from the README is
    also worth surfacing to the owner on its own merits.

- timestamp: 2026-09-12T07:13Z
  checked: `docker ps` on netcup-prod
  found: grafana, prometheus, loki, node-exporter, cadvisor, postgres-exporter, promtail all Up and
    healthy.
  implication: The collection stack is fine; this is purely a dashboard-reference defect.

## Eliminated

- hypothesis: Datasource UID drift — a previously-correct UID in the dashboard JSON stopped
    matching after the datasource was re-provisioned with a new random UID.
  evidence: Neither dashboard JSON has ever contained a concrete UID (git history of
    `docker/grafana/provisioning/dashboards/json/` shows the refs as `${ds_prometheus}` /
    `"Prometheus"` since the initial 21a41c7 commit). There is no stored UID to have drifted.
  timestamp: 2026-09-12T07:16Z

- hypothesis: The public share links are stale/revoked, or Caddy is not routing them.
  evidence: `GET /api/public/dashboards/{token}` returns 200 with full dashboard JSON for both
    tokens over the public HTTPS hostname.
  timestamp: 2026-09-12T07:14Z

- hypothesis: Prometheus is down or scraping nothing, so panels are legitimately empty.
  evidence: The panel query fails at datasource RESOLUTION with HTTP 500 before any query is
    issued; all exporter containers are healthy.
  timestamp: 2026-09-12T07:16Z

## Resolution

root_cause: |
  Two independently fatal conditions, BOTH required for a panel to render on a Grafana public
  dashboard, neither of which the three provisioned dashboards satisfied:
  (1) The panel's datasource ref was not a resolvable UID — `${ds_prometheus}` (a datasource
      template variable) in node-exporter-full.json, and the legacy name string "Prometheus" in
      cadvisor.json and postgres-exporter.json. Grafana's public-dashboard query backend resolves
      datasources by UID only → `data source not found` → HTTP 500 → the "Datasource was not
      found" tooltip.
  (2) 100% of panel queries depended on dashboard template variables ($node/$job/$host/
      $container/$Instance/$Database/$Interval). The public backend interpolates built-in macros
      ($__rate_interval) but NEVER dashboard variables, regardless of any saved `current` value,
      so each query would reach Prometheus with literal "$node" text and silently match nothing.
  NOT the textbook cause the symptom suggests: there was no datasource UID drift. No dashboard
  ever held a concrete UID, and Grafana derives an unpinned provisioned datasource's UID
  deterministically from its name (a fresh container produced byte-identical UIDs to production's).

fix: |
  - datasources.yaml: pinned `uid: PBFA97CFB590B2093` (Prometheus) and `uid: P8E80F9AEF21F6940`
    (Loki) — DELIBERATELY the values Grafana had already derived, with a decision-record comment.
    Measured: provisioning a DIFFERENT uid onto an existing datasource aborts Grafana startup with
    exit 1, which under `restart: unless-stopped` is a crash loop. Same-uid re-provision is clean.
  - All 182 dashboard datasource refs repointed to that literal uid.
  - All 335 query/interval fields flattened to the concrete values read off live production
    Prometheus; 2 title/description labels flattened to readable equivalents; 9 now-dead template
    variables removed.
  - Added scripts/verify-public-dashboards.py + -selftest.py, wired as a 4th job in
    .github/workflows/invariant-checks.yml.
  - Documented the incident in docs/INFRA_RUNBOOK.md.

verification:
  - signal: "Local reproduction (grafana/grafana:13.2.1 + repo provisioning + request-recording
      datasource stub) — BEFORE: HTTP 500, no query reaches the datasource. AFTER: HTTP 200 on all
      three dashboards with fully interpolated PromQL."
    result: pass
  - signal: "Both-directions gate proof — verify-public-dashboards.py against the pre-fix JSON at
      git HEAD (datasources.yaml held fixed so the dashboards are the only variable): exit 1, 1001
      violations. Against the fixed tree: exit 0."
    result: pass
  - signal: "Gate selftest — 17 engineered cases, each invariant demonstrated to fire and to stay
      quiet on clean input. Caught a real bug in the gate on first run (unhashable dict in a set
      membership test), which was fixed."
    result: pass
  - signal: "Live data check — all 323 distinct flattened queries run against production
      Prometheus: cAdvisor 6/6 and Postgres Internals 43/43 return data; VM Host Metrics 232/274."
    result: pass
  - signal: "Empty-panel discriminator — for every empty query, checked whether the metric exists
      at all vs. exists but is missed by the substituted labels. ZERO in the second category; all
      24 metrics are absent from this Prometheus (node_hwmon_*, node_systemd_*, node_processes_*,
      etc. — collectors not enabled / hardware a VPS lacks). Pre-existing upstream breadth."
    result: pass
  - signal: "Restart-safety — booted Grafana on a persistent volume already holding the old
      datasources, then re-provisioned with the fix. Container `running exit=0`, uids unchanged."
    result: pass
  - signal: "Production behaviour unverified end-to-end — the fix is committed but NOT pushed, so
      production still serves the broken dashboards. Confirming the live public links render is
      the human-verify step after deploy."
    result: deferred

files_changed:
  - docker/grafana/provisioning/datasources/datasources.yaml
  - docker/grafana/provisioning/dashboards/json/node-exporter-full.json
  - docker/grafana/provisioning/dashboards/json/cadvisor.json
  - docker/grafana/provisioning/dashboards/json/postgres-exporter.json
  - scripts/verify-public-dashboards.py
  - scripts/verify-public-dashboards-selftest.py
  - .github/workflows/invariant-checks.yml
  - docs/INFRA_RUNBOOK.md

open_items:
  - "Postgres Internals (token 8939df8f9d0d4c358f9a0fd422d1f385) is publicly shared but linked
     nowhere in the README. Fixed alongside the other two, but whether it SHOULD be public is a
     decision for the owner, not a debugging finding."
  - "Flattening removed the variable dropdowns for the logged-in admin too — notably $Interval on
     Postgres Internals, now fixed at 10m. Preserving both would mean separate public copies of all
     three dashboards, whose new uids would invalidate the README's existing share links."
