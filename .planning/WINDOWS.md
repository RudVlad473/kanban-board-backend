---
schema_version: 1
open_count: 1
waived_count: 0
fixed_count: 1
total_count: 2
last_updated: 2026-08-14T19:47:39.825Z
---

# Broken Windows Ledger

> Cross-phase defect register. With `workflow.windows_enforce` enabled, `/gsd-ship` blocks while `open_count > 0`.
> Waive with `gsd-tools windows waive <id> "<reason>"` (reason required).
> Mark fixed with `gsd-tools windows fixed <id>`.

| id | phase | kind | file | line | description | status | reason | recorded_at | resolved_at |
|----|-------|------|------|------|-------------|--------|--------|-------------|-------------|
| 1 | 05 | deviation | docker-compose.prod.yml |  | DB_URL_PARAMS env var name for the Neon JDBC URL query-string placeholder was chosen independently by plan 05-02 without visibility into plan 05-01's actual choice (both ran in parallel worktrees); must be reconciled at merge time before plan 05-04's deploy. | fixed |  | 2026-08-12T12:15:57.631Z | 2026-08-13T08:30:07.341Z |
| 2 | 05 | deviation | .planning/REQUIREMENTS.md |  | INFRA-08's requirement text names OCI's specific three network layers (Security List, NSG, OS firewall) verbatim, but the deploy target pivoted from Oracle Cloud to Netcup (05-03 Task 1/2), which has a genuinely different two-layer model (OS iptables + Netcup Cloud Firewall). The requirement's intent (multi-layer, externally-verified, only 80/443 reachable) is met by the two-layer Netcup setup, but the requirement's literal wording is now stale. Not fixed here -- REQUIREMENTS.md wording reconciliation is out of this task's scope; flagging for 05-06 (INFRA-08's owning plan) or a dedicated docs pass. | open |  | 2026-08-14T19:47:39.825Z |  |

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
    "status": "open",
    "reason": "",
    "recorded_at": "2026-08-14T19:47:39.825Z",
    "resolved_at": null
  }
]
````

