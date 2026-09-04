---
quick_id: 260904-obv
type: quick
autonomous: true
requirements: [D-1, D-2, D-3, D-4, D-5, D-6, X-1]
files_modified:
  - src/main/resources/db/migration/V9__add_columns_color.sql
  - src/main/java/com/vrudenko/kanban_board/constant/ValidationConstants.java
  - src/main/java/com/vrudenko/kanban_board/entity/ColumnEntity.java
  - src/main/java/com/vrudenko/kanban_board/dto/annotation/ColumnColor.java
  - src/main/java/com/vrudenko/kanban_board/dto/column_dto/SaveColumnRequestDTO.java
  - src/main/java/com/vrudenko/kanban_board/dto/column_dto/ColumnResponseDTO.java
  - src/main/java/com/vrudenko/kanban_board/dto/column_dto/ColumnFullResponseDTO.java
  - src/test/java/com/vrudenko/kanban_board/dto/ColumnColorTest.java
  - src/test/java/com/vrudenko/kanban_board/controller/ColumnControllerTest.java
  - src/test/java/com/vrudenko/kanban_board/controller/BoardFullReadTest.java
  - src/test/java/com/vrudenko/kanban_board/security/InjectionAttemptTest.java
  - docs/ARCHITECTURE.md

estimate:
  tokens: 90000
  raw_tokens: 60000
  tasks: 3
  confidence: low

must_haves:
  truths:
    - "POST /api/boards/{boardId}/columns with `\"color\": \"#FF5733\"` returns 201 and a body carrying that exact string, and the persisted `columns.color` row value equals it — read back from the repository, not inferred from the response echo."
    - "POST /api/boards/{boardId}/columns with NO `color` key still returns 201 exactly as it does today, with `color` null in the response and null in the database. Every pre-existing test that creates a column keeps passing unchanged."
    - "A create request whose `color` is not a `#RRGGBB` hex string is answered 400 with the project's RFC 7807 ProblemDetail envelope (`code` + `errors`), and no column row is persisted."
    - "A create request whose `color` carries an XSS payload (`<script>alert(1)</script>`, and the attribute-breakout form `\" onload=\"alert(1)`) is answered 400 by the same input-validation path — asserted on the STATUS, not on the payload being absent from the response, and proven to fail against a `color` field carrying no format constraint (X-1)."
    - "GET /api/boards/{boardId}/full returns each column's `color` through the fetch-join + BoardFullMapper→ColumnFullMapper chain, proven by a test that FAILS when `color` is deliberately excluded from that mapper."
    - "Mixed-case input (`#AbCdEf`) is accepted and returned byte-identical — no silent normalization happens anywhere on the path."
    - "The application boots against the migrated schema with `spring.jpa.hibernate.ddl-auto=validate`, i.e. `ColumnEntity.color`'s declared length agrees with `columns.color varchar(7)`."
    - "The number of prepared statements issued by the full-board read is unchanged — `color` rides the existing fetch-join projection and adds no query."
  artifacts:
    - "src/main/resources/db/migration/V9__add_columns_color.sql — nullable, no backfill, no DB CHECK"
    - "src/main/java/com/vrudenko/kanban_board/dto/annotation/ColumnColor.java — composed Bean Validation constraint following the @BoardName pattern"
    - "src/test/java/com/vrudenko/kanban_board/dto/ColumnColorTest.java — validator-tier boundary matrix (rule 4)"
  key_links:
    - "SaveColumnRequestDTO.color → ColumnMapper.fromSaveColumnRequestDTO → ColumnEntity.color: MapStruct maps by NAME under `unmappedTargetPolicy = ReportingPolicy.IGNORE`, so a name mismatch is silent. Gated by grepping the generated ColumnMapperImpl."
    - "ColumnEntity.color → ColumnFullMapper → ColumnFullResponseDTO.color: same silent-drop risk on the GAP-04 nested read path."
    - "ColumnEntity.color `length` ↔ columns.color `varchar(7)`: Hibernate's ddl-auto=validate compares these at startup; a mismatch is a context-boot failure, not a test failure."
---

<objective>
Give a board column an optional `color`, accepted at creation time, persisted, and returned on
both the flat and the nested (GAP-04 full-board) reads.

Purpose: columns currently have no visual identity in the API contract. The mock-up design system
(`docs/MOCKUP_FEATURE_GAP.md` MU-Th1) defines a palette; this exposes a per-column slot for one.
Output: one Flyway migration, one entity field, one composed validation annotation, three DTO
fields, and four test files proving the behavior in both directions.
</objective>

<approach_analysis>

## Alternates considered

