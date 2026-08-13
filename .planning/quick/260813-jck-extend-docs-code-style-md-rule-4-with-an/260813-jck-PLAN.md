---
phase: quick-260813-jck
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - docs/CODE_STYLE.md
  - .planning/STATE.md
autonomous: true
requirements:
  - QUICK-260813-JCK-DTOTIERRULE

estimate:
  tokens: 18000
  raw_tokens: 18000
  tasks: 1
  confidence: low

must_haves:
  truths:
    - "docs/CODE_STYLE.md rule 4 carries a fourth bolded paragraph naming the `dto/*Test.java` tier, positioned between the existing purpose paragraph and the base-class paragraph (D-01)"
    - "The new paragraph states that dto-tier tests build a plain `jakarta.validation.Validator` and are neither `@SpringBootTest` nor extenders of any `support/fixtures/` base — no Spring context, no Testcontainers (D-02)"
    - "The new paragraph gives the split rule: the DTO tier owns the full boundary matrix for one field/annotation; the controller tier keeps at most one or two representatives proving malformed-body-to-400-with-the-right-envelope (D-03)"
    - "The new paragraph cites `SaveSubtaskRequestDTO.title`'s `@NotBlank` + `@SubtaskTitle` pairing as the worked example, naming quick tasks 260813-h2f and 260813-i6r and both endpoints of the move (4 controller tests added, 3 relocated, 1 kept) (D-03)"
    - "The new paragraph records the message-collision caution: assert on constraint annotation type, not message text, when more than one constraint can fire on the same input (D-04)"
    - "Rule 4's base-class paragraph no longer asserts that *every* test class is a `@SpringBootTest` extending one of three `support/fixtures/` bases — it is qualified so the new dto-tier paragraph does not contradict it (D-05)"
    - "Rule 13's subpackage enumeration includes `dto/` (D-06)"
    - "`docs/CODE_STYLE.md` still has exactly 13 `### ` rule headings — no rule 14 was added and nothing was renumbered (D-01)"
    - "Zero `.java` files appear in the working-tree diff for this task (D-07)"
  artifacts:
    - "docs/CODE_STYLE.md — rule 4 grows one bolded paragraph plus a bounded qualifier on the base-class paragraph's opening clause; rule 13's subpackage list gains `dto/`. No other rule is touched."
  key_links:
    - "Rule 4's existing base-class paragraph opens by claiming every test class is a `@SpringBootTest` extending one of three `support/fixtures/` bases. The three `dto/` test classes are neither, so documenting the dto tier without D-05's qualifier would make rule 4 contradict itself inside one section — the qualifier is what keeps the addition coherent, not decoration on top of it."
    - "The subpackage list lives in two places: rule 13's prose (edited here, D-06) and `TestPlacementArchTest`'s `.because()` string (deliberately NOT edited — source is out of scope). Safe only because that ArchUnit rule is a root-package prohibition with no allowlist, so its message is advisory prose, not the enforcement surface."
    - "Every existing cross-reference to rule 4 across the repo names it by content (`rule 4's purpose test`, `rule 4's base-class test`, `rule 4's no-mocks constraint`) — never by paragraph count or ordinal position — so inserting a paragraph invalidates none of them. Grep-confirmed during planning; the verify step re-confirms."
---

<objective>
Give `docs/CODE_STYLE.md` rule 4 the paragraph it is currently missing: which tier a Bean Validation
boundary case belongs at — the `dto/*Test.java` validator tier, or a single representative controller
test.

Rule 4 already answers "which package by purpose" (service / controller / e2e) and "which base class
by tier". It says nothing about the `dto/` test package, even though three classes live there
(`SubtaskTitleMessageTest`, `SignupRequestDTOTest`, `OptionalNotBlankTest`) and quick task 260813-i6r
just moved three tests into it *citing rule 4 as its authority* — authority rule 4 does not actually
contain in writing. That task deliberately left `docs/CODE_STYLE.md` byte-identical (its own D-04)
and deferred the doc change to here.

