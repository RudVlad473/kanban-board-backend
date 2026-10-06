---
phase: quick-261006-fby
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - scripts/verify-comments.py
  - scripts/verify-comments-selftest.py
  - build.gradle
  - src/main/java/**/*.java (comment-only sweep, 116 files with src/test/java; list in the SUMMARY)
  - src/test/java/**/*.java (comment-only sweep)
  - .planning/quick/261006-fby-ban-javadoc-html-tags-and-inline-tags-in/sweep-javadoc-markup.py
  - docs/CODE_STYLE.md
  - .planning/codebase/CONVENTIONS.md
  - .claude/CLAUDE.md (two lines, ONLY with the user's explicit approval, see Task 3)
autonomous: true
requirements: [261006-fby]

estimate:
  tokens: 190000
  raw_tokens: 190000
  tasks: 3
  confidence: low

must_haves:
  truths:
    - "With the new javadoc-markup rule in place, `python3 scripts/verify-comments.py check` on the pre-sweep tree reports 1,126 javadoc-markup violations in 116 Java files (554 java-main lines, 572 java-test lines) and no violation from any other rule; on the final tree it reports OK with 0 violations. Both numbers are recorded in the SUMMARY (measured at 1f34861 during planning; re-measure if HEAD has moved)"
    - "The selftest's new r5 cases FAIL before the rule exists (RED, failing test names recorded) and all cases pass after (GREEN); making the tag regex case-insensitive makes the lookalike case fail, which proves the quiet side bites too"
    - "Spotless runs google-java-format with Javadoc formatting off: `./gradlew spotlessCheck` passes on the swept tree, and with the flag removed it FAILS on a swept file because the formatter re-inserts the paragraph tag (both directions recorded)"
    - "The sweep is comment-only and prose-preserving: `verify-comments.py equiv --base 1f34861 src/main/java src/test/java` reports OK for every changed file, every changed diff line is a comment line, and the sweep script's word-sequence verify reports no deleted or replaced word, only additions at the listed hand-fixed paragraph-start lines"
    - "No comment line starts with `@` unless it was already a block tag before the sweep, and the ErrorProne warning histogram per check name is no higher after the sweep than before (or UnescapedEntity is disabled with a recorded reason, per Task 2)"
    - "`./gradlew spotlessCheck` and the full `./gradlew test` pass on the final tree; exactly two executor commits land through the unmodified pre-commit hook without --no-verify: commit 1 = formatter flag + sweep + sweep script, commit 2 = rule + selftest + docs"
    - "docs/CODE_STYLE.md rule 14, the verify-comments.py docstring and .planning/codebase/CONVENTIONS.md state the plain-text rule; .claude/CLAUDE.md lines 173-174 are changed only if the user approved it, otherwise they are listed as open in the SUMMARY"
  artifacts:
    - path: scripts/verify-comments.py
      provides: "javadoc-markup rule inside lint(), Java-only, run over the tokenizer's comment text so string literals and text blocks never trip it"
    - path: scripts/verify-comments-selftest.py
      provides: "r5 cases: fires on every HTML-tag and inline-tag shape, one violation per line, quiet on generics/placeholders/braces/literals, Java-only"
    - path: build.gradle
      provides: "googleJavaFormat().aosp().formatJavadoc(false) with a policy-compliant reason comment"
    - path: .planning/quick/261006-fby-ban-javadoc-html-tags-and-inline-tags-in/sweep-javadoc-markup.py
      provides: "apply (tokenizer-scoped markup-to-plain-text transform) and verify (per-block word-sequence proof against a base ref)"
    - path: docs/CODE_STYLE.md
      provides: "rule 14 item 7 plus the formatter note and a Discouraged example"
  key_links:
    - from: .githooks/pre-commit
      to: scripts/verify-comments.py check
      via: "the hook runs the working-tree linter before spotlessCheck and fastTest, so the new rule gates every commit once it is in the tree"
    - from: .github/workflows/invariant-checks.yml comment-lint job
      to: scripts/verify-comments-selftest.py then scripts/verify-comments.py check
      via: "CI proves the rule can fire, then checks the tree; no workflow edit needed"
    - from: build.gradle spotless java block
      to: google-java-format 1.24.0 --skip-javadoc-formatting
      via: "Spotless 7.0.2 GoogleJavaFormatConfig.formatJavadoc(boolean), confirmed with javap on the cached plugin jar during planning"
    - from: sweep-javadoc-markup.py
      to: scripts/verify-comments.py extract()/lex_cstyle
      via: "imported by file path, so the sweep edits exactly the line ranges the linter calls comments"
---

# Quick task 261006-fby: ban Javadoc HTML tags and inline tags in Java comments

<objective>
Make every Java comment plain text. Add a comment-policy lint rule that rejects HTML tags and the
`{@...}` inline-tag form in Java comments. Stop google-java-format from re-inserting the
paragraph tag. Sweep the 1,126 existing violating lines in 116 files with a comment-only change,
and update the docs.

Purpose: the comment policy (docs/CODE_STYLE.md rule 14) treats comments as source-read prose,
and this repo never renders Javadoc (no `javadoc` task in build.gradle, CI or the Dockerfile).
The markup is therefore noise on the only surface anyone reads. The formatter currently forces it
back in, because google-java-format 1.24.0 rewrites every blank-line paragraph break as a
paragraph tag.

Output: the rule plus its selftest, one build.gradle flag, a swept `src/` tree, a reusable sweep
script with a proof mode, and updated docs. These land as two commits.

Locked by the user (honored throughout):
- Banned: HTML tags in Java comments (for example the paragraph, list, list-item, preformatted,
  bold, italic and anchor tags) and the `{@` inline-tag form (code, link, literal and so on).
  Plain braces in prose or code samples stay allowed. Block tags (`@param`, `@return`,
  `@throws`) are NOT banned. Checked: rule 14 and the linter do not forbid them. The linter
  already counts `@param`/`@return`/`@throws` lines as non-prose (NONPROSE_TAG_RE), so the policy
  expects them.
- Scope: Java files only. Out of scope: any non-Java file's comments, any production or test CODE
  change, and the fan-out-review skill.
- Rejected by the user: Checkstyle or any other new dependency.
</objective>

<execution_context>
@~/.claude/gsd-core/workflows/execute-plan.md
@~/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@.claude/CLAUDE.md
@docs/CODE_STYLE.md
@docs/SESSION_LESSONS.md
@scripts/verify-comments.py
@scripts/verify-comments-selftest.py
@.githooks/pre-commit

Measured during planning at HEAD 1f34861, using the linter's own `analyze()` (so functional and
AAA lines are excluded exactly as `check` excludes them):
- 196 Java files are in scope and none is exempt. FROZEN migrations are `.sql`, so no frozen or
  generated path holds a Java comment.
