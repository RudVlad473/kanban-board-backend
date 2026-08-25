---
phase: 10-ci-deploy-hardening
plan: 06
subsystem: docs
tags: [readme, documentation, architecture-showcase, mermaid, ci-cd-docs]

# Dependency graph
requires:
  - phase: 10-ci-deploy-hardening (plans 01-05)
    provides: "the settled, checkpoint-resolved state of every CI gate, digest pin, and cookie flag this plan describes — sequenced last (wave 3) so nothing described here was later reverted at a checkpoint"
provides:
  - "README.md restructured as a production-reality-first architecture showcase (HARDEN-08)"
affects: [documentation, newcomer-onboarding]

# Actuals (#2632) — pairs with the plan's `estimate` to calibrate future estimates.
actuals:
  tokens: 5661
  tasks: 3
  commits: 1

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Embed exactly one Mermaid diagram inline (GitHub-native rendering), cite its canonical .mmd source path directly beneath it, and link every other diagram rather than embedding it — a maintenance trade-off accepted explicitly (D-02/T-10-34)"

key-files:
  created: []
  modified:
    - README.md

key-decisions:
  - "Confirmed D-02's diagram choice (infra-physical-deployment.mmd over infra-delivery-scenario.mmd) empirically rather than trusting the plan's pre-recorded choice: rendered both locally with mmdc, and the chosen diagram's SVG viewBox (536x1526) is roughly 4.5x narrower and 1.7x shorter than the alternative's (2333x2568) -- confirms the plan's own 32-vs-52-source-line reasoning at the rendered-output level, not just the source level."
  - "Engineering highlights section kept immediately after 'What this is' and before the new Production deployment section (Claude's discretion, CONTEXT.md D-03 note) -- it is the five-bullet elevator pitch, not testing-architecture depth, so keeping it near the top does not violate D-03's 'production reality ahead of testing depth' ordering constraint."
  - "Nonprod environment description sourced from Phase 8/9 SUMMARYs and docs/INFRA_RUNBOOK.md's dated 'Nonprod bring-up' section, not docs/INFRA_ARCHITECTURE.md -- that file was written before Phase 8 and, confirmed by grep, contains zero mentions of nonprod."
  - "Test count re-derived as 424 (grep count of @Test/@ParameterizedTest annotations across src/test/java, 2026-08-19), not a ./gradlew test-reported figure -- both are plan-sanctioned methods; the static count was chosen to avoid an extra multi-minute Testcontainers run purely for a number already obtainable in seconds, and the method is recorded inline in the README's Testing section for reproducibility."

requirements-completed: [HARDEN-08]

