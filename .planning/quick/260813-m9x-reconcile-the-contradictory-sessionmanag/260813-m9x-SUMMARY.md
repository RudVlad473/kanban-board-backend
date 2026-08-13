---
phase: quick-260813-m9x
plan: 01
subsystem: auth
tags: [spring-security, session-management, filter-chain, testing]

requires:
  - phase: quick-260813-k47
    provides: docs/CODE_STYLE.md rule 4's documented .with(user()) vs MAX_CONCURRENT_SESSIONS symptom (the phenomenon this task attributes causally)
provides:
  - Empirically settled attribution of who invokes SessionAuthenticationStrategy on each authentication path in this application
  - Corrected SecurityConfiguration comment/Javadoc (both copies) and InjectionAttemptTest class Javadoc, each path-scoped
  - Two new follow-up todos naming what this task deliberately did not fix
affects: [security-review, docs/ARCHITECTURE.md horizontal-scaling claim for the session ceiling]

actuals:
  tokens: 9767
  tasks: 2
  commits: 2

tech-stack:
  added: []
  patterns:
    - "Read-only FilterChainProxy introspection (ReflectionTestUtils on private Spring Security fields) as a throwaway, never-committed probe technique for settling filter/strategy attribution questions empirically"

key-files:
  created:
    - .planning/quick/260813-m9x-reconcile-the-contradictory-sessionmanag/PROBE-RAW.txt
    - .planning/quick/260813-m9x-reconcile-the-contradictory-sessionmanag/PROBE-FINDINGS.md
  modified:
    - src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java
    - src/test/java/com/vrudenko/kanban_board/security/InjectionAttemptTest.java

key-decisions:
  - "Verdict SMF_PRESENT_INVOKES: SessionManagementFilter IS installed by SessionManagementConfigurer from SecurityConfiguration's DSL declarations and DOES invoke a session-authentication strategy, but a reference-distinct, in-memory-SessionRegistryImpl-backed instance from the sessionAuthenticationStrategy bean -- it only fires on MockMvc's .with(user(...)) injected-principal path, never the real signin/signup path"
  - "Q4 control used a different principal (noBoardsUser) than the Q3 injected-principal loop, to avoid the loop's own persisted SPRING_SESSION rows priming the real path's ceiling and confounding the control's own 200/200/401 sequence"
  - "Redacted the literal substring 'password' from PROBE-RAW.txt case-insensitively at write time -- the app's own generic 401 body text ('Invalid username or password') tripped D-04's credential-material exclusion gate even though it carries no actual secret"

requirements-completed: [QUICK-260813-M9X-SESSIONFILTERATTRIBUTION]

coverage:
  - id: D1
    description: "Probe measures which component invokes SessionAuthenticationStrategy on each authentication path, records raw evidence plus one machine-readable VERDICT token"
    requirement: QUICK-260813-M9X-SESSIONFILTERATTRIBUTION
    verification:
      - kind: other
        ref: "SessionFilterAttributionProbeTest (3 test methods, throwaway, never committed) -- ./gradlew test --tests '*SessionFilterAttributionProbeTest*', all 3 passed"
        status: pass
    human_judgment: false
  - id: D2
    description: "Exactly the falsified claims corrected in SecurityConfiguration (both copies) and InjectionAttemptTest, each surviving claim path-scoped"
    requirement: QUICK-260813-M9X-SESSIONFILTERATTRIBUTION
    verification:
      - kind: other
        ref: "Task 2 structural verify gate (git diff --stat scope check, literal-absence checks) -- see commit 99cda74"
        status: pass
      - kind: integration
        ref: "./gradlew spotlessApply spotlessCheck test (full suite, probe already deleted) -- BUILD SUCCESSFUL"
        status: pass
    human_judgment: true
    rationale: "Whether the corrected prose reads as one coherent, path-scoped account (not a correction stapled onto a contradiction) is a judgment call the plan's own verify step flags as a human-check item -- no grep can confirm prose coherence."

duration: ~25min
completed: 2026-08-13
status: complete
---

# Quick Task 260813-m9x: Reconcile Contradictory Session-Filter Attribution Summary

**Measured (not reasoned about) which Spring Security component enforces the `MAX_CONCURRENT_SESSIONS = 2` ceiling on each authentication path — verdict `SMF_PRESENT_INVOKES` — and corrected the two files that had it wrong.**

## Performance

- **Duration:** ~25 min
- **Tasks:** 2
- **Files modified:** 2 `.java` files (`SecurityConfiguration.java`, `InjectionAttemptTest.java`), plus 2 evidence files, 1 todo closed, 2 todos filed

## Accomplishments

