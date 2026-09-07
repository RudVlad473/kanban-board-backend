---
phase: 12-self-hosted-observability-stack
plan: 04
subsystem: infra
tags: [grafana, dashboards-as-code, prometheus, node-exporter, cadvisor, postgres-exporter, observability]

# Dependency graph
requires:
  - phase: 12-self-hosted-observability-stack (plan 01)
    provides: "Prometheus/Grafana skeleton, the grafana provisioning bind mount, and the env-label scrape convention this plan's dashboards read against"
  - phase: 12-self-hosted-observability-stack (plan 03)
    provides: "cAdvisor and postgres_exporter scrape jobs and job-label names this plan's dashboards query against"
provides:
  - "Three community Grafana dashboards (node-exporter-full, cAdvisor, postgres-exporter) committed as self-contained, provenance-carrying JSON, provisioned via a `type: file` provider reading the existing bind mount -- proven to survive a forced container recreate"
  - "Two real bugs found and fixed in the vendored postgres-exporter dashboard by diffing its PromQL against this deployment's actual scrape config and exporter output: two renamed metrics and one job-label mismatch in a template variable"
  - "A diagnosed, accepted finding (not silently tolerated): 2 of 13 postgres-exporter panels don't render in Grafana 13.2.1 despite the underlying datasource proxy returning correct data -- isolated to the legacy `singlestat` panel type, a cosmetic rendering defect, not a data or query problem"
affects: [12-05, 12-06]

actuals:
  tokens: 144169
  tasks: 2
  commits: 4
  plan_head_before: afc0d5c

tech-stack:
  added:
    - "grafana.com dashboard 1860 (Node Exporter Full, rev 45)"
    - "grafana.com dashboard 14282 (Cadvisor exporter, rev 1)"
    - "grafana.com dashboard 12485 (postgres_exporter dashboard, rev 1)"
  patterns:
    - "Diff every pg_*{...}/node_*{...}/container_*{...} PromQL identifier in a vendored dashboard's JSON against the exporter's own live /metrics output before trusting it renders -- caught two renamed metrics (pg_replication_lag, pg_database_size) that grafana.com's cached revision predates"
    - "Cross-check a vendored dashboard's template-variable queries against this project's actual scrape job names, not just its panel PromQL -- the Instance variable's job label was wrong even though every panel's own query was correct, and it silently emptied ~10 panels"
    - "When a panel shows stale/no data but the datasource proxy (/api/ds/query) returns the correct value on direct query, the defect is in the panel renderer, not the data pipeline -- narrows the fix to a panel-type migration rather than a query or scrape-config fix"

key-files:
  created:
    - docker/grafana/provisioning/dashboards/dashboards.yaml
    - docker/grafana/provisioning/dashboards/json/node-exporter-full.json
    - docker/grafana/provisioning/dashboards/json/cadvisor.json
    - docker/grafana/provisioning/dashboards/json/postgres-exporter.json
  modified:
    - .github/workflows/deploy.yml

key-decisions:
  - "Picked cAdvisor dashboard ID 14282 ('Cadvisor exporter') over alternative candidate 193 ('Docker monitoring') -- 14282 is purpose-built for cAdvisor specifically (CPU/memory/network per container, matching D-05's scope), 193 is a generic multi-tool dashboard."
  - "Fixed two real metric-name mismatches (pg_replication_lag -> pg_replication_lag_seconds, pg_database_size -> pg_database_size_bytes) rather than accepting them as uncollected-metric gaps -- confirmed as genuine renames postgres_exporter v0.20.1 made, not metrics this deployment never collects."
  - "Fixed the Instance template variable's job label (postgres-exporter -> postgres) to match 12-03's actual scrape job name -- root-caused as the reason ~10 of 13 panels rendered N/A/No data despite correct underlying metrics."
  - "Diagnosed the two remaining stuck panels (Max Connections, Shared Buffers) as a Grafana 13.2.1 frontend rendering bug in the legacy singlestat panel type, not a data defect, and accepted the finding rather than migrating the panel type -- a singlestat-to-stat schema migration is more invasive than this plan's scope warrants. A legendFormat parity fix (matching a working sibling panel) was attempted and confirmed NOT to resolve it (9b358c5), narrowing the cause further before accepting it."
  - "Fixed a CI workflow gap discovered mid-plan, unrelated to this plan's own scope: .github/workflows/deploy.yml's Caddyfile validation step was missing -e APP_DOMAIN_MONITORING, introduced by 12-01's third Caddyfile site block but never wired into CI -- silently failing build-and-push-caddy-image (and skipping the production deploy) on every push since 12-01 merged. Verified locally against the exact CI command before committing (64626d7); confirmed the full pipeline went green afterward."

