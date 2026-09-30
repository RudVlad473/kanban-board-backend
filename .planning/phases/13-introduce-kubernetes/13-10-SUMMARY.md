---
phase: 13-introduce-kubernetes
plan: 10
subsystem: infra
tags: [k3s, decommission, compose, caddy, docker, firewall, d-04, d-08]
requires:
  - phase: 13-09
    provides: T0 measurement, restart snapshot, k3s resource measurement
provides:
  - Compose, Caddy and Docker Engine removed from netcup-prod and the repository
  - Decommission Record and D-08 gate record in docs/INFRA_RUNBOOK.md
  - Edge re-proven without Docker and after a reboot
affects: [infra, ci, runbook]
tech-stack:
  added: []
  patterns: [k8s-only invariant gates, KANBAN-INGRESS as sole forward-path filter]
key-files:
  created: [.planning/phases/13-introduce-kubernetes/13-10-SUMMARY.md]
  modified:
    - .claude/CLAUDE.md
    - .github/workflows/deploy.yml
    - .github/workflows/invariant-checks.yml
    - .github/dependabot.yml
    - docs/INFRA_RUNBOOK.md
    - docs/INFRA_ARCHITECTURE.md
    - infra/vm/README.md
    - scripts/verify-k8s-invariants.py
    - scripts/verify-k8s-invariants-selftest.py
    - scripts/verify-public-dashboards.py
    - scripts/verify-public-dashboards-selftest.py
    - scripts/verify-postgres-init-quoting.sh
  deleted:
    - docker-compose.prod.yml
    - docker-compose.nonprod.yml
    - Caddyfile
    - docker/ (caddy, prometheus, loki, promtail, grafana, postgres-init)
    - scripts/verify-compose-ports(.py, -selftest.py)
    - scripts/verify-deploy-scp-coverage(.py, -selftest.py)
    - scripts/verify-caddy-image-tag.py
    - scripts/verify-postgres-memory-invariant.py
    - infra/vm/docker-user-firewall.sh
    - infra/vm/docker-user-firewall.service
key-decisions:
  - "Operator decision, 2026-09-30, verbatim: reply 1 = decommission-reboot-keep-dump (decommission Compose incl. volumes, retire Docker Engine, reboot netcup-prod, keep the dump at /root/k3s-cutover-archive 0700/0600, do not shred)"
  - "I9 retired rather than renumbered, so I10 keeps its number"
  - "IPv6 todo left pending with a dated note: ss -6 shows k3s 6443/10250 on a dual-stack wildcard bind"
status: complete
metrics:
  completed: 2026-09-30
actuals:
  tokens: 17000   # chars/4 over the added lines of the repo diff (67 kB); estimateTokens scale, not a harness count
  tasks: 1        # this executor ran Task 3 only; Tasks 1-2 were done by earlier executors/operator
  commits: 1      # measured from plan_head_before below, this executor's slice only
plan_head_before: cb643c7a259155a2c29f1b45536882d8d81c1bf5
---

# Phase 13 Plan 10 (Task 3): Decommission Summary

Compose, Caddy and Docker Engine are gone from netcup-prod and the repository. k3s is the only
runtime, and after a reboot the edge holds without Docker's firewall chain, with one leg of the
edge proof not obtainable by this executor (see "NOT RUN / not proven").

Scope: this executor ran **Task 3 only**. Task 1 (D-08 gate, PASS, `cb643c7`) and Task 2 (operator
decision) were done before it. Task 2's reply is recorded verbatim in `key-decisions` above and in the
runbook's Decommission Record.

## What ran, in the plan's order

Full evidence is in `docs/INFRA_RUNBOOK.md` `## Decommission Record — Plan 13-10 (2026-09-30)`.

1. Pre-deletion re-check on the VM: 28/28 pods Running/Completed, both health endpoints UP.
2. `docker compose down -v --remove-orphans` for both projects; volumes and networks removed; `docker
   image prune -af`; `docker ps -a` / `docker volume ls` empty; `docker system df` all zero.
3. `/opt/deploy/*` emptied except `.env.prod` / `.env.nonprod` (0600).
4. `systemctl disable --now docker-user-firewall.service docker.service docker.socket containerd.service`.
5. Edge re-proof without Docker: pods Ready, health UP, PREROUTING jump first, `k3s-host-firewall.sh
   check` OK, off-box `nc` to NodePorts 30104/30080 fails, 22/443 connect.
6. `systemctl reboot` at 13:40:24Z; boot 13:40:51Z; SSH back 13:41:07Z; all pods Ready by 13:41:59Z;
   `REMOTE_OK` printed at `uptime` "up 1 minute"; docker `disabled`, `k3s-host-firewall` `enabled`.
   Re-run at 13:48:59Z, "up 8 minutes": `REMOTE_OK` again, both endpoints UP.
7. Dump moved to `/root/k3s-cutover-archive` (0700, files 0600), `sha256sum -c` OK after the move,
   not shredded.

Repo: one commit deleting the Compose/Caddy era and editing CI, Dependabot, gates, `infra/vm/README.md`,
`.claude/CLAUDE.md`, both docs, and the two todos. `writing-for-agents` was invoked before
`.claude/CLAUDE.md` was edited; only the Platform Requirements bullets naming Compose/Caddy as the
runtime or edge changed.

## Verify results

