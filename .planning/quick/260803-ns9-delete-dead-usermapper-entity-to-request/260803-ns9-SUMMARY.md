---
phase: quick/260803-ns9
plan: 01
subsystem: auth
tags: [mapstruct, security, javadoc]

# Dependency graph
requires:
  - "260803-m3i: found and filed the entity-to-request-DTO hash-leak finding this task closes, while re-verifying that task's own callers"
provides:
  - "UserMapper with no method that accepts a UserEntity and returns a request DTO — the hazard is unrepresentable, not merely unreached"
  - "Class-level Javadoc invariant note (naming neither deleted method) documenting the mechanism and the required @Mapping(target = \"password\", ignore = true) escape hatch if such a method is ever genuinely needed"
  - ".planning/todos/completed/2026-08-03-usermapper-entity-to-request-dto-methods-leak-the-bcrypt-hash.md — originating todo closed"
affects: [security, mapper-layer]

actuals:
  tokens: 3243
  tasks: 2
  commits: 3

tech-stack:
  added: []
  patterns:
    - "HTML entity escape (&#064;) for a literal @ inside {@code ...} Javadoc when the surrounding text could wrap to the start of a physical comment line, avoiding accidental javadoc block-tag parsing of a code example like @Mapping(...)"

key-files:
  modified:
    - src/main/java/com/vrudenko/kanban_board/mapper/UserMapper.java
    - .planning/STATE.md
  renamed:
    - ".planning/todos/pending/2026-08-03-usermapper-entity-to-request-dto-methods-leak-the-bcrypt-hash.md -> .planning/todos/completed/2026-08-03-usermapper-entity-to-request-dto-methods-leak-the-bcrypt-hash.md"

key-decisions:
  - "Deleted both toSigninRequestDTO(UserEntity) and toSignupRequestDTO(UserEntity) outright rather than exempting with @Mapping(ignore = true) — operator's locked decision (approach A in the plan's trade-off matrix), consistent with 260803-m3i's disposition of the sibling hash-less overloads in the same file"
  - "The invariant Javadoc paragraph names neither deleted method nor SigninRequestDTO, so the acceptance-grep for both literals stays a meaningful check going forward rather than a permanent false positive"

requirements-completed: []

coverage:
  - id: D1
    description: "UserMapper declares no method that accepts a UserEntity and returns a request DTO; the generated dto.setPassword(entity.getPassword()) mapping no longer exists"
    verification:
      - kind: unit
        ref: "grep -rn 'toSigninRequestDTO' src/ -> 0 hits; grep -rn 'toSignupRequestDTO' src/ -> 0 hits (both re-run after the edit, matching the plan's combined gate)"
        status: pass
      - kind: integration
        ref: "./gradlew compileJava compileTestJava -> BUILD SUCCESSFUL in 12s"
        status: pass
    human_judgment: false
  - id: D2
    description: "The dead SigninRequestDTO import is removed; surviving members (passwordEncoder, toResponseDTO, toResponseDTOList, fromSignupRequestDTO) are byte-for-byte unchanged and in original order; the class Javadoc carries the invariant note"
    verification:
      - kind: unit
        ref: "grep -c 'SigninRequestDTO' UserMapper.java -> 0; grep -c 'SignupRequestDTO' UserMapper.java -> 2; grep -c 'toResponseDTO' UserMapper.java -> 2; grep -q 'passwordEncoder.encode(dto.getPassword())' -> match; grep -q 'UserDetails' -> match; grep -q '260803-ns9' -> match; git diff on AuthenticationController.java and dto/user_dto/ -> empty"
        status: pass
      - kind: integration
        ref: "./gradlew spotlessCheck -> BUILD SUCCESSFUL (UP-TO-DATE); full ./gradlew test -> BUILD SUCCESSFUL in 3m 20s"
        status: pass
    human_judgment: false
  - id: D3
    description: "The originating todo is closed via git mv (pending/ -> completed/), content unchanged; STATE.md records the task, prunes the closed todo from Pending Todos, and its Decisions bullet explicitly states nothing leaked"
    verification:
      - kind: integration
        ref: "git diff --cached --summary (unrestricted) -> 'rename ... (100%)'; git diff --cached --stat -> '0 insertions(+), 0 deletions(-)'; grep '260803-ns9' STATE.md -> match; grep 'todos/pending/2026-08-03-usermapper...' STATE.md -> 0 hits"
        status: pass
    human_judgment: false

