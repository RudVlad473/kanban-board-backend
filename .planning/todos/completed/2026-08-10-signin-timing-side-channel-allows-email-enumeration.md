---
created: 2026-08-10T20:00:00.000Z
resolved: 2026-08-11
title: Signin timing side-channel allows email/account enumeration via BCrypt-computation asymmetry
area: backend
severity: security
files:
  - src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java:49
---

## Problem

`/claude-security` scan (2026-08-10, medium effort, whole-repo `attack-surface` scope, run
against phase 07.1's finished HEAD) finding **F1** (medium severity, confidence medium, 2/3
adversarial-panel votes):

`AuthenticationController.signin` (`:49`) fast-fails with no BCrypt hashing when the submitted
email is not registered (`userService.findByEmail` throws before `authenticationManager
.authenticate` ever runs), but runs a full `passwordEncoder.matches()` BCrypt comparison
(tens of milliseconds by design) whenever the email *is* registered, regardless of whether the
password is right or wrong. Both cases return an identical 401 body — `D-08`'s anti-enumeration
collapse is real and proven by `AuthenticationTest.Signin.AntiEnumeration
.shouldReturnByteIdenticalBody_whenComparingUnregisteredEmailAndWrongPasswordSignins` — but the
*response latency* differs measurably between the two cases, letting an attacker enumerate
registered emails via timing alone, with no visible difference in the response content at all.

