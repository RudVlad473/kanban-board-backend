---
schema_version: 1
open_count: 1
waived_count: 0
fixed_count: 0
total_count: 1
last_updated: 2026-08-12T12:15:57.631Z
---

# Broken Windows Ledger

> Cross-phase defect register. With `workflow.windows_enforce` enabled, `/gsd-ship` blocks while `open_count > 0`.
> Waive with `gsd-tools windows waive <id> "<reason>"` (reason required).
> Mark fixed with `gsd-tools windows fixed <id>`.

| id | phase | kind | file | line | description | status | reason | recorded_at | resolved_at |
|----|-------|------|------|------|-------------|--------|--------|-------------|-------------|
| 1 | 05 | deviation | docker-compose.prod.yml |  | DB_URL_PARAMS env var name for the Neon JDBC URL query-string placeholder was chosen independently by plan 05-02 without visibility into plan 05-01's actual choice (both ran in parallel worktrees); must be reconciled at merge time before plan 05-04's deploy. | open |  | 2026-08-12T12:15:57.631Z |  |

````json
[
  {
    "id": 1,
    "kind": "deviation",
    "phase": "05",
    "file": "docker-compose.prod.yml",
    "line": null,
    "description": "DB_URL_PARAMS env var name for the Neon JDBC URL query-string placeholder was chosen independently by plan 05-02 without visibility into plan 05-01's actual choice (both ran in parallel worktrees); must be reconciled at merge time before plan 05-04's deploy.",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-08-12T12:15:57.631Z",
    "resolved_at": null
  }
]
````