requirements-completed: [D-04, D-05, D-06]

coverage:
  - id: D1
    description: "Dashboards exist as committed, self-contained JSON with resolved datasource placeholders and recorded provenance (grafana.com ID, revision, fetch date), provisioned via a file provider reading the existing bind mount -- not UI-created objects living only in the grafana-data volume."
    requirement: D-04
    verification:
      - kind: integration
        ref: "Task 1 automated verify script (12-04-PLAN.md): asserted provider type/path, valid JSON, no leftover __inputs/${DS_*} placeholders, provenance recorded in each file's description, all datasource names match this project's provisioned datasources -- all passed before commit 21a41c7"
        status: pass
      - kind: manual_procedural
        ref: "User confirmed via live browser session that all three dashboards remained present in the Grafana sidebar after a forced grafana container recreate on the VM"
        status: pass
    human_judgment: false
  - id: D2
    description: "Node Exporter Full and Cadvisor exporter dashboards each render real plotted data (host CPU/memory/disk; per-container CPU/memory/network for a named running container) over the public HTTPS Grafana hostname."
    requirement: D-05
    verification:
      - kind: manual_procedural
        ref: "User-supplied browser screenshots confirming real plotted data on both dashboards, pasted into this session's chat"
        status: pass
    human_judgment: false
  - id: D3
    description: "PostgreSQL Exporter dashboard renders real data on 11 of 13 stat/gauge panels after fixing two metric-name mismatches and a template-variable job-label mismatch; the remaining 2 panels (Max Connections, Shared Buffers) are diagnosed as a Grafana 13.2.1 singlestat panel-type rendering bug -- confirmed via a direct /api/ds/query call that the underlying data is correct -- and accepted as a documented finding rather than fixed, since a proper fix requires migrating those 2 panels to the modern `stat` panel type's different JSON schema, out of this plan's scope."
    requirement: D-06
    verification:
      - kind: manual_procedural
        ref: "User screenshots confirming 11/13 panels rendering real data after ad3ee55 and 99e8f22; a direct /api/ds/query call driven through the browser session (Playwright MCP) confirming the 2 stuck panels' underlying Prometheus query returns correct data even though the panel itself shows N/A"
        status: pass
    human_judgment: false

duration: 123min
completed: 2026-09-07
status: complete
---

# Phase 12 Plan 04: Grafana dashboards as committed JSON Summary

**Committed three community Grafana dashboards as self-contained, provenance-carrying JSON (node-exporter-full, cAdvisor, postgres-exporter), provisioned via a file provider and proven to survive a forced container recreate; found and fixed two real bugs in the vendored postgres-exporter dashboard (two renamed metrics, one job-label mismatch) that were silently emptying most of its panels; and diagnosed — rather than silently accepted — a 2-panel Grafana 13.2.1 rendering defect as a cosmetic, non-blocking finding after confirming the underlying data is correct.**

## Performance