- 1,126 comment lines violate the rule as specified below: 991 contain an inline tag and 354 an
  HTML tag. They sit in 116 files, with 554 lines in java-main and 572 in java-test.
- Tag mix: paragraph 290 (always at line start, never alone, never closed), bold 64, list item 29,
  emphasis 12, unordered list 12, italic 10, ordered list 4. There are 0 preformatted, 0 anchor
  and 0 break tags. Inline tags are 1,048 code and 337 link: 55 member-local (`#m`) and 53 with a
  parameter list. There are 0 labelled links and 0 of any other inline tag. 6 code bodies hold
  nested braces, such as a route with `{boardId}`, and 235 inline tags open on one line and close
  on the next.
- All 240 affected comments are full-line `/** ... */` blocks. No `//` comment and no trailing
  comment carries markup.
- Removing the inline-code wrapper leaves a comment line starting with `@` in 31 places, because
  the code tag wraps an annotation name. In 23 of them, a word from the previous line can be
  pulled down. In 8, the `@` opens a paragraph and needs a hand fix: KafkaConsumerConfig.java:148,
  BoardService.java:180, ColumnService.java:85, SubtaskService.java:44, TaskService.java:51,
  TaskService.java:289, ComposedConstraintPropertyCustomizerTest.java:697 and
  SigninTimingEqualizationTest.java:101 (pre-sweep line numbers).
- Non-HTML angle tokens in Java comments: `<name>`, `<T>`, `<SubtaskEntity>`, `<Outcome>`,
  `<Condition>`. There is no uppercase HTML tag. One line carries 3 `&gt;` entities
  (BoardRepository.java:42).
- Spotless 7.0.2's `GoogleJavaFormatConfig` exposes both `formatJavadoc(boolean)` and
  `skipJavadocFormatting()` (javap on the cached plugin jar).
- No runtime or test reads Javadoc. There is no therapi and no springdoc Javadoc module, and no
  test opens a source file. A comment change therefore cannot change behaviour. Dropping the 16
  standalone list-wrapper lines only shifts LineNumberTable entries.
- ErrorProne promotes NotJavadoc to an error on test sources (build.gradle). The sweep never
  changes a comment opener, so that check is unaffected. Its HTML-oriented Javadoc checks
  (InvalidBlockTag, UnescapedEntity) are warnings, and Task 2 measures them.
</context>

## Approach and trade-offs (project directive: 2 alternatives, matrix, non-obvious trade-offs)

**Data flow, in three sentences.** `lex_cstyle` in scripts/verify-comments.py splits a Java file
into comment events (line ranges with their text) and code tokens (with string literals and text
blocks kept whole). The new rule reads only the comment text of those events, and the sweep
rewrites only the line ranges of those events. `equiv` then re-lexes both versions and compares
the code-token lists, which proves that nothing outside a comment moved.

### 1. How the sweep runs

| Approach | Pros/Cons | Why Picked |
|----------|-----------|------------|
| **Tokenizer-scoped script.** `sweep-javadoc-markup.py apply` imports the linter by path. It transforms only the line ranges of full-line block-comment events that the rule flags, and reports what it will not touch as residue for hand fixing. Its `verify` mode proves that prose is unchanged. | + Edits exactly what the linter calls a comment, so a quoted tag inside a string literal or text block is untouchable by construction. + Deterministic and idempotent: a second run changes nothing. + Reviewable through three independent proofs: `equiv` shows code tokens are identical, `verify` shows the word sequence of every block is identical, and the diff shows every changed line is a comment line. − The script is about 200 lines that must be right. The tracer tests it on the hardest file first. | **Picked.** A reviewer checks a short script and three machine proofs, plus the hand-fixed residue (about 8 lines), instead of 1,126 lines. |
| Alt 1: hand edits, file group by file group | + Each line gets human judgement. − 1,126 lines in 116 files is slow and inconsistent. The only proof is `equiv`, so a dropped word or a mangled sentence would pass. | Rejected: it cannot be reviewed at this size. |
| Alt 2: raw regex (sed or perl) over whole files | + Fastest to write. − It reaches string literals and text blocks. It cannot follow the 235 tags that span a ` * ` line prefix or the 6 tags with nested braces. `equiv` would only catch code damage after the fact. | Rejected: it is unsafe by construction. |

A javaparser or OpenRewrite recipe was also considered and rejected, because it adds a dependency
the project constraint forbids.

