---
quick_id: 260904-obv
status: complete
subsystem: column-api
tags: [dto, validation, mapstruct, flyway, security, mutation-gate-test-verification]
dependency-graph:
  requires: []
  provides: [column-color-field, column-color-validator]
  affects: [ColumnEntity, SaveColumnRequestDTO, ColumnResponseDTO, ColumnFullResponseDTO, ColumnFullMapper]
tech-stack:
  added: []
  patterns: [composed-bean-validation-constraint, raw-sql-and-jsonpath-mutation-safe-assertions]
key-files:
  created:
    - src/main/resources/db/migration/V9__add_columns_color.sql
    - src/main/java/com/vrudenko/kanban_board/dto/annotation/ColumnColor.java
    - src/test/java/com/vrudenko/kanban_board/dto/ColumnColorTest.java
  modified:
    - src/main/java/com/vrudenko/kanban_board/constant/ValidationConstants.java
    - src/main/java/com/vrudenko/kanban_board/entity/ColumnEntity.java
    - src/main/java/com/vrudenko/kanban_board/dto/column_dto/SaveColumnRequestDTO.java
    - src/main/java/com/vrudenko/kanban_board/dto/column_dto/ColumnResponseDTO.java
    - src/main/java/com/vrudenko/kanban_board/dto/column_dto/ColumnFullResponseDTO.java
    - src/test/java/com/vrudenko/kanban_board/controller/ColumnControllerTest.java
    - src/test/java/com/vrudenko/kanban_board/controller/BoardFullReadTest.java
    - src/test/java/com/vrudenko/kanban_board/security/InjectionAttemptTest.java
    - docs/ARCHITECTURE.md
decisions:
  - "D-1: color is nullable varchar(7), #RRGGBB, case-insensitive on input, no normalization -- persisted verbatim."
  - "D-2: color NOT added to BaseColumn (minimal common contract, not a field roster)."
  - "D-3: no DB CHECK constraint -- a violation would route through handleDataIntegrityViolation to 409 with the raw constraint message, the wrong status and a small information disclosure."
  - "D-4: @ColumnColor NOT stacked with @OptionalNotBlank -- the pattern already rejects blank by construction; stacking would double the violation count on a blank input."
  - "D-5: update path stays out of scope -- flagged and filed as a follow-up todo, not silently dropped."
  - "D-6: no versioning changes -- @Version already covers the new field for free."
  - "X-1: XSS test lives in InjectionAttemptTest.StoredXss using MockMvc, matching that class's own convention, not REST Assured."
  - "Test design: response-echo and persistence-read-back assertions use jsonPath/raw-SQL rather than typed DTO/entity getters, so a future field-removal mutation surfaces as a genuine runtime assertion failure instead of a compile error."
metrics:
  duration: "~70 minutes"
  completed: "2026-09-04"
actuals:
  tokens: 7800
  tasks: 3
  commits: 3
---

# Quick Task 260904-obv: Add color field to Column Summary

Added an optional `color` field to columns -- nullable `varchar(7)` `#RRGGBB` hex string, accepted
at creation, persisted verbatim, and returned on both the flat and GAP-04 nested full-board reads,
enforced by a composed `@ColumnColor` Bean Validation constraint (no DB CHECK) with no output
sanitization added anywhere.

## What Was Built

**Task 1 (`912e5ee`)** -- Schema and entity:
- `V9__add_columns_color.sql`: `ALTER TABLE columns ADD COLUMN color varchar(7)` -- nullable, no
  `DEFAULT`, no backfill, no CHECK. Header comment records the two decision reasons (D-1, D-3).
- `ValidationConstants`: `COLUMN_COLOR_LENGTH = 7`, `COLUMN_COLOR_PATTERN =
  "^#[0-9a-fA-F]{6}$"`, `COLUMN_COLOR_VALIDATION_MESSAGE`.
- `ColumnEntity.color`: `@Column(length = ValidationConstants.COLUMN_COLOR_LENGTH)`, no
  `nullable = false`.

