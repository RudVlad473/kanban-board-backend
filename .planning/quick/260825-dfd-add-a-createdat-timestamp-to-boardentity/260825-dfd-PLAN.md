---
quick_id: 260825-dfd
type: quick
autonomous: true
requirements: [QUICK-01, QUICK-02, QUICK-03, QUICK-04, QUICK-05]
files_modified:
  - src/main/resources/db/migration/V8__add_boards_created_at.sql
  - src/main/java/com/vrudenko/kanban_board/entity/BoardEntity.java
  - src/main/java/com/vrudenko/kanban_board/dto/board_dto/BoardResponseDTO.java
  - src/main/java/com/vrudenko/kanban_board/service/BoardService.java
  - src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java
  - src/test/java/com/vrudenko/kanban_board/service/BoardServiceTest.java
  - .github/workflows/deploy.yml

estimate:
  tokens: 60000
  raw_tokens: 40000
  tasks: 2
  confidence: low

must_haves:
  truths:
    - "POST /api/boards returns a body whose createdAt is a non-null ISO-8601 instant (QUICK-03)."
    - "A subsequent GET /api/boards returns the byte-identical createdAt value for that board — the in-memory response and the database re-read do not drift (QUICK-02)."
    - "PUT /api/boards/{boardId} leaves createdAt unchanged (QUICK-02)."
    - "The boards table rejects an INSERT with a null created_at (QUICK-01)."
    - "No entity other than BoardEntity, and no table other than boards, gains a created_at (QUICK-05)."
  artifacts:
    - src/main/resources/db/migration/V8__add_boards_created_at.sql
    - "src/main/java/com/vrudenko/kanban_board/entity/BoardEntity.java (createdAt field)"
    - "src/main/java/com/vrudenko/kanban_board/dto/board_dto/BoardResponseDTO.java (createdAt field)"
    - "src/main/java/com/vrudenko/kanban_board/service/BoardService.java (single-clock-read population)"
    - "src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java (HTTP-boundary assertions, QUICK-04)"
    - "src/test/java/com/vrudenko/kanban_board/service/BoardServiceTest.java (service-tier assertions, QUICK-04)"
  key_links:
    - "MapStruct name-matching wires BoardEntity.createdAt to BoardResponseDTO.createdAt with no explicit @Mapping — nothing in the build proves the generated BoardMapperImpl actually did it except a non-null assertion taken through the HTTP boundary (QUICK-03)."
    - "spring.jpa.hibernate.ddl-auto=validate binds BoardEntity.createdAt to V8's created_at column exactly: a name or type mismatch fails every @SpringBootTest at context startup, before any assertion runs."
    - "The single Instant computed in BoardService.save() feeds both the persisted column and BoardCreatedEvent.timestamp — one clock read, two consumers."
---

<objective>
Give `BoardEntity` a creation timestamp and surface it on the flat board response.

Purpose: `boards` is currently the only user-visible resource with no record of when it came into
existence — the activity feed can say a board *was* created, but the board itself cannot say when.

Output: a `V8` Flyway migration adding a backfilled `NOT NULL created_at` column, a matching
`Instant createdAt` on `BoardEntity` populated at insert, the same field on `BoardResponseDTO`
carried through the existing MapStruct mapper, and assertions at both the service tier and the
HTTP boundary.

Scope guard (QUICK-05, explicit user instruction): `BaseEntity` is not touched, and no other
entity gains a timestamp. This is Board-only.
</objective>

<data_flow>
`BoardService.save()` reads the wall clock exactly once, truncates that `Instant` to microseconds,
and sets it on the transient `BoardEntity` before `boardRepository.save(...)` — so the value is
already on the in-memory instance when `boardMapper.toResponseDTO(board)` builds the response, and
no read-back query is needed. That same `Instant` is handed to the `BoardCreatedEvent` published
moments later, so the board's own `createdAt` and its activity-feed row agree exactly rather than
differing by however long the two statements took. Every later read (`GET /boards`,
`GET /boards/{id}`) loads the value from PostgreSQL through the identical mapper, which is why the
microsecond truncation matters: it makes the in-memory value and the re-read value the same value,
not merely close.
</data_flow>

