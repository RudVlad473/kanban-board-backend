---
schema_version: 1
open_count: 1
waived_count: 0
fixed_count: 7
total_count: 8
last_updated: 2026-08-20T08:19:01.132Z
---

# Broken Windows Ledger

> Cross-phase defect register. With `workflow.windows_enforce` enabled, `/gsd-ship` blocks while `open_count > 0`.
> Waive with `gsd-tools windows waive <id> "<reason>"` (reason required).
> Mark fixed with `gsd-tools windows fixed <id>`.

| id | phase | kind | file | line | description | status | reason | recorded_at | resolved_at |
|----|-------|------|------|------|-------------|--------|--------|-------------|-------------|
| 1 | 05 | deviation | docker-compose.prod.yml |  | DB_URL_PARAMS env var name for the Neon JDBC URL query-string placeholder was chosen independently by plan 05-02 without visibility into plan 05-01's actual choice (both ran in parallel worktrees); must be reconciled at merge time before plan 05-04's deploy. | fixed |  | 2026-08-12T12:15:57.631Z | 2026-08-13T08:30:07.341Z |
| 2 | 05 | deviation | .planning/REQUIREMENTS.md |  | INFRA-08's requirement text names OCI's specific three network layers (Security List, NSG, OS firewall) verbatim, but the deploy target pivoted from Oracle Cloud to Netcup (05-03 Task 1/2), which has a genuinely different two-layer model (OS iptables + Netcup Cloud Firewall). The requirement's intent (multi-layer, externally-verified, only 80/443 reachable) is met by the two-layer Netcup setup, but the requirement's literal wording is now stale. Not fixed here -- REQUIREMENTS.md wording reconciliation is out of this task's scope; flagging for 05-06 (INFRA-08's owning plan) or a dedicated docs pass. | fixed |  | 2026-08-14T19:47:39.825Z | 2026-08-17T11:02:52.646Z |
| 3 | 8 | unrun-verify | docs/INFRA_RUNBOOK.md |  | 08-02 Task 3: live curl proof against nonprod/production and runbook record not run -- Docker Hub image build precondition unmet (commits not pushed/merged to master); needs re-dispatch after merge | fixed |  | 2026-08-18T13:12:05.036Z | 2026-08-20T08:19:00.432Z |
| 4 | 09 | unrun-verify | .github/workflows/deploy.yml |  | health-check-nonprod green/red path not observed live (Task 2, CI-04) -- static YAML verified only, deferred out of worktree | fixed |  | 2026-08-19T08:28:30.805Z | 2026-08-19T09:34:22.766Z |
| 5 | 09 | unrun-verify | .github/workflows/deploy.yml |  | cleanup-old-images-nonprod/cleanup-unused-image-nonprod idempotency + cross-repository isolation not observed live (Task 3, CI-03) -- static YAML verified only, deferred out of worktree | fixed |  | 2026-08-19T08:28:31.447Z | 2026-08-19T09:34:23.383Z |
| 6 | 09 | unrun-verify | docs/INFRA_RUNBOOK.md |  | Task 1 repository-secret sweep (gh secret delete on 9 secrets) + live push-to-master proof not executed (CI-02) -- deferred out of worktree per Plan 09-01 precedent | fixed |  | 2026-08-19T08:28:32.183Z | 2026-08-19T09:34:24.050Z |
| 7 | 10 | unrun-verify | .github/workflows/deploy.yml |  | Task 1 tracer real push-to-master + gh run watch deploy proof deferred to post-merge (worktree agent has no push authority; Plan 10-04 further edits deploy.yml) | fixed |  | 2026-08-19T15:31:56.697Z | 2026-08-20T08:19:01.132Z |
| 8 | 10 | unrun-verify | .github/dependabot.yml |  | Task 4 D-07 composition proof (Dependabot Check-for-updates UI log confirming both appleboy digest pins parse without error) not observed live -- checkpoint auto-approved per workflow.auto_advance=true, no CLI/API equivalent for this UI-only GitHub flow | open |  | 2026-08-19T15:32:05.656Z |  |

