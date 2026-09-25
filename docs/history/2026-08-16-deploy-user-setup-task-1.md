# Deploy user setup — Plan 05-05 Task 1 (2026-08-16)

A dedicated, minimally-privileged deploy identity was created for GitHub Actions, replacing the
root-only access model this VM had until now (see "Access" above — before this, there was no
non-root user and no `docker` group member at all).

## What was done

- New user `deploy`, created via `adduser --disabled-password --gecos ""` (no password login), added
  to the `docker` group (`usermod -aG docker deploy`). Confirmed it can run `docker compose` and
  cannot `sudo` without a password (`sudo -n true` fails as expected).
- A new, dedicated ed25519 keypair (`netcup_deploy_key`) generated on the operator's own machine —
  never on the VM — and its public half appended to `/home/deploy/.ssh/authorized_keys`. Distinct
  from the pre-existing personal admin key (`id_ed25519_netcup_prod`); either can be revoked
  independently.
- The deploy artifacts (`docker-compose.prod.yml`, `Caddyfile`, `.env.prod`) were moved from
  `/root/` (where Plan 05-04's manual deploy had left them, since that deploy pre-dates this
  dedicated-user setup) to a new `/opt/deploy/kanban-board-backend/` directory, owned by `deploy`.
  `/root` is not an appropriate directory to hand ownership of to a second user.

## Deviation found and fixed: Compose project name was directory-derived

**What went wrong:** Docker Compose derives its project name (and, from that, every named volume's
actual name) from the CWD basename unless pinned explicitly. The containers Plan 05-04 started from
`/root` were named `root-app-1`/`root-caddy-1`/`root-redpanda-1` under project `root`, with volumes
`root_redpanda-data`/`root_caddy-data`/`root_caddy-config`. Moving the compose file to
`/opt/deploy/kanban-board-backend/` silently changed the *default* project name to
`kanban-board-backend` for any future `docker compose up` run from there — which would create a
**second, unrelated** set of containers and fresh, empty named volumes rather than recognizing the
already-running ones.

**Caught by:** running `docker compose ps` as `deploy` from the new directory returned nothing for
containers that were demonstrably running (confirmed via plain `docker ps`), and the containers'
`com.docker.compose.project`/`working_dir` labels showed `root`/`/root` — not a permissions issue,
a genuine second project.

**Fix:** added a top-level `name: kanban-board-backend` key to `docker-compose.prod.yml` (Compose
Spec v2+), pinning the project name in the file itself, independent of whatever directory it's run
from. This is the same category of fix as the pre-existing `.env` vs `.env.prod` auto-load gotcha
documented above — never trust an implicit, directory/filename-derived default for something a
future move or rename can silently change.

**Cutover performed (real downtime, seconds-to-low-minutes, not the planned zero-downtime path):**
old `root`-project containers stopped and removed (`docker compose -p root ... down`); stack
brought up fresh under the pinned name. This created empty `kanban-board-backend_*` volumes,
**losing the 14 registered Avro schemas** (they remained intact in the now-orphaned
`root_redpanda-data` volume) — caught immediately via `rpk registry subject list` returning zero
subjects. Fixed by copying `root_redpanda-data`'s contents into `kanban-board-backend_redpanda-data`
via a one-off `alpine` container (`cp -a`), then restarting `redpanda` — `rpk registry subject list`
confirmed all 14 subjects back. Caddy also got a **fresh** Let's Encrypt certificate during this
cutover (its old cert/config volumes were likewise orphaned) — succeeded on the first attempt, no
rate-limit risk incurred, so its orphaned `root_caddy-*` volumes were left alone rather than risking
another cert request to "fix" something already working.

The orphaned `root_redpanda-data`, `root_caddy-data`, `root_caddy-config` volumes still exist on the
VM (not deleted) — safe to remove once this cutover has been running stable for a while, not done as
part of this task.

## A second incident during the same cutover: the app container's healthy restart

