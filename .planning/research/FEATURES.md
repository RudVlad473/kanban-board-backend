# Feature Research

**Domain:** Nonprod/staging environment + Playwright E2E CI gate, for a solo/portfolio single-VPS backend feeding a not-yet-built separate frontend repo
**Researched:** 2026-08-17
**Confidence:** MEDIUM (patterns cross-checked across multiple independent web sources; no vendor-authoritative doc dive was needed since the mechanisms — GitHub Actions events, Neon branching, Docker Compose resource limits — are already used or directly analogous to what this project's `deploy.yml` and infra already do. Individual source confidence is LOW per this project's classify-confidence tier for general web search; treat specifics as directional, not vendor-guaranteed.)

## Framing: what this repo can actually build right now

The stated goal names two repos: this backend repo, and a **separate frontend repo that does not yet exist** (or is early-stage). The milestone's job is explicitly scoped to "provide that target" — i.e., everything below is filtered into:

- **Buildable now, independent of the frontend repo** — infra shape, data isolation, a reset/seed mechanism, CORS placeholder. All of this is verifiable today with `curl`/manual HTTP calls, with zero dependency on Playwright or the frontend repo existing.
- **Blocked on the frontend repo existing** — anything that requires a live GitHub Actions workflow *in that repo* to dispatch into, wait on, or read results from. This cannot be meaningfully built or tested from this repo alone; wiring it "speculatively" now means guessing at a workflow file, event name, and base-URL env var convention that repo hasn't chosen yet.

This split is the single biggest scoping decision for this milestone and is reflected in the MVP Definition below.

## Feature Landscape

### Table Stakes (Expected for This Capability to Exist)

Missing these = there is no real target for the frontend's E2E suite to hit, or the target is unsafe/unusable.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Long-lived, reachable nonprod deploy target (colocated resource-capped Compose stack on the existing Netcup VPS, or a second small free-tier VM) | A Playwright suite needs a stable base URL to point at; without this there is nothing to build a gate around | MEDIUM | Colocation is the pragmatic default at this scale (see Pattern Analysis) — a second dedicated VM is the fallback only if resource contention with prod proves real, not a default |
| DB isolation (separate Neon branch for nonprod, not shared with prod) | Prevents E2E test writes/deletes from touching real production data; already a native, free Neon capability this project depends on | LOW | Neon branching is documented as near-instant and cheap enough to use freely — this is the lowest-effort isolation win available |
| Kafka isolation (topic-name prefix per environment on the existing single Redpanda broker) | Prevents nonprod activity-log events from mixing into production's Kafka topics/consumer groups | LOW–MEDIUM | Topic-prefix isolation on the *existing* broker, not a second broker — see Anti-Features |
| Nonprod CI deploy job in this repo (reusing `deploy.yml`'s build → Flyway-verify → deploy pattern, targeted at nonprod) | The existing prod pipeline is the only proven deploy mechanism this project has; a second bespoke mechanism for nonprod would be unjustified extra surface | LOW–MEDIUM | Structurally the same job graph as today's `deploy-to-netcup`, parameterized by target host/env file |
| CORS entry for the eventual frontend's nonprod origin | Phase 07.1 already wired CORS for local dev origins only; a deployed nonprod frontend origin is a new, real origin the browser will send | LOW | Buildable now with a placeholder/expected subdomain even before the frontend repo exists |
| A safe, documented data reset/seed mechanism reachable before an E2E run (test-only reset endpoint, or a re-runnable seed script/Flyway-adjacent data script) | Standard Playwright-against-real-backend guidance converges on this: seed before, and guarantee a clean slate, rather than trust tests to self-clean | MEDIUM | This is the one piece worth building and *manually* verifying now (via curl) even with no consumer yet — it's independently useful and de-risks the eventual E2E wiring |
| DNS/TLS for nonprod (second Caddy vhost + subdomain) | Matches the existing production pattern (Caddy automatic HTTPS); a plain-HTTP or self-signed nonprod target would make it a worse stand-in for the real deploy shape it's meant to validate | LOW | Same mechanism already proven in prod; just a second vhost entry |

### Differentiators (Real Value, Justifiably Deferred Until the Frontend Repo Exists)

Not required for this milestone's *buildable* scope, but the right next step once there's a real consumer.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| `repository_dispatch` from this repo's nonprod-deploy job into the frontend repo, to kick off its Playwright suite against the freshly-deployed nonprod URL | Closes the loop the milestone is ultimately for — "deploy nonprod, then something runs E2E against it" | MEDIUM | Idiomatic mechanism for cross-repo triggering (custom `event_type`+payload, runs on target's default branch, needs a PAT with `repo` scope stored as a secret) — but genuinely cannot be finished/tested without the frontend repo's workflow file to dispatch into |
| Readiness/health gate before declaring nonprod deployed (poll a health endpoint until 200 before firing any downstream signal) | Avoids racing a not-yet-warmed-up container; matches the HTTPS health check already used to verify prod in Phase 5 | LOW | Cheap and buildable now, independent of the frontend repo — arguably should move up to Table Stakes if effort allows |
| Ephemeral Neon branch swap per E2E run (fresh branch off a known-good parent per run, instead of one stable nonprod branch + reset) | Stronger isolation — no cross-run bleed even under a bug in the reset mechanism | MEDIUM–HIGH | Requires the CI job to dynamically create a branch, rewrite the app's DB connection string, and restart/redeploy the app pointed at it — real extra CI wiring for a solo project with no PR-concurrency problem to justify it (see Pattern Analysis) |

