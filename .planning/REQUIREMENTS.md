# Requirements: Kanban Board Backend — v1.3 Nonprod Environment & CI Hardening

**Defined:** 2026-08-17
**Core Value:** Provision a resource-shrunk nonprod/staging environment so a future frontend repo's Playwright E2E suite has a real, non-mocked target — bundled with the CI/deploy hardening todos that were unblocked once v1.2's deploy.yml rewrite settled.

## v1 Requirements

### Nonprod Infrastructure

- [x] **NONPROD-01**: A nonprod Compose stack (app + Redpanda) is colocated on the existing Netcup VPS Lite 2, name-pinned (directory, Compose project name, container names, network name, volume names all distinct from production) and gated via Docker Compose `profiles:`
- [x] **NONPROD-02**: Nonprod's database is an isolated Neon branch, wired via its own env file/secrets structurally separate from `.env.prod`
- [x] **NONPROD-03**: Nonprod's Kafka/Schema Registry isolation is a second, separate Redpanda broker instance — not topic-name-prefixing on the shared production broker — because the Avro Schema Registry's `RecordNameStrategy` keys compatibility history by class name, not topic, so a shared broker would leave the registry itself shared even with prefixed topics
- [x] **NONPROD-04**: Nonprod is reachable over real HTTPS at its own stable hostname, via a second Caddy site block and a second DuckDNS subdomain (enumerated exactly, never wildcard-matched against the shared `*.duckdns.org` suffix), matching production's automatic-TLS pattern
- [x] **NONPROD-05**: CORS is configured for the expected nonprod frontend origin (a placeholder domain is acceptable ahead of the frontend repo existing), reusing the existing externalized `app.cors.allowed-origins` config with zero code change
- [x] **NONPROD-06**: Nonprod's actual Redpanda memory floor (`mem_limit` / `--memory`) is measured live via iterative restart cycles — not assumed from arithmetic (e.g., "half of prod's cap") — with a documented, exercised fallback to a second small VPS (~€4/month) if no safe value is found on the colocated host

### CI Deploy Automation

- [x] **CI-01**: A CI job deploys to nonprod on every push to master, extending `deploy.yml`'s existing build → Flyway-verify → deploy job graph (`flyway-verify-nonprod`, `deploy-to-nonprod`), parameterized by target, running parallel to — never gating, never gated by — the existing `deploy-to-netcup` production job
- [x] **CI-02**: `production` and `staging` GitHub Environments are introduced as a prerequisite before the nonprod deploy job is added, so the nonprod job does not inherit full, unscoped access to all 10 existing repository secrets by default
- [x] **CI-03**: Nonprod images are pushed to a Docker Hub repository separate from production's, so the existing `cleanup-old-images` job's per-run tag-deletion sweep cannot delete nonprod's currently-running tag on the next production push
- [x] **CI-04**: A readiness/health check polls the nonprod deploy's health endpoint until it returns 200 before the deploy is considered complete, mirroring the HTTPS health check already used to verify production in v1.2 Phase 5
- [x] **CI-05**: A CI job registers the application's Avro schemas against both the production and the nonprod schema registries on every deploy, reusing the existing `AvroSchemaRegistrar`/`PropertiesLauncher` one-off-container mechanism — replacing the hand-run registration in `docs/INFRA_RUNBOOK.md`'s "Manual deploy — Plan 05-04 Task 2" and Phase 8 plan 08-01's manual nonprod invocation — rather than introducing a new tool; it extends `deploy.yml`'s existing job graph with the nonprod registration running parallel to, never gating and never gated by, the production deploy path, so one CI run keeps both registries in step with each other, while within each single environment registration completes before that environment's app serves traffic, because `spring.kafka.producer.properties.auto.register.schemas=false` makes an unregistered subject a runtime publish failure rather than a lazy self-heal

### Data Reset Mechanism

- [x] **RESET-01**: A test-data reset/seed mechanism exists for nonprod, reachable and manually verifiable via curl (no Playwright consumer required to exist yet), covering both Postgres state and Kafka/activity-log state — not Postgres alone

### API Contract Completeness

