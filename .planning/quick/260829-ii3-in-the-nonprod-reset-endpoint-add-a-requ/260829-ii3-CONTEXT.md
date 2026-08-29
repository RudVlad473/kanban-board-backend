# Quick Task 260829-ii3: In the nonprod reset endpoint, add a REQUIRED request parameter: a list of user IDs to delete, cascading to all resources those users own, following existing ownership-cascade patterns. Resolve: (1) relationship to existing full-reset behavior, (2) whether to also trim Kafka activity topics, (3) preserve existing security posture unchanged. - Context

**Gathered:** 2026-08-29
**Status:** Ready for planning

<domain>
## Task Boundary

Add a targeted, cascade-deleting operation to the existing nonprod reset endpoint
(`ResetController` / `ResetService` / `ResetTruncateService`) that deletes a required list of
user IDs and every resource each owns (boards, columns, tasks, subtasks), following the
existing ownership-cascade deletion pattern (`UserService`'s account-deletion cascade;
`TaskService.deleteAllByColumn`'s batch-delete + `EntityManager` flush/clear discipline) —
without inventing a new deletion mechanism, and without altering the endpoint's existing
security posture.

</domain>

<decisions>
## Implementation Decisions

### Endpoint structure — one endpoint, mode selected by query param
- The existing full-reset (truncate-everything) behavior and the new targeted per-user delete
  live on the SAME endpoint, selected by a boolean query parameter (e.g. `?fullReset=true`).
- `fullReset=true` (or present/true) runs today's unconditional `ResetTruncateService.truncateAll()`
  + Kafka topic trim, unchanged.
- `fullReset` absent/false selects the new targeted mode, which requires a `userIds` list in the
  request body.

### `userIds` validation
- `userIds` is REQUIRED in targeted mode. An EMPTY list is a validation error (400) — NOT treated
  as a no-op and NOT treated as a full-reset sentinel. The two modes are never conflated through
  the same field's value.
- If any supplied user ID does not exist in the database, FAIL THE WHOLE REQUEST (400/404) rather
  than silently skipping unknown IDs. No partial deletion on a batch containing an unknown ID.

### Cascade scope
- Deleting a user cascades to every resource that user owns (boards → columns → tasks →
  subtasks), via the existing ownership-cascade pattern already used elsewhere (not a new
  mechanism).
- `activity_log` (Postgres) rows belonging to the deleted users ARE deleted as part of the same
  cascade, scoped to those users — same pattern as the other owned-resource deletes.

### Kafka activity topics — explicitly OUT of scope for targeted delete
- The raw `kanban.activity` / `kanban.activity.dlt` Kafka topics are LEFT UNTOUCHED by the
  targeted delete. Technical reason (not a preference): `AdminClient.deleteRecords()` — the only
  primitive `ResetService` uses today — can only trim a partition by offset (delete everything
  before a point), not filter out one key's interleaved records. Selectively removing one user's
  records would require consuming, filtering, and republishing the entire topic to a new one —
  a fundamentally heavier mechanism that also shifts offsets `ActivityLogConsumer`'s committed
  offsets depend on. Full-reset mode keeps trimming both topics via offset-truncation as it does
  today; that path is unaffected by this decision.

### Security posture — unchanged, applies identically to both modes
- `@Profile("nonprod")` gate, `X-Reset-Token` shared-secret header, `MessageDigest.isEqual`
  constant-time comparison, and the no-oracle-on-header-presence behavior (absent header and
  wrong header both fail identically) all stay exactly as implemented today and gate BOTH the
  full-reset and targeted-delete paths the same way — no new/weaker check for the new mode.

### Claude's Discretion
- Exact request/response DTO shapes (e.g. `ResetUsersRequestDTO { List<String> userIds }`),
  controller method structure (one `@PostMapping` branching on the query param vs. two mapped
  methods), and the specific validation-error response body follow this project's existing
  DTO/`ProblemDetail`/`GlobalExceptionHandler` conventions.
- Whether `userIds` is validated as non-empty via `@NotEmpty` (bean validation) vs. a service-side
  check — follow whichever existing pattern this codebase already uses for required list bodies.

</decisions>

<specifics>
## Specific Ideas

No specific implementation ideas beyond the decisions above — open to standard approaches
consistent with this codebase's existing patterns.

</specifics>

<canonical_refs>
## Canonical References

- `src/main/java/com/vrudenko/kanban_board/controller/ResetController.java` — existing endpoint,
  security posture (Javadoc documents the two independent controls and constant-time comparison
  rationale).
- `src/main/java/com/vrudenko/kanban_board/service/ResetService.java` — existing `resetAll()`
  orchestration (Kafka topic trim + Postgres truncate), listener-pause discipline.
- `src/main/java/com/vrudenko/kanban_board/service/ResetTruncateService.java` — existing
  `truncateAll()`, flush/clear discipline around bulk statements.
- `UserService`'s account-deletion cascade and `TaskService.deleteAllByColumn` — the existing
  ownership-cascade / batch-delete + flush/clear patterns this new operation must follow rather
  than reinvent.
- Plan 08-02 (RESET-01, D-01, D-02, D-03) — original design decisions behind the existing reset
  endpoint's "genuinely empty, no reseed" full-reset semantics, unaffected by this change.

</canonical_refs>