**Approach A — nullable `varchar(7)`, format enforced by a composed `@ColumnColor` Bean
Validation annotation, no DB CHECK.** (PICKED)
Mirrors `@BoardName`'s existing composed-constraint shape (`@Constraint(validatedBy = {})` +
`@ReportAsSingleViolation` + a composing `@Pattern`). Validation fires at the DTO boundary, so a
bad value is a 400 with the project's field-error envelope before anything reaches JPA.

**Approach B — `NOT NULL DEFAULT '#635FC7'` with a backfill, inline `@Pattern` on the DTO.**
Every existing column gets the design system's accent purple; `color` is never null, so no consumer
ever handles absence. Rejected on two counts, one of them not mine to decide.

**Approach C — a project `enum ColumnColor` holding the mock-up's fixed palette, persisted via
`@Enumerated(EnumType.STRING)`.** This is what `docs/CODE_STYLE.md` rule 1 ("prefer enums over
magic String constants") points at, and `UserEntity.theme` (`ThemePreference`, `varchar(10)`) is
the in-repo precedent for exactly this shape. It deserves a real answer rather than a dismissal.

## Trade-off matrix

| Approach | Pros / Cons | Why picked / rejected |
|---|---|---|
| **A — nullable varchar(7) + composed `@ColumnColor`** | **+** Existing rows need no invented value. **+** Bad input → 400 + `errors` map, the same envelope every other validation failure uses. **+** Follows the `@BoardName`/`@TaskTitle` convention exactly, so the next reader finds it where they expect. **+** Reversible: tightening to NOT NULL later is a migration; loosening from NOT NULL is a product argument. **−** Every consumer must handle `color == null`. **−** Any hex string is accepted, including one outside the design palette. | **PICKED.** The null-handling cost is real but is carried by the client, once; the alternative pushes an invented product decision into every historical row permanently. |
| **B — NOT NULL DEFAULT + backfill** | **+** No null-handling anywhere. **+** A DB default means an insert path that forgets `color` still produces a valid row. **−** Requires choosing a color for every pre-existing column — a product decision nobody has made. **−** A permanent DB default makes the database a second writer of this column, which is precisely what `V8__add_boards_created_at.sql` went out of its way to avoid (it drops its default immediately, with a comment saying why). | **REJECTED.** Contradicts the orchestrator's explicit instruction AND an established in-repo migration decision. The `V8` precedent is the stronger of the two reasons. |
| **C — `enum ColumnColor` palette** | **+** Compiler-enforced closed set; rule 1's whole argument. **+** `ThemePreference` proves the pattern works here. **+** Guarantees every color is on-brand. **−** Requires the palette to actually BE closed and known — `docs/MOCKUP_FEATURE_GAP.md` catalogues background/text colors for a light and dark theme, but names no per-column palette; there is no source of truth to enumerate from. **−** Inventing one makes this task a design decision. **−** An unknown enum name deserializes to a 400 via `HttpMessageNotReadableException`, a different envelope from the field-error one. | **REJECTED, with the rule-1 caveat recorded.** Rule 1 governs values "from a fixed, known-at-compile-time set." This set is neither fixed nor known — searching `docs/` and `src/` for "color" returns only the theme-palette rows in `MOCKUP_FEATURE_GAP.md`, nothing column-scoped. Modelling it as an enum would be coining the closed set, not honoring one. If a palette is later specified, migrating `varchar(7)` → enum is a follow-up with a clean path. |

## Non-obvious trade-offs

**1. `ddl-auto=validate` makes the entity's declared length load-bearing (the one that actually
bites).** Both profiles run `spring.jpa.hibernate.ddl-auto=validate`
(`application.properties:212`, `application-test.properties:35`), so Hibernate compares the entity
mapping against the migrated schema at context startup. A bare `private String color;` maps to
Hibernate's default length 255 and will not agree with `varchar(7)`. The precedent to copy is
`TaskEntity.title`: `@Column(nullable = false, length = ValidationConstants.MAX_TASK_TITLE_LENGTH)`
against `tasks.title varchar(32)`. Here: `@Column(length = ValidationConstants.COLUMN_COLOR_LENGTH)`
with the constant at 7, and **no** `nullable = false`. This failure is a context-boot failure that
takes the entire suite down at once, not a single red test.

**2. A DB CHECK constraint would route a client format error to the wrong status and leak internals.**
`GlobalExceptionHandler.handleDataIntegrityViolation` (line 178) maps
`DataIntegrityViolationException` to **HTTP 409** with `ex.getMessage()` as the `detail` — a raw
Hibernate/JDBC constraint message reaching the client. So enforcing the hex format in the database
would turn "you sent a malformed color" into a 409 whose body quotes the constraint expression: a
wrong status code and a small information disclosure in one move. Bean Validation at the DTO
boundary gives 400 + a `errors` field map and never names a database object. The database still
carries `varchar(7)` as a length backstop; it just is not the format authority.

