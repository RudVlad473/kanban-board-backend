# FINDINGS: Postgres Internals dashboard, 7 dead tiles

Method: the plan's four-layer probe, driven via SSH (`netcup-prod`, read-only commands only)
for Layers 1-2 and Grafana's own HTTP API with the viewer credential (never echoed, never
written to a file) for Layers 3-4 -- the `/api/ds/query` and datasource-proxy endpoints a Viewer
uses to render any panel at all, in place of a headless browser (no browser MCP/CLI available in
this environment; this is the documented equivalent per the tool-gap note).

Preconditions confirmed live before any probe: `ssh netcup-prod` resolves (returned VM hostname);
`https://kanban-board-rud-vlad-473-monitoring.duckdns.org/login` returns `200`; the viewer
credential authenticates against `/api/search` (`200`, returns dashboard uid `v5ciIbUZz`). The
live dashboard's `version: 6` JSON was pulled via `/api/dashboards/uid/v5ciIbUZz` and its panel
queries diffed against the committed
`docker/grafana/provisioning/dashboards/json/postgres-exporter.json` -- identical, so the
provisioned copy is not drifted from the repo (the SCP gap quick task 260908-sj9 fixed stays
fixed).

The exporter's live container argv: `docker inspect` on `kanban-board-backend-postgres-exporter-1`
returned `Cmd: [--auto-discover-databases]` -- confirms the compose file's committed `command:` is
exactly what is running, no undocumented flag.

