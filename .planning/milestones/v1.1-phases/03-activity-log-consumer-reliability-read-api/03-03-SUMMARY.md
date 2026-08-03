---
phase: 03-activity-log-consumer-reliability-read-api
plan: 03
subsystem: activity-feed-read-api
tags: [rest, spring-data-jpa, pagination, mapstruct, rest-assured]

dependency-graph:
  requires:
    - "Phase 3 Plan 01: ActivityLogEntity, ActivityAction, ActivityLogRepository.findAllByBoardId"
    - "OwnershipVerifierService.verifyOwnershipOfBoard (existing, reused unmodified)"
  provides:
    - "GET /boards/{boardId}/activity — this codebase's first paginated endpoint"
    - "ActivityLogResponseDTO / ActivityLogMapper / ActivityLogService / ActivityController"
    - "Convention: a service-owned two-key Sort (createdAt desc, id desc) as the pattern any future paginated endpoint in this codebase should copy"
  affects: []

tech-stack:
  added: []
  patterns:
    - "Service constructs its own deterministic Sort and rebuilds the effective Pageable from it, discarding any sort the caller supplied — the client cannot reintroduce a non-deterministic page boundary on a feed whose only sensible order is chronological"
    - "Two-key total order (createdAt desc, id desc) as the fix for offset pagination over rows that can share an identical timestamp — the ULID id tiebreak degrades gracefully into newest-first instead of an arbitrary order"

key-files:
  created:
    - src/main/java/com/vrudenko/kanban_board/dto/activity_dto/ActivityLogResponseDTO.java
    - src/main/java/com/vrudenko/kanban_board/mapper/ActivityLogMapper.java
    - src/main/java/com/vrudenko/kanban_board/service/ActivityLogService.java
    - src/main/java/com/vrudenko/kanban_board/controller/ActivityController.java
    - src/test/java/com/vrudenko/kanban_board/e2e/activity/ActivityReadE2ETest.java
  modified:
    - src/main/java/com/vrudenko/kanban_board/constant/ApiPaths.java

decisions:
  - "Service builds the effective Pageable from PageRequest.of(pageNumber, pageSize, deterministicSort), always discarding any Sort on the caller-supplied Pageable — proven by a dedicated test that requests a different sort field and still gets createdAt-descending back."
  - "Sort constructed as two separate Sort.Order.desc(...) statements assigned to named locals (createdAtDesc, idDesc) rather than inlined into one Sort.by(...) call, so each tiebreak key is independently visible in the diff and to a reader scanning the method."
  - "E2E test seeds ActivityLogEntity rows directly through ActivityLogRepository rather than publishing through Kafka — no broker needed, and direct seeding is the only way to place multiple rows at an identical Instant, which the page-boundary case requires. The Kafka path itself is already proven end-to-end by Plans 01 and 02."

metrics:
  duration: ~20min
  completed: 2026-08-02
status: complete
actuals:
  tokens: 5015
  tasks: 2
  commits: 2
---

# Phase 3 Plan 3: Activity Feed Read API Summary

`GET /boards/{boardId}/activity` now gives a board owner a real, paginated, newest-first view of
their board's activity — the half of Phase 3 a user can actually see, authorized through the
existing ownership chain reused unmodified, and paging deterministically even when many rows share
the same instant.

## Performance

- **Duration:** ~20min
- **Completed:** 2026-08-02T16:53:29+02:00
- **Tasks:** 2/2
- **Files modified:** 6 (5 created, 1 modified)

## Accomplishments

- `ApiPaths.ACTIVITY`, `ActivityLogResponseDTO` (D-10's exact five fields — `eventId`, `action`,
  `detail`, `userId`, `createdAt`, deliberately no row `id` and no `boardId`), `ActivityLogMapper`,
  `ActivityLogService.findAllByBoardId`, and `ActivityController` — the full read slice, mirroring
  the Board/Column/Task shape exactly.
- Authorization is `OwnershipVerifierService.verifyOwnershipOfBoard`, called first and unmodified,
  with the queried board id re-derived from the verified entity (`pair.getSecond().getId()`) rather
  than the raw path variable, per CODE_STYLE rule 2.
- The service constructs its own two-key `Sort` (`createdAt` descending, then `id` descending) and
  rebuilds the effective `Pageable` from it, discarding any sort the caller supplied — this
  codebase's first paginated endpoint therefore establishes a correct convention (a caller cannot
  reintroduce a non-deterministic page boundary) instead of a subtly broken one.
- `ActivityReadE2ETest` proves all of it over real HTTP against H2, no broker required: ownership
  scoping and the D-10 field shape on the wire, newest-first ordering, 401 with no leaked row for
  another user's board, 404 for an unknown board, every row returned exactly once when seven rows
  share one identical `createdAt` instant paged at size 3, an empty board returning an empty `Page`
  (not a 404), an oversized page request clamped to the configured `max-page-size`, and a
  caller-supplied sort failing to override the service's ordering.
