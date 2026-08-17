---
phase: quick-260817-tvd
plan: 01
subsystem: docs
tags: [documentation, sequence-diagrams, authentication, e2e-testing]
status: complete

dependency-graph:
  requires: []
  provides:
    - docs/AUTH_FLOWS.md
    - docs/diagrams/auth-signup-scenario.mmd
    - docs/diagrams/auth-signup-scenario.png
    - docs/diagrams/auth-signin-scenario.mmd
    - docs/diagrams/auth-signin-scenario.png
  affects:
    - docs/ARCHITECTURE.md
    - README.md

tech-stack:
  added: []
  patterns:
    - "Kruchten Scenario (+1) sequence diagrams, house style: standalone .mmd source + committed .png render, embedded via ![...](diagrams/x.png) + <sub>[diagram source](diagrams/x.mmd)</sub>"
    - "Two diagrams of one scenario at two abstraction levels, reciprocally cross-linked and each declaring its audience, rather than one diagram forced to serve two readers"

key-files:
  created:
    - docs/AUTH_FLOWS.md
    - docs/diagrams/auth-signup-scenario.mmd
    - docs/diagrams/auth-signup-scenario.png
    - docs/diagrams/auth-signin-scenario.mmd
    - docs/diagrams/auth-signin-scenario.png
  modified:
    - docs/ARCHITECTURE.md
    - README.md

decisions:
  - "Approach A from the plan's trade-off matrix: new client-facing diagrams in a new host doc, leaving docs/diagrams/architecture-signin-scenario.mmd byte-identical, reciprocally cross-linked. Rejected rewriting the existing security diagram (would have destroyed finding-level detail to serve a different audience) and rejected a single combined signup+signin diagram (would have needed three levels of alt nesting at a legibility precedent this repo has tripped before)."

metrics:
  duration: "~35 minutes"
  completed: 2026-08-17

actuals:
  tokens: 5252
  tasks: 3
  commits: 3
---

# Phase quick-260817-tvd Plan 01: Create Authentication Sequence Diagrams Summary

Added two client-facing Kruchten Scenario (+1) sequence diagrams (`POST /api/signup`,
`POST /api/signin`) and a new host document, `docs/AUTH_FLOWS.md`, written for a frontend/QA
engineer with no JVM/Spring background who needs the HTTP-observable authentication contract to
write a Playwright E2E suite against this API.

## What was built

- **`docs/diagrams/auth-signup-scenario.mmd` / `.png`** — the full `POST /api/signup` flow: the
  400 validation arm (with the per-field `errors` map), the 409 duplicate-email arm (with its
  database-unique-constraint race backstop landing on a second, different 409 code), the shared
  `authenticate()` helper (token built from the user's **id**, not email; provider comparison;
  minimal principal), the strategy-then-context-save ordering with a note that the concurrent-
  session ceiling cannot reject a signup, the after-response session commit, the 201 success
  response (with the note that its `Location` header does not currently resolve), and the
  auto-rollback arm that deletes the just-created account and surfaces as 401 rather than 403.
- **`docs/diagrams/auth-signin-scenario.mmd` / `.png`** — the full `POST /api/signin` flow,
  drawn with the same participant aliases as the signup diagram for the shared middle section: the
  timing-equalizer arm on an unknown email, the wrong-password arm, the ceiling-then-rotation
  ordering of the composite session strategy, and — the diagram's headline — the ceiling-rejection
  arm terminating in a response visually identical to the wrong-password arm, called out as
  deliberate (D-08) rather than a drawing accident.
- **`docs/AUTH_FLOWS.md`** — the host document: an audience/purpose declaration, a pointer to
  `ARCHITECTURE.md`'s signin diagram as the complementary security-reviewer view, `## Sign up` and
  `## Sign in` sections (route, request body, status table, embedded diagram, "what this means for
  a test" paragraph), and a `## What will break your E2E suite` section covering the 2-session
  ceiling, the two session lifetimes (10-minute cookie vs. 180-minute server-side timeout),
  `SameSite=Strict`, credentialed CORS with its explicit origin allow-list, session-id rotation,
  disabled CSRF, and the 401-vs-403 split — each fact stated with its source property/constant and
  its consequence for a test author.
- **`docs/ARCHITECTURE.md`** — one scoped edit adding a reciprocal cross-link from the existing
  security-focused signin diagram to `AUTH_FLOWS.md`, plus a clause on the existing "Simplified:"
  paragraph pointing at the new signup diagram now that signup has one.
- **`README.md`** — one new row in the doc-index table for `docs/AUTH_FLOWS.md`, naming its
  audience.

## Deviations from Plan

None — plan executed exactly as written, task by task, with all three `<verify>` automated gates
passing on the first or second attempt (see below).

**Minor self-correction during Task 2's verify (not a plan deviation, a gate catching its own
target):** the automated verify for Task 2 requires the literal `JSESSIONID` to appear in
`AUTH_FLOWS.md`'s hazards section (gated against `server.servlet.session.cookie.name=JSESSIONID`
in `application.properties`). The first draft of the two-session-lifetimes bullet referenced the
cookie only by property name, not by its configured value. Fixed by naming the cookie
(`server.servlet.session.cookie.name=JSESSIONID`) inline in that bullet before re-running the gate,
which then passed. No commit was made with the missing literal — caught before Task 2's commit.

