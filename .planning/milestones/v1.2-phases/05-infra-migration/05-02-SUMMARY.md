---
phase: 05-infra-migration
plan: 02
subsystem: infra
tags: [docker-compose, caddy, redpanda, github-actions, buildx, mermaid, arm64]

# Dependency graph
requires:
  - phase: 05-infra-migration (plan 05-01, parallel wave-1)
    provides: "Actuator health endpoint at /api/actuator/health, Neon datasource config with a JDBC URL query-string placeholder"
provides:
  - "docker-compose.prod.yml — standalone production manifest (caddy, app, redpanda; no postgres)"
  - "Caddyfile — single site block, automatic HTTPS, hostname injected via {$APP_DOMAIN}"
  - ".env.prod — every variable the manifest expects, placeholders only"
  - "docs/INFRA_ARCHITECTURE.md — Physical/Deployment + Scenario (+1) Mermaid views"
  - "arm64 buildx platform target on the build-and-push-docker-image CI job"
affects: [05-03-provision-oracle-vm, 05-04-manual-deploy, 05-05-cicd-pipeline]

# Actuals (#2632)
actuals:
  tokens: 6486
  tasks: 4
  commits: 4

# Tech tracking
tech-stack:
  added: [caddy:2, docker/setup-qemu-action, docker/setup-buildx-action, docker/build-push-action]
  patterns:
    - "Shared x-logging YAML anchor applied to every service, not copy-pasted, so a future added service cannot silently be missed"
    - "Standalone prod Compose file rather than an overlay, since Compose overlays cannot remove the local dev postgres service"
    - "Kruchten 4+1-scoped Mermaid diagrams (Physical/Deployment, Scenario +1) instead of one blended C4-style diagram, per docs/DIAGRAM_CONVENTIONS.md"

key-files:
  created:
    - docker-compose.prod.yml
    - Caddyfile
    - .env.prod
    - docs/INFRA_ARCHITECTURE.md
  modified:
    - .github/workflows/deploy.yml

key-decisions:
  - "Chose DB_URL_PARAMS as the env var name docker-compose.prod.yml injects for plan 05-01's JDBC URL query-string placeholder — 05-01 runs in a parallel worktree and picks its own name independently, so this is a cross-plan integration risk, not a locked contract; flagged in WINDOWS.md and this SUMMARY for reconciliation before plan 05-04's deploy."
  - "Redpanda resource caps set to --smp 1 / --memory 2G, conservative for the unconfirmed 2-OCPU/12GB shape (revisit in plan 05-03 once the Oracle console tenancy is verified), per CONTEXT.md's explicit discretion to plan conservatively rather than assume the more generous figure."
  - "linux/arm64 only on the buildx platform target, not multi-platform amd64+arm64 — the real deploy target is ARM64 only, and building a platform nobody deploys to only pays QEMU-emulation CI minutes for no benefit."

patterns-established:
  - "Cross-plan placeholder variable names introduced by a parallel wave-1 plan must be explicitly named and flagged for reconciliation, not silently assumed, when a sibling plan's manifest needs to reference them before the two plans merge."

requirements-completed: [INFRA-01, INFRA-03, INFRA-04, INFRA-07]