### 2. What each construct becomes

| Approach | Pros/Cons | Why Picked |
|----------|-----------|------------|
| **Plain text.** A code tag becomes its body. A link becomes its target, with a leading `#` dropped and any other `#` written as `.` (`BoardEntity#getColumn()` becomes `BoardEntity.getColumn()`). A paragraph tag becomes nothing, because the blank comment line before it already marks the break. Bold, italic and emphasis tags become nothing. List wrappers are dropped, and list items become `- ` (or `1. `, `2. ` in an ordered list). `&gt;`, `&lt;` and `&amp;` become their characters on swept lines. A line left starting with `@` is repaired, as in the next row. | + This is what the user asked for, and the repo's newest comments already name identifiers bare, outside the tags. + `- ` lines count as prose exactly as list-item lines did, while the dropped wrapper lines were counted as prose too. So per-block prose counts only fall, and the summary-first and narration rules cannot newly fire. − Code names lose their visual marker, and emphasis is lost in 22 places. | **Picked.** |
| Alt 1: backticks for code (Markdown style) | + Keeps the code/prose distinction, cannot produce a line-leading `@`, and Java 23 Markdown doc comments use backticks. − It swaps one markup dialect for another that Java 21 does not render, adds about 2,100 backtick characters, and was not requested. | Rejected. Existing backticks (40 comments) stay as they are. |
| Alt 2: delete an identifier mention that only asserts similarity to a sibling (rubric rule 7) | + Shorter comments. − That needs judgement at 1,385 sites, changes content, and breaks the word-sequence proof that makes the sweep reviewable. | Rejected for this task. It is a separate judgement pass, a non-goal here. |

**Line-leading `@` repair.** Javadoc parses a line that starts with `@` as a block tag. That ends
the main description, ErrorProne reports InvalidBlockTag, and the linter's TAG_RE ends the summary
paragraph. So when the sweep leaves a line starting with `@` that did not start with `@` before:
- **Inside a paragraph (23 sites):** the script moves the previous line's last whitespace-delimited
  token to the front of that line. It joins without a space when that token ends in an opening
  bracket or quote. The word sequence is unchanged, and the line stays shorter than it was,
  because the tag text it lost is longer than one word.
- **At the start of a paragraph (8 sites):** no previous word exists, so the script reports the
  line and the executor fixes it by hand. The fix adds the fewest words that make the sentence
  start with a word, for example "The @Transactional annotation is declared here ..." and "The
  @Version bypass, by design: ...". Nothing else is reworded.

### 3. Commit order under the hook

The hook runs gitleaks (on the staged diff), then the comment lint (on the working tree), then
spotlessCheck, then fastTest. It takes about 4 minutes.

| Approach | Pros/Cons | Why Picked |
|----------|-----------|------------|
| **Write the rule first, test-first, and leave it uncommitted. Commit 1 = the formatter flag, the sweep and the sweep script. Commit 2 = the rule, the selftest and the docs.** | + The rule exists before the sweep, so it measures the real 1,126 and serves as the sweep's completeness oracle. + The 116-file mechanical diff and the rule logic are reviewed separately. + Commit 1 is self-consistent. The hook lints with the working-tree linter, which is the old rules plus the new one. The new rule only adds a check and changes no existing rule (the unchanged selftest cases prove this), so a clean result under the superset implies commit 1's own tree passes its own older linter. − Commit 1 is staged selectively (`build.gradle`, `src/`, the sweep script) while scripts/ changes stay unstaged. | **Picked.** 2 executor commits, about 8 minutes of hook time. |
| Alt 1: one commit holding everything | + One hook run. − A reviewer cannot separate a 116-file comment diff from rule logic. | Rejected. |
| Alt 2: three commits (formatter, sweep, rule) | + Finest grain. − It spends an extra 4-minute hook on a one-line flag whose safety on the unswept tree spotlessCheck already proves locally in Task 1. | Rejected. |

Committing the rule first is impossible, because the hook would refuse 1,126 violations.

### 4. Where the rule lives

| Approach | Pros/Cons | Why Picked |
|----------|-----------|------------|
| **A new `javadoc-markup` rule in `lint()` of scripts/verify-comments.py.** It runs per comment line, only when `scope.kind == "java"`, with a fixed lowercase HTML element list and `\{@[A-Za-z]`, and reports one violation per offending line. | + Reuses the tokenizer, so a quoted tag in a string literal or text block is code, not comment. + Already wired into the hook and CI, with a selftest harness and no new dependency. + Covers `//`, `/* */` and `/** */` alike. − Regex, not a Javadoc parser. Its holes (uppercase tags, unlisted elements, entities) are documented. | **Picked.** |
| Alt 1: Checkstyle (JavadocStyle and friends) | + A mature parser. − A new dependency, rejected by the user and by the no-new-frameworks constraint. | Rejected. |
| Alt 2: a custom ErrorProne BugChecker | + Runs inside javac. − Needs a separate checker module wired as an annotation processor, sees only `/** */` and not `//` comments, and adds build complexity for a regex-sized check. | Rejected. |

**No exception marker.** The tokenizer already treats string literals and text blocks as code, so
test fixtures that hold markup in strings are invisible to the rule. A comment that has to discuss
these constructs names them in words ("the paragraph tag"). An allow marker would only add a bypass
surface, so none is added.

**Lowercase-only tag names.** Type parameters and placeholders share the angle-bracket shape (`<T>`,
`Pair<A, B>`, `List<String>`, `<name>`). Matching only lowercase names from a fixed HTML element
list keeps all 5 non-HTML tokens found in today's comments legal. The cost is a known hole: an
uppercase tag passes. Today no uppercase tag exists.

