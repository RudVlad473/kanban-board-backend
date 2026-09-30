---
phase: 11-migrate-database-from-neon-to-self-hosted-postgres
plan: 06
subsystem: infra
tags: [postgres, neon, decommission, backups, documentation]

requires:
  - phase: 11-05
    provides: "Green CI run over the self-hosted database, closing the red window"
provides:
  - "Neon project deleted, confirmed via control-plane API (list_projects empty + describe_project 404)"
  - "docs/INFRA_RUNBOOK.md presents the self-hosted topology as current, with a full decommission record"
  - "The point-in-time-recovery regression documented honestly (D-12), with a written but untested manual restore procedure"
  - "The pre-existing backup-runbook todo re-scoped against the new reality"
affects: []

actuals:
  tokens: 9200
  tasks: 3
  commits: 1

tech-stack:
  added: []
  patterns:
    - "Control-plane API confirmation (list + describe-404) as the authoritative deletion evidence, rather than a connection-protocol probe whose error shape turned out to be indistinguishable from a never-existed endpoint"

key-files:
  created: []
  modified:
    - docs/INFRA_RUNBOOK.md
    - .planning/todos/pending/2026-08-20-no-documented-backup-restore-runbook-for-prod-db.md

key-decisions:
  - "D-07: operator chose delete-now after all five gate conditions were verified live (not inferred from earlier summaries) — production/nonprod health 200, both databases 8/8 Flyway, latest CI run green, nothing anywhere still resolving Neon's hostname."
  - "Task 2 was executed via the Neon MCP tools (describe_project, list_projects, delete_project) rather than the plan's originally-envisioned manual web-console click-through — an explicit choice offered to and made by the operator, given MCP access was available in this session; the plan's own capture-then-delete-then-confirm sequencing was followed exactly regardless of the execution surface."
  - "The plan's literal acceptance criterion — the former endpoint must fail 'at resolution or connection, not authentication' — turned out to be based on an incorrect assumption about Neon's proxy behavior. A live control test (a password-auth attempt against a hostname that never existed) produced the byte-identical 'password authentication failed' error as the real deleted endpoint. The control-plane API result (empty list + 404), not the connection-protocol error shape, was used as the authoritative deletion evidence instead, and this correction is recorded in the runbook's Decommission Record."
  - "The backup todo was re-scoped in place (kept in pending/, not moved to completed/) rather than closed — the underlying concern (no backup exists) is now documented, not resolved; only the automation that would actually close it remains open, deliberately deferred under D-12."

requirements-completed: [D-07, D-12]

coverage:
  - id: D1
    description: "The managed provider's project is deleted only after a live-verified end-to-end gate and an explicit recorded human decision (D-07)"
    verification:
      - kind: manual_procedural
        ref: "Five gate conditions checked live and recorded in docs/INFRA_RUNBOOK.md's Decommission Record; operator's verbatim decision recorded in this SUMMARY"
        status: pass
    human_judgment: false
  - id: D2
    description: "Deletion confirmed authoritatively, not merely attempted"
    verification:
      - kind: manual_procedural
        ref: "list_projects search empty + describe_project returns HTTP 404, both captured verbatim in the Decommission Record"
        status: pass
    human_judgment: false
  - id: D3
    description: "Loss of point-in-time recovery documented as an acknowledged, honest regression with a usable-but-untested manual restore procedure (D-12)"
    verification:
      - kind: manual_procedural
        ref: "docs/INFRA_RUNBOOK.md 'Backups and restore' section — opens with the plain absence statement, real pg_dump/pg_restore command shapes, dated written-but-never-executed marker"
        status: pass
    human_judgment: false
  - id: D4
    description: "The pre-existing backup todo reflects what actually happened — documented, not closed by documentation alone"
    verification:
      - kind: manual_procedural
        ref: ".planning/todos/pending/2026-08-20-...md's new Resolution section, kept in pending/"
        status: pass
    human_judgment: false

duration: 40min
completed: 2026-08-26
status: complete
---

# Phase 11 Plan 06: Decommission the managed Neon provider Summary