**Task 2 (`c8e87d6`)** -- `@ColumnColor` and the DTO fields:
- `ColumnColor.java`: composed `@Pattern` constraint following `BoardName`'s exact structure
  (`@ReportAsSingleViolation`, `@Constraint(validatedBy = {})`, no hand-written validator).
  Javadoc records D-4 and D-1's no-normalization guarantee.
- `color` added to `SaveColumnRequestDTO` (`@ColumnColor`), `ColumnResponseDTO` (plain), and
  `ColumnFullResponseDTO` (plain) -- no `@JsonInclude` on any of the three.
- `ColumnColorTest`: validator-tier boundary matrix (null, three letter-cases, empty,
  whitespace-only, missing hash, five/seven digits, non-hex letters, trailing newline,
  surrounding spaces) -- 12 tests, all assert on violation count and property path.

**Task 3 (`50d4aca`)** -- HTTP behavior, nested read, XSS rejection, docs:
- `ColumnControllerTest.ColumnCreation`: three new tests -- create-with-color, create-without-color,
  create-with-invalid-color (one controller-tier representative).
- `BoardFullReadTest.GetFullBoard`: nested full-board read carries `color`.
- `InjectionAttemptTest.StoredXss`: a parameterized case proving `color` rejects a script payload
  and an attribute-breakout payload with 400 -- the deliberate counterpoint to this group's D-16
  verbatim-round-trip decision.
- `docs/ARCHITECTURE.md`: migration chain extended to `V8` and `V9`.

**Filed, not silently dropped (D-5):** `.planning/todos/pending/2026-09-04-allow-editing-a-columns-color-after-creation.md`
-- a column's color can be set once at creation and never changed; the update path (`UpdateColumnRequestDTO`)
was deliberately left untouched because it already carries a documented single-field exception to
this codebase's `Update*RequestDTO` convention, and adding a second optional field reopens that
DTO's fixed-shape contract as a real design question, too large to bolt onto this task.

## Design choice worth recording: mutation-safe assertions

Every response-echo and persistence-read-back assertion in the three new/extended controller-tier
tests reads through `jsonPath`/`JsonNode`/raw JDBC `SELECT`, never a typed DTO or entity getter
(`ColumnResponseDTO.getColor()`, `ColumnEntity.getColor()`). This was a deliberate correction made
mid-task: the plan's own `<verification_notes>` predicted specific mutations would produce a
**runtime assertion failure** (e.g. "the response-body assertion fails"), but the first draft of
these tests used typed getters directly, and removing the field broke **compilation of the whole
test source set** instead -- a "weak red" exactly like the compile-failure case the plan already
warned against for a different mutation. Rewriting the assertions to read through JSON/SQL rather
than the typed accessor restored the intended runtime-failure behavior; see "Red-then-green
evidence" below for the actual observed outcomes both ways.

## Red-then-green evidence (per the orchestrator's explicit verification requirements)

**1. XSS test (`InjectionAttemptTest.StoredXss.shouldReturnBadRequestAndPersistNothing_whenColumnColorIsXssPayload`)**
-- required to be checked against the unvalidated state before being counted as covered.

- **Green (real):** both parameterized cases (`<script>alert('xss')</script>`, `" onload="alert(1)`)
  return 400 with `code: VALIDATION_FAILED` and no column created.
- **Red direction, as literally specified (`@ColumnColor` commented out, field still present):**
  re-ran the test. **Both cases still failed the 400 assertion, but NOT via the predicted "201 with
  the payload persisted."** Observed instead: **409**, `code: DATA_INTEGRITY_VIOLATION`, detail
  `"...value too long for type character varying(7)..."`. Root cause: both payloads are longer
  than 7 characters, so PostgreSQL's `varchar(7)` length backstop independently rejects them at the
  INSERT, before the request could ever reach 201. This is an honest deviation from the plan's
  predicted outcome, reported rather than force-fit.
- **Follow-up probe (not part of the committed suite -- run once, observed, then deleted):** to
  confirm `@ColumnColor` does genuine work beyond the length backstop, a throwaway 7-character
  malicious-shaped payload (`<a x=1>`, exactly `varchar(7)`-sized) was POSTed with `@ColumnColor`
  still disabled. **Result: `201`, body
  `{"id":"...","name":"probe-holder","version":0,"position":8,"color":"<a x=1>"}`** -- genuinely
  persisted verbatim. This confirms `@ColumnColor`'s format check is not redundant with the length
  backstop: a short malicious value that fits inside `varchar(7)` bypasses the DB constraint but is
  caught by the Bean Validation pattern.
