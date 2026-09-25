# Log aggregation — Plan 12-02 (2026-09-07)

Loki + Promtail deployed alongside plan 12-01's Prometheus/Grafana skeleton, closing the
log-shipping half of the folded todo (`2026-08-20-no-remote-log-shipping-...`, D-01) — an infra
incident's Postgres/Redpanda/Caddy logs are now readable from one place instead of requiring SSH
to the box and hoping Docker's `10m x 3` rotation hasn't already discarded the window.

## Topology

Promtail reads `/var/run/docker.sock` (mounted `:ro`) and therefore discovers every container on
the HOST, both Compose projects (`kanban-board-backend` and `kanban-board-nonprod`), with **no
per-container configuration on either side** — this is why D-08's "all containers" requirement
needed zero changes to `docker-compose.nonprod.yml`, which a future reader would otherwise assume
was an oversight rather than the deliberate consequence of `docker.sock` being host-wide, not
Compose-project-scoped. Promtail pushes to `http://loki:3100/loki/api/v1/push`; Grafana reads Loki
via the datasource `docker/grafana/provisioning/datasources/datasources.yaml` appends alongside the
existing Prometheus one.

## Containers shipping logs

Docker assigns the real container names Promtail's `container` relabel target reports — for the
production Compose project (no `container_name:` pins) these are the Compose-generated
`<project>-<service>-1` form, while the nonprod project's two services pin explicit names. Both
forms are recorded here so a future reader's Grafana query matches what Promtail actually emits,
not the plan's shorthand:

| Plan's shorthand name | Real `container` label Promtail emits | `compose_project` label |
|---|---|---|
| `app` | `kanban-board-backend-app-1` | `kanban-board-backend` |
| `kanban-nonprod-app` | `kanban-nonprod-app` | `kanban-board-nonprod` |
| `postgres` | `kanban-board-backend-postgres-1` | `kanban-board-backend` |
| `redpanda` | `kanban-board-backend-redpanda-1` | `kanban-board-backend` |
| `kanban-nonprod-redpanda` | `kanban-nonprod-redpanda` | `kanban-board-nonprod` |
| `caddy` | `kanban-board-backend-caddy-1` | `kanban-board-backend` |

