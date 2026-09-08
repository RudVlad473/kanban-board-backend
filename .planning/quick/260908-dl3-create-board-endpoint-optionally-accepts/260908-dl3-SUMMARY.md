---
phase: quick-260908-dl3
plan: 01
subsystem: api
tags: [spring-boot, jpa, hibernate, jakarta-validation, mapstruct, board]

# Dependency graph
requires: []
provides:
  - "POST /api/boards accepts an optional caller-supplied id, validated against the real
    RandFlakeGenerator base36 format"
  - "BoardId composed constraint (dto/annotation/BoardId.java), modeled on ColumnColor"
  - "BoardService.save id-assignment + uniqueness-guard path"
  - "RandFlakeGenerator now honors a pre-assigned id (allowAssignedIdentifiers() +
    4-arg generate(...) override)"
affects: [board-creation, id-generation, entity-persistence]

# Actuals (#2632)
actuals:
  tokens: 8900
  tasks: 3
  commits: 3
plan_head_before: e1b08b9d65eb70419bce9abd5f3097e849426456

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Composed Jakarta Validation constraint (@Pattern-based, no validator class) for an
      optional id field, mirroring @ColumnColor"
    - "Check-then-act uniqueness guard backstopped by the database primary key, same shape as
      the existing board-name uniqueness guard"

key-files:
  created:
    - src/main/java/com/vrudenko/kanban_board/dto/annotation/BoardId.java
    - src/test/java/com/vrudenko/kanban_board/dto/BoardIdTest.java
  modified:
    - src/main/java/com/vrudenko/kanban_board/constant/ValidationConstants.java
    - src/main/java/com/vrudenko/kanban_board/dto/board_dto/SaveBoardRequestDTO.java
    - src/main/java/com/vrudenko/kanban_board/mapper/BoardMapper.java
    - src/main/java/com/vrudenko/kanban_board/service/BoardService.java
    - src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java
    - src/test/java/com/vrudenko/kanban_board/e2e/board/BoardCreationE2ETest.java
    - src/test/java/com/vrudenko/kanban_board/security/InjectionAttemptTest.java
    - .claude/CLAUDE.md