### Anti-Features (Would Look Sophisticated, Wrong for This Project's Scale)

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|------------------|-------------|
| Per-PR ephemeral full-stack preview environments (spin up a whole VM/Compose stack + DB + Kafka per PR, tear down on close) | Feels like "the professional way" teams do staging | Solves a parallel-review-contention problem this project structurally cannot have (one developer, one branch in flight at a time); real platform-engineering cost to build and maintain the provisioning layer itself, for a benefit that never materializes at this scale | One static, long-lived nonprod environment (Table Stakes above) |
| A second dedicated Kafka/Redpanda broker for nonprod | "Isolation" instinct — separate infra per environment | Duplicates the exact resource-capped single-node pattern prod already uses, on a VPS that's explicitly capacity-constrained, for isolation that topic-name prefixing already delivers at near-zero cost | Topic-prefix isolation (`nonprod.` vs no prefix, or similar) on the one existing broker |
| Backend CI blocking/gating its own promotion-to-production on the frontend repo's E2E suite passing | The milestone's own prior-todo framing ("deploys to nonprod, runs Playwright, only then promotes to prod") reads this way | Inverts normal repo ownership — each repo should verify itself; making *this* repo's prod deploy depend on a suite that lives and runs in a *different* repo is a real coupling/blocking-radius risk (a frontend test flake or outage would block a backend-only fix from shipping) for a benefit ("prod backend regressions caught pre-promotion") that a good nonprod smoke/health check already covers more cheaply | Deploy to nonprod, verify health, promote to prod on backend's own tests passing (as today) — let the frontend repo's *own* CI gate on nonprod being reachable, not the reverse |
| Building the full cross-repo dispatch-and-wait wiring speculatively now, before the frontend repo exists | Wanting to "finish the whole feature" in one pass | Nothing to test it against yet; the frontend repo hasn't chosen its workflow file shape, event name, or base-URL env var convention — anything built now is a guess that will likely need rework once that repo's actual CI is written | Build and verify the nonprod target + reset mechanism now (fully testable via curl); defer the dispatch wiring to when the frontend repo's first workflow exists |

## Feature Dependencies

```
Nonprod deploy target (Compose stack, Caddy vhost)
    └──requires──> Neon nonprod branch
    └──requires──> Kafka topic-prefix isolation
    └──requires──> CORS entry for nonprod frontend origin

CI job: deploy-to-nonprod (this repo)
    └──requires──> Nonprod deploy target existing

Data reset/seed mechanism
    └──enhances──> Nonprod deploy target (buildable and manually verifiable independently — no hard dependency)

repository_dispatch → frontend Playwright run
    └──requires──> Nonprod deploy target reachable over HTTPS
    └──requires──> Frontend repo existing, with its own workflow to dispatch into   [BLOCKED — not yet buildable]
    └──enhances──> Readiness/health gate (should fire dispatch only after nonprod is confirmed warm)

Ephemeral Neon branch per E2E run
    └──conflicts with──> "one stable nonprod branch" simplicity — pick one, not both, for v1
```

### Dependency Notes