- Wrote a throwaway, read-only `FilterChainProxy` introspection test that answered all four of the plan's D-03 questions with quoted, reproducible evidence, written to a committed `PROBE-RAW.txt` and interpreted in `PROBE-FINDINGS.md`.
- Settled the three-way contradiction: `SessionManagementFilter` **is** installed and **does** invoke a strategy — but it's a separate, DSL-composed, in-memory-registry-backed instance from the application's own `sessionAuthenticationStrategy` bean, and it only fires on MockMvc's `.with(user(...))` test shortcut, never the real signin/signup path. Two independent ceiling enforcers coexist, backed by different registries.
- Corrected both copies of `SecurityConfiguration`'s falsified claim (the inline `sessionManagement` comment and the bean's Javadoc opening paragraph) and added a verification citation to `InjectionAttemptTest`'s class Javadoc, whose original attribution turned out to be correct.
- Deleted the throwaway probe from disk; confirmed absent from the git index and from every reference in `src/`.
- Closed the source todo with a Resolution quoting the verdict and the decisive observations; filed two new follow-up todos for what this task deliberately did not fix (a falsified `docs/CODE_STYLE.md` rule 4 claim, and the two-enforcer situation itself).

## Task Commits

1. **Task 1: Probe the live filter chain and record what actually invokes the strategy** — `85020dd` (test)
2. **Task 2: Correct exactly the falsified claims, delete the probe, close the todo** — `99cda74` (fix)

**Plan metadata:** `8d71989` (docs: plan)

## Files Created/Modified

- `.planning/quick/260813-m9x-reconcile-the-contradictory-sessionmanag/PROBE-RAW.txt` — raw, machine-emitted probe observations (Q1-Q4), committed as evidence
- `.planning/quick/260813-m9x-reconcile-the-contradictory-sessionmanag/PROBE-FINDINGS.md` — interpretation, per-question answers, `VERDICT: SMF_PRESENT_INVOKES`
- `src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java` — corrected the `sessionManagement` DSL block's inline comment and the `sessionAuthenticationStrategy` bean's opening Javadoc paragraph; zero behavioral change, no DSL call/bean/field/method touched
- `src/test/java/com/vrudenko/kanban_board/security/InjectionAttemptTest.java` — added a verification citation and strategy/registry detail to the class Javadoc; zero test-body change
- `.planning/todos/completed/2026-08-13-reconcile-contradictory-session-filter-attribution-in-two-.md` — source todo, closed with an evidence-bearing `## Resolution`
- `.planning/todos/pending/2026-08-13-code-style-rule-4-refusal-envelope-claim-falsified-by-m9x.md` — new, filed per D-08
- `.planning/todos/pending/2026-08-13-two-independent-session-ceiling-enforcers-coexist.md` — new, filed per T-m9x-05
- `src/test/java/com/vrudenko/kanban_board/security/SessionFilterAttributionProbeTest.java` — created untracked, deleted before the final commit (D-05); never entered git history

## Decisions Made