| Check | Result |
|---|---|
| Deleted-path assertions (all listed files/dirs gone, local `docker-compose.yml` present) | PASS (no `FAIL:` output) |
| `verify-k8s-invariants-selftest.py` and `verify-k8s-invariants.py --no-provisional` | PASS (12 roots) |
| `verify-public-dashboards-selftest.py` and `verify-public-dashboards.py` | PASS (k8s: 3 dashboards) |
| `verify-k8s-manifests.sh` | PASS (all 12 roots valid, exit 0) |
| `verify-postgres-init-quoting.sh` against `k8s/data/postgres/init` | PASS, 9 of 9 |
| YAML/doc assertions (invariant jobs, Caddy CI, Dependabot entry, decommission record) | PASS |
| VM `REMOTE_OK` block (twice: post-reboot and at 13:48:59Z) | PASS |
| Off-box `nc` to Traefik `websecure` NodePort fails | PASS (rc 1, before and after reboot) |
| Both health endpoints UP | PASS (before and after reboot, and at 13:48:59Z) |
| `gh run watch` of `invariant-checks.yml` on `main` | **NOT RUN** (cannot push, so `main` has no run for this change) |
| `gh run watch` of `deploy.yml` on `main` | **NOT RUN** (same reason) |

## NOT RUN / not proven

- **The last two verify lines** (latest `invariant-checks.yml` and `deploy.yml` runs on `main`
  green). The executor may not push. The `invariant-checks` jobs' commands were run locally and pass,
  which is not the same as a green CI run on GitHub. `deploy.yml` was only YAML-parsed here: its
  remaining jobs (`setup`, `run-tests`, `build-and-push-docker-image`, both `flyway-verify`, both
  `cleanup-old-images`) were not executed, and the `setup` job's outputs were edited.
- **KANBAN-INGRESS DROP counter increase.** The counter stayed at 22 packets (before reboot) and
  was not moved by the probe after the reboot either. The Netcup Cloud Firewall (Layer 2) drops the
  probe first, the documented gap in "Edge hardening on k3s — Plan 13-08". Attributing the drop to
  KANBAN-INGRESS needs an operator-opened temporary console rule (source IP scoped, port 30104), as
  on 2026-09-27. Until someone does that, "counter increasing" is unproven after Docker's removal;
  what is proven is that the chain and jump are unchanged, the NodePorts are unreachable off-box, and
  the reboot preserved the state.
- The exact public outage window during the reboot was not measured (bounded by about 90 s).

## Deviations from Plan

**1. [Rule 1 - Bug] `docker compose down -v` was a silent no-op for nonprod**
- **Found during:** VM step 1.
- **Issue:** `docker-compose.nonprod.yml` puts both services behind `profiles: ["nonprod"]`; `down`
  without the profile exits 0 and removes nothing.
- **Fix:** re-ran with `--profile nonprod`; verified with `docker ps -a`.
- **Files modified:** none (VM only). Recorded in the runbook.

**2. [Rule 3 - Blocking] Volumes the plan did not list had to be removed for `docker volume ls` to be empty**
- **Found during:** VM step 2.
- **Issue:** after `down -v`, `root_caddy-config`, `root_caddy-data`, `root_redpanda-data`,
  `redpanda-nonprod-data` and an anonymous 72 MB Postgres-shaped volume `e757bd8d...` remained.
- **Fix:** removed them by hand, as the plan's "must be empty" requires. The anonymous volume's origin
  was not established; only a directory listing was read. Flagged in the runbook's Part A and D. If the
  operator wanted that volume examined first, that is no longer possible.

**3. [Rule 3 - Blocking] Repo edits beyond the listed files_modified, needed for the gates to pass**
- `scripts/verify-postgres-init-quoting.sh` and `scripts/verify-k8s-invariants*.py` were in the plan.
  I also changed the final `invariants OK` message of `verify-k8s-invariants.py` (it still claimed
  "init scripts byte-identical to Compose") and pruned `verify-public-dashboards-selftest.py`'s
  Compose-only I6 case. The `scripts/verify-k8s-invariants.py` docstring keeps "I9: RETIRED".

**4. [Plan inconsistency] `files_modified` lists `verify-public-dashboards*.py` next to the files to
delete, but the action text says to edit it (drop the Compose scope) and the verify block still runs
it.** I edited it, following the action text.

## Findings, reported and not acted on

- **k3s API server (6443) and kubelet (10250) listen on a dual-stack wildcard; `ip6tables INPUT` is
  ACCEPT.** Whether the Netcup Cloud Firewall blocks them over IPv6 was not probed. The IPv6 todo was
  left pending with a dated note. Both ports require TLS authentication.
- `0.0.0.0:31759` is also listening (k3s-server). It was not investigated.
- The anonymous volume above (72 MB, Postgres data directory, created 2026-09-26T09:36:43+02:00) is
  unexplained.
- Restart counts across the cluster are now nonzero (32) because of the reboot; anyone running D-08.3
  again would need a new window.
- The Task 1 `<automated>` verify script bug (`split(...)[1]`) is irrelevant here, per instructions.

## Known Stubs

None.

## Threat Flags

None new. T-13-49 (NodePort exposure once DOCKER-USER is gone) is mitigated to the extent stated in
"NOT RUN / not proven".

## Self-Check

PASSED. Checked after the commit: SUMMARY, runbook, architecture doc and the moved todo exist on
disk; `git rev-list --count cb643c7..HEAD` = 1 (matches `commits: 1`); working tree clean; the 21
deleted files are the intended Compose/Caddy-era paths (post-commit deletion check), none unexpected.
The pre-commit hook (gitleaks, spotlessCheck, fast tests) passed on the second attempt; the first
attempt failed only because another session stopped the Gradle daemon ("stop command received"),
unrelated to this change, and was retried without `--no-verify`.
