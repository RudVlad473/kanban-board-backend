---
status: complete
completed: 2026-08-25
commit: 6aadda1
---

# Quick task 260825-dfd: Add createdAt to BoardEntity — Summary

Added a `createdAt` timestamp to boards, populated on creation and returned on every board
response.

## Accomplishments

- New `V8__add_boards_created_at.sql` migration: `boards.created_at timestamp(6) with time zone
  NOT NULL`, backfilled to migration time for existing rows, `DEFAULT` dropped immediately after
  so the application stays the single writer of the column.
- `BoardEntity.createdAt` (`java.time.Instant`), populated by a single `Instant.now()` read in
  `BoardService.save()` — truncated to microseconds (Postgres `timestamp(6)` drops finer
  precision) and reused for both the persisted column and `BoardCreatedEvent`'s timestamp, so
  the two can never disagree.
- `BoardResponseDTO.createdAt`, wired through the existing MapStruct `BoardMapper` (auto-mapped
  by field name, no mapper changes needed).
- Deliberately manual `Instant.now()`, not Hibernate's `@CreationTimestamp` — matches this
  codebase's existing convention (every other `Instant` field is a hand-written service-layer
  read; there is no precedent for a Hibernate value-generation annotation anywhere in this
  codebase) and is what lets one clock read serve both the column and the event.
- Test coverage: `BoardControllerTest.Save` (HTTP round-trip: POST then GET, asserts the same
  `createdAt` value comes back both times) and `BoardServiceTest.CreatedAtTest` (population on
  save, stability on reload, immutability on rename — three tests at the service tier).
- Fixed a stale `V1-V7` → `V1-V8` comment in `.github/workflows/deploy.yml`'s migration-range
  note.

## Incident during execution (relevant context for future sessions)

A separately-dispatched fork (working an unrelated OpenAPI-documentation spike task in the same
session) inherited enough context to dispatch its own duplicate `gsd-executor` for this exact
quick task, unprompted. That duplicate raced two concurrent `./gradlew test` runs against the
same `build/` directory, corrupting two verification attempts ("Could not write XML test
results" and a coverage-verification failure) before being force-stopped. No commits landed from
either concurrent run — the corruption was confined to `build/` output and a wasted ~15 minutes,
not the source tree. Once stopped and coordinated (fork paused all file/gradle activity until
given the all-clear), a clean verification surfaced one genuine, pre-existing-in-this-session bug
unrelated to the collision: `BoardControllerTest.Save`'s test data used
`dataFactory.getRandomText(...)` for the board name, which does not guarantee the alphanumeric+
space charset `@BoardName`'s validator requires (`^[a-zA-Z0-9 ]*$`) — occasionally producing a
name that fails validation with a 400. Fixed to `dataFactory.getRandomWord(...)`, matching the
established convention already used elsewhere in this codebase for board names
(`BoardCreationE2ETest.randomBoardName()`). See `~/.claude/TOOL_GOTCHAS.md`'s new "Agent
orchestration / forks" section for the generalized lesson.

## Verification

Final clean `./gradlew spotlessCheck test` run: `BUILD SUCCESSFUL`, 0 failures, coverage
verification passed.

## Deviations from Plan

- **[Bug, caught before commit] `BoardControllerTest.Save`'s data generator used the wrong
  DataFactory method** — see incident note above. Fixed before the feature commit landed; no
  separate commit needed since the file was never committed in its broken state.