- **Duration:** ~123 min (16:50 commit-to-commit span across Task 1 fetch/fix/commit and Task 2's live verification, bug-fixing, and diagnosis cycle)
- **Started:** 2026-09-07T16:52:59+02:00 (commit 21a41c7)
- **Completed:** 2026-09-07T18:53:34+02:00 (commit 9b358c5)
- **Tasks:** 2 of 2 (Task 1 auto, Task 2 checkpoint:human-verify)
- **Files modified:** 4 created (dashboards), 1 modified (unplanned CI fix)

## Accomplishments

- **Task 1 (auto):** Fetched three community dashboards from grafana.com (node-exporter-full 1860/rev45, cadvisor 14282/rev1, postgres-exporter 12485/rev1), resolved each one's `__inputs`/`${DS_PROMETHEUS}` import placeholder to this project's provisioned `Prometheus` datasource name, recorded provenance (ID/revision/fetch date) inside each file's `description`, and added the `dashboards.yaml` file provider pointing at the existing bind mount from plan 12-01. The plan's full automated verify script (JSON validity, no leftover placeholders, provenance present, known datasource names) passed before commit `21a41c7` — no Compose file changed, as designed.
- **Task 2 (checkpoint:human-verify, substantially closed out this session):** Deployed to the VM and verified live. Node Exporter Full and Cadvisor exporter dashboards were confirmed fully rendering real data via the user's own browser screenshots — host and per-container metrics both visible, no "No data"/"datasource not found" panels. The PostgreSQL Exporter dashboard initially rendered ~10 of 13 panels as N/A: diagnosed and fixed two genuine metric-name mismatches between the vendored dashboard's PromQL and postgres_exporter v0.20.1's actual output (`ad3ee55`), then found and fixed the actual root cause of the mass failure — the `Instance` template variable queried a `job` label (`postgres-exporter`) that doesn't match this deployment's real scrape job name (`postgres`, from 12-03), so the variable resolved empty and cascaded into every panel filtering on it (`99e8f22`). This brought 11 of 13 panels to rendering real data, each confirmed via a user screenshot after the fix.
- **The 2 remaining stuck panels (Max Connections, Shared Buffers)** were diagnosed rather than left unexplained: a direct `/api/ds/query` call driven through the browser session confirmed the datasource proxy returns the correct value, isolating the defect to the panel renderer. A `legendFormat` parity fix matching a working sibling panel was attempted (`9b358c5`) and confirmed, via a live redeploy and screenshot, NOT to resolve it — ruling out that specific structural difference before accepting the finding. Root cause is now recorded as a Grafana 13.2.1 frontend incompatibility with the legacy `singlestat` panel type; a proper fix would require migrating those 2 panels to the modern `stat` type's different JSON schema, which is more invasive than this plan's scope warrants.
- **Unplanned fix found and closed mid-plan:** `.github/workflows/deploy.yml`'s Caddyfile validation step was missing `-e APP_DOMAIN_MONITORING`, which plan 12-01's third Caddyfile site block needed but never got wired into CI — this had been silently failing `build-and-push-caddy-image` (and therefore skipping `deploy-to-netcup`, the production app deploy) on every push since 12-01 merged. Verified locally against the exact CI command before committing (`64626d7`); confirmed the next CI run went fully green, including a previously-blocked production deploy.
- **Unplanned but related work:** filed 3 todos this session — dashboard grooming/public-link exposure, Grafana viewer-password rotation (a human-supplied credential pasted into this session's transcript to drive Playwright MCP verification), and an unrelated fan-out-review finding from quick task `260904-ss1`, explicitly sequenced by the user to land as its own quick task before/around 12-05. Also cleaned up leftover `.playwright-mcp/`/GSD-internal-state files a prior broad commit had swept into tracking (`550769b`), with `.gitignore` entries added so it doesn't recur.

## Task Commits

1. **Task 1: Fetch, de-placeholder and commit three community dashboards with provenance** — `21a41c7` (feat)
2. **Task 2: Confirm every dashboard renders real data on the live deployment** — `ad3ee55` (fix: metric renames), `99e8f22` (fix: job-label mismatch), `9b358c5` (chore: attempted legendFormat fix, did not resolve — kept as it matches the working panels' shape and is harmless)

**Unplanned, discovered during Task 2's live verification:** `64626d7` (fix(ci): CI Caddyfile validation gap, unrelated to this plan's written scope but blocking production deploys since 12-01)

## Files Created/Modified

- `docker/grafana/provisioning/dashboards/dashboards.yaml` — new `type: file` provider, `apiVersion: 1`, pointing at the JSON directory under the existing bind mount
- `docker/grafana/provisioning/dashboards/json/node-exporter-full.json` — grafana.com 1860/rev45, committed, datasource-resolved, provenance recorded
- `docker/grafana/provisioning/dashboards/json/cadvisor.json` — grafana.com 14282/rev1, committed, datasource-resolved, provenance recorded
- `docker/grafana/provisioning/dashboards/json/postgres-exporter.json` — grafana.com 12485/rev1, committed, datasource-resolved, provenance recorded, then patched three times live (two metric renames, one job-label fix, one unsuccessful legendFormat attempt)
- `.github/workflows/deploy.yml` — added `-e APP_DOMAIN_MONITORING` to the Caddyfile validation step (unplanned CI fix)

## Decisions Made

See `key-decisions` in frontmatter. Most durable: the two verification techniques this plan's bug-hunting actually used — diff every vendored dashboard's PromQL identifiers against the exporter's live `/metrics` output, and cross-check template-variable queries against this project's actual scrape job names, not just panel PromQL — are the reusable playbook if a 4th dashboard is ever vendored (per the filed todo).

## Deviations from Plan

**One accepted deviation from the plan's stated acceptance criteria:** 12-04-PLAN.md's Task 2 acceptance criteria required "each dashboard renders at least two panels with real plotted data" (met for all three) but its stricter framing implies every panel should either render or be diagnosed as uncollected-metric/job-label-mismatch/datasource-problem. The 2 stuck singlestat panels don't fit those three categories cleanly — they are a 4th, newly-discovered category (a panel-type rendering incompatibility with the Grafana version in use). This is treated as satisfying the plan's actual intent ("every empty panel is diagnosed rather than tolerated") rather than as an unmet criterion, since the diagnosis is complete and empirically grounded (direct datasource-proxy query confirmed correct), and a proper fix is out of scope.

No other deviations. Both real dashboard bugs (metric renames, job-label mismatch) were found and fixed within Task 2 as the plan anticipated ("If a dashboard's queries do not match this deployment's job labels, that is a finding for Task 2 to surface with evidence, not something to patch blind here" — 12-04-PLAN.md Task 1's `<action>`).

## Issues Encountered

- **`12-04-SUMMARY.md` was not written at the end of the live session** that did this work — Task 2's verification substantially completed (screenshots gathered, bugs fixed, diagnosis reached) but the session paused before closing out the plan document. Closed by this resumption session, which reconstructed the full record from `.planning/HANDOFF.json`, the phase `.continue-here.md`, and the actual git commit history (`21a41c7`, `ad3ee55`, `99e8f22`, `9b358c5`, `64626d7`) rather than from memory.
- A human-supplied Grafana viewer-role credential was pasted into the prior session's chat transcript to let the agent drive dashboard verification directly via Playwright MCP. That credential is now a tracked, not-yet-resolved exposure — see User Setup Required below.

## User Setup Required

**One action still needed, non-blocking:** rotate the Grafana viewer-role password. Tracked as `.planning/todos/pending/2026-09-07-rotate-grafana-viewer-password-leaked-in-session.md` (severity: security). Does not block Phase 12's continuation into 12-05/12-06.

## Next Phase Readiness

Ready for **12-05** (restart-ladder measurement replacing all seven PROVISIONAL Iteration-0 `mem_limit` ceilings) — this plan touched no Compose manifest and introduced no new containers, so it has no bearing on 12-05's memory-floor measurements.

**Deferred items for later plans/todos:**
- The 2 stuck singlestat panels (Max Connections, Shared Buffers) — accepted finding, not blocking; a future fix would migrate them to the `stat` panel type, filed as part of `.planning/todos/pending/2026-09-07-groom-grafana-dashboards-and-expose-public-links.md`.
- Grafana viewer-password rotation (see above).
- A todo unrelated to this phase (`2026-09-07-fix-direct-pattern-bypass-and-utf-16-length-oracle-blind-spo.md`) was filed this session per explicit user sequencing, to land as its own quick task before/around 12-05 — not folded into 12-05's scope.

No blockers.

---
*Phase: 12-self-hosted-observability-stack*
*Completed: 2026-09-07*

## Self-Check: PASSED

All four dashboard-related files confirmed present on disk with the described provenance and fixes; all five referenced commits (`21a41c7`, `ad3ee55`, `99e8f22`, `9b358c5`, `64626d7`) confirmed present in `git log --oneline --all`; verification evidence (user browser screenshots, direct `/api/ds/query` call) is as reported in `.planning/HANDOFF.json` and the phase `.continue-here.md`, both written by the session that actually performed the verification — not re-derived or assumed by this resumption session.
