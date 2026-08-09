# Phase 7: Restructure test folder - Research

**Researched:** 2026-08-09
**Domain:** Java/Spring Boot test-suite organization (JUnit 5, Testcontainers, REST Assured, MockMvc) — no production code
**Confidence:** HIGH (every claim below was produced by reading the actual file, not by pattern-matching on names)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** All shared test infrastructure moves into one `support/` package, subdivided into
  subfolders by concern (e.g. containers/base classes in one subfolder, event-recording
  listeners in another) rather than staying flat or splitting into multiple top-level packages.
  User's explicit call: combine "one location" with "categorized within it." Exact subfolder
  names/boundaries are Claude's discretion. — Reversibility: reversible — package moves are a
  mechanical, compiler-checked refactor with no runtime behavior change.
- **D-02:** Researcher/planner actively scan for genuinely closely-related test classes (same
  entity/service tested from different angles) and propose specific merge candidates with
  justification. Executor merges only the candidates with a clear readability win — this is not
  evaluation-only; approved merges get applied in this phase.
- **D-03:** The exact downgrade rule is not locked — user was explicitly unsure whether the
  codebase has any "no real socket needed" E2ETest classes and didn't want to guess blind.
  Confirmed during discussion: the app already has both tiers in practice today — 4
  `controller/*ControllerTest` classes use in-process `MockMvc`, while 16 files reference
  RestAssured/RANDOM_PORT (real HTTP socket); the todo counts 23 `E2ETest`-suffixed classes
  overall (this research finds the actual current count is 22 concrete classes — see Open
  Questions). Research must determine the actual rule by inspecting what each of the 23/22
  classes asserts (real-socket-specific behavior? Kafka/Schema-Registry dependency?) and propose
  a concrete, applicable-per-class rule during planning — grounded in what's found, not decided
  upfront. The todo's own starting hypothesis ("no real-socket assertion AND no Kafka
  dependency") is a reasonable default to test against, not a locked decision.
- **D-04:** This phase executes everything decided, not just recommends. Fixture relocation,
  approved `@Nested` merges, and the tier downgrades that pass the research-derived rule (D-03)
  all happen as real diffs in this phase's plan(s) — no separate "recommendation doc, defer
  execution" step.

### Claude's Discretion

- Exact `support/` subfolder names and boundaries (D-01) — e.g. `support/containers/` for the
  three `Abstract*ContainerTest`/`AbstractAppTest`/`AbstractAppE2ETest` base classes vs.
  `support/listeners/` or similar for `RecordingActivityEventListener` — planner's call, guided
  by what's actually in each class. **This research recommends a finer-grained 3-way split; see
  "Fixture Relocation Plan" below for the reasoning.**
- Specific `@Nested` merge candidates (D-02) — planner/researcher identify which of the 44 (now
  48 on disk) concrete test classes are close enough (same entity/service, different angles) to
  justify merging; no candidates are pre-selected here.
- The concrete E2ETest→in-process downgrade list (D-03) — entirely research-driven; no classes
  are pre-selected for downgrade or exemption here. **This research delivers that list — see
  "E2ETest Tier-Downgrade Verdicts" below.**

### Deferred Ideas (OUT OF SCOPE)

None — discussion stayed within the three items the source todo scoped (fixture layout, @Nested
merges, E2ETest tier downgrades). No production code changes, no new test coverage, no change to
the no-mocks/real-DB-everywhere testing philosophy (`docs/CODE_STYLE.md`).

</user_constraints>

<phase_requirements>
## Phase Requirements

No formal requirement IDs exist for this phase yet (per ROADMAP.md, TBD). This phase is scoped
entirely by `.planning/phases/07-.../07-CONTEXT.md`'s three decisions (D-01 fixture relocation,
D-02 `@Nested` merges, D-03 E2ETest tier downgrade), which this research directly supports:

| Decision | Research Support |
|----------|------------------|
| D-01 (fixture relocation) | "Fixture Relocation Plan" section: concrete subfolder structure, full file move list, complete import blast radius (grepped, not estimated) |
| D-02 (`@Nested` merge candidates) | "@Nested Merge Candidates" section: specific file groups named, with anti-candidates flagged |
| D-03 (E2ETest tier downgrade) | "E2ETest Tier-Downgrade Verdicts" section: per-class KEEP/DOWNGRADE verdict for all 22 concrete `E2ETest`-suffixed classes, each backed by a full read of the file |

</phase_requirements>

## Summary

This phase touches 48 files under `src/test/java/com/vrudenko/kanban_board/` (not the 45 the
source todo estimated — 7 new test files landed during Phase 6 after the todo was filed on
2026-08-08; see Open Questions). All three evaluation items have concrete, file-level answers
below, each backed by having actually read every file in scope, not by grepping filenames.

**D-01 (fixture relocation):** the five shared-infrastructure files split naturally into three
concerns, not two: real Testcontainers container lifecycle (`AbstractPostgresContainerTest`,
`AbstractKafkaContainerTest`), app-level fixture data + HTTP helpers (`AbstractAppTest`,
`AbstractAppE2ETest`), and event capture (`RecordingActivityEventListener`). Recommended target:
`support/containers/`, `support/fixtures/`, `support/listeners/`. The import blast radius is
large — effectively every one of the 48 files touches at least one of these four base-class names,
either as a brand-new import (files currently in the same package as the base class, relying on
implicit same-package visibility) or as an import-path edit (files already in a subpackage).