- **Nonprod deploy target requires Neon branch + Kafka isolation + CORS:** none of these are optional add-ons — a nonprod environment sharing prod's DB branch or Kafka topics isn't isolated at all, and without the CORS entry a browser-driven Playwright suite (not just Playwright's HTTP-only `request` context) cannot even complete a credentialed request against it.
- **`repository_dispatch` wiring is blocked on the frontend repo existing:** this is the one item in the whole landscape that is not a complexity/effort call but a hard external dependency. It should be tracked as an explicit follow-on, not attempted in this milestone.
- **Data reset mechanism enhances rather than requires the deploy target:** it's valuable and testable on its own (hit it with curl against the nonprod DB), so there's no reason to defer it just because the dispatch wiring is blocked.
- **Ephemeral-branch-per-run conflicts with stable-branch-plus-reset:** these are two different answers to the same problem (test isolation) — picking both adds CI complexity (dynamic connection-string rewrites) for marginal benefit over the simpler option at this project's scale (see Pattern Analysis).

## MVP Definition

### Launch With (v1 — this milestone, all buildable now regardless of frontend repo status)

- [ ] Nonprod Compose stack, resource-capped, colocated on the existing Netcup VPS (or a second free-tier VM if colocation proves resource-constrained) — mirrors prod's shape (app + Caddy)
- [ ] Neon nonprod branch, wired via its own env file/secrets, kept structurally separate from `.env.prod`
- [ ] Kafka topic-prefix isolation on the existing single Redpanda broker
- [ ] Second Caddy vhost + subdomain, real HTTPS (matches prod's proven pattern)
- [ ] CORS entry for the expected nonprod frontend origin (placeholder domain acceptable pre-frontend-repo)
- [ ] CI job in this repo that deploys to nonprod, reusing `deploy.yml`'s build/Flyway-verify/deploy job graph, parameterized by target
- [ ] A test-data reset/seed mechanism, built and manually verified (curl) even with no Playwright consumer yet
- [ ] Readiness/health check confirming the nonprod deploy actually came up before considering it "provided"

### Add After Validation (v1.x — once the frontend repo exists with its own first workflow)

- [ ] `repository_dispatch` (or equivalent) from this repo's nonprod-deploy job into the frontend repo's workflow
- [ ] Decide — with the frontend repo's actual shape in hand — whether backend-side waiting/gating on the frontend E2E result is even the right direction of coupling, versus the frontend repo gating itself on nonprod reachability

### Future Consideration (v2+ — only if a concrete need surfaces)

- [ ] Per-PR ephemeral environments (only relevant if this ever becomes a multi-contributor project)
- [ ] Ephemeral Neon branch per E2E run instead of stable-branch-plus-reset (only if the reset mechanism proves insufficiently isolated in practice)
- [ ] A second dedicated nonprod Kafka broker (only if topic-prefix isolation proves insufficient)

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| Nonprod deploy target (Compose + Caddy vhost) | HIGH | MEDIUM | P1 |
| Neon nonprod branch | HIGH | LOW | P1 |
| Kafka topic-prefix isolation | MEDIUM | LOW | P1 |
| CORS entry for nonprod frontend origin | MEDIUM | LOW | P1 |
| CI deploy-to-nonprod job | HIGH | MEDIUM | P1 |
| Data reset/seed mechanism | HIGH | MEDIUM | P1 |
| Readiness/health gate | MEDIUM | LOW | P1–P2 |
| `repository_dispatch` into frontend repo | HIGH (eventually) | MEDIUM | P2 (blocked) |
| Ephemeral Neon branch per run | LOW–MEDIUM | MEDIUM–HIGH | P3 |
| Per-PR ephemeral environments | LOW at this scale | HIGH | P3 (reject unless team scale changes) |
| Second dedicated nonprod Kafka broker | LOW | MEDIUM | P3 (reject unless prefix isolation fails) |

**Priority key:**
- P1: Buildable and warranted this milestone
- P2: Correct next step, but genuinely blocked on the frontend repo existing
- P3: Deferred/rejected at current scale — revisit only on a concrete triggering need

## Pattern Analysis

Reframed from a competitor comparison into a comparison of the standard shapes this kind of setup takes, evaluated against this project's actual scale (solo developer, one small VPS, no dedicated ops team, no PR-concurrency problem).

### Staging environment shape

| Criterion | Static long-lived shared staging | Ephemeral per-PR preview | Shared staging, data reset per run |
|-----------|-----------------------------------|---------------------------|--------------------------------------|
| Fits solo/portfolio scale | Yes — no contention to solve | No — solves a problem this project doesn't have | Yes |
| Infra/CI build cost | LOW–MEDIUM (one Compose stack, one CI job) | HIGH (dynamic provisioning + teardown automation) | LOW–MEDIUM (same infra as static, plus a reset step) |
| Matches prod's own shape (validates deploy pipeline) | Yes | Yes, but a different pipeline than prod's | Yes |
| Recommendation | **This project's actual answer**, combined with the data-reset column | Reject — gold-plating for this scale | **This is really the same environment as column 1, with a reset discipline layered on — treat as one recommendation, not three separate options** |

### Cross-repo CI trigger for the eventual frontend E2E run

| Criterion | `repository_dispatch` | `workflow_dispatch` (cross-repo via API) | Polling "wait-for-workflow" actions |
|-----------|------------------------|--------------------------------------------|----------------------------------------|
| Purpose-built for cross-repo, event-driven triggering | Yes — custom `event_type`+payload is exactly this shape | Partial — more naturally a manual/same-repo trigger; usable cross-repo via API but less idiomatic for this | N/A — these poll for a *result*, not trigger a run |
| Needed if this repo also wants to *block on* the frontend's result | No (fire-and-forget) | No | Yes, if that blocking behavior is actually wanted |
| Auth requirement | PAT with `repo` scope (not `GITHUB_TOKEN`) stored as a secret | Same | Same, plus repeated polling calls against GitHub's REST API (rate-limit aware interval) |
| Recommendation | **Right mechanism for "deploy nonprod, then notify the frontend repo"** once that repo exists | Not the idiomatic choice here | Only add if backend-gating-on-frontend is deliberately chosen (see Anti-Features doubt above) — otherwise skip entirely |

### Data-seeding/reset strategy

| Criterion | Fresh Neon branch per E2E run | Stable nonprod branch + reset endpoint/script |
|-----------|-------------------------------|-----------------------------------------------|
| Isolation strength | Highest — literally can't leak state between runs | Good, contingent on the reset mechanism being correct and complete (including Kafka topic state, not just Postgres) |
| CI wiring cost | Higher — needs dynamic branch create/delete and a connection-string rewrite + app restart per run | Lower — one stable connection string, one HTTP call or script invocation before each run |
| Cost/overhead at Neon's advertised branching speed/pricing | Low per Neon's own model (branching described as near-instant, usable per-run without meaningful overhead) | Lowest — no extra branch lifecycle to manage at all |
| Recommendation | Legitimate v2 upgrade once the reset-endpoint approach is proven and a real E2E suite exists to notice cross-run bleed | **Right choice for v1** — lower cost, and this project's own Kafka-backed activity log needs an equivalent reset step regardless (topic/consumer-group state), which the "fresh Postgres branch" approach alone would not solve either |

## Sources

- Autonoma AI — [Staging Environment vs Preview Environment](https://getautonoma.com/blog/staging-environment-vs-preview-environment)
- Upsun — [Fix the staging bottleneck with preview environments](https://upsun.com/blog/staging-bottleneck-preview-environments/)
- Shipyard — [A Guide to Preview Environments](https://shipyard.build/preview-environments/)
- GitHub Docs — [Events that trigger workflows](https://docs.github.com/actions/using-workflows/events-that-trigger-workflows)
- Marc Nuri — [Triggering GitHub Actions across different repositories](https://blog.marcnuri.com/triggering-github-actions-across-different-repositories)
- Juraj Sim — [Calling vs Dispatching: GitHub Actions Comparison](https://jurajsim.hashnode.dev/a-comparison-of-calling-vs-dispatching-workflows-in-github-actions)
- OneUptime — [How to Set Up Cross-Repository Workflows in GitHub Actions](https://oneuptime.com/blog/post/2025-12-20-cross-repository-workflows-github-actions/view)
- Neon — [Database branching workflow primer](https://neon.com/docs/get-started/workflow-primer)
- Neon — [A database for every preview environment using Neon, GitHub Actions, and Vercel](https://neon.com/blog/branching-with-preview-environments)
- Neon — [Database Branching Workflows](https://neon.com/branching)
- Neon FAQs — [Which Postgres services integrate with GitHub Actions to create a fresh database for every pull request automatically?](https://neon.com/faqs/postgres-services-github-actions-fresh-database-pull-requests)
- Qaskills — [Playwright test data management guide](https://qaskills.sh/blog/playwright-test-data-management-guide-2026)
- Seedfast — [E2E Test Fixtures: Generate Playwright & Cypress Data](https://seedfa.st/blog/e2e-test-fixtures)
- techresolve — [Docker compose single file or multiple yaml files?](https://techresolve.blog/2025/12/23/docker-compose-single-file-or-multiple-yaml-files/)
- Tomer Ben David — [Docker Compose for Side Projects on VPS](https://medium.com/@Tom1212121/docker-compose-for-side-projects-on-vps-cd2b6b380081)
- DCHost — [Hosting Architecture For Dev, Staging And Production: One VPS Or Separate Servers?](https://www.dchost.com/blog/en/hosting-architecture-for-dev-staging-and-production-one-vps-or-separate-servers/)
- GitHub Marketplace — [Trigger Workflow and Wait](https://github.com/marketplace/actions/trigger-workflow-and-wait)
- GitHub — [convictional/trigger-workflow-and-wait](https://github.com/convictional/trigger-workflow-and-wait)
- GitHub Marketplace — [Wait for workflow](https://github.com/marketplace/actions/wait-for-workflow)
- This project's own `.github/workflows/deploy.yml` and `.planning/PROJECT.md` — used to ground every recommendation above in the actual existing stack (Netcup VPS Lite 2, Neon, self-hosted single-node Redpanda, Caddy, GitHub Actions) rather than generic advice

---
*Feature research for: nonprod/staging environment + Playwright E2E CI gate*
*Researched: 2026-08-17*