coverage:
  - id: D1
    description: "Diagram choice confirmed against both candidate sources and proven to render (locally, syntactically) before embedding; every fact the README asserts (test count, gate presence, nonprod shape) is measured from the repository, not carried over from the prior README"
    requirement: HARDEN-08
    verification:
      - kind: other
        ref: "mmdc (local Mermaid CLI) rendered both docs/diagrams/infra-physical-deployment.mmd and infra-delivery-scenario.mmd to SVG with zero errors; viewBox comparison (536x1526 vs 2333x2568) confirms the chosen diagram is far more compact, consistent with the plan's own reasoning"
        status: pass
      - kind: other
        ref: "grep -rcE '@(Test|ParameterizedTest)\\b' src/test/java --include=*.java summed to 424 (re-derived 2026-08-19, superseding the stale 382 figure)"
        status: pass
      - kind: other
        ref: "grep -cE 'trufflehog' .github/workflows/secret-scan.yml -> 8 (real hard-gated job, not comment noise); test -f gradle/verification-metadata.xml -> PRESENT; grep -cE 'wrapper-validation' on both deploy.yml and security-scan.yml -> 1 each; grep -cE 'appleboy/(scp|ssh)-action@[0-9a-f]{40}' deploy.yml -> 5; Secure cookie property -> true in both application.properties and application-test.properties"
        status: pass
    human_judgment: false
  - id: D2
    description: "README.md restructured per D-01 through D-04, full cover-list coverage, link integrity, and gate-accuracy verified against the live repository"
    requirement: HARDEN-08
    verification:
      - kind: other
        ref: "grep -c '^```mermaid' README.md -> 1; wc -l README.md -> 269 (from 130); grep -c '^## ' README.md -> 12 (>= 8); all 12 cover-list probes (CI/CD, pre-commit, Spotless, Error Prone, JaCoCo, dependency-check, gitleaks, Testcontainers, docs/diagrams, Local, Stack, nonprod) >= 1; production/deployment section (line 36) precedes Testing (line 200); all 13 relative link targets confirmed to exist via ls; all 5 [how](...) highlight links, the API table, and the 'Not done, deliberately' paragraph preserved verbatim; Stack table kept its original 10 rows, each gaining exactly one rationale sentence"
        status: pass
    human_judgment: false
  - id: D3
    description: "Newcomer read-through, GitHub-rendered diagram check, and D-01 middle-ground calibration"
    requirement: HARDEN-08
    verification:
      - kind: other
        ref: "Orchestrator confirmed with a real headless browser (Playwright) against the live merged page (github.com/RudVlad473/kanban-board-backend#production-deployment): scrolled the mermaid section into view and screenshotted it directly. Diagram renders cleanly -- all nodes, edge labels, and %%{init}%% styling present, no error box. Superseded an earlier WebFetch-based false negative (WebFetch never executes JS, and GitHub's Mermaid rendering is client-side via an iframe to viewscreen.githubusercontent.com, so a non-JS fetch structurally cannot observe the true render state)."
        status: pass
    human_judgment: true
    rationale: "Task 3 (checkpoint:human-verify, gate=\"blocking\", not \"blocking-human\") was auto-approved by the executor, provisionally, matching this phase's established pattern (10-01 Task 4, 10-04 Task 3). This isolated worktree agent had no browser to visually confirm GitHub's Mermaid rendering itself -- it substituted local mmdc syntax validation and a public gist. The orchestrator then closed the gap for real post-merge with an actual browser, rather than accepting the auto-approval as final -- the same pattern applied to 10-04's Task 3. The D-01 calibration and D-03 ordering were reasoned through structurally (file grew 130->269 lines across 12 short-to-medium sections broken up by six tables, not a wall of prose; production/deployment leads at line 36, testing depth follows at line 200) and the D-04 stack rationale lines were spot-checked against the plan's own bar for informativeness. A full newcomer prose read-through remains a nice-to-have, not re-verified here since nothing about it is correctness- or security-sensitive."

# Metrics
duration: ~25min
completed: 2026-08-19
status: complete
---

# Phase 10 Plan 06: README Architecture Showcase Summary

**README.md restructured from a 130-line trimmed front door into a 269-line, 12-section production-reality-first architecture showcase — one embedded, locally-validated Mermaid diagram, a per-row-rationale Stack table, and a Quality & security gates section whose every claim was grepped against the live repository (TruffleHog present, verification-metadata.xml present, 5 digest-pinned appleboy sites, Secure cookie true in both profiles) rather than carried over from memory.**

## Performance

- **Duration:** ~25 min
- **Tasks:** 3 of 3 (Task 3 was a checkpoint, auto-approved per auto mode — see Deviations)
- **Files modified:** 1 (`README.md`)

## Accomplishments

- Confirmed the D-02 diagram choice (`infra-physical-deployment.mmd`) empirically: rendered both
  candidate `.mmd` sources locally with `mmdc`, both syntactically valid, and the chosen one's
  rendered SVG (`viewBox="0 15 536.2 1526.4"`) is roughly 4.5x narrower and 1.7x shorter than the
  alternative's (`viewBox="-50 -10 2333.5 2568"`) — the plan's own "32 vs 52 source lines" reasoning
  holds at the rendered-output level too.
