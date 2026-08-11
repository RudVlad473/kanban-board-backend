---
phase: quick-260811-ffs
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - build.gradle
  - docs/CODE_STYLE.md
  - src/**/*.java
autonomous: true
requirements: [QUICK-260811-ffs]

estimate:
  tokens: 45000
  raw_tokens: 30000
  tasks: 3
  confidence: low

must_haves:
  truths:
    - "`./gradlew spotlessCheck` passes on the fully reformatted tree (proves the pipeline is idempotent, not just that apply ran)"
    - "In every file that has static imports, the LAST import line is a static import (they currently sort FIRST — this inversion is the sharpest observable proof the `\\#` group token parsed correctly)"
    - "In every file that has `java.*` imports, the FIRST import line is a `java.*` import"
    - "In every file that has both, the last `com.vrudenko.*` import precedes the first `org.*` import (first-party group sits ahead of the third-party catch-all)"
    - "Import groups are separated by exactly one blank line in the generated blocks"
    - "The full test suite is still green — reformatting broke no compilation and removed no test"
    - "docs/CODE_STYLE.md carries a rule 10 documenting the grouping and its rationale"
  artifacts:
    - build.gradle (importOrder call carrying the five-group order)
    - docs/CODE_STYLE.md (new `### 10.` rule)
    - 161 reformatted files under src/main and src/test
  key_links:
    - "build.gradle `importOrder(...)` group list -> the actual import blocks Spotless generates in src/**/*.java"
    - "Groovy source literal `'\\\\#'` -> Spotless `ImportSorterImpl.STATIC_SYMBOL`, whose bytecode-confirmed value is the two-character string `\\#`"
    - ".githooks/pre-commit `spotlessCheck` -> the reformatted tree (the hook re-gates the same check at commit time, so a partial reformat cannot be committed)"
---

<objective>
Change Spotless's `importOrder()` in build.gradle from its default (one undifferentiated ASCII-sorted block, static imports first) to an explicit five-group order with blank-line separation, then reformat all 161 Java files to match and document the convention.

Purpose: import blocks currently interleave JDK, first-party and third-party packages into a single wall of text, so a reader cannot see at a glance what a class depends on. Grouping makes provenance visible.
Output: build.gradle config change, 161 reformatted source files, one new rule in docs/CODE_STYLE.md.

Scope: formatting only. No production logic, no dependency, no runtime behavior changes.
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@.claude/CLAUDE.md
@build.gradle
@docs/CODE_STYLE.md
</context>

## Decision (locked — do not re-litigate)

The operator chose this group order, and it is final:

1. `java.*`
2. `javax.*`
3. `com.vrudenko.*` (first-party)
4. everything else third-party (the `''` catch-all: `jakarta.*`, `org.springframework.*`, `io.*`, `com.github.*`, `com.fasterxml.*`, …)
5. static imports last

Each group separated by one blank line.

**One mechanical correction to how it gets written, which does not change the decision.** The order above is implemented verbatim; only the Groovy escaping of the static-group token differs from the shorthand the request was written in. The static group must be written `'\\#'` (two backslashes) in build.gradle, not `'\#'`:

- Spotless expects the literal two-character token `\#`. Verified directly against the bytecode of the exact version this project resolves (`spotless-lib-3.0.2.jar`): `ImportSorterImpl` carries a constant pool entry `STATIC_KEYWORD STATIC_SYMBOL \# SUBGROUP_SEPARATOR |` — the constant's value is backslash-hash.
- Groovy single-quoted strings process escape sequences, so producing the literal `\#` requires `'\\#'`. `'\#'` is not a recognised Groovy escape and will fail the build script at *configuration* time.
- The Spotless Gradle README's own example uses the same doubled form: `importOrder('java|javax', 'com.acme', '', '\\#com.acme', '\\#')`.

**Known-inert group, stated honestly:** there are currently **zero** `javax.*` imports in this codebase — Spring Boot 3 moved everything to `jakarta.*` (61 files). Group 2 is therefore future-proofing and will produce no visible output today. It is retained because it is what was chosen and it costs nothing (Spotless emits no blank line for an empty group).

## Approaches considered

**Approach A (chosen) — explicit `importOrder(...)` group list in build.gradle.**
**Approach B — `importOrderFile('eclipse-import-order.txt')`**, externalising the order into an Eclipse-exported `.importorder` file that Spotless reads.
**Approach C — `googleJavaFormat().reorderImports(true)`**, deleting the separate `importOrder()` step and letting google-java-format own import ordering.

