# Monitoring role and metrics targets — Plan 12-03 (2026-09-07)

Completes the metrics half of the observability phase (D-04/D-05/D-06): per-container breakdown
via cAdvisor, Postgres internals via postgres_exporter, and both Redpanda brokers scraped over a
new cross-project network. Every intended Prometheus target is UP, including
`redpanda-nonprod:9644` — the one target this phase's research predicted would silently fail
without the network wiring below.

## The monitoring role, and why it is not an init script

`docker/postgres-init/01-create-databases-and-roles.sh` only runs against an EMPTY
`postgres-data` volume on first container boot (its own header comment says so verbatim) — this
volume has been populated since Phase 11, so a script placed there would silently never run,
which is exactly RESEARCH Pitfall 2. The role was created live instead, via `docker exec` against
the already-running `kanban-board-backend-postgres-1` container, authenticated as `kanban_admin`
over the container's own local UNIX socket (trust-authenticated, no password needed for that
hop).

Command shape used (password never a value in this document, never a CLI argument, never printed
to any transcript):

```bash
docker exec -i kanban-board-backend-postgres-1 \
  psql -v ON_ERROR_STOP=1 --username kanban_admin --dbname postgres <<SQL
\set monitoring_pass '<value, piped via heredoc stdin only>'
CREATE ROLE monitoring WITH LOGIN PASSWORD :'monitoring_pass';
GRANT CONNECT ON DATABASE kanban_prod TO monitoring;
GRANT CONNECT ON DATABASE kanban_nonprod TO monitoring;
GRANT pg_monitor TO monitoring;
SQL
```

This deliberately uses psql's `\set` meta-command inside the piped SQL body rather than the
init script's own `-v var=value` convention: `-v` on a `docker exec` invocation against an
ALREADY-RUNNING container puts the value into that `docker exec` process's argv, which Docker
logs in `docker events`' command line — the exact leak mechanism that exposed the Grafana admin
password during Plan 12-01. `\set` inside stdin carries the value through the pipe instead, never
through argv, while `:'monitoring_pass'` still gets psql's own SQL-literal quoting on
substitution, so the safety property the init script's `-v`/`:'var'` convention provides is
unchanged.

**Recovery path if the role is ever lost:** re-run the command above. It is idempotent-by-inspection
(`CREATE ROLE` fails loudly if the role already exists; the `GRANT` statements are no-ops on a
role that already holds them), not a redeploy.

The generated password was NOT restricted to avoid `@ : / ? # %` — see "postgres_exporter
credential handling" below for why those characters are safe here, and confirmed live to actually
be present in this deployment's password.

## Least-privilege proof

Positive test, as the `monitoring` role, via local socket:

```
SELECT count(*) FROM pg_stat_activity;
 count
-------
     9
(1 row)
```

Negative test, `kanban_prod`:

```
SELECT * FROM users LIMIT 1;
ERROR:  permission denied for table users
```

Negative test, `kanban_nonprod`:

```
SELECT * FROM users LIMIT 1;
ERROR:  permission denied for table users
```

Role membership, confirmed via `pg_auth_members` rather than `\du`'s summary column:

```
   member   | granted_role
------------+--------------
 monitoring | pg_monitor
(1 row)
```

## Scrape targets

Every job UP on the one shared Prometheus instance, verified via its `/api/v1/targets` endpoint
immediately after deploy:

| Job | Target | `env` label | Health |
|---|---|---|---|
| node | node-exporter:9100 | prod | up |
| cadvisor | cadvisor:8080 | prod | up |
| postgres | postgres-exporter:9187 | shared | up |
| redpanda | redpanda:9644 | prod | up |
| redpanda | redpanda-nonprod:9644 | nonprod | up |
| prometheus | prometheus:9090 | prod | up |

`redpanda-nonprod:9644` reporting `up` is the concrete proof that D-04's "one shared stack
monitors BOTH environments" claim holds for this target, not merely for the ones that were always
going to work.

## The kanban-metrics network

A third cross-project network, mirroring `kanban-edge` and `kanban-db`'s existing shape exactly.
Scope: letting production's `prometheus` reach the nonprod `redpanda-nonprod` broker's native
metrics endpoint — without it, that target is unreachable and D-04's shared-stack claim is false
for it (RESEARCH Pitfall 1). Reusing `kanban-edge` or `kanban-db` instead was rejected: both carry
an explicit, documented scope in their own comments, and overloading either would silently widen
a boundary those comments exist to state.

Exactly two members across both Compose projects: `prometheus` (production) and
`redpanda-nonprod` (nonprod). Nothing else joins it.

Command needed on a rebuilt host:

```bash
docker network create kanban-metrics
```

