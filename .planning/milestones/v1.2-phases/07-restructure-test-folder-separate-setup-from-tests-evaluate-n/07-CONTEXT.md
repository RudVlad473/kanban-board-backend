# Phase 7: Restructure test folder - Context

**Gathered:** 2026-08-09
**Status:** Ready for planning

<domain>
## Phase Boundary

Reorganize `src/test/java/com/vrudenko/kanban_board/` — the fixture/setup infrastructure
(`AbstractAppTest`, `AbstractAppE2ETest`, `AbstractPostgresContainerTest`,
`AbstractKafkaContainerTest`, `RecordingActivityEventListener`) currently sits interspersed with
44 concrete test classes at inconsistent nesting depth (some flat, some in `e2e/{domain}/`, some
in domain-named packages like `service/`, `controller/`, `activitylog/`). This phase evaluates
and — for anything that survives evaluation — executes three changes: (1) relocate all shared
fixture/setup classes into a single `support/` package split into subfolders by concern, (2)
propose and apply genuine `@Nested` merge candidates among closely related test classes, and (3)
determine (via research) which of the 23 `E2ETest`-suffixed classes can safely drop to the
cheaper in-process `@SpringBootTest` tier, then apply that downgrade. Does not cover anything
outside `src/test/java/` — no production code changes, no new test coverage, no change to the
no-mocks/real-DB-everywhere testing philosophy (`docs/CODE_STYLE.md`).

</domain>

<decisions>
## Implementation Decisions

### Fixture/setup package layout
- **D-01:** All shared test infrastructure moves into one `support/` package, subdivided into
  subfolders by concern (e.g. containers/base classes in one subfolder, event-recording
  listeners in another) rather than staying flat or splitting into multiple top-level packages.
  User's explicit call: combine "one location" with "categorized within it." Exact subfolder
  names/boundaries are Claude's discretion (see below). — **Reversibility:** reversible — package
  moves are a mechanical, compiler-checked refactor with no runtime behavior change.

### @Nested merge candidates
- **D-02:** Researcher/planner actively scan for genuinely closely-related test classes (same
  entity/service tested from different angles) and propose specific merge candidates with
  justification. Executor merges only the candidates with a clear readability win — this is not
  evaluation-only; approved merges get applied in this phase.

### E2ETest suffix / tier downgrade criteria
- **D-03:** The exact downgrade rule is **not locked** — user was explicitly unsure whether the
  codebase has any "no real socket needed" E2ETest classes and didn't want to guess blind.
  Confirmed during discussion: the app already has both tiers in practice today — 4
  `controller/*ControllerTest` classes use in-process `MockMvc`, while 16 files reference
  RestAssured/RANDOM_PORT (real HTTP socket); the todo counts 23 `E2ETest`-suffixed classes
  overall. **Research must determine the actual rule** by inspecting what each of the 23 classes
  asserts (real-socket-specific behavior? Kafka/Schema-Registry dependency?) and propose a
  concrete, applicable-per-class rule during planning — grounded in what's found, not decided
  upfront. The todo's own starting hypothesis ("no real-socket assertion AND no Kafka
  dependency") is a reasonable default to test against, not a locked decision.

### Output form
- **D-04:** This phase executes everything decided, not just recommends. Fixture relocation,
  approved `@Nested` merges, and the tier downgrades that pass the research-derived rule (D-03)
  all happen as real diffs in this phase's plan(s) — no separate "recommendation doc, defer
  execution" step.

### Claude's Discretion
- Exact `support/` subfolder names and boundaries (D-01) — e.g. `support/containers/` for the
  three `Abstract*ContainerTest`/`AbstractAppTest`/`AbstractAppE2ETest` base classes vs.
  `support/listeners/` or similar for `RecordingActivityEventListener` — planner's call, guided
  by what's actually in each class.
- Specific `@Nested` merge candidates (D-02) — planner/researcher identify which of the 44
  concrete test classes are close enough (same entity/service, different angles) to justify
  merging; no candidates are pre-selected here.
- The concrete E2ETest→in-process downgrade list (D-03) — entirely research-driven; no classes
  are pre-selected for downgrade or exemption here.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Source document (this phase's entire origin)
- `.planning/todos/pending/2026-08-08-restructure-test-folder-separate-setup-from-tests-evaluate-n.md`
  — the original todo; frames all three evaluation items and their rationale (including the
  explicit warning against a mechanical mass-move without judgment).

### Project-level state
- `docs/CODE_STYLE.md` — the no-mocks / real-DB-everywhere testing philosophy this phase must not
  violate; also documents `@Nested`/AAA conventions (rule 4 per STATE.md history) that D-02's
  merge candidates must follow.
- `.planning/STATE.md` — Phase 04.2 history: H2 was fully removed and every test (E2ETest-suffixed
  or not) already hits real Testcontainers PostgreSQL — this is *why* the `E2ETest` suffix no
  longer tracks "real vs. fake DB" and D-03's research question exists at all.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `support/RecordingActivityEventListener.java` — the one file already living in `support/`;
  establishes the target package's existing (if minimal) precedent.
- `controller/{Board,Column,Subtask,Task}ControllerTest.java` — the 4 existing classes proving
  the in-process `MockMvc` tier is already a real, working pattern in this codebase, not a
  hypothetical target.

### Established Patterns
- Package structure today mixes three organizing principles at once: flat top-level (most
  `Abstract*`/`*E2ETest` classes), domain-suffix packages (`controller/`, `service/`, `security/`,
  `activitylog/`, `event/`), and one nested-by-feature tree (`e2e/{activity,board,column,task}/`)
  — this inconsistency is the concrete evidence behind the todo's "not clear at a glance"
  complaint.
- 16 files reference RestAssured/`RANDOM_PORT` (real HTTP socket over the wire); 4 files under
  `controller/` use `MockMvc` (in-process, no socket) — both tiers are proven, active patterns in
  this codebase today, confirmed by grep during this discussion.

### Integration Points
- Any class import path changes from package moves (D-01) must be verified compile-clean via
  `./gradlew compileTestJava` — Spotless/import-order enforcement (`build.gradle`) will also need
  to run after any file relocation.

</code_context>

<specifics>
## Specific Ideas

No specific file-level examples were given beyond the todo's own text — the user's contributions
in this discussion were policy-level decisions (D-01 through D-04), not concrete file picks. The
23-file E2ETest-suffix figure and the "51% of 45 test files" statistic both come from the source
todo, not independently re-verified during this discussion beyond confirming the 16
RestAssured/4 MockMvc split above.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within the three items the source todo scoped (fixture layout, @Nested
merges, E2ETest tier downgrades).

### Reviewed Todos (not folded)
- 8 other pending todos matched this phase in `todo.match-phase` scoring (virtual threads, Java
  25 bump, sequence diagram, NullAway, alert-service exploration, deploy-job rewrite, D-02
  BACKWARD rationale gap) — all on generic keyword overlap only (`planning`, `phase`, `separate`,
  `evaluate`, `2026`), not actual topic. None concern test-suite organization. Left pending,
  unchanged.

</deferred>

---

*Phase: 7-Restructure test folder*
*Context gathered: 2026-08-09*