| Approach | Pros / Cons | Why picked / rejected |
|---|---|---|
| **A. Inline `importOrder('java', 'javax', 'com.vrudenko', '', '\\#')`** | **+** The order is visible in the same file as every other build-gate decision, next to `googleJavaFormat`/`removeUnusedImports`, so a reader sees the whole formatting contract in one 8-line block. **+** No new file, no new indirection. **+** Directly expresses the locked decision including blank-line separation. **−** Needs the non-obvious `'\\#'` escaping, which is a genuine footgun (mitigated by Task 1's config tracer). | **Picked.** This repo already keeps every build-gate judgement inline and commented in build.gradle (ErrorProne rationale, `fastTest` filter, hooks bootstrap). A one-line config change matches that precedent exactly, and the codebase is too small for the indirection of B to buy anything. |
| **B. `importOrderFile('eclipse-import-order.txt')`** | **+** Shareable with an IDE — an Eclipse/IntelliJ user could import the same file so their IDE's "Organize Imports" agrees with the build. **−** Adds a second file that must stay in sync with build.gradle's intent, and the ordering decision stops being visible where every other formatting decision lives. **−** Nobody on this project uses Eclipse; the IDE-sync benefit is theoretical. | **Rejected.** Pays a real indirection cost for a benefit (IDE round-tripping) that no one on this project would collect. Reconsider only if an IDE-sync need actually appears. |
| **C. `googleJavaFormat().reorderImports(true)`** | **+** Fewest moving parts — one formatter owns everything, no escaping footgun. **−** **Cannot satisfy the requirement at all**: google-java-format's `ImportOrderer` emits a single ASCII-sorted block with static imports first and *no* custom groups and *no* blank-line separation. That is precisely the current state being replaced. | **Rejected on capability, not preference.** It structurally cannot produce grouped, blank-line-separated output. |

## Non-obvious trade-offs

**Idempotency risk (the real technical risk here, and why Task 2 verifies with `spotlessCheck`, not just `spotlessApply`).** Spotless runs steps in declaration order, and `removeUnusedImports()` is declared *after* `importOrder()`. So imports are grouped and blank-line-separated first, and only then are unused ones deleted. If deletion empties a group, the two blank lines that bracketed it can collapse into a double blank line that `importOrder` would itself have normalised — a non-idempotent pipeline, where `spotlessApply` produces output that `spotlessCheck` then rejects. Running `spotlessCheck` immediately after `spotlessApply` is exactly the detector: `check` re-runs the identical pipeline and byte-compares against what `apply` wrote. If it fails, the fix is to move `removeUnusedImports()` *above* `importOrder()` so grouping is the last word on the import block.

**git blame pollution — the largest real cost of this change.** Rewriting the import block of ~161 files puts this commit on top of `git blame` for every one of those lines. Mitigation: keep the reformat in **one isolated commit that contains nothing else**, so it can be added to a `.git-blame-ignore-revs` file (and `git blame --ignore-rev`) later. This is the main reason Task 3's docs edit should not be smeared into the reformat commit.

**Commit atomicity is forced by the pre-commit hook, not by preference.** `.githooks/pre-commit` runs `./gradlew spotlessCheck` and aborts the commit on failure. A commit containing only Task 1's build.gradle change (config updated, sources not yet reformatted) fails that check by construction. **Task 1 and Task 2 must therefore land as a single commit.** There is no valid intermediate commit.

**Merge-conflict window.** Any in-flight branch touching imports will conflict with a 161-file reformat. Per STATE.md, Phase 07.1 is closed (9/9) and Phase 5 has not started; the working tree is clean apart from untracked `.gsd/`. This is the cheapest window available — doing it mid-phase would be materially worse.

**Performance / memory: none at runtime.** This is a build-time formatter configuration; zero bytes of production behavior change, and import order has no bearing on compiled bytecode. Build cost is a one-time full rewrite of 161 files; subsequent runs are Spotless's usual incremental up-to-date check. Expect the commit itself to take ~4 minutes because the pre-commit hook re-runs `spotlessCheck` plus `fastTest`.

**Security: none.** No dependency added or removed, no production code path touched. `removeUnusedImports()` ran before this change and continues to run unchanged, so no import that was previously stripped survives.

## How the verification actually works (data flow)

`spotlessApply` pipes each `src/**/*.java` file through the four declared steps in order and writes the result back to disk. `spotlessCheck` pushes the same files through the same pipeline but compares the result to the file on disk instead of writing, failing the build on any byte difference. Because `apply` already wrote the pipeline's own output, a subsequent `check` can only fail if the pipeline is not idempotent — which is the specific defect being screened for.

<tasks>

<task type="tracer">
  <name>Task 1: Configure the five-group import order and prove the config parses</name>
  <files>build.gradle</files>
  <action>
In build.gradle's `spotless { java { ... } }` block (around line 30), replace the bare `importOrder()` call with `importOrder('java', 'javax', 'com.vrudenko', '', '\\#')` — five arguments, with two literal backslashes before the hash in the fifth.

Leave the other three steps (`googleJavaFormat().aosp()`, `formatAnnotations()`, `removeUnusedImports()`) and their declaration order untouched.

Add a short comment directly above the call, matching the explanatory-comment style build.gradle already uses for its other build-gate decisions (ErrorProne, fastTest, hooks bootstrap). The comment must record three things: the group order and that each group is blank-line separated; that the doubled backslash is Groovy escaping producing the single-backslash token Spotless matches static imports on; and that the javax group currently matches nothing because Spring Boot 3 uses jakarta, so it is deliberate future-proofing rather than a mistake to be tidied away.

This task is a tracer: it deliberately runs the checker BEFORE any source is reformatted, to separate a config failure from a formatting failure while only one file has changed. `./gradlew spotlessCheck` is EXPECTED to exit non-zero here — that is success, because 161 files genuinely do not yet match the new order. What matters is the FAILURE MODE. A report of format violations means the config parsed and Spotless accepted the group spec. A Groovy script compilation error, or any complaint about the group specification itself, means the escaping is wrong — stop and fix it before touching any source file, because reformatting 161 files under a broken spec is the one expensive mistake available in this plan.
  </action>
  <verify>
    <automated>
grep -n "importOrder" build.gradle
./gradlew spotlessCheck 2>&1 | tail -30
    </automated>
  </verify>
  <done>build.gradle's importOrder call lists the five groups. `./gradlew spotlessCheck` exits non-zero and its output reports Java format violations in src files — NOT a Groovy/script configuration error and NOT a complaint about the import-group spec.</done>
</task>

<task type="auto">
  <name>Task 2: Reformat all 161 files, prove idempotency, prove grouping, prove nothing broke</name>
  <files>src/main/**/*.java (111 files), src/test/**/*.java (50 files) — all rewritten by the formatter, none hand-edited</files>
  <action>
Run `./gradlew spotlessApply` to rewrite every Java file's import block under the new order.

Then run the four verification layers below and report the ACTUAL output of each, including anything that did not work. Do not summarise a command you did not run.

Layer 1 — idempotency: `./gradlew spotlessCheck` must now PASS. If it fails, the pipeline is not idempotent; the cause and fix are described in this plan's Non-obvious trade-offs section (move `removeUnusedImports()` above `importOrder()`, re-run apply, re-check).

Layer 2 — structural correctness across all files: three mechanical greps, each of which must emit zero FAIL lines. These check that static imports moved to LAST (they currently sort first, in 23 files — this inversion is the strongest available evidence the static-group token was parsed rather than silently swallowed into the catch-all group), that `java.*` sorts first, and that first-party `com.vrudenko.*` precedes third-party `org.*`.

Layer 3 — eyeball one exemplar: print the import block of src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java, which exercises four of the five groups (java, com.vrudenko, third-party, static). Confirm the groups appear in the locked order, separated by single blank lines.

Layer 4 — nothing broke: `./gradlew test`. Docker must be running (Testcontainers PostgreSQL backs every test, and the kafka-tagged classes need Redpanda). Expect roughly 5 minutes and ~210+ tests. Report the actual test count and result rather than asserting it passed.

Scope discipline while you are in these files: do not hand-edit any import block, and do not "fix" the pre-existing redundant pair in TaskControllerTest where both `MockMvcRequestBuilders.*` and `MockMvcRequestBuilders.put` are imported. That redundancy predates this change and is out of scope; cleaning it up would put non-formatting content into a commit whose entire value depends on being purely mechanical.

Commit Task 1 and Task 2 together as a single commit — see this plan's Non-obvious trade-offs section for why an intermediate commit is impossible. Expect the pre-commit hook to spend ~4 minutes re-running spotlessCheck and fastTest; that is the hook working, not a hang.
  </action>
  <verify>
    <automated>
./gradlew spotlessCheck

# static imports must now be LAST (they sort first today, in 23 files)
for f in $(grep -rl '^import static' src --include=*.java); do
  last=$(grep '^import ' "$f" | tail -1)
  case "$last" in "import static"*) ;; *) echo "FAIL static-not-last: $f" ;; esac