All six confirmed queryable with real, recent log text (not Docker's `{"log":...,"stream":...,
"time":...}` envelope) — sample lines: Postgres `checkpoint complete: wrote ... buffers`; Redpanda
`storage - disk_log_impl.cc:3310 - Removing "..."`; Caddy real Caddy JSON access/error log; both
`app`/`kanban-nonprod-app` real Spring Boot `INFO ... DispatcherServlet` lines. Two distinct
`compose_project` values observed (`kanban-board-backend`, `kanban-board-nonprod`), confirming
Promtail crossed the Compose-project boundary with zero per-container configuration on either side.

## Retention

30 days, matching Prometheus's own D-07 window, sized against the 222 GB disk headroom measured
2026-09-07. Enforced by `docker/loki/loki-config.yaml`'s `compactor.retention_enabled: true` +
`limits_config.retention_period: 720h` — the current Loki 2.x+ mechanism. The superseded
`table_manager`/`chunk_store_config` retention mechanism is deliberately **not used** anywhere in
this config; a future reader doing maintenance on this file should not reintroduce it.

## Accepted risks

1. **Promtail is formally end of life (EOL) as of 2026-03-02** (per Grafana's own docs, quoted in
   `12-RESEARCH.md`'s "State of the Art") and receives no further security patches. D-01/D-08 name
   it explicitly as the chosen tool, so this is a locked user decision, not an unexamined one.
   Grafana Alloy — the actively-maintained successor, with an automated Promtail-config converter —
   is the documented migration path if this is ever revisited.
2. **The `docker.sock` mount is read-only, and that removes one tampering class, not the underlying
   privilege.** The Docker API's dangerous operations (creating a privileged container, mounting the
   host filesystem into one) are HTTP requests over the socket, not filesystem writes to it, so
   `:ro` prevents this container from being made to write different bytes to the socket file, but
   does not remove the API-level elevation-of-privilege risk a compromised Promtail container would
   carry. Mitigated only by Promtail having no inbound route and no published port — the same
   posture D-05 already accepts for cAdvisor's own `docker.sock` mount.
3. **Every container's stdout is now centralized behind one Grafana login (D-03).** `SHOW
   log_statement;` on the live Postgres reports `none`, confirmed independently over SSH via the
   container's own log stream (not merely asserted) — routine `SELECT`/`INSERT`/`UPDATE` statements
   are absent from what Promtail ships to Loki, and the only `STATEMENT:` lines observed are
   Postgres's own error-context disclosure (schema-DDL text attached to an "already exists" error
   during a Spring Session JDBC schema-init retry, not user data or bind parameters). If
   `log_statement` is ever raised to `all` for debugging — as Phase 11 did once for a one-off
   verification — every bind parameter, including credentials passed as parameters, would start
   flowing into this centralized store. This is the concrete reason Task 2 checks the live setting
   explicitly rather than assuming it.

## Operator note — reading logs during an incident

In Grafana's Explore view, select the **Loki** datasource and run:

```
{container="kanban-board-backend-postgres-1"}
```

(swap the container label for any row in the table above — `kanban-board-backend-caddy-1`,
`kanban-board-backend-redpanda-1`, `kanban-nonprod-app`, `kanban-nonprod-redpanda`, etc.) to see
that container's recent lines, newest first. Add `| json` after the query for containers emitting
JSON logs (Caddy) to get field-level filtering. To scope by environment instead of by container —
useful for "is this a prod-only or nonprod-only issue" — query
`{compose_project="kanban-board-backend"}` or `{compose_project="kanban-board-nonprod"}`.

## Deviations from the plan text, and why

- **[Rule 1 — Bug] `schema_config.configs[0].from` set to the deploy date instead of a safely-past
  one, discovered live during Task 2's deploy (commit `8512bbf`).** Promtail's first run tails each
  container's on-disk log from its start, and Docker's `10m x 3` rotation lets a quiet container's
  backlog span weeks. A `from:` of "today" put every pre-deploy line outside any schema period —
  Loki returned a hard `500` ("no schema config found for time X") instead of the softer "too old"
  `400`, and Promtail's client retries a failed `500` batch forever, permanently blocking that
  stream's newer entries. Fixed by backdating `from:` to `2024-01-01` — schema periods don't
  pre-allocate storage, so backdating costs nothing. **General lesson:** a fresh Loki deploy against
  an already-populated Docker host must set `schema_config.from` safely before the oldest possible
  on-disk log line, not the deploy date.
- **[Rule 1 — Bug] `limits_config.reject_old_samples_max_age` left at Loki's 7-day default,
  discovered immediately after fixing the above (commit `073ac05`).** Even after the schema fix,
  the pre-deploy backlog older than 7 days was still soft-rejected (`400`, "too old"). Promtail
  batches multiple streams into one push, and a rejected batch is dropped **in its entirety**
  (`promtail_dropped_entries_total{reason="ingester_error"}` peaked at 27815) — so a genuinely
  recent stream's valid entries were taken down as collateral whenever they shared a push with a
  stale one. Fixed by setting `reject_old_samples_max_age: 720h` to match `retention_period`, so
  nothing inside the actual 30-day retention window can be rejected as "too old" in the first place.
- **[Rule 1 — Bug, found independently during this plan's own close-out, not by the prior two
  fixes] A third, distinct Loki ingester guard — `too_far_behind`, bound to `max_chunk_age` (default
  1h, so the effective cutoff is ~30 minutes behind "now") — is a per-STREAM ordering check, not a
  `limits_config` age setting, and neither commit above touches it.** After both fixes were deployed
  and re-verified, `kanban-nonprod-app` (one of the six required containers) still showed **zero**
  queryable lines across its entire log history: its container had been running 27 hours with no
  application-level traffic (this codebase does not log routine successful requests, per
  `.claude/CLAUDE.md`'s own "Logging not extensively used" note), so its ENTIRE on-disk backlog
  already predated the `too_far_behind` cutoff at the moment Promtail (re)started and tried to
  catch it up in one batch — every line in that stream was rejected, and with no new activity there
  was nothing to establish a fresh high-water mark. Confirmed via Loki's own `/config` endpoint that
  `reject_old_samples_max_age`/`retention_period` were both correctly `30d` — this is a genuinely
  separate mechanism, not a regression of the prior two fixes. Per Loki's own docs
  (`docs/sources/operations/request-validation-rate-limits.md`), the deprecated `unordered_writes`
  flag can suppress this check but users are "encouraged to address the root cause... rather than
  modifying global chunk age settings" — so this is recorded as an **accepted operational
  characteristic**, not something to configure around. Remediated live for `kanban-nonprod-app` by
  `docker restart`-ing it (safe: nonprod, `restart: unless-stopped`, does not count against the
  RestartCount=0 acceptance bar which only names the four production containers) to produce a fresh
  line within the current window; confirmed queryable immediately after. **General lesson, worth
  more than the two above:** on ANY future recreation of the `promtail` container (its positions
  file is not a named volume, so every recreate re-tails every container from the start), a
  container that has been quiet for longer than roughly `max_chunk_age / 2` (30 minutes with the
  default used here) will show zero queryable history until it next emits a genuinely new log line
  — restart it, or wait for real activity, to bootstrap its stream. This is expected Loki behavior,
  not a bug to chase, but it will look exactly like a broken pipeline to whoever hits it next if it
  isn't written down.

## Deployment date

2026-09-07.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