- [x] **API-01**: The generated OpenAPI spec (`/v3/api-docs`) declares the `ProblemDetail` error envelope on every operation that can produce one — springdoc's default reflection-based generation only documents a controller method's declared return type, never a `@ControllerAdvice`/`GlobalExceptionHandler`'s intercepted exception paths, so today every `400/401/403/404/409/500` this API actually returns at runtime is undocumented in the spec, forcing consumers (the frontend repo) to hand-author error types instead of generating them. Close the gap with a global `OpenApiCustomizer`/`GlobalOpenApiCustomizer` bean (not per-endpoint `@ApiResponse` annotation, which has to be remembered on every future controller method) that injects the `ProblemDetail` schema into every operation's `responses` map, plus an automated regression guard — a test asserting the live-generated spec declares the standard error codes, and/or a CI lint step (e.g. Spectral against `/v3/api-docs`) — so this cannot silently regress the way it silently arrived. Discovered live by a frontend-side planning agent during v1.3 Phase 9 planning (2026-08-18); not caught by this repo's own e2e/integration tests because those assert runtime response bodies, never the separately-generated OpenAPI document itself.

### CI/Deploy Hardening (bundled todos, unblocked by v1.2 Phase 5's deploy.yml rewrite)

- [ ] **HARDEN-01**: `.github/dependabot.yml` gains a `package-ecosystem: "github-actions"` entry, alongside the existing `gradle` entry (resolves pending todo 2026-08-13-add-github-actions-ecosystem-to-dependabot-after-deploy-rewrite.md)
- [ ] **HARDEN-02**: CI runs a TruffleHog live-credential verification pass in `secret-scan.yml`, complementing gitleaks' regex/entropy-only detection with a check for whether a matched credential is currently live and exploitable (resolves pending todo 2026-08-16-add-a-trufflehog-live-credential-verification-pass-in-ci.md)
- [ ] **HARDEN-03**: `uses:` references in `deploy.yml` and `security-scan.yml` are pinned to commit digests rather than mutable tags, consistent with this repo's own existing scanner precedent (resolves pending todo 2026-08-16-digest-pin-github-actions-mutable-tags-are-currently-trusted-by-tag-only.md)
- [ ] **HARDEN-04**: `deploy.yml`'s `run-tests` job's `Set up Java` step sets `cache: 'gradle'`, matching the existing precedent in `security-scan.yml` (resolves pending todo 2026-08-16-add-gradle-cache-to-deploy-yml-run-tests-job.md)
- [ ] **HARDEN-05**: The pre-commit gitleaks hook works correctly when invoked from a worktree created outside the main repo tree (resolves pending todo 2026-08-16-gitleaks-hook-cannot-scan-a-worktree-created-outside-the-main-repo-tree.md)
- [ ] **HARDEN-06**: `security-scan.yml`'s stale `Set up Java` comment and its still-outdated `checkout@v3`/`setup-java@v4` references are corrected (resolves pending todo 2026-08-16-security-scan-yml-stale-comment-and-stale-actions-after-260816-sv1.md)
- [ ] **HARDEN-07**: The session cookie has the `Secure` flag set in both `application.properties` and `application-test.properties`, now that real TLS exists in production (resolves pending todo 2026-08-10-set-secure-flag-on-session-cookie-once-real-tls-exists.md)
- [ ] **HARDEN-08**: README is expanded from its current trimmed front door into a full project architecture showcase (resolves pending todo 2026-08-16-expand-readme-into-a-full-project-architecture-showcase.md)

## Future Requirements

Deferred until the frontend repo exists — tracked, not attempted this milestone:

- **FRONTEND-DISPATCH-V2**: `repository_dispatch` (or equivalent) from this repo's nonprod-deploy job into the frontend repo's own workflow, to trigger its Playwright suite against the freshly-deployed nonprod URL — hard-blocked on that repo having a workflow file to dispatch into
- **FRONTEND-COUPLING-V2**: Once the frontend repo's actual CI shape is known, revisit whether any backend-side waiting/gating on its E2E result is warranted (default per this milestone's decision: no — see Out of Scope)

## Out of Scope