<trade_off_analysis>

## Approaches considered

**A — Hibernate `@CreationTimestamp` on the entity field.** Declarative; Hibernate 6 assigns
before-execution generated values onto the entity instance during `persist()`, so the value is in
memory before the DTO mapping, and Hibernate 6.2+ truncates the generated clock to the column's
declared temporal precision for free.

**B — Explicit `Instant.now()` in `BoardService.save()`, shared with `BoardCreatedEvent`.**
(CHOSEN.) Matches how every other `Instant` in this codebase is produced.

**C — Keep a permanent database-side `DEFAULT now()` and read the value back via Hibernate
`@Generated(event = INSERT)`.** The database is the sole clock authority.

## Trade-off matrix

| Approach | Pros / Cons | Why picked / rejected |
|---|---|---|
| A — `@CreationTimestamp` | **+** Cannot be forgotten by a future insert path. **+** Precision truncation is automatic. **−** Introduces the codebase's first Hibernate value-generation annotation — there is currently zero precedent for one. **−** The board's `createdAt` and its `BoardCreatedEvent.timestamp` become two independent clock reads that disagree. **−** A future reader must know Hibernate's before-execution generator timing to know the field is populated by the time the mapper runs. | Rejected. The user's tiebreaker was explicitly "whichever is more consistent with existing conventions," and every `Instant` in this codebase today is a hand-written `Instant.now()` at a service call site (`BoardService`, `ColumnService`, `TaskService`, `SubtaskService`) or an explicitly-set field (`ActivityLogConsumer` sets `ActivityLogEntity.createdAt` from `event.timestamp()`). Note that `ActivityLogEntity.createdAt` is *not* a precedent for an auto-populated audit stamp — it is a domain value carried from the event, deliberately not row-insert time. |
| B — explicit set in `BoardService.save()` | **+** Zero new mechanisms; reads exactly like the four existing `Instant.now()` call sites. **+** One clock read serves both the column and `BoardCreatedEvent`, so the board and its activity row agree exactly. **+** The population point is visible at the call site rather than inferred from an annotation's generator timing. **−** A future insert path that bypasses this method writes null. | **Picked.** The null-write failure mode is fail-closed, not silent: the `NOT NULL` constraint rejects the insert immediately. There is exactly one other `boardRepository.save(...)` call today (`updateById`, on an already-persisted entity — an UPDATE, not an INSERT), and every test fixture reaches boards through `userService.addBoardByUserId` → `boardService.save`, so no path exists to miss right now. |
| C — permanent DB default + `@Generated` read-back | **+** Single clock authority, immune to a forgotten call site. **−** `@Generated` forces Hibernate to issue a SELECT after the INSERT to retrieve the value — a real added statement on the hottest write path. **−** Contradicts this project's own schema-provenance stance (`FlywaySchemaProvenanceTest`'s Javadoc: the app, not the schema, is where behavior lives). | Rejected on the extra round trip alone. |

## Non-obvious trade-offs

1. **Precision (the load-bearing one).** PostgreSQL `timestamp(6) with time zone` stores
   microseconds; `Instant.now()` on Java 21 can carry finer digits. `BoardControllerTest`'s
   existing `FindAllByUserId` test compares `objectMapper.writeValueAsString(fixtureDTOs)` — DTOs
   captured in memory from `save()` — against a fresh `GET /boards` via `content().json(...)`,
   which compares values for any field present on both sides. If the in-memory instant carries
   sub-microsecond digits the database silently drops, that pre-existing test fails on a field it
   was never written to check. `truncatedTo(ChronoUnit.MICROS)` removes the class of failure
   rather than papering over it, and is why Task 2 asserts POST-value equals GET-value rather
   than merely asserting non-null.