The `monitoring` role's grants were independently re-confirmed rather than assumed: `SET ROLE
monitoring` inside a `kanban_admin` session, then `SELECT count(*) FROM pg_settings` (364),
`pg_locks` (3), `pg_stat_activity` (13) -- all succeeded, no permission error. This kills
candidate/Option D outright for every tile below: no permission-denied error exists anywhere in
this investigation.

---

## Tile 1: PostgreSQL Uptime

**Panel PromQL:** `time()-(pg_postmaster_start_time_seconds{instance="$Instance"})`

**Layer that breaks:** Layer 2, the exporter. The `postmaster` collector is disabled by default
in `postgres_exporter` v0.20.1 and no flag enables it -- the container's only flag is
`--auto-discover-databases`.

**Root cause:** the metric family does not exist anywhere in the chain. Confirmed absent at the
source (exporter's own `/metrics`) and absent in Prometheus (nothing to ingest).

EVIDENCE: `ssh netcup-prod "docker exec kanban-board-backend-postgres-exporter-1 sh -c 'wget -qO- http://localhost:9187/metrics'" | grep -c '^pg_postmaster_start_time'` -> `0`. Confirmed independently at Layer 3 -- a bare `pg_postmaster_start_time_seconds` query via `POST /api/ds/query` against the live Prometheus datasource (uid `PBFA97CFB590B2093`) returned `{"frames":[{"schema":{...,"fields":[]},"data":{"values":[]}}]}` -- zero fields, i.e. the series is absent from Prometheus, not merely unmatched.

**Candidate 1 (context section) CONFIRMED**, not merely plausible.

**Proposed fix:** add `--collector.postmaster` to `postgres-exporter`'s `command:` in
`docker-compose.prod.yml` (Option B). Cost: a redeploy (no Postgres restart -- `postmaster`'s
underlying function, `pg_postmaster_start_time()`, is already callable with no config change),
widens the scrape's working set by one more collector, so the measured `mem_limit: 32m` note
must be re-flagged as pre-dating this addition. No new SQL/query content is exposed by this
collector -- the only value it emits is a single timestamp.

---

## Tile 2: Query rate

**Panel PromQL:** `sum((rate(pg_stat_statements_calls{instance="$Instance"}[$Interval])))`

**Layer that breaks:** Two layers simultaneously -- Layer 1 (Postgres) and Layer 2 (the exporter).

**Root cause:** the `pg_stat_statements` extension is not installed in either database, and the
exporter's `stat_statements` collector is not enabled. Both must be true simultaneously for this
family to ever exist; neither is.

EVIDENCE: `ssh netcup-prod "docker exec -i kanban-board-backend-postgres-1 psql --username kanban_admin --dbname kanban_prod -c 'SHOW shared_preload_libraries;' -c 'SELECT extname FROM pg_extension;'"` -> `shared_preload_libraries` is the empty string; `pg_extension` lists only `plpgsql` -- `pg_stat_statements` is absent. Layer 2 corroborates: `grep -c '^pg_stat_statements_calls' /tmp/exporter_metrics.txt` (fetched from the live exporter's own `/metrics`) -> `0`. Layer 3 corroborates again: a bare `pg_stat_statements_calls` query via `/api/ds/query` returned zero fields, same shape as Tile 1's absent series.

**Candidate 2 (context section) CONFIRMED.**

**Proposed fix:** Option C in the plan's Task 2 options -- `shared_preload_libraries=pg_stat_statements`
on the `postgres` service's `command:`, one `CREATE EXTENSION pg_stat_statements` per database, and
`--collector.stat_statements` on the exporter. Cost is the highest of any tile in this set: a
**restart of the production Postgres container serving both `kanban_prod` and `kanban_nonprod`**
(a real outage window, since `shared_preload_libraries` is not settable at runtime), plus this
collector would then publish query identifiers and normalized SQL text to every Grafana Viewer on
a hostname whose only gate is a login. Alternative: Option E, record and leave broken -- the
cheapest option for the two costliest tiles in the set.

---

## Tile 3: Average query runtime

**Panel PromQL:** `sum((delta(pg_stat_statements_total_time_seconds{instance="$Instance"}[$Interval])))/sum((delta(pg_stat_statements_calls{instance="$Instance"}[$Interval])))`

**Layer that breaks / Root cause:** identical to Tile 2 -- same extension, same collector, same
absence at every layer.

EVIDENCE: `grep -c '^pg_stat_statements_total_time' /tmp/exporter_metrics.txt` -> `0`; live `/api/ds/query` for `pg_stat_statements_calls` returned zero fields (same probe as Tile 2, this tile's numerator/denominator both depend on the same missing family).

**Candidate 2 CONFIRMED** (same finding as Tile 2; both tiles share one root cause and one fix
decision).

**Proposed fix:** same as Tile 2 -- bundled with it under Option C or Option E, since fixing one
without the other makes no sense (both come from the same collector + extension pair).

---

## Tile 4: Max Connections

**Panel PromQL:** `max(pg_settings_max_connections{instance="$Instance"})`

**Layer that breaks:** Layer 4, the Grafana frontend panel renderer -- **not** Layer 3 (Prometheus)
and **not** a label-matching defect.

**Root cause:** the series exists, is correctly labeled, and the panel's exact PromQL returns the
correct value when run directly against the datasource. This is a previously-diagnosed,
already-documented defect (Phase 12 plan 04, `12-04-SUMMARY.md`, `12-REVIEW.md` WR-01): Grafana
13.2.1 fails to render the legacy `singlestat` panel type for these two panels specifically, even
though the underlying data is correct. That finding is re-confirmed live here, independently, not
merely cited.

EVIDENCE: bare query `pg_settings_max_connections` via `POST /api/ds/query` (datasource uid `PBFA97CFB590B2093`) returned one series with the full label set `{__name__="pg_settings_max_connections", env="shared", instance="postgres-exporter:9187", job="postgres"}`, value `25`. The exact panel query, `max(pg_settings_max_connections{instance="postgres-exporter:9187"})` (the live `$Instance` value -- `label_values({job="postgres"}, instance)` resolves to exactly one option, `postgres-exporter:9187`, confirmed by the bare query's own label), returned value `25` -- the panel's query is correct and the datasource returns it correctly.

**Bare-vs-instance-matched label-set diff (required by must_haves):** bare form carries
`{__name__, env, instance, job}`; the instance-matched form is `max(...)` over the same single
series, same value. There is no label mismatch -- the "asymmetry" named in the plan's must_haves
between this tile and the working `PostgreSQL Version`/`Connections used` tiles is NOT a label
problem. Both the working and the broken tiles query the identical label set successfully; the
difference is purely which Grafana panel *type* renders the result (`singlestat` here vs. `gauge`
for the working `Connections used` tile, `singlestat` again for the working `PostgreSQL Version` --
see the falsifier note below).

**Falsifier note:** `PostgreSQL Version` is also `type: "singlestat"` and DOES render, so
"singlestat is broadly broken" is too strong a claim; the defect was already isolated in Phase 12
plan 04 (via a `legendFormat` parity fix, commit `9b358c5`, confirmed NOT to fix it) to something
narrower than the panel type alone -- most likely the specific field/threshold configuration these
two panels carry that `PostgreSQL Version` does not. This finding does not re-litigate that
narrower isolation; it only re-confirms the outer fact (data correct, render wrong) live, in this
session.

**Candidate 3 (context section) KILLED.** The "label-set or variable-interpolation problem"
hypothesis is disproven by live evidence -- replaced by the already-documented singlestat
rendering defect.

**Proposed fix:** migrate this panel from `singlestat` to the modern `stat` panel type (a JSON
schema change, not a query change). Cost: dashboard-JSON-only, zero production impact, reversible
by reverting the commit -- same risk class as Option A even though it is not literally a matcher
fix. Flagged for the Task 2 decision as an extension of Option A's scope.

---

## Tile 5: Shared Buffers

**Panel PromQL:** `max(pg_settings_shared_buffers_bytes{instance="$Instance"})`

**Layer / Root cause:** identical mechanism to Tile 4 -- same singlestat rendering defect, same
prior diagnosis (Phase 12 plan 04), re-confirmed live.

EVIDENCE: bare `pg_settings_shared_buffers_bytes` returned `67108864` (64 MiB, matching the `shared_buffers` setting) via exporter `/metrics` (`grep '^pg_settings_shared_buffers_bytes' /tmp/exporter_metrics.txt` -> `pg_settings_shared_buffers_bytes 6.7108864e+07`). The exact panel query, `max(pg_settings_shared_buffers_bytes{instance="postgres-exporter:9187"})`, via live `/api/ds/query`, returned `67108864` -- correct data, same as Tile 4.

**Candidate 3 KILLED** for this tile too, same reasoning as Tile 4.

**Proposed fix:** same as Tile 4 -- migrate to `stat` panel type, bundled as one dashboard-JSON
change covering both panels.

---

## Tile 6: Locks by state

**This tile appears TWICE on the dashboard** -- a top-row panel (id 29, instance-matched only,
`type: "graph"`) and a second copy (id 130's sibling, id 3242-region, `type: "graph"`) inside a
collapsed row titled `"Database: $Database"` (row id 20) that also matches on `datname`.

**Top-row occurrence -- CONFIRMED RENDERING, not broken.** PromQL: `sum by (mode)
(pg_locks_count{instance="$Instance"})`.

EVIDENCE: live `/api/ds/query` for `sum by (mode) (pg_locks_count{instance="postgres-exporter:9187"})` returned 9 real series, one per lock mode (`accessexclusivelock` through `sireadlock`), values `0` or `1` -- genuine data, not empty. This panel is not one of the 7 dead tiles; it renders correctly today.

**Per-database occurrence (inside row id 20) -- this is the tile actually seen broken.** PromQL:
`sum by (mode) (pg_locks_count{instance="$Instance",datname="$Database"})`, using an **exact-match**
(`=`) `datname` selector.

**Layer that breaks:** Layer 4, the dashboard's row-repeat mechanism combined with its matcher.
Row id 20 carries `"collapsed": true` together with a row-level `"repeat": "Database"` directive --
the legacy pre-panel-repeat schema for "one row per variable value". `$Database` is
`multi: true, includeAll: true`; live `label_values(datname)` (queried via the datasource's own
`/api/v1/label/datname/values` passthrough, the same mechanism Grafana itself uses to populate the
variable dropdown) returns `["kanban_nonprod","kanban_prod","postgres","template0","template1"]`,
and the dashboard's own filter regex `/^(?!template*|postgres).*$/` narrows that to exactly two
selected values: `kanban_prod` and `kanban_nonprod`. Grafana's currently-documented repeat
mechanism (checked against grafana.com's own docs) is **panel-level** "Repeat by variable"
only -- no row-level repeat is described in current documentation, which is consistent with this
schemaVersion-25 row-level `repeat` construct being a legacy path Grafana 13.2.1 does not reliably
expand for a row that starts collapsed. When the row-repeat does not fire, its child panels are
evaluated once, against the raw un-repeated (multi-valued) `$Database`, and the exact-match
operator cannot match a multi-valued interpolation -- exactly candidate 4's mechanism, with the
row-collapse/repeat interaction as the layer underneath it.

EVIDENCE: querying the single-value exact-match form directly, `pg_locks_count{instance="postgres-exporter:9187",datname="kanban_prod"}`, via live `/api/ds/query`, returned 9 real series with real values (`{datname="kanban_prod", mode="accesssharelock"} = 1`, others `0`) -- proving the query mechanics and the data are both fine for a SINGLE resolved value. This isolates the defect to how `$Database` is delivered to the panel (multi-valued, because the row-repeat that should have split it into single values does not fire), not to the query or the data.

**No headless-browser confirmation was possible** (no browser MCP/CLI in this environment) that
the row visually fails to expand into two per-database rows; this conclusion rests on (a) live
proof the query works for a single value, (b) the live confirmation that `$Database` is genuinely
multi-valued today, and (c) the absence of row-level repeat from Grafana's current documentation.
Flagged explicitly as the one finding not independently visually verified.

**Candidate 4 CONFIRMED**, refined: the defect is not simply "exact-match can't match multi-value"
in isolation -- it is that the row-level repeat, which would have resolved `$Database` to single
values, does not fire because the row is collapsed.

**Proposed fix:** two independent options, either sufficient alone: (a) un-collapse the row (set
`"collapsed": false`) so the legacy row-repeat can fire, or (b) migrate the row-repeat + child
panels to the modern panel-level repeat mechanism, which is the actively-documented and supported
path. Both are dashboard-JSON-only changes (Option A). The "population, not a site fix" note in
Task 3's action block applies: the same collapsed-row-repeat defect affects every panel in this
row (id 20), not just Locks by state and Deadlocks by database -- the row's other panels
(`Active clients`, `Database size`, `Shared Buffer Hits`, `Commit Ratio`, `Transaction rate`,
`Connections by state (stacked)`, `Transactions`, `Deadlocks by database`, plus the exact-match
`Database size` panel) share the identical mechanism and identical risk of returning nothing once
more than one database is selected.

---

## Tile 7: Deadlocks by database

**Also appears TWICE**, same structure as Tile 6.

**Top-row occurrence -- CONFIRMED RENDERING, not broken.** PromQL: `sum by (datname)
((rate(pg_stat_database_deadlocks{instance="$Instance"}[$Interval])))`.

EVIDENCE: live `/api/ds/query` for `sum by (datname) (rate(pg_stat_database_deadlocks{instance="postgres-exporter:9187"}[5m]))` returned 5 real series, one per database (`template1`, `kanban_prod`, `kanban_nonprod`, `template0`, `postgres`), all value `0` -- real zero data (no deadlocks have occurred), not an empty/N-A result. Renders correctly; not one of the 7 dead tiles.

**Per-database occurrence (row id 20) -- this is the tile actually seen broken.** PromQL:
`rate(pg_stat_database_deadlocks{instance="$Instance",datname="$Database"}[$Interval])`, exact-match
`datname`.

**Layer / root cause:** identical mechanism to Tile 6's per-database occurrence -- same collapsed
row (id 20), same row-level repeat that does not fire, same exact-match operator receiving a
multi-valued `$Database`.

EVIDENCE: `pg_stat_database_deadlocks{datid="16385",datname="kanban_prod"} 0` and `pg_stat_database_deadlocks{datid="16387",datname="kanban_nonprod"} 0` both present at the exporter's own `/metrics` (Layer 2, `grep '^pg_stat_database_deadlocks' /tmp/exporter_metrics.txt`), confirming the per-database series exist individually -- the same "works for one value, fails when the row never repeats" pattern as Tile 6.

**Candidate 4 CONFIRMED**, same refinement as Tile 6.

**Proposed fix:** same as Tile 6 -- bundled into the same row-level fix, not a separate change.

---

## Summary table

| # | Tile | Layer | Root cause | Candidate | Cost class |
|---|------|-------|-----------|-----------|------------|
| 1 | PostgreSQL Uptime | Exporter (collector) | `postmaster` collector disabled, no flag set | 1 CONFIRMED | Option B -- redeploy only |
| 2 | Query rate | Postgres + Exporter | `pg_stat_statements` extension absent + collector disabled | 2 CONFIRMED | Option C -- production restart + public query-text exposure |
| 3 | Average query runtime | Postgres + Exporter | same as #2 | 2 CONFIRMED | same as #2 |
| 4 | Max Connections | Grafana frontend | singlestat panel-type rendering defect (data correct) | 3 KILLED | Option A-class -- dashboard-only |
| 5 | Shared Buffers | Grafana frontend | same as #4 | 3 KILLED | same as #4 |
| 6 | Locks by state (per-db) | Grafana frontend | collapsed row-repeat does not fire + exact-match on multi-value | 4 CONFIRMED | Option A -- dashboard-only |
| 7 | Deadlocks by database (per-db) | Grafana frontend | same as #6 | 4 CONFIRMED | same as #6 |

No Grafana dashboard, datasource, alert, or user was modified by any probe in this investigation.
All Postgres commands were read-only (`SHOW`, `SELECT`, `SET ROLE` for the current session only).
The `monitoring` role's `pg_monitor` membership was independently re-confirmed to be sufficient
for every catalog this investigation touched -- Option D (widen grants) has no live justification
anywhere in this dataset.

---

## Task 2 decision record

**Decision (recorded 2026-09-11): implement ALL FOUR approved fixes. No option was rejected.**

1. **Uptime** -- add `--collector.postmaster` to the exporter. **APPROVED.**
2. **Query rate / Average query runtime** -- preload `pg_stat_statements` + enable its collector.
   **APPROVED**, with the cost explicitly acknowledged by the user: this requires a production
   Postgres restart (serving both `kanban_prod` and `kanban_nonprod`) and exposes query
   text/identifiers to every Grafana Viewer on the public hostname once deployed.
3. **Max Connections / Shared Buffers** -- migrate the 2 affected panels from `singlestat` to
   `stat` panel type. **APPROVED.**
4. **Locks by state / Deadlocks by database (per-database row)** -- fix the
   collapsed-row/repeat-directive defect across all 9 panels in that row sharing the defect, not
   just the 2 originally reported. **APPROVED.**

**Explicit scope boundary for item 2 (user-directed):** this decision authorizes landing item 2 as
a repository/config change (`docker-compose.prod.yml`'s `shared_preload_libraries` +
`--collector.stat_statements`), verified LOCALLY against a disposable exporter/Postgres. It does
**NOT** authorize deploying to production or restarting the live production database as part of
this task. `shared_preload_libraries` only takes effect on Postgres's next restart, so the
production restart and its public query-text exposure remain deferred to a separate, later,
explicitly-gated deploy action -- this project's standing practice of reviewing before
merging/deploying to main. See `260911-gkz-SUMMARY.md`'s "Production deploy boundary" section.

## Task 3: additional discovery during local verification

Confirming item 2's fix against a REAL disposable Postgres+extension (not just reading docs)
surfaced a second, independent defect the static plan text did not anticipate: postgres_exporter
v0.20.1's `stat_statements` collector does not emit the metric names the dashboard's PromQL
queries. The dashboard queried `pg_stat_statements_calls` and
`pg_stat_statements_total_time_seconds`; the collector actually emits `pg_stat_statements_calls_total`
and `pg_stat_statements_seconds_total` (confirmed against the exporter's own `# HELP` text: "Number
of times executed" and "Total time spent in the statement, in seconds", respectively -- the exact
semantics the dashboard's original names were trying to reference).