**Deleted the Neon project after a live-verified end-to-end gate and an explicit human decision, confirmed the deletion authoritatively via the control-plane API (a connection-protocol probe proved uninformative — Neon's proxy gives an identical "auth failed" response to both a real deleted endpoint and one that never existed), and replaced the runbook's Neon section with current self-hosted documentation plus an honest "there is no backup" regression record.**

## Performance

- **Duration:** ~40 min
- **Started:** 2026-08-26T18:20:00Z (approx, Task 1 gate verification)
- **Completed:** 2026-08-26T19:00:00Z
- **Tasks:** 3
- **Files modified:** 2 (`docs/INFRA_RUNBOOK.md`, one todo file) + one external deletion (no repository state)

## Task 1 Decision Record (D-07)

**Gate verified live, before the decision was presented:**
1. Production health endpoint: `200`
2. Nonprod health endpoint: `200`
3. `kanban_prod` / `kanban_nonprod` Flyway counts: `8` / `8`
4. Latest default-branch workflow run (`32985965535`, plan 11-05): fully green, including both Flyway verification jobs
5. GitHub Environment secrets/variables (both `production` and `staging`) and both VM `.env` files: none resolved Neon's hostname — `DB_HOST` was already `postgres` everywhere; only `DB_USER`/`DB_PASS` remain secrets, `DB_HOST`/`DB_NAME` are plain variables per plan 11-05's own deviation

**Decision presented:** delete-now, keep-dormant (with a required re-check date), or halt — with the one-way consequence and the illusory nature of a "dormant fallback" (no current data under D-04, compute quota-blocked until 2026-09-01) stated explicitly.

**Operator's verbatim answer:** "Delete the project now (recommended per locked D-07)"

## Task 2 Evidence

**Execution surface, an explicit operator choice:** the Neon MCP tools available in this session (`describe_project`, `list_projects`, `delete_project`), rather than the plan's originally-envisioned manual web-console click-through — offered to and chosen by the operator, given the option existed.

1. **Identifiers captured before deletion** (via `describe_project`, cross-referenced against the runbook's existing Neon section): project `kanban-board-db` / `floral-union-23715140`, org Rudenko Vladimir / `org-red-moon-37279582`, region `aws-eu-central-1`, Postgres 18, production branch `br-divine-waterfall-b2864di0` (compute `ep-delicate-bird-b2lni8pr`), nonprod branch `br-still-shadow-b2r7ez0l`.
2. **Confirmed nothing still resolved Neon's hostname** — same check as the Task 1 gate, immediately before deletion.
3. **`delete_project` called** for `floral-union-23715140` — succeeded.
4. **Deletion confirmed authoritatively:** `list_projects` search for the name/id returned an empty result; `describe_project` against the same ID returned `HTTP 404`. A supplementary connection-protocol test (real `psql` attempt against the former direct host) returned `password authentication failed` — the exact error shape the plan's acceptance criteria warned against as evidence of survival — but a control test against a hostname that **never existed** produced the byte-identical error, proving Neon's proxy does not distinguish "wrong credentials" from "endpoint gone" at that layer (an anti-enumeration design choice). The control-plane API result is the evidence actually relied on.
5. **Credential revocation:** no `neonctl`/`neon` CLI binary, no CLI config files, and no `NEON_API_KEY`/`NEON_TOKEN` reference in any local shell profile were found. Nothing to revoke.
6. **Both public health endpoints re-confirmed `200`** immediately after deletion.

`git status` showed no repository file changed by Task 2 itself, as required.

## Task Commits

1. **Task 3: Replace the provider's runbook section, record the decommission, and document the backup gap** - `a48706a` (docs)

_Tasks 1 (checkpoint:decision) and 2 (checkpoint:human-action) produced no repository commits — Task 1 is a recorded human decision, Task 2 is the live Neon deletion described above._

## Files Created/Modified
- `docs/INFRA_RUNBOOK.md` - Replaced "## Database — Neon" with "## Database — self-hosted PostgreSQL" (pointing at the plan 11-02/11-03 evidence rather than restating it); added the "Decommission Record — Plan 11-06" section (gate verification, decision, four Parts, date); added the "Backups and restore — current coverage and the documented gap (D-12)" section.
- `.planning/todos/pending/2026-08-20-no-documented-backup-restore-runbook-for-prod-db.md` - Added a `## Resolution` section; kept in `pending/` (re-scoped, not closed) since the underlying concern is documented, not fixed.

## Decisions Made
- See Key Decisions in frontmatter for the four load-bearing decisions this plan made.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Plan's assumed failure signature for a deleted Neon endpoint does not hold**
- **Found during:** Task 2, step 4 (confirming deletion took effect)
- **Issue:** The plan's acceptance criteria expected the former endpoint to fail "at name resolution or connection, not at authentication." A real `psql` connection attempt (deliberately wrong password) against the deleted endpoint returned `ERROR: password authentication failed` — the exact shape the plan flagged as concerning.
- **Fix:** Ran a control test against a hostname that had never existed in the same region — it produced the byte-identical error, proving this error shape carries no information about endpoint existence for Neon's architecture (anti-enumeration by design). Used the control-plane API's authoritative result (`list_projects` empty + `describe_project` 404) as the real evidence instead, and recorded this finding in the Decommission Record so a future reader does not misinterpret an "auth failed" response from this endpoint as evidence of survival.
- **Files modified:** None (verification-method correction, documented in `docs/INFRA_RUNBOOK.md`).
- **Verification:** Both the search-empty and describe-404 results are unambiguous, independent confirmations from the provider's own control plane.
- **Committed in:** `a48706a` (documented as part of the Decommission Record).

---

**Total deviations:** 1 auto-fixed (1 bug in the plan's own assumed evidence shape, corrected via a live control test).
**Impact on plan:** None on the actual outcome — the project genuinely is deleted, confirmed by the authoritative source. Only the verification method used differs from the plan's literal wording, and that difference is itself documented as a finding.

## Issues Encountered
None beyond the documented deviation above.

## User Setup Required
None.

## Next Phase Readiness
This is the final plan of Phase 11. Every phase deliverable is complete: the self-hosted topology is live and measured, CI verifies against it, and the managed provider is gone with an honest accounting of what was lost (no backup coverage, acknowledged and scoped rather than hidden). No further plans remain in this phase.

---
*Phase: 11-migrate-database-from-neon-to-self-hosted-postgres*
*Completed: 2026-08-26*
