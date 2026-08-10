---
created: 2026-08-10T20:00:00.000Z
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