2. **`ALTER TABLE` cost is O(1), not O(rows).** `ADD COLUMN ... NOT NULL DEFAULT now()` does not
   rewrite the table on PostgreSQL 11+: `now()` is `STABLE`, not `VOLATILE`, so PostgreSQL
   evaluates it once and stores the result as a single `pg_attribute.attmissingval` — the same
   catalog-only-change reasoning `V7__add_board_optimistic_locking_version_column.sql`'s header
   already records for its `DEFAULT 0`. Every pre-existing row therefore reads back the *same*
   instant (migration time), which is the honest semantics: there is no historical creation time
   to recover, only "these existed as of this migration."

3. **State invalidation in `equals`/`hashCode`.** `BoardEntity.version` carries
   `@EqualsAndHashCode.Exclude` with a comment explaining the hazard — a mutable field driving
   `hashCode` makes an object already stored in a `HashSet` unreachable once the field changes.
   `createdAt` is written once before persist and never mutated afterward, so that hazard does not
   apply and no exclusion is warranted; it belongs in `equals` alongside `name` and `user`.
   Deliberately not excluding it is a decision, not an oversight.

4. **No added queries.** The value is computed in the JVM and folded into the INSERT statement
   that already runs. A `countQueries()` assertion (`docs/CODE_STYLE.md` rule 4) would therefore
   prove nothing this change could break, so none is added — the query-count tool is reserved for
   the fan-out shapes it was introduced for.

5. **The `DEFAULT` is dropped after backfilling.** Leaving `DEFAULT now()` permanently in place
   would convert approach B's one real weakness (a future insert path forgetting to set the value)
   from a loud constraint violation into a silently-plausible-but-wrong timestamp. The default
   exists to backfill existing rows and for nothing else.

</trade_off_analysis>

<context>
@.planning/STATE.md
@docs/CODE_STYLE.md
@src/main/java/com/vrudenko/kanban_board/entity/BoardEntity.java
@src/main/java/com/vrudenko/kanban_board/entity/ActivityLogEntity.java
@src/main/java/com/vrudenko/kanban_board/dto/board_dto/BoardResponseDTO.java
@src/main/java/com/vrudenko/kanban_board/service/BoardService.java
@src/main/java/com/vrudenko/kanban_board/mapper/BoardMapper.java
@src/main/resources/db/migration/V3__add_activity_log.sql
@src/main/resources/db/migration/V7__add_board_optimistic_locking_version_column.sql
@src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java
@src/test/java/com/vrudenko/kanban_board/service/BoardServiceTest.java
</context>

<tasks>

<task id="1" type="tracer" tdd="true">
  <name>Task 1: Wire createdAt end-to-end — migration, entity, service, DTO</name>

  <files>
src/main/resources/db/migration/V8__add_boards_created_at.sql
src/main/java/com/vrudenko/kanban_board/entity/BoardEntity.java
src/main/java/com/vrudenko/kanban_board/dto/board_dto/BoardResponseDTO.java
src/main/java/com/vrudenko/kanban_board/service/BoardService.java
src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java
.github/workflows/deploy.yml
  </files>

  <read_first>