done

# java.* must be FIRST
for f in $(grep -rl '^import java\.' src --include=*.java); do
  first=$(grep '^import ' "$f" | head -1)
  case "$first" in "import java."*) ;; *) echo "FAIL java-not-first: $f" ;; esac
done

# first-party com.vrudenko.* must precede third-party org.*
for f in $(grep -rl '^import com\.vrudenko' src --include=*.java); do
  grep -q '^import org\.' "$f" || continue
  lastv=$(grep -n '^import com\.vrudenko' "$f" | tail -1 | cut -d: -f1)
  firstorg=$(grep -n '^import org\.' "$f" | head -1 | cut -d: -f1)
  [ "$lastv" -lt "$firstorg" ] || echo "FAIL vrudenko-after-org: $f"
done

sed -n '1,30p' src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java

./gradlew test
    </automated>
  </verify>
  <done>`./gradlew spotlessCheck` passes. All three grep loops emit zero FAIL lines. TaskControllerTest's import block shows java, then com.vrudenko, then third-party, then static, each separated by one blank line. `./gradlew test` is green with the actual test count reported.</done>
</task>

<task type="auto">
  <name>Task 3: Document the import grouping as CODE_STYLE.md rule 10</name>
  <files>docs/CODE_STYLE.md</files>
  <action>