duration: ~15min
completed: 2026-08-03
status: complete
---

# Quick Task 260803-ns9: Delete Dead UserMapper Entity-to-Request DTO Methods Summary

**Deleted `UserMapper.toSigninRequestDTO(UserEntity)` and `toSignupRequestDTO(UserEntity)` — two uncalled methods MapStruct compiled into `dto.setPassword(entity.getPassword())`, copying the stored bcrypt hash into a request DTO — and closed the originating security-marked todo. Zero callers existed, so nothing leaked; this makes the hazard unrepresentable rather than merely unreached.**

## Performance

- **Duration:** ~15 min
- **Tasks:** 2 (Task 1 tracer, Task 2 auto)
- **Files modified:** 2 modified, 1 renamed

## Accomplishments

- **Re-verified zero callers by fresh grep BEFORE any edit, not inherited from any prior task's claim.** Ran `grep -rn 'toSigninRequestDTO\|toSignupRequestDTO' src/` first. Actual output:
  ```
  src/main/java/com/vrudenko/kanban_board/mapper/UserMapper.java:29:    public abstract SigninRequestDTO toSigninRequestDTO(UserEntity entity);
  src/main/java/com/vrudenko/kanban_board/mapper/UserMapper.java:31:    public abstract SignupRequestDTO toSignupRequestDTO(UserEntity entity);
  ```
  Exactly two hits, both declaration lines — matching the plan's expected result exactly. No live caller existed anywhere in `src/main` or `src/test`, so this was a dead-code deletion, not a behaviour change.