key-decisions:
  - "RandFlakeGenerator needed TWO overrides, not one: allowAssignedIdentifiers() alone (the
    plan's verified finding #2) does not make Hibernate honor a pre-set id -- IdentifierGenerator's
    default 4-arg generate(session, owner, currentValue, eventType) ignores currentValue entirely
    and forwards to the legacy 2-arg generate(session, object). Both overrides are required; caught
    by a real e2e run returning a server-generated id instead of the explicit one."
  - "BoardMapper's fromSaveBoardRequestDTO needed NO @Mapping(target=\"id\", ignore=true): the
    plan's verified finding #6 assumed MapStruct would silently auto-map SaveBoardRequestDTO.id
    onto the primary key, but BoardEntity uses plain Lombok @Builder (not @SuperBuilder), so the
    generated builder has no id(...) method for a field inherited from BaseEntity -- MapStruct's
    builder-based construction cannot reach it at all. Attempting the explicit ignore annotation
    was itself rejected by MapStruct as an unknown target property, which is what surfaced this."
  - "Id-assignment stays boards-only by design: BoardEntity.column, ColumnEntity.task and
    TaskEntity.subtasks all order by id ascending as a creation-order proxy, and base36 string
    ordering does not preserve the numeric ordering that proxy depends on. Extending this feature
    to columns/tasks/subtasks without first replacing @OrderBy(\"id\") with an explicit ordering
    column would silently corrupt their iteration order -- recorded as a decision comment in
    BoardService, not implemented."

requirements-completed: [QUICK-260908-dl3]

coverage:
  - id: D1
    description: "POST /api/boards with no id in the body returns 201 with a server-generated id, byte-identical to prior behaviour"
    verification:
      - kind: e2e
        ref: "BoardCreationE2ETest.CreateBoard#shouldReturnServerGeneratedIdMatchingBoardIdPattern_whenIdIsOmitted"
        status: pass
    human_judgment: false
  - id: D2
    description: "POST /api/boards with a well-formed, unused id returns 201 and persists under that exact id, readable back through a fresh GET"
    verification:
      - kind: e2e
        ref: "BoardCreationE2ETest.CreateBoard#shouldPersistExactlyGivenId_whenExplicitWellFormedIdIsProvided"
        status: pass
    human_judgment: false
  - id: D3
    description: "POST /api/boards with an already-taken id returns 409 DUPLICATE_RESOURCE and creates nothing"
    verification:
      - kind: e2e
        ref: "BoardCreationE2ETest.DuplicateId#shouldReturnConflictAndLeaveBoardsUnchanged_whenIdAlreadyUsedByAnotherBoard"
        status: pass
    human_judgment: false
  - id: D4
    description: "POST /api/boards with a malformed id (XSS payload, uppercase, over-length, punctuation/whitespace) returns 400 VALIDATION_FAILED with an errors.id entry, and creates nothing; a well-formed id exactly at the length bound is accepted"
    verification:
      - kind: integration
        ref: "InjectionAttemptTest.MalformedBoardId (7 cases: 2 XSS payloads, uppercase, over-length, at-bound accept, punctuation, whitespace)"
        status: pass
    human_judgment: false
  - id: D5
    description: "The id shape constant (MAX_BOARD_ID_LENGTH, BOARD_ID_PATTERN) is pinned to RandFlakeGenerator's real output so the regex and the generator cannot drift apart silently"
    verification:
      - kind: unit
        ref: "BoardIdTest#maxBoardIdLength_shouldBeAtLeastGeneratorsRealCeiling, BoardIdTest#boardIdPattern_shouldMatchEveryValueTheGeneratorCanEmit"
        status: pass
    human_judgment: false

duration: ~65min
completed: 2026-09-08
status: complete
---

# Quick Task 260908-dl3: Optional Caller-Supplied Board Id Summary

**`POST /api/boards` optionally accepts a caller-supplied id in the request body, validated against RandFlakeGenerator's real base36 format, uniqueness-checked before insert, and rejected 409 when taken -- the absent-id path is unchanged.**

## Performance

- **Duration:** ~65 min
- **Started:** 2026-09-08 (session start, exact timestamp not captured)
- **Completed:** 2026-09-08T11:02:19+02:00
- **Tasks:** 3
- **Files modified:** 10 (2 created, 8 modified)

## Accomplishments

- `BoardId` composed Jakarta Validation constraint admits only the lowercase base36 charset and
  length `RandFlakeGenerator` can itself emit, pinned by `BoardIdTest` against the generator's
  real ceiling (`Long.toString(Long.MAX_VALUE, 36)` = `"1y2p0ij32e8e7"`, length **13** --
  `MAX_BOARD_ID_LENGTH = 13`, `BOARD_ID_PATTERN = "^[0-9a-z]{1,13}$"`)
- `SaveBoardRequestDTO` gains a nullable `id` field; `BoardService.save` assigns it onto the
  entity before persist, guarded by a check-then-act `existsById` uniqueness lookup that throws
  `AppDuplicateResourceException.withMessage(...)` (409 `DUPLICATE_RESOURCE`) before any field is
  written onto the entity
- `RandFlakeGenerator` now honors a pre-assigned id: both `allowAssignedIdentifiers()` (makes the
  assigned id visible to the generator at all) and the 4-arg
  `generate(session, owner, currentValue, eventType)` override (makes the generator actually keep
  it instead of overwriting it) were required -- see Deviations below
- `BoardCreationE2ETest` proves the explicit-id round trip through a fresh `GET`, the unchanged
  default path, and the duplicate-id 409 with an unchanged board list
- `InjectionAttemptTest.MalformedBoardId` proves 7 hostile/boundary cases (2 XSS payloads,
  uppercase, over-length, exactly-at-bound acceptance, embedded punctuation, embedded whitespace),
  each on status + `VALIDATION_FAILED` code + `errors.id` + persistence
- `.claude/CLAUDE.md`'s stale "ULID-based entity IDs" claim corrected in three places to the
  actual scheme (base36-rendered packed Snowflake-shaped long, per-JVM unique)

## Task Commits

1. **Task 1: End-to-end explicit-id board create -- one path only** - `8db2a3d` (feat, tracer/TDD)
2. **Task 2: Uniqueness conflict on a taken id** - `f4a7dae` (feat, TDD)
3. **Task 3: Hostile and malformed id coverage, plus the format-claim correction** - `cb38532` (test, TDD)

_Each task followed RED (a real e2e/MockMvc run against unimplemented behaviour) -> GREEN ->
commit. Task 1's RED was a compile failure (`SaveBoardRequestDTOBuilder` had no `id(...)` method
yet); Tasks 2 and 3's RED were real HTTP assertion failures against the running app._

## Files Created/Modified

- `src/main/java/com/vrudenko/kanban_board/dto/annotation/BoardId.java` - composed constraint,
  modeled on `ColumnColor`