Both brokers' admin APIs were probed live (Task 1, 2026-09-07) before any compose change was
written: an in-container request to each broker's own non-loopback address on 9644, paired with a
loopback request on the same port, discriminating a default bind from an explicit one. BOTH
brokers already answered `200` on `/public_metrics` on their own non-loopback address as well as
on loopback — i.e. the admin API was already reachable, not loopback-restricted, on this
deployment. Production's reachability was additionally corroborated off-container, from
`prometheus` over the production `default` network, returning real `redpanda_application_build`
metrics. **No `--admin-addr` flag was added to either broker, and neither broker's `command:`
changed** — a verified default and an assumed one are not the same thing, and this deployment's
default was verified, live, before being trusted.

## cAdvisor mount posture

Four mounts, ALL read-only: `/:/rootfs:ro`, `/var/run:/var/run:ro`, `/sys:/sys:ro`,
`/var/lib/docker/:/var/lib/docker:ro`. No `privileged: true`, no `pid: host` — both are needed
only for process-level metrics, which this phase's scope (per-container CPU/mem/network)
explicitly excludes.

**D-05 amendment, dated 2026-09-07.** D-05's literal text (`12-CONTEXT.md`) names cAdvisor's
mounts as `/var/run/docker.sock`, `/sys` and `/proc`, all read-only. The set actually deployed is
broader in two places (the whole `/var/run` directory rather than the socket alone, plus
`/rootfs` and `/var/lib/docker`) and narrower in one (`/proc` is not mounted at all). This is
deliberate, not drift: D-05's literal set alone leaves cAdvisor starting, answering `/metrics`,
and emitting empty or self-only series (RESEARCH Pitfall 3) — cAdvisor needs `/rootfs` and
`/var/lib/docker` to resolve container filesystem and image-layer state, and it reads its own
procfs (host-wide for the counters it uses) rather than needing a `/proc` bind. What D-05 actually
protects is unchanged: every mount is read-only (in fact `/var/run:ro` is STRICTER than cAdvisor's
own upstream example, which mounts it read-write), no privilege flag is set, and no PID namespace
is shared. The widening is of file surface only, never of privilege posture. **Falsifier:** if a
future cAdvisor release drops the `/rootfs`/`/var/lib/docker` requirement or adds a `/proc` one,
this amendment is stale and must be re-derived against that release's own docs.

**Live finding (Task 3, 2026-09-07):** cAdvisor's own startup log reports
`Could not configure a source for OOM detection, disabling OOM events: open /dev/kmsg: no such
file or directory`. This is expected on this kernel/cgroup configuration (no `/dev/kmsg` bind, not
mounted per D-05's scope) and disables only OOM-event detection — cAdvisor started cleanly
otherwise and confirmed producing real per-container series for `app`, `postgres`, `caddy`,
`redpanda`, every other production container, and both nonprod containers, not merely
self-describing metrics. No `/var/run` permission error occurred. No mount was loosened in
response to this finding.

## postgres_exporter credential handling

Configured with `DATA_SOURCE_URI` (host, port, database and parameters only —
`postgres:5432/kanban_prod?sslmode=disable`, no scheme, no credentials), `DATA_SOURCE_USER`
(`monitoring`) and `DATA_SOURCE_PASS` as three SEPARATE environment variables, and deliberately
NO `DATA_SOURCE_NAME`. A password containing `@ : / ? # %` is structural inside a URI — it would
corrupt the connection string or redirect the connection to a host the password's own contents
chose — so the credential is never assembled into one by this compose file. The exporter itself
assembles the DSN with Go's `url.UserPassword(user, pass)`, which percent-encodes both components,
so the encoding is done by the library that owns the format. `DATA_SOURCE_NAME` outranks the
trio in the exporter's own resolution order, so its presence — even empty — would silently
reinstate the interpolated-credential DSN while the three correct variables sat there looking
right; it is asserted absent by Task 2's automated verify.

Cross-reference: same finding, same fix, as Phase 11 plan 11-07's CR-01 against
`docker/postgres-init/01-create-databases-and-roles.sh`.

**Empirical proof, not merely a design claim (Task 3, 2026-09-07):** the live `MONITORING_DB_PASS`
DOES contain at least one of `@ : / ? # %` (confirmed by a boolean shell test against the live
value on the VM; the value itself was never read into this document or any transcript). The
connection succeeded — `postgres_exporter`'s logs show no authentication failure and no DSN/URL
parse error, and `pg_stat_database_numbackends` returns real values for every database including
both `kanban_prod` and `kanban_nonprod`. That successful connection, against a password that
actually exercises the hazard, is the empirical proof that the split-variable form handles those
characters — not merely an assumption that it should.

## Falsifier

If a future `postgres_exporter` release changes its DSN-assembly library or its variable
precedence order, this section's safety claim is stale and must be re-verified against that
release's own documentation before trusting the split form again.

## Deployment date

2026-09-07.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
