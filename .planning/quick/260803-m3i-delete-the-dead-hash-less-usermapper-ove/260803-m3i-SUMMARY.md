---
phase: quick/260803-m3i
plan: 01
subsystem: auth
tags: [mapstruct, jpa, hibernate, password-hashing, ddl, security]

# Dependency graph
requires:
  - "260803-l6f: UserPersistenceE2ETest proving HTTP signup persists a real bcrypt hash — the regression guard this task's falsification step relies on and whose stale comment this task corrects"
provides:
  - "UserMapper with exactly one entity-producing overload (fromSignupRequestDTO(SignupRequestDTO)) — the two hash-less siblings that could silently resolve to a null-hash write no longer exist"
  - "UserEntity.passwordHash declared @Column(nullable = false) — H2 test schema now rejects a null hash at the write"
  - "docs/plans/backend-modernization/04-password-hash-not-null-ddl.sql — guarded, idempotent, NOT YET RUN production DDL bridge for the same constraint"
  - ".planning/todos/pending/2026-08-03-usermapper-entity-to-request-dto-methods-leak-the-bcrypt-hash.md — new security-marked todo for the entity-to-request-DTO hash-copy finding, deliberately left unfixed"
affects: [security, mapper-layer, entity-layer, production-schema-migrations]

actuals:
  tokens: 5427
  tasks: 3
  commits: 3

tech-stack:
  added: []
  patterns:
    - "Guarded DDL bridge: a PL/pgSQL DO block that pre-flight-counts rows violating the incoming constraint and RAISE EXCEPTIONs with the count before touching the schema, rather than a bare ALTER that could fail mid-deploy on data the operator never checked — used because SET NOT NULL, unlike the precedent ADD COLUMN ... DEFAULT, can fail outright against existing rows"
    - "Falsify the new constraint by hand when no new test can safely assert it: temporarily revert, run the full suite, confirm it stays green (proving the suite doesn't already cover the property), then restore and treat the real run as the actual verification"

key-files:
  modified:
    - src/main/java/com/vrudenko/kanban_board/mapper/UserMapper.java
    - src/main/java/com/vrudenko/kanban_board/entity/UserEntity.java
    - src/test/java/com/vrudenko/kanban_board/security/UserPersistenceE2ETest.java
    - docs/plans/backend-modernization/STATUS.md
    - .planning/STATE.md
  created:
    - docs/plans/backend-modernization/04-password-hash-not-null-ddl.sql
    - .planning/todos/pending/2026-08-03-usermapper-entity-to-request-dto-methods-leak-the-bcrypt-hash.md
  renamed:
    - ".planning/todos/pending/2026-08-03-remove-hash-less-user-mapper-overloads-and-tighten-password-hash-nullability.md -> .planning/todos/completed/2026-08-03-remove-hash-less-user-mapper-overloads-and-tighten-password-hash-nullability.md"

key-decisions:
  - "Chosen approach A (guarded DO block) over a bare ALTER (approach B) or a silent backfill-to-sentinel (approach C, rejected outright) — locked in the plan's trade-off matrix, executed as decided"
  - "The 04- prefix on the new DDL script continues the DDL-script delivery sequence (02, 03, 04), not the epic-numbered .md docs in the same directory (04-redis.md is Epic 4, unrelated) — the script's own header disambiguates this explicitly"
  - "The production migration is explicitly NOT applied by this task. UserEntity's annotation change only affects the H2 test schema (ddl-auto is unset in the real Postgres profile); running the DDL script via psql against the real database, before merge, remains an outstanding human action"

requirements-completed: []