````json
[
  {
    "id": 1,
    "kind": "deviation",
    "phase": "05",
    "file": "docker-compose.prod.yml",
    "line": null,
    "description": "DB_URL_PARAMS env var name for the Neon JDBC URL query-string placeholder was chosen independently by plan 05-02 without visibility into plan 05-01's actual choice (both ran in parallel worktrees); must be reconciled at merge time before plan 05-04's deploy.",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-08-12T12:15:57.631Z",
    "resolved_at": "2026-08-13T08:30:07.341Z"
  },
  {
    "id": 2,
    "kind": "deviation",
    "phase": "05",
    "file": ".planning/REQUIREMENTS.md",
    "line": null,
    "description": "INFRA-08's requirement text names OCI's specific three network layers (Security List, NSG, OS firewall) verbatim, but the deploy target pivoted from Oracle Cloud to Netcup (05-03 Task 1/2), which has a genuinely different two-layer model (OS iptables + Netcup Cloud Firewall). The requirement's intent (multi-layer, externally-verified, only 80/443 reachable) is met by the two-layer Netcup setup, but the requirement's literal wording is now stale. Not fixed here -- REQUIREMENTS.md wording reconciliation is out of this task's scope; flagging for 05-06 (INFRA-08's owning plan) or a dedicated docs pass.",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-08-14T19:47:39.825Z",
    "resolved_at": "2026-08-17T11:02:52.646Z"
  },
  {
    "id": 3,
    "kind": "unrun-verify",
    "phase": "8",
    "file": "docs/INFRA_RUNBOOK.md",
    "line": null,
    "description": "08-02 Task 3: live curl proof against nonprod/production and runbook record not run -- Docker Hub image build precondition unmet (commits not pushed/merged to master); needs re-dispatch after merge",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-08-18T13:12:05.036Z",
    "resolved_at": "2026-08-20T08:19:00.432Z"
  },
  {
    "id": 4,
    "kind": "unrun-verify",
    "phase": "09",
    "file": ".github/workflows/deploy.yml",
    "line": null,
    "description": "health-check-nonprod green/red path not observed live (Task 2, CI-04) -- static YAML verified only, deferred out of worktree",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-08-19T08:28:30.805Z",
    "resolved_at": "2026-08-19T09:34:22.766Z"
  },
  {
    "id": 5,
    "kind": "unrun-verify",
    "phase": "09",
    "file": ".github/workflows/deploy.yml",
    "line": null,
    "description": "cleanup-old-images-nonprod/cleanup-unused-image-nonprod idempotency + cross-repository isolation not observed live (Task 3, CI-03) -- static YAML verified only, deferred out of worktree",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-08-19T08:28:31.447Z",
    "resolved_at": "2026-08-19T09:34:23.383Z"
  },
  {
    "id": 6,
    "kind": "unrun-verify",
    "phase": "09",
    "file": "docs/INFRA_RUNBOOK.md",
    "line": null,
    "description": "Task 1 repository-secret sweep (gh secret delete on 9 secrets) + live push-to-master proof not executed (CI-02) -- deferred out of worktree per Plan 09-01 precedent",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-08-19T08:28:32.183Z",
    "resolved_at": "2026-08-19T09:34:24.050Z"
  },
  {
    "id": 7,
    "kind": "unrun-verify",
    "phase": "10",
    "file": ".github/workflows/deploy.yml",
    "line": null,
    "description": "Task 1 tracer real push-to-master + gh run watch deploy proof deferred to post-merge (worktree agent has no push authority; Plan 10-04 further edits deploy.yml)",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-08-19T15:31:56.697Z",
    "resolved_at": "2026-08-20T08:19:01.132Z"
  },
  {
    "id": 8,
    "kind": "unrun-verify",
    "phase": "10",
    "file": ".github/dependabot.yml",
    "line": null,
    "description": "Task 4 D-07 composition proof (Dependabot Check-for-updates UI log confirming both appleboy digest pins parse without error) not observed live -- checkpoint auto-approved per workflow.auto_advance=true, no CLI/API equivalent for this UI-only GitHub flow",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-08-19T15:32:05.656Z",
    "resolved_at": null
  }
]
````
