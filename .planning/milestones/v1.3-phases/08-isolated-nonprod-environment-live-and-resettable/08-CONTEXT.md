# Phase 8: Isolated Nonprod Environment, Live and Resettable - Context

**Gathered:** 2026-08-18
**Status:** Ready for planning

<domain>
## Phase Boundary

A second deployment of this app is live over real HTTPS at its own stable hostname, provably isolated from production at every layer that matters (database, Kafka broker, schema-registry compatibility history, container/network/volume identity, secrets), sized by live measurement rather than arithmetic, and returnable to a known-clean baseline on demand.

Requirements in scope: NONPROD-01, NONPROD-02, NONPROD-03, NONPROD-04, NONPROD-05, NONPROD-06, RESET-01 (per ROADMAP.md).

We clarified HOW to implement this. CI automation (Phase 9) and the hardening todos (Phase 10) are explicitly out of this phase's scope.

</domain>

<decisions>
## Implementation Decisions

### Reset Endpoint (RESET-01)

- **D-01:** The reset endpoint authenticates via a shared-secret header (a nonprod-only env var, e.g. `RESET_TOKEN`, checked against the request), not session auth, an IP allowlist, or reliance on hostname obscurity. Chosen because it's cheap, doesn't touch user accounts, and works identically for a manual `curl` today and Playwright's `beforeEach` later.
- **D-02:** The reset endpoint must be **profile-gated**, not merely auth-gated — the controller/bean only registers under a nonprod-only Spring profile, so it cannot exist in production at all, regardless of whether the auth check is ever misconfigured. — **Reversibility:** costly — removing the profile gate later would mean the endpoint could physically exist in a production build; treat "does not exist outside nonprod" as a standing invariant for this endpoint, not a preference to revisit casually.
- **D-03:** Reset target state is **genuinely empty** — truncate both Postgres and the activity-log/Kafka state to zero rows, no reseed/fixture data. Each E2E test is responsible for creating its own fixtures via the real API. (A seeded-fixture option was considered and explicitly rejected.)

### Data Isolation

- **D-04:** The nonprod Neon branch is created **schema-only/empty** (Flyway migrations applied fresh), not as a data copy of production. Consistent with D-03 — nonprod never holds a snapshot of production's real rows.

### Networking & Naming

- **D-05:** Nonprod's DuckDNS hostname is `kanban-board-rud-vlad-473-nonprod.duckdns.org` — a `-nonprod` suffix on production's existing subdomain, matching this milestone's own "nonprod" terminology exactly (not `-staging`, to avoid the one place project docs and the live hostname would otherwise diverge). Must be enumerated exactly in CORS/session-cookie config, never wildcard-matched against the shared `*.duckdns.org` suffix (research Pitfall 5).
- **D-06:** NONPROD-05's CORS placeholder origin is the **same local-dev value** `CorsConfig.java` already defaults to (`http://localhost:5173,http://localhost:3000`), not a guessed future frontend hosting URL. A frontend dev pointing a local dev server at the nonprod API works immediately with zero config; no code change needed either way since the origin list is externalized via `app.cors.allowed-origins`.

### Cost/Ops Judgment

- **D-07:** If the live Redpanda memory-floor measurement (NONPROD-06) shows colocation on the existing Netcup VPS doesn't hold, **stop and report before provisioning the fallback second VPS** (~€4/month) — do not provision it automatically. This is a new recurring real-money cost contingent on a measurement outcome that doesn't exist yet, and the user explicitly wants to approve it, not have it happen unattended. — **Reversibility:** one-way — provisioning a second paid VPS is a real recurring-cost commitment; the planner MUST insert a `checkpoint:decision` immediately before whichever task would provision the fallback VPS, framing "measured floor found, stay colocated" vs. "no safe floor found, provision fallback" as the two doors, even though the technical act of provisioning is not itself irreversible.

### Claude's Discretion