**Not the same finding as `T-07.1-04-02`** (plan 07.1-04's threat register, D-07): that decision
accepts enumeration via *signup's* explicit 409 response *content* on a duplicate email — a
different endpoint, a different signal (response body, not timing), and an explicit, already-
recorded operator trade-off. F1 is signin's *timing*, unaddressed by that decision and not closed
by citing it.

## Why this is deferred, not fixed now

- The standard mitigation (always perform a constant-cost BCrypt comparison — e.g. hash the
  submitted password against a fixed dummy hash when the email doesn't exist, so both branches
  spend comparable time) is a real code change to a security-sensitive hot path, not a
  configuration flip, and needs its own test proving the timing gap actually closes (a flaky
  wall-clock assertion, or a statistical multi-sample approach) — nontrivial to get right, and
  risks a flaky/superficial test that looks green without actually proving anything if rushed.
- Confidence on this finding is medium (2/3 panel votes, not a unanimous 3/3), and severity is
  medium, not high — this project's `security_block_on: high` setting does not mandate fixing it
  in-phase, and it is not newly introduced by this phase (the `signin` method's fast-fail-on-
  unknown-email shape predates 07.1; this phase added `@Valid` and the ProblemDetail envelope
  around it, not the timing asymmetry itself).
- This project already accepts a related, more direct enumeration vector (D-07's signup 409) for
  its stated personal/portfolio scope. A timing side-channel is a strictly harder attack to
  exploit in practice (requires many samples, network jitter tolerance, statistical analysis) than
  a directly-readable 409 body the app already concedes.

## Solution

When picked up: hash the submitted password against a fixed, precomputed dummy BCrypt hash (any
valid hash of a constant string, computed once at startup or as a static final field) in the
branch where the email lookup fails, so both branches always pay one BCrypt comparison. Prove the
fix with either (a) a statistical test asserting the two branches' mean latency over N samples is
within a tolerance band, or (b) a simpler structural test asserting `passwordEncoder.matches()` is
invoked exactly once in both the found- and not-found-email paths (via a thin wrapper/spy, staying
within `docs/CODE_STYLE.md` rule 4's no-mocks-on-repositories constraint — a spy on the password
encoder bean specifically, not a repository mock, would need its own justification if attempted).

## Resolution

Option (b) taken — the simpler structural proof, not the statistical wall-clock alternative — via
quick task `260811-ezy`, delivered across two commits touching `AuthenticationController.java` and
one new test class:

- **What shipped:** `AuthenticationController` now injects the application's own `PasswordEncoder`
  bean and, at `@PostConstruct`, computes an equalizer BCrypt hash from a fixed internal plaintext
  constant (`equalizerHash = passwordEncoder.encode(EQUALIZER_PLAINTEXT)`). `signin` was
  restructured into two sequential try blocks: the first narrowly catches
  `AppEntityNotFoundException` from `userService.findByEmail` and performs
  `passwordEncoder.matches(dto.getPassword(), equalizerHash)` — result discarded on purpose, the
  call exists for its cost, not its answer — before throwing the same `BadCredentialsException`
  every other credential failure throws; the second try block is the original `authenticate(...)`
  flow, unchanged, so the extra comparison never doubles up on a branch that already pays one.
  Deriving the hash from the injected bean (rather than a hardcoded literal) means its BCrypt work
  factor automatically tracks whatever strength `BeanConfiguration.passwordEncoder()` configures,
  so a future strength change cannot silently reopen the channel with the opposite sign.
- **RED-then-GREEN, not merely asserted:** `SigninTimingEqualizationTest` was written and run
  *before* the production change existed.
  `shouldInvokeMatchesExactlyOnce_whenEmailIsUnregistered` failed as designed:
  `org.opentest4j.AssertionFailedError: expected: 1 but was: 0` — proving the unknown-email branch
  paid zero BCrypt cost prior to this fix.
  `shouldInvokeMatchesExactlyOnce_whenPasswordIsWrong` passed in that same RED run, proving the
  counting harness (a hand-written delegating `PasswordEncoder` wired `@Primary` through the real
  Spring context, `docs/CODE_STYLE.md` rule 4 — no mock framework) was sound before it was trusted
  to prove anything. After the production change, both cases pass, and
  `AuthenticationTest.Signin.AntiEnumeration`'s two cases pass unchanged, with zero edits to
  `AuthenticationTest.java`.
- **Residual, narrowed not closed:** the registered-email path still performs one extra indexed
  database read (`UserAuthenticationProvider`'s `loadUserByUsername` call, reached via
  `AuthenticationManager`) that the now-equalized unknown-email path does not. This is
  sub-millisecond against BCrypt's tens of milliseconds, so the dominant, remotely-measurable term
  is gone — but this is a large-constant-factor reduction, not a formal constant-time guarantee.
  Documented in the production code comment, not just here.
- **Accepted CPU cost-profile change:** unknown-email signin requests stop being cheap — a sprayed
  list of random emails now costs one full BCrypt (~50-100ms) each instead of one indexed miss.
  This is the intended symmetry (the registered-email path always cost exactly this), and no rate
  limiting exists on `POST /signin` today in either case, so the mitigation does not raise any
  existing ceiling. Recorded here so a future rate-limiting decision inherits this context rather
  than rediscovering it.
- **Provider-side equalization (Approach B) remains the architecturally better home, and was
  rejected here for blast radius, not for correctness.** Moving the mitigation into
  `UserAuthenticationProvider` (or replacing it with Spring's own `DaoAuthenticationProvider`,
  which ships this exact mitigation built in) is unreachable without restructuring:
  `authenticationManager.authenticate` is never called at all when the email is unknown, because
  `AuthenticationController` resolves email → userId first, and the principal Spring sees is the
  userId, not the email. Fixing it there means moving email resolution into the provider, which
  rewrites `loadUserByUsername`'s contract, the session-strategy call site, and signup's reuse of
  the same `authenticate` helper — an auth-path rewrite, not a quick task, with every regression it
  could cause being a security regression. Not adopted here; a future phase touching
  `UserAuthenticationProvider` should weigh it fresh rather than re-deriving this trade-off.
- **Independence from `T-07.1-04-02`/D-07 restated:** that decision accepts enumeration via
  signup's explicit 409 response *content* on a duplicate email — a different endpoint, a
  different signal (response body, not timing), and an already-recorded, separate operator
  trade-off. This resolution does not touch signup and does not supersede or fold into D-07; a
  future reader should not treat closing F1 as having also revisited that decision.
