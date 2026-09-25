# Nonprod CI deploy identity and environment-scoped secrets — Plan 09-01 (2026-08-18)

CI-02's credential boundary was built: two GitHub Environments holding every deploy secret, and a
second, filesystem-confined Linux deploy identity on the VM (`deploy-nonprod`), distinct from
production's `deploy`. This is provisioning only — Task 3 (deferred, see below) wires
`.github/workflows/deploy.yml`'s new jobs to actually consume these.

## Task 1 checkpoint — decision and residuals accepted

The human operator selected **option-a**: `deploy-nonprod` confined by standard Unix filesystem
permissions only (no SSH forced-command/restricted-shell hardening — D-02 stands), plus one
Docker Hub token duplicated into both GitHub Environments (no second Docker Hub account). Two
residual risks are explicitly accepted, not overlooked:

- **T-09-03 (docker-group root-equivalence):** `deploy-nonprod` must be in the `docker` group to
  run `docker compose`. Docker group membership is root-equivalent on the host — a holder of the
  nonprod SSH key can `docker run -v /opt/deploy/kanban-board-backend:/x ...` and read
  `.env.prod` regardless of this task's `chmod 750`. Not new — production's own `deploy` user has
  had the same property since Plan 05-05 — and this phase strictly narrows the blast radius versus
  running nonprod deploys as `deploy` itself. Accepted; not mitigated further.
- **T-09-09 (account-wide Docker Hub token):** per-repository Docker Hub token scoping is a
  Pro/Team-plan feature (tier unverified). The same `DOCKERHUB_TOKEN` value is stored in both
  `production` and `staging` environments, so a compromised nonprod workflow step holds a
  credential with delete rights over production's image repository too. Accepted; the alternative
  (a second Docker Hub account/namespace) was rejected as disproportionate complexity CONTEXT.md's
  decisions did not call for.

Mechanical resource names confirmed by the operator: Linux user `deploy-nonprod`, Docker Hub
repository `rudenkovladimir/kanban-board-backend-nonprod`, GitHub Environments `production`/
`staging`, identical secret NAMES in both environments with per-environment values.

## What was done

**A. Two GitHub Environments, zero protection rules (D-04).** Created via
`gh api --method PUT repos/RudVlad473/kanban-board-backend/environments/{production,staging}`.
Both read back with an empty `protection_rules` array and `deployment_branch_policy: null` — no
required reviewers, no wait timer, no branch restriction, confirmed by `gh api
repos/RudVlad473/kanban-board-backend/environments --jq '.total_count'` returning `2`.