- Exact directory/Compose-project-name/container-name/network-name/volume-name choices for the nonprod stack — must all differ from production's per research Pitfall 1, but the specific names are an implementation detail, not a vision decision.
- Iteration procedure and starting values for the Redpanda `--memory`/`--smp` live-measurement pass (NONPROD-06) — mirrors the methodology already used for production (`docs/INFRA_RUNBOOK.md`'s Task 3 measurement), Claude's judgment on iteration count/workload shape.
- Exact shape of the `RESET_TOKEN` value and where it's generated/stored (`.env.nonprod` alongside DB creds) — mechanical, no user preference expressed.

### Folded Todos

- **`2026-08-12-add-nonprod-staging-environment-and-playwright-e2e-ci-gate.md`** ("Add a nonprod/staging environment and wire Playwright E2E tests against real (non-mocked) deploys") — this is the todo that originally seeded the entire v1.3 milestone. It was already tagged `resolves_phase: 8` during roadmap creation (this session, prior to this discussion) rather than during this workflow's own `cross_reference_todos` step — recorded here so it isn't mistaken for an unaddressed match.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Production infrastructure (the pattern nonprod must mirror and stay isolated from)
- `docs/INFRA_RUNBOOK.md` — full current-state record of the Netcup VM: firewall (2 layers), Neon connection details, DuckDNS, deploy-user setup, the Compose-project-name collision incident (Pitfall 1's real precedent), and the Redpanda memory-tuning measurement methodology (NONPROD-06 should mirror this)
- `docker-compose.prod.yml` — the exact service shape (caddy/app/redpanda), `mem_limit`/`--memory`/`--smp` values, logging anchor, `name:` pinning, healthchecks — nonprod's compose file must diverge on every identity axis (name, containers, network, volumes) per research Pitfall 1
- `Caddyfile` — the `{$APP_DOMAIN}` env-var placeholder pattern nonprod's second site block should reuse
- `src/main/java/com/vrudenko/kanban_board/config/CorsConfig.java` — confirms `app.cors.allowed-origins` is already externalized; D-06's placeholder value goes here with zero code change

### Requirements and research
- `.planning/REQUIREMENTS.md` — NONPROD-01..06, RESET-01 full text, Out of Scope section (no per-PR ephemeral envs, no ephemeral Neon branch per run, no second Neon project)
- `.planning/research/SUMMARY.md` — architecture approach, the 5 critical pitfalls (deploy-job overwrite, no GitHub Environments, cleanup-old-images cross-contamination, resource contention, DuckDNS wildcard scope), and the Redpanda memory-floor gap this phase must close
- `.planning/ROADMAP.md` — Phase 8 goal, success criteria, dependency note ("builds on the production stack already live from v1.2 Phase 5")

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `CorsConfig.java`'s `@Value("${app.cors.allowed-origins:...}")` pattern — nonprod's placeholder origin (D-06) is a deployment-time env var, zero code change
- `docker-compose.prod.yml`'s `x-logging: &default-logging` anchor — same rotation discipline (10m/3-file) should apply to nonprod's containers
- Production's already-proven `mem_limit` + Redpanda `--memory`/`--smp` cgroup-backstop pattern (with its documented "don't set mem_limit numerically equal to --memory" gotcha) — directly informs how NONPROD-06's live measurement pass should be structured

### Established Patterns
- Flyway owns schema creation (`ddl-auto=validate` outside test profile) — nonprod's Neon branch (D-04, schema-only) gets its schema via the same Flyway-verify job pattern `docs/INFRA_RUNBOOK.md` documents for production, not a manual DDL step
- Spring profiles already gate environment-specific behavior in this codebase (test profile for Testcontainers) — D-02's profile-gated reset endpoint extends an existing pattern, not a new mechanism

### Integration Points
- `SecurityConfiguration` / session cookie config — D-05's exact hostname enumeration matters here too (existing project convention already flags exact-hostname enumeration as required, never wildcard, for CORS/cookies alike)
- Caddy's existing single container gains a second site block (no second Caddy container — only one process can bind host 80/443)

</code_context>

<specifics>
## Specific Ideas

No UI is involved — this is a backend-only infrastructure phase. No specific visual/branding requirements were raised.

</specifics>

<deferred>
## Deferred Ideas

None raised beyond what ROADMAP.md already scopes to Phase 9 (CI automation, GitHub Environments) and Phase 10 (hardening todos).

### Reviewed Todos (not folded)

`cross_reference_todos` ran `todo.match-phase 8` and returned 27 keyword-matched candidates. Beyond the one folded todo above, all others were reviewed and judged false-positive keyword collisions (generic terms like "phase", "code", "same", "set" matching unrelated backend/testing/docs debt — e.g. NullAway evaluation, ActivityAction package placement, TaskMovedEvent position asymmetry, session-ceiling-enforcer duplication). None describe nonprod environment isolation, Neon branching, Kafka isolation, or reset mechanisms, so none were presented as choices — presenting 26 irrelevant options would have been noise, not a real decision point. Not individually listed here; see `.planning/todos/pending/` directly if a re-triage is ever wanted.

</deferred>

---

*Phase: 8-Isolated Nonprod Environment, Live and Resettable*
*Context gathered: 2026-08-18*