coverage:
  - id: D1
    description: "Standalone production Compose manifest (caddy, app, redpanda) validates cleanly, has no postgres service, publishes only 80/443, every service carries a logging block, app healthcheck asserts UP status, Redpanda drops --mode dev-container for explicit resource caps"
    requirement: "INFRA-01"
    verification:
      - kind: other
        ref: "docker compose -f docker-compose.prod.yml --env-file .env.prod config (manual run this session, exit 0)"
        status: pass
      - kind: other
        ref: "acceptance-criteria greps: postgres=0, dev-container=0, DDL_AUTO=0, published-ports=2, restart-unless-stopped=3, overprovisioned=1, logging-blocks=4/4"
        status: pass
    human_judgment: false
  - id: D2
    description: "Redpanda production config carries explicit --overprovisioned/--smp/--memory caps (conservative, flagged for plan 05-03 revisit) instead of the local dev --mode dev-container preset, and publishes no Kafka/Registry host port"
    requirement: "INFRA-03"
    verification:
      - kind: other
        ref: "docker compose config resolved output — redpanda service has no ports: entry, command lists --overprovisioned/--smp/--memory"
        status: pass
    human_judgment: false
  - id: D3
    description: "Caddyfile validates via `caddy validate`, reverse-proxies to app:8080 over the internal network only, relies entirely on automatic HTTPS (no tls internal / auto_https off), hostname injected via {$APP_DOMAIN}"
    requirement: "INFRA-04"
    verification:
      - kind: other
        ref: "docker run caddy:2 caddy validate --config Caddyfile --adapter caddyfile (this session, 'Valid configuration')"
        status: pass
      - kind: other
        ref: "acceptance-criteria greps: reverse_proxy=1, tls-internal=0, auto_https-off=0 (non-comment lines)"
        status: pass
    human_judgment: false
  - id: D4
    description: "Every service in docker-compose.prod.yml carries the shared logging anchor with a documented worst-case on-disk bound (10m x 3 files per container)"
    requirement: "INFRA-07"
    verification:
      - kind: other
        ref: "docker compose config resolved output shows driver: json-file on all three services plus the anchor definition"
        status: pass
    human_judgment: false
  - id: D5
    description: "docs/INFRA_ARCHITECTURE.md: two parsing Mermaid diagrams (Physical/Deployment with ARM64/x86_64 node labels, Scenario +1 delivery path labelled as target state), prose on port reachability/TLS termination/state location, and the folded architecture-diagram todo closed"
    verification:
      - kind: other
        ref: "docker run minlag/mermaid-cli against docs/INFRA_ARCHITECTURE.md (this session) — both mermaid blocks rendered to SVG successfully, exit 0"
        status: pass
      - kind: other
        ref: ".planning/todos/pending/2026-08-04-create-high-level-infra-architecture-diagram-before-live-inf.md removed; .planning/todos/completed/... added with Resolution section"
        status: pass
    human_judgment: false
  - id: D6
    description: "build-and-push-docker-image CI job cross-compiles for linux/arm64 via docker/build-push-action, keeping tag naming and Docker Hub login unchanged"
    verification:
      - kind: other
        ref: "rhysd/actionlint against .github/workflows/deploy.yml (this session) — no new findings, only pre-existing out-of-scope warnings"
        status: pass
      - kind: other
        ref: "grep -ci 'platforms:\\s*linux/arm64' .github/workflows/deploy.yml returns 1"
        status: pass
    human_judgment: true
    rationale: "The acceptance criterion 'docker buildx imagetools inspect against a real pushed tag lists linux/arm64' requires an actual GitHub Actions run on master pushing to Docker Hub — not reproducible from this local worktree session. YAML syntax and structural correctness are proven; the real CI run is deferred to the next push to master (plan 05-04/05-05's territory), consistent with D-03's build-order (manual VM proof before CI/CD wiring)."

duration: ~45min
completed: 2026-08-12
status: complete
---

# Phase 5 Plan 2: Production Compose Manifest, Caddy Config, Infra Diagram, ARM64 Build Summary

**Standalone docker-compose.prod.yml (caddy + app + redpanda, no postgres) with capped logs and an honest UP-status healthcheck, a Caddyfile relying on automatic Let's Encrypt HTTPS, two Kruchten-scoped Mermaid architecture diagrams closing the folded diagram todo, and an arm64 buildx cross-compile added to the CI build job.**

## Performance

- **Duration:** ~45 min
- **Completed:** 2026-08-12
- **Tasks:** 4/4
- **Files modified:** 7 (5 new: docker-compose.prod.yml, .env.prod.example, Caddyfile, docs/INFRA_ARCHITECTURE.md, todos/completed entry; 1 modified: .github/workflows/deploy.yml; 1 deleted: todos/pending entry)

## Accomplishments
- Production Compose manifest defines exactly three services (caddy, app, redpanda), no Postgres and no `ddl-auto`, publishing only host ports 80/443; verified by `docker compose ... config` resolving cleanly and by every acceptance-criteria grep from the plan.
- Redpanda's production command block drops the local dev `--mode dev-container` preset for explicit `--overprovisioned`/`--smp 1`/`--memory 2G` caps, documented as conservative and pending confirmation in plan 05-03; no Kafka or Schema Registry port is published to the host.
- App healthcheck asserts the aggregate `status` is `UP` in the response body (not merely that a response arrived), matching this plan's own prohibition against a healthcheck that cannot fail; no host port is published for the app, so Caddy is the only path in.
- Caddyfile validated with `caddy validate` — single site block keyed off `{$APP_DOMAIN}` (no hardcoded hostname), relies entirely on Caddy's automatic HTTPS, no `tls internal` or `auto_https off`.
- `docs/INFRA_ARCHITECTURE.md` ships two Mermaid diagrams, both confirmed rendering via `mermaid-cli` this session: a Physical/Deployment view (Oracle VM trust boundary, ARM64/Ampere and x86_64 node labels, internal-vs-external port/edge labelling) and a Scenario (+1) sequence diagram of the delivery path, explicitly labelled as the target state after plan 05-05. The folded architecture-diagram todo is closed.
- `build-and-push-docker-image`'s CI job now cross-compiles for `linux/arm64` via `docker/setup-qemu-action` + `docker/setup-buildx-action` + `docker/build-push-action`, keeping the exact same tag naming and Docker Hub login step so plan 05-04's manual pull and plan 05-05's automated deploy resolve unchanged.