Purpose: close the gap between what rule 4 says and what the codebase (and the last two quick tasks)
already does, so the next agent reaching for a validation boundary matrix does not have to
re-derive the tier split from the test tree.
Output: one new bolded paragraph inside rule 4, one bounded qualifier on rule 4's base-class
paragraph so it stops contradicting the new one, and `dto/` added to rule 13's subpackage
enumeration. No source changes.
</objective>

## Approach & Trade-offs

**Decisions locked for this task** (derived from the task description's constraints plus facts
confirmed by reading during planning):

- **D-01** — The content lands as a **new bolded paragraph inside rule 4**, positioned *after* the
  "Which package a new test belongs in (by purpose...)" paragraph and *before* the "Which base class
  to extend" paragraph. Not a new rule 14. Rationale: this is a purpose/tier-selection question,
  which is exactly what rule 4's first paragraph already is; the dto tier is a fourth answer to the
  same question, so it reads as a continuation and not an addendum. Rule count stays at 13.
- **D-02** — The paragraph must state the dto tier's actual mechanics, confirmed by reading all
  three files: a `jakarta.validation.Validator` built in `@BeforeEach` from
  `Validation.buildDefaultValidatorFactory()`, with **no** `@SpringBootTest`, **no**
  `support/fixtures/` base class, and **no** container. This is the cheapest tier in the suite and
  the reason the split is worth making at all.
- **D-03** — The split rule and its worked example: the DTO tier owns the *full* boundary matrix for
  one field/annotation (null, blank, whitespace-only, too-short, too-long, message-collision); the
  controller tier keeps at most one or two representatives proving "malformed body -> 400 with the
  right envelope". Worked example is `SaveSubtaskRequestDTO.title`'s `@NotBlank` + `@SubtaskTitle`
  pairing: 260813-h2f added the annotation with four controller-level boundary tests; a suite-wide
  triage in 260813-i6r found it was the only place in the codebase over-testing at the controller
  tier, moved three cases into `SubtaskTitleMessageTest.SaveSubtaskRequestDTOTest`, and kept exactly
  one (`whenJsonBodyIsEmpty`, the `{}` case) at the controller.
- **D-04** — The paragraph carries the message-collision caution as a *caution*, not a footnote:
  `@SubtaskTitle`'s `@ReportAsSingleViolation` default message is byte-identical to `@NotBlank`'s on
  the same field, so a DTO-tier test asserting on message text alone cannot tell which constraint
  fired. 260813-i6r's fix is the pattern to record — assert on the set of triggered constraint
  annotation types for the empty-string case (which trips both), and keep message-text assertions
  for null / whitespace-only (which trip only `@NotBlank`, and only stay unambiguous because an
  exact `hasSize(1)` pins that exactly one constraint fired).
- **D-05** — Rule 4's base-class paragraph opens "every test class is a `@SpringBootTest` extending
  one of three bases under `support/fixtures/`". That is already false (`CorsConfigTest` and
  `KanbanBoardApplicationTests` extend `AbstractPostgresContainerTest` directly) and the new
  paragraph makes it visibly false. Apply a **bounded qualifier to that opening clause only** — scope
  it to tests that need a Spring context. Do not rewrite, reorder, or re-example that paragraph.
- **D-06** — Add `dto/` to rule 13's subpackage enumeration, which currently omits it. Same file,
  directly caused by this change, and leaving it would put rule 4 and rule 13 in contradiction.
- **D-07** — No source changes. `TestPlacementArchTest`'s `.because()` string also omits `dto/`;
  it is a test source file and stays untouched. Surface it as a follow-up in the SUMMARY.

**Alternates considered:**

