---
phase: quick-261006-jh9
plan: 01
subsystem: agent-instructions
tags: [claude-md, context-budget, pre-commit, ci, writing-for-agents]
requires: []
provides:
  - "Byte-ceiling gate over every always-loaded instruction file and its @-imports"
  - "Trimmed, hand-owned, marker-free .claude/CLAUDE.md"
affects: [".githooks/pre-commit", ".github/workflows/invariant-checks.yml", "docs/LOCAL_DEV.md", "docs/SESSION_LESSONS.md", "docs/INFRA_ARCHITECTURE.md"]
tech-stack:
  added: []
  patterns: ["reviewed-constant ceiling with a both-direction selftest, wired like verify-comments.py"]
key-files:
  created:
    - scripts/verify-instruction-budget.py
    - scripts/verify-instruction-budget-selftest.py
  modified:
    - .claude/CLAUDE.md
    - .githooks/pre-commit
    - .github/workflows/invariant-checks.yml
    - docs/LOCAL_DEV.md
    - docs/SESSION_LESSONS.md
    - docs/INFRA_ARCHITECTURE.md
    - scripts/verify-comments.py
decisions:
  - "Trim survives GSD regeneration by removing every section marker (the generator's own hand-crafted skip), not by editing the global generator"
  - "CI trigger is an ordered paths list that re-includes instruction files, because the old paths-ignore skipped every *.md"
metrics:
  duration: "about 1h20m"
  completed: 2026-10-06
status: incomplete
actuals:
  tokens: 9000
  tasks: 3
  commits: 3
plan_head_before: 51bfa408101ac9248f9d4bd4ad5ca83ba1ff9c16
plan_head_after: 7b23313e68b4661a1abadc11eaa300d9c143b587
commits: 3
---

# Phase quick-261006-jh9 Plan 01: Trim CLAUDE.md and add a size ceiling Summary

`.claude/CLAUDE.md` went from 36,844 to 4,605 bytes and a stdlib byte-ceiling gate (selftest, pre-commit step, CI job) now stops it growing back.

## Status: incomplete

Every must_have that can be checked locally is met. `status: incomplete` is set because two must_haves cannot be confirmed from this checkout, and the plan said not to claim them:

- **Not verified (needs a real GitHub PR run):** that the new `paths` trigger actually runs invariant-checks for a change touching only an instruction file, and that all five jobs run on the PR. The YAML shape was asserted locally (exact ordered list, `branches: [main]`, job steps); GitHub's behaviour was not exercised.
- **Not verified (human check):** the authoritative token figure, `/context` in a fresh session. The numbers below are bytes-based estimates.

## Measurements

| | Bytes | Tokens at bytes/4 | Tokens at bytes/3.5 |
|---|---|---|---|
| Before (file) | 36,844 | 9,211 | 10,527 |
| Before (injected, 501 bytes of GSD marker comments stripped) | 36,343 | 9,086 | 10,384 |
| After (file) | 4,605 | 1,151 | 1,316 |
| After (injected, 591-byte maintainer comment stripped) | 4,014 | 1,004 | 1,147 |

- **Method:** tokens are bytes divided by 4, with bytes divided by 3.5 as the upper bound for dense markdown with code spans and paths. Both are proxies, not a tokenizer run.
- **Saving:** about 8k to 9k tokens in every session in this repo (injected before to injected after).
- The target text was 4,636 bytes; the written file is 4,605 (the plan's two GSD placeholders were replaced by the current file's text, which is slightly shorter than the plan estimated). The must_have bound is 5,500.
- **Ceiling:** `CEILING_BYTES` 37,300 after Task 1 (36,844 + 400, rounded up to 100), lowered to 5,100 after Task 2 (4,605 + 400, rounded up to 100). Both carry dated lines in the gate's Decisions block.

## Directions actually run

| Check | Fires above | Quiet at or below |
|---|---|---|
| Selftest (29 cases) | RED against the stub: 26 of 29 failed (every behavior case; only 3 trivially-true cases passed). Boundary cases ceiling+1 fail | Boundary cases exactly-ceiling and ceiling-1 pass; each root name passes at the ceiling; lookalikes (annotations, code span, fence, email) count nothing |
| Mutation | Dropping `AGENTS.md` from `ROOT_FILES` made `test_each_root_name_fires_alone` FAIL (28/29); restored, 29/29 | n/a |
| Real gate | `--ceiling 1000` exits 1 (run at 37,300 and again at 5,100); `--ceiling 99999999` exits 2 at 37,300; `--ceiling 5101` exits 2 at 5,100 | Exit 0 at 37,300 (36,844 bytes) and at 5,100 (4,605 bytes) |
| Real hook | `.claude/CLAUDE.md` padded by 2,000 bytes (TOTAL 38,844 vs CEILING 37,300): hook exit 1, printed `OVER BUDGET`, refused at the budget step, never printed `Checking formatting`. File restored byte-identical (`git diff --exit-code` clean, 36,844 bytes) and the probe log removed | All three task commits passed the unmodified hook (gitleaks, comment lint, budget, spotlessCheck, fastTest) |

The hook probe ran against the 37,300 ceiling, before the trim. It was not re-run against the 5,100 ceiling; the gate itself was shown to fire at 5,100 via `--ceiling`.

## Regeneration proof (scratch copies, removed afterwards)