- **Restored:** `@ColumnColor` uncommented, full green confirmed (`git diff` on `SaveColumnRequestDTO.java`
  clean before the Task 3 commit).

**2. "Create WITH a color persists and returns it" (`ColumnControllerTest.ColumnCreation.shouldCreateWithColor_andPersistItExactly`)**
-- checked with two separate mutations, per the plan's own split (response echo vs. persistence).

- **Green (real):** POST with `color: "#AbCdEf"` returns 201, `jsonPath("$.color")` equals
  `"#AbCdEf"`, and a raw `SELECT color FROM columns WHERE id = ?` shows the same string.
- **Mutation A -- `color` commented out on `ColumnResponseDTO`:** `jsonPath("$.color")` threw
  `com.jayway.jsonpath.PathNotFoundException` (genuine runtime failure -- test suite still
  compiled and ran; only this assertion failed).
- **Mutation B -- `color` commented out on `ColumnEntity`:** the SAME test failed at the earlier
  response-echo assertion: `AssertionError: JSON path "$.color" expected:<#AbCdEf> but was:<null>`
  -- MapStruct's `unmappedTargetPolicy = IGNORE` silently left `ColumnResponseDTO.color` unset once
  its only source (`ColumnEntity.color`) was gone, so the response itself carried `null`, proving
  the entity→response mapping chain is genuinely exercised.
- **Restored both:** `git diff` on `ColumnResponseDTO.java` and `ColumnEntity.java` clean before
  the Task 3 commit; full green re-confirmed after each restoration.

**3. "Create WITHOUT color still succeeds" (`shouldCreateWithoutColor_andPersistNull`)** -- this is
explicitly a **backward-compatibility regression guard, not a bug-pin**, as instructed: a codebase
with no `color` field at all also passes this test, so there is no feature-removal red direction to
check. Its value is catching a *future* change that makes `color` mandatory. Confirmed by
temporarily adding `@NotBlank` alongside `@ColumnColor`: the test failed with
`Status expected:<201> but was:<400>`, body `errors.color: "must not be blank"` -- exactly the
scenario this guard exists to catch. Restored (`git diff` on `SaveColumnRequestDTO.java` clean).

**4. Full unfiltered gate:**

```
$ ./gradlew spotlessCheck
BUILD SUCCESSFUL in 1s
3 actionable tasks: 3 up-to-date

$ ./gradlew test
[... 382+ test methods across unit/integration/E2E/Kafka tiers ...]
> Task :jacocoTestCoverageVerification
BUILD SUCCESSFUL in 5m 22s
7 actionable tasks: 3 executed, 4 up-to-date
```

Zero failures (`grep -c FAILED` on the captured output returned 0), including the 90%/90%/75%
JaCoCo ratchet.

**5. No output-encoding or sanitization added.** Confirmed by inspection of every diff in this
task: `color`'s 400 comes exclusively from `@ColumnColor`'s format constraint at the DTO boundary.
No new sanitizer, encoder, or escaping logic was introduced anywhere in `src/main`. The existing
D-16 decision in `InjectionAttemptTest.StoredXss` (free-text fields round-trip script payloads
verbatim) is unchanged and untouched by this task.

## Additional mutations run (Task 1, migration/entity coupling)

- **Migration renamed to `.sql.disabled`:** context failed to start with
  `Schema-validation: missing column [color] in table [columns]` -- confirmed the entity and
  migration are genuinely coupled. Restored, re-confirmed green.
- **Entity `@Column(length = ...)` mismatched against the migration's `varchar(7)` (tried +1 and
  +50):** **did NOT reproduce a schema-validation failure** in this Hibernate/PostgreSQL
  combination -- `FlywaySchemaProvenanceTest` stayed green both times. This is a genuine finding,
  reported honestly rather than counted as covered: Hibernate's `ddl-auto=validate` in this stack
  does not appear to compare `VARCHAR` length against the live column, only existence/nullability
  for this dialect/version. Restored to the correct length (`7`) regardless, since it remains the
  documented, correct value.