**3. ReDoS and the whole-string match.** `^#[0-9a-fA-F]{6}$` is fixed-length with no alternation
and no nested quantifier — linear time, no catastrophic-backtracking shape. (The tempting
`^#([0-9a-fA-F]{3}){1,2}$` short-hex variant introduces a nested quantifier; bounded, so still safe,
but it is the shape to avoid and it is not needed here.) `@Pattern` evaluates with
`Matcher.matches()`, a whole-region match, so a trailing-newline smuggle (`"#ff0000\n"`) cannot slip
past — `OptionalNotBlank`'s Javadoc documents the same `matches()` semantics from the other
direction. That is a claim, so Task 2 **tests** it rather than asserting it here.

**4. Performance and memory: nothing changes.** `varchar(7)` nullable costs 1 + n bytes for a
non-null value and nothing for a null one, carries no index, and rides the existing
`BoardRepository.findByIdWithColumnsTasksAndSubtasks` fetch-join projection. No extra round trip,
no new N+1, so the codebase's `Statistics.getPrepareStatementCount()` assertions stay valid
unchanged. Task 3 keeps a truth asserting exactly that rather than assuming it.

**5. The JSON contract widens for every column response.** `ColumnResponseDTO` carries no
`@JsonInclude(NON_NULL)` (verified — only `UpdateColumnRequestDTO` does), so every column payload
now includes `"color": null`. Any consumer deserializing with `FAIL_ON_UNKNOWN_PROPERTIES` enabled
would break. In-repo consumers are safe (the tests deserialize into these same DTOs). Do **not** add
`@JsonInclude` to fix this: it would diverge from `ColumnResponseDTO`'s current shape for a
cosmetic reason, and the existing `ColumnControllerTest.UpdateById` assertion uses
`content().json(...)`, which is JSONAssert-LENIENT — expected and actual both serialize `color` as
null, so it stays green either way. Task 3 re-runs that test to confirm rather than reasoning about it.

**6. XSS: this codebase deliberately does not sanitize, and `color` does not change that.**
`InjectionAttemptTest.StoredXss` carries an explicit decision comment (D-16, lines 417-422): a
stored script payload round-trips **verbatim**, sanitizing for display is the consuming frontend's
job, and no test in that group asserts escaping. `color`'s 400 comes from the format constraint
being narrow, not from any new sanitization layer — and none is added here.

**Observation, flagged not fixed:** free-text validation across this codebase is already
inconsistent on this axis. `@BoardName` composes `@Pattern("^[a-zA-Z0-9 ]*$")`, so a board name
carrying `<script>` is rejected 400. `SaveColumnRequestDTO.name` carries only `@NotBlank` +
`@Size` (no character class), and `@TaskTitle` composes only `@Size` — so column names, task
titles, task descriptions and subtask titles accept the same payload and store it verbatim, which
is what `StoredXss` asserts today. `color` lands in the `@BoardName` camp because its value set has
a genuine closed format, not because a policy was applied. This inconsistency is out of scope; it
is recorded here so a future reader does not read `color`'s 400 as a codebase-wide guarantee.

</approach_analysis>

<context>
@.planning/STATE.md
@.claude/CLAUDE.md
@docs/CODE_STYLE.md

Read before editing (each is a pattern to copy, not just background):
@src/main/java/com/vrudenko/kanban_board/dto/annotation/BoardName.java
@src/main/java/com/vrudenko/kanban_board/entity/ColumnEntity.java
@src/main/resources/db/migration/V8__add_boards_created_at.sql
@src/test/java/com/vrudenko/kanban_board/dto/OptionalNotBlankTest.java
</context>

<decisions_recorded>

**D-1 — `color` is a nullable `varchar(7)`, `#RRGGBB`, case-insensitive on input, no backfill.**
Persisted verbatim: a client that sends `#AbCdEf` reads back `#AbCdEf`. No normalization to upper
or lower case anywhere. Normalizing would be a silent transformation of client data for no stated
benefit; if a caller needs case-folded comparison, that is a caller concern. Task 2 pins this with
a test so the choice is enforced rather than merely intended.

**D-2 — `color` is NOT added to the `BaseColumn` interface.** Investigated as instructed, and the
evidence is decisive: `BaseColumn` declares exactly one method (`String getName()`) even though
`version` and `position` appear on three of the four column DTOs plus the entity. It is the
minimal common contract across every column-shaped type, not a field roster. Adding `getColor()`
would force `UpdateColumnRequestDTO` — explicitly out of scope per D-5 — to grow the field or fail
to compile. Correct answer: leave it alone.