- Full suite (`./gradlew test`, 168+ tests including this plan's 7) and `spotlessCheck` both pass.

## Task Commits

1. **Task 1: Serve the feed — DTO, mapper, ownership-verified paginated service, controller** -
   `59b478c` (feat)
2. **Task 2: Prove the feed — ownership rejection, newest-first, stable page boundaries, empty
   board** - `e575d6c` (test)

## Files Created/Modified

- `src/main/java/com/vrudenko/kanban_board/constant/ApiPaths.java` - added `ACTIVITY = "/activity"`
- `src/main/java/com/vrudenko/kanban_board/dto/activity_dto/ActivityLogResponseDTO.java` - the five
  D-10 fields, no `id`/`boardId`, no `@JsonInclude` (this is a response DTO, not a partial update)
- `src/main/java/com/vrudenko/kanban_board/mapper/ActivityLogMapper.java` - single MapStruct method,
  no list variant (the service maps a `Page`, not a `List`)
- `src/main/java/com/vrudenko/kanban_board/service/ActivityLogService.java` -
  `findAllByBoardId(userId, boardId, pageable)`: ownership check first, service-owned deterministic
  sort, board id re-derived from the verified entity
- `src/main/java/com/vrudenko/kanban_board/controller/ActivityController.java` - authenticated
  `@GetMapping` returning `Page<ActivityLogResponseDTO>` directly (D-09), no wrapper DTO
- `src/test/java/com/vrudenko/kanban_board/e2e/activity/ActivityReadE2ETest.java` - 7-method
  real-HTTP proof (ownership scoping, ordering, ownership rejection + not-found, same-instant page
  boundaries, empty board, page-size clamping, caller-sort override rejection)

## Decisions Made

See frontmatter `decisions` for the full list: the service always discards the caller's sort and
substitutes its own two-key total order; the two `Sort.Order.desc(...)` calls are assigned to named
locals rather than inlined, so each tiebreak key is independently visible; and the E2E suite seeds
rows directly through the repository (no Kafka broker needed for this plan's own proofs — the
publish path is already proven by Plans 01 and 02).

## Deviations from Plan

None — plan executed as written. No Rule 1/2/3 auto-fixes were needed; the endpoint, service and
test all worked as specified on the first pass once written.

## Issues Encountered

None. `./gradlew spotlessApply spotlessCheck build -x test` passed on the first attempt for Task 1,
and `./gradlew spotlessApply spotlessCheck test --tests '...ActivityReadE2ETest'` passed on the
first attempt for Task 2 (all 7 nested test methods green). The full suite (`./gradlew test`) was
run twice more afterward (once per task) and stayed green both times.

## User Setup Required

None introduced by this plan. The pre-existing, still-outstanding manual gate carried from Plan 01
remains: `docs/plans/backend-modernization/03-activity-log-ddl.sql` must be run via `psql` against
the real deploy-target Postgres instance before this phase's PR merges (master auto-deploys to EC2
on push, and the real profile sets no `ddl-auto`). This plan's own `<verify>` block names the same
`human-check` — it is not a new requirement, just re-surfaced here as this is the phase's last plan.

## Next Phase Readiness

- This is the last plan of Phase 3 (activity-log-consumer-reliability-read-api). All three plans in
  this phase are now complete: consumer + idempotent persistence + DLT wiring (Plan 01), the
  idempotency/dead-letter reliability proof suite (Plan 02), and this read API (Plan 03).
- The milestone's remaining pre-merge gate is the manual `psql` DDL run named above — this should be
  carried forward explicitly to phase-end / ship-time verification, since no automated test can
  prove it against the real database from this codebase.
- `PAGE-V2-01` (keyset pagination) and `KAFKA-V2-01` (production Kafka deployment) remain deferred
  to v2, as recorded in PROJECT.md's Deferred Items.

## Known Stubs

None — every artifact this plan promised is real and proven by a real-HTTP test, not a stub.

## Threat Flags

None beyond what the plan's own `<threat_model>` already covers. T-03-13 (authorization),
T-03-14 (unbounded page size), and T-03-15 (caller-supplied sort) are all directly enforced in code
and proven by this plan's own tests as designed. T-03-16 and T-03-17 are accepted per the threat
register, carried unchanged from Plan 01.

## Self-Check: PASSED

- `src/main/java/com/vrudenko/kanban_board/dto/activity_dto/ActivityLogResponseDTO.java` — FOUND
- `src/main/java/com/vrudenko/kanban_board/mapper/ActivityLogMapper.java` — FOUND
- `src/main/java/com/vrudenko/kanban_board/service/ActivityLogService.java` — FOUND
- `src/main/java/com/vrudenko/kanban_board/controller/ActivityController.java` — FOUND
- `src/test/java/com/vrudenko/kanban_board/e2e/activity/ActivityReadE2ETest.java` — FOUND
- `ApiPaths.ACTIVITY` constant — FOUND (`grep` verified)
- Commit `59b478c` — FOUND in `git log --oneline --all`
- Commit `e575d6c` — FOUND in `git log --oneline --all`

---
*Phase: 03-activity-log-consumer-reliability-read-api*
*Completed: 2026-08-02*