`src/main/resources/db/migration/V7__add_board_optimistic_locking_version_column.sql` — the header-comment shape and the catalog-only-change rationale to mirror.
`src/main/resources/db/migration/V3__add_activity_log.sql` line 12 — the exact `created_at timestamp(6) with time zone NOT NULL` column spelling that `ActivityLogEntity`'s `Instant createdAt` already validates against under `ddl-auto=validate`.
`src/main/java/com/vrudenko/kanban_board/entity/ActivityLogEntity.java` lines 67-68 — the `Instant` field declaration to copy (`@Column(nullable = false)` only; Hibernate's naming strategy derives `created_at`, no explicit `name` attribute anywhere in this codebase).
`src/main/java/com/vrudenko/kanban_board/service/BoardService.java` lines 189-201 — the `save` method being modified, and lines 196-198 for the `BoardCreatedEvent` that will share the clock read.
`src/main/java/com/vrudenko/kanban_board/entity/BoardEntity.java` lines 56-65 — the `@EqualsAndHashCode.Exclude` comment on `version`, so the reasoning for NOT excluding `createdAt` is written against what is actually there.
  </read_first>

  <behavior>
    - `POST /api/boards` with a valid name returns 201 and a body whose `createdAt` parses as an ISO-8601 instant.
    - A subsequent `GET /api/boards` returns, for that same board id, a `createdAt` exactly equal to the one POST returned (proves the in-memory value and the database re-read do not drift).
    - The value is truncated to microsecond precision, so the equality above holds by construction rather than by luck of the platform clock.
  </behavior>

  <action>
Implement QUICK-01, QUICK-02 and QUICK-03 as one vertical slice, since none of the four layers is
independently observable — a migration with no entity field fails `ddl-auto=validate` at context
startup, and an entity field with no DTO field is invisible to every test in the suite.

**Migration (QUICK-01).** Create `V8__add_boards_created_at.sql` — `V7` is the current highest, so
`V8` is the next sequential number. Two statements: first
`ALTER TABLE boards ADD COLUMN created_at timestamp(6) with time zone NOT NULL DEFAULT now();`,
then `ALTER TABLE boards ALTER COLUMN created_at DROP DEFAULT;`. Match `V3`'s column type spelling
exactly, not a bare `timestamptz`. Open the file with a header comment in `V7`'s style recording
three things: (a) existing rows are backfilled to migration time because no real historical
creation time exists to recover; (b) `now()` is `STABLE` rather than `VOLATILE`, so PostgreSQL
stores one `attmissingval` instead of rewriting the table — the same catalog-only-change property
`V7`'s own header claims for `DEFAULT 0`; (c) the default is dropped immediately afterward so the
application stays the single writer and a future insert path that omits the value is rejected by
the `NOT NULL` constraint rather than quietly receiving insert time.

**Entity (QUICK-02).** Add `private Instant createdAt;` to `BoardEntity` under `@Column(nullable =
false)`, importing `java.time.Instant`. Follow `ActivityLogEntity`'s declaration: no explicit
column `name`, no `updatable = false`. Do **not** add `@EqualsAndHashCode.Exclude` — write a short
comment saying why it is absent, pointing at the neighbouring `version` field's comment: that
exclusion exists because `version` mutates on every update and would strand an object already
stored in a hash-based collection under its old hash, whereas this field is written once before
persist and never afterward. Do not touch `BaseEntity` (QUICK-05).

**Service (QUICK-02).** In `BoardService.save`, hoist one clock read into a local before
`boardRepository.save(board)`: an `Instant` from `Instant.now()` truncated to
`ChronoUnit.MICROS`. Set it on the entity via the Lombok setter, then pass the *same* local into
the `BoardCreatedEvent` constructor in place of that line's current inline `Instant.now()`.
Import `java.time.temporal.ChronoUnit`. Add a comment at the truncation recording the two reasons
it is there — the column is `timestamp(6)` so PostgreSQL drops anything finer, and the response
DTO is built from this in-memory instance while every later read comes from the database, so
without truncation those two paths can return different values for the same board. Leave
`updateById` alone: it must not touch `createdAt`.

**DTO (QUICK-03).** Add `private Instant createdAt;` to `BoardResponseDTO` after `version`,
importing `java.time.Instant`. No mapper change is needed — `BoardMapper.toResponseDTO` maps by
name under MapStruct's default matching, and the DTO's Lombok builder is already how `version`
reaches it. Do **not** add `createdAt` to `BoardFullResponseDTO` or `BoardFullMapper`; the task
scopes to the flat response only.

**Stale CI comment.** `.github/workflows/deploy.yml` around line 151 names the migration range
`V1-V7` in the `flyway-verify` job's header comment. Update that range to include `V8`. The job
itself mounts the whole migration directory and needs no other change.

**Verifying test (goes in `BoardControllerTest`).** Add a `@Nested` class named `Save` (there is
none yet — the class currently covers `FindAllByUserId`, `DeleteById`, `UpdateById` and
`AddColumnByBoardId`) holding one test named
`testWithAuthenticatedUser_shouldReturnStableCreatedAt_whenBoardIsCreated`. POST a board, read the
response into `BoardResponseDTO` via the injected `ObjectMapper` the way `UpdateById`'s test
already does, assert `createdAt` is not null, then GET the board list, locate the same id, and
assert its `createdAt` equals the POST body's. Use AssertJ fully qualified and AAA section
comments per `docs/CODE_STYLE.md` rules 3 and 5.

Run `./gradlew spotlessApply` before verifying — Google Java Format AOSP will reformat the touched
lines and `spotlessCheck` is a CI gate.
  </action>

  <verify>
    <automated>./gradlew spotlessApply &amp;&amp; ./gradlew test --tests 'com.vrudenko.kanban_board.controller.BoardControllerTest' --tests 'com.vrudenko.kanban_board.config.FlywaySchemaProvenanceTest'</automated>
    <automated>grep -c 'created_at' src/main/resources/db/migration/V8__add_boards_created_at.sql</automated>
  </verify>

  <done>
`V8__add_boards_created_at.sql` exists and applies cleanly (proven by `FlywaySchemaProvenanceTest`
booting a real PostgreSQL container against the full migration set with `ddl-auto=validate` — a
name or type mismatch between the column and `BoardEntity.createdAt` fails context startup, so a
green run of that class is the schema/entity agreement check). `BoardControllerTest` passes in
full, including the new `Save` test proving POST's `createdAt` equals GET's, and including the
pre-existing `FindAllByUserId` test whose `content().json(...)` fixture comparison now also covers
the new field.
  </done>
</task>

<task id="2" type="auto" tdd="true">
  <name>Task 2: Service-tier assertions and full-suite regression</name>

  <files>
src/test/java/com/vrudenko/kanban_board/service/BoardServiceTest.java
  </files>

  <read_first>
`src/test/java/com/vrudenko/kanban_board/service/BoardServiceTest.java` lines 155-190 (the `updateById` happy-path test whose shape the immutability test mirrors) and lines 290-340 (the `@Nested` class style used by `UpdateByIdUniquenessTest`).
`src/test/java/com/vrudenko/kanban_board/support/fixtures/AbstractAppTest.java` lines 91-130 — how `mockPopulatedBoard` and `mockEmptyBoards` are seeded through `userService.addBoardByUserId`, which is what makes those fixture DTOs carry a real `createdAt`.
  </read_first>

  <behavior>
    - `boardService.save(...)` returns a DTO whose `createdAt` is non-null (QUICK-04, service tier — the controller test proves the HTTP boundary, this proves the service contract independently).
    - Re-reading the same board through `boardService.findAllByUserId(...)` yields a `createdAt` equal to the one `save` returned.
    - `boardService.updateById(...)` returns a DTO whose `createdAt` equals the value the board had before the rename — an update must not restamp creation time.
  </behavior>

  <action>
Add a `@Nested` class to `BoardServiceTest` named `CreatedAtTest`, placed after the existing
`UpdateByIdUniquenessTest`, with three tests named per `docs/CODE_STYLE.md` rule 5's
`should<Outcome>_when<Condition>` convention:

1. `shouldPopulateCreatedAt_whenBoardIsSaved` — create a board through
   `userService.addBoardByUserId` (the sanctioned entry point the fixtures themselves use, which
   also exercises the duplicate-name guard path a raw `boardService.save` would skip) with a
   random valid name, and assert the returned DTO's `createdAt` is not null.
2. `shouldReturnSameCreatedAt_whenBoardIsReloaded` — create a board as above, then locate it in
   `boardService.findAllByUserId(...)` by id and assert the reloaded `createdAt` equals the
   created one. This is the service-tier twin of Task 1's HTTP round-trip assertion and is what
   actually fails if the microsecond truncation is dropped.
3. `shouldNotChangeCreatedAt_whenBoardIsRenamed` — take `mockEmptyBoards.getFirst()`, rename it
   via `boardService.updateById` using `boardMapper.toUpdateBoardRequestDTO(BoardEntity.builder()
   .name(newName).version(board.getVersion()).build())` exactly as the existing update tests do,
   and assert the returned DTO's `createdAt` equals the fixture's original `createdAt`.

Use AssertJ fully qualified (`Assertions.assertThat(...)`), AAA section comments, and
`RandomStringUtils.randomAlphabetic` bounded by `ValidationConstants` for names — all three
conventions are already in force in this file.

Do not add a `countQueries()` assertion: the chosen population strategy computes the value in the
JVM and folds it into the INSERT that already runs, so there is no statement-count change for such
an assertion to detect.

Then run the full suite. Note that `./gradlew test` is finalized by
`jacocoTestCoverageVerification`; the new Lombok accessors are exercised by the tests above, so the
ratchet should hold — if it trips, that is a real signal to investigate, not a threshold to relax.
  </action>

  <verify>
    <automated>./gradlew spotlessApply &amp;&amp; ./gradlew spotlessCheck test</automated>
  </verify>

  <done>
`./gradlew spotlessCheck test` is green end to end. `BoardServiceTest.CreatedAtTest` holds three
passing tests covering population, reload stability, and rename-immutability. No test elsewhere in
the suite regressed — in particular `BoardControllerTest.FindAllByUserId` (whole-DTO JSON
comparison), `BoardServiceTest.testUpdateById_shouldNotUpdateBoard_whenBoardDoesntBelongToUser`
(whole-DTO `isEqualTo`, both sides read from the database so both carry the same `createdAt`), and
`BoardCreationE2ETest` (REST Assured deserializing `BoardResponseDTO`, which picks up the JSR-310
module through its default object-mapper factory's `findAndRegisterModules()`).
  </done>
</task>

</tasks>

<verification>
1. `./gradlew spotlessCheck test` passes — the project's own CI gate.
2. `FlywaySchemaProvenanceTest` green proves `V8` applies against a real PostgreSQL container and
   that `ddl-auto=validate` accepts `BoardEntity.createdAt` against the new column.
3. `grep -rn 'createdAt' src/main/java/com/vrudenko/kanban_board/entity/` returns hits only in
   `BoardEntity.java` and the pre-existing `ActivityLogEntity.java` — confirming QUICK-05's
   Board-only scope and that `BaseEntity` was not touched.
4. `grep -rn 'created_at' src/main/resources/db/migration/V8__add_boards_created_at.sql` shows the
   column added against `boards` and no other table.
</verification>

<success_criteria>
- A newly created board carries a non-null `createdAt` in its POST response, and the same value on
  every subsequent read.
- Renaming a board does not change its `createdAt`.
- Existing rows are backfilled to migration time with no table rewrite.
- `BaseEntity` and every non-Board entity are byte-for-byte unchanged.
- `./gradlew spotlessCheck test` green.
</success_criteria>

<output>
Create `.planning/quick/260825-dfd-add-a-createdat-timestamp-to-boardentity/260825-dfd-SUMMARY.md`
when done. Record in it: the chosen population strategy and the consistency argument that decided
it, the microsecond-truncation rationale, and whether the pre-existing
`BoardControllerTest.FindAllByUserId` whole-DTO JSON comparison needed any adjustment (the plan
predicts it does not, given truncation — if it did, that prediction was wrong and the reason
belongs in the summary).
</output>
