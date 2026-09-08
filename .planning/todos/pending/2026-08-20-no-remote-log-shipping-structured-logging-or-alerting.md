---
created: 2026-08-20T00:00:00.000Z
title: "No remote log shipping, no structured/UTC logging standard, no alerting on unusual activity"
area: infra
severity: moderate
files:

  - docker-compose.prod.yml
  - docs/plans/backend-modernization/06-observability.md

audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## Problem

Filed from a 33-agent ASVS 4.0.3 Level 2 audit (ASVS V1.7.1, V1.7.2, V7.3.3, V7.3.4, V11.1.7,
V11.1.8).

`docker-compose.prod.yml`'s `json-file` logging driver caps logs at 30MB/container with no remote
destination. No sentry/logstash/elk/datadog/cloudwatch/papertrail/loki/grafana integration exists
(grep confirmed), except one unimplemented backlog line.

## Solution

Point at the existing planned-but-unimplemented backlog item at
`docs/plans/backend-modernization/06-observability.md` (Prometheus+Grafana) rather than proposing a
redundant new observability initiative. When that plan is picked up, ensure it also covers
structured/UTC logging and remote shipping/retention beyond the current 30MB local cap, and basic
alerting on unusual auth-failure volume (feeding off the new log events from
`2026-08-20-no-security-event-logging-on-auth-and-access-control.md`).

## Partial resolution (Phase 12)

Dated 2026-09-08. This todo names three distinct gaps. Phase 12 closed one of them:

- **Remote log shipping — CLOSED.** Loki + Promtail (D-01, D-08) now ship every container's logs
  off the local 30MB `json-file` cap into a 30-day-retained log store, queryable from Grafana. See
  `docs/INFRA_RUNBOOK.md`, "Log aggregation — Plan 12-02" for the full topology.
- **Structured/UTC logging standard — STILL OPEN.** This is an application-level logging-format
  change (structured fields, UTC timestamps in the app's own log output) that Phase 12 did not
  touch — it built the shipping pipeline, not a change to what gets shipped.
- **Alerting on unusual activity — STILL OPEN.** Explicitly excluded from Phase 12's scope
  (`12-CONTEXT.md`). Prometheus/Grafana now collect the metrics an alerting rule would consume, but
  no alert rule, notification channel or on-call path exists yet.

This todo stays in `pending/` rather than moving to `completed/` — closing it now would misrepresent
two-thirds of it as done.