**B. `deploy-nonprod` VM identity (D-01, D-02).** Executed as root over the operator's own
authorized SSH session (`ssh netcup-prod`), the same precedent as "Deploy user setup — Plan 05-05
Task 1":
- `adduser --disabled-password --gecos "" deploy-nonprod`; `usermod -aG docker deploy-nonprod`
  (the account was already a `docker` group member as an `adduser` default, `usermod` reported "no
  changes" — `id deploy-nonprod` confirmed the group present regardless).
- A NEW ed25519 keypair (`netcup_deploy_nonprod_key`) generated locally, outside this repository's
  working tree (a session scratch directory, never under `.planning/` or the repo root), distinct
  from production's `netcup_deploy_key`. Only the public half was appended to
  `/home/deploy-nonprod/.ssh/authorized_keys` (`chmod 700` the directory, `600` the file, both
  owned by `deploy-nonprod`).
- `/opt/deploy/kanban-board-nonprod` re-owned to `deploy-nonprod:deploy-nonprod`, mode `750`;
  `.env.nonprod` stayed mode `600`, owned by `deploy-nonprod`.
- `/opt/deploy/kanban-board-backend` (production's directory) tightened from Ubuntu's `adduser`
  default `755` to `750`, closing the read path `deploy-nonprod` would otherwise have had; `.env.prod`
  confirmed still mode `600`.

**Deviation found and fixed: the first nonprod keypair's private half was deleted, not retained.**
Production's `netcup_deploy_key` precedent (see "Deploy user setup — Plan 05-05 Task 1" above) keeps
its private key permanently at the operator's local `~/.ssh/`, giving the operator a durable copy
for future manual/debugging access outside CI — GitHub Environment secrets are write-only, so once a
key exists only there, it cannot be retrieved again by any means. The first `netcup_deploy_nonprod_key`
generated for this task was written to a session-scoped scratch directory and deleted immediately
after use, breaking that precedent. **Caught before this document was finalized**, not after: the
key was rotated — a second `netcup_deploy_nonprod_key` generated and persisted at the operator's
local `~/.ssh/netcup_deploy_nonprod_key` (mirroring `netcup_deploy_key`'s location), the VM's
`/home/deploy-nonprod/.ssh/authorized_keys` **replaced** (not appended — still exactly one key) with
the new public half, and the `staging` environment's `NETCUP_SSH_KEY` secret overwritten with the
new private half. All proof outputs below reflect this final, persisted key.

**C. Nonprod Docker Hub repository and environment secrets.** `kanban-board-backend-nonprod`
created under namespace `rudenkovladimir` via the same JWT-login-then-POST pattern
`cleanup-old-images` already uses (`POST /v2/users/login/` for a token, then `POST
/v2/repositories/` with `Authorization: JWT <token>`) — HTTP `201`, `is_private: false`. All nine
secrets set in both environments via `gh secret set NAME --env {production,staging}`, every value
piped from a file or an SSH command's stdout directly into `gh secret set`'s stdin — never via
`--body`, never echoed to a terminal, never written into this repository. `DB_*` values for both
environments were recovered as root from the VM's own already-deployed `.env.prod`/`.env.nonprod`
files rather than re-typed; `NETCUP_HOST`/`NETCUP_DEPLOY_USER`/`NETCUP_HOST_FINGERPRINT` are
non-secret/re-derivable per this document's existing "Repository secret inventory" entry; the
production `NETCUP_SSH_KEY` reused the existing local keypair (fingerprint-matched against the VM's
`deploy` `authorized_keys` before use, confirming it was the correct file); `DOCKERHUB_TOKEN` was
read from a local plaintext file outside this repository and piped straight into `gh secret set`,
never displayed.

## Verbatim proof outputs

**`id deploy-nonprod`:**
```
uid=1001(deploy-nonprod) gid=1001(deploy-nonprod) groups=1001(deploy-nonprod),100(users),990(docker)
```

**`sudo -u deploy-nonprod sudo -n true`:**
```
sudo: a password is required
```
(exit code 1 — no passwordless sudo, matching `deploy`'s existing posture)

**Directory/file modes, both directions:**
```
stat -c '%U %G %a' /opt/deploy/kanban-board-nonprod            -> deploy-nonprod deploy-nonprod 750
stat -c '%U %a' /opt/deploy/kanban-board-nonprod/.env.nonprod  -> deploy-nonprod 600
stat -c '%U %a' /opt/deploy/kanban-board-backend                -> deploy 750
stat -c '%a' /opt/deploy/kanban-board-backend/.env.prod         -> 600
```

**`deploy-nonprod` locked out of production's directory:**
```
$ sudo -u deploy-nonprod ls /opt/deploy/kanban-board-backend/
ls: cannot open directory '/opt/deploy/kanban-board-backend/': Permission denied

$ sudo -u deploy-nonprod cat /opt/deploy/kanban-board-backend/.env.prod
cat: /opt/deploy/kanban-board-backend/.env.prod: Permission denied
```

**`deploy-nonprod` can operate its own Compose stack:**
```
$ sudo -u deploy-nonprod docker compose -f /opt/deploy/kanban-board-nonprod/docker-compose.nonprod.yml \
    --env-file /opt/deploy/kanban-board-nonprod/.env.nonprod --profile nonprod ps
NAME                      IMAGE                                               STATUS
kanban-nonprod-app        rudenkovladimir/kanban-board-backend:777cb27        Up 5 hours (healthy)
kanban-nonprod-redpanda   docker.redpanda.com/redpandadata/redpanda:v26.2.1   Up 5 hours (healthy)
```

**Off-VM proof the new key works, genuinely as `deploy-nonprod`:**
```
$ ssh -i <nonprod key> -o IdentitiesOnly=yes deploy-nonprod@159.195.114.230 'whoami && pwd && docker ps --format "{{.Names}}"'
deploy-nonprod
/home/deploy-nonprod
kanban-board-backend-app-1
kanban-nonprod-app
kanban-nonprod-redpanda
kanban-board-backend-caddy-1
kanban-board-backend-redpanda-1
```
(the full container list is visible — Docker group membership, not directory ownership, is what
gates `docker ps`/`docker exec`, which is exactly T-09-03's accepted residual)

**Fingerprints — two distinct keys, exactly one entry each:**
```
deploy-nonprod:  256 SHA256:725Z4K23Pi3u1z3Kd/0S6iAyNhH7BHgaA2aBthTPcJI netcup_deploy_nonprod_key (ED25519)
deploy (prod):   256 SHA256:MFlDKTQq+e++HcgQyzKsVg6hJAJarq+fk4stKbH6AM4 andre@DESKTOP-88EQU47 (ED25519)
wc -l /home/deploy-nonprod/.ssh/authorized_keys -> 1
```

**Docker Hub repository, public:**
```
$ curl -s https://hub.docker.com/v2/repositories/rudenkovladimir/kanban-board-backend-nonprod/ | jq -r '.name, .is_private'
kanban-board-backend-nonprod
false
```

**GitHub Environments, zero protection rules:**
```
$ gh api repos/RudVlad473/kanban-board-backend/environments --jq '[.environments[] | {name, rules: (.protection_rules | length)}]'
[{"name":"production","rules":0},{"name":"staging","rules":0}]
```

## Environment-scoped secret inventory (names and purpose only — no values recorded here)

| Secret name | `production` value origin | `staging` value origin |
|---|---|---|
| `NETCUP_SSH_KEY` | Existing `netcup_deploy_key` private half (fingerprint-verified against the VM's `deploy` `authorized_keys` before reuse) | NEW `netcup_deploy_nonprod_key`, generated this task, never on the VM before being appended to `deploy-nonprod`'s `authorized_keys` |
| `NETCUP_DEPLOY_USER` | `deploy` | `deploy-nonprod` |
| `NETCUP_HOST` | `159.195.114.230` | `159.195.114.230` (same physical VM) |
| `NETCUP_HOST_FINGERPRINT` | Re-derived via `ssh-keyscan \| ssh-keygen -lf -` against the real host | Same value (same host key) |
| `DB_HOST` / `DB_NAME` / `DB_USER` / `DB_PASS` | Recovered as root from `/opt/deploy/kanban-board-backend/.env.prod`; direct (non-pooler) endpoint confirmed | Recovered as root from `/opt/deploy/kanban-board-nonprod/.env.nonprod`; direct (non-pooler) endpoint confirmed — nonprod Neon branch |
| `DOCKERHUB_TOKEN` | Read from a local plaintext file outside this repository, piped directly into `gh secret set`, never displayed | Same value (option-a: account-wide token duplicated, not a second Docker Hub account — T-09-09) |

Both environments hold the same nine secret NAMES with per-environment values —
`gh secret list --env production` and `gh secret list --env staging` both return exactly
`DB_HOST,DB_NAME,DB_PASS,DB_USER,DOCKERHUB_TOKEN,NETCUP_DEPLOY_USER,NETCUP_HOST,NETCUP_HOST_FINGERPRINT,NETCUP_SSH_KEY`.

**Repository-level sweep completed (Plan 09-02, Task 1, 2026-08-19).** The nine repository-level
secrets above were retained as a safety net through plan 09-01's first live-verified run, then
deleted (`gh secret delete`, no `--env`) once that run was proven green. A tenth, orphaned
repository secret (`NONPROD_RESET_TOKEN`) was found alongside them at sweep time — unreferenced by
any workflow or doc, apparent leftover from earlier manual testing — and deleted in the same pass
after explicit confirmation. `gh secret list --json name --jq '[.[].name]|join(",")'` now returns
exactly `NVD_API_KEY`, matching this plan's acceptance criterion. A push immediately after the sweep
(`08b253b`, run `32233904310`) confirmed both deploy paths still resolve every secret correctly
through their `environment:` declaration alone — full green run, `run-tests` through both
`cleanup-old-images*` jobs.

## Task 3 deliberately deferred — not run in this session

This plan's Task 3 (`.github/workflows/deploy.yml` edit, adding `flyway-verify-nonprod` and
`deploy-to-nonprod`, retrofitting `environment:` onto every secret-reading job, and pushing to
`master` to prove a live end-to-end run) was **not executed in this session**. This work was done
inside an isolated git worktree — its commits live on a private per-agent branch until the
orchestrator merges them back to `master`. Task 3 as written pushes directly to `origin/master` and
triggers a real, production-impacting GitHub Actions deploy run; that must happen under the human
operator's direct observation, not fire unattended from a background agent inside a worktree.
`.github/workflows/deploy.yml` is unmodified by this session. Task 2's provisioning above is
complete and independently verified; Task 3 is left for the orchestrator to run (or hand to a
follow-up session) once this worktree's commits have landed on `master`.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