| Approach | Pros / Cons | Why picked / rejected |
|---|---|---|
| **A. New bolded paragraph inside rule 4, after the purpose paragraph** (chosen) | + The dto tier is a fourth answer to the question rule 4's first paragraph already asks, so it reads as continuation, not bolt-on. + Every existing "see rule 4" cross-reference keeps pointing at one place that now answers the whole tier question. − Rule 4 becomes the longest rule in the file by some margin. | Picked. The length cost is real but is the lesser evil: splitting tier selection across two rule numbers is exactly the drift that made this gap invisible for two quick tasks running. |
| **B. New standalone rule 14, "Bean Validation boundary matrices belong at the DTO tier"** | + Keeps rule 4 from growing further; gets its own greppable number for future citations. − Tier selection would then live in rules 4, 13 *and* 14, and rule 13 already has to cross-reference rule 4 twice to stay coherent — a third node makes that worse. − A reader answering "where does this test go" would have to know to read three rules. | Rejected. The file's own "Adding a rule" section permits it, but fragmenting one decision across three numbers is the failure mode this task exists to fix. |
| **C. Append the sentences to the existing purpose paragraph inline** | + Smallest possible diff; no new bolded lead-in. − That paragraph is already dense and ends with an explicit "this rule governs package/purpose selection and is independent of the base-class rule immediately below" handoff sentence; appending past that handoff breaks its closing structure. | Rejected. Would damage the paragraph's existing shape to save one bolded line. |
| **D. Attach it to rule 12 (`@OptionalNotBlank`) as the validation-flavoured rule** | + Rule 12 is the other place Bean Validation is discussed. − Rule 12 is about *which annotation to put on a production field*; this is about *where a test goes*. Different question, different audience, and it would leave rule 4 still silent on the dto tier. | Rejected — miscategorised. |

**Non-obvious trade-offs:**

- *The internal contradiction is the real hazard, not the addition.* Rule 4's base-class paragraph
  currently generalises to "every test class". Documenting a tier that runs no Spring context at all,
  four lines above that claim, turns a stale generalisation into a visible self-contradiction inside
  one rule. D-05's qualifier is therefore load-bearing, not polish — without it this task makes the
  document *less* trustworthy than before, which is the opposite of the point.
- *Two copies of the subpackage list, only one edited.* `dto/` is missing from both rule 13's prose
  and `TestPlacementArchTest`'s `.because()` message. This task fixes the prose (D-06) and leaves the
  source string (D-07), so they drift. That is acceptable only because the ArchUnit rule is a
  *root-package prohibition with no allowlist* — it fails a class in the root package and nothing
  else, so the enumeration in its message is advisory text and no test behaviour depends on it. If
  that rule ever gains an allowlist, the drift stops being cosmetic. Flag it, do not fix it here.
- *The caution guards against a green worthless test, which is the failure mode worth documenting.*
  A DTO-tier test that asserts message text where two constraints render identical messages passes
  while observing the wrong constraint. That is strictly worse than no test, because it also
  suppresses the instinct to write a real one — which is why D-04 belongs in the rule and not in a
  commit message.
- *`spotlessCheck` is not a gate here.* Spotless targets `src/**/*.java`; markdown is outside it. Do
  not run the build expecting it to validate this change, and do not treat a green build as evidence
  the paragraph is correct. The verify block below is the only real gate.
- *Performance / memory:* not applicable — one markdown file, no runtime artefact.
- *Supply chain:* no package-manager installs, no dependency changes, no `build.gradle` touch.

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| (none crossed) | Docs-only change to a contributor-facing style guide. No request path, no data path, no build input, no runtime artefact is modified. |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-jck-01 | Tampering (integrity of guidance) | `docs/CODE_STYLE.md` rule 4 | low | mitigate | A rule that mis-states its own enforcement mechanism causes agents to write tests at the wrong tier or to trust a non-existent guard. Mitigated by D-02/D-05 (mechanics stated from files actually read, not assumed) and by the verify block's read-back of the whole rule. |
| T-jck-02 | Repudiation (untraceable convention) | rule 13 vs `TestPlacementArchTest` | low | accept | The subpackage list drifts between prose and the ArchUnit `.because()` string. Accepted for this task: the ArchUnit rule has no allowlist, so no behaviour depends on the string. Recorded as a SUMMARY follow-up rather than silently absorbed. |
| T-jck-SC | Tampering (supply chain) | n/a | n/a | n/a | No npm/pip/cargo installs in this task — no package-legitimacy gate applies. |
</threat_model>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@docs/CODE_STYLE.md
@src/test/java/com/vrudenko/kanban_board/dto/SubtaskTitleMessageTest.java
@src/test/java/com/vrudenko/kanban_board/dto/SignupRequestDTOTest.java
@src/test/java/com/vrudenko/kanban_board/dto/OptionalNotBlankTest.java
@src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java

