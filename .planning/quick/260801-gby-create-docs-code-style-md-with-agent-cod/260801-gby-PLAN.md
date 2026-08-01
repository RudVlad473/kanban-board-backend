---
phase: quick/260801-gby
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - docs/CODE_STYLE.md
  - .claude/CLAUDE.md
autonomous: true
requirements: [QUICK-260801-gby]

estimate:
  tokens: 18000
  raw_tokens: 18000
  tasks: 2
  confidence: low

must_haves:
  truths:
    - "An agent reading .claude/CLAUDE.md is pointed to docs/CODE_STYLE.md as the source of code-style rules."
    - "docs/CODE_STYLE.md states the enums-over-magic-constants rule with a rationale and a bad-vs-good Java example."
    - "A future rule can be appended to docs/CODE_STYLE.md by adding one new section, with no restructuring."
    - "CLAUDE.md's existing heading structure and section order are unchanged apart from one added line."
  artifacts:
    - "docs/CODE_STYLE.md"
    - ".claude/CLAUDE.md (## Code Style section, one added reference line)"
  key_links:
    - "CLAUDE.md '## Code Style' bullet list -> relative path docs/CODE_STYLE.md (must resolve from repo root)"
    - "docs/CODE_STYLE.md rule example -> real codebase symbols (org.springframework.http.HttpStatus, GlobalExceptionHandler, com.vrudenko.kanban_board)"
---

<objective>
Create a persistent, append-only code-style guide at `docs/CODE_STYLE.md` seeded with exactly one rule (prefer enums over magic int/String constants), and add a single pointer line to it from the `## Code Style` section of `.claude/CLAUDE.md`.

Purpose: give the developer one durable file to accumulate agent-facing style preferences over time, discoverable by every future Claude Code session via CLAUDE.md, without bloating CLAUDE.md itself.
Output: `docs/CODE_STYLE.md` (new), `.claude/CLAUDE.md` (one-line edit).
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/PROJECT.md
@.planning/STATE.md
@.claude/CLAUDE.md
@src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java
</context>

<interface_context>
Grounding facts already verified during planning — do not re-derive:

- `docs/` exists at repo root and currently contains only `docs/plans/`. `docs/CODE_STYLE.md` does not exist yet.
- `.claude/CLAUDE.md` line 111 is the heading `## Code Style`. Lines 113-118 are six existing bullets (Google Java Format / AOSP, Spotless plugin, target `src/**/*.java`, features, Spotless enforcement, no separate linter). Line 120 is the next heading, `## Import Organization`. The insertion point is after line 118, still inside the `## Code Style` bullet list.
- `GlobalExceptionHandler.java` already imports `org.springframework.http.HttpStatus` and uses enum constants (`HttpStatus.NOT_FOUND`, `HttpStatus.BAD_REQUEST`, `HttpStatus.CONFLICT`, `HttpStatus.UNAUTHORIZED`, `HttpStatus.INTERNAL_SERVER_ERROR`) throughout — it is the compliant reference, not a violation site.
- `HttpStatusCode` (the non-enum interface, with its `valueOf(int)` factory) is also imported in that file — that is the type to contrast against in the non-compliant example.
- A repo-wide search found no raw numeric status literals in `src/test` (no `statusCode(<int>)` usages). The rule is therefore forward-looking guidance, not a cleanup mandate. Do not claim existing violations in the doc.
- Root package is `com.vrudenko.kanban_board`; formatting is Google Java Format AOSP (4-space indent) enforced by Spotless.
</interface_context>

<tasks>

<task type="tracer">
  <name>Task 1: Create docs/CODE_STYLE.md with the enum rule, end to end</name>
  <files>docs/CODE_STYLE.md</files>
  <action>
Create `docs/CODE_STYLE.md` as a new file. This is the thin end-to-end slice: a complete, readable style guide carrying exactly one finished rule, shaped so rule two is a copy-paste of the section skeleton.

Structure the file in this order:

1. An H1 title naming it the code style guide for this repository.
2. A short intro paragraph (2-3 sentences) stating that this file records code-style preferences that AI coding agents and human contributors must follow when writing Java in this repo, that it is additive, and that it complements — does not replace — the Spotless / Google Java Format AOSP formatting already enforced by the build. Say explicitly that formatting is mechanical and enforced by `./gradlew spotlessCheck`, while this file covers judgement-level choices Spotless cannot check.
3. An H2 heading `## Rules`.
4. The first rule as an H3 section titled for the enum preference and numbered `### 1.` so later rules continue the sequence.

Inside the rule section, use exactly these three labelled parts, in this order, so every future rule matches:
   - A one-or-two-sentence **rule statement**: when a value comes from a fixed, known-at-compile-time set, model it as an enum (a JDK/framework-provided one where it exists, otherwise a project enum under `com.vrudenko.kanban_board`) rather than as bare `int` or `String` literals scattered across call sites. Name HTTP status codes as the canonical case: use `org.springframework.http.HttpStatus`.
   - A line beginning with the bold label **Why** giving the rationale: the compiler enforces the closed set, so a typo or an out-of-range value fails at build time instead of runtime; switch statements can be checked for exhaustiveness; the value carries a self-documenting name at every call site; and the set has one authoritative definition to change instead of N literal sites to grep for.
   - A code example presented as two fenced ```java blocks, the first labelled as the discouraged form and the second as the preferred form. Ground both in this repo: write them as `@ExceptionHandler` methods in the shape of the real `GlobalExceptionHandler`, handling `AppEntityNotFoundException` and returning `ResponseEntity<String>`. The discouraged block returns the status via the non-enum `HttpStatusCode.valueOf(404)` factory (and may show a second call site repeating the bare literal to make the duplication visible). The preferred block returns `HttpStatus.NOT_FOUND` with the `org.springframework.http.HttpStatus` import. Format both blocks as Google Java Format AOSP would: 4-space indent, no tabs.
   - Close the section with one short note that `GlobalExceptionHandler` already follows this rule and is the reference to imitate, and that the rule generalises beyond HTTP status — any closed value set (roles, states, sort directions) should be an enum.

5. After the rule, an H2 heading `## Adding a rule` containing a 2-4 line instruction that new rules are appended as a new `###` section under `## Rules`, numbered with the next integer, and must carry the same three parts (rule statement, **Why** line, bad-vs-good example).

Write in plain declarative prose. Do not invent rules beyond the one specified above — the file ships with one rule by design. Do not reference phases, plans, or GSD artifacts; this is a repo doc with an indefinite lifetime.
  </action>
  <verify>
    <automated>test -f docs/CODE_STYLE.md &amp;&amp; grep -q '^## Rules' docs/CODE_STYLE.md &amp;&amp; grep -q '^### 1\.' docs/CODE_STYLE.md &amp;&amp; grep -q '^## Adding a rule' docs/CODE_STYLE.md &amp;&amp; grep -q 'HttpStatus\.NOT_FOUND' docs/CODE_STYLE.md &amp;&amp; grep -q 'HttpStatusCode\.valueOf' docs/CODE_STYLE.md &amp;&amp; grep -q '\*\*Why\*\*' docs/CODE_STYLE.md &amp;&amp; [ "$(grep -c '^```java' docs/CODE_STYLE.md)" -eq 2 ] &amp;&amp; echo CODE_STYLE_OK</automated>
  </verify>
  <done>`docs/CODE_STYLE.md` exists and contains: an H1 title, an intro, `## Rules`, one `### 1.` rule section with a rule statement, a bolded **Why** line and exactly two fenced `java` blocks (one showing `HttpStatusCode.valueOf`, one showing `HttpStatus.NOT_FOUND`), and an `## Adding a rule` section describing the append convention. The verify command prints `CODE_STYLE_OK`.</done>
</task>

<task type="auto">
  <name>Task 2: Point CLAUDE.md's Code Style section at the guide</name>
  <files>.claude/CLAUDE.md</files>
  <action>