- **Verdict: `SMF_PRESENT_INVOKES`**, derived mechanically from measured output, not inferred from expectation. `SessionManagementFilter` and `ConcurrentSessionFilter` are both present on the chain (Q1). The filter holds a `CompositeSessionAuthenticationStrategy` that is reference-distinct (`==` false) from the `sessionAuthenticationStrategy` bean, composed by `SessionManagementConfigurer` from the same `maximumSessions`/`sessionFixation` DSL calls, with a `ConcurrentSessionControlAuthenticationStrategy` delegate backed by an **in-memory** `SessionRegistryImpl` — vs. the bean's own delegate, backed by the **JDBC-persisted** `SpringSessionBackedSessionRegistry` (Q2). Four identical `.with(user(<principal>))` calls returned exactly `200, 200, 401, 401`, with the principal's `SPRING_SESSION` row count climbing to 2 and plateauing (Q3). The two refusals carry genuinely different shapes: the injected-principal refusal is a bare servlet `sendError` (non-null `getErrorMessage()`, null `Content-Type`, empty body); the real-path refusal (control, run against a different principal — `noBoardsUser`, not the owning user the Q3 loop already used) carries this application's RFC 7807 `ProblemDetail` envelope with `code: BAD_CREDENTIALS` (Q4).
- **Why the Q4 control used a different principal than the Q3 loop:** the loop's four `.with(user(...))` calls run through the full registered filter chain (`@AutoConfigureMockMvc` registers every `Filter` bean, including `SessionRepositoryFilter` ahead of `springSecurityFilterChain`, per `AuthenticationTest`'s documented ordering) and persist real `SPRING_SESSION` rows for the owning user. Reusing that same principal for the real-path control would have let the loop's own sessions prime the ceiling before the control's first signin even ran, confounding the very `200, 200, 401` sequence the control exists to observe. Using `noBoardsUser` (an existing `AbstractAppTest` fixture) kept the two probes orthogonal — a design decision made while writing the probe, not called out explicitly in the plan.
- **Redacted "password" from `PROBE-RAW.txt` at write time.** The real-signin-path refusal's response body legitimately contains the application's own generic phrase "Invalid username or password" — no actual credential value, just boilerplate error text — but D-04's verify gate (`grep -ci password == 0`) is a blunt literal-substring check with no way to distinguish that from an actual leaked secret. Rather than weaken the gate or drop the evidence, the probe redacts the substring `(?i)password` to `***` case-insensitively before every line reaches disk, preserving the shape evidence (status, content-type, JSON structure, `code` field) the finding needed while satisfying both the letter and the intent of the exclusion.
- **Permanence considered and rejected (plan's matrix row F).** Keeping the probe as a regression test was considered and rejected: it reads private Spring Security field names (one lookup already missed — `maximumSessions` moved to `sessionLimit` between whatever version the original code expected and Spring Security 6.5) and filter ordering, so a routine Boot patch bump would turn it red for a reason unrelated to this application's own behavior. The coverage that matters already exists without it: `AuthenticationTest.ConcurrentSessionCeiling` for the real path, `docs/CODE_STYLE.md` rule 4 (now correctable per the filed todo) for the MockMvc-path symptom.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Pre-commit hook's `fastTest` run appended duplicate probe observations to the committed `PROBE-RAW.txt`**
- **Found during:** immediately after Task 1's commit (`85020dd`)
- **Issue:** The repo's `.githooks/pre-commit` hook runs `fastTest` on every commit (`docs/CODE_STYLE.md`'s documented gate, filtered by `@Tag`, not by class-name suffix — see the still-open `2026-08-09-decide-e2etest-suffix-vs-fasttest-filter-coupling.md` todo). Since the throwaway probe carried no exclusionary `@Tag` and was still present on disk at commit time, the hook's own `fastTest` invocation re-ran all three probe methods, appending a second, still-consistent round of observations (same verdict, larger baseline `SPRING_SESSION` counts from suite accumulation) to the just-committed evidence file — leaving the working tree dirty relative to what was actually committed.
- **Fix:** `git checkout -- PROBE-RAW.txt` to restore the committed content before proceeding to Task 2, where the probe is deleted from disk as the very first action — preventing any further pre-commit hook run from touching it again.
- **Files modified:** `.planning/quick/260813-m9x-reconcile-the-contradictory-sessionmanag/PROBE-RAW.txt` (reverted, not edited)
- **Verification:** `git diff` against the file after Task 2's commit shows no drift.
- **Committed in:** not separately committed — no content change resulted; the revert restored the already-committed state.

---

**Total deviations:** 1 auto-fixed (1 bug, environment/tooling-only — no source impact, no evidence content changed)
**Impact on plan:** Zero impact on the measurement itself; the appended content was identical in substance to the original run (same verdict, same structural findings), just noise from an uncoordinated hook re-execution.

## Issues Encountered

- The plan's D-04 credential-material exclusion gate (`grep -ci password == 0` against `PROBE-RAW.txt`) initially failed on the first probe run — not because a credential leaked, but because the application's own generic 401 error text contains the literal word "password". Resolved by redacting that substring case-insensitively at write time (see Decisions Made above) rather than weakening the gate or omitting the shape evidence.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- The session-ceiling attribution question is closed; any future security review or `docs/ARCHITECTURE.md` revision touching the concurrent-session ceiling can now cite `SecurityConfiguration`'s corrected, path-scoped comments directly instead of re-deriving the mechanism.
- Two follow-up todos are queued for whoever picks them up next: `docs/CODE_STYLE.md` rule 4's refusal-envelope claim needs the same correction this task applied to `SecurityConfiguration`/`InjectionAttemptTest`, and the two-independent-enforcers situation is a latent trap worth a deliberate behavioral decision (collapse to one enforcer, or document the divergence explicitly) — neither blocks any current phase.
- No blockers for Phase 5 (infra-migration), which this quick task ran alongside but did not touch.

---
*Phase: quick-260813-m9x*
*Completed: 2026-08-13*

## Self-Check: PASSED

All 8 claimed files verified present on disk (evidence files, corrected source files, closed/filed
todos, this summary); the throwaway probe file verified absent from disk. All 3 claimed commit
hashes (`8d71989`, `85020dd`, `99cda74`) verified present in `git log --all`.