Facts already established by reading during planning, so the executor does not need to re-derive
them (re-read only to match prose voice, not to rediscover mechanics):

- **Rule 4's current shape**, in order: bolded paragraph 1 "Which package a new test belongs in (by
  purpose, decided before which base class to extend):"; bolded paragraph 2 "Which base class to
  extend, within `support/fixtures/`:"; bolded paragraph 3 "Pre-commit gate membership is by `@Tag`,
  not by class name."; then `**Why:**`, then Discouraged / Preferred Java blocks, then a closing
  `AbstractAppTest` reference line.
- **Rule 4's prose voice**: dense multi-clause sentences, backticked identifiers, em-dashes,
  `Worked example:` / `Worked examples:` lead-ins naming a fully-qualified test method, and a closing
  sentence that hands off to a neighbouring rule. Paragraph 1 ends with exactly such a handoff.
- **All three `dto/` test classes** build `Validation.buildDefaultValidatorFactory().getValidator()`
  into a `private Validator validator` field in `@BeforeEach`. None is annotated `@SpringBootTest`.
  None extends any base class. `SubtaskTitleMessageTest` is package-private; the other two are
  `public`. They use `@Nested` classes named after the DTO under test (e.g.
  `SaveSubtaskRequestDTOTest`, `UpdateSubtaskRequestDTOTest`) rather than after a method.
- **The worked example's numbers**: 260813-h2f added `@NotBlank(message = "Subtask title cannot be
  empty")` to `SaveSubtaskRequestDTO.title` alongside the existing `@SubtaskTitle`, proving it with
  four new `TaskControllerTest.AddSubtaskByTaskId` tests. 260813-i6r moved three of those four into
  `SubtaskTitleMessageTest.SaveSubtaskRequestDTOTest` and kept
  `testWithAuthenticatedUser_shouldReturnBadRequest_whenJsonBodyIsEmpty` (the `{}` case) as the sole
  controller-tier representative. Full suite went 444 -> 444, net zero.
- **The message collision, precisely**: `@SubtaskTitle` is `@ReportAsSingleViolation` composing
  `@Size(min = 3, max = 32)`, and its own `message()` default is the string `@NotBlank` also uses on
  that field. Null and whitespace-only-of-length-3 trip *only* `@NotBlank` (`@Size` passes on null
  and on length 3), so those two assert `hasSize(1)` plus message equality. Empty string trips
  *both*, so that case asserts on the set of constraint annotation simple names
  (`NotBlank` + `SubtaskTitle`) instead.
- **`TaskControllerTest` already carries a pointer comment** at lines ~394-396 telling the reader the
  rest of the matrix lives at the DTO tier "per docs/CODE_STYLE.md rule 4". That comment is currently
  pointing at a rule that does not say so. This task is what makes it true. Do not edit that comment.
- **Cross-reference audit (already run, repo-wide)**: every citation of rule 4 in `src/` and `docs/`
  refers to it by content — `rule 4's purpose test`, `rule 4's base-class test`, `rule 4's no-mocks
  constraint`, `countQueries` sanction, MockMvc context-path note — and never by paragraph count or
  ordinal. `docs/LOCAL_DEV.md`'s anchored links are all to rule 8. `README.md` and
  `docs/ARCHITECTURE.md` link the file generically. There is no table of contents of rules anywhere.
  Inserting a paragraph therefore breaks no existing reference.
- **Rule 13's enumeration** currently reads `service/`, `controller/`, `e2e/{activity,board,column,subtask,task}/`,
  `activitylog/`, `config/`, `security/`, `handler/`, `architecture/`, `support/{containers,fixtures,listeners}/`
  — `dto/` is absent.
- **`TestPlacementArchTest`** enforces only "no test class directly in the root package, except
  `KanbanBoardApplicationTests`". It has no subpackage allowlist, so `dto/` is already mechanically
  fine and no source change is needed to legalise it.
</context>

<tasks>

