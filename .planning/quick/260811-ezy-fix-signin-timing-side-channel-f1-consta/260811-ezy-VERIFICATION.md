---
phase: quick-260811-ezy
verified: 2026-08-11T12:10:00Z
status: passed
score: 8/8 must-haves verified
behavior_unverified: 0
overrides_applied: 0
---

# Quick Task 260811-ezy: Fix Signin Timing Side-Channel (F1) Verification Report

**Task Goal:** Fix signin timing side-channel (F1): constant-time BCrypt comparison in
`AuthenticationController.signin` for the unknown-email path, per
`.planning/todos/pending/2026-08-10-signin-timing-side-channel-allows-email-enumeration.md`.

**Verified:** 2026-08-11
**Status:** passed
**Re-verification:** No — initial verification

**Merge note:** confirmed this is a post-merge check against `master` HEAD (commit `f5cd510`), not
the worktree. The fix landed at `1951b66` (`fix(260811-ezy)`) and a later, unrelated quick task
`260811-ffs` (import-group reordering) subsequently touched
`AuthenticationController.java`/`AuthenticationTest.java` for import order only, then merged at
`f5cd510`. Verified the semantic content survived that merge — see Truth 1 and the Anti-Pattern
section below.

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | A `POST /signin` with an unregistered email drives exactly one BCrypt comparison, same count as a registered email + wrong password | ✓ VERIFIED | `AuthenticationController.java:75-92` — two sequential try blocks; the narrowed `catch (AppEntityNotFoundException e)` calls `passwordEncoder.matches(dto.getPassword(), equalizerHash)` before throwing. Behaviorally proven: `SigninTimingEqualizationTest$Signin` ran with `tests="2" failures="0" errors="0"` in a fresh, verifier-run `./gradlew test` (not SUMMARY narration) — `shouldInvokeMatchesExactlyOnce_whenEmailIsUnregistered` and `shouldInvokeMatchesExactlyOnce_whenPasswordIsWrong` both pass |
| 2 | Both requests still return HTTP 401 with a byte-identical `ProblemDetail` body; `AuthenticationTest.Signin.AntiEnumeration` passes with none of its assertions edited | ✓ VERIFIED | `AuthenticationTest$Signin$AntiEnumeration` ran with `tests="2" failures="0" errors="0"` in the same fresh run. `git diff d8ff685^ HEAD -- .../AuthenticationTest.java` shows only import-line churn (from the unrelated `260811-ffs` merge) — zero non-import lines changed, confirming no assertion was edited |
| 3 | The equalizer hash is produced by the app's own configured `PasswordEncoder` bean at startup, not a source-literal | ✓ VERIFIED | `AuthenticationController.java:64-67`: `@PostConstruct private void initializeEqualizerHash() { equalizerHash = passwordEncoder.encode(EQUALIZER_PLAINTEXT); }`, using the constructor-injected `passwordEncoder` field (same bean type `UserAuthenticationProvider` injects, both sourced from `BeanConfiguration.passwordEncoder()`) |
| 4 | The new regression test was observed RED before the production change existed and GREEN after, with actual failing output, not an assertion | ✓ VERIFIED | SUMMARY quotes the verbatim RED failure (`expected: 1 but was: 0`) from a direct pre-fix `./gradlew test` run; commit `d8ff685` (test) precedes `1951b66` (fix) in history, and the verifier's own fresh run confirms GREEN post-fix |
| 5 | The new test class carries no `@Tag`, so it runs in `fastTest` / the pre-commit gate | ✓ VERIFIED | `SigninTimingEqualizationTest.java` has no `@Tag` annotation anywhere in the file (checked full source) |
| 6 | No Mockito construct is introduced — the counter is a hand-written delegating `PasswordEncoder` bean through the real Spring context | ✓ VERIFIED | `grep -niE 'mockito|@MockBean|@Mock\b'` against the test file returns no matches. `CountingPasswordEncoder` is a hand-written class forwarding every call to a real delegate, published `@Primary` via `@TestConfiguration` |
| 7 | `./gradlew spotlessCheck` and `./gradlew test` both pass | ✓ VERIFIED | Verifier ran `./gradlew test --tests '*SigninTimingEqualizationTest*' --tests '*AuthenticationTest*' spotlessCheck` fresh against post-merge HEAD: `BUILD SUCCESSFUL in 2m 21s`, `spotlessCheck UP-TO-DATE`/passing, both test classes green |
| 8 | The source todo no longer sits in `.planning/todos/pending/` | ✓ VERIFIED | `.planning/todos/pending/2026-08-10-signin-timing-side-channel-allows-email-enumeration.md` does not exist; `.planning/todos/completed/2026-08-10-signin-timing-side-channel-allows-email-enumeration.md` exists with `resolved: 2026-08-11` front matter and a `## Resolution` section |