## Task Commits

Each task was committed atomically:

1. **Task 1: Write the production Compose manifest with resource caps, health, restart and log limits** - `b62c313` (feat)
2. **Task 2: Write the Caddy reverse-proxy config for automatic HTTPS** - `1a2dab2` (feat)
3. **Task 3: Author the infrastructure architecture diagram and close the folded todo** - `1816c79` (docs)
4. **Task 4: Cross-compile the app image for the Oracle VM's ARM64 architecture** - `a195a53` (feat)

**Plan metadata:** committed separately after this SUMMARY, per orchestrator's final-commit step.

## Files Created/Modified
- `docker-compose.prod.yml` - Standalone production manifest: caddy (80/443, TLS/cert volumes), app (image by IMAGE_TAG, no host port, UP-status healthcheck), redpanda (explicit resource caps, no host port), shared logging anchor
- `.env.prod.example` - Every variable the manifest interpolates, placeholders only, header warning it must never carry real values
- `Caddyfile` - Single site block, `{$APP_DOMAIN}` placeholder, automatic HTTPS
- `docs/INFRA_ARCHITECTURE.md` - Physical/Deployment view + Scenario (+1) delivery-path view, prose on port reachability/TLS/state location, maintenance note
- `.github/workflows/deploy.yml` - `build-and-push-docker-image` job: QEMU + Buildx + `docker/build-push-action` targeting `linux/arm64`, replacing the bare x86_64-only `docker build`/`docker push` pair
- `.planning/todos/completed/2026-08-04-create-high-level-infra-architecture-diagram-before-live-inf.md` - Moved from pending, `## Resolution` section added naming this plan and `docs/INFRA_ARCHITECTURE.md`
- `.planning/WINDOWS.md` - New broken-windows ledger entry recording the cross-plan `DB_URL_PARAMS` naming risk (see Deviations below)

## Decisions Made
- **DB_URL_PARAMS naming (cross-plan risk, not a locked contract):** plan 05-01 (parallel wave-1, different worktree) introduces a JDBC URL query-string placeholder in `application.properties` and its own task explicitly leaves the exact variable name to that plan's executor ("Choose the exact variable name and default"). This plan's `docker-compose.prod.yml` needed to supply a production value for that placeholder without visibility into 05-01's actual choice. Chose `DB_URL_PARAMS` as a self-documenting name, wired it through `.env.prod.example` and the `app.environment` block, and documented the assumption in both files' comments plus a WINDOWS.md ledger entry — if 05-01 lands under a different name, `docker-compose.prod.yml`/`.env.prod.example` must be updated to match before plan 05-04's deploy.
- **Redpanda caps set to `--smp 1`/`--memory 2G`:** conservative for the still-unconfirmed 2-OCPU/12GB Oracle tenancy shape, per CONTEXT.md's explicit instruction not to silently assume the more generous figure; comment states these must be revisited once plan 05-03 verifies the real console shape.
- **`linux/arm64` only on the buildx platform target, not multi-platform:** the real deploy target is ARM64 only; adding amd64 would only add QEMU-emulation CI cost for a platform nothing pulls, per the plan's own instruction not to add it "unless a concrete need... surfaces later."
- **App restart policy applied to all three services, not just app/caddy:** the plan's acceptance criteria only required at least 2 services carrying `restart: unless-stopped`; applied it to Redpanda too since an unrestarted broker after an OOM or host reboot would silently strand the app in a degraded, unhealthy-dependency state.

## Deviations from Plan

None of Rules 1-4 were triggered — no bugs found, no missing critical functionality, no blocking issues, no architectural changes needed. One cross-plan integration risk was surfaced and documented rather than silently resolved:

**1. [Cross-plan risk, not a Rule 1-4 deviation] DB_URL_PARAMS variable name chosen without visibility into plan 05-01's parallel choice**
- **Found during:** Task 1 (writing docker-compose.prod.yml's app environment block)
- **Issue:** Plan 05-01 (parallel wave-1 plan, separate worktree) introduces a JDBC URL query-string placeholder in `application.properties`, explicitly leaving the exact variable name to its own executor's discretion. This plan's manifest needed to reference that same variable name to supply a production value, but the two plans execute concurrently with no shared state until merge.
- **Resolution:** Named it `DB_URL_PARAMS`, documented the assumption prominently in `docker-compose.prod.yml`'s header comment, `.env.prod.example`'s inline comment, and this SUMMARY's Decisions section. Recorded as an open `deviation`-kind entry in `.planning/WINDOWS.md` (entry id 1) so it stays visible at ship time even after this SUMMARY scrolls out of context.
- **Verification:** N/A until plan 05-01's actual SUMMARY is available — reconciliation is a required check before plan 05-04's deploy, not before this plan's own tasks are considered done (05-02's own acceptance criteria do not depend on the placeholder's name matching, only on a value being injected).
- **Committed in:** `b62c313` (Task 1 commit); WINDOWS.md entry added post-hoc in this SUMMARY's own preparation, to be committed with the plan-metadata commit.

---

**Total deviations:** 0 Rule-based auto-fixes; 1 documented cross-plan integration risk (not a deviation from this plan's own scope, since the placeholder name was always this plan's discretion to choose per PATTERNS.md/RESEARCH.md).
**Impact on plan:** No scope creep. The cross-plan risk does not block this plan's own acceptance criteria (which only require the manifest to resolve and inject a value) but must be reconciled before plan 05-04's real deploy.

## Issues Encountered
- **Windows path translation with `docker run -v`:** Git Bash's automatic POSIX-to-Windows path conversion mangled bind-mount paths for both the Caddy validation run and the mermaid-cli render, producing "file not found" errors even though the file existed. Fixed by setting `MSYS_NO_PATHCONV=1` on those specific `docker run` invocations — not a code change, a local verification-tooling workaround.
- **Gradle daemon held a file lock across the first commit attempt**, causing `fastTest`'s cleanup step to fail with "Unable to delete directory" on a from a still-warm daemon. Resolved with `./gradlew --stop` before retrying; the commit then succeeded. Also used `--stop` again at the end of the session so Testcontainers' Ryuk reaper could clean up the Postgres/Redpanda containers spun up by each commit's pre-commit test run — confirmed via `docker ps` that only pre-existing, unrelated long-running containers remained afterward.
- **No local mermaid CLI or YAML linter was pre-installed.** Verified both new artifacts by pulling well-known, official-adjacent Docker images for the session (`minlag/mermaid-cli` to render the two Mermaid blocks to SVG, `rhysd/actionlint` to lint the modified GitHub Actions workflow) rather than trusting visual inspection alone or using an unverified `npx` install.

## User Setup Required

None - no external service configuration required by this plan. (Plan 05-03 will require human-guided secret generation per D-02; not this plan's scope.)

## Next Phase Readiness
- All four artifacts this plan owns (`docker-compose.prod.yml`, `Caddyfile`, `.env.prod.example`, `docs/INFRA_ARCHITECTURE.md`) are ready for plan 05-04's manual tracer deploy, and the CI build job now produces an arm64-compatible image before 05-04 ever attempts to pull one.
- **Blocker for plan 05-04:** the `DB_URL_PARAMS` naming risk above must be reconciled against plan 05-01's actual SUMMARY before the manual deploy — if 05-01 chose a different variable name, `docker-compose.prod.yml` and `.env.prod.example` need a one-line rename to match.
- Redpanda's `--smp`/`--memory` values are explicitly flagged as provisional pending plan 05-03's real Oracle console verification — do not treat them as final without that check.
- The `docker buildx imagetools inspect` acceptance criterion for Task 4 cannot be proven from a local worktree; it requires the next real push-to-master CI run, which naturally happens once this plan's branch merges.

## Self-Check: PASSED

- FOUND: docker-compose.prod.yml
- FOUND: Caddyfile
- FOUND: .env.prod.example
- FOUND: docs/INFRA_ARCHITECTURE.md
- FOUND: .planning/todos/completed/2026-08-04-create-high-level-infra-architecture-diagram-before-live-inf.md
- CONFIRMED REMOVED: .planning/todos/pending/2026-08-04-create-high-level-infra-architecture-diagram-before-live-inf.md
- FOUND commits: b62c313, 1a2dab2, 1816c79, a195a53 (all present in `git log --oneline --all`)

---
*Phase: 05-infra-migration*
*Completed: 2026-08-12*
