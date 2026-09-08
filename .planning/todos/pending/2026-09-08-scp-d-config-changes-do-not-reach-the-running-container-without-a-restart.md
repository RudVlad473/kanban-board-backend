---
created: 2026-09-08T00:00:00.000Z
title: "SCP'd config changes do not reach the running container without a restart (Grafana datasources, Prometheus, Loki, Promtail)"
area: infra
severity: moderate
files:
  - .github/workflows/deploy.yml
  - docker-compose.prod.yml
  - docs/INFRA_RUNBOOK.md
---

## Problem

Filed from quick task 260908-sj9 (2026-09-08), which fixed `deploy-to-netcup`'s SCP step to
actually *transfer* four previously-missing repo-relative bind-mount sources
(`docker/grafana/provisioning`, `docker/prometheus/prometheus.yml`, `docker/loki/loki-config.yaml`,
`docker/promtail/promtail-config.yaml`) to the VM. Transferring the file is not the same as
applying it. A bind-mounted file's *content* is not part of Compose's config hash, so
`docker compose up -d` leaves alone any container whose only change is that file's content — the
same mechanism `deploy-to-netcup`'s own Caddy-reload comment already documents and works around for
`Caddyfile`.

Of the four newly-transferred config surfaces, only one is hot:

- **Grafana dashboards** — hot. The file provider re-reads `json/` every 30s
  (`updateIntervalSeconds: 30`), so a dashboard JSON edit lands automatically. This is the
  originating brief's actual complaint, and quick task 260908-sj9 fully closes it.
- **Grafana *datasources*** (`docker/grafana/provisioning/datasources/datasources.yaml`) —
  provisioned once at container startup only. An edit to this file is SCP'd to the VM correctly
  after this fix, but has zero runtime effect until Grafana's container is restarted.
- **Prometheus** (`docker/prometheus/prometheus.yml`) — config read at process start. An edit is
  SCP'd correctly but takes no effect until Prometheus is restarted (the compose file deliberately
  does not set `--web.enable-lifecycle`, so there is no reload endpoint to hit instead — see that
  file's own comment for why).
- **Promtail** (`docker/promtail/promtail-config.yaml`) — same shape: config read at process start,
  no reload path, needs a restart.

So a future edit to any of these three files will be transferred to the VM by every deploy from now
on, but will silently have NO effect on the running stack until something restarts the affected
container — a gap that reads as "fixed" after 260908-sj9 unless this is documented and eventually
closed.

## Solution

Add a reload or targeted-restart step to `deploy-to-netcup`'s own SSH script
(`.github/workflows/deploy.yml`), ordered AFTER `docker compose ... up -d` for the identical reason
the existing Caddy reload is ordered after it (see that job's own comment): on the deploy that first
introduces this step, the containers already running are whatever the *previous* image built, so a
reload issued before `up -d` would target the wrong process generation.

Concrete shape, mirroring the existing Caddy reload pattern:
- `docker compose ... restart grafana` (or a Grafana API-based datasource reload, if one exists for
  this image tag — check before assuming) to pick up `datasources.yaml` changes.
- `docker compose ... restart prometheus` for `prometheus.yml` changes. (Adding
  `--web.enable-lifecycle` and a `POST /-/reload` call instead is a real alternative, but that flag
  was deliberately rejected in `docker-compose.prod.yml`'s own comment as adding an unauthenticated
  write endpoint to a service with no other access control — revisit only if that trade-off is
  reconsidered.)
- `docker compose ... restart promtail` for `promtail-config.yaml` changes.

Each restart should be conditional or otherwise cheap enough to run on every deploy without a real
cost, matching how the Caddy reload already runs unconditionally on every deploy today. Failure
semantics should match the Caddy reload's own `set -e` behavior: a failed restart reddens the job
with `app` already swapped in and the previous config still serving, which is the intended fail-open
posture for a config-application step, not an oversight.

## Out of scope for quick task 260908-sj9

That task was explicitly scoped to fixing the SCP *transfer* gap and adding a CI gate that keeps it
fixed — it is forbidden from touching any other step in `deploy-to-netcup`'s SSH script (its own
`<action>` text: "Change nothing else... every other step in `deploy-to-netcup`... stay[s]
untouched"). This todo is the deliberate handoff of the *application* half of the problem, tracked
separately rather than silently left implied as covered.