## Additional mutation (Task 3, nested read)

`@Mapping(target = "color", ignore = true)` added to `ColumnFullMapper.toColumnFullResponseDTO`:
re-ran `BoardFullReadTest` -- exactly one test failed
(`shouldReturnColorOnNestedColumn_matchingCreatedValue`, `AssertionFailedError`), no other
regression. Restored, re-confirmed green (15 tests, 0 failures).

## Deviations from Plan

**1. [Rule 1 -- design correction] Response/persistence assertions rewritten to avoid compile-time
coupling.** See "Design choice worth recording" above. The plan's `<verification_notes>` predicted
specific mutations would surface as runtime assertion failures; the first-draft implementation
(typed getters) instead broke compilation. Rewritten to read through `jsonPath`/`JsonNode`/raw SQL,
restoring the intended runtime-failure behavior on both `ColumnResponseDTO` and the persistence
check. No production code was affected by this change -- test-only.

**2. [Honest deviation, not a fix] XSS mutation direction did not match the plan's literal
prediction.** The plan expected disabling `@ColumnColor` to produce "201 with the payload
persisted." The actual observed result was 409 (`DATA_INTEGRITY_VIOLATION`, `varchar(7)` length
backstop), because both required test payloads exceed 7 characters. Investigated with a one-off
throwaway probe (not committed) using a 7-character malicious-shaped payload, which DID reproduce
201-with-persistence, confirming `@ColumnColor` still does genuine, non-redundant security work.
Reported per instruction ("if you cannot make it fail in that direction, say so plainly") rather
than silently claiming the predicted outcome.

**3. [Rule 1 -- redundant assertion removed] `InjectionAttemptTest.StoredXss`'s new test originally
also asserted `noneMatch(c -> payload.equals(c.getColor()))` on the post-attempt column list.**
Removed: the preceding `containsExactlyInAnyOrderElementsOf(priorColumnIds)` assertion already
proves no column was created at all (a 400 rejects the whole create), making the `getColor()` check
strictly redundant, and it was also the source of an unwanted compile-time coupling to
`ColumnResponseDTO.color` during the mutation testing described above.

No other deviations. Plan's `<tasks>` executed as written otherwise.

## Known Stubs

None.

## Threat Flags

None -- the threat model in the plan (T-obv-01 through T-obv-04) was implemented and verified
exactly as specified; no new surface was introduced beyond what the plan's STRIDE register already
covers.

## Self-Check: PASSED

- FOUND: src/main/resources/db/migration/V9__add_columns_color.sql
- FOUND: src/main/java/com/vrudenko/kanban_board/constant/ValidationConstants.java
- FOUND: src/main/java/com/vrudenko/kanban_board/entity/ColumnEntity.java
- FOUND: src/main/java/com/vrudenko/kanban_board/dto/annotation/ColumnColor.java
- FOUND: src/main/java/com/vrudenko/kanban_board/dto/column_dto/SaveColumnRequestDTO.java
- FOUND: src/main/java/com/vrudenko/kanban_board/dto/column_dto/ColumnResponseDTO.java
- FOUND: src/main/java/com/vrudenko/kanban_board/dto/column_dto/ColumnFullResponseDTO.java
- FOUND: src/test/java/com/vrudenko/kanban_board/dto/ColumnColorTest.java
- FOUND: src/test/java/com/vrudenko/kanban_board/controller/ColumnControllerTest.java
- FOUND: src/test/java/com/vrudenko/kanban_board/controller/BoardFullReadTest.java
- FOUND: src/test/java/com/vrudenko/kanban_board/security/InjectionAttemptTest.java
- FOUND: docs/ARCHITECTURE.md
- FOUND: .planning/todos/pending/2026-09-04-allow-editing-a-columns-color-after-creation.md
- FOUND commit: 912e5ee (task 1 -- feat: schema and entity)
- FOUND commit: c8e87d6 (task 2 -- test: ColumnColor validator and DTO fields)
- FOUND commit: 50d4aca (task 3 -- test: HTTP behavior, nested read, XSS rejection, docs)