While bringing `app` back up immediately after the redpanda volume migration, it was explicitly
stopped as a side effect of `docker compose stop app redpanda` (issued together with redpanda,
since `app` depends on the registry). The very next `docker compose up -d app` attempt appeared to
leave it stopped rather than restarting — most likely `app`'s `depends_on: redpanda: condition:
service_healthy` racing a `redpanda` that had only been up ~10 seconds and not yet reported healthy.
Resolved by explicitly confirming `redpanda` was `(healthy)` first, then re-running
`docker compose up -d app` and observing it continuously for over 2 minutes (past the ~90-second mark
where the previous instance died) before considering it stable. Verified via `docker events`/`docker
inspect` (not assumed) that the earlier stop was this session's own command and not an external
process — no cron job, systemd timer, or other session on the VM was responsible.

## Re-verification after both fixes

- `docker compose ps`: `app`/`redpanda` `(healthy)`, `caddy` `running`, stable for 2+ minutes of
  continuous observation.
- Off-VM: `curl .../api/actuator/health` → `200`.
- `rpk registry subject list` → 14 subjects, matching the pre-cutover count exactly.

## Repository secret inventory (names and purpose only — no values recorded here)

| Secret name | Purpose | Origin |
|---|---|---|
| `NETCUP_SSH_KEY` | Private half of the dedicated deploy keypair; authenticates the deploy job as `deploy` | Generated locally (`ssh-keygen -t ed25519`), never present on the VM before being appended to `deploy`'s `authorized_keys` |
| `NETCUP_DEPLOY_USER` | The non-root deploy identity (`deploy`) the SSH/SCP deploy steps authenticate as | Created on the VM this task |
| `NETCUP_HOST` | The VM's public IPv4 (`159.195.114.230`) | Already known (plan 05-03) |
| `NETCUP_HOST_FINGERPRINT` | SSH host key fingerprint, pins the deploy connection against MITM — never disabled | `ssh-keyscan` + `ssh-keygen -lf` against the real host |
| `DB_HOST` / `DB_NAME` / `DB_USER` / `DB_PASS` | Neon's **direct** (non-pooled) endpoint, split into fields — consumed by plan 05-05 Task 2's Flyway migration-verification job | Neon dashboard, direct connection string |

**Deviation from the plan's acceptance criteria, recorded deliberately:** the plan calls for six
secrets under names distinct from the AWS-era ones specifically so a future reader can't confuse a
live secret with a stale one by name alone. `DB_HOST`/`DB_NAME`/`DB_USER`/`DB_PASS` **reuse the
AWS-era secret names** (operator's explicit choice — "reused variable names from last year") rather
than new ones. `DB_NAME`/`DB_USER`/`DB_PASS` were updated the same day this task ran; `DB_HOST` was
updated slightly later in the same session after an initial gap where its timestamp lagged the other
three — confirmed via `gh secret list` (names/timestamps only, values never seen) before treating it
as done. If these are ever audited later, do not assume a `DB_*`-named secret is safe by name alone —
check its last-updated timestamp against this entry's date (2026-08-16).

**Also deliberately not registered:** a secret for Neon's **pooled** connection string. The original
plan/research assumed one was needed for the app's runtime config, but that config already lives in
`.env.prod` on the VM (plan 05-04) and nothing in this workflow writes or reads it — the pooled
secret would have had zero consumers. Dropped as unnecessary rather than registered for its own sake.

**Update (2026-08-18, Plan 09-02 Task 1) — this table is now historically inaccurate and
deliberately left in place, not rewritten.** As of Plan 09-01, every one of the nine deploy secrets
named above (`NETCUP_SSH_KEY`, `NETCUP_DEPLOY_USER`, `NETCUP_HOST`, `NETCUP_HOST_FINGERPRINT`,
`DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASS`) plus `DOCKERHUB_TOKEN` also exists as an
**environment-scoped** secret in both `production` and `staging` (see "Nonprod CI deploy identity
and environment-scoped secrets — Plan 09-01" below for the full inventory and provenance). Plan
09-02's Task 1 is the sweep that removes the repository-level copies recorded in this table,
leaving `NVD_API_KEY` (consumed only by `security-scan.yml`, a workflow whose jobs declare no
`environment:`) as the sole remaining repository-scoped secret. **That sweep — the actual
`gh secret delete` calls and the live push-to-`master` proof required by Task 1 parts B/C — was not
executed inside this worktree**, for the same reason Plan 09-01's own Task 3 gives below ("Task 3
deliberately deferred"): the deletion is rated `costly` reversibility (GitHub secrets are
write-only; a repository secret, once deleted, cannot be recovered) and its proof requires a live
push to `origin/master` and observation of the resulting GitHub Actions run — both of which must
happen from the merged tree under the operator's/coordinator's direct observation, not from an
unmerged per-agent worktree branch. This worktree's part of Task 1 is limited to: (a) the mechanical
precondition re-check below, confirming every job in `deploy.yml` that interpolates a `secrets.`
value already declares an `environment:` (true both before and after this plan's Task 2/3 additions,
since the three new jobs also declare `environment: staging`), and (b) this annotation itself. See
this plan's own SUMMARY.md for the exact remaining steps.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