- Pushed a public gist (https://gist.github.com/RudVlad473/9025cd6915dea13fd3a1723f003833fc)
  carrying the exact fenced Mermaid block, for direct human confirmation of GitHub-native rendering
  — this isolated worktree agent has no browser to perform that confirmation itself.
- Re-derived every factual claim the README makes, rather than editing the prior numbers by guess:
  424 annotated test methods (up from a stale 382), TruffleHog present and hard-gated (not
  comment-only), `gradle/verification-metadata.xml` present, `gradle/actions/wrapper-validation`
  present in both `deploy.yml` and `security-scan.yml`, 5 digest-pinned `appleboy/*` call sites,
  `server.servlet.session.cookie.secure=true` in both Spring profiles, and OWASP `dependency-check`
  confirmed report-only (`failBuildOnCVSS = 11`, weekly schedule).
- Restructured `README.md` to lead with production reality: `What this is` → `Engineering
  highlights` → `Production deployment` (the embedded diagram, plus a new nonprod paragraph the
  prior README never mentioned at all) → `CI/CD pipeline & deploy strategy` → `Quality & security
  gates` → `Stack` (now with inline rationale) → `API` → `Testing` → `Local development` →
  `Diagrams` → `Project status` → `Documentation`.
- Sourced the nonprod environment's shape from Phase 8/9 SUMMARYs and
  `docs/INFRA_RUNBOOK.md`'s dated "Nonprod bring-up" section — confirmed by grep that
  `docs/INFRA_ARCHITECTURE.md` (written before Phase 8) contains zero mentions of nonprod, so
  that was not a usable source despite the plan's read_first list naming it as a candidate.
  Also expanded the Documentation index with `INFRA_ARCHITECTURE.md`, `INFRA_RUNBOOK.md`, and
  `DIAGRAM_CONVENTIONS.md`, none of which the prior README's index listed.
- File grew from 130 to 269 lines with 12 `##` headings and six tables (API, Stack, two new
  gate/diagram tables) — the D-01 "middle ground" between the trimmed version and a full reversal.

## Task Commits

Each task was committed atomically:

1. **Task 1: Choose and validate the embedded diagram, confirm the facts** — no code commit (investigation-only; its output is the diagram choice and measured facts folded into Task 2's edit)
2. **Task 2: Restructure README.md as a production-reality-first architecture showcase** — `d5f145c` (docs)

Task 3 (`type="checkpoint:human-verify"`, `gate="blocking"`) auto-approved per
`workflow.auto_advance=true` — see Deviations below.

## Files Created/Modified

- `README.md` — Restructured from 130 to 269 lines: production-reality-first ordering, one
  embedded Mermaid diagram citing its canonical source, per-row Stack rationale, a new Quality &
  security gates section, re-derived test count, and an expanded documentation index
- `.planning/REQUIREMENTS.md` — HARDEN-08 marked complete (checkbox and traceability table)

## Decisions Made

- Confirmed D-02's diagram choice empirically rather than trusting the plan's pre-recorded decision
  — both candidates rendered locally, and the size comparison independently supports
  `infra-physical-deployment.mmd`.
- Kept "Engineering highlights" immediately after "What this is," ahead of the new "Production
  deployment" section — Claude's discretion per CONTEXT.md; it's a five-bullet elevator pitch, not
  testing-architecture depth, so it does not conflict with D-03's ordering constraint.
- Sourced nonprod's shape from Phase 8/9 SUMMARYs and `docs/INFRA_RUNBOOK.md` rather than
  `docs/INFRA_ARCHITECTURE.md`, after confirming by grep that the latter predates nonprod entirely.
- Re-derived the test count via a static `@Test`/`@ParameterizedTest` grep (424) rather than a live
  `./gradlew test` run, to avoid a multi-minute Testcontainers run for a number obtainable in
  seconds — both methods are explicitly sanctioned by the plan, and the method used is recorded
  inline in the README for reproducibility.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] "Error Prone" grep target would have failed — README used the code-styled `ErrorProne` (no space) everywhere**
- **Found during:** Task 2, running the plan's own cover-list verification loop
- **Issue:** The plan's `<verify>` block checks `grep -ci 'Error Prone' README.md` (with a space) as one of the cover-list probes requiring a count `>= 1`. The first draft only used the literal Gradle plugin/task name `ErrorProne` (no space, matching `docs/ARCHITECTURE.md`'s own existing usage) in the Stack table and the Quality & security gates table, so the space-separated probe printed `0`.
- **Fix:** Added a prose mention of "Error Prone" (with space, as the tool is conventionally named in English) in the Stack table's Language/runtime rationale cell and reworded the gates table's Build-gates row to `Spotless / Error Prone (` + `ErrorProne` + ` plugin)`, keeping the exact plugin/task name intact for anyone grepping the codebase while also satisfying the natural-language form the plan's own checklist expects.
- **Files modified:** `README.md`
- **Verification:** `grep -ci 'Error Prone' README.md` now prints `2`.
- **Committed in:** `d5f145c`