**Score:** 8/8 truths verified (0 present, behavior-unverified)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/test/java/com/vrudenko/kanban_board/security/SigninTimingEqualizationTest.java` | New structural regression test, no mocks | ✓ VERIFIED | Exists, substantive (177 lines, real assertions), wired (runs and passes in the module's test suite) |
| `src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java` | Equalizing BCrypt comparison on unknown-email branch | ✓ VERIFIED | Exists, substantive, wired — `passwordEncoder` field injected via `@RequiredArgsConstructor`, `equalizerHash` computed at `@PostConstruct`, invoked in the narrowed `AppEntityNotFoundException` catch. Semantic content survived the later `260811-ffs` import-reorder merge intact (confirmed by direct read of the merged file) |
| `docs/ARCHITECTURE.md` | Signin sequence diagram shows the unknown-email branch | ✓ VERIFIED | `alt email not registered` / `else email registered` split present at diagram lines ~62-90, with `AC->>AC: passwordEncoder.matches(...)` and an explanatory `Note over AC` citing F1 |
| `.planning/todos/completed/2026-08-10-signin-timing-side-channel-allows-email-enumeration.md` | Todo moved with Resolution section | ✓ VERIFIED | Present, `resolved: 2026-08-11` in front matter, full `## Resolution` section covering what shipped, RED/GREEN evidence, residual asymmetry, DoS trade-off, and the Approach-B follow-up |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| `AuthenticationController`'s injected `PasswordEncoder` | `UserAuthenticationProvider`'s injected `PasswordEncoder` | Same singleton bean, `BeanConfiguration.passwordEncoder()` | ✓ WIRED | Both classes declare a plain `PasswordEncoder` field with no differing `@Qualifier`; only one `PasswordEncoder` bean (`BeanConfiguration.passwordEncoder()`, `new BCryptPasswordEncoder()`) exists in the production context, so both resolve to the same instance by construction |
| `@PostConstruct`-computed equalizer hash | `BeanConfiguration.passwordEncoder()`'s configured strength | `passwordEncoder.encode(EQUALIZER_PLAINTEXT)` at startup | ✓ WIRED | Direct call on the injected bean — confirmed no hardcoded hash literal exists in the file |
| Counting delegate's `@Primary` bean | Every `PasswordEncoder` injection point in the test's Spring context | `@TestConfiguration` + `@Primary` + `@Qualifier("passwordEncoder")` on the delegate param | ✓ WIRED | Test passed with a nonzero invocation count observed (both cases assert `.isEqualTo(1)` and pass), which is only possible if `@Primary` actually won at `UserAuthenticationProvider`'s and the controller's injection points |
| `catch (AppEntityNotFoundException)` ahead of `catch (Exception)` | Equalizing comparison fires only on unknown-email branch | Two sequential try blocks, narrowed first catch | ✓ WIRED | Source confirms narrowed catch type; `shouldInvokeMatchesExactlyOnce_whenPasswordIsWrong` passing with count exactly 1 (not 2) confirms no double-invocation on the registered-email branch |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Unknown-email signin drives exactly 1 `matches()` call | `./gradlew test --tests '*SigninTimingEqualizationTest*'` (fresh, verifier-run) | `SigninTimingEqualizationTest$Signin`: `tests="2" failures="0" errors="0"` | ✓ PASS |
| `AntiEnumeration` byte-identical-body guarantee still holds, unedited | `./gradlew test --tests '*AuthenticationTest*'` (fresh, verifier-run) | `AuthenticationTest$Signin$AntiEnumeration`: `tests="2" failures="0" errors="0"` | ✓ PASS |
| Format gate | `./gradlew spotlessCheck` (fresh, verifier-run) | `spotlessCheck` task green | ✓ PASS |
| No debt markers in touched files | `grep -nE "TBD|FIXME|XXX|TODO|HACK|PLACEHOLDER"` on `AuthenticationController.java` + `SigninTimingEqualizationTest.java` | No matches | ✓ PASS |

Note on tooling: the first two attempts hit the same transient Windows Gradle-daemon file-lock race
documented in the SUMMARY ("Unable to delete directory ... test-results ... binary" / "Gradle build
daemon has been stopped"). A `./gradlew --stop` followed by a retry produced a clean
`BUILD SUCCESSFUL in 2m 21s` — consistent with the SUMMARY's own account of this environment quirk,
not a code defect.

### Anti-Patterns Found

None. `AuthenticationController.java` and `SigninTimingEqualizationTest.java` carry no `TBD`,
`FIXME`, `XXX`, `TODO`, `HACK`, or `PLACEHOLDER` markers, no empty-implementation stubs, and no
Mockito constructs. The one genuinely notable event — a second, unrelated quick task
(`260811-ffs`) reordering imports in the same file after this task's fix landed — was checked
specifically: `git diff d8ff685^ HEAD -- AuthenticationController.java` and the equivalent diff for
`AuthenticationTest.java` both show import-line churn only; every non-import line (the `PasswordEncoder`
field, `@PostConstruct` block, the two-try-block signin structure, and all `AntiEnumeration`
assertions) is byte-identical to what Task 2/Task 1 committed.

### Requirements Coverage

Quick tasks in this project are not routed through `.planning/REQUIREMENTS.md`; the plan's own
`requirements: [QUICK-260811-ezy]` frontmatter is the task's self-contained requirement ID and is
satisfied by Truths 1-8 above.

### Human Verification Required

None. All must-haves are either structurally provable (grep/diff) or behaviorally provable (a
fresh, verifier-run test execution against post-merge HEAD), and every check above ran clean.

### Gaps Summary

No gaps. All 8 must-have truths verified against post-merge `master` HEAD. The production fix
(`AuthenticationController.java`), the regression test (`SigninTimingEqualizationTest.java`), the
architecture diagram update, and the todo closure are all present, substantive, and wired. The
later unrelated import-reordering merge (`260811-ffs`) did not disturb any semantic content of this
task's changes — confirmed by direct diff, not by trusting the merge to have been clean.

---

_Verified: 2026-08-11_
_Verifier: Claude (gsd-verifier)_
