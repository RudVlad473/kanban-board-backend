# CI Flyway verification over SSH — Plan 11-05 (2026-08-26)

Closes the "Known red window in CI" opened by plan 11-02's cutover (see that section above): from
the moment production/staging's GitHub Environment secrets pointed at the self-hosted instance,
`flyway-verify`/`flyway-verify-nonprod` failed on every push — a GitHub-hosted runner has no
network route to a container that publishes no host port (D-03). This plan rewrites both jobs to
reach the self-hosted database the same way this pipeline's other post-deploy steps already do:
over SSH, from ON the VM.

## Mechanism

Both jobs now run the Flyway CLI as a one-off container **on the VM**, not on the runner:

1. **Stage** (`appleboy/scp-action`, digest-pinned, same action `deploy-to-netcup` uses) — copies
   `src/main/resources/db/migration/` to a per-environment staging path, `rm: true` clearing any
   file left over from a prior run (e.g. a since-renamed migration) before copying, so the staged
   set always exactly mirrors what was checked out:
   - production: `/opt/deploy/kanban-board-backend/ci-flyway-verify/production/`
   - nonprod: `/opt/deploy/kanban-board-nonprod/ci-flyway-verify/staging/`

   Both are distinct from the deploy jobs' own SCP targets (`/opt/deploy/kanban-board-backend/`,
   `/opt/deploy/kanban-board-nonprod/`) — production's verify job can run while a previous push's
   deploy still holds the `deploy-to-netcup-vm` concurrency lock, so this path must never collide
   with what that job writes.

2. **Verify** (`appleboy/ssh-action`, digest-pinned, same action every other SSH step in this file
   uses) — a script starting with the mandatory `set -e` (this action has no fail-fast input of
   its own; see "Automated Avro schema registration — Plan 09-03" below for the live-verification
   history of that requirement) that:
   - asserts the target is resolvable and reachable at all (`docker run --rm --network kanban-db
     postgres:16 pg_isready -h postgres -p 5432 -t 5`) — replaces the old endpoint-suffix guard,
     which inspected a hostname marker belonging to the retired managed provider's pooler and can
     never appear on a Compose service name, so it was dead code;
   - runs `flyway/flyway:11.7.2 migrate` (unchanged version) as a one-off container attached to the
     shared `kanban-db` network, `FLYWAY_URL=jdbc:postgresql://postgres:5432/<database>` with no
     TLS parameter (the connection is a Docker bridge hop; the target has no TLS listener), mounting
     the staged directory read-only at `/flyway/sql`.

Each job keeps its own `environment:` declaration (`production`/`staging`) unchanged, so the
identical `secrets.NETCUP_*`/`secrets.DB_USER`/`secrets.DB_PASS` references resolve against each
environment's own values — the same mechanism this file's other environment-scoped jobs already
rely on.

**`DB_HOST`/`DB_NAME` moved from GitHub Environment *secrets* to *variables*** this plan
(`postgres`/`kanban_prod` for production, `postgres`/`kanban_nonprod` for staging) — a Rule 2
addition made mid-execution, not in the original plan text. A database hostname/name is not
sensitive, and GitHub redacts any log line containing a registered secret's value regardless of
where it came from — so as long as `DB_NAME` stayed a secret, the actual resolved database name
would never appear unmasked in either job's log, defeating the whole point of proving a
wrong-environment scoping mistake (T-11-26) would be visible rather than silently hidden.
`DB_USER`/`DB_PASS` remain real secrets; only the two non-sensitive values moved.

## Decision (D-13)

**Task 1 checkpoint (`gate="blocking-human"`), answered before Task 2 started:**

> **Decision presented:** which mechanism do the CI Flyway verification jobs use to reach the
> self-hosted database — a one-off container run on the VM over SSH (Option A), or a literal
> local port-forward from the runner (Option B)?
>
> **Option A — one-off container on the VM, over SSH:** no port exposed anywhere, so D-03 keeps
> holding unmodified; the pattern already runs in this repository for schema registration, using
> the same digest-pinned action and secret set; container-name DNS resolves correctly because the
> container runs inside the network's own namespace. Cost: the migration `.sql` files must be
> copied to the VM on every run.
>
> **Option B — literal local port-forward:** matches D-13's literal wording; keeps the Flyway
> container on the runner. Cost: no clean target — either a loopback-only host bind (a literal,
> undiscussed exception to D-03) or the container's internal bridge address (resolved in the SSH
> server's own namespace, unstable across every container recreate — a real CI-flake source).
>
> **Operator's answer, verbatim (via AskUserQuestion):** "Option A: one-off container over SSH
> (recommended)"
>
> **Reasoning accepted:** no port exposure anywhere, so D-03 keeps holding unmodified; the pattern
> already runs in this repository for schema registration (`register-schemas-production`), using
> the same digest-pinned action and the same secret set; container-name DNS resolution works
> because the container runs inside the network's own namespace. Trade-off accepted: the migration
> `.sql` directory must be copied to the VM on every verification run.

Option A was implemented exactly as decided — no port-forward variant, no `docker-compose.prod.yml`
change, no exception to D-03 of any shape.

## Why not an ephemeral database in CI

The obvious simplification a future reader will propose: skip the VM/SSH complexity entirely and
spin up a throwaway Postgres container directly in the GitHub-hosted runner, verifying migrations
against an empty database with no VM dependency at all. It is rejected — explicitly, by D-13, and
independently on the merits: it would prove something materially weaker than what this gate exists
to prove. Migrations that apply cleanly to an *empty* database can still conflict with the *real,
already-migrated* production/nonprod schema history — migration drift is exactly the failure class
this gate exists to catch, and an ephemeral database is blind to it by construction. Verifying
against the real reachable instance, not a stand-in, is the whole point.

