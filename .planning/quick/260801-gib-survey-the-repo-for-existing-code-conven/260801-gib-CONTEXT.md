# Quick Task 260801-gib: Survey the repo for existing code conventions/patterns worth codifying in docs/CODE_STYLE.md - Context

**Gathered:** 2026-08-01
**Status:** Ready for planning

<domain>
## Task Boundary

Following research (260801-gib-RESEARCH.md), append new rules to docs/CODE_STYLE.md for the specific candidates the user selected, and fix an existing-rule violation found during research (raw HTTP status ints in E2E tests, which contradicts CODE_STYLE.md Rule 1).

</domain>

<decisions>
## Implementation Decisions

### Which candidates to codify
User selected exactly: C3, C1, C4, C2, C6, C10 (in the research doc's numbering). Do NOT add C5, C7, C8, C9, or any of the "lower-value candidates" (L1-L5) or "not recommended" items — those were explicitly not selected.

### C3 — Ownership-verified loading only
Codify both halves noted in research: (a) domain services (Board/Column/Task/Subtask) always resolve entities through the ownership-verified loader before reading or mutating, never a direct `repository.findById`; (b) once verified, downstream repository calls use the verified entity's id (`pair.getSecond().getId()`), never the raw path-variable/parameter id.

### C1 — AssertJ fully qualified + Assertions.catchException
Always `Assertions.assertThat(...)` (no static import), and always `Assertions.catchException(...)` to assert thrown exceptions — never JUnit's `assertThrows`.

### C4 — No mocks in tests
Every test class is a `@SpringBootTest` extending `AbstractAppTest`, exercising real Spring wiring against H2. No Mockito, no `@MockBean`, no slice tests (`@WebMvcTest`/`@DataJpaTest`). New shared fixtures belong in `AbstractAppTest`, not inlined per test class.

### C2 — @Nested test structure + naming + AAA comments
Group test methods for one method-under-test inside a `@Nested` class named after that method (e.g. `FindAllByColumnIdTest`); name test methods `should<Outcome>_when<Condition>` (service tests) — note the research found controller tests use a `testWithAuthenticatedUser_should<Outcome>_when<Condition>` variant; keep both dialects as documented sub-patterns rather than forcing normalization. Use `// arrange` / `// act` / `// assert` section comments instead of `@DisplayName`.

### C6 — Update*RequestDTO fixed shape
Every `Update*RequestDTO` carries: `@JsonInclude(JsonInclude.Include.NON_NULL)` on the class, a `@NotNull private Long version` field, and — when more than one field is independently optional — a private `@AssertTrue` method named `atLeastOneFieldPopulated()`. `Save*RequestDTO` and `*ResponseDTO` never carry `@JsonInclude` (it specifically marks a DTO as a partial update).

### C10 — Optional unwrapping direction
**Locked decision: keep current house style.** Repository `Optional` results are unwrapped via an `isEmpty()` guard that throws the appropriate `App*Exception`, then `.get()` — never `orElseThrow`. This is a deliberate "keep it as-is" choice (not a rewrite), matching all 11 existing call sites with zero code changes required for this rule.

### E2E HTTP status fix (Rule 1 conflict)
**Locked decision: fix the code, not the rule.** The 10 raw-int HTTP status assertions in `TaskLockingE2ETest.java` (lines 63, 78, 92, 122, 150) and `ColumnLockingE2ETest.java` (lines 54, 69, 83, 112, 139) — e.g. `isEqualTo(409)` — must be changed to use `HttpStatus.CONFLICT.value()` (or equivalent `org.springframework.http.HttpStatus` enum constant `.value()`) so they comply with existing CODE_STYLE.md Rule 1. This is a source-code test fix, not a docs-only change — confirm `./gradlew test` still passes after the edit.

</decisions>

<specifics>
## Specific Ideas

Follow the existing docs/CODE_STYLE.md structure and "Adding a rule" convention set up in the first quick task: one `###` section per rule, each with a rule statement, a **Why** rationale line, and a bad/good Java code example grounded in this codebase's real files (cite real classes, not invented ones) — consistent with Rule 1's style.

Number the new rules sequentially continuing from Rule 1 (i.e. Rules 2-7, in this order: ownership-verified loading (C3), AssertJ/catchException (C1), no-mocks testing (C4), @Nested/AAA test structure (C2), Update*RequestDTO shape (C6), Optional unwrapping via isEmpty()-guard (C10)).

</specifics>

<canonical_refs>
## Canonical References

- docs/CODE_STYLE.md (existing file, Rule 1 + "Adding a rule" convention to follow)
- .planning/quick/260801-gib-survey-the-repo-for-existing-code-conven/260801-gib-RESEARCH.md (full evidence, file:line citations for every candidate, and the E2E incidental finding)

</canonical_refs>
