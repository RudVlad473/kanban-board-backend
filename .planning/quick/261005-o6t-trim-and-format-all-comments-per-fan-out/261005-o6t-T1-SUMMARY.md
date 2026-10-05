---
phase: quick-261005-o6t
plan: 01
task_scope: T1 only (tracer)
subsystem: tooling
tags: [comments, lint, equivalence-proof, tracer]
requires: []
provides:
  - scripts/verify-comments.py (files / stats / report / check / equiv)
  - scripts/verify-comments-selftest.py (37 cases, mutation-checked)
  - UserService.java and lombok.config trimmed to the comment rubric
affects: [T2, T3, T4, T5]
tech-stack:
  added: []
  patterns: [stdlib-only lint, lexers per family, equiv by code-representation compare]
key-files:
  created: [scripts/verify-comments.py, scripts/verify-comments-selftest.py]
  modified: [src/main/java/com/vrudenko/kanban_board/service/UserService.java, lombok.config]
status: complete
commits: 2
plan_head_before: 679dcf97cf28e65f4f7c52699f6560f01e9a8c6e
plan_head_after: 72bb681ab915405c015612653901a5967af06225
actuals:
  tokens: 14400   # chars/4 over the four files written or changed (approximate)
  tasks: 1
  commits: 2
---

# Phase quick-261005-o6t Plan 01, T1 (tracer): comment lint + equivalence harness, end to end on two files

The comment-policy harness (`verify-comments.py` + selftest) is committed and proven on the real tree,
and `UserService.java` and `lombok.config` are cleaned end to end: 8 planning-id/narration
violations became 0, and `equiv` proves no code token changed.

Only T1 was executed. T2-T5 are untouched.

## Commits

| Hash | Message |
|---|---|
| b6897c9 | chore(261005-o6t): add comment-policy lint, equivalence proof and selftest |
| 72bb681 | chore(261005-o6t): trim comments in UserService and lombok.config to the comment policy |

