---
phase: quick-260816-tqc
plan: 01
subsystem: docs
tags: [documentation, diagrams, ci-cd, infra]
status: complete
dependency-graph:
  requires: []
  provides:
    - "docs/diagrams/infra-delivery-scenario.mmd (current-state CI/CD Scenario (+1) diagram)"
    - "docs/diagrams/infra-delivery-scenario.png (matching render)"
  affects:
    - "docs/INFRA_ARCHITECTURE.md"
tech-stack:
  added: []
  patterns:
    - "Mermaid sequenceDiagram box grouping for a physical trust boundary (Netcup VPS) inside a Scenario (+1) view"
    - "par/and block to depict genuinely concurrent CI jobs (build-and-push-docker-image, flyway-verify)"
    - "--x arrow style to visually distinguish a no-op container ('left running') from a recreate"
key-files:
  created: []
  modified:
    - docs/diagrams/infra-delivery-scenario.mmd
    - docs/diagrams/infra-delivery-scenario.png
    - docs/INFRA_ARCHITECTURE.md
decisions:
  - "Rewrote the diagram in place (Approach A from the plan's trade-off matrix) rather than adding a second competing diagram or splitting into two views -- the existing file was factually wrong (named a job that no longer exists, disclaimed itself as unbuilt), not merely incomplete, so leaving it next to a corrected version would have left the reader unable to tell which was authoritative."
  - "Kept the pipeline and the VM-side container switch as one diagram (rejected the Approach C fallback) -- the rendered PNG came in at 784px wide, well under every existing diagram's 3136px, so the width gate that would have triggered the split never fired."
metrics:
  duration: ~20min
  completed: 2026-08-16
actuals:
  tokens: 2800
  tasks: 2
  commits: 2
---

# Phase quick-260816-tqc Plan 01: Rewrite the CI/CD delivery-path diagram as current-state Summary

Replaced the plan-05-02-era forecast diagram of the CI/CD delivery path with an accurate, current-state Kruchten Scenario (+1) sequence diagram of all seven `deploy.yml` jobs plus the Docker Compose container-switch mechanics on the Netcup VM, and reconciled the surrounding `docs/INFRA_ARCHITECTURE.md` prose to match.

## What Was Built

**Task 1 — `docs/diagrams/infra-delivery-scenario.mmd` / `.png` (commit `17643ba`):** Rewrote the sequence diagram from scratch against the live `.github/workflows/deploy.yml` and `docker-compose.prod.yml` (not the stale `.mmd`). Eight participants: Developer, GitHub Actions runner, Docker Hub, Neon Postgres, and a `box`-grouped Netcup VPS containing the Compose CLI, `app`, `caddy`, and `redpanda`. The diagram now shows:

- All seven jobs by name (`setup`, `run-tests`, `build-and-push-docker-image`, `flyway-verify`, `deploy-to-netcup`, `cleanup-old-images`, `cleanup-unused-image`), gated by an automated check that both greps each name and counts the jobs actually defined under `jobs:` in `deploy.yml` (so a newly *added* job trips the gate too, not just a rename).
- `build-and-push-docker-image` and `flyway-verify` drawn inside a `par`/`and` block — a real property of the job graph (both `needs: [setup, run-tests]` only) that the old diagram drew as sequential.
- `deploy-to-netcup`'s SCP (compose file + Caddyfile, fingerprint-pinned SSH, no `--remove-orphans` so `.env.prod` survives) and the concurrency-group queueing note.
- The VM-side container switch — the payload the old diagram reduced to one arrow: `app` recreated (its `image:` tag changes every deploy), `caddy`/`redpanda` left running via a distinct `--x` arrow style (a dotted line ending in a cross, vs. the solid arrow used for `app`'s recreate) because their resolved config is byte-identical, and a `Note` naming `docker-compose.prod.yml`'s top-level `name: kanban-board-backend` pin as the reason Compose converges on the already-running stack instead of starting a second one — citing `docs/INFRA_RUNBOOK.md`'s incident where a directory-derived project name did exactly that and briefly lost 14 registered Avro schemas.
- Two accuracy hazards stated rather than smoothed over: `flyway-verify` applies migrations to the real production database before the image reaches the VM (not an atomic deploy), and `docker compose up -d` returns once `app` is *started*, not once its healthcheck passes.
- The `cleanup-old-images` / `cleanup-unused-image` split as an `alt` on run outcome, with `cleanup-old-images` explicitly annotated as **not** currently achieving tag pruning (its DELETE calls are rejected `unauthorized`; cites the open todo `2026-08-16-cleanup-old-images-delete-calls-rejected-unauthorized.md`).

Re-rendered via `npx @mermaid-js/mermaid-cli@11`. The render came in at 784px wide — well under the 3136px every other checked-in diagram renders at — so the Approach C fallback (splitting into two diagrams) in the plan's trade-off matrix was never triggered.

**Task 2 — `docs/INFRA_ARCHITECTURE.md` (commit `432fd23`):** Scoped `Edit` calls only, per the plan's constraint:

- Deleted the "this diagram describes the target state after plan 05-05 lands" forecast disclaimer and the dead `deploy-to-ec2` reference; replaced with a one-line lead-in dating the reconciliation (this quick task, 2026-08-16).
- Renamed every `ddl-verify` reference to `flyway-verify` and corrected its described mechanism from a hand-rolled `psql` DDL sequence to the pinned Flyway CLI container running against `src/main/resources/db/migration`, keeping the direct-endpoint point and adding the pooled-endpoint guard.
- Repaired the truncated "Externally reachable vs. internal-only (delivery path)" sentence, which previously ran two thoughts together and ended mid-clause.
- Added a new paragraph — the prose companion to the diagram's container-switch half — stating which service is recreated and why, that `caddy`/`redpanda` are left alone, why the project-name pin makes that true (citing `docs/INFRA_RUNBOOK.md`), and the `up -d`-doesn't-wait-for-health caveat.
- Fixed the Maintenance Note's stale `linux/arm64` platform claim (the deploy target pivoted from Oracle/ARM64 to Netcup/x86_64 in Phase 5) and extended it to name the specific `deploy.yml` (seven job names, job graph) and `docker-compose.prod.yml` (project-name pin, `app` tag interpolation) facts this document now pins.

Both markdown links (`diagrams/infra-delivery-scenario.png` / `.mmd`) kept resolving unchanged since the filename was reused. Section count (`## `) stayed at 3. `.github/workflows/deploy.yml` and `docker-compose.prod.yml` are byte-identical to their pre-task state (asserted by `git diff --quiet` in both tasks' verify gates).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Mermaid parse errors during initial diagram authoring**
- **Found during:** Task 1, first render attempt
- **Issue:** The first draft of the `.mmd` source used HTML-entity-escaped angle brackets (`&lt;short SHA&gt;`), a bare colon inside an actor-to-actor message (`recreate -- image: reference...`), and a semicolon inside message text (`Basic auth; see todo`) — all three tripped the mermaid-cli v11 sequence-diagram parser with `Parse error ... got 'NEWLINE'/'INVALID'`, each surfacing on a separate render iteration.
- **Fix:** Replaced angle-bracket placeholders with parenthetical phrasing, removed the bare colon, and replaced the semicolon with a double-dash. Re-rendered clean on the fourth attempt.
- **Files modified:** `docs/diagrams/infra-delivery-scenario.mmd`
- **Commit:** `17643ba` (folded into the task commit; no separate commit needed since these were pre-verify authoring fixes, not a regression in already-committed work)

**2. [Rule 1 - Bug] Mermaid `box` title rendered with literal quote characters**
- **Found during:** Task 1, visual re-check of the rendered PNG
- **Issue:** `box "Netcup VPS -- x86_64, Vienna"` rendered the quote marks literally in the box header (`'Netcup VPS -- x86_64, Vienna'`) instead of being stripped as a delimiter.
- **Fix:** Removed the quotes (`box Netcup VPS -- x86_64, Vienna`) — mermaid's `box` directive takes the rest of the line as a plain-text title with no quoting needed.
- **Files modified:** `docs/diagrams/infra-delivery-scenario.mmd`
- **Commit:** `17643ba`

None of the above changed the diagram's content or any of the plan's `must_haves` — both were rendering-syntax corrections discovered while iterating toward a clean render, well within Task 1's fix-attempt budget (2 of 3).

## Known Stubs

None — this is a documentation-only change with no code, and no placeholder content was introduced.

## Threat Flags

None — no new network endpoint, auth path, file-access pattern, or schema change was introduced. The diagram and prose describe facts already public in `deploy.yml`/`docker-compose.prod.yml`; verified before commit that no secret value, VM hostname/IP, host-key fingerprint, or `.env.prod` content appears anywhere in the new content (automated negative-grep gate in both tasks' `<verify>` blocks, plus the pre-commit `gitleaks` scan, both passed clean).

## Self-Check: PASSED

- FOUND: `docs/diagrams/infra-delivery-scenario.mmd`
- FOUND: `docs/diagrams/infra-delivery-scenario.png`
- FOUND: `docs/INFRA_ARCHITECTURE.md`
- FOUND commit `17643ba` in `git log --oneline --all`
- FOUND commit `432fd23` in `git log --oneline --all`