### Non-obvious trade-offs

- **Turning off Javadoc formatting is global.** google-java-format stops reflowing Javadoc
  paragraphs and normalising Javadoc indentation for every future comment. Lines shortened by the
  sweep stay ragged, and comment width is no longer machine-normalised. The comment linter, not
  the formatter, is the comment gate from now on.
- **Rendered Javadoc degrades.** If anyone ever runs `javadoc` or reads IDE hover text, paragraphs
  run together, lists collapse into running text, and generics such as `Map<String, String>` in
  prose could make doclint complain. That is accepted: nothing renders Javadoc here, and the source
  is the reading surface.
- **ErrorProne's HTML-minded Javadoc checks now disagree with policy.** UnescapedEntity's own
  remedy is to escape or to wrap in a code tag, both banned. Task 2 measures the warning histogram
  before and after. It disables UnescapedEntity only if the sweep made it fire. The 2 code bodies
  holding generics are the expected trigger.
- **Performance.** The rule adds two regex searches per comment line, which is negligible over
  about 200 files. The sweep runs once.
- **Security.** No package is installed and no gate is loosened. The hook and CI stay fail-closed.
  The only risk is a wrong mechanical edit, which the threat register covers.

## Source coverage audit

| Source | Item | Covered by |
|--------|------|------------|
| GOAL | lint rule banning HTML tags and `{@` in Java comments | Task 1 (rule, selftest), Task 3 (commit) |
| GOAL | stop google-java-format re-inserting the paragraph tag | Task 1 (flag, proven both ways), Task 2 (commit 1) |
| GOAL | sweep existing violations, comment-only | Task 1 (tracer file), Task 2 (tree) |
| GOAL | update the docs | Task 3 |
| REQ | 261006-fby | all tasks |
| CONTEXT | banned set; block tags allowed | Task 1 regexes and selftest; `@param` quiet case |
| CONTEXT | Java-only scope; no code change; skill untouched | rule gated on kind java; `equiv`; no skill path in files_modified |
| CONTEXT | no Checkstyle or new dependency | Approach 4 |
| CONTEXT | FROZEN migrations untouched | the sweep selects only kind java; `equiv` refuses any FROZEN byte change |

Non-goals: rubric rule-7 pruning of identifier mentions, banning entities or uppercase tags,
comments in non-Java files, docs/wiki copies (docs originals stay authoritative per
.claude/CLAUDE.md), pushing to origin.

<tasks>

<task type="tracer" tdd="true">
  <name>Task 1: Tracer, from rule to formatter flag to sweep on one file (rule test-first; NO commit)</name>
  <files>scripts/verify-comments-selftest.py, scripts/verify-comments.py, build.gradle, .planning/quick/261006-fby-ban-javadoc-html-tags-and-inline-tags-in/sweep-javadoc-markup.py, src/main/java/com/vrudenko/kanban_board/repository/BoardRepository.java</files>
  <read_first>scripts/verify-comments.py (read it WHOLE with the Read tool), scripts/verify-comments-selftest.py, build.gradle lines 60-78 and 615-660, src/main/java/com/vrudenko/kanban_board/repository/BoardRepository.java lines 1-60, docs/SESSION_LESSONS.md lessons 3 and 9</read_first>
  <behavior>
    - test_r5_javadoc_markup_fires_on_html_tags: each of these, placed after a one-line summary and a blank line in a `/** */` block, yields exactly ["javadoc-markup"]: the paragraph open and close tags, the unordered-list, ordered-list and list-item tags, the preformatted, bold, italic, emphasis and code tags, a self-closing break tag, and an anchor tag with an href attribute. A bold pair inside a `//` comment and a paragraph tag inside a `/* */` comment fire too.
    - test_r5_javadoc_markup_fires_on_inline_tags: the code, link (with `Foo#bar()`), literal, inheritDoc, linkplain and value inline forms each fire. A code tag whose opener ends one line and whose body sits on the next fires on the opener's line (assert `Violation.line`). A line holding three banned tokens yields exactly ONE violation.
    - test_r5_javadoc_markup_ignores_lookalikes: no violation from `List<String>`, `Map<K, V>`, `<T>`, `Pair<A, B>`, a `<name>` placeholder, `a < b > c`, a route with `{boardId}`, `flags() = {DOTALL}`, an `@param x the value` block-tag line, or an email-like `user@example.com`. Markup inside a Java string literal and inside a text block is not a comment and does not fire. Keep each fixture block to at most 3 prose lines, and avoid the TODO/FIXME words, so no other rule fires.
    - test_r5_javadoc_markup_is_java_only: the same markup in a build.gradle `//` comment, a `.sh` `#` comment and a Python docstring yields [].
  </behavior>
  <action>
Record BASE as the output of `git rev-parse --short HEAD`. It was 1f34861 at planning time; if
it differs, use the new value everywhere this plan says 1f34861. Check `free -h` (lesson 9).

Capture the ErrorProne baseline before touching any Java file. Run `./gradlew compileJava
compileTestJava --rerun-tasks --console=plain`, pipe the output through `grep -o 'warning: \[[A-Za-z]*\]' | sort | uniq -c`,
and record the histogram, keeping main and test apart if the output allows. Task 2 compares
against it.

RED: add the four r5 cases from the behavior block to scripts/verify-comments-selftest.py, using
its existing `rules()` helper and `JAVA`/`TEST_JAVA` paths. For the line and count assertions,
call `_gate.lint(...)` directly. Run `python3 scripts/verify-comments-selftest.py` and record which
cases fail. The two `fires` cases must FAIL. The `ignores` and `java_only` cases pass even now
(they assert absence) and only become meaningful once the rule exists; say so in the SUMMARY.

