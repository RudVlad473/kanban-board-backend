# Public Grafana dashboards rendered no data — debug session (2026-09-12)

## The defect

All three dashboards shared through Grafana's public-dashboard feature rendered their shell —
titles, layout, rows — with every panel showing "No data" / "N/A" and a "Datasource was not found"
tooltip. Two of the three are linked from the README's "Live" section; the third
(`Postgres Internals`, access token `8939df8f…`) is publicly reachable but linked nowhere, which is
worth knowing on its own merits.

Nothing was wrong with the collection stack. Every exporter container was healthy, all six
Prometheus targets were `up`, and the same dashboards rendered correctly for a logged-in admin.
**Grafana's public-dashboard renderer is a different, stricter code path than the logged-in one**,
and the dashboards had never satisfied it.

## The evidence

`POST /api/public/dashboards/{token}/panels/{id}/query` returned HTTP 500 for every panel. The
container log named the cause verbatim:

```
logger=datasources level=warn msg="Invalid datasource uid ..." uid=${ds_prometheus}
    action=read name= error="invalid UID"
logger=publicdashboards.service level=error msg="Error querying datasources for public dashboard"
    error="data source not found" datasources=[public-ds]
```

Two independently fatal conditions, both required for a panel to render, neither of which held:

1. **The datasource ref must be a literal uid.** `node-exporter-full.json` referenced
   `{"type":"prometheus","uid":"${ds_prometheus}"}` — a datasource *template variable* — and the
   other two referenced the legacy name string `"Prometheus"`, a remnant of resolving the vendored
   `${DS_PROMETHEUS}` placeholder to the datasource's *name* rather than its uid when these were
   first committed. The public renderer resolves by uid only; both forms fail lookup.
2. **The query must contain no dashboard template variables.** Reproduced locally against
   `grafana/grafana:13.2.1` with this repo's real provisioning and the Prometheus datasource
   pointed at a request-recording stub: with condition 1 fixed, the panel returned HTTP 200 and the
   PromQL that reached the datasource was
   `rate(node_pressure_cpu_waiting_seconds_total{instance="$node",job="$job"}[1m15s])` — verbatim,
   uninterpolated. `$__rate_interval` *was* interpolated (to `1m15s`); `$node` and `$job` were not.
   Pinning each variable's saved `current`/`options` value and setting `refresh: 0` changed
   nothing. Grafana does not support template variables on public dashboards
   (grafana/grafana#67346, still open). 100% of panel targets on all three dashboards used them.

A recording stub was used rather than a real Prometheus deliberately: against real data an
uninterpolated query and a correct query that matches nothing both return empty, so the stub is
what makes condition 2 observable at all.

## Why the obvious diagnosis was wrong

The symptom is the textbook signature of *datasource UID drift* — a dashboard pinned to a uid that
a re-provisioned datasource no longer has. It was not that. Neither dashboard ever held a concrete
uid, so there was nothing to drift; and Grafana **derives an unpinned provisioned datasource's uid
deterministically from its name**, confirmed by a fresh local container generating byte-identical
values (`PBFA97CFB590B2093`, `P8E80F9AEF21F6940`) to the ones production has been running since
Phase 12. The uid was stable the whole time.

## The landmine in the obvious fix

The natural fix — give the datasource a readable `uid: prometheus` and point the dashboards at it —
**takes production down**. Measured by booting Grafana on a persistent volume with the old
provisioning, then re-provisioning a *different* uid onto the same volume:

```
logger=provisioning level=error msg="Failed to provision data sources"
    error="Datasource provisioning error: data source not found"
Error: ✗ invalid service state: Failed, expected: Terminated, failure: starting module
    provisioning: ... Datasource provisioning error: data source not found
```

Exit code 1 — a hard startup failure, not a degraded start. Under `restart: unless-stopped` that is
a crash loop taking the whole monitoring stack with it. A fresh-container test passes cleanly and
would never have surfaced this.

## The fix

`datasources.yaml` now pins each uid **to the value Grafana had already derived**, and all 182
dashboard datasource refs point at that literal. Every template variable in every query was
replaced with the single value it resolves to on this one-host deployment, and the now-dead
variables removed:

| Variable | Substituted with | Source |
|---|---|---|
| `$node` / `$job` / `$nodename` | `node-exporter:9100` / `node` / `node-exporter` | `node_uname_info` on live Prometheus |
| `$host` | `cadvisor:8080` | `instance` label of the cadvisor job |
| `$container` | `.+` | preserves the variable's own `includeAll` semantics |
| `$Instance` | `postgres-exporter:9187` | `pg_up` |
| `$Database` | `.+` | preserves `multi`/`includeAll` |
| `$Interval` | `10m` | the variable's own saved default |

**This deploy needs no Grafana restart.** The dashboard JSONs are hot — the file provider re-reads
`json/` every 30s (see "Residual gaps" under the SCP entry above) — and the `datasources.yaml`
edit, which *would* need a restart, is purely declarative here: it writes down the uid the running
instance already has. A restart is harmless if one happens for another reason; re-provisioning the
same uid onto an existing volume was verified to start cleanly.

## Verification

Every one of the 323 distinct flattened queries was run against live production Prometheus:
cAdvisor 6/6 and Postgres Internals 43/43 return data; VM Host Metrics returns data for 232 of 274.
The 42 empty ones were checked individually against the discriminator that matters — does the
metric exist at all, or does it exist but not match the substituted labels? **Zero** fall into the
second category. All 24 metrics behind them are absent from this Prometheus entirely
(`node_hwmon_*`, `node_systemd_*`, `node_processes_*`, `node_interrupts_total`,
`node_cpu_scaling_frequency_*`, `node_power_supply_online`, `node_pressure_irq_stalled_*`) —
collectors `node_exporter` does not enable by default and hardware a VPS does not have. That is
pre-existing breadth in a dashboard vendored for bare metal, not a consequence of this fix.

## The gate

`scripts/verify-public-dashboards.py`, wired into `.github/workflows/invariant-checks.yml`
alongside its self-test, fails any PR where a publicly-shared dashboard references a datasource by
name or by variable, or carries a `$variable` in a query field. Demonstrated red against the
pre-fix JSON (1001 violations) and green against the fixed tree. See that script's own module
docstring for the full invariant list and its KNOWN HOLES — not restated here.

The gate earns its maintenance because of condition 2's failure mode specifically: it produces
HTTP 200 with an empty result. No log line, no unhealthy container, no failing request — the
dashboard just looks quiet. Nothing else in this stack can tell that apart from an idle metric, and
these dashboards are vendored from grafana.com, where template variables are the norm, so every
future re-fetch reintroduces it.

## Residual gaps this fix does NOT close

1. **The gate reads committed JSON, not the running Grafana.** A dashboard shared publicly from the
   UI, or edited and saved into the `grafana-data` volume, is invisible to it. The authoritative
   list of what is actually public is `GET /api/dashboards/public-dashboards` on the VM.
2. **The substituted label values are not checked against live Prometheus by the gate.** If the
   stack ever grows a second node or an exporter is renamed, the gate stays green while the
   dashboards silently narrow to a host that no longer exists.
3. **The dashboards are no longer interactive for the logged-in admin either.** Flattening removed
   the variable dropdowns for everyone, not just public viewers — including `$Interval` on Postgres
   Internals, now fixed at `10m`. On a single-host deployment the other variables had exactly one
   selectable value, so little was lost; `$Interval` is a real, if small, reduction. Preserving
   both would mean maintaining separate public copies of all three dashboards, whose new uids would
   invalidate the README's existing share links.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