- Pre-trim file from commit 0ba2069: `generate-claude-md` reported `"action": "updated"` (36,844 to 32,141 bytes).
- Trimmed file: reported `"action": "skipped"`, copy byte-identical to the real file (`cmp`).
- The real file was never regenerated.

## Ledger

72 rows. Before cutting, the check ran with 63 probes hit, 8 needing no destination, and one miss: L70 (the local-dev recipe), which is the fact Task 2 moves into `docs/LOCAL_DEV.md`, so its destination did not exist yet. That is the expected state, not a plan error. After the move: **72 rows, 64 probes hit, 8 need no destination, 0 misses.** No destination was found missing a fact the plan said it held, so no row was corrected and no sentence was added to any doc.

## Verification of the target's claims against the tree

- `jakarta.transaction.Transactional` is the only `@Transactional` import (9 files); no Spring one.
- Field `@Autowired` is in 16 of 17 service/controller files. `ResetTruncateService` has no `@Autowired` field because its only field is a `@PersistenceContext` EntityManager (read, not just grepped), so the target's wording holds without any edit.
- `config/RandFlakeGenerator.java` and `AbstractAppTest.countQueries` exist. `ulid-creator` is declared in build.gradle (line 198) and imported nowhere in `src/`. The hook step list matches.
- No target line failed verification, so none was corrected.

## Deviations from Plan

None to the plan's scope. Notes:

- The CI trigger comment was placed in the Decisions block (as the plan asks) and a second comment sits above the `paths` list in the YAML; both are plain text and the comment lint passes.
- Added `suggested_ceiling` and `ratchet_advisory` as separate pure functions (the plan describes the advisory inside `main`) so the selftest can check it without capturing output.
- `main` catches argparse's `SystemExit` and returns 2, so a usage error is a return value, as the selftest requires.
- The gate prints `note:` lines for conditional rules files and for path-like imports that name no existing file.

## Known Stubs

None.

## Threat Flags

None. The new surface is a stdlib script that prints repo-relative paths and byte counts only.

## Stale claims left behind elsewhere (not fixed: out of scope)

- `.planning/codebase/{STACK,CONVENTIONS,ARCHITECTURE,TESTING,STRUCTURE,INTEGRATIONS,CONCERNS}.md` still hold the stale claims the trim removed from CLAUDE.md (Gradle 8.7, ULID as the id scheme, "no constructor injection", REST Assured for controller tests, line-number references, and so on). Out of scope per the plan. Note that these files are the source GSD's generator reads, which is why the markers had to go; they will stay stale until someone refreshes them.
- `docs/LOCAL_DEV.md`: older prose naming a `kafka` compose service and a `docker-compose.prod.yml` paragraph that predate the Redpanda and k3s changes. Left untouched, as the plan said; follow-up candidate.
- `docs/INFRA_RUNBOOK.md` line 985 lists `.claude/CLAUDE.md` "(Platform Requirements bullets only)" among files touched by a past cutover. It is a historical record, but the section no longer exists.
- `docs/learning/08-testing-strategy.md` line 637 links to `.claude/CLAUDE.md` Constraints ("Testing"), a section that no longer exists. `docs/learning/01`, `04`, `05` and `06` quote the old CLAUDE.md wording as the thing they corrected; they read as history and are fine.
- `docs/history/2026-08-17-*` and `2026-09-07-log-aggregation.md` cite the old "Logging not extensively used" note and the GSD-generated Platform Requirements section as they were. Historical.

## Follow-ups

- Open the PR and confirm in the Actions run that all five invariant-checks jobs ran and that the path filter fires on an instruction-file-only change. If `.*/**` or `**` behave unexpectedly the trigger is the T-jh9-04 risk.
- Run `/context` in a fresh session and record the real token figure.
- The CODE_STYLE.md split is the next queued task.

## Environment after the run

- `docker ps -a`: three leftover containers, none started by this task and none removed: `strange_galois` (postgres:16, exited 16 hours ago), `naughty_perlman` (redpanda v26.2.1, exited 10 days ago), `loving_newton` (postgres:16, exited 10 days ago). They predate this session, so they are not from the three hook runs; the hook's Testcontainers left nothing behind that I can distinguish.
- The Gradle daemon was stopped with `./gradlew --stop` (1 daemon stopped). No hook run hit "stop command received", so no retry was needed.
- Nothing was merged; the branch is `quick/trim-claude-md-and-size-guard`. The user's untracked `.serena/` and `docs/learning/kafka-architecture-overview.md` were left alone.

## Commits

- a6f2a65 feat(quick-261006-jh9): cap always-loaded agent instruction files with a byte ceiling
- 5c724df docs(quick-261006-jh9): trim CLAUDE.md to what an agent cannot find by looking
- 7b23313 docs(quick-261006-jh9): list the instruction-budget job and verify the removed-fact ledger

## Self-Check: PASSED for what is verifiable locally

- Files exist: scripts/verify-instruction-budget.py, scripts/verify-instruction-budget-selftest.py, .claude/CLAUDE.md (4,605 bytes), docs/LOCAL_DEV.md section `Inspecting live output`.
- Commits a6f2a65, 5c724df and 7b23313 are on the branch; `git rev-list --count 51bfa40..HEAD` is 3.
- The CI path filter and the `/context` figure are not self-checked (see Status).
