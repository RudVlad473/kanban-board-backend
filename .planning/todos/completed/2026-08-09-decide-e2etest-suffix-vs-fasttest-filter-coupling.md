---
created: 2026-08-09T16:10:00.000Z
resolved: 2026-08-10
resolves_phase: 07.1
title: Decide the E2ETest suffix vs. fastTest filter coupling
area: testing
severity: minor
files:
  - build.gradle
  - src/test/java/com/vrudenko/kanban_board/BoardFullReadE2ETest.java
  - src/test/java/com/vrudenko/kanban_board/ColumnDeletionE2ETest.java
  - src/test/java/com/vrudenko/kanban_board/ColumnOrderingE2ETest.java
  - src/test/java/com/vrudenko/kanban_board/SubtaskLockingE2ETest.java
  - src/test/java/com/vrudenko/kanban_board/TaskOrderingE2ETest.java
  - src/test/java/com/vrudenko/kanban_board/ThemePersistenceE2ETest.java
  - src/test/java/com/vrudenko/kanban_board/e2e/activity/ActivityReadE2ETest.java
  - src/test/java/com/vrudenko/kanban_board/e2e/column/ColumnLockingE2ETest.java
  - src/test/java/com/vrudenko/kanban_board/e2e/task/TaskLockingE2ETest.java
  - src/test/java/com/vrudenko/kanban_board/e2e/task/TaskMoveE2ETest.java
  - src/test/java/com/vrudenko/kanban_board/security/AuthenticationE2ETest.java
  - src/main/java/com/vrudenko/kanban_board/security/UserAuthenticationProvider.java
---

## Problem