## Known Environmental Note (not a plan deviation)

Task 3's automated verify includes a scope-boundary assertion (`git diff --name-only HEAD | grep
-cvE '^(docs/|README\.md|\.planning/)'` must equal `0`) intended to catch accidental edits outside
docs/README/`.planning/`. On this run it reported a nonzero count solely because
`.gsd/dispatch-isolation-sentinel.json` — a harness/session-bookkeeping file, not part of this
repo's own source tree — was already modified before this quick task began (visible in the git
status snapshot taken at session start, before any file in this plan was touched). Re-running the
same check with that one pre-existing, unrelated file excluded shows a clean pass: no file under
`src/`, no build file, no properties file, and no file outside `docs/`, `README.md`, and
`.planning/` was modified by this quick task's own work. `.planning/HANDOFF.json`'s deletion,
visible in the same `git diff --name-only HEAD` output, is also pre-existing session state, not
caused by this task, and falls inside the `.planning/` exclusion the check already allows.

## Verification Performed

- Both `.mmd` sources render cleanly with `@mermaid-js/mermaid-cli@11` (no parser quirks hit —
  the four known v11 hazards from quick task 260816-tqc were avoided from the first draft).
- Every `ErrorCode`-shaped identifier named in either diagram or `AUTH_FLOWS.md` was extracted and
  checked for real membership in `ErrorCode.java` — all matched
  (`VALIDATION_FAILED`, `DUPLICATE_RESOURCE`, `DATA_INTEGRITY_VIOLATION`, `BAD_CREDENTIALS`).
  Route literals (`/signup`, `/signin`) and every quoted configuration value (cookie name, cookie
  max-age, session timeout, `SameSite`, CORS allow-list) were grep-gated against their source files
  and matched.
- Negative grep for credential-shaped and IP-address-shaped literals found nothing in either new
  document or diagram source.
- Both renders (784px wide) are well under the existing in-repo diagram-width precedent (3136px
  max).
- `docs/diagrams/architecture-signin-scenario.mmd` and `.png` confirmed byte-identical to their
  pre-task state across the full three-commit range (`git diff --quiet HEAD~3 HEAD --
  docs/diagrams/architecture-signin-scenario.*`).
- `git diff --name-only HEAD~3 HEAD | grep -c '\.java$'` returned `0` — no production code touched
  across any of the three task commits.
- Visually inspected both rendered PNGs: the signup diagram's three failure arms (400/409/401) are
  visually separable from the success path with the strategy-call-then-context-save ordering
  unambiguous; the signin diagram's wrong-password arm and session-ceiling arm terminate in
  identical-looking `401 ProblemDetail {code: BAD_CREDENTIALS}` responses at the same visual
  position, making the deliberate indistinguishability read as intentional.
- `.githooks/pre-commit`'s `gitleaks` scan, `spotlessCheck`, and `fastTest` passed clean on all
  three commits with no new `.gitleaks.toml` entry needed.

## Known Stubs

None. All content is a real, verified description of the shipped implementation — no placeholder
data, no unwired sections.

## Threat Flags

None. This plan's own `<threat_model>` covers the relevant surface (information disclosure in
example bodies, accuracy-as-a-published-contract, the ephemeral `mermaid-cli` fetch, and the
pre-existing signin diagram as an accidental-casualty risk) and every disposition's mitigation was
applied as planned — no new, unplanned surface was introduced.

## Self-Check: PASSED

- `docs/AUTH_FLOWS.md` — FOUND
- `docs/diagrams/auth-signup-scenario.mmd` — FOUND
- `docs/diagrams/auth-signup-scenario.png` — FOUND
- `docs/diagrams/auth-signin-scenario.mmd` — FOUND
- `docs/diagrams/auth-signin-scenario.png` — FOUND
- `docs/ARCHITECTURE.md` (modified) — FOUND
- `README.md` (modified) — FOUND
- Commit `9577f49` (Task 1) — FOUND in `git log --oneline`
- Commit `36674d1` (Task 2) — FOUND in `git log --oneline`
- Commit `2bfbac9` (Task 3) — FOUND in `git log --oneline`