Edit `.claude/CLAUDE.md` to add exactly ONE new bullet to the existing `## Code Style` bullet list (heading at line 111). Append it as the last bullet of that list, immediately after the existing bullet about no separate linting tool being configured, and before the blank line preceding the `## Import Organization` heading.

The new bullet must: link to `docs/CODE_STYLE.md` as a relative markdown link, and instruct that this file is the authoritative source for repository code-style rules and must be consulted before writing or modifying Java code.

Use the Edit tool with a scoped replacement anchored on the trailing bullet of the `## Code Style` list. Do NOT use Write on this file — a whole-file rewrite would destroy the surrounding sections.

Do not restate, summarise, or duplicate the enum rule here. Do not add, remove, reorder, or reword any other line, bullet, or heading in the file. The only diff to `.claude/CLAUDE.md` is one added line.
  </action>
  <verify>
    <automated>grep -q 'docs/CODE_STYLE\.md' .claude/CLAUDE.md &amp;&amp; awk '/^## Code Style$/{f=1;next} /^## /{f=0} f' .claude/CLAUDE.md | grep -q 'docs/CODE_STYLE\.md' &amp;&amp; grep -q '^## Import Organization$' .claude/CLAUDE.md &amp;&amp; [ "$(git diff --numstat -- .claude/CLAUDE.md | awk '{print $1"/"$2}')" = "1/0" ] &amp;&amp; echo CLAUDE_MD_OK</automated>
  </verify>
  <done>`.claude/CLAUDE.md` has one added line and zero deleted lines (`git diff --numstat` reports `1 0`); the added line sits inside the `## Code Style` section and links to `docs/CODE_STYLE.md`; the `## Import Organization` heading and all other sections are intact. The verify command prints `CLAUDE_MD_OK`.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| (none introduced) | Documentation-only change. No new input parsing, no network surface, no auth path, no dependency added, no executable code shipped. |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-quick-01 | Tampering | `.claude/CLAUDE.md` | low | mitigate | An over-broad edit could silently drop project guardrails (security conventions, ownership rules) that steer every future agent session. Task 2 forbids `Write` on this file and gates on `git diff --numstat` equalling exactly `1 0`, so any collateral deletion fails verification. |
| T-quick-02 | Information disclosure | `docs/CODE_STYLE.md` | low | accept | The doc contains only public framework names and illustrative snippets already present in the repository; no credentials, endpoints, or internal infrastructure detail. |
</threat_model>

<verification>
1. `test -f docs/CODE_STYLE.md` — the guide exists.
2. `git diff --numstat -- .claude/CLAUDE.md` reports `1 0` — exactly one line added, none removed.
3. `git status --porcelain` shows only `docs/CODE_STYLE.md` (untracked) and `.claude/CLAUDE.md` (modified). No Java source or test file is touched.
4. Both task `<automated>` commands pass, printing `CODE_STYLE_OK` and `CLAUDE_MD_OK`.

No build or test run is required — this change touches no file under `src/`, so `./gradlew spotlessCheck` and `./gradlew test` outcomes are unaffected.
</verification>

<success_criteria>
- `docs/CODE_STYLE.md` exists with one complete rule (statement + **Why** + bad-vs-good Java example) and a documented append convention for future rules.
- The rule's example uses real symbols from this codebase (`HttpStatus`, `HttpStatusCode`, `GlobalExceptionHandler`, `com.vrudenko.kanban_board`) rather than generic placeholders.
- `.claude/CLAUDE.md` gained exactly one line, inside `## Code Style`, linking to `docs/CODE_STYLE.md`; its heading structure is otherwise byte-identical.
- The enum rule text is not duplicated into CLAUDE.md.
- No files under `src/` were modified.
</success_criteria>

<output>
Create `.planning/quick/260801-gby-create-docs-code-style-md-with-agent-cod/260801-gby-SUMMARY.md` when done.
</output>