- **Read the current file before editing and confirmed it matched the plan's assumptions.** `UserMapper` declared exactly five members: `passwordEncoder`, `toResponseDTO`, `toResponseDTOList`, the two doomed methods, and the single `@Mapping`-annotated `fromSignupRequestDTO(SignupRequestDTO)` hashing overload. No surprise state.
- **Deleted exactly the two targeted method declarations**, cleanly removing surrounding blank lines. `passwordEncoder`, `toResponseDTO`, `toResponseDTOList`, and `fromSignupRequestDTO` survive byte-for-byte, same text, same order.
- **Removed the now-unused `SigninRequestDTO` import** in the same edit — nothing else in the file referenced that type after the deletion. The `SigninRequestDTO` class itself, `AuthenticationController.signin`'s use of it as `@RequestBody`, and all four test classes that build it are untouched (`git diff` on both is empty).
- **Added a forward-looking class-Javadoc invariant paragraph** naming neither deleted method nor `SigninRequestDTO` by name (per the plan's hard constraint, so the acceptance grep stays meaningful): it explains that `UserEntity` implements `UserDetails` (so `getPassword()` returns the bcrypt hash), that request DTOs declare a matching `password` property, that `unmappedTargetPolicy = ReportingPolicy.IGNORE` would silently map the two by name, and that any future equivalent method must carry an explicit `@Mapping(target = "password", ignore = true)`. Used the `&#064;Mapping(...)` HTML-entity escape for the literal `@` inside the `{@code ...}` block, since a bare `@Mapping` landing at the start of a wrapped Javadoc comment line risks being misparsed as an (unknown) block tag.
- **Ran the plan's exact combined verification gate.** All grep/structural checks passed on the first pass (`toSigninRequestDTO`/`toSignupRequestDTO` → 0 hits in `src/`; `SigninRequestDTO` → 0, `SignupRequestDTO` → 2, `toResponseDTO` → 2 in `UserMapper.java`; `passwordEncoder.encode(dto.getPassword())`, `UserDetails`, and `260803-ns9` all present; `AuthenticationController.java` and `dto/user_dto/` diffs empty). `./gradlew compileJava compileTestJava` → `BUILD SUCCESSFUL`. `./gradlew spotlessCheck` → `BUILD SUCCESSFUL`. Full `./gradlew test` (bounded, ran to completion) → `BUILD SUCCESSFUL in 3m 20s`.
- **Closed the todo by the project's actual convention**: `git mv .planning/todos/pending/2026-08-03-usermapper-entity-to-request-dto-methods-leak-the-bcrypt-hash.md .planning/todos/completed/...` with zero content changes — confirmed a 100%-similarity rename via `git diff --cached --summary` (unrestricted) and `git diff --cached --stat`.
- **Updated STATE.md**: appended a `260803-ns9` row to Quick Tasks Completed, added a `[Quick/260803-ns9]` Decisions bullet that explicitly states nothing leaked (zero callers throughout), removed the `[security]` bullet referencing the now-closed todo from Pending Todos, and refreshed `last_activity_desc`/`last_updated` only.

## Task Commits

Each task was committed atomically:

1. **Task 1: Delete both entity-to-request-DTO mappers, drop the dead import, record the invariant** - `e500858` (fix, tracer)
2. **Task 2: Close the originating todo and record the task in STATE.md** - `1d05a44` (docs) + `9d1b57e` (docs)

`9d1b57e` exists because the first attempt at Task 2's `git add` used a combined command that included the (already-renamed-away) pending-path pathspec; git aborted the whole `add` with a fatal pathspec error before staging `STATE.md`, so `1d05a44` captured only the todo rename. Caught during Task 2's own verification, fixed with a second real commit rather than an amend — see Deviations below.

## Files Created/Modified

- `src/main/java/com/vrudenko/kanban_board/mapper/UserMapper.java` - Removed `toSigninRequestDTO(UserEntity)`, `toSignupRequestDTO(UserEntity)`, and the `SigninRequestDTO` import; added the class-Javadoc invariant paragraph
- `.planning/todos/completed/2026-08-03-usermapper-entity-to-request-dto-methods-leak-the-bcrypt-hash.md` - Renamed from `pending/`, content unchanged
- `.planning/STATE.md` - Added the `260803-ns9` row to Quick Tasks Completed, a Decisions bullet, pruned the closed todo from Pending Todos, refreshed `last_activity_desc`/`last_updated`

## Decisions Made

- Deleted both methods outright (plan's approach A) rather than exempting them with `@Mapping(ignore = true)` (approach B) — the operator's locked decision, consistent with `260803-m3i`'s disposition of the sibling hash-less overloads in the same file
- The invariant Javadoc paragraph is phrased as a forward-looking rule ("must carry an explicit ignore mapping if ever added"), not a description of the current file's contents, so it cannot become false through an unrelated later edit — and it names neither deleted method nor `SigninRequestDTO`, so it doesn't poison the acceptance grep with the exact literals the grep searches for

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Split Task 2's commit in two after a combined `git add` silently under-staged STATE.md**
- **Found during:** Task 2, immediately after committing `1d05a44` and re-running `git status`
- **Issue:** `git add <completed-todo-path> <pending-todo-path> .planning/STATE.md` was run as one command; the second pathspec (the already-vacated `pending/` path) matched nothing, and git's `add` aborts the entire invocation on any unmatched pathspec rather than staging the valid ones. The subsequent `git commit` therefore captured only the todo rename (`0 insertions(+), 0 deletions(-)`) and left `STATE.md` unstaged.
- **Fix:** Re-ran `git add .planning/STATE.md` alone, reviewed the staged diff (matched the plan's exact instructions), and created a second, separate commit (`9d1b57e`) rather than amending `1d05a44` — per the git safety protocol's "always create new commits, never amend unless explicitly requested."
- **Files modified:** `.planning/STATE.md` (already correct content; only the staging/commit sequencing was fixed)
- **Verification:** `git status --short` after `9d1b57e` shows only the pre-existing unrelated `.planning/config.json` change remaining
- **Committed in:** `9d1b57e`

**2. [Verification-methodology finding, not a code issue] Task 2's own `<automated>` verify command, as literally written, misreports the todo rename as a 43-line addition instead of a `0 0` rename in this git version (2.53.0.windows.1)**
- **Found during:** Task 2, running the plan's literal combined verify gate
- **Issue:** `git diff --cached --numstat -- .planning/todos/completed/<file>` — restricted to only the new-path side of the rename — shows `43 0 <path>` because scoping the pathspec to a single endpoint of a rename pair defeats git's rename pairing in this environment. This same discrepancy was already documented and worked around by `260803-m3i`'s SUMMARY for the identical pattern.
- **Fix:** None to the source change — the rename is genuinely content-preserving. Confirmed with the equivalent unrestricted command: `git diff --cached --numstat` (no pathspec) correctly shows `0	0	.planning/todos/{pending => completed}/2026-08-03-usermapper-entity-to-request-dto-methods-leak-the-bcrypt-hash.md`, and `git diff --cached --summary` shows `rename ... (100%)`.
- **Files affected:** None (verification-only finding)
- **Verification:** `git diff --cached --numstat` (unrestricted) and `git diff --cached --summary`, both run before commit
- **Committed in:** N/A (no code change; evidence captured in this SUMMARY)

---

**Total deviations:** 2 (1 auto-fixed staging mistake, 1 verification-methodology finding — not a code issue)
**Impact on plan:** None on the actual change. The todo rename is genuinely content-preserving; STATE.md's final content matches the plan's exact instructions. No scope creep.

## Issues Encountered

None blocking. The full `./gradlew test` run (Kafka Testcontainers E2E included) completed within the bounded timeout used (3m 20s).

## Falsification / Evidence (per plan's explicit requirement)

A green `./gradlew test` proves nothing about this specific change — the deleted methods had zero callers, so the suite was always going to be green with or without them. **This SUMMARY does not claim "tests confirm the leak is closed."** The actual evidence chain, exactly as the plan requires:

1. The fresh pre-edit grep (`grep -rn 'toSigninRequestDTO\|toSignupRequestDTO' src/`, quoted above with its actual output) proves no caller existed before the deletion.
2. `./gradlew compileJava compileTestJava` (`BUILD SUCCESSFUL in 12s`) proves MapStruct still generates a valid `UserMapperImpl` and nothing was orphaned.
3. The green full suite proves only that nothing adjacent broke — not that a leak was closed. There were no callers; the removed thing was a possibility, not an event.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- The entity-to-request-DTO hash-copy hazard is closed at the codebase level, by construction (no method exists to call), with a forward-looking invariant note guarding against silent regrowth
- The originating security-marked todo is closed; `Pending Todos` in STATE.md no longer references it
- No blockers for other work. No dependency added, no request/response contract changed, no database change

---
*Quick task: 260803-ns9*
*Completed: 2026-08-03*

## Self-Check: PASSED

- FOUND: src/main/java/com/vrudenko/kanban_board/mapper/UserMapper.java
- FOUND: .planning/todos/completed/2026-08-03-usermapper-entity-to-request-dto-methods-leak-the-bcrypt-hash.md
- CONFIRMED GONE: .planning/todos/pending/2026-08-03-usermapper-entity-to-request-dto-methods-leak-the-bcrypt-hash.md
- FOUND: .planning/STATE.md
- FOUND: e500858
- FOUND: 1d05a44
- FOUND: 9d1b57e