GREEN: in scripts/verify-comments.py, add two module constants beside TAG_RE:
- JAVADOC_HTML_RE: a case-SENSITIVE regex matching an opening, closing or self-closing tag whose
  name is in a fixed lowercase HTML element list (a, abbr, b, big, blockquote, br, caption, cite,
  code, dd, del, dfn, div, dl, dt, em, h1-h6, hr, i, img, ins, kbd, li, ol, p, pre, q, s, samp,
  small, span, strike, strong, sub, sup, table, tbody, td, tfoot, th, thead, tr, tt, u, ul, var).
  An optional attribute part starts with whitespace and contains no angle bracket.
- JAVADOC_INLINE_RE: `\{@[A-Za-z]`.

In `lint()`, inside the existing per-line loop over `block.lines` (where the planning-id hits are
collected), and only when `scope.kind == "java"`: collect the distinct matched tokens of both
regexes, keeping inline matches as the opener text such as the code or link opener. If any are
found, append ONE `Violation("javadoc-markup", path, ln, ...)` whose detail names the tokens and
says to write plain text, and append "javadoc-markup" to `flags`. Change no existing rule or
helper. Leave the paragraph-tag accommodations in `is_prose`, MARKER_RE and the summary-paragraph
break as they are: they are language-agnostic, and removing them is an unrelated behaviour change.
Re-run the selftest; every case must pass.

Mutation proof for the quiet side: temporarily add `re.I` to JAVADOC_HTML_RE and run the selftest.
The ignores case must FAIL, because `Pair<A, B>` and `<T>`-style tokens start to match. Revert,
and record both runs.

Baseline count: run `python3 scripts/verify-comments.py check`. Record the javadoc-markup line
count (`grep -c '^FAIL: javadoc-markup:'`), expected 1126. Record the distinct-file count (take
field 3 with awk, cut at the first colon, sort -u, wc -l), expected 116. Confirm that
`grep '^FAIL: ' | grep -vc 'javadoc-markup'` gives 0, so no other rule fires. A different count
at a moved HEAD is fine if explained. A different count at 1f34861 means the regexes differ from
this plan's: stop and reconcile.

Formatter: in the build.gradle `spotless { java { ... } }` block, chain
`.formatJavadoc(false)` onto `googleJavaFormat().aosp()`. Above it, add a comment that follows
rule 14: a one-line summary, then a blank `//` line, then detail. The comment must say that Java
comments are plain text under docs/CODE_STYLE.md rule 14, and that with Javadoc formatting on,
google-java-format 1.24.0 rewrites every blank-line paragraph break into the paragraph tag that
scripts/verify-comments.py rejects. Do not put any planning id (quick-task id, D-number, phase or
plan number) in any comment you write; the planning-id rule bans them. Run `./gradlew
spotlessCheck` on the still-unswept tree. It must pass, which proves the flag alone reformats
nothing.

Sweep script: create
.planning/quick/261006-fby-ban-javadoc-html-tags-and-inline-tags-in/sweep-javadoc-markup.py,
stdlib only. Load scripts/verify-comments.py by file path with importlib, the way the selftest
does, so the module's ROOT resolves to the repo. Give it two subcommands.

`apply [paths...]` resolves in-scope java files via the module's `resolve_paths` and `classify`
(kind java, not exempt). For each comment event from `extract("java", text)` that is full-line,
not mergeable (a block comment) and holds a rule match, rewrite only lines ev.start..ev.end, in
this order:
- (a) Split each line into its prefix (indentation, then the opener, the leading `*` or the closer,
  then one space) and its content. Join the contents with newlines.
- (b) Replace each inline tag, closed by brace-depth matching so nested braces survive. Code and
  literal tags become their body. Link and linkplain tags become their label if one is present;
  otherwise they become the target, with a leading `#` dropped and other `#` written as `.`
  (parentheses inside the target do not split it). Any other tag name is left alone and reported
  as residue. Whitespace containing a newline between the tag name and the body stays a newline,
  so the line count is unchanged.
- (c) Per line: decode `&gt;`, `&lt;` and `&amp;`. Strip a leading paragraph tag and the spaces
  after it. Delete bold, italic and emphasis open and close tags. Mark lines whose whole content is
  a list open or close tag for dropping.
- (d) Line-leading `@` repair, against the original content line at the same index, as described
  in Approach 2: pull the previous line's last token down inside a paragraph; at a paragraph start,
  leave the line and report it as residue.
- (e) Drop the marked lines. Convert each list item into `- ` (unordered) or `N. ` (ordered,
  numbered from 1 per list), dedented to the paragraph margin, with continuation lines re-indented
  to align under the item text. A nested list, or a preformatted, anchor or any other unhandled
  tag, is left alone and reported as residue.
- (f) Collapse two consecutive empty content lines created by a drop into one. Strip trailing
  whitespace. Reassemble with the original prefixes; an empty content line keeps the bare ` *`
  prefix.

Write files byte-for-byte otherwise, reading and writing with newline="" so line endings survive.
Print the files changed, the blocks changed and every residue line as path:line plus its text.

`verify --base REF [paths...]`: for each changed java file, extract the comment events of the
base version (`git show`) and of the working tree. Require equal event counts. For each event pair,
compare word sequences (`[A-Za-z0-9_]+`) after normalising both sides: decode entities, remove
rule-regex HTML tags and `{@name` openers, and strip a leading `- ` or `N. ` list marker per line.
Report every insertion as ADDED with path:line and the words, and every deletion or replacement as
CHANGED. Exit 1 on any CHANGED or count mismatch; insertions alone exit 0.