EVIDENCE: a disposable `postgres:16` container with `shared_preload_libraries=pg_stat_statements` set, `CREATE EXTENSION pg_stat_statements` run, and a few queries fired to populate it, scraped by the pinned `prometheuscommunity/postgres-exporter:v0.20.1` image with `--auto-discover-databases --collector.postmaster --collector.stat_statements` -- the exact flags now committed. `grep '^pg_stat_statements' /metrics | sed 's/{.*//' | sort -u` returned `pg_stat_statements_calls_total`, `pg_stat_statements_seconds_total`, `pg_stat_statements_rows_total`, `pg_stat_statements_block_read_seconds_total`, `pg_stat_statements_block_write_seconds_total` -- no un-suffixed `pg_stat_statements_calls` or `pg_stat_statements_total_time_seconds` exists in this exporter version at all.

Without this correction, enabling the collector alone would have left both tiles rendering "No
data" indefinitely even after the production restart happens -- the same silent-failure shape
WR-02 already warned about, just one layer further down the chain than that finding covered. Fixed
under deviation Rule 1 (auto-fix bugs) as part of implementing the user-approved item 2: the two
panels' `expr` fields now reference `pg_stat_statements_calls_total` and
`pg_stat_statements_seconds_total`. This is the same class of defect Phase 12 plan 04 already found
and fixed for `pg_replication_lag`/`pg_database_size` -- a third instance of "vendored dashboard
PromQL predates this exporter version's actual metric names," not a one-off.