- `src/test/java/com/vrudenko/kanban_board/dto/BoardIdTest.java` - pins the regex to the
  generator's real output
- `src/main/java/com/vrudenko/kanban_board/constant/ValidationConstants.java` -
  `MAX_BOARD_ID_LENGTH`, `BOARD_ID_PATTERN`, `BOARD_ID_VALIDATION_MESSAGE`
- `src/main/java/com/vrudenko/kanban_board/dto/board_dto/SaveBoardRequestDTO.java` - nullable
  `@BoardId private String id`
- `src/main/java/com/vrudenko/kanban_board/mapper/BoardMapper.java` - documents why no
  `@Mapping(target = "id", ignore = true)` is needed (see Deviations)
- `src/main/java/com/vrudenko/kanban_board/service/BoardService.java` - uniqueness guard + id
  assignment in `save()`
- `src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java` -
  `allowAssignedIdentifiers()` + 4-arg `generate(...)` override
- `src/test/java/com/vrudenko/kanban_board/e2e/board/BoardCreationE2ETest.java` - explicit-id
  happy path, default-path re-assertion, `DuplicateId` nested class
- `src/test/java/com/vrudenko/kanban_board/security/InjectionAttemptTest.java` -
  `MalformedBoardId` nested class (7 cases)
- `.claude/CLAUDE.md` - 3 corrected sentences (ULID -> base36 Snowflake-shaped)

## Decisions Made