<task type="auto">
  <name>Task 1: Add rule 4's DTO-tier paragraph, qualify its base-class opener, list `dto/` in rule 13</name>
  <files>docs/CODE_STYLE.md</files>
  <read_first>
    Re-read rule 4 (all three bolded paragraphs) and rule 13 in `docs/CODE_STYLE.md` before writing,
    for voice only — the mechanics are already recorded in this plan's `<context>` block. Match the
    existing register exactly: dense sentences, backticked identifiers, em-dashes, a `Worked example:`
    lead-in, a closing handoff sentence. Do not introduce bullet lists, headings, or code fences
    inside the new paragraph; no existing rule-4 paragraph uses them.
  </read_first>
  <action>
    Make three scoped edits to `docs/CODE_STYLE.md`. Use `Edit`, never `Write` — this is an existing
    file and a whole-file rewrite would risk collateral change to the other twelve rules.

    Edit 1 (D-01, D-02, D-03, D-04) — insert one new bolded paragraph into rule 4, between the
    paragraph that begins "**Which package a new test belongs in" and the paragraph that begins
    "**Which base class to extend". Lead it with the bolded, colon-terminated phrase
    `**Which tier a Bean Validation boundary case belongs at — `dto/*Test.java` vs. one representative
    controller test:**` and cover these beats in this order, as continuous prose:

    - The `dto/` package holds validator-tier tests that exercise Bean Validation constraints
      directly against a DTO instance. Name the mechanics per D-02: a `jakarta.validation.Validator`
      obtained from `Validation.buildDefaultValidatorFactory()` in `@BeforeEach`, no `@SpringBootTest`,
      no `support/fixtures/` base class, no container — the cheapest tier in the suite, which is what
      makes an exhaustive matrix affordable there.
    - The split rule per D-03: one field-plus-annotation's full boundary matrix (null, blank,
      whitespace-only, below minimum length, above maximum length, and cases where two constraints
      collide) belongs at this tier; the controller tier keeps at most one or two representatives
      proving that a malformed body produces 400 with the right envelope, and does not re-enumerate
      the matrix behind an HTTP round trip.
    - The worked example per D-03, with a `Worked example:` lead-in and concrete numbers: quick task
      260813-h2f added `@NotBlank` alongside the existing `@SubtaskTitle` on
      `SaveSubtaskRequestDTO.title` and proved it with four `TaskControllerTest.AddSubtaskByTaskId`
      tests; a suite-wide triage in 260813-i6r found this was the only controller-tier
      over-enumeration in the codebase, relocated three cases to
      `SubtaskTitleMessageTest.SaveSubtaskRequestDTOTest`, and kept
      `testWithAuthenticatedUser_shouldReturnBadRequest_whenJsonBodyIsEmpty` as the single
      controller-tier representative.
    - The caution per D-04, framed as a trap this tier specifically invites: because `@SubtaskTitle`
      carries `@ReportAsSingleViolation`, its rendered message is byte-identical to `@NotBlank`'s on
      the same field, so a DTO-tier test asserting only on message text cannot establish which
      constraint fired. State the resolution concretely — an input that trips more than one
      constraint is asserted on the set of triggered constraint annotation types, while inputs that
      trip exactly one may assert message text, and only because an exact violation-count assertion
      pins that fact.
    - A closing handoff sentence in the shape paragraph 1 already uses, pointing at the base-class
      paragraph immediately below and noting that this tier is the one case that answers it with
      "none".

    Edit 2 (D-05) — qualify the opening clause of rule 4's base-class paragraph so it no longer claims
    that *every* test class is a `@SpringBootTest` extending one of the three `support/fixtures/`
    bases. Scope the claim to test classes that need a Spring context, and keep the qualifier to the
    opening clause. Do not touch that paragraph's `AbstractPostgresContainerTest` sentence, its
    `AbstractAppMockMvcTest` context-path sentence, its Mockito prohibition, its shared-fixtures
    sentence, or its `countQueries` sentence.

    Edit 3 (D-06) — add `dto/` to rule 13's subpackage enumeration, in the existing backticked,
    comma-separated list. Insert it in a position consistent with the list's current ordering. Change
    nothing else in rule 13.

    Do not add a rule 14. Do not renumber any heading. Do not touch
    `TestPlacementArchTest.java` or any other `.java` file — its `.because()` string also omits
    `dto/`, and that is recorded as a deliberate out-of-scope follow-up (D-07), to be surfaced in the
    SUMMARY rather than fixed here.
  </action>
  <verify>
    <automated>cd "$(git rev-parse --show-toplevel)" && test "$(grep -c '^### ' docs/CODE_STYLE.md)" = "13" && test "$(git status --porcelain | grep -cE '\.java$')" = "0" && test "$(grep -c 'Which tier a Bean Validation boundary case belongs at' docs/CODE_STYLE.md)" = "1" && grep -q '260813-i6r' docs/CODE_STYLE.md && grep -q 'ReportAsSingleViolation' docs/CODE_STYLE.md && grep -q 'buildDefaultValidatorFactory' docs/CODE_STYLE.md && test "$(grep -c 'dto/' docs/CODE_STYLE.md)" -ge "2" && git status --porcelain | grep -q 'docs/CODE_STYLE.md' && echo VERIFY_OK</automated>

    Smoke-tested against the pre-edit tree during planning: heading count already reads 13 (so the
    gate proves "no rule 14 added", not a tautology), dirty-`.java` count already reads 0, and the
    lead-in and two-or-more-`dto/` gates both read red — they go green only if the paragraph and the
    rule-13 entry actually land.
    <human-check>
      Read rule 4 end to end. It must read as one rule that answers tier selection completely — the
      new paragraph continuing paragraph 1's question rather than interrupting it, and paragraph 2's
      qualified opener no longer contradicting it. If the new paragraph reads as an addendum bolted
      onto a finished rule, rewrite it rather than shipping it.
    </human-check>
  </verify>
  <done>
    Rule 4 contains four bolded paragraphs, the new one third-from-last and covering all four D-02
    through D-04 beats; rule 4's base-class opener is scoped to Spring-context tests; rule 13's
    enumeration lists `dto/`; the file still has 13 `### ` headings; the working-tree diff contains
    `docs/CODE_STYLE.md` and no `.java` file.
  </done>
</task>

</tasks>

<verification>
Beyond the task's own gates:

1. `git diff -- docs/CODE_STYLE.md` — confirm the diff touches only rule 4 and rule 13. Any hunk
   landing in rules 1-3, 5-12 or the "Adding a rule" section is collateral damage and must be
   reverted.
2. Confirm no `.md` file outside `docs/CODE_STYLE.md` and `.planning/` changed.
3. Re-run the cross-reference audit to confirm nothing went stale:
   `grep -rn 'rule 4' --include=*.java --include=*.md . | grep -v '^./.planning'` — every hit must
   still cite rule 4 by content (purpose test / base-class test / no-mocks / countQueries /
   context-path), and none by paragraph count or ordinal position.

Note on the heading-count gate: `^### ` is the rule-heading marker in this markdown file, so counting
it is the intended signal rather than a comment-counting artefact — there are no shell-style comment
lines in `docs/CODE_STYLE.md` to filter out.

`./gradlew spotlessCheck` and `./gradlew test` are deliberately **not** gates for this task: Spotless
targets `src/**/*.java` only, and no source file is modified. Running the suite here would prove
nothing about the change and cost several minutes.
</verification>

<success_criteria>
- Rule 4 answers the dto-tier question in writing, so `TaskControllerTest`'s existing "per
  docs/CODE_STYLE.md rule 4" pointer comment and 260813-i6r's cited authority are both now true.
- Rule 4 does not contradict itself: the base-class paragraph's generalisation is scoped.
- Rule 13's subpackage list is complete.
- Exactly one file changed outside `.planning/`; zero source files changed.
- The `TestPlacementArchTest.because()` `dto/` omission is recorded as a follow-up in the SUMMARY,
  not silently absorbed.
</success_criteria>

<output>
Create `.planning/quick/260813-jck-extend-docs-code-style-md-rule-4-with-an/260813-jck-SUMMARY.md` when done.
</output>