Tracer run: `apply src/main/java/com/vrudenko/kanban_board/repository/BoardRepository.java`. That
file holds the paragraph, ordered-list, list-item and italic tags, code and link tags, fully
qualified and `#`-member links, tags split across lines, and the `&gt;` entities. Inspect the
result:
- The ordered list becomes `1. ` and `2. ` items.
- `Decisions:` survives as a marker line.
- The links read `BoardEntity.getColumn()` and `com.vrudenko.kanban_board.entity.ColumnEntity.getTask()`.
- The arrow chain reads `board->column->task->subtasks`.

Then run, in order:
- `python3 scripts/verify-comments.py check src/main/java/com/vrudenko/kanban_board/repository/BoardRepository.java`
  must say OK.
- `python3 scripts/verify-comments.py equiv --base HEAD src/main/java/com/vrudenko/kanban_board/repository/BoardRepository.java`
  must say OK.
- `sweep-javadoc-markup.py verify --base HEAD` on that file must report no CHANGED.
- `./gradlew spotlessCheck` must pass.
- Negative control: temporarily remove `.formatJavadoc(false)`, run `./gradlew spotlessCheck`, and
  confirm that it FAILS on BoardRepository.java, with the paragraph tag in the reported diff.
  Restore the flag and confirm that it passes again. Record both.

Re-run `apply` on the same file and confirm that it changes nothing (idempotent).

Do NOT commit at the end of this task. The hook lints the whole working tree, and the other 115
files still violate the new rule. This task's changes are committed in Task 2 (build.gradle,
BoardRepository.java, the sweep script) and Task 3 (scripts/).
  </action>
  <verify>
    <automated>python3 scripts/verify-comments-selftest.py && python3 scripts/verify-comments.py check src/main/java/com/vrudenko/kanban_board/repository/BoardRepository.java && python3 scripts/verify-comments.py equiv --base HEAD src/main/java/com/vrudenko/kanban_board/repository/BoardRepository.java && python3 .planning/quick/261006-fby-ban-javadoc-html-tags-and-inline-tags-in/sweep-javadoc-markup.py verify --base HEAD src/main/java/com/vrudenko/kanban_board/repository/BoardRepository.java && ./gradlew spotlessCheck</automated>
  </verify>
  <done>The selftest RED run (failing r5 names), the GREEN run, the case-insensitive mutation failure and its revert, the baseline (1,126 lines in 116 files, 0 from other rules), and the ErrorProne baseline histogram are recorded. spotlessCheck passes with the flag and fails without it on the swept BoardRepository.java. That file lints clean, is `equiv`-equivalent and is word-identical to HEAD. Nothing is committed.</done>
</task>

<task type="auto">
  <name>Task 2: Sweep the tree, prove it is comment-only, and land commit 1 (formatter flag + sweep + script)</name>
  <files>src/main/java/**/*.java, src/test/java/**/*.java (comment lines only), build.gradle (only if the ErrorProne rule below triggers), .planning/quick/261006-fby-ban-javadoc-html-tags-and-inline-tags-in/sweep-javadoc-markup.py</files>
  <read_first>The residue lines the apply run prints (read each with offset/limit, not whole files), and build.gradle lines 615-660 if the ErrorProne histogram changes</read_first>
  <action>
Before running the script, state the data flow above (three sentences) in the session. Then run
`python3 .planning/quick/261006-fby-ban-javadoc-html-tags-and-inline-tags-in/sweep-javadoc-markup.py apply src/main/java src/test/java`.
Record the files and blocks changed, expected about 116 files and 240 blocks counting the tracer
file.

Hand-fix the residue. Expected: the 8 paragraph-start `@` lines listed in context, now
shifted by the sweep. Add the fewest words that make the line start with a word, for example "The
@Transactional annotation is declared here ...", "The @Qualifier("deadLetterKafkaTemplate")
annotation is required ...", "The @Version bypass, by design: ...". Reword nothing else. Report
any other residue (an unexpected tag name, a nested list, a non-block comment with markup) in the
SUMMARY and fix it by hand in the same plain-text style. If a paragraph-start fix would need more
than adding words, keep the sentence and move the annotation name off the line start by reordering
only within that sentence, then report it.

Re-run `apply` over both trees and confirm that it changes 0 files.

Prove the sweep:
- `python3 scripts/verify-comments.py check` must report OK with 0 violations tree-wide, every
  rule included. If summary-first or segregated-narration fires on a swept block, restore the
  paragraph break the sweep lost (a blank comment line) and re-run.
- `python3 .planning/quick/261006-fby-ban-javadoc-html-tags-and-inline-tags-in/sweep-javadoc-markup.py verify --base 1f34861 src/main/java src/test/java`
  must exit 0 with zero CHANGED. Its ADDED lines must be exactly the hand fixes; list them in the
  SUMMARY.
- `python3 scripts/verify-comments.py equiv --base 1f34861 src/main/java src/test/java` must
  report OK for every changed file (expected 116).
- Every changed diff line must be a comment line. Take
  `git diff 1f34861 -- src/main/java src/test/java`, keep lines that start with + or -, remove the
  file-header lines, then remove lines that are + or - followed by optional whitespace and then
  `*`, `/*`, `/**` or `//`. The remaining count must be 0.
- `./gradlew spotlessCheck` must pass.