coverage:
  - id: D1
    description: "UserMapper no longer declares any entity-producing overload accepting a SigninRequestDTO — the overload-resolution hazard is gone by construction"
    verification:
      - kind: unit
        ref: "grep -rn 'fromSigninRequestDTO' src/ -> 0 hits; grep -rn 'fromSignupRequestDTO(SigninRequestDTO' src/ -> 0 hits; grep -rn 'fromSignupRequestDTO' src/ -> 2 hits (declaration + UserService.java:51 call)"
        status: pass
      - kind: integration
        ref: "./gradlew compileJava compileTestJava -> BUILD SUCCESSFUL"
        status: pass
    human_judgment: false
  - id: D2
    description: "UserEntity.passwordHash is non-nullable; the H2 test schema rejects a null hash at the write; the change was falsified by hand (temporarily reverted, suite confirmed green/silent, restored) rather than assumed proven by a green suite alone"
    verification:
      - kind: unit
        ref: "grep -c 'nullable = false' UserEntity.java -> 2; grep -c 'nullable = true' UserEntity.java -> 0"
        status: pass
      - kind: integration
        ref: "./gradlew spotlessCheck test (nullable=false, final state) -> BUILD SUCCESSFUL in 3m 24s"
        status: pass
      - kind: manual_procedural
        ref: "Falsification: passwordHash reverted to nullable=true, ./gradlew test -> BUILD SUCCESSFUL in 3m 30s (proves suite is silent on this property), then restored"
        status: pass
    human_judgment: false
  - id: D3
    description: "UserPersistenceE2ETest's assertions are byte-for-byte unchanged; only the stale explanatory comment (which claimed a NOT NULL assertion 'would fail today') was corrected"
    verification:
      - kind: unit
        ref: "git diff --unified=0 UserPersistenceE2ETest.java | grep -E '^[-+]' | grep -v comment/blank lines -> 0 non-comment lines changed"
        status: pass
    human_judgment: false
  - id: D4
    description: "A guarded, idempotent production DDL bridge script exists (pre-flight null count, RAISE EXCEPTION naming the row count, SET NOT NULL only if the count is zero) and was NOT executed against any database by the agent"
    verification:
      - kind: unit
        ref: "grep checks in Task 2's <automated> verify (ALTER TABLE, SELECT COUNT, RAISE EXCEPTION, WHAT THIS IS NOT, SAFE TO RE-RUN, flyway) -> all present"
        status: pass
      - kind: manual_procedural
        ref: "04-password-hash-not-null-ddl.sql — human must read end-to-end and run only the pre-flight SELECT COUNT(*) against the real database before merge"
        status: unknown
    human_judgment: true
    rationale: "RO-4/RO-3 require a human to read the script and run the pre-flight query against the real production database before merge — this is explicitly a step no agent performs (plan's <human-check>)."
  - id: D5
    description: "The originating todo is closed via git mv (pending/ -> completed/), content unchanged; a new security-marked todo records the entity-to-request-DTO hash-copy finding discovered while re-verifying callers; STATE.md records both and states explicitly that the production migration is not applied"
    verification:
      - kind: integration
        ref: "git show --stat 5961cba shows 'rename ... (100%)' with no content diff on the moved file; grep 'severity: security' on the new todo; grep '260803-m3i' STATE.md"
        status: pass
    human_judgment: false

duration: ~35min
completed: 2026-08-03
status: complete
---

# Quick Task 260803-m3i: Delete the Dead Hash-Less UserMapper Overloads and Tighten passwordHash Nullability Summary

**Deleted two dead `UserMapper` entity-producing overloads that could silently write a null password hash, tightened `UserEntity.passwordHash` to non-nullable (verified by hand-falsification, not by a new test), and delivered — but did not run — a guarded production DDL bridge script; the production database migration remains an outstanding, explicitly flagged human pre-merge action.**

## Performance

- **Duration:** ~35 min
- **Tasks:** 3 (Task 1 tracer, Task 2 auto, Task 3 auto)
- **Files modified:** 5 modified + 2 created + 1 renamed

## Accomplishments

