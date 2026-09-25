# Deploy SCP coverage gap — quick task 260908-sj9 (2026-09-08)

## The defect

`deploy-to-netcup`'s SCP step (`.github/workflows/deploy.yml`) never transferred four of the six
repo-relative bind-mount sources `docker-compose.prod.yml` declares, from Phase 12 onward:
`docker/grafana/provisioning`, `docker/prometheus/prometheus.yml`, `docker/loki/loki-config.yaml`,
`docker/promtail/promtail-config.yaml`. Only `docker-compose.prod.yml`, `Caddyfile`, and
`docker/postgres-init/01-create-databases-and-roles.sh` were ever kept in the `source:` list — see
the two frozen historical records above ("Manual deploy — Plan 05-04 Task 1" and "Task 3 — Deploy
job rewrite and cleanup jobs (INFRA-05)"), left unedited on purpose: Phase 11 added
`postgres-init` to the same `source:` string and updated neither of those past-tense records
either, which is the precedent for not "correcting" them here. Because `docker-compose.prod.yml`
bind-mounts these paths relative to its own directory on the VM
(`/opt/deploy/kanban-board-backend/`), the four missing paths resolved against whatever hand-copy
already sat there rather than against the git checkout — production served that hand-copied
configuration for the entire observability stack behind fourteen green deploys.

## The evidence

Live `find -printf` timestamps on the VM (`ssh netcup-prod`, read-only, this session) separate the
three CI-written paths from the four hand-copied ones with no ambiguity:

```
2026-09-08 20:28  docker-compose.prod.yml                    <- last CI deploy
2026-09-08 20:28  Caddyfile                                  <- last CI deploy
2026-09-08 20:28  docker/postgres-init/01-create-...sh       <- last CI deploy
2026-09-07 15:20  docker/promtail/promtail-config.yaml       <- frozen, hand-copied
2026-09-07 16:07  docker/loki/loki-config.yaml               <- frozen, hand-copied
2026-09-07 16:45  docker/prometheus/prometheus.yml           <- frozen, hand-copied
2026-09-07 16:53  docker/grafana/provisioning/dashboards     <- frozen, hand-copied
```

## The fix

`deploy-to-netcup`'s `source:` value was extended from three comma-separated entries to seven,
appending the four missing paths in the order listed above. `target:` and the deliberate absence of
`rm:` (the target directory also holds `.env.prod`, which must never be recreated or deleted) are
both unchanged.

## The gate

`scripts/verify-deploy-scp-coverage.py`, wired into `.github/workflows/invariant-checks.yml`
alongside its self-test, now holds `docker-compose.prod.yml`'s bind mounts and
`deploy-to-netcup`'s `source:` list to provable per-PR agreement (and covers
`docker-compose.nonprod.yml` / `deploy-to-nonprod` the same way, whose expected repo-relative set
is empty today). See that script's own module docstring for the full invariant list and its KNOWN
HOLES — not restated here.

## Residual gaps this fix does NOT close

1. **No deletion sync.** `rm:` cannot be used on this step, so a file deleted from the repo is
   never removed from the VM. Grafana's file provider will keep serving an orphaned dashboard JSON
   indefinitely — copying is one-directional, new and changed content always wins, but nothing here
   removes stale content.
2. **Transferred is not applied, for three of the four.** A bind-mounted file's *content* is not
   part of Compose's config hash, so `docker compose up -d` does not recreate a container whose
   only change is that file's content — the same mechanism `deploy-to-netcup`'s own Caddy-reload
   comment already documents for `Caddyfile`. Per consumer:
   - **Grafana dashboards** — hot. The file provider re-reads `json/` every 30s, so dashboard
     changes land automatically once the files arrive. This is the case the originating brief cared
     about, and it is fully closed by this fix.
   - **Grafana datasources** — provisioned at container startup only; a `datasources.yaml` edit
     needs a container restart to take effect.
   - **Prometheus / Loki / Promtail** — config is read at process start; a config edit needs a
     restart or a SIGHUP to take effect.

Gap 2 (the reload gap for Grafana datasources, Prometheus, Loki, and Promtail) is filed as
`.planning/todos/pending/2026-09-08-scp-d-config-changes-do-not-reach-the-running-container-without-a-restart.md`.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