ErrorProne: re-run the Task 1 histogram command and compare. No check name may appear, or rise in
count, that was not in the baseline, with two exceptions:
- If UnescapedEntity rose, append `disable('UnescapedEntity')` to the existing
  `tasks.withType(JavaCompile).configureEach { options.errorprone { ... } }` block, with a short
  rule-14-compliant reason: comments are plain text, and the check's only remedies are an HTML
  escape or a banned inline tag. Re-run to confirm the count is back.
- If InvalidBlockTag rose, a line-leading `@` slipped through: fix that comment, do not disable
  the check.

If the baseline's EscapedEntity finding at UserMapper.java (named in build.gradle's comment above
`compileTestJava`) is now gone, or was already absent in the Task 1 baseline, correct that sentence
so it states what the histogram actually shows. Any other ErrorProne change gets recorded and
reported, not suppressed.

Full suite: check `free -h`, then run `./gradlew test` in the background with a long timeout. It
must end BUILD SUCCESSFUL. Afterwards run `docker ps -a` and remove only Testcontainers left
behind by this run (Postgres or Redpanda containers created during it).

Commit 1. Stage only build.gradle, the swept files (`git add -u src/main/java src/test/java`) and
the sweep script. Leave scripts/verify-comments.py and scripts/verify-comments-selftest.py
unstaged, and never stage the untracked .serena/ or docs/learning/kafka-architecture-overview.md.
Commit with a message such as "style(quick-261006-fby): write Java comments as plain text and stop
google-java-format re-inserting paragraph tags". The body cites the proofs (equiv count, verify
with 0 CHANGED, the 0 non-comment diff lines, the formatter negative control) and the superset
argument for why this commit's own tree passes its own linter. End with the Co-Authored-By trailer
from the session's attribution instructions.

Run the commit in the background with a timeout of at least 10 minutes; the hook takes about 4.
Never use --no-verify. If the hook's Gradle JVM is killed under memory pressure (lesson 9), stop
and report BLOCKED with the `free -h` reading; do not bypass the hook. After the commit, run
`git status --short`. Only the two scripts/ files and the pre-existing untracked paths may remain.

Review aid for the SUMMARY: `git show --stat HEAD | tail -1`, and a `git show --word-diff=plain`
excerpt of BoardRepository.java.
  </action>
  <verify>
    <automated>python3 scripts/verify-comments.py check && python3 .planning/quick/261006-fby-ban-javadoc-html-tags-and-inline-tags-in/sweep-javadoc-markup.py verify --base 1f34861 src/main/java src/test/java && python3 scripts/verify-comments.py equiv --base 1f34861 src/main/java src/test/java && ./gradlew spotlessCheck</automated>
  </verify>
  <done>Commit 1 is on HEAD and went through the full hook (gitleaks, comment lint, spotlessCheck, fastTest). The tree lints with 0 violations. equiv is OK over src. verify reports 0 CHANGED, and its ADDED lines are exactly the listed hand fixes. 0 non-comment diff lines. The ErrorProne histogram is no worse than the baseline, or UnescapedEntity is disabled with a reason. The full `./gradlew test` passed and no orphaned test containers remain.</done>
</task>

<task type="auto">
  <name>Task 3: Document the rule and land commit 2 (rule + selftest + docs)</name>
  <files>scripts/verify-comments.py, scripts/verify-comments-selftest.py, docs/CODE_STYLE.md, .planning/codebase/CONVENTIONS.md, .claude/CLAUDE.md (conditional)</files>
  <read_first>docs/CODE_STYLE.md lines 649-690, .planning/codebase/CONVENTIONS.md lines 100-112, .claude/CLAUDE.md lines 160-176</read_first>
  <action>
**Linter docstring** (scripts/verify-comments.py). Extend the `Rules:` sentence so it also says
that Java comments carry no Javadoc HTML tags and no `{@` inline tags. The docstring currently
has 6 prose lines before `Decisions:`, and the narration rule allows 8, so add at most 2 lines
there. Under `Decisions:`, add a falsifiable entry. It says the tag list is fixed and lowercase
because type parameters and placeholders share the angle-bracket shape: on 2026-10-06 the Java
comments held 5 such non-HTML tokens and no uppercase HTML. It is false if an uppercase tag
appears. Under `Known holes:`, add these:
- an uppercase or unlisted element name passes;
- HTML entities are not banned;
- a tag whose name is split from its bracket across lines is not seen;
- the rule covers Java only, even though Groovy comments use the same syntax.

Run `python3 scripts/verify-comments.py check scripts/verify-comments.py`; it must say OK.

**docs/CODE_STYLE.md rule 14.** Add item 7 to the numbered one-pass review list: write comments
as plain text, with no HTML tags and no `{@...}` inline tags. A blank comment line is the
paragraph break, `- ` starts a list item, and an identifier is written by its bare name, never
starting a comment line with `@` unless it is a block tag such as `@param`. Extend the **Why:**
paragraph by one or two sentences. Javadoc is never rendered here, so the markup only adds noise
to the source. Spotless runs google-java-format with Javadoc formatting off because the formatter
would otherwise insert the paragraph tag at every blank-line break.

In the existing Discouraged Java block, add a Javadoc that uses a paragraph tag and a code tag.
In the Preferred section, add the same Javadoc in plain text. Markdown docs are outside the ban,
but the Preferred example must not model a banned construct. Grep the rest of docs/CODE_STYLE.md
for `{@` and for paragraph or list tags in Java examples. There were none at planning time; fix
any you find in a non-Discouraged example.