- `comment-pass-start` is a local tag at `679dcf97cf28e65f4f7c52699f6560f01e9a8c6e` (the plan commit,
  per the plan's `git tag comment-pass-start HEAD`; code identical to 3837ab1 apart from the plan file).
  Tags are repo-wide, so the other worktrees can see it. T5 deletes it.
- Both commits used `--no-verify` on the user's explicit authorisation for this run (comment-only edits).

## Before-table (committed tool, `stats --ref comment-pass-start`, full tree)

`comment` = lines that are only comment (incl. block-comment interior and Python docstrings);
`long` = blocks over 8 prose lines; `ids` = gated planning-id hits outside exempt files.

| family | files | lines | comment | % | long | ids | ids_exempt | suspects | aaa |
|---|---|---|---|---|---|---|---|---|---|
| gradle | 2 | 776 | 440 | 56.7 | 14 | 44 | 0 | 23 | 0 |
| java-main | 129 | 7633 | 2080 | 27.3 | 61 | 161 | 0 | 8 | 0 |
| java-test | 65 | 19975 | 3930 | 19.7 | 61 | 198 | 0 | 15 | 1431 |
| other | 16 | 2029 | 550 | 27.1 | 10 | 51 | 0 | 29 | 0 |
| properties | 2 | 421 | 306 | 72.7 | 8 | 31 | 0 | 4 | 0 |
| sh | 10 | 1405 | 498 | 35.4 | 12 | 23 | 5 | 23 | 0 |
| sql | 13 | 378 | 229 | 60.6 | 4 | 21 | 14 | 0 | 0 |
| yaml | 52 | 2297 | 625 | 27.2 | 19 | 125 | 0 | 141 | 0 |
| yml | 10 | 1484 | 664 | 44.7 | 21 | 106 | 0 | 38 | 0 |
| TOTAL | 299 | 36398 | 9322 | 25.6 | 210 | 760 | 19 | 281 | 1431 |

Whole-tree `check` at base (before any pass): 1537 FAIL lines = 760 planning-id, 564 summary-first,
209 segregated-narration, 3 tracked-todo (the three known TODOs: `TaskService.java:97`,
`TaskControllerTest.java:86`, `OwnershipVerifierServiceTest.java:17`). Runtime of a whole-tree check: 0.9 s.

### Differences from the planning-time table (more than 10%)

| item | planning | measured | likely cause (not individually verified) |
|---|---|---|---|
| other: comment lines | ~290 | 550 | the tool counts Python docstring lines (the four `scripts/*.py` gates) as comment lines; the planning classifier counted `#` lines only |
| sh: comment lines | 525 | 498 | planning figure was approximate ("~34%"); tool is 35.4% |
| ids: decision | 341 | 332 | path masking removes ids inside existing repo paths |
| ids: quick-task | 142 | 113 | an artifact name now absorbs the quick-task id inside it (counted once, as artifact-name) |
| ids: Phase N | 112 | 87 | case-sensitive `Phase \d+` and path masking; planning method unknown |
| ids: plan NN-NN | 100 | 32 | the gated regex requires the word `plan(s)` before the number; the bare `NN-NN` shape is a report-only suspect (281 total) |
| ids: artifact names | 16 | 62 | the regex covers every `*-{SUMMARY,PLAN,CONTEXT,RESEARCH,VERIFICATION,MEASUREMENTS,REVIEW,UAT,PATTERNS,VALIDATION,SPEC}.md`, wider than the planning count |
| ids total | ~853 | 760 (+19 in exempt files) | sum of the above |

By category at base: decision 332, quick-task 113, requirement 103, Phase 87, artifact-name 62, plan-number 32,
threat 21, Epic 10. Threat ids (21) match planning exactly.

## T1 two-file before / after

| file | comment lines | % | long blocks | id hits | check violations |
|---|---|---|---|---|---|
| UserService.java | 22 -> 19 | 13.9 -> 12.3 | 0 -> 0 | 7 -> 0 | 4 blocks flagged -> 0 |
| lombok.config | 16 -> 11 | 84.2 -> 78.6 | 1 -> 0 | 1 -> 0 | 1 block flagged -> 0 |
| total | 38 -> 30 | 21.5 -> 17.8 | 1 -> 0 | 8 -> 0 | all clear |

Decisions made while trimming (all content-preserving):
- `UserService.toResponseDTO`: kept as a decision record (summary, blank, `Decisions:`): the
  not-`@Transactional` observation and "do not inject `UserMapper` into a controller because
  `LayeringArchTest` rule 1 polices only the repository package". Dropped `F1`, `D-01`, `260812-hs4`.
- `UserService.save`: summary + `Decisions:` carrying the `uk_users_email` backstop and the 409 mapping.
  Cut the "same pattern `BoardService.updateById` relies on" sibling reference (rubric rule 7) and `D-07`.
- `findThemeByUserId`: two lines; kept the `docs/CODE_STYLE.md rule 2` path, dropped `GAP-05 (D-10..D-12)`.
- `updateTheme`: two lines, deliberate-absence record kept (no `@Version`, last-write-wins). Dropped `T-06-29`.
  It still points at `UpdateThemeRequestDTO`, whose own Javadoc still carries `D-10..D-12`, `06-06` and
  `T-06-29`. That file belongs to T2.
- `lombok.config`: summary line, then `Why this is the way it is:` carrying the whole JaCoCo rationale
  (the JaCoCo 0.8.2 `@lombok.Generated` filter, Lombok not stamping the annotation by default). Dropped
  `quick task 260812-eg8`, `design_rationale, trade-off 1`, "this task's whole job" and the "no lombok.config
  before this quick task" history sentence. The `stopBubbling` block is two lines.
- Kept because something references it: nothing in `UserService` is pointed at by docs. `docs/learning/05-api-layer.md`
  line 289 cites `UserController`'s Javadoc, not `UserService`'s. `lombok.config` is referenced by name from
  `build.gradle` (line 445) and `docs/learning/09`, and its content still says what they rely on.
- No TODOs removed. No rename suggestions.

## Verification (real output)

`python3 scripts/verify-comments-selftest.py` : `37/37 passed`, exit 0. The single `FAIL: no-files: ...` line in its
output is the intended output of the zero-files case, which asserts exit 1. The PyYAML cross-check compared the
stdlib YAML comment detector with `yaml.scan` on 62 in-scope YAML files with zero mismatches (PyYAML 6.0.3).

Mutation check of the gate (throwaway harness in the gitignored `build/`, removed afterwards): 16 deliberate
breakages (quote tracking, R2/R3 thresholds, frozen refusal, functional/keyword/AAA comparisons, path masking,
zero-files guard, Java string lexing, shell heredoc, SQL dollar-quote, TODO target check, workflow run-block
comments, generated-file cut, code comparison) were each applied to the gate. 16/16 made the selftest fail
(no survivors). A second live check planted one-token code edits in `UserService.java` (`return false` to
`return true`) and `lombok.config` (`stopBubbling = false`): `equiv` exited 1 on both; after restoring, exit 0.

```
$ python3 scripts/verify-comments.py check scripts/verify-comments.py scripts/verify-comments-selftest.py lombok.config src/main/java/com/vrudenko/kanban_board/service/UserService.java
OK: 4 files, 43 comment blocks            (exit 0)

$ python3 scripts/verify-comments.py equiv --base comment-pass-start
NOTE: scripts/verify-comments-selftest.py is new since comment-pass-start, nothing to compare
NOTE: scripts/verify-comments.py is new since comment-pass-start, nothing to compare
OK: equiv vs comment-pass-start: 2 changed in-scope files equivalent, 2 new, 0 allow-listed   (exit 0)
```

Gradle (`--no-daemon`, see Deviations): `spotlessJava` and `spotlessJavaCheck` executed (not cached) and
`BUILD SUCCESSFUL in 13s`; `compileJava` executed and `BUILD SUCCESSFUL in 24s`.
Not run: `compileTestJava` (not in T1's verify line) and the full `./gradlew test` (T5's gate).

gitleaks: `gitleaks dir` on each of the four files, `no leaks found`, exit 0 (see Deviations for why not `--staged`).

## Deviations from Plan

1. **[Authorised] `git commit --no-verify`** for both commits, per the run's instruction. Verification was
   still run manually as listed above.
2. **[Rule 3 - Blocking] Gradle daemon killed mid-build.** `./gradlew ...` failed repeatedly with
   `Gradle build daemon has been stopped: stop command received`: the `docs/SESSION_LESSONS.md` lesson 9
   signature (host had about 1.3 GB free; Serena's JDT LS, meilisearch, two Claude sessions and others were
   running; no second GSD worktree was building). Spotless passed on the second attempt with `--no-daemon`,
   `compileJava` only on the third, using `--no-daemon --max-workers=1 -Dorg.gradle.vfs.watch=false
   -Dorg.gradle.jvmargs=-Xmx512m`. Note for the Wave-2 orchestrator: this host will probably not sustain
   three parallel Gradle runs.
3. **[Tooling] `gitleaks git --staged` was refused** by this environment's worktree guard (a git command among
   gitleaks's operands). I ran `gitleaks dir <file>` on each changed file instead: it scans whole files, not the
   staged diff, and it ignored `.gitleaks-baseline.json`, so it is a close but not identical substitute.
4. **Shell equivalence uses the script's own quote- and heredoc-aware lexer, not `shlex`** to strip trailing
   comments. An unterminated quote or heredoc is reported as a failure instead of being passed. Recorded in the
   script's `Known holes:`.
5. **Exemption/functional list is slightly wider than the plan's never-edit list**: additionally `type: ignore`,
   `pragma:` and `nosec` comment lines count as functional, and `// arrange|act|assert` lines in `java-test` are
   excluded from the prose rules (their count is still compared by `equiv`). All other exemption classes are
   exactly the plan's: EXCLUDED_PREFIXES, VENDORED, GENERATED, FROZEN, ROLLOUT_GATED. Nothing was exempted to
   make a file pass.
6. `docs/CODE_STYLE.md rule 14` is named in every FAIL line as specified, but the rule does not exist until T5.

## Behaviour notes for the Wave-2 executors

- `report --all <paths>` is the worklist; without `--all` it lists only flagged blocks.
- `equiv` compares the working tree against the base for tracked paths. A file new since the base is reported
  as `NOTE ... new` and not compared, which is why the two harness scripts show as new.
- The PostToolUse "comment length advisory" hook fires on any `//` block over 6 lines. It does not recognise a
  summary line followed by a blank `//` line and a `Decisions:` marker, so it flags compliant decision records.
  The gate in `verify-comments.py` is the arbiter.
- `check` at base reports 3 `tracked-todo` failures; these are the three TODOs the plan already lists for T2/T3.

## Unverified / not done

- The pre-commit hook and CI wiring (T5) do not exist yet; nothing refuses a planning id today.
- Windows behaviour of the stdlib-only lint cannot be verified on this host.
- The `ids` count differences against the planning table are explained by regex and masking differences only
  by inspection of the patterns, not by a category-by-category diff against the planning-time method.
- `compileTestJava` and the full test suite were not run (outside T1's verify line).
- The lexers are checked on fixtures and (YAML only) cross-checked against PyYAML on the real tree; the Java,
  Groovy, shell, SQL and TOML lexers have not been cross-checked against an independent parser on the whole
  tree. `equiv` over the 2 changed files is all the real-tree exercise they have had so far. T2-T4 will exercise
  them over about 280 files; a spurious `equiv` or `check` result there should be suspected in the lexer first.

## Known Stubs

None.

## Threat Flags

None. (No new network endpoints, auth paths or schema changes; both scripts read the repo and call local `git`.)

## Self-Check: PASSED

- `scripts/verify-comments.py`, `scripts/verify-comments-selftest.py`, `lombok.config`, `UserService.java`: present.
- Commits b6897c9 and 72bb681 are ancestors of HEAD; `git rev-list --count 679dcf97..HEAD` = 2 (matches `commits: 2`).
- Tag `comment-pass-start` resolves to 679dcf97cf28e65f4f7c52699f6560f01e9a8c6e.
- No tracked file was deleted by either commit.