- **Backend gating its own production promotion on the frontend repo's E2E results** — inverts repo ownership; a frontend test flake or outage would block an unrelated backend hotfix. The frontend repo gates itself on nonprod reachability instead, since nonprod deploys continuously on every master push and is therefore already a stable target. *(User-confirmed decision, 2026-08-17.)*
- **A second dedicated Netcup VPS for nonprod, provisioned up front** — research measured ~2.65GB unreserved headroom under current production caps; colocation is the default, with the second-VPS path held as an exercised fallback only if live measurement (NONPROD-06) shows colocation doesn't hold. *(User-confirmed decision, 2026-08-17.)*
- **Per-PR ephemeral full-stack preview environments** — solves a parallel-review-contention problem this solo project structurally does not have; real platform-engineering cost for no realized benefit at this scale
- **Ephemeral Neon branch per E2E run** — a legitimate v2 upgrade once the reset-endpoint approach (RESET-01) is proven and a real E2E suite exists to notice cross-run bleed; unjustified CI wiring cost (dynamic branch create/delete + connection-string rewrite) for a solo project today
- **A second Neon *project* (vs. a branch)** — over-engineering for one persistent staging target; a branch is free and near-instant on the existing plan tier

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| NONPROD-01 | Phase 8 | Complete |
| NONPROD-02 | Phase 8 | Complete |
| NONPROD-03 | Phase 8 | Complete |
| NONPROD-04 | Phase 8 | Complete |
| NONPROD-05 | Phase 8 | Complete |
| NONPROD-06 | Phase 8 | Complete |
| RESET-01 | Phase 8 | Complete |
| CI-01 | Phase 9 | Complete |
| CI-02 | Phase 9 | Complete |
| CI-03 | Phase 9 | Complete |
| CI-04 | Phase 9 | Complete |
| CI-05 | Phase 9 | Complete |
| API-01 | Phase 9 | Complete |
| HARDEN-01 | Phase 10 | Pending |
| HARDEN-02 | Phase 10 | Pending |
| HARDEN-03 | Phase 10 | Pending |
| HARDEN-04 | Phase 10 | Pending |
| HARDEN-05 | Phase 10 | Pending |
| HARDEN-06 | Phase 10 | Pending |
| HARDEN-07 | Phase 10 | Pending |
| HARDEN-08 | Phase 10 | Pending |

**Coverage: 21/21 v1 requirements mapped, each to exactly one phase. No orphans, no duplicates.**

Phase mapping rationale:

- **Phase 8 (Isolated Nonprod Environment, Live and Resettable)** — every NONPROD-* requirement plus RESET-01. NONPROD-03 (second Redpanda broker) cannot be separated from NONPROD-01/06: the broker *is* part of the Compose stack, and NONPROD-06's memory floor is that broker's. RESET-01 was folded in rather than left as a one-requirement phase; it needs the running stack to verify against and shares Phase 8's manual, curl-driven verification mode.
- **Phase 9 (Nonprod Continuous Deploy & Scoped CI Credentials)** — all five CI-* requirements plus API-01. CI-02 (GitHub Environments) is sequenced *within* this phase ahead of CI-01, so the nonprod job never runs with unscoped production secrets and then gets re-gated afterwards. CI-05 sits here rather than in Phase 8 because Phase 8 registers nonprod's schemas by hand as part of bring-up, and CI-05 replaces both that hand-run and production's with the automated step, so it depends on nonprod existing but belongs with the automation work; it also inherits CI-02's environment scoping, because the registry publishes no host port and the step therefore reaches the broker over the same SSH path the deploy job uses. **API-01 is a deliberate, user-confirmed scope exception** (2026-08-18) rather than a natural fit — it is API-contract completeness, not CI/deploy work, and by this same document's own precedent (see Phase 10 below) would ordinarily be kept out. It was folded in anyway because it was discovered live during this phase's planning by a downstream frontend consumer and the user chose one reviewable PR over strict scope purity for this specific item.
- **Phase 10 (CI & Deploy Hardening)** — all eight HARDEN-* requirements, kept as their own phase because they have no dependency on the nonprod build, two of them (HARDEN-07 cookie flag, HARDEN-08 README) are not CI work at all, and folding them into Phase 9 would blur that phase's goal. Ordered last so HARDEN-03's digest-pinning covers `deploy.yml`'s final job graph including the new nonprod jobs, and so HARDEN-07's `Secure` cookie flag lands only once every deployed environment is TLS-served.

---
*Last updated: 2026-08-18 — traceability filled in by roadmapper (v1.3 Phases 8-10); CI-05 added by quick task 260818-ied; API-01 added and folded into Phase 9 by explicit user decision, 2026-08-18*