`build.gradle`'s `fastTest` task filters by the class-name pattern `*E2ETest` (`filter {
excludeTestsMatching '*E2ETest' }`), and `.githooks/pre-commit` runs `fastTest` as the local gate on
every commit. That means the `E2ETest` suffix is not merely descriptive — it is load-bearing build
configuration. Renaming a class to (or away from) that suffix silently changes what runs before
every future commit.

Phase 7 (`.planning/phases/07-restructure-test-folder-separate-setup-from-tests-evaluate-n/`)
downgraded 12 `*E2ETest`-suffixed classes (listed in `files:` above, plus the classes merged into
`AuthenticationE2ETest`) from the real-socket RestAssured/`RANDOM_PORT` tier to the in-process
`@SpringBootTest` + `MockMvc` tier. Every one of them kept the `E2ETest` suffix, since renaming was
explicitly out of this phase's scope (D-04's boundary; see 07-07-PLAN.md's tradeoffs section,
Approach B). The practical result: these 12 classes no longer need a real socket or Testcontainers
Kafka, but they are still excluded from `fastTest` and therefore never run as part of the
pre-commit gate — only on the full `./gradlew test` / CI run.

`build.gradle`'s own comment on `fastTest` is also already imprecise, independently of this phase:
it describes the filter as excluding "Testcontainers-backed E2E classes" (`build.gradle:178`), but
since H2 was dropped in Phase 04.2, every test in the suite — `E2ETest`-suffixed or not — already
starts a real Testcontainers PostgreSQL instance. What the filter actually excludes today is
real-socket and real-Kafka classes specifically, not "Testcontainers-backed" classes in general.
After this phase, it also excludes 12 classes that are neither real-socket nor Kafka-dependent —
they simply still carry the suffix.

The naming question and the filter question have to be decided together: renaming any of the 12
classes to drop the suffix would silently pull them into the pre-commit gate (12 additional
container-backed classes on every `git commit`), which is a developer-workflow change no existing
decision in this project authorizes on its own.

**Small adjacent finding, folded in here rather than filed separately:** while downgrading the
auth/session classes (phase plan 07-02), the executor found a stale class-name reference in a
production-code comment — `src/main/java/com/vrudenko/kanban_board/security/UserAuthenticationProvider.java:38`
says "Enforced by SessionPersistenceE2ETest.SigninPersistence#shouldNotPersistBcryptHash_whenSigninSucceeds",
but that class was deleted in 07-02 and merged into `AuthenticationE2ETest.SigninPersistence` (same
method name). Deliberately left unedited by 07-02 and by this phase's own plan 07-07, since both are
locked to zero `src/main/java` changes. This is a one-line comment fix, genuinely trivial, and can
be picked up as a drive-by whenever this todo (or any other change touching that file) is executed.

## Solution

Decide, at minimum, between these options — framed here so the decision doesn't have to re-derive
the evidence above:

1. **Rename the 12 classes to drop the `E2ETest` suffix, and let `fastTest`'s existing filter widen
   naturally.** Pro: the suffix goes back to meaning "needs a real socket or Kafka," and these 12
   classes join the pre-commit gate. Con: this is a real, unreviewed developer-workflow change —
   every future `git commit` now runs 12 additional Testcontainers-backed classes locally — and it
   has to happen in the same change as the rename, not as an incidental side effect.
2. **Rename the 12 classes and retarget `fastTest`'s filter to something explicit, such as a JUnit 5
   `@Tag` on the classes that still need a real socket or Kafka**, rather than relying on a
   class-name substring match. Pro: decouples the exclusion mechanism from naming entirely, so a
   future rename can never again silently change what the pre-commit hook runs. Con: more moving
   parts (a new annotation convention to document and enforce) for a marginal readability gain over
   option 1.
3. **Keep both the naming and the filter exactly as they are, and only correct `build.gradle`'s
   stale comment** (drop "Testcontainers-backed," describe the filter as excluding real-socket and
   real-Kafka classes specifically). Pro: zero workflow change, zero risk. Con: the `E2ETest` suffix
   stays misleading on 12 classes indefinitely, and a future reader has to already know this todo
   exists to understand why.

Also fix the one-line stale `SessionPersistenceE2ETest` → `AuthenticationE2ETest` reference in
`UserAuthenticationProvider.java:38` as part of whichever option is chosen (or as a standalone
trivial edit if this todo is picked up purely for that line).

## Resolution

**Option 2 taken** (rename + retarget `fastTest` to JUnit 5 `@Tag`), delivered as
`07.1-07-PLAN.md` across three tasks:

- **Task 1** added `@Tag("kafka")` to the 8 Kafka-backed `activitylog/` classes and
  `@Tag("realSocket")` to `BoardCreationE2ETest`, then retargeted `fastTest` from
  `filter { excludeTestsMatching '*E2ETest' }` to
  `useJUnitPlatform { excludeTags 'kafka', 'realSocket' }` — matching the `rehearsal` tag
  precedent the `test` task already used. Both halves landed in the same commit; the old
  name filter was deleted outright, not left alongside the new mechanism.
- **Task 2** renamed all 11 in-process classes (the live count was 11, not the 12 estimated
  when this todo was filed — re-derived by grep at execution time rather than trusted from
  either this todo or CONTEXT.md) from `*E2ETest` to `*Test`, now safe because gate
  membership is tag-driven rather than name-driven.
- **Task 3** fixed the stale `UserAuthenticationProvider.java` comment (now citing
  `AuthenticationTest.SigninPersistence#shouldNotPersistBcryptHash_whenSigninSucceeds`,
  verified to exist and run standalone via `./gradlew test --tests`), and proved the tag
  mechanism has teeth: temporarily tagging the 18-test `AuthenticationTest` class dropped
  `fastTest`'s count from 266 to 248 (exactly 18), and removing the tag restored 266 —
  confirming a typo in either half of the mechanism would be caught by count, not merely by
  a green build.

Option 2 was picked over Option 1 (rename only, let the untouched name filter widen)
specifically because Option 1 reintroduces the same defect under a new name — the next tier
change would hit an identical silent-coupling trap. Option 3 (comment fix only) was rejected
because it leaves the 11 classes' names actively misleading indefinitely.
