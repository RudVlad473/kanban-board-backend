# Phase 2: Kafka Foundation, Domain Events & Move Endpoint - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-01
**Phase:** 2-Kafka Foundation, Domain Events & Move Endpoint
**Areas discussed:** Kafka-down resilience, Move endpoint scope

---

## Kafka-down resilience

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, always succeed | Task/board/column write completes normally; the activity log just falls behind | ✓ |
| No, fail the request | Roll back and return an error if publishing fails | |

**User's choice:** Yes, always succeed.
**Notes:** Matches the epic's own framing of decoupling the write path from the activity-log side effect.

| Option | Description | Selected |
|--------|-------------|----------|
| Log the failure | Attach a `.whenComplete` failure callback that logs via SLF4J | ✓ |
| Silent best-effort | No callback/logging | |

**User's choice:** Log the failure.

| Option | Description | Selected |
|--------|-------------|----------|
| Start regardless | App boots even if broker isn't up yet; producer retries on next publish | |
| Fail fast if Kafka unreachable | Explicit startup health check against the broker | |
| (free text) | User wanted: wait a reasonable bounded time for Kafka to become reachable, then fail if it doesn't — ideally using Kafka's own "readiness" as a signal | ✓ |

**User's choice:** Bounded wait, then fail — resolved via a follow-up clarifying question into a concrete mechanism.
**Notes:** User wasn't sure of the exact implementation ("not sure how that would look for our case"). Claude proposed docker-compose healthcheck on the `kafka` service + `depends_on: condition: service_healthy` on the `app` service as the concrete mechanism matching this intent.

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, this is it | Compose-level healthcheck + depends_on gates app startup on Kafka being reachable, bounded by a retry/timeout window | ✓ |
| I also want app-level failure | Also want the Spring app itself to explicitly fail startup as belt-and-suspenders | |

**User's choice:** Yes, this is it (compose-level mechanism confirmed sufficient).

---

## Move endpoint scope

| Option | Description | Selected |
|--------|-------------|----------|
| Column reassignment only | Matches what exists today and what the epic literally asks for | |
| Also add position within column | Introduce an ordering/position field | ✓ (initial answer) |

**User's initial choice:** Also add position within column.

| Option | Description | Selected |
|--------|-------------|----------|
| No defined position | Tasks aren't ordered today; a moved task is just associated with the new column | |
| Appended at the end | Sort last via createdAt/updatedAt | |
| (free text) | "let's introduce ordering concept" | ✓ (initial answer) |

**User's initial choice:** Introduce an ordering concept.

**Scope-creep flag raised:** Task ordering is a new capability not present in REQUIREMENTS.md (MOVE-01/02/03 only cover column reassignment) or in Epic 1's spec (which only asks for "move task between columns"). It would require a new position field, reorder logic for sibling tasks, and its own migration — comparable in size to the rest of Phase 2.

| Option | Description | Selected |
|--------|-------------|----------|
| Defer as its own future phase | Keep Phase 2 focused on Epic 1 as scoped; note ordering as a deferred idea | ✓ |
| Expand Phase 2 to include it now | Add ordering requirements to this phase, growing its scope beyond Epic 1 | |

**User's final choice:** Defer as its own future phase.
**Notes:** User accepted the scope-creep flag and agreed to defer. Phase 2 stays column-reassignment-only (D-04), with no defined landing position for a moved task (D-05).

---

## Claude's Discretion

- Exact healthcheck command/probe for the `kafka` compose service and its retry/timeout tuning
- Log level/message format for the publish-failure callback
- Confirming `ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)` as the mechanism during planning (strongly recommended by research, not directly discussed with the user)

## Deferred Ideas

- Task ordering/position within a column (drag-and-drop reorder) — deferred to a future milestone/phase per the scope-creep resolution above.
- Production (EC2) Kafka deployment — already tracked as v2-deferred (`KAFKA-V2-01`); reinforced by confirming the deploy pipeline is a single `docker run`, not compose.
