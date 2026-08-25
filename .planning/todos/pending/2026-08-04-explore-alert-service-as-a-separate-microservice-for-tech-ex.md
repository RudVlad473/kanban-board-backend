---
created: 2026-08-04T12:52:07.000Z
title: Explore an alert-service integration as a separate microservice
area: backend
severity: minor
files: []
audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## Problem

No concrete requirement drives this yet — it's a speculative/theoretical idea, not a scoped feature. The activity-log pipeline (Kafka event → consumer → persisted log) is a natural place to hang a downstream alert/notification service (e.g. "notify a user when X activity happens on their board"). This project's PROJECT.md already notes that a separate deployable microservice for the activity-log consumer is a "possible later epic" — an alert service would be a genuine second consumer of `kanban.activity`, which is exactly the scenario that would make a second, independently-deployed service (and its own Schema Registry compatibility story) a real test case rather than a hypothetical one.

## Solution

TBD — not scoped. If pursued, likely shape: a standalone service subscribing to `kanban.activity` (a second, independently-deployed consumer group), used primarily as a technology-exploration vehicle (e.g. proving multi-consumer schema compatibility, service-to-service auth, or a specific alerting tech like email/webhook/push) rather than a user-requested feature. Revisit only if there's a concrete reason to test one of those technologies — not on its own priority.
</content>