Append a new `### 10.` section under `## Rules`, following the exact three-part shape the file's own "Adding a rule" section mandates and that rules 1-9 all follow: a rule statement, a bolded **Why** line, and a discouraged-vs-preferred code example.

State the five-group order and the blank-line separation. Name build.gradle's `importOrder` call as the enforcing mechanism.

Address one honest tension rather than glossing it: this file's preamble scopes it to "judgement-level choices Spotless cannot check", and unlike rules 1-9 this rule IS mechanically enforced by `./gradlew spotlessCheck`. Say so explicitly, and give the reason it is recorded here anyway — the build enforces WHAT the order is but records nothing about WHY first-party sits third, ahead of third-party rather than after it, which is the part a contributor would otherwise be tempted to "correct" toward the more common first-party-last convention. Note that a developer never hand-maintains these blocks: `spotlessApply` (and the pre-commit hook) rewrites them.

Also record that the `javax` group currently matches nothing, since Spring Boot 3 uses `jakarta.*`, and is retained deliberately.

For the example pair, use a real before/after drawn from the reformat — the discouraged block being the single undifferentiated ASCII-sorted run with static imports on top, the preferred block being the grouped form. Take the "preferred" side from the actual post-reformat content of a real file so the example cannot drift from what the build produces.

Commit this separately from the Task 1+2 reformat commit, so the mechanical reformat stays a pure, blame-ignorable commit.
  </action>
  <verify>
    <automated>
grep -n '^### 10\.' docs/CODE_STYLE.md
grep -c '^\*\*Why:\*\*' docs/CODE_STYLE.md
./gradlew spotlessCheck
    </automated>
  </verify>
  <done>docs/CODE_STYLE.md contains a `### 10.` rule carrying all three required parts (statement, bolded Why, discouraged/preferred examples). The **Why:** count is 10, one per rule. spotlessCheck still passes (markdown is not in Spotless's `src/**/*.java` target, so this is a regression guard, not a new gate).</done>
</task>

</tasks>

<verification>
- `./gradlew spotlessCheck` passes on the full tree (the idempotency gate).
- `./gradlew test` passes with no reduction in test count versus the pre-change baseline of ~210+ tests.
- Zero FAIL lines from the three structural grep loops in Task 2.
- Static imports appear last in all 23 files that have them, inverted from their current first position.
- `git log` shows the 161-file reformat as one isolated commit with no non-formatting content in it.
</verification>

<success_criteria>
Import blocks across all 161 Java files are grouped java / javax / com.vrudenko / third-party / static, blank-line separated, with the ordering enforced by build.gradle and the rationale recorded as docs/CODE_STYLE.md rule 10. Both gradle gates green. No production behavior changed.
</success_criteria>

<output>
Create `.planning/quick/260811-ffs-add-import-group-blank-line-separation-t/260811-ffs-SUMMARY.md` when done.

Record in the summary: the actual test count and wall-clock from `./gradlew test`; whether the pipeline turned out to be idempotent on the first `spotlessCheck` after `spotlessApply` (and if not, what was reordered); the final commit SHAs, noting which one is the blame-ignorable reformat.
</output>