---

**Total deviations:** 1 auto-fixed (Rule 1, a verify-script-target correction with no loss of technical accuracy).
**Impact on plan:** None on scope or intent — the fix only adjusted wording to satisfy a literal grep target the plan itself specified, while keeping the technically-correct tool name (`ErrorProne`) intact everywhere it already appeared.

## Issues Encountered

**Task 1's "view rendered on GitHub" requirement could not be performed by this isolated worktree
agent — no browser tool is available.** What was done instead, and why it's a reasonable substitute
short of the literal instruction:
- `mmdc` (a local Mermaid CLI already present on this machine) rendered both candidate `.mmd` files
  to SVG with zero errors, confirming the syntax is at minimum valid Mermaid and giving comparable
  rendered dimensions for the D-02 size argument.
- A public gist (https://gist.github.com/RudVlad473/9025cd6915dea13fd3a1723f003833fc) was created
  carrying the exact fenced `mermaid` block that now lives in `README.md`, so a human (or the
  orchestrator, or a future session with browser access) can open it and confirm GitHub's real
  renderer accepts the `%%{init}%%` directive, the nested subgraphs, the invisible-link spacer, and
  the inline `style` statement without an error box — the one failure mode the plan calls out as
  strictly worse than no diagram at all.
- The plan's own Task 3 (this plan's checkpoint) independently re-asks this exact question
  ("View the README rendered on GitHub... If you see a raw code block or an error box, that is a
  blocking defect") as a human-verify step, so the literal on-GitHub confirmation is not skipped
  entirely — it is deferred to that step, which is the plan's own designated place for it.

**Task 3's checkpoint was auto-approved by the executor** (provisional, per the same pattern as
10-01 Task 4 and 10-04 Task 3), then genuinely closed by the orchestrator after merge with a real
headless browser (Playwright) — not left on the auto-approval.

**On-GitHub Mermaid render confirmed, and it revealed a real methodology gap along the way.** A
first check via `WebFetch` (which converts fetched HTML to markdown and never executes
JavaScript) reported the diagram as unrendered raw code — a false negative: GitHub's Mermaid
rendering is client-side (a `<section data-type="mermaid">` placeholder that JS enriches via an
iframe to `viewscreen.githubusercontent.com`), so no non-JS-executing fetch could ever observe
the true rendered state, correct or not. Re-checked with an actual headless browser
(`https://github.com/RudVlad473/kanban-board-backend#production-deployment`), scrolled the
section into view, and screenshotted it directly: the diagram renders cleanly and legibly — all
nodes (Browser/API client, the Netcup VPS trust boundary containing caddy/app/redpanda
subgraphs, the Neon Postgres node), edge labels, and the `%%{init}%%` styling all present exactly
as authored, no error box. Two `net::ERR_NAME_NOT_RESOLVED` console errors were present but are
GitHub's own unrelated analytics collector (`collector.github.com`) being blocked in this
environment, not the Mermaid rendering path.

## User Setup Required

None. The diagram-render checkpoint is fully closed (not deferred) — confirmed with a real
browser against the live GitHub page, not the gist substitute. A genuine newcomer read-through of
the merged README's prose remains a nice-to-have, not a blocker; nothing about it is
security- or correctness-sensitive.

## Next Phase Readiness

- Ready for merge into wave 3, the final wave of Phase 10. This is the last plan in the phase per
  `depends_on: ["10-01", "10-02", "10-03", "10-04", "10-05"]`.
- HARDEN-08 marked complete in `.planning/REQUIREMENTS.md`. All eight HARDEN-* requirements are now
  complete, closing out Phase 10 (CI & Deploy Hardening) pending the orchestrator's own end-of-wave
  verification and the recommended post-merge GitHub-render / newcomer-read-through follow-up noted
  above.

## Self-Check: PASSED

- FOUND: `README.md`
- FOUND: `.planning/REQUIREMENTS.md`
- FOUND: `.planning/phases/10-ci-deploy-hardening/10-06-SUMMARY.md`
- FOUND commit `d5f145c` (Task 2 — README restructure)

---
*Phase: 10-ci-deploy-hardening*
*Completed: 2026-08-19*