## Live verification (2026-08-26)

**Run:** [`32985965535`](https://github.com/RudVlad473/kanban-board-backend/actions/runs/32985965535)
("CI/CD with Docker"), triggered by push of commit `a536e60` to `main`. Overall conclusion:
**success**, all jobs green (`setup`, `run-tests`, `build-and-push-docker-image`, `flyway-verify`,
`flyway-verify-nonprod`, `deploy-to-netcup`, `deploy-to-nonprod`, `register-schemas-production`,
`health-check-nonprod`, `cleanup-old-images`, `cleanup-old-images-nonprod`).

**`flyway-verify` (production), read from the job's own log, not the green check:**
```
Verifying Flyway migrations against database: kanban_prod
postgres:5432 - accepting connections
Database: jdbc:postgresql://postgres:5432/kanban_prod (PostgreSQL 16.15)
Successfully validated 8 migrations (execution time 00:00.037s)
Current version of schema "public": 8
Schema "public" is up to date. No migration necessary.
```

**`flyway-verify-nonprod` (staging), read from the job's own log:**
```
Verifying Flyway migrations against database: kanban_nonprod
postgres:5432 - accepting connections
Database: jdbc:postgresql://postgres:5432/kanban_nonprod (PostgreSQL 16.15)
Successfully validated 8 migrations (execution time 00:00.036s)
Current version of schema "public": 8
Schema "public" is up to date. No migration necessary.
```

Both database names above are the real, unmasked values printed by Flyway's own connection log and
this job's own echo — confirmed distinct, confirmed matching each job's own environment, neither
job's log shows the other's database. This is the direct proof T-11-26 required: had production's
`environment:` scoping resolved staging's credentials (or vice versa), this log excerpt would show
it, rather than showing a green check over a silent misconfiguration.

**Downstream deploys and public health, confirmed after the run:**
- `deploy-to-netcup` and `deploy-to-nonprod` both ran (`needs:` satisfied by their own
  environment's verify job only) and succeeded.
- `docker ps` on the VM, read directly (not inferred from the green check): `app` running
  `rudenkovladimir/kanban-board-backend:a536e60`; `kanban-nonprod-app` running
  `rudenkovladimir/kanban-board-backend-nonprod:a536e60` — both matching the pushed commit's short
  SHA exactly.
- `health-check-nonprod` passed after 2/30 attempts.
- Off-VM `curl https://kanban-board-rud-vlad-473.duckdns.org/api/actuator/health` →
  `200 {"status":"UP"}`; `curl https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api/actuator/health`
  → `200 {"status":"UP"}`.

**Deviation from a smooth run:** GitHub Actions itself was mid-incident when this push landed
(githubstatus.com reported "Actions: major outage", root-caused to a database/Vitess primary
failover, ~15:11–15:5x UTC 2026-08-26) — the `setup` job sat `queued` with no runner assigned for
roughly 20 minutes before GitHub's own infrastructure recovered and the run proceeded normally.
Not a defect in this plan's work; recorded here because a stuck-queued run is otherwise easy to
mistake for a workflow-file problem.

## Gate still bites

A real broken-migration push through actual CI was judged unnecessarily risky for what it would
prove (a throwaway branch manually triggering the real deploy-gated workflow against live
infrastructure, for evidence obtainable more safely another way) — so this used the safer path the
plan's own instructions name as the fallback: real evidence, gathered live against the real
self-hosted instance, without going through GitHub Actions or touching git history.

**Method:** on the VM, using the exact same command shape `flyway-verify` runs (`set -e` as the
first line, the same `pg_isready` reachability assertion, the same `docker run --rm --network
kanban-db ... flyway/flyway:11.7.2 migrate` invocation), against a disposable scratch database
(`ci_flyway_gate_test`, created and dropped in the same session, never touching `kanban_prod` or
`kanban_nonprod`) seeded with the real V1–V8 migrations plus one deliberately invalid
`V9__deliberately_broken.sql` (a column definition referencing a table that does not exist —
`REFERENCES table_that_does_not_exist(id)`), wrapped in a script that also prints a marker line
immediately after the Flyway invocation, to prove `set -e` — not just Flyway's own exit code —
actually stops the script.

**Result, verbatim:**
```
Migrating schema "public" to version "9 - deliberately broken"
ERROR: Migration of schema "public" to version "9 - deliberately broken" failed! Changes successfully rolled back.
ERROR: Script V9__deliberately_broken.sql failed
...
Message    : ERROR: syntax error at or near "REFERENCES"
=== Wrapping script exit code: 1 ===
```
The marker line (`MARKER_LINE_SHOULD_NOT_PRINT_IF_SET_MINUS_E_WORKS`) never printed — `set -e`
aborted the script at the failing `docker run`, exactly as it does in the real `flyway-verify`/
`flyway-verify-nonprod` jobs. This exercises the same exit-code chain already live-proven in this
repository for the adjacent mechanism (`AvroSchemaRegistrar` → non-zero JVM exit → `docker ... run`
propagates it → `set -e` aborts the script → the ssh-action step fails, see "Automated Avro schema
registration — Plan 09-03" above), substituting Flyway's own documented abort-on-first-failure
behavior for the registrar's `IllegalStateException` — Flyway's non-zero exit on a failed migration
was independently already proven live once before, too (commit `125eebb`'s deliberate failing run,
see "Automated deploy — Plan 05-05 Task 2 and Task 3" above). Both constituent links of this job's
own exit-code chain have now been proven live at least once; this session's test is the first to
prove both hold together, in this exact job shape, against the self-hosted instance.

## Verification date

2026-08-26.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