- **The overload-resolution hazard is gone by construction.** Re-verified zero callers by a fresh `grep -rn` across all of `src/` (main and test) before touching anything — the only hits were the two doomed declarations, the surviving hashing overload's declaration, and its one legitimate call site (`UserService.java:51`). Deleted `fromSigninRequestDTO(SigninRequestDTO)` and `fromSignupRequestDTO(SigninRequestDTO)` from `UserMapper`. `./gradlew compileJava compileTestJava` confirmed MapStruct still generates `UserMapperImpl` cleanly with no orphaned caller.
- **`UserEntity.passwordHash` now rejects a null write at the database, not just at the type system.** Changed `@Column(nullable = true)` to `@Column(nullable = false)` and replaced the comment, which previously justified nullability by citing future non-password auth methods that don't exist, with one stating the current reality and pointing at the new DDL script.
- **Verified the new constraint by falsification, not by a green suite alone.** A green `./gradlew test` proves nothing about a new schema constraint if nothing asserts it. Per the plan's required falsification step: temporarily reverted `passwordHash` to `nullable = true`, ran the full suite (`BUILD SUCCESSFUL in 3m 30s` — confirms the suite is silent on this property, as expected, since no fixture path writes a null hash), then restored `nullable = false` and reran `./gradlew spotlessCheck test` as the real verification (`BUILD SUCCESSFUL in 3m 24s`). The SUMMARY reports this constraint as verified by inspection and by the falsification exercise, not by a test asserting it directly — none exists, by design (RO-5 requires `UserPersistenceE2ETest`'s assertions to stay untouched).
- **`UserPersistenceE2ETest`'s stale comment corrected, its assertions untouched.** The old comment claimed a DB-level NOT NULL assertion "would fail today"; that became false the moment the entity changed. Rewrote it to state the column is now non-nullable and that this class still deliberately asserts what the column holds, not what the schema forbids. `git diff --unified=0` on this file, filtered to non-comment/non-blank lines, returns zero — confirmed mechanically, not just asserted.
- **Delivered the production DDL bridge; ran none of it.** `docs/plans/backend-modernization/04-password-hash-not-null-ddl.sql` is a guarded `DO $$ ... $$;` block: counts `users` rows with a null `password_hash`, `RAISE EXCEPTION`s with the actual count if any exist, and otherwise runs `ALTER TABLE users ALTER COLUMN password_hash SET NOT NULL;`. Its header follows the four-part structure of the two precedent scripts, plus a `PRE-FLIGHT` section instructing the operator to run the standalone count query days ahead of the deploy window, and an explicit note disclaiming the `04-` prefix as *not* meaning Epic 4. **No SQL was executed against any database by this task — the codebase half is complete; the production database half is an explicit, flagged human pre-merge action.** Appended a matching dated note to `STATUS.md`'s decision log, including the rejected backfill-to-sentinel approach and why.
- **Discovered and filed, rather than silently fixed or dropped, a second latent hazard.** While re-verifying callers for Task 1, confirmed `UserMapper`'s `toSigninRequestDTO`/`toSignupRequestDTO` (entity-to-DTO direction) are also uncalled — and that because `UserEntity` implements `UserDetails`, `getPassword()` returns the bcrypt hash, which MapStruct's property-name matching would copy into either DTO's `password` field if either method were ever called. Filed as a new security-marked todo rather than fixed, since this task's scope was locked to the two named overloads.
- **The originating todo closed the way this repo actually closes todos.** `git mv` from `pending/` to `completed/`, confirmed as a pure 100%-similarity rename via `git show --stat` on the final commit, with no content diff — matching the established convention (commits `3405f15`, `7e1b6e1`, and this session's `260803-m2z`).

## Task Commits

Each task was committed atomically:

1. **Task 1: Delete the two dead entity-producing UserMapper overloads** - `c9615a3` (fix, tracer)
2. **Task 2: Tighten passwordHash to non-nullable and deliver the production DDL bridge** - `baab313` (fix)
3. **Task 3: Close the originating todo and file the newly discovered hash-leak finding** - `5961cba` (docs)

## Files Created/Modified

- `src/main/java/com/vrudenko/kanban_board/mapper/UserMapper.java` - Removed the two dead entity-producing overloads (`fromSigninRequestDTO`, `fromSignupRequestDTO(SigninRequestDTO)`); `SigninRequestDTO` import kept (still used by `toSigninRequestDTO`)
- `src/main/java/com/vrudenko/kanban_board/entity/UserEntity.java` - `passwordHash` column changed to `nullable = false`; comment rewritten to state current reality and point at the DDL script
- `src/test/java/com/vrudenko/kanban_board/security/UserPersistenceE2ETest.java` - Comment-only correction above the persistence assertions; zero assertion/structural changes
- `docs/plans/backend-modernization/04-password-hash-not-null-ddl.sql` - New: guarded, idempotent, NOT executed production DDL bridge
- `docs/plans/backend-modernization/STATUS.md` - New dated decision-log entry recording the script, the pre-flight rationale, and the rejected backfill approach
- `.planning/todos/completed/2026-08-03-remove-hash-less-user-mapper-overloads-and-tighten-password-hash-nullability.md` - Renamed from `pending/`, content unchanged
- `.planning/todos/pending/2026-08-03-usermapper-entity-to-request-dto-methods-leak-the-bcrypt-hash.md` - New security-marked todo
- `.planning/STATE.md` - Added the `260803-m3i` row to Quick Tasks Completed, a Decisions bullet stating the production migration is outstanding, and the new todo to Pending Todos

## Decisions Made

- Approach A (guarded `DO` block) chosen over a bare `ALTER` (B, insufficient failure signal) or backfill-to-sentinel (C, rejected outright — manufactures the exact defect being closed) — locked in the plan, executed as decided, no deviation
- `04-` prefix continues the DDL-script delivery sequence rather than inventing a quick-task-keyed filename, with the script's own header disambiguating the Epic 4 collision
- Falsification step run for real (not skipped): reverting the constraint and confirming a green suite is what proves the suite doesn't already cover this property, making the subsequent restored-state green run the actual evidence rather than a coincidence

## Deviations from Plan

**1. [Plan verification script quirk, not a code issue] Task 3's own `<automated>` verify command for the todo rename misreports as a 62-line addition rather than a 0/0 rename in this git version (2.53.0.windows.1).**
- **Found during:** Task 3, running the plan's literal verify command
- **Issue:** `git diff --cached --numstat -- .planning/todos/completed/<file>` (restricted to only the new-path side of the rename) shows `62 0 <path>` — appearing as a full addition — because restricting the pathspec to a single endpoint of a rename pair defeats git's rename pairing in this version/config. Running the equivalent command without the single-path restriction (`git diff --cached --numstat` unrestricted, or `git diff --cached --stat -M`) correctly shows `1 file changed, 0 insertions(+), 0 deletions(-)`, and `git show --stat` on the final commit confirms `rename ... (100%)` with no content diff.
- **Fix:** None to the source change — the rename is genuinely content-preserving. Documented the discrepancy here rather than silently accepting a misleading "0 0" reading from a check that, as literally written against this git version, would not have produced one.
- **Files affected:** None (verification-only finding)
- **Verification:** `git show --stat 5961cba` shows `rename .planning/todos/{pending => completed}/...md (100%)`; `git diff --cached --stat -M` (before commit) showed `0 insertions(+), 0 deletions(-)`
- **Committed in:** N/A (no code change; evidence captured in this SUMMARY)

---

**Total deviations:** 1 (verification-methodology finding, not a fix)
**Impact on plan:** None on the actual change — the todo rename is genuinely content-preserving, confirmed by the correct git invocation. No scope creep, no code affected.

## Issues Encountered

None blocking. The falsification step and the full test suite (Kafka Testcontainers E2E included) both ran to completion within the bounded timeouts used (each full `./gradlew test` invocation took ~3.5 minutes).

## User Setup Required

**External database action required before merge.** `docs/plans/backend-modernization/04-password-hash-not-null-ddl.sql` was delivered but NOT run:
1. Read the script end-to-end as the operator who will run it.
2. Run the pre-flight query `SELECT COUNT(*) FROM users WHERE password_hash IS NULL;` against the real Postgres database — ideally days before the deploy window — and report the count.
3. If the count is zero, run the full script via psql against the real database, immediately before merging/deploying this PR.
4. If the count is non-zero, STOP — do not run the script yet; each such row is an account that can never sign in, and its disposition (fix, disable, delete) is a human decision, not an automated one.

This is the one step this task explicitly cannot perform (RO-4).

## Next Phase Readiness

- The overload-resolution hazard and the nullable-hash hazard are both closed at the codebase level; the corresponding production DDL is ready to run but not yet applied
- A new security-marked todo (`2026-08-03-usermapper-entity-to-request-dto-methods-leak-the-bcrypt-hash.md`) is filed and unblocked — no dependency on this task's remaining work
- No blockers for other work. No dependency added, no request/response contract changed

---
*Quick task: 260803-m3i*
*Completed: 2026-08-03*

## Self-Check: PASSED

- FOUND: src/main/java/com/vrudenko/kanban_board/mapper/UserMapper.java
- FOUND: src/main/java/com/vrudenko/kanban_board/entity/UserEntity.java
- FOUND: src/test/java/com/vrudenko/kanban_board/security/UserPersistenceE2ETest.java
- FOUND: docs/plans/backend-modernization/04-password-hash-not-null-ddl.sql
- FOUND: docs/plans/backend-modernization/STATUS.md
- FOUND: .planning/todos/completed/2026-08-03-remove-hash-less-user-mapper-overloads-and-tighten-password-hash-nullability.md
- FOUND: .planning/todos/pending/2026-08-03-usermapper-entity-to-request-dto-methods-leak-the-bcrypt-hash.md
- FOUND: .planning/STATE.md
- FOUND: c9615a3
- FOUND: baab313
- FOUND: 5961cba
