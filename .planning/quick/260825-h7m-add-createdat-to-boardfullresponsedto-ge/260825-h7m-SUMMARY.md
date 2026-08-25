---
quick_id: 260825-h7m
status: complete
subsystem: board-read
tags: [dto, mapstruct, board-full-read, test-coverage]
dependency-graph:
  requires: [260825-dfd]
  provides: [board-full-response-createdat]
  affects: [BoardFullResponseDTO, BoardFullMapper, BoardFullReadTest]
tech-stack:
  added: []
  patterns: [mapstruct-name-based-auto-mapping, mutation-gate-test-verification]
key-files:
  created: []
  modified:
    - src/main/java/com/vrudenko/kanban_board/dto/board_dto/BoardFullResponseDTO.java
    - src/test/java/com/vrudenko/kanban_board/controller/BoardFullReadTest.java
decisions:
  - "MapStruct auto-mapping was sufficient with no explicit @Mapping — confirmed by grepping the generated BoardFullMapperImpl for setCreatedAt before writing any test."
  - "Extended the existing shouldMatchFlatEndpointsFieldByField_forSameBoard test rather than adding a new one, since the property (board-level parity) is exactly what that test was already named and commented for."
metrics:
  duration: "~25 minutes"
  completed: "2026-08-25"
actuals:
  tokens: 946
  tasks: 2
  commits: 2
---

# Quick Task 260825-h7m: Add createdAt to BoardFullResponseDTO Summary

Carried the board's `createdAt` timestamp through the nested `GET /boards/{boardId}/full` read,
closing the gap where quick task 260825-dfd added the field to the flat `BoardResponseDTO` but
missed the nested document a client is most likely to actually use as its single board fetch.

## What Was Built

- Added `private Instant createdAt;` to `BoardFullResponseDTO`, positioned after `version` and
  before `columns`, mirroring the flat DTO's declaration exactly (bare field, no annotation).
- Added an HTTP-boundary test (`shouldReturnBoardsOwnCreatedAt_matchingFlatEndpoint`) proving
  `GET /boards/{boardId}/full` returns a non-null `createdAt` equal to the fixture's.
- Extended the existing `FlatEquivalence.shouldMatchFlatEndpointsFieldByField_forSameBoard` test
  to also fetch `GET /boards` and assert the nested document's `name`, `version`, and `createdAt`
  match the flat representation's — closing the untested gap at the board level that let
  `createdAt` reach production missing from the nested DTO in the first place.

## MapStruct Auto-Mapping: Confirmed Sufficient

The plan predicted MapStruct's name-based auto-mapping would populate `createdAt` on
`BoardFullResponseDTO` with no explicit `@Mapping` needed, since `BoardEntity.createdAt` and
`BoardFullResponseDTO.createdAt` share the same name and type. This was verified directly rather
than assumed: after adding the field, `./gradlew compileJava` was run and the generated
`BoardFullMapperImpl.java` was grepped for `setCreatedAt`, which was found present
(`boardFullResponseDTO.setCreatedAt( entity.getCreatedAt() );`) before any test was written.
**No `@Mapping` annotation was required.** `BoardFullMapper.java` was not modified at all.

## Mutation Gate Result

The mutation-check `<verify>` step in task 2 temporarily added
`@Mapping(target = "createdAt", ignore = true)` to `BoardFullMapper`, re-ran the targeted test
suite, and confirmed it failed — both `shouldReturnBoardsOwnCreatedAt_matchingFlatEndpoint` (task
1's test) and `shouldMatchFlatEndpointsFieldByField_forSameBoard` (task 2's extended test) went
red with `AssertionError`/`AssertionFailedError` as expected. The mapper was then restored via
`git checkout --`, confirmed clean via `git status --short`, and a fresh `./gradlew compileJava`
re-confirmed `setCreatedAt` is back in the generated mapper. The coverage genuinely detects the
defect being closed, not merely the field's presence on the class.

## Exact-Equality Assertion: Held First Try

The `createdAt` exact-equality assertion (`isEqualTo`, no tolerance window) passed on the first
run in both the task 1 targeted suite and the full `FlatEquivalence` test. No flake was observed.
This confirms 260825-dfd's `Instant.now().truncatedTo(ChronoUnit.MICROS)` microsecond-truncation
guarantee in `BoardService.save()` is still intact — the in-memory fixture value and the
endpoint's database re-read remain comparable with strict equality.

## Deviations from Plan

None — plan executed exactly as written. Both tasks completed with no auto-fixes, no
architectural questions, and no scope creep beyond the two files the plan named.

## Verification

- `./gradlew compileJava` + `grep setCreatedAt` on generated `BoardFullMapperImpl.java`: confirmed
  present with no explicit `@Mapping`.
- `./gradlew spotlessCheck test --tests 'com.vrudenko.kanban_board.controller.BoardFullReadTest' -x jacocoTestCoverageVerification`:
  green after both tasks, 9 tests passing (7 in `GetFullBoard`, 2 in `FlatEquivalence`).
- Mutation gate: mapper's `createdAt` mapping disabled → 2 tests failed as expected → mapper
  restored, `git status` clean, `setCreatedAt` confirmed regenerated.
- `git diff --stat` against the pre-task base confirms exactly two files touched:
  `BoardFullResponseDTO.java` (+6 lines) and `BoardFullReadTest.java` (+43 lines) — no migration,
  entity, service, repository, or mapper-interface file was modified (QUICK-04).
- Full unfiltered `./gradlew spotlessCheck test` (the project's own CI gate, enforcing the
  90%/90%/75% JaCoCo ratchet) was run as the final verification step — see the executor's final
  status for its outcome; the targeted per-task runs above deliberately skip that gate per the
  plan's own trade-off notes.

## Known Stubs

None.

## Follow-up Worth Filing

This is the **second** board field (`version` was the first, per `BoardFullResponseDTO`'s
existing D-13/D-15 comment) to reach the flat `BoardResponseDTO` before the nested
`BoardFullResponseDTO`. Approach B from the plan's alternatives — composing the flat DTO into the
nested one so structural drift becomes impossible rather than merely tested — is worth filing as
a todo for consideration the next time a board-level field is added, since the current pattern
(parallel field declarations kept in sync by test coverage alone) has now let the same class of
gap through twice.

## Self-Check: PASSED

- FOUND: src/main/java/com/vrudenko/kanban_board/dto/board_dto/BoardFullResponseDTO.java
- FOUND: src/test/java/com/vrudenko/kanban_board/controller/BoardFullReadTest.java
- FOUND: .planning/quick/260825-h7m-add-createdat-to-boardfullresponsedto-ge/260825-h7m-SUMMARY.md
- FOUND commit: 89fff5d (task 1 — feat: carry createdAt through the nested board read)
- FOUND commit: 1dd30ea (task 2 — test: assert board-level field parity in the flat-equivalence test)