**.planning/codebase/CONVENTIONS.md.** This is the source of the generated GSD conventions block
in .claude/CLAUDE.md. Replace its two lines, "Link references used: ..." (line 109) and "HTML
tags used in JavaDoc: ..." (line 110), with one line: comments are plain text, with no HTML tags
and no `{@...}` inline tags (docs/CODE_STYLE.md rule 14, enforced by scripts/verify-comments.py);
identifiers are written by bare name, a blank comment line separates paragraphs, and `- ` starts
a list item.

**.claude/CLAUDE.md lines 173-174** carry the same two lines and tell every future agent to write
the banned markup. Only the user can authorize a CLAUDE.md change. If the execution prompt
records the user's explicit approval, apply the identical replacement there. Otherwise leave the
file untouched and list it as an open item in the SUMMARY, with the exact replacement text.

**Commit 2.** Run `python3 scripts/verify-comments-selftest.py` (all pass) and
`python3 scripts/verify-comments.py check` (OK). Stage scripts/verify-comments.py,
scripts/verify-comments-selftest.py, docs/CODE_STYLE.md, .planning/codebase/CONVENTIONS.md (and
.claude/CLAUDE.md only if approved). Commit in the background with a timeout of at least 10
minutes, with a message such as "feat(quick-261006-fby): lint Java comments for Javadoc HTML and
inline tags" whose body states RED/GREEN, the mutation proof and the 1,126 to 0 count, ending
with the Co-Authored-By trailer. Never use --no-verify; on a memory-pressure kill, report
BLOCKED. Do not push.
  </action>
  <verify>
    <automated>python3 scripts/verify-comments-selftest.py && python3 scripts/verify-comments.py check && git log --oneline -2 && git status --short</automated>
  </verify>
  <done>Commit 2 is on HEAD and went through the full hook. The selftest passes in full and the tree lints with 0 violations. Rule 14, the linter docstring and CONVENTIONS.md describe the plain-text rule. CLAUDE.md is either updated with the user's approval or listed as open with its replacement text. The working tree holds no change from this task beyond the pre-existing untracked paths.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| developer working tree → commit | The pre-commit hook (gitleaks, comment lint, spotlessCheck, fastTest) is the local gate. This plan adds a rule to it and must not weaken it. |
| commit → CI | invariant-checks.yml comment-lint runs the selftest, then `check`. Fail-closed. |
| mechanical transform → source | A script rewrites 116 source files. A wrong edit outside a comment would change behaviour. |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-fby-01 | Tampering | sweep-javadoc-markup.py apply | high | mitigate | Edits only line ranges of full-line block-comment events from the linter's own lex_cstyle. `equiv --base 1f34861` proves the code-token lists are identical. A diff filter proves every changed line is a comment line. The full `./gradlew test` runs on the swept tree. |
| T-fby-02 | Tampering | prose content during the sweep | medium | mitigate | `verify` compares per-block word sequences against the base and fails on any deleted or replaced word. Insertions are listed and must equal the hand-fixed lines. |
| T-fby-03 | Tampering (gate weakening) | javadoc-markup rule | medium | mitigate | RED/GREEN selftest and a case-insensitive mutation proof for the quiet side. The real-tree baseline of 1,126 must match before the sweep, and 0 must hold after. |
| T-fby-04 | Denial of service (developer workflow) | false positives blocking commits | low | mitigate | A fixed lowercase element list plus the lookalike selftest (generics, placeholders, braces, literals). Uppercase tags are an accepted known hole, recorded in the docstring. |
| T-fby-05 | Elevation of privilege (gate bypass) | `git commit --no-verify` under memory pressure | medium | mitigate | Forbidden by this plan. A killed hook JVM ends the task BLOCKED, and only the operator may authorize a bypass, freshly. |
| T-fby-06 | Tampering | FROZEN Flyway migrations | high | mitigate | The sweep selects kind java only. `equiv` fails on any byte change under src/main/resources/db/migration/. |
| T-fby-07 | Information disclosure | rendered Javadoc or IDE hover | low | accept | Paragraphs and lists render as running text. Nothing publishes Javadoc (no javadoc task in build.gradle, CI or the Dockerfile). |
| T-fby-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package install anywhere in this plan. The sweep script and rule are stdlib-only, so the package-legitimacy gate has nothing to check. |
</threat_model>

<verification>
On the final tree (after commit 2):
- `python3 scripts/verify-comments-selftest.py`: all pass, r5 cases included.
- `python3 scripts/verify-comments.py check`: OK, 0 violations (1,126 javadoc-markup violations
  before the sweep, recorded).
- `python3 scripts/verify-comments.py equiv --base 1f34861 src/main/java src/test/java`: OK.
- `python3 .planning/quick/261006-fby-ban-javadoc-html-tags-and-inline-tags-in/sweep-javadoc-markup.py verify --base 1f34861 src/main/java src/test/java`:
  exit 0, 0 CHANGED.
- `./gradlew spotlessCheck`: pass. The full `./gradlew test` passed on the swept Java tree in Task
  2. Task 3 changes no Java.
- `git log --oneline -3` shows the two commits in order (sweep, then rule) on top of 1f34861.
</verification>

<success_criteria>
- Java comments contain no HTML tag and no `{@` inline tag, and the hook and CI reject a new one.
- google-java-format no longer inserts the paragraph tag, proven by the negative control.
- The sweep is proven comment-only (`equiv`, diff filter, full suite) and prose-preserving
  (`verify`), and its 8 or so hand fixes are listed.
- Two commits through the unmodified hook, without --no-verify, not pushed.
- Docs updated. The CLAUDE.md change is either approved and applied, or open with its text.
</success_criteria>

<output>
Create `.planning/quick/261006-fby-ban-javadoc-html-tags-and-inline-tags-in/261006-fby-SUMMARY.md` when done
</output>