**D-02 (`@Nested` merges):** the strongest, most defensible candidate is consolidating the three
real-HTTP authentication/session files (`security/AuthenticationControllerTest`,
`security/SessionPersistenceE2ETest`, `security/UserPersistenceE2ETest`) into one file — they are
already co-located in the `security` package, already share near-identical
RestAssured/RANDOM_PORT setup code (one of them, `AuthenticationControllerTest`, actually
duplicates `AbstractAppE2ETest`'s port/cookie wiring instead of extending it — a pre-existing
defect this phase's relocation work will make obvious), and together cover one code path
(`AuthenticationController.authenticate`) from six genuinely different angles. Three further
per-entity candidates (Column, Task, Subtask/Board resource families) become viable **only after**
their sibling E2E classes downgrade to the MockMvc tier per D-03 — merging an in-process MockMvc
class with a still-real-socket RestAssured class would defeat the whole point of tiering. Two
explicit **anti-candidates** are flagged to prevent a well-intentioned but wrong merge.

**D-03 (tier downgrade):** of 22 concrete `E2ETest`-suffixed classes (not 23 — see Open
Questions), **13 downgrade** to the in-process `@SpringBootTest` + `MockMvc` tier and **9 stay**
on the real-socket/real-Kafka tier. 8 of the 9 keepers are Kafka/Schema-Registry-dependent
(`extends AbstractKafkaContainerTest`) and are kept per the todo's own stated rule without
further debate. The 9th, `BoardCreationE2ETest`, is kept for a different, non-obvious reason: its
`ConcurrentCreate` nested test fires two genuinely concurrent HTTP requests from two threads to
prove a database-level unique-constraint race — a guarantee this codebase's own
`ActivityLogIdempotencyE2ETest` precedent (cited in `BoardCreationE2ETest`'s own Javadoc) already
treats as belonging on the real-socket tier, since Spring's `MockMvc` carries no documented
thread-safety guarantee for concurrent multi-threaded `perform()` calls the way a live embedded
Tomcat instance does.

**Primary recommendation:** execute all three decisions as scoped. Move the five shared files
into a 3-way-split `support/` package; merge the auth/session trio into one file; downgrade the
13 named classes to MockMvc, keep the 9 named classes on RestAssured/RANDOM_PORT — and, while
doing the relocation work, delete or flag the two genuinely empty test files this research
surfaced as a byproduct (`BoardE2ETest.java`, `service/SubtaskServiceTest.java` — both zero
`@Test` methods), since they are exactly the kind of file-count sprawl the source todo complains
about, with none of the value.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Shared test fixture/setup infrastructure | Test source tree (`src/test/java`) | — | Pure test-scaffolding concern; no production tier owns it |
| `@Nested` test-class organization | Test source tree | — | Readability/navigation concern within the test tree only |
| E2ETest tier classification (socket vs. in-process) | Test source tree | Spring MVC dispatch (`DispatcherServlet` vs. `MockMvc`) | The tier boundary tracks how a request enters the app (real embedded Tomcat socket vs. in-process servlet simulation), which is itself a test-infrastructure decision, not a production-code one |

This phase makes zero production-code changes (`src/main/java` is untouched); the "architecture"
being restructured is entirely within `src/test/java`.

## Standard Stack

No new libraries are introduced. This phase reorganizes files using the project's existing,
already-adopted test stack:

| Library | Version (as pinned in `build.gradle`) | Role in this phase |
|---------|----------------------------------------|---------------------|
| JUnit 5 (Jupiter) | Spring Boot 3.5.0 BOM-managed | `@Nested`, `@Test` — the merge mechanism (D-02) |
| REST Assured | 5.5.5 `[CITED: CLAUDE.md Technology Stack section]` | The real-HTTP tier being evaluated for downgrade (D-03) |
| Spring MockMvc / `spring-security-test` | Spring Boot 3.5.0 BOM-managed | The in-process tier candidate classes downgrade to (D-03) |
| Testcontainers PostgreSQL/Redpanda | 1.21.0 (BOM-managed) `[VERIFIED: CLAUDE.md Key Dependencies]` | Unaffected by this phase — container lifecycle classes are relocated, not rewritten |

No `npm view`/`pip index`/`cargo search` verification is applicable — this is a Gradle/Java
project touching zero `build.gradle` dependency lines.

## Package Legitimacy Audit

**Not applicable.** This phase installs no external packages and edits no dependency
coordinates in `build.gradle`. It is a pure package-relocation and file-consolidation refactor
within `src/test/java`.

## Fixture Relocation Plan (D-01)

### What each shared-infrastructure file actually does

Read directly, not inferred from naming:

| File | Current package | What it actually is `[VERIFIED: file read in full this session]` |
|------|------------------|---------------------------------------------------------------|
| `AbstractPostgresContainerTest.java` | `com.vrudenko.kanban_board` | Pure Testcontainers container lifecycle. Starts exactly one shared `PostgreSQLContainer<>` for the whole JVM run via a `static { postgres.start(); }` block (not the `@Testcontainers`/`@Container` JUnit extension — documented as deliberately avoided). Also carries the `docker-java` `api.version=1.44` system-property pin (testcontainers-java#11212). No fixture data, no HTTP helpers. |
| `activitylog/AbstractKafkaContainerTest.java` | `com.vrudenko.kanban_board.activitylog` | Pure Testcontainers container lifecycle for a `RedpandaContainer` (Kafka + Schema Registry), same imperative-`static`-start pattern as the Postgres class, plus `AvroSchemaRegistrar.registerAll(...)` schema bootstrap. `extends AbstractPostgresContainerTest`. Also carries `sendAndAwaitAck(ActivityEvent)`, a Kafka-send helper — this is the one piece of "fixture-ish" behavior in this class, but it's Kafka-specific, not app-domain-specific. |
| `AbstractAppTest.java` | `com.vrudenko.kanban_board` | App-level fixture **data**: creates 2 users, 3 boards, 7 columns, 7 tasks, 7 subtasks per test via the real service layer in `@BeforeEach`; `@AfterEach` cleanup; `countQueries(Runnable)` Hibernate-statistics helper. `extends AbstractPostgresContainerTest` — inherits the container, adds nothing container-related itself. |
| `AbstractAppE2ETest.java` | `com.vrudenko.kanban_board` | Real-HTTP-flow helper: wires `RestAssured.port`/`baseURI`/`basePath` from `@LocalServerPort` + `@Value`, and provides `signin()`. `extends AbstractAppTest`. This is fixture/setup for the socket-tier specifically, not a container concern. |
| `support/RecordingActivityEventListener.java` | `com.vrudenko.kanban_board.support` (already in target package) | A real Spring `@Component` (`@TransactionalEventListener(phase = AFTER_COMMIT)`) that records every `ActivityEvent` observed after commit. Not a test base class at all — it's a production-shaped test spy registered in the Spring context. |

### Recommended subfolder structure

CONTEXT.md's own worked example (`support/containers/` for **all five** base classes, vs.
`support/listeners/` for the recorder) conflates two genuinely different concerns — Testcontainers
lifecycle vs. app-domain fixture data — under one folder. Based on what these files actually do
(table above), this research recommends a **3-way split** instead:

```
support/
├── containers/          # Real Testcontainers lifecycle only — nothing app-domain-specific
│   ├── AbstractPostgresContainerTest.java
│   └── AbstractKafkaContainerTest.java
├── fixtures/             # App-domain fixture data + HTTP-flow helpers
│   ├── AbstractAppTest.java
│   └── AbstractAppE2ETest.java
└── listeners/            # Event-capture test doubles (real Spring components, not mocks)
    └── RecordingActivityEventListener.java
```

Rationale per folder:
- **`containers/`** — both files' entire responsibility is "start one Testcontainers instance
  for the whole JVM run and expose its connection details." Neither touches `UserService`,
  `BoardService`, or any other application service.
- **`fixtures/`** — both files' entire responsibility is "populate/tear down realistic
  application state through the real service layer" (`AbstractAppTest`) or "let a test talk to
  that state over a real HTTP connection" (`AbstractAppE2ETest`). Both depend on the app's own
  services (`UserService`, `BoardService`, `ColumnService`, `TaskService`), which the container
  classes never do.
- **`listeners/`** — `RecordingActivityEventListener` is a Spring-managed component, not an
  abstract test base class; grouping it with the two `containers/` files (as CONTEXT.md's example
  suggests) would mix "things you extend" with "things you autowire," which is exactly the kind
  of inconsistency the source todo is trying to eliminate.

This is the one place this research diverges from CONTEXT.md's own illustrative example — flagged
explicitly since D-01 states the exact boundary is "Claude's discretion," and the example was
offered as an illustration, not a locked choice.

### Full file move list

| File | From | To |
|------|------|-----|
| `AbstractPostgresContainerTest.java` | `com.vrudenko.kanban_board` | `com.vrudenko.kanban_board.support.containers` |
| `activitylog/AbstractKafkaContainerTest.java` | `com.vrudenko.kanban_board.activitylog` | `com.vrudenko.kanban_board.support.containers` |
| `AbstractAppTest.java` | `com.vrudenko.kanban_board` | `com.vrudenko.kanban_board.support.fixtures` |
| `AbstractAppE2ETest.java` | `com.vrudenko.kanban_board` | `com.vrudenko.kanban_board.support.fixtures` |
| `support/RecordingActivityEventListener.java` | `com.vrudenko.kanban_board.support` | `com.vrudenko.kanban_board.support.listeners` |

### Import blast radius (grepped, not estimated)

`[VERIFIED: grep run this session against src/test/java/com/vrudenko/kanban_board, see command output]`

**Files with `extends AbstractAppTest` (13 concrete classes + `AbstractAppE2ETest` itself, 14 total):**
`AbstractAppE2ETest.java`, `ActivityLogCleanupIsolationTest.java`, `controller/BoardControllerTest.java`,
`controller/ColumnControllerTest.java`, `controller/SubtaskControllerTest.java`,
`controller/TaskControllerTest.java`, `event/ActivityEventPublicationTest.java`,
`security/AuthenticationControllerTest.java`, `service/BoardServiceTest.java`,
`service/ColumnServiceTest.java`, `service/OwnershipVerifierServiceTest.java`,
`service/SubtaskServiceTest.java`, `service/TaskServiceTest.java`, `service/UserServiceTest.java`

**Files with `extends AbstractAppE2ETest` (14 total):**
`BoardCreationE2ETest.java`, `BoardFullReadE2ETest.java`, `ColumnDeletionE2ETest.java`,
`ColumnOrderingE2ETest.java`, `e2e/activity/ActivityReadE2ETest.java`, `e2e/board/BoardE2ETest.java`,
`e2e/column/ColumnLockingE2ETest.java`, `e2e/task/TaskLockingE2ETest.java`,
`e2e/task/TaskMoveE2ETest.java`, `security/SessionPersistenceE2ETest.java`,
`security/UserPersistenceE2ETest.java`, `SubtaskLockingE2ETest.java`, `TaskOrderingE2ETest.java`,
`ThemePersistenceE2ETest.java`

**Files with `extends AbstractPostgresContainerTest` (5 total):**
`AbstractAppTest.java`, `activitylog/AbstractKafkaContainerTest.java`, `EventIdGeneratorTest.java`,
`FlywaySchemaProvenanceTest.java`, `KanbanBoardApplicationTests.java`. One additional file
references it by fully-qualified name rather than `extends` + import:
`event/avro/ActivityEventAvroMapperTest.java` (`extends com.vrudenko.kanban_board.AbstractPostgresContainerTest`
`[VERIFIED: event/avro/ActivityEventAvroMapperTest.java:29-30]`) — 6 total touch points.

**Files with `extends AbstractKafkaContainerTest` (9 total):**
`activitylog/ActivityLogAvroDeadLetterE2ETest.java`, `activitylog/ActivityLogAvroRoundTripE2ETest.java`,
`activitylog/ActivityLogConsumerE2ETest.java`, `activitylog/ActivityLogDeadLetterE2ETest.java`,
`activitylog/ActivityLogIdempotencyE2ETest.java`, `activitylog/HistoricalActivityEventReconstructorTest.java`,
`activitylog/HistoricalSchemaRehearsalE2ETest.java`, `activitylog/SchemaCompatibilityE2ETest.java`,
`activitylog/SchemaRegistryOutageE2ETest.java`

**Files referencing `RecordingActivityEventListener` (2 usages + the file itself):**
`e2e/task/TaskMoveE2ETest.java`, `event/ActivityEventPublicationTest.java`

**Practical consequence for the executor:** because `AbstractAppTest`/`AbstractAppE2ETest`/
`AbstractPostgresContainerTest` currently live in the *root* package
(`com.vrudenko.kanban_board`), every file that is *also* in the root package today (e.g.
`BoardCreationE2ETest.java`, `SubtaskLockingE2ETest.java`, `EventIdGeneratorTest.java`,
`KanbanBoardApplicationTests.java`) currently has **no import statement at all** for these base
classes — same-package implicit visibility. Moving the base classes to `support.*` means these
files need a **brand-new** import added, not just an edited one. Files already in a subpackage
(`controller/`, `service/`, `security/`, `e2e/*/*`, `activitylog/`, `event/`) already have an
explicit import and only need the import's target path edited. `./gradlew compileTestJava`
(per CONTEXT.md's own Integration Points note) is the correct verification gate — it will fail
loudly and precisely on every file that needs one of these two edit types, so this move is
mechanical and compiler-checked, not a hidden risk.

## @Nested Merge Candidates (D-02)

### Candidate 1 (strongest): consolidate the three real-HTTP auth/session files

`security/AuthenticationControllerTest.java`, `security/SessionPersistenceE2ETest.java`,
`security/UserPersistenceE2ETest.java` `[VERIFIED: all three files read in full this session]`

All three: live in the same `security` package already; extend `AbstractAppTest` directly and
manually re-wire RestAssured's `port`/`baseURI`/`basePath`/`signin()`-equivalent setup rather than
extending `AbstractAppE2ETest` (which already provides exactly this) — confirmed by
`AuthenticationControllerTest.java:26-43`, which duplicates
`AbstractAppE2ETest.java:14-34` field-for-field (`@LocalServerPort int port`,
`@Value("${server.servlet.session.cookie.name}")`, `@Value("${server.servlet.context-path}")`,
the same `@BeforeEach` RestAssured wiring). This duplication is itself a defect this phase's
relocation work surfaces — the merge (or, at minimum, switching `AuthenticationControllerTest` to
extend `AbstractAppE2ETest`) fixes it as a side effect.

All three cover `AuthenticationController.authenticate` (the shared signin/signup call site) from
six distinct angles: successful signin/signup (`AuthenticationControllerTest.Signin`/`Signup`),
`spring_session`/`spring_session_attributes` schema+row persistence
(`SessionPersistenceE2ETest.SchemaCreation`/`SigninPersistence`), the concurrent-session ceiling
and session-fixation rotation (`SessionPersistenceE2ETest.ConcurrentSessionCeiling`/`SessionFixation`),
and bcrypt-hash persistence via signup (`UserPersistenceE2ETest.SignupPasswordHashPersistence`/`SignupThenSignin`).
Proposed target: one file (e.g. `security/AuthenticationE2ETest.java`), `@Nested` groups mirroring
the existing nested-class names verbatim (`Signin`, `Signup`, `SessionPersistence`,
`ConcurrentSessionCeiling`, `SessionFixation`, `PasswordHashPersistence`, `SignupThenSignin`).
Each currently-independent `@Nested`/`@Test` group can still be run individually after the merge
(JUnit 5's `@Nested` preserves per-class/per-method targeting), so no test-selection capability is
lost — satisfying D-02's explicit constraint.

### Candidates 2-4 (conditional on D-03 downgrades landing first)

Once the D-03 downgrades below are applied, these become natural `@Nested` merge targets **at the
MockMvc tier** (merging a socket-tier RestAssured file into an in-process MockMvc file first would
be backwards — the merge should happen after the tier change, not before, or the downgrade work
has to happen twice):

| Resource | Existing MockMvc-tier file | E2E files that downgrade into it (this research's D-03 verdicts) |
|----------|----------------------------|--------------------------------------------------------------------|
| Column | `controller/ColumnControllerTest.java` | `e2e/column/ColumnLockingE2ETest.java`, `ColumnOrderingE2ETest.java`, `ColumnDeletionE2ETest.java` |
| Task | `controller/TaskControllerTest.java` | `e2e/task/TaskLockingE2ETest.java`, `TaskOrderingE2ETest.java`, `e2e/task/TaskMoveE2ETest.java` |
| Subtask | `controller/SubtaskControllerTest.java` | `SubtaskLockingE2ETest.java` |
| Board | `controller/BoardControllerTest.java` | `BoardFullReadE2ETest.java`, and the non-`ConcurrentCreate` `@Nested` groups of `BoardCreationE2ETest.java` (`CreateBoard`, `DuplicateName`, `RenameBoard`, `CrossUserIsolation`) — `ConcurrentCreate` itself stays out (see D-03 verdict below) |

**Caution on file size:** the Column and Task merges each combine 4 source files into 1 (roughly
600-900 combined lines per the line counts read this session). The planner should weigh this
against the source todo's own explicit warning against "a mechanical mass-move without judgment" —
a reasonable middle ground is to execute Candidate 1 (auth/session, unconditionally justified) in
this phase, and treat Candidates 2-4 as executor's per-merge call once the D-03 downgrades are
in hand, applying only the ones with a "clear readability win" per D-02's own qualifier, rather
than forcing all three resource families to merge uniformly.

### Explicit anti-candidates (do not merge these)

- **`activitylog/ActivityLogDeadLetterE2ETest.java` + `activitylog/ActivityLogAvroDeadLetterE2ETest.java`**
  — same domain (dead-letter routing), similar names, and both `extends AbstractKafkaContainerTest`,
  which makes them look like an obvious merge by filename alone. The first class's own Javadoc
  states explicitly: *"This class is a sibling of [`ActivityLogDeadLetterE2ETest`], not a
  replacement — that class keeps proving the framing-level poison shapes it always has, unchanged"*
  `[VERIFIED: activitylog/ActivityLogAvroDeadLetterE2ETest.java:33-35]`. They intentionally test
  two different failure boundaries (pre-Avro JSON framing failures vs. post-Avro
  magic-byte/schema-id resolution failures) against the same topic. Merging them would blur that
  documented distinction.
- **`activitylog/SchemaCompatibilityE2ETest.java` + `activitylog/SchemaRegistryOutageE2ETest.java`
  + `activitylog/ActivityLogIdempotencyE2ETest.java` + `activitylog/HistoricalSchemaRehearsalE2ETest.java`**
  — all four share a package and a Kafka-container ancestor, but each covers a genuinely
  independent concern (schema compatibility-mode enforcement, registry-unavailable resilience,
  producer/consumer idempotent dedupe, historical-data rehearsal). Grouping-by-package-alone
  without a genuine "different angles on the same thing" relationship is exactly the failure mode
  D-02 warns against; each earns its own file.

### Housekeeping finding (not a merge candidate, but adjacent)

Two files in this survey are **entirely empty** — zero `@Test` methods:
- `e2e/board/BoardE2ETest.java` — `public class BoardE2ETest extends AbstractAppE2ETest {}`
  `[VERIFIED: e2e/board/BoardE2ETest.java:7, confirmed 0 @Test matches via grep]`
- `service/SubtaskServiceTest.java` — `public class SubtaskServiceTest extends AbstractAppTest {}`
  `[VERIFIED: service/SubtaskServiceTest.java:5, confirmed 0 @Test matches via grep]`

These are not "closely related classes split across files" (D-02's target), but they are exactly
the kind of file-count sprawl the source todo's "45 files, not clear at a glance" complaint is
about, with zero test value attached. Recommend flagging for deletion in this phase's plan as a
low-risk cleanup — either delete outright (nothing depends on them; `BoardE2ETest` and
`SubtaskServiceTest` are referenced nowhere else per the grep results above) or, if the planner
prefers to stay strictly in scope, leave a one-line TODO comment and defer deletion to a future
quick task. This is offered as a finding, not a locked recommendation — D-04 scopes this phase to
"fixture relocation, `@Nested` merges, and tier downgrades," and file deletion is adjacent to but
not explicitly one of those three items.

## E2ETest Tier-Downgrade Verdicts (D-03)

### The rule this research applied

Per-class, two independent questions, evaluated by reading the file (not the filename):

1. **Kafka/Schema-Registry dependency** — does the class `extend AbstractKafkaContainerTest`
   (directly or transitively)? If yes: **KEEP**, full stop, regardless of question 2. This matches
   the todo's own stated rule and CONTEXT.md's explicit instruction to keep "the real-socket/
   real-Kafka tier for genuinely cross-cutting concerns... where it is actually buying something
   a Postgres-real, in-process test can't."
2. **Real-socket-specific assertion** — for every non-Kafka class, does any assertion in the file
   depend on something a real embedded-Tomcat HTTP socket provides that Spring's `MockMvc` (full
   filter chain, full `DispatcherServlet` dispatch, in-process — already proven in this codebase
   by the 4 existing `controller/*ControllerTest` classes) cannot replicate? Status codes,
   response headers (including `Location`), JSON body shape, and cookie values set via
   `Set-Cookie` are all faithfully reproduced by `MockMvc` — none of those alone justify KEEP.
   The one thing this research found that genuinely does NOT have a documented `MockMvc`
   equivalent is **true concurrent multi-threaded request dispatch**: REST Assured against a real
   `RANDOM_PORT` embedded Tomcat guarantees two threads' requests are handled by two real servlet
   container threads with real interleaving at the connection-pool/transaction level; `MockMvc`
   carries no equivalent documented guarantee for concurrent `perform()` invocation from multiple
   threads against one shared context.

### Verdict table (all 22 concrete classes)

| # | Class | Kafka dep? | Real-socket-specific? | Verdict | Reason |
|---|-------|:---:|:---:|---------|--------|
| 1 | `activitylog/ActivityLogAvroDeadLetterE2ETest.java` | Yes | — | **KEEP** | `extends AbstractKafkaContainerTest`; publishes raw poison bytes to a real Redpanda broker and reads back the real dead-letter topic |
| 2 | `activitylog/ActivityLogAvroRoundTripE2ETest.java` | Yes | — | **KEEP** | `extends AbstractKafkaContainerTest`; proves genuine Avro wire-format bytes (magic byte + schema id) through a real broker + real Schema Registry |
| 3 | `activitylog/ActivityLogConsumerE2ETest.java` | Yes | — | **KEEP** | `extends AbstractKafkaContainerTest`; consumer-group formation and delivery are asynchronous against a real broker |
| 4 | `activitylog/ActivityLogDeadLetterE2ETest.java` | Yes | — | **KEEP** | `extends AbstractKafkaContainerTest`; raw byte-array producer/consumer against real broker topics |
| 5 | `activitylog/ActivityLogIdempotencyE2ETest.java` | Yes | — | **KEEP** | `extends AbstractKafkaContainerTest` (confirmed by grep: class declaration line) |
| 6 | `activitylog/HistoricalSchemaRehearsalE2ETest.java` | Yes | — | **KEEP** | `extends AbstractKafkaContainerTest` (confirmed by grep) |
| 7 | `activitylog/SchemaCompatibilityE2ETest.java` | Yes | — | **KEEP** | `extends AbstractKafkaContainerTest` (confirmed by grep) |
| 8 | `activitylog/SchemaRegistryOutageE2ETest.java` | Yes | — | **KEEP** | `extends AbstractKafkaContainerTest` (confirmed by grep); needs the real, independently-controllable registry-URL override this class's harness exposes |
| 9 | `BoardCreationE2ETest.java` | No | **Yes** | **KEEP** | `ConcurrentCreate` nested class fires two real concurrent HTTP threads to race a DB unique-constraint (`uk_boards_user_id_name`); its own Javadoc models this on `ActivityLogIdempotencyE2ETest`'s concurrent-race pattern, which this codebase already treats as a real-infra-tier concern. The other four nested groups (`CreateBoard`, `DuplicateName`, `RenameBoard`, `CrossUserIsolation`) have no such dependency and are downgrade-eligible if the planner chooses to split the file (see Candidate 4 above) |
| 10 | `BoardFullReadE2ETest.java` | No | No | **DOWNGRADE** | Every assertion is status code + JSON body shape (nested `BoardFullResponseDTO`) or a flat-vs-nested field comparison; nothing socket-specific |
| 11 | `ColumnDeletionE2ETest.java` | No | No | **DOWNGRADE** | Status code + repository-state assertions after a sequential DELETE; no concurrency |
| 12 | `ColumnOrderingE2ETest.java` | No | No | **DOWNGRADE** | Sequential creates/reorders/deletes with position assertions via `ColumnRepository`; no concurrency despite testing an ordering invariant |
| 13 | `SubtaskLockingE2ETest.java` | No | No | **DOWNGRADE** | Sequential PUT-then-PUT optimistic-lock assertions (not genuinely concurrent — "first PUT, await response, second PUT") |
| 14 | `TaskOrderingE2ETest.java` | No | No | **DOWNGRADE** | Same shape as Column ordering — sequential position assertions via `TaskRepository` |
| 15 | `ThemePersistenceE2ETest.java` | No | No | **DOWNGRADE** | Status code + JSON body + a logout/re-signin round trip proving server-side (not session-scoped) persistence — testable under MockMvc via manual cookie relay across `perform()` calls, same filter chain either way |
| 16 | `e2e/activity/ActivityReadE2ETest.java` | No | No | **DOWNGRADE** | Own Javadoc states rows are seeded directly via `ActivityLogRepository`, "this suite needs no broker" `[VERIFIED: e2e/activity/ActivityReadE2ETest.java:26-30]`; pagination/sort assertions are pure HTTP+DB, no concurrency |
| 17 | `e2e/board/BoardE2ETest.java` | No | No | **DOWNGRADE** (trivial) | Zero `@Test` methods — nothing to evaluate; see Housekeeping finding above recommending deletion instead |
| 18 | `e2e/column/ColumnLockingE2ETest.java` | No | No | **DOWNGRADE** | Sequential optimistic-lock PUT/PUT/PUT assertions, same shape as `SubtaskLockingE2ETest` |
| 19 | `e2e/task/TaskLockingE2ETest.java` | No | No | **DOWNGRADE** | Sequential optimistic-lock PUT/PUT/PUT assertions, same shape |
| 20 | `e2e/task/TaskMoveE2ETest.java` | No | No | **DOWNGRADE** | All `@Nested` groups (`StaleVersion`, `ConcurrentConflict` — despite the name, this is two *sequential* PATCH calls, not real threads — `CrossBoardTarget`, `UnownedTarget`, `MissingVersion`, `UnknownIds`) are sequential HTTP + `RecordingActivityEventListener` assertions; `RecordingActivityEventListener` is a real Spring bean either way, so downgrading loses nothing there |
| 21 | `security/SessionPersistenceE2ETest.java` | No | No\* | **DOWNGRADE** (with caveat) | Proves Spring Session JDBC persistence, the concurrent-session ceiling, and session-fixation rotation — all through the real `AuthenticationController.authenticate` call site, none through a genuine concurrent race (its "concurrent ceiling" test issues two signins sequentially, then a third). \*Caveat: this class must NOT switch to the 4 controllers' `.with(user(userId))` MockMvc shortcut, since that bypasses `AuthenticationController.authenticate` entirely and would silently stop testing the thing this class exists to prove. It must instead POST to `/signin` via `mockMvc.perform()` and relay the resulting `Set-Cookie` value into the next `perform()` call by hand — Spring Boot auto-configures the full `springSecurityFilterChain` under `@AutoConfigureMockMvc` when `spring-security-test` is on the classpath, so `SessionAuthenticationStrategy` still runs |
| 22 | `security/UserPersistenceE2ETest.java` | No | No\* | **DOWNGRADE** (with caveat) | Proves a real HTTP `/signup` writes a bcrypt hash to `USERS` and that a fresh `/signin` authenticates against it — same caveat as row 21: must use a real `/signin`/`/signup` POST through `MockMvc`, never the `.with(user())` shortcut, or the class stops proving what it exists to prove |

### Summary count

- **22 concrete `E2ETest`-suffixed classes** exist on disk today (not 23 — see Open Questions).
- **9 KEEP** (8 Kafka/Schema-Registry-dependent + 1 real-concurrency-dependent).
- **13 DOWNGRADE** (11 clean downgrades + 2 with a documented migration caveat: must preserve the
  real `/signin`/`/signup` POST + cookie-relay pattern, not adopt the `.with(user())` shortcut).

## Common Pitfalls

### Pitfall 1: Treating filename similarity as a merge signal
**What goes wrong:** `ActivityLogDeadLetterE2ETest` and `ActivityLogAvroDeadLetterE2ETest` look
like an obvious merge by name and shared ancestor, but the first class's own Javadoc explicitly
documents them as intentional siblings testing different failure boundaries.
**Why it happens:** package co-location + near-identical names are a strong visual signal that
overrides actually reading the Javadoc.
**How to avoid:** read the class-level Javadoc of every merge candidate before merging — several
classes in this codebase explicitly document why they are *not* redundant with a sibling.
**Warning signs:** a class Javadoc containing the word "sibling," "not a replacement," or
"deliberately... different."

### Pitfall 2: Downgrading `SessionPersistenceE2ETest`/`UserPersistenceE2ETest` via the wrong MockMvc idiom
**What goes wrong:** copying the `.with(user(userId))` shortcut from the 4 existing
`controller/*ControllerTest` classes into these two classes would compile and pass, but would
silently stop exercising `AuthenticationController.authenticate` — the `SessionAuthenticationStrategy`
(concurrent-session ceiling, session-fixation rotation) and the bcrypt-hash-write path would never
run, since `.with(user())` injects a pre-authenticated principal directly, bypassing the real
signin flow entirely.
**Why it happens:** `.with(user(userId))` is the only MockMvc auth pattern this codebase currently
has a precedent for, so it's the path of least resistance.
**How to avoid:** for these two classes only, use a real `POST /signin` (or `/signup`) through
`mockMvc.perform()`, extract the `Set-Cookie` header from the response, and pass it explicitly
(via `.cookie(...)`) on the next `perform()` call.
**Warning signs:** a downgraded test in this pair that still passes even when
`AuthenticationController.authenticate`'s session-strategy call is commented out — that's a sign
the real flow is no longer being exercised.

### Pitfall 3: Merging before downgrading (Column/Task/Subtask candidates)
**What goes wrong:** merging `ColumnLockingE2ETest` into `ColumnControllerTest` while
`ColumnLockingE2ETest` is still on the RestAssured/RANDOM_PORT tier produces a single file mixing
two different request-dispatch mechanisms (`MockMvc.perform()` alongside `given()...when()...`),
which is more confusing than the two-file status quo, not less.
**Why it happens:** D-02 and D-03 are presented as parallel, independent decisions, inviting
parallel execution.
**How to avoid:** sequence the plan's tasks so tier downgrades (D-03) land before any conditional
merge (D-02 Candidates 2-4) that depends on them.
**Warning signs:** a merged file importing both `io.restassured.RestAssured.given` and
`org.springframework.test.web.servlet.MockMvc` at once.

## Code Examples

### The existing, working MockMvc pattern this phase's downgrades should imitate
```java
// Source: controller/BoardControllerTest.java:30-32, 44-61 (read in full this session)
@SpringBootTest
@AutoConfigureMockMvc
public class BoardControllerTest extends AbstractAppTest {
    @Autowired private MockMvc mockMvc;

    @Nested
    class FindAllByUserId {
        @Test
        void testWithAuthenticatedUser_shouldReturn_whenBoardsExist() throws Exception {
            var userId = getOwningUser().getId();
            mockMvc.perform(get(getBoardPrefix()).with(user(userId)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(content().json(allBoards))
                    .andReturn();
        }
    }
}
```
This is the shortcut pattern (`.with(user(userId))`) that is correct for every downgrade
candidate **except** `SessionPersistenceE2ETest`/`UserPersistenceE2ETest`, per Pitfall 2 above.

### The Kafka-dependency signal that forces KEEP
```java
// Source: activitylog/ActivityLogConsumerE2ETest.java:30-31 (read in full this session)
@SpringBootTest
class ActivityLogConsumerE2ETest extends AbstractKafkaContainerTest {
```
Any class with this `extends` clause is an automatic KEEP under this research's rule — no further
per-assertion analysis needed.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|----------------|
| A1 | `MockMvc.perform()` carries no documented thread-safety guarantee for genuinely concurrent multi-threaded invocation against one shared `MockMvc` instance, the way a real embedded-Tomcat socket does — used as the sole justification for keeping `BoardCreationE2ETest` on the real-socket tier. `[ASSUMED]` — this is standard Spring-testing community knowledge/training-data knowledge, not verified against Spring's official reference docs in this session (no web search was run to confirm this specific claim). | E2ETest Tier-Downgrade Verdicts, row 9 | If wrong (i.e. MockMvc genuinely does support safe concurrent `perform()` calls), `BoardCreationE2ETest` could downgrade too, making all 22 classes fall into a clean binary Kafka-dependent/not-Kafka-dependent rule with zero exceptions — a materially simpler outcome the planner should sanity-check with a spike (write the downgraded concurrent test, run it a dozen times, watch for flakiness) before committing either way |
| A2 | Spring Boot auto-configures the full `springSecurityFilterChain` (including `SessionAuthenticationStrategy`) under `@AutoConfigureMockMvc` when `spring-security-test` is on the classpath, with no extra `.apply(springSecurity())` call needed — used to justify that `SessionPersistenceE2ETest`'s downgrade caveat (real POST + cookie relay) is achievable at all under MockMvc. `[ASSUMED]` — based on training knowledge of Spring Boot 3.x MockMvc autoconfiguration behavior, not verified against this project's actual Spring Boot 3.5.0 dependency behavior in this session. | E2ETest Tier-Downgrade Verdicts, rows 21-22; Pitfall 2 | If wrong, these two classes cannot safely downgrade via the documented caveat and should instead move to the KEEP list, changing the summary count from 13/9 to 11/11 |

## Open Questions

1. **CONTEXT.md's "23 E2ETest-suffixed classes" figure vs. this research's count of 22**
   - What we know: CONTEXT.md and the source todo both state 23; this research's own filename
     grep (`find ... -name "*E2ETest.java" ! -name "Abstract*"`) and an independent
     `grep -rl "class \w*E2ETest"` both return 22 concrete classes on disk today (2026-08-09).
   - What's unclear: whether the todo's original 23-count (filed 2026-08-08) included
     `AbstractAppE2ETest.java` itself (whose name also matches `*E2ETest` and whose class
     declaration also matches `class \w*E2ETest` via the `Abstract` prefix), which would explain
     the off-by-one exactly, or whether a 23rd concrete class existed on 2026-08-08 and was later
     renamed/removed during Phase 6.
   - Recommendation: treat this research's verdict table (22 rows) as authoritative for planning
     purposes — it is a direct file enumeration against the current tree, not a carried-forward
     estimate. If the planner wants certainty on the historical discrepancy, `git log --diff-filter=AD --name-only -- '**E2ETest.java'` against the 2026-08-08 commit range would resolve it, but it has no bearing on what to execute today.

2. **Whether to execute the Column/Task/Subtask/Board conditional `@Nested` merges (Candidates 2-4) in this same phase, or defer them**
   - What we know: they are legitimate merge candidates but only *after* the D-03 downgrades land
     (Pitfall 3), and merging 4 source files into 1 for Column and Task is a meaningfully larger
     diff than the unconditional auth/session merge (Candidate 1).
   - What's unclear: whether the user's D-02 "clear readability win" bar is met for a ~700-900
     line merged controller-test file, or whether that crosses into "harder to navigate than 4
     smaller files" — a genuinely subjective call this research cannot resolve on the user's
     behalf.
   - Recommendation: the plan should treat Candidate 1 as locked-in (unconditionally justified,
     independent of D-03 sequencing) and present Candidates 2-4 as executor's per-family
     discretion once the corresponding downgrade tasks are done, consistent with D-02's own
     "executor merges only the candidates with a clear readability win" language.

## Environment Availability

No new external dependency is introduced by this phase. The existing test suite already depends
on Docker (for Testcontainers PostgreSQL/Redpanda), already verified working in this environment
across every prior phase (04.1, 04.2, 4, 6) per `.planning/STATE.md`'s phase history. This
phase's changes are pure Java source moves/merges; `./gradlew compileTestJava` and `./gradlew test`
are the only two commands this phase's verification depends on, both already proven to run in
this environment.

## Security Domain

`security_enforcement: true`, `security_asvs_level: 1` per `.planning/config.json`. This phase
makes zero production-code changes (`src/main/java` untouched) and introduces no new attack
surface, new input-handling path, or new credential/session-handling logic — it moves and merges
test source files and reclassifies which HTTP-dispatch mechanism proves existing, already-shipped
behavior. The one place security-relevant *test coverage* is at stake is the caveat in Pitfall 2
above: downgrading `SessionPersistenceE2ETest`/`UserPersistenceE2ETest` incorrectly (via the
`.with(user())` shortcut instead of a real signin/signup POST) would silently stop testing the
concurrent-session ceiling and session-fixation protections — this is called out explicitly as a
pitfall precisely because it is the one way this refactor-only phase could regress a security
control's test coverage without any production code changing. No ASVS category table is included
since no new input validation, authentication, session management, access control, or
cryptography code is being written in this phase.

## Sources

### Primary (HIGH confidence — every file read directly this session)
- `AbstractAppE2ETest.java`, `AbstractAppTest.java`, `AbstractPostgresContainerTest.java`,
  `activitylog/AbstractKafkaContainerTest.java`, `support/RecordingActivityEventListener.java` —
  full reads, basis for the Fixture Relocation Plan
- All 22 concrete `*E2ETest.java` files — full reads, basis for the tier-downgrade verdict table
- `controller/BoardControllerTest.java`, `controller/ColumnControllerTest.java`,
  `controller/SubtaskControllerTest.java`, `controller/TaskControllerTest.java`,
  `security/AuthenticationControllerTest.java` — full reads, basis for the MockMvc-pattern
  reference and the auth/session merge candidate
- `docs/CODE_STYLE.md`, `.planning/phases/07-.../07-CONTEXT.md`,
  `.planning/todos/pending/2026-08-08-restructure-test-folder-....md`, `.planning/STATE.md`,
  `.planning/REQUIREMENTS.md`, `.planning/config.json` — full reads, project-constraint basis
- `grep`/`find` output against the live `src/test/java/com/vrudenko/kanban_board` tree (multiple
  commands, this session) — basis for the full file inventory (48 files), the import blast-radius
  tables, and the 22-vs-23 discrepancy in Open Questions

### Secondary (MEDIUM confidence)
None — no web/documentation lookups were needed for this phase; every claim traces to a file this
session actually read or a command this session actually ran against the real repository.

### Tertiary (LOW confidence)
- A1, A2 in the Assumptions Log — both are training-knowledge claims about Spring/MockMvc
  behavior not independently verified against official Spring documentation in this session.

## Metadata

**Confidence breakdown:**
- Fixture relocation plan (D-01): HIGH — every file read in full, every `extends`/import
  relationship grepped directly against the live tree, not estimated
- `@Nested` merge candidates (D-02): HIGH for Candidate 1 (fully justified by direct evidence,
  including a genuine pre-existing code-duplication defect found while reading), MEDIUM for
  Candidates 2-4 (correct in principle, but their execution is explicitly gated on D-03 landing
  first, and their "clear readability win" bar is inherently a judgment call)
- E2ETest tier-downgrade verdicts (D-03): HIGH for the 9 KEEP verdicts (8 are a mechanical
  `extends AbstractKafkaContainerTest` check; the 9th has a documented, precedent-backed
  justification), MEDIUM for the 13 DOWNGRADE verdicts (correct based on direct reading, but 2 of
  the 13 carry an assumption — A2 — about MockMvc's Spring Security autoconfiguration that this
  session did not independently verify against official docs)

**Research date:** 2026-08-09
**Valid until:** effectively indefinite for the fixture-relocation and merge-candidate findings
(they describe the current, static file tree); the tier-downgrade verdicts should be re-checked
if any of the 22 files gain new assertions before this phase executes, since a verdict is
per-assertion, not per-filename.