- Chose Approach A from the plan's trade-off matrix (override `allowAssignedIdentifiers()`;
  `BoardService.save` assigns the id when supplied) over B (drop `@RandFlakeId` from `BaseEntity`,
  explicit generator bean everywhere) and C (split `id` out of `BaseEntity` for `BoardEntity`
  only) -- smallest diff, bounded blast radius confirmed by task 2's `rg 'setId\('` search showing
  `BoardService` is the only service-package class writing an entity id.
- Id-assignment deliberately stays boards-only (see key-decisions in frontmatter): extending to
  columns/tasks/subtasks would corrupt their `@OrderBy("id")` creation-order proxy under base36
  string ordering. Recorded as a decision comment in `BoardService`, not implemented.
- Check-then-act race window accepted as-is, backstopped by the primary key ->
  `DATA_INTEGRITY_VIOLATION` (409), same relationship the existing board-name uniqueness guard
  already has with `uk_boards_user_id_name`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `allowAssignedIdentifiers()` alone did not make Hibernate honor a pre-assigned id**
- **Found during:** Task 1, first scoped test run (GREEN attempt) -- a real e2e assertion failed:
  `expected: "8qfkwhk1dc74" but was: "8qfkwhlrssu8"` (a server-generated id came back instead of
  the explicit one)
- **Issue:** The plan's verified finding #2 assumed overriding `allowAssignedIdentifiers()` to
  `true` was sufficient. Decompiling `hibernate-core-6.6.53.Final-sources.jar` showed
  `AbstractSaveEventListener#generateId` unconditionally calls
  `generator.generate(source, entity, currentValue, INSERT)` for every insert (excepting
  `Assigned`-strategy generators). `allowAssignedIdentifiers()` only controls whether
  `currentValue` is non-null when passed in; `IdentifierGenerator`'s own default implementation of
  that 4-arg method **ignores `currentValue` entirely** and forwards to the legacy 2-arg
  `generate(session, object)`, which always calls `generateRandflake()`.
- **Fix:** Added an explicit override of the 4-arg
  `generate(session, owner, currentValue, eventType)` returning `currentValue` when non-null,
  else `generateRandflake()`. Rewrote the decision-record comment on `RandFlakeGenerator` to
  document both required overrides and why one alone is insufficient.
- **Files modified:** `src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java`
- **Verification:** Re-ran `BoardCreationE2ETest` -- explicit-id round trip now persists the
  supplied id exactly.
- **Committed in:** `8db2a3d` (Task 1 commit)

**2. [Rule 1 - Bug] `BoardMapper`'s planned explicit `@Mapping(target = "id", ignore = true)` does not compile**
- **Found during:** Task 1, first `compileJava` after wiring `BoardMapper`
- **Issue:** The plan's verified finding #6 assumed MapStruct would silently auto-map
  `SaveBoardRequestDTO.id` onto `BoardEntity`'s primary key by name, requiring an explicit ignore.
  `BoardEntity` uses plain Lombok `@Builder` (not `@SuperBuilder`), and the generated
  `BoardEntityBuilder` has no `id(...)` method for a field inherited from `BaseEntity` -- MapStruct
  target-property resolution in builder mode cannot see it at all. The compiler rejected the
  `@Mapping` annotation itself: `Unknown property "id" in result type BoardEntity.BoardEntityBuilder`.
- **Fix:** Removed the `@Mapping` annotation; documented in `BoardMapper`'s Javadoc why `id` is
  unreachable via the builder and that `BoardService#save` assigns it explicitly afterward via
  `BaseEntity`'s inherited `setId` (a separate code path the builder does not use).
- **Files modified:** `src/main/java/com/vrudenko/kanban_board/mapper/BoardMapper.java`
- **Verification:** `compileJava` succeeds; `BoardIdTest`/`BoardCreationE2ETest` pass.
- **Committed in:** `8db2a3d` (Task 1 commit)

**3. [Rule 1 - Bug] Test-authoring bug: hyphenated board names in Task 3's new cases violated `@BoardName`'s charset**
- **Found during:** Task 3, first scoped GREEN attempt -- `shouldAccept_whenBoardIdIsExactlyMaxLength`
  failed (`Status expected:<201> but was:<400>`)
- **Issue:** Six new test cases in `InjectionAttemptTest.MalformedBoardId` used board names like
  `"max-length-id-holder"` containing hyphens, which `@BoardName`'s pattern
  (`^[a-zA-Z0-9 ]*$`, letters/digits/spaces only) rejects. This was masked in every case except the
  at-the-bound acceptance test, because every other case expected 400 anyway (for the id reason),
  so a coincidental 400-for-the-wrong-reason still passed the assertion. The at-bound acceptance
  case is exactly the one that exists to catch this class of masking bug.
- **Fix:** Replaced hyphens with spaces in all six board names (e.g. `"max length id holder"`).
- **Files modified:** `src/test/java/com/vrudenko/kanban_board/security/InjectionAttemptTest.java`
- **Verification:** Re-ran scoped `InjectionAttemptTest` -- all cases pass for the reason they
  claim to test.
- **Committed in:** `cb38532` (Task 3 commit)

**4. [Rule 3 - Blocking] ErrorProne `StringCaseLocaleUsage` blocked `.toUpperCase()` with no `Locale`**
- **Found during:** Task 3, first scoped test compile
- **Issue:** `new RandFlakeGenerator().generateRandflake().toUpperCase()` fails `compileTestJava`
  under this project's ErrorProne configuration.
- **Fix:** `.toUpperCase(Locale.ROOT)`, added the `java.util.Locale` import.
- **Files modified:** `src/test/java/com/vrudenko/kanban_board/security/InjectionAttemptTest.java`
- **Verification:** `compileTestJava` succeeds.
- **Committed in:** `cb38532` (Task 3 commit)

---

**Total deviations:** 4 auto-fixed (2 Rule 1 bugs in the plan's own verified findings, caught by
Task 1's real e2e run; 1 Rule 1 test-authoring bug caught by Task 3's own boundary-acceptance
case; 1 Rule 3 blocking ErrorProne fix).
**Impact on plan:** All four were necessary for the explicit-id path to actually work and for the
new tests to test what they claim. No scope creep -- every fix stayed inside the plan's stated
`files_modified` list.

## Issues Encountered

- The pre-commit hook's `fastTest` gradle task and the full `./gradlew test` run both take several
  minutes (a real Testcontainers-backed PostgreSQL instance per run); commits and the Task 3 full
  gate were dispatched via `run_in_background` and awaited via task-completion notifications
  rather than blocking synchronously. No functional issue -- noted for future executor timing
  expectations on this project.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `POST /api/boards` now supports optimistic client-side creation with a pre-known id
  (offline-first use case from the plan's objective), fully covered end to end.
- The boards-only scope decision is recorded in `BoardService` as a decision comment; extending
  caller-supplied ids to columns/tasks/subtasks is explicitly a future, separate change requiring
  an `@OrderBy("id")` replacement first -- not started here.
- No blockers for the parent milestone (Phase 12, currently at plan 12-04/6) -- this quick task
  was dispatched independently and does not touch Phase 12 files.

---
*Quick task: 260908-dl3*
*Completed: 2026-09-08*

## Self-Check: PASSED

All 10 claimed files (2 created, 8 modified) verified present on disk. All 3 commit hashes
(`8db2a3d`, `f4a7dae`, `cb38532`) verified present in `git log --oneline --all`. Full gate
(`./gradlew spotlessCheck test`) verified green with 215 test classes, 0 failures, including
`jacocoTestCoverageVerification`, `OpenApiDocsTest`, and `ComposedConstraintPropertyCustomizerTest`.