**D-3 — no DB CHECK constraint.** Rationale in Non-obvious trade-off 2 (wrong status code, leaks
constraint internals through `handleDataIntegrityViolation`'s `ex.getMessage()`).

**D-4 — `@ColumnColor` is NOT stacked with `@OptionalNotBlank`.** `docs/CODE_STYLE.md` rule 12
prescribes stacking `@OptionalNotBlank` on an optional String field that must reject blank, and it
was checked against this field. It does not apply: rule 12's target is a field whose composed
annotation is `@Size` + character class, which accepts `"   "` on its own. `^#[0-9a-fA-F]{6}$`
already rejects whitespace-only by construction, so stacking would produce **two** violations for a
blank input and break the "exactly one violation, asserted by count" convention rule 4 depends on.
This reasoning belongs in `ColumnColor.java`'s Javadoc so the next rule-12 audit finds an answer
rather than an apparent violation.

**D-5 — the update path stays out of scope.** `UpdateColumnRequestDTO` is untouched. **Flagged as
a follow-up, not silently accepted:** a column whose color can be set once and never changed is a
half-feature; a client that mistypes a color has no recourse but to delete and recreate the column.
`UpdateColumnRequestDTO`'s Javadoc already documents (D-02, quick task 260811-ufu) why `name` is
mandatory there, and adding an optional `color` alongside it is a genuine design question about
that DTO's fixed-shape contract (rule 6) — too big to bolt onto a creation-path task. File it.

**D-6 — no versioning changes.** `ColumnEntity` already carries `@Version`; a new field is covered
by the existing optimistic-lock machinery for free.

**X-1 — the XSS test lives in `InjectionAttemptTest.StoredXss` using MockMvc, not REST Assured.**
Deliberate deviation from the brief's "REST Assured, matching the existing controller-test
convention" wording, on evidence: REST Assured appears in exactly six test files, all of them
`AbstractAppE2ETest` real-socket classes (`e2e/board`, `e2e/reset`, `security/*E2ETest`); every
`controller/*ControllerTest` and the entire existing `InjectionAttemptTest` adversarial-payload
suite use MockMvc via `AbstractAppMockMvcTest`. Two costs to using REST Assured here: the test would
sit at the real-socket tier and be excluded from the pre-commit `fastTest` gate by its
`@Tag("realSocket")`, and it would not be adjacent to the `StoredXss` group whose D-16 decision
comment it directly qualifies. MockMvc is both the convention and the gate-covered choice.

</decisions_recorded>

<tasks>

<task type="tracer">
  <name>Task 1: Schema and entity — one migration, one field, agreeing at boot</name>
  <files>
    src/main/resources/db/migration/V9__add_columns_color.sql
    src/main/java/com/vrudenko/kanban_board/constant/ValidationConstants.java
    src/main/java/com/vrudenko/kanban_board/entity/ColumnEntity.java
  </files>
  <action>
Confirm first that `V8__add_boards_created_at.sql` is still the highest-numbered file under
`src/main/resources/db/migration/` and that no `V9` exists; if a concurrent branch has taken V9,
use the next free number and carry it through every reference below.

Create `V9__add_columns_color.sql` containing a single statement adding a nullable `color` of type
`varchar(7)` to `columns`, with no `DEFAULT` and no backfill. Give it a header comment in the shape
V5 and V8 use: state that nullability is deliberate because existing rows have no meaningful color
and inventing one is a product decision, and that the format is enforced at the DTO boundary rather
than by a CHECK because a CHECK violation resolves through `handleDataIntegrityViolation` to a 409
carrying the raw constraint message. That is a decision record, not narration — it is the one
comment category `CODE_STYLE.md` treats as worth its length.

Add to `ValidationConstants`, placed with the other column entries: `COLUMN_COLOR_LENGTH` = 7,
`COLUMN_COLOR_PATTERN` = the anchored six-hex-digit pattern preceded by a literal hash, accepting
both letter cases, and `COLUMN_COLOR_VALIDATION_MESSAGE` naming the required `#RRGGBB` form. All
three must be `public static final` so `@Pattern`/`@Column` can consume them as compile-time
constants.

Add `color` to `ColumnEntity` as a `String` annotated `@Column(length =
ValidationConstants.COLUMN_COLOR_LENGTH)`. Copy `TaskEntity.title`'s annotation shape but omit
`nullable = false`. Do not add a field initializer. Do not touch the class's `@Getter`/`@Setter`-only
Lombok configuration — the class carries a long field-level decision record explaining why
field-based equals/hashCode was removed, and adding any `@EqualsAndHashCode` back would re-open it.

Then run `./gradlew spotlessApply` before verifying.
  </action>
  <verify>
    <automated>./gradlew test --tests '*FlywaySchemaProvenanceTest*' -x jacocoTestCoverageVerification</automated>
  </verify>
  <verification_notes>
`-x jacocoTestCoverageVerification` is mandatory on every filtered run in this repo and is not
defensive noise: `build.gradle:321` wires `test` `finalizedBy jacocoTestCoverageVerification`, and
that gate's 0.90 instruction floor is sized against the whole suite. `docs/INFRA_RUNBOOK.md:561`
records a filtered five-class run failing it at 0.41 with zero failing tests. Omitting the exclusion
produces a BUILD FAILED that looks like a test failure and is not one.

**Direction checked — this task's gate fails without the migration.** Before trusting green, rename
`V9__add_columns_color.sql` to `V9__add_columns_color.sql.disabled` and re-run the same command.
Expected: the Spring context fails to start with a Hibernate `SchemaManagementException` naming a
missing column `color` on table `columns`. Restore the filename and confirm green. Record both
observed outcomes in the summary. This is what proves the entity field and the migration are
actually coupled rather than independently green.

Second direction worth one minute: set the entity annotation's `length` to a value other than 7 and
re-run — expected, a schema-validation failure on column length. This is the failure mode that would
otherwise surface as an unexplained whole-suite outage in a later task.
  </verification_notes>
  <done>
`columns.color varchar(7)` exists in the Testcontainers-built schema, is nullable, and Hibernate's
`ddl-auto=validate` accepts `ColumnEntity` against it. `FlywaySchemaProvenanceTest` passes
unchanged — its migration-count assertion targets a fixed `IN ('1'..'6')` set and its table-set
assertion lists tables, not columns, so neither needs editing.
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: `@ColumnColor` and the DTO fields, with the boundary matrix at the validator tier</name>
  <files>
    src/main/java/com/vrudenko/kanban_board/dto/annotation/ColumnColor.java
    src/main/java/com/vrudenko/kanban_board/dto/column_dto/SaveColumnRequestDTO.java
    src/main/java/com/vrudenko/kanban_board/dto/column_dto/ColumnResponseDTO.java
    src/main/java/com/vrudenko/kanban_board/dto/column_dto/ColumnFullResponseDTO.java
    src/test/java/com/vrudenko/kanban_board/dto/ColumnColorTest.java
  </files>
  <behavior>
    - null (field omitted) → zero violations. This is the whole optionality contract.
    - `#ff0000`, `#FF0000`, `#AbCdEf` → zero violations each; mixed case is not a special case.
    - `""` → one violation, property path `color`.
    - `"   "` → one violation.
    - `ff0000` (no leading hash) → one violation.
    - `#ff000` (five digits) and `#ff00000` (seven digits) → one violation each.
    - `#gg0000` (non-hex letters) → one violation.
    - `"#ff0000\n"` (valid value plus a trailing newline) → one violation. This is the
      `Matcher.matches()` whole-region claim from trade-off 3, tested rather than asserted.
    - `#ff0000` padded with surrounding spaces → one violation. Bean Validation does not trim, and
      a reader who assumes it does would introduce a real hole.
  </behavior>
  <action>
Create `ColumnColor.java` in `dto/annotation/` following `BoardName.java`'s exact structure:
`@Documented`, `@Target({ElementType.FIELD})`, `@Retention(RetentionPolicy.RUNTIME)`,
`@ReportAsSingleViolation`, `@Constraint(validatedBy = {})`, one composing `@Pattern` sourcing its
`regexp` and `message` from the `ValidationConstants` entries added in Task 1, and the three
standard members (`message`, `groups`, `payload`). No hand-written `ConstraintValidator`.

Give the annotation a short class-level Javadoc recording exactly two things, per D-4 and D-1:
why `@OptionalNotBlank` is deliberately absent (the pattern already rejects blank, and stacking
would double the violation count), and that a valid value is persisted verbatim with no case
normalization. Keep it to those two points — everything else about the annotation is legible from
its own declaration.

Add `@ColumnColor private String color;` to `SaveColumnRequestDTO`, leaving the existing `name`
field and its constraints untouched. Add a plain `private String color;` to `ColumnResponseDTO` and
to `ColumnFullResponseDTO`. Response DTOs carry no validation annotations anywhere in this codebase
and must not start here. Do not add `@JsonInclude` to any of the three.

Do NOT edit `ColumnMapper`, `ColumnFullMapper`, `BoardFullMapper`, `BoardRepository`,
`ColumnService`, `BoardService`, `BoardController` or `BaseColumn`. MapStruct matches
source-to-target by property name, and `color` → `color` resolves implicitly on all three mapping
methods. That implicit resolution is the risk, not the convenience — `unmappedTargetPolicy =
ReportingPolicy.IGNORE` means a name mismatch would be silent, which is why the verify step below
greps the generated implementations instead of trusting it.

Write `ColumnColorTest.java` under `src/test/java/com/vrudenko/kanban_board/dto/`, covering the
matrix above. Copy `OptionalNotBlankTest`'s tier exactly: no `@SpringBootTest`, no fixture base
class, no container — a `jakarta.validation.Validator` from
`Validation.buildDefaultValidatorFactory()` in `@BeforeEach`. Nest by DTO under test, name methods
`should<Outcome>_when<Condition>`, mark AAA sections with comments, use fully-qualified AssertJ
`Assertions` (rules 3 and 5). Assert on violation COUNT and property path in every case; assert on
message text only for a case where exactly one constraint can fire — `@ReportAsSingleViolation`
collapses the composing `@Pattern`'s message into `@ColumnColor`'s own, which rule 4 flags as a
place where message-only assertions cannot establish which constraint fired.

Run `./gradlew spotlessApply` before verifying.
  </action>
  <verify>
    <automated>./gradlew test --tests '*ColumnColorTest*' -x jacocoTestCoverageVerification</automated>
    <automated>grep -n 'color' build/generated/sources/annotationProcessor/java/main/com/vrudenko/kanban_board/mapper/ColumnMapperImpl.java build/generated/sources/annotationProcessor/java/main/com/vrudenko/kanban_board/mapper/ColumnFullMapperImpl.java</automated>
  </verify>
  <verification_notes>
**The generated-mapper grep is a gate, not a curiosity.** `ColumnMapperImpl` must show `color`
being carried in both `fromSaveColumnRequestDTO` (entity setter) and `toColumnResponseDTO` (builder
call — `ColumnResponseDTO` is `@Builder`, so expect `.color(...)`, not `setColor`). `ColumnFullMapperImpl`
must show it once (`ColumnFullResponseDTO` has no builder, so expect a setter). If the generated
path differs from the one above, locate it with
`find build/generated -name 'Column*MapperImpl.java'` rather than assuming the build layout. Zero
hits in any of the three places means MapStruct silently dropped the field and every downstream
assertion in Task 3 would be testing nothing.

**Direction checked — mutation, not compile failure.** Writing the test first and watching it fail
to compile proves only that the field does not exist yet; that is a weak red. Do this instead:
with the field and tests in place and green, comment out `@ColumnColor` on
`SaveColumnRequestDTO.color`, re-run, and confirm every invalid-input case FAILS (0 violations
where 1 was expected) while the null and valid cases stay green. Restore the annotation and confirm
full green. Record both observed counts. That mutation is what proves the matrix is pinned to the
constraint rather than to the field's mere existence.
  </verification_notes>
  <done>
`ColumnColorTest` passes; the whole boundary matrix including the trailing-newline and
surrounding-space cases is covered at the cheapest tier; the mutation above was run and observed to
turn the invalid-input cases red; the generated mapper implementations demonstrably carry `color`
on all three mapping methods.
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 3: HTTP behavior, the nested read, the XSS case, and the docs</name>
  <files>
    src/test/java/com/vrudenko/kanban_board/controller/ColumnControllerTest.java
    src/test/java/com/vrudenko/kanban_board/controller/BoardFullReadTest.java
    src/test/java/com/vrudenko/kanban_board/security/InjectionAttemptTest.java
    docs/ARCHITECTURE.md
  </files>
  <behavior>
    - ColumnControllerTest, create-with-color: POST the columns endpoint with a valid mixed-case
      color → 201; the deserialized `ColumnResponseDTO` carries that exact string; re-reading the
      row through `columnRepository` shows the same string persisted. Response echo alone is not
      evidence of persistence.
    - ColumnControllerTest, create-without-color: POST a body carrying only `name` → 201; response
      `color` is null; the persisted row's color is null. This is the backwards-compatibility guard
      for every existing client and every existing test.
    - ColumnControllerTest, create-with-invalid-color: POST `not-a-color` → 400, ProblemDetail
      envelope with the `code` property and an `errors` entry keyed on `color`. One
      controller-tier representative only — the full matrix lives in `ColumnColorTest` per rule 4's
      tier split.
    - BoardFullReadTest: after creating a column with a color, the full-board nested read returns
      that column with the same color, and the number of prepared statements the read issues is
      unchanged from the existing baseline assertion in that class.
    - InjectionAttemptTest.StoredXss: POST with `color` = `<script>alert(1)</script>` → 400 with the
      field-error envelope, and no column row exists carrying that value. Same for the
      attribute-breakout form `" onload="alert(1)`. Prefer a `@ParameterizedTest` with a
      `@ValueSource` over two near-identical methods — that class already uses both annotations.
  </behavior>
  <action>
Extend the existing `ColumnControllerTest` nested group that covers column creation rather than
creating a new class; follow the surrounding methods' MockMvc + `.with(user(userId))` shape and
their `objectMapper.readValue(...)` response handling. `SaveColumnRequestDTO` is `@Builder`, so
build request bodies through the builder rather than hand-writing JSON, except for the
invalid-value case, where a hand-written JSON string is the only way to send a value the builder
would happily accept but the constraint must reject.

Extend `BoardFullReadTest` inside its existing nested structure. It already fetches a column out of
`body.getColumns()` by id and asserts on `getName`/`getVersion`/`getPosition`; add the color
assertion in that same shape. Create the colored column through
`boardService.addColumnByBoardId(...)` with a `SaveColumnRequestDTO` carrying a color — that class
already does exactly this for its own fixtures.

Extend `InjectionAttemptTest.StoredXss`. Its existing helpers `createColumn(cookie, boardId, name)`
and `listColumns(cookie, boardId)` are the shape to reuse; the new case needs a request body
carrying a `color`, so either widen the helper or add a sibling. Authenticate with
`signinCookie()` and replay the cookie, NOT `.with(user(userId))` — that class's own Javadoc
documents that `.with(user(...))` establishes a fresh session per call and trips the two-session
concurrency ceiling on the third request in a method. Assert on the 400 status and the envelope;
then assert no persisted column carries the payload. Add a short comment marking this case as the
deliberate counterpoint to the group's D-16 verbatim-round-trip decision: `color` rejects because
its format is closed, not because a sanitization policy was introduced. Do not add any
sanitization or output encoding anywhere.

In `docs/ARCHITECTURE.md`'s "Schema management" section, extend the migration chain that currently
ends at `V7__add_board_optimistic_locking_version_column`. It is already stale — `V8` shipped and
was never added — so append both `V8__add_boards_created_at` and `V9__add_columns_color`, with one
clause naming what V9 does and that the column is nullable by design. One or two sentences; that
section is a chain listing, not a changelog.

Run `./gradlew spotlessApply`, then the full gate.
  </action>
  <verify>
    <automated>./gradlew spotlessCheck</automated>
    <automated>./gradlew test</automated>
  </verify>
  <verification_notes>
The final `./gradlew test` is unfiltered on purpose: this is the point where
`jacocoTestCoverageVerification` must run for real (it is `finalizedBy` on `test`), and where the
pre-existing suite proves nothing regressed. Two places specifically worth reading the result of
rather than just the exit code: `ColumnControllerTest.UpdateById`'s `content().json(...)`
assertion, which is JSONAssert-LENIENT and should stay green with `color` null on both sides
(trade-off 5), and any query-count assertion over the full-board read.

**Direction checked, create-with-color:** run the new test before Task 2's DTO fields exist — it
fails to compile, which is a weak red. Do the mutation instead: with everything green, comment out
`color` in `ColumnResponseDTO` — expected, the response-body assertion fails. Then restore it and
comment out `color` in `ColumnEntity` — expected, the persistence read-back assertion fails. Two
separate mutations because the test makes two separate claims.

**Direction checked, create-without-color:** this one cannot be made to fail by removing the
feature — a codebase with no `color` at all passes it. That is honest and expected: it is a
regression guard, not a feature test, and its value is that it goes red if a future change makes
`color` mandatory. State that plainly in the summary rather than claiming a red direction it does
not have. Confirm it fails in the direction it IS meant to catch by temporarily adding `@NotBlank`
alongside `@ColumnColor` and observing a 400 where 201 was expected.

**Direction checked, nested full-board read:** removing `color` from `ColumnFullResponseDTO` is
another compile failure. The real mutation: add `@Mapping(target = "color", ignore = true)` to
`ColumnFullMapper.toColumnFullResponseDTO` and re-run — expected, the nested color assertion fails
with null while the flat-read assertions stay green. That isolates the mapper chain, which is
exactly the silent-drop risk this test exists for. Restore afterwards.

**Direction checked, XSS — read this before writing the test.** The trap is real and it is the one
that would make this test worthless. `^#[0-9a-fA-F]{6}$` rejects every script payload by
construction, so the test goes green the instant the regex exists, and a naive assertion
("the payload is not reflected in the response") would ALSO pass against a codebase where `color`
was never added — Spring Boot leaves `FAIL_ON_UNKNOWN_PROPERTIES` disabled, so an unknown `color`
key is silently dropped and creation returns **201**. Therefore: assert on the **400 status and the
`errors` envelope**, never on the absence of the payload. Then prove the red direction by
commenting out `@ColumnColor` on `SaveColumnRequestDTO.color` (field still present, no format
constraint) and re-running — expected, 201 with the script payload persisted in `columns.color`,
i.e. the test FAILS. Restore and confirm green. Record the observed status codes for both
directions. If for any reason it cannot be made to fail that way, say so plainly in the summary and
do not count the case as covered.
  </verification_notes>
  <done>
`./gradlew spotlessCheck` and `./gradlew test` both pass, including the JaCoCo ratchet on the
unfiltered run. Every mutation named above was executed, its observed outcome recorded, and the
source restored. `docs/ARCHITECTURE.md`'s migration chain reaches V9.
  </done>
</task>

</tasks>

<threat_model>

## Trust boundaries

| Boundary | Description |
|---|---|
| HTTP client → `BoardController.addColumnByBoardId` | Untrusted JSON crosses here; `@Valid @RequestBody SaveColumnRequestDTO` is the only filter between it and the entity. |
| `ColumnEntity.color` → every column read (flat and nested) | Whatever is persisted is echoed to every future reader of that board, including other sessions of the same user. |

## STRIDE register

| ID | Category | Component | Severity | Disposition | Mitigation |
|---|---|---|---|---|---|
| T-obv-01 | Tampering | `SaveColumnRequestDTO.color` | medium | mitigate | Composed `@ColumnColor` anchored pattern at the DTO boundary; `varchar(7)` as a length backstop. Proven by `ColumnColorTest`'s matrix, including the trailing-newline case. |
| T-obv-02 | Elevation of Privilege (stored XSS in a consumer) | `color` echoed on every column read | medium | mitigate | Same input constraint — the value set admits no `<`, `>`, quote or space. Proven by the `InjectionAttemptTest.StoredXss` addition asserting 400. No output encoding is added: `StoredXss`'s D-16 decision comment states escaping for display is the consuming frontend's job, and that decision is not reopened here. |
| T-obv-03 | Information disclosure | Validation failure response body | low | mitigate | Format enforcement is kept at the DTO boundary specifically so a malformed value produces a Bean Validation field error rather than a `DataIntegrityViolationException` whose `detail` is `ex.getMessage()` — a raw database constraint message (`GlobalExceptionHandler:178`). This is the concrete reason D-3 rejects a DB CHECK. |
| T-obv-04 | Denial of Service | The validation regex | low | accept | `^#[0-9a-fA-F]{6}$` is fixed-length, alternation-free and has no nested quantifier, so it is linear-time on any input; `@Size`-free by design. Accepted with no further control. |

No package-manager installs in this task, so no legitimacy gate applies.

</threat_model>

<verification>
1. `./gradlew spotlessCheck` — passes.
2. `./gradlew test` — passes unfiltered, including `jacocoTestCoverageVerification`.
3. Every mutation named in the three `<verification_notes>` blocks was actually run, its observed
   outcome recorded in the summary, and the source restored. A summary that reports green without
   the red directions has not verified anything.
4. The generated `ColumnMapperImpl` / `ColumnFullMapperImpl` grep showed `color` on all three
   mapping methods.
</verification>

<success_criteria>
- Creating a column with `"color": "#AbCdEf"` returns 201 and that exact string, persisted and
  re-read from the database.
- Creating a column with no `color` returns 201 exactly as before, with null in the response and in
  the database, and no pre-existing test needed editing to stay green.
- A malformed color and an XSS payload are both answered 400 with the field-error envelope, and no
  row is persisted; the XSS case was proven to return 201 with the payload stored when the format
  constraint is removed.
- The nested full-board read carries `color`, and fails when the mapper is made to ignore it.
- `docs/ARCHITECTURE.md`'s migration chain lists V8 and V9.
- The follow-up from D-5 (colors are set-once and uneditable) is filed, not silently dropped.
</success_criteria>

<output>
Write `.planning/quick/260904-obv-add-color-field-to-column-and-accept-it-/260904-obv-SUMMARY.md`
when done. It must record, per test, which red direction was checked and what was observed —
including the create-without-color case, which honestly has no feature-removal red direction and
must be reported as a regression guard rather than counted as covered.
</output>
