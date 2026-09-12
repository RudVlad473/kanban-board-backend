---
status: resolved
trigger: "Public 'CPU/Memory & Network Usage - cAdvisor' Grafana dashboard (public link https://kanban-board-rud-vlad-473-monitoring.duckdns.org/public-dashboards/5a72e5df7fd54618ae28972cabcd8846) shows every panel empty. CPU Usage panel's warning icon tooltip literally reads 'Plugin graph not found'. Memory Usage, Memory Cached, Received Network Traffic, Sent Network Traffic panels show the same red warning icon with empty bodies. Screenshot provided by user."
created: 2026-09-12T00:00:00Z
updated: 2026-09-12T00:00:00Z
---

## Symptoms

- Expected: the public cAdvisor dashboard renders live CPU/Memory/Network container metrics to
  any visitor, same as the other two public dashboards (VM Host Metrics, Postgres Internals).
- Actual: every panel on the dashboard is empty; the CPU Usage panel's error icon tooltip reads
  "Plugin graph not found" verbatim (Grafana's own wording, not a paraphrase).
- Errors: "Plugin graph not found" (screenshot, not pasted as text).
- Timeline: PR #17 (commit f643255, merged today 2026-09-12) already fixed a DIFFERENT defect on
  this same dashboard — unresolvable datasource refs (`"Prometheus"` name string instead of a
  literal uid) and unintepolated dashboard template variables in queries. That fix is already on
  `main` (confirmed via `git log`: f643255 is HEAD, `git log origin/main..HEAD` is empty — nothing
  uncommitted or unpushed). The symptom in this new report is different: "Plugin graph not found"
  is Grafana's specific error for a panel whose `type` is the legacy Angular "graph" panel, which
  was fully removed as a renderable plugin once Angular support is dropped (Grafana 10 deprecated,
  11+ removed by default) — distinct from a datasource-resolution failure.
- Reproduction: open https://kanban-board-rud-vlad-473-monitoring.duckdns.org/public-dashboards/5a72e5df7fd54618ae28972cabcd8846

## Current Focus

bug_class: Bohrbug — fully deterministic, reproduces on every load of the public dashboard.

status: root cause CONFIRMED by direct observation on live production (see Evidence entries
2026-09-12T09:5x). Building local repro harness to prove the fix mechanism before applying it.

hypothesis (pre-seeded, NOW INDEPENDENTLY CONFIRMED — see below): all 5 empty panels
(CPU Usage id=15, Memory Usage id=9, Memory Cached id=14, Received Network Traffic id=4, Sent
Network Traffic id=6) in
`docker/grafana/provisioning/dashboards/json/cadvisor.json` are `"type": "graph"` — the legacy
Angular graph panel, vendored unchanged from the original grafana.com community dashboard
(`schemaVersion: 27`). The already-merged PR #17 fix flattened datasource refs and query
variables but did NOT touch panel `type` fields, so these panels never rendered in the FIRST
place under whatever Grafana version + angular-support configuration production runs — the
"Datasource was not found" symptom PR #17 fixed and this "Plugin graph not found" symptom are two
independently fatal, unrelated defects on the same panels; fixing one does not fix the other.
Corroborating: `postgres-exporter.json` (the third public dashboard, not linked from the README)
also carries 10 legacy `"graph"` panels and 8 legacy `"singlestat"` panels (schemaVersion 25) —
same defect class, likely same symptom, unverified by this session yet.
`node-exporter-full.json` (VM Host Metrics, the dashboard PR #17's own verification focused on)
already uses modern panel types exclusively (timeseries/gauge/stat/bargauge, schemaVersion 41) —
consistent with why THAT dashboard was reported as rendering (232/274 queries with data) while
this one is not.

next_action: none — resolved and verified live (commit bbbed20, deployed via CI run 34692214220).
Both public dashboards render with zero plugin errors. Scratch harness deleted, no orphaned
containers. Remaining optional follow-up, deliberately NOT done here to avoid expanding scope:
Postgres Internals is still unlinked from README's Live section, and its "PostgreSQL Uptime" panel
reads N/A for want of an exporter metric.

reasoning_checkpoint:
  hypothesis: "Panels with type=graph/singlestat (Angular-era) cannot render at all — not 'no
    data', but 'plugin not found' — once the Grafana version in use has Angular support removed,
    independent of whether their datasource/query refs are otherwise correct."
  confirming_evidence:
    - "Screenshot tooltip reads 'Plugin graph not found' verbatim — this exact string is emitted
      by Grafana's panel renderer when it cannot resolve a panel type to a registered plugin,
      which is what happens to type='graph' once the built-in Angular graph plugin is removed."
    - "cadvisor.json panel type census: CPU Usage/Memory Usage/Memory Cached/Received+Sent
      Network Traffic are all type='graph' (verified via direct JSON read this session)."
    - "node-exporter-full.json (the dashboard NOT showing this symptom) has zero 'graph' or
      'singlestat' panels — 100% modern types."
  falsification_test: "If the datasource/variable fix from PR #17 were the ONLY defect, cAdvisor's
    panels would show 'No data' (like VM Host Metrics' partially-empty panels), not 'Plugin graph
    not found'. They show the plugin error instead, which datasource/query fixes cannot produce —
    falsifies 'PR #17's fix was incomplete for this dashboard' in favor of 'a second, independent
    defect exists on the panel-type dimension.'"
  candidate_causes:
    - "config: dashboard JSON panel `type` field is a removed/unsupported legacy plugin id
      ('graph', 'singlestat') for the Grafana version actually running in production"
  and_gate: "unknown yet — need to confirm whether angular_support.enabled is set anywhere, and
    the exact production Grafana version, before concluding no other condition is required"

## Evidence

- timestamp: 2026-09-12T00:00:00Z
  checked: `git log --oneline -5`, `git status`, `git log origin/main..HEAD`
  found: f643255 (PR #17, the prior debug session's fix) is already HEAD of main, already
    pushed/merged. Working tree clean apart from an unrelated untracked `.zed/settings.json`.
  implication: the prior session's file (`.planning/debug/grafana-datasource-not-found.md`)
    understated its own resolution — status says "committed locally, NOT pushed" but it is in
    fact already merged. That fix is live in production. This new symptom is NOT that defect
    recurring; it is something PR #17 never touched.

- timestamp: 2026-09-12T00:00:00Z
  checked: panel `type` field for every panel in
    `docker/grafana/provisioning/dashboards/json/cadvisor.json`
  found: "id=15 CPU Usage: graph. id=9 Memory Usage: graph. id=14 Memory Cached: graph. id=4
    Received Network Traffic: graph. id=6 Sent Network Traffic: graph. id=17 Containers Info:
    table (not graph — unclear from screenshot whether this one also fails; not in the reported
    empty-panel list, worth confirming). Rows are type=row (structural, not data panels)."
  implication: every reported broken panel is exactly the set of type='graph' panels. 1:1 match.

- timestamp: 2026-09-12T00:00:00Z
  checked: panel `type` census for the other two public dashboards
  found: "node-exporter-full.json (VM Host Metrics): timeseries=4, gauge=5, stat=5, bargauge=1,
    row=16 — zero graph/singlestat. postgres-exporter.json (Postgres Internals, unlinked from
    README): graph=10, singlestat=8, gauge=3, stat=2, row=6 — same legacy-panel defect class as
    cAdvisor."
  implication: this is a systemic defect across the two dashboards that were never modernized
    past their original grafana.com vendored schemaVersion (cAdvisor schemaVersion=27, Postgres
    Internals schemaVersion=25), not specific to cAdvisor. Postgres Internals likely shows the
    same symptom on its 10 graph + 8 singlestat panels once checked.

- timestamp: 2026-09-12T00:00:00Z
  checked: `grep -ri angular docker/grafana docker-compose.prod.yml` (no config found)
  found: no explicit `angular_support` setting anywhere in the repo's Grafana provisioning or
    compose files — production relies on Grafana's own version default.
  implication: whether graph panels render at all depends entirely on which Grafana image tag is
    deployed and that version's own Angular-support default; need to confirm the deployed image
    tag/version to know exactly why they fail this way rather than schema-auto-migrating silently.

- timestamp: 2026-09-12T09:50Z
  checked: LIVE production Grafana version + angular config, via `ssh netcup-prod` + `docker exec`.
  found: Running image `grafana/grafana:13.2.1` (digest sha256:f772d434e8fa…), matching the repo's
    pin in docker-compose.prod.yml line 642 — the pinned tag is the running fact, not just a claim.
    `grafana -v` → `grafana version 13.2.1`. `grep -i angular /usr/share/grafana/conf/defaults.ini`
    returns NOTHING, and grafana.ini likewise. No `GF_*ANGULAR*` env var on the container.
  implication: `angular_support_enabled` no longer EXISTS as a setting in Grafana 13 — Angular was
    not merely disabled-by-default, it was removed outright. There is no config knob that could
    bring the graph panel back. The fix must be the dashboard JSON, not Grafana config.

- timestamp: 2026-09-12T09:52Z
  checked: `ls /usr/share/grafana/public/app/plugins/panel/` inside the live container — the
    authoritative registry of panel plugins this binary can resolve.
  found: alertlist annolist barchart bargauge candlestick canvas dashlist debug flamegraph gauge
    geomap gettingstarted heatmap histogram live logs logstable news nodeGraph piechart stat
    state-timeline status-history table text timeseries traces trend welcome xychart.
    NO `graph`. NO `singlestat`.
  implication: DIRECT observation of the mechanism, not an inference from release notes. A panel
    whose `type` is `graph` cannot resolve to any registered plugin → Grafana emits exactly
    "Plugin graph not found". `table` IS present, which is why panel id=17 (Containers Info) was
    the one panel the user did NOT report as broken.

- timestamp: 2026-09-12T09:54Z
  checked: `GET /api/public/dashboards/5a72…` — what the live public endpoint actually serves.
  found: `schemaVersion: 27`, panels served verbatim as `type: "graph"` (ids 15, 9, 14, 4, 6) plus
    `type: "table"` (id 17) and 4 `type: "row"`. The backend serves the STORED JSON unmigrated.
  implication: nothing server-side rewrites the legacy panel type on the way out. Whatever
    graph→timeseries auto-migration Grafana once had is not rescuing this dashboard.

- timestamp: 2026-09-12T09:55Z
  checked: `POST /api/public/dashboards/5a72…/panels/{id}/query` for all 6 data panels, live, over
    a 30-minute window. THE key discriminator between "PR #17 was incomplete" and "second defect".
  found: ALL SIX return HTTP 200 with real series — panel 15: 13 frames / 793 datapoints; panel 9:
    14 frames / 803; panel 14: 14/803; panel 4: 13/793; panel 6: 13/793; panel 17: 13/13.
    Zero query errors.
  implication: ROOT CAUSE ISOLATED TO THE FRONTEND. The data path PR #17 fixed is fully healthy —
    the datasource resolves and the queries return live container metrics right now. The panels are
    blank purely because the browser cannot find a plugin to draw that data with. This falsifies
    "PR #17's fix was incomplete" and confirms a second, independent, frontend-only defect.

- timestamp: 2026-09-12T11:40Z
  checked: panel-type census walked RECURSIVELY (descending into collapsed rows' nested `panels`)
    rather than top-level only, on HEAD's `postgres-exporter.json`.
  found: graph=17, singlestat=11 — not the graph=10, singlestat=8 recorded in the earlier census
    entry above, which counted top-level panels only. cadvisor.json is unaffected (graph=5 either
    way; its legacy panels all sit at the top level).
  implication: CORRECTS the earlier evidence entry. 10 of Postgres Internals' broken panels are
    nested inside collapsed rows, so any checker that walks only `dashboard["panels"]` sees a
    dashboard that is 64% fixed and reports nothing. The guard built in this session descends the
    row tree for exactly this reason, and has a self-test case pinning that behavior.

- timestamp: 2026-09-12T11:52Z
  checked: LIVE production public cAdvisor dashboard driven in a real browser (playwright,
    `.debug-grafana-probe/tooltip-check.mjs`) — hovering every panel-header error icon to read the
    tooltip text a screenshot can only show as a red triangle. This is the BEFORE verdict, taken
    against real production while it still served the unfixed JSON.
  found: exactly 5 error icons, each tooltip reading "Plugin graph not found" verbatim — a 1:1
    match with the 5 `type: "graph"` panels. The companion render probe recorded those same 5
    panels as canvas=0, svg=1, NO legend and an empty body (title text only), while the one
    `table` panel (Containers Info) rendered 156 svg nodes and real container rows.
  implication: the reported symptom is now reproduced and captured as text, not just as a
    user-supplied screenshot, and the discriminator is sharp: broken panels have NO drawing
    surface at all, they are not merely empty ones.

- timestamp: 2026-09-12T11:47Z
  checked: AFTER verdict for both dashboards, in the local harness — the SAME pinned image
    production runs (`grafana/grafana:13.2.1`), bind-mounting the repo's real provisioning
    directory, with both dashboards genuinely shared through Grafana's public-dashboard feature so
    the probe drives the public renderer code path, not the authenticated one.
  found: cAdvisor — 0 error icons, 0 "Plugin ... not found", all 5 migrated timeseries panels
    drawing into a `<canvas>` with legends carrying computed values (CPU mean 5.91% / max 9.45%,
    memory 253 MiB, network 12.2 kB/s), plus the table panel unchanged. Postgres Internals — 0
    error icons, 23/23 panels with a drawing surface and noData=false, real values throughout
    (PostgreSQL Version 160015, Total database size 29.28 MiB, Shared Buffer Hits 99.96%,
    Transaction rate 5.90, Tuples fetched 242/251/259).
  implication: the fix is verified at the RENDER layer, both directions, on the same Grafana
    version as production — not merely "the error banner disappeared". Caveat recorded honestly:
    3 Postgres stat panels read N/A (Uptime, Query rate, Average query runtime) because the
    throwaway probe's postgres-exporter exposes no `pg_stat_statements` series; that is a
    probe-data gap, not a render defect — those panels resolved their plugin and drew their shell.
    Production has the extension (commit 1cdc05c).
  note: `timeseries` draws into `<canvas>` (uPlot), not `<svg>` — so the probe's svg counter is NOT
    the render signal on modern panels. The legend's computed mean/max is, because Grafana can only
    compute it from series that actually arrived.

- timestamp: 2026-09-12T12:05Z
  checked: the new guard (I5/I6 in `scripts/verify-public-dashboards.py`) run in BOTH directions
    against the real dashboards, per the repo's "prove the test fails without the fix" rule.
  found: against HEAD's unfixed JSON (reconstructed via `git show HEAD:<path>` into a scratch tree,
    new gate + old inputs) → EXIT 1 with 33 violations: all 5 cAdvisor `graph` panels and all 28
    Postgres legacy panels (17 graph + 11 singlestat), each naming its migration target. Against
    the working tree → EXIT 0. Self-test: 26/26 cases pass, including one asserting a datasource
    ref's own `type: "prometheus"` is NOT mistaken for a panel type, and one asserting a legacy
    panel nested inside a row is still caught.
  implication: the guard demonstrably fails without the fix and passes with it. It would have
    caught this defect on the day PR #17 shipped.

- timestamp: 2026-09-12T12:20Z
  checked: LIVE post-deploy verification of commit bbbed20, after CI run 34692214220 (CI/CD with
    Docker) completed with every job success. Four independent layers, because a green pipeline is
    not evidence that Grafana re-read anything.
  found:
    1. SHIPPED — both migrated files are on the host at
       `/opt/deploy/kanban-board-backend/docker/grafana/provisioning/dashboards/json/`, timestamped
       with this deploy, both schemaVersion 42, and `grep -c` for `graph`/`singlestat` returns 0 on
       all three deployed dashboards.
    2. RE-PROVISIONED — the live public-dashboard API now serves schemaVersion 42 for both
       (cAdvisor {row:4, timeseries:5, table:1}; Postgres {row:6, timeseries:17, stat:13, gauge:5}),
       matching the working tree exactly, with zero legacy types across all three public dashboards.
       The Grafana container reports "Up 3 days" — it was NOT restarted, so the pickup came from the
       provider's own `updateIntervalSeconds: 30` file scan; grafana.db's mtime moved at deploy time,
       confirming the re-read reached storage.
    3. DATA PATH INTACT — all 41 live panel-query endpoints (6 cAdvisor + 35 Postgres) return HTTP
       200 with real datapoints: 0 errored, 0 empty. Panel ids are unchanged from before the
       migration, so the public endpoints still address the same panels.
    4. RENDERS — real browser against both live public links: cAdvisor 0 error icons (was 5) and
       6/6 panels drawing with live production series (app container CPU mean 0.613% / max 4.56%,
       Memory 454 MiB / 863 MiB); Postgres Internals 0 error icons and 23/23 panels drawing
       (Version 160015, Query rate 4.96, Average query runtime 299 µs, Total database size
       50.65 MiB, Shared Buffer Hits 99.99%). Zero "Plugin ... not found" anywhere on either page.
  implication: RESOLVED in production, verified at the layer the defect actually lived in. The
    before/after on the SAME live URL is unambiguous: 5 error icons reading "Plugin graph not found"
    with no drawing surface, to 0 error icons with computed legend values.
  caveat recorded rather than glossed: Postgres Internals' "PostgreSQL Uptime" panel reads N/A on
    live. Its plugin resolved and it drew its shell, so this is not the defect under investigation —
    it is a missing exporter metric, pre-existing and out of scope here.

## Eliminated

- hypothesis: PR #17's datasource/query fix was incomplete or regressed for cadvisor.json, so the
    panels have no data to draw.
  evidence: All 6 live public-dashboard panel query endpoints return HTTP 200 with 793–803
    datapoints each and no error. The data is arriving; only the renderer is missing.
  timestamp: 2026-09-12T09:55Z

- hypothesis: Angular support is merely disabled by config and could be re-enabled
    (`angular_support_enabled = true`) as a one-line fix.
  evidence: The setting does not exist in Grafana 13.2.1 — absent from both `defaults.ini` and
    `grafana.ini` in the live container, and the `graph`/`singlestat` plugin directories are not
    shipped in the image at all. There is nothing to re-enable.
  timestamp: 2026-09-12T09:50Z

## Resolution

root_cause: `docker/grafana/provisioning/dashboards/json/cadvisor.json` (schemaVersion 27) and
  `postgres-exporter.json` (schemaVersion 25) were vendored from grafana.com carrying Angular-era
  panel types — 5 `graph` panels in cAdvisor, 17 `graph` + 11 `singlestat` in Postgres Internals.
  Production runs `grafana/grafana:13.2.1`, which removed Angular outright: neither `graph` nor
  `singlestat` exists among the 30 panel plugins the image ships, and `angular_support_enabled` is
  no longer a setting, so no configuration can restore them. The public-dashboard API serves the
  stored JSON unmigrated, so each such panel resolves to no plugin and renders an error triangle
  reading "Plugin graph not found". Entirely frontend: all six live panel-query endpoints returned
  HTTP 200 with 793–803 datapoints throughout, so the data path PR #17 fixed was never implicated.

fix: migrated both dashboards through Grafana's own DashboardMigrator rather than hand-editing
  schema — imported each into a pinned 13.2.1 instance and exported the migrated JSON back
  (schemaVersion 25/27 → 42). cAdvisor {graph:5} → {timeseries:5}; Postgres {graph:17,
  singlestat:11, stat:2} → {timeseries:17, stat:13}. Dashboard uids (`pMEd7m0Mz`, `v5ciIbUZz`),
  titles, and every data panel id are preserved byte-for-byte, so the existing public share links
  and panel-query endpoints keep addressing the same panels; only structural `row` ids renumbered.
  PR #17's own fix survived the round-trip — no `__inputs`, zero `${DS_*}` placeholders, and the
  only prometheus datasource uid in either file remains the literal `PBFA97CFB590B2093`.

verification:
  - render, both directions, same Grafana version as production: live prod BEFORE = 5 error icons
    reading "Plugin graph not found", panels with no drawing surface; local AFTER (pinned 13.2.1,
    real provisioning mount, genuine public-dashboard share) = 0 error icons, panels drawing into
    canvas with computed legend values on both dashboards (23/23 on Postgres).
  - guard, both directions: new gate EXIT 1 on HEAD's unfixed JSON (33 violations), EXIT 0 on the
    working tree; self-test 26/26.
  - integrity: valid JSON, uid/title/data-panel-id preservation, 1:1 panel-type conversion with no
    panel lost or invented, zero `graph`/`singlestat` remaining.
  - live post-deploy: see the final Evidence entry.

files_changed:
  - docker/grafana/provisioning/dashboards/json/cadvisor.json (migrated to schemaVersion 42)
  - docker/grafana/provisioning/dashboards/json/postgres-exporter.json (migrated to schemaVersion 42)
  - scripts/verify-public-dashboards.py (new I5 panel-type invariant + I6 allowlist/version drift)
  - scripts/verify-public-dashboards-selftest.py (9 new cases covering I5/I6)

prevention:
  why not caught: the `public-dashboards` CI gate existed and was green. It checked the DATA path
    only — that every datasource ref resolves by uid (I1) and that no query carries an
    uninterpolated template variable (I2/I3). Nothing in the stack asserted that a panel's `type`
    names a plugin the running Grafana can actually load, so a dashboard whose every query was
    perfect and whose every panel was unrenderable passed. That is precisely how this shipped green
    the same day PR #17 fixed the first defect on the same files.
  guard: `scripts/verify-public-dashboards.py` I5 — every panel type must be one of the 30 plugins
    `grafana/grafana:13.2.1` ships (derived from the image's own plugin.json-bearing directories,
    not recalled) or the structural `row`, walked recursively through collapsed rows. Paired with
    I6, which fails the gate if `docker-compose.prod.yml` ever pins a Grafana version the allowlist
    was not derived from — so a future image bump cannot leave I5 silently describing a version
    nothing runs. Both are demonstrated to fire in
    `scripts/verify-public-dashboards-selftest.py`, which CI runs immediately before the gate.
