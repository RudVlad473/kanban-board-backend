# Phase 5 — Multi-Source Coverage Audit

**Audited:** 2026-08-04 at plan time. Every item from all four source artifacts is COVERED by a plan or EXCLUDED with a reason. No item is silently dropped and no item is reduced in scope.

## GOAL — ROADMAP Phase 5 goal and success criteria

| # | Goal element | Status | Plan |
|---|---|---|---|
| G-1 | Publicly reachable over real HTTPS on the Oracle A1 Flex VM, Docker, restart policy + healthcheck | COVERED | 05-01 (health endpoint), 05-02 (manifest), 05-03 (VM + DNS), 05-04 (proven live) |
| G-2 | Neon serverless Postgres, pooled connection string, TLS, HikariCP sized for cold start, zero JPA changes | COVERED | 05-01 (datasource + pool), 05-03 (project), 05-04 (proven by write surviving restart) |
| G-3 | Resource-capped single-node Redpanda that cannot starve the JVM, + Phase 4's registry suite re-run green against the VM | COVERED | 05-02 (explicit caps), 05-04 task 2 (registry cutover + suite), 05-04 task 3 (caps set from measurement) |
| G-4 | Push to master triggers automated build-and-deploy with fresh SSH credentials, gated by DDL verification against Neon's direct endpoint | COVERED | 05-05 (all three tasks) |
| G-5 | Only 80/443 externally reachable, verified by outside scan across all three OCI layers; 9092 never internet-facing; log drivers capped | COVERED | 05-02 (log caps + no published ports), 05-03 (three-layer setup), 05-06 task 1 (external audit), 05-06 task 2 (rotation observed) |

## REQ — REQUIREMENTS.md phase requirement IDs

| ID | Status | Plans |
|---|---|---|
| INFRA-01 | COVERED | 05-01, 05-02, 05-03, 05-04 |
| INFRA-02 | COVERED | 05-01, 05-03, 05-04 |
| INFRA-03 | COVERED | 05-02, 05-04 |
| INFRA-04 | COVERED | 05-02, 05-03, 05-04 |
| INFRA-05 | COVERED | 05-05, 05-06 |
| INFRA-06 | COVERED | 05-01, 05-04, 05-05 |
| INFRA-07 | COVERED | 05-02, 05-06 |
| INFRA-08 | COVERED | 05-03, 05-06 |

Unmapped: 0.

## RESEARCH — 05-RESEARCH.md findings, pitfalls, patterns and open questions

| Item | Status | Plan / disposition |
|---|---|---|
| Pitfall A — pooled endpoint vs prepared-statement caching | COVERED | 05-01 task 2 (URL parameter + written rationale) |
| Pitfall B — ephemeral vs Reserved public IP | COVERED | 05-03 task 1 (explicit decision checkpoint, one-way rated) |
| Pitfall C — INFRA-06 has no migration tool; use the idempotent bridge scripts | COVERED | 05-05 task 2 (no Flyway/Liquibase, negative-grepped) |
| Pattern 1 — Actuator health must be explicitly permitted in Spring Security | COVERED | 05-01 task 1 |
| Pattern 2 — production Redpanda must drop the dev preset | COVERED | 05-02 task 1 (negative-grepped) |
| Anti-pattern — publishing broker/registry ports in production | COVERED | 05-02 task 1, 05-06 task 1 |
| Anti-pattern — assuming the runtime image needs a package for the healthcheck | COVERED | 05-02 task 1 (action states no Dockerfile change is needed) |
| Runtime state — no data to migrate; Neon is a fresh empty provision | COVERED | 05-04 task 1 (DDL applied before app start) |
| Runtime state — stale AWS-era secrets must be revoked, not superseded | COVERED | 05-06 task 3 |
| Runtime state — accumulated Docker Hub tags must be pruned | COVERED | 05-06 task 3 |
| Actuator exposure must be `health` only, never a wildcard | COVERED | 05-01 task 1 (negative-grepped, threat T-05-01) |
| Deploy keypair generated locally, never on the VM | COVERED | 05-05 task 1 |
| Host-key pinning instead of the old job's absent verification | COVERED | 05-05 task 3 (negative-grepped) |
| Caddy `/data` must be a named volume (rate-limit protection) | COVERED | 05-02 tasks 1 and 2 |
| Log driver caps on every service | COVERED | 05-02 task 1, 05-06 task 2 |
| Three-layer firewall audited together, verified externally | COVERED | 05-03 task 2, 05-06 task 1 |
| Truncated delete command in the unused-image cleanup job | COVERED | 05-05 task 3 |
| Both cleanup jobs dormant behind the disabled deploy job | COVERED | 05-05 task 3 (rename), 05-06 task 3 (observed to run) |
| Open Question 1 — standalone prod compose vs overlay | RESOLVED | 05-02 objective: standalone, with the trade-off matrix and the structural reason |
| Open Question 2 — is the image repository public? | COVERED | 05-05 task 3 (resolve before relying on an unauthenticated pull; add login if private) |
| Open Question 3 — does Always Free include a Reserved IP? | COVERED | 05-03 task 1 (resolved in-console during the same check as the shape) |
| Assumption A2 — scp-action tag not independently confirmed | COVERED | 05-05 task 3 (re-confirm both tags at execution time) |
| Assumption A3 — no LISTEN/advisory-lock/temp-table usage | COVERED | 05-01 task 2 (explicit grep, halt-and-report if a genuine hit) |
| Assumption A4 — Reserved IP free-tier availability | COVERED | 05-03 task 1 (fallback option with recorded residual risk) |
| Assumption A5 — Docker Hub repo visibility | COVERED | 05-05 task 3 |
| Warning against enabling virtual threads to compensate for fewer cores | COVERED | 05-04 task 3 (explicitly forbidden, negative-grepped) |

## CONTEXT — 05-CONTEXT.md locked decisions and folded todos

| ID | Decision | Status | Plan |
|---|---|---|---|
| D-01 | Free subdomain rather than a paid domain | COVERED | 05-02 task 2 (injectable hostname, reversibility noted), 05-03 task 3 (registration + A record) |
| D-02 | Guided step-by-step secret handling, one at a time | COVERED | 05-03 (all tasks), 05-05 task 1 (secrets table walked in dependency order) |
| D-03 | Manual deploy proven on real Oracle infra before CI/CD automation | COVERED | 05-04 is the tracer and Wave 2; 05-05 (automation) is Wave 3 and depends on it |
| Folded todo | Infra architecture diagram before live onboarding | COVERED | 05-02 task 3 (authored + todo closed), 05-06 task 3 (promoted to current state) |
| Folded todo | Rewrite the disabled deploy job | COVERED | 05-05 task 3 (rewritten, both side-findings addressed, todo closed) |
| Discretion | Verify tenancy shape rather than assume the generous figure | HONORED | 05-03 task 1 decides; 05-04 task 3 tunes from the verified figure |
| Discretion | Compose file structure | HONORED | 05-02: standalone file, with recorded reasoning |
| Discretion | Redpanda cap values deferred to measurement | HONORED | 05-02 sets provisional values flagged as such; 05-04 task 3 replaces them with measured ones |
| Discretion | Free-subdomain service choice | HONORED | 05-03 task 3 (DuckDNS as reference, any A-record-pointable host works) |
| Discretion | SSH deploy mechanics | HONORED | 05-05 trade-off matrix confirms the maintained actions with fingerprint pinning |
| Discretion | Oracle provisioning specifics | HONORED | 05-03 tasks 1 and 2 |

## Planner-added scope (not in any source artifact)

| Item | Why added | Plan |
|---|---|---|
| Baseline schema DDL script | Reading the three bridge scripts directly shows two are ALTER-only deltas that fail against an empty database, and production leaves Hibernate ddl-auto unset. Without this, the app cannot start against Neon and INFRA-06's job cannot pass. This is a prerequisite for a stated requirement, not new scope. | 05-01 task 3 |
| Docker's published-port rules bypass the input chain | The external-audit requirement is meaningless if the audit is a local rule listing; this is why the scan must come from off-VM. | 05-03, 05-06 task 1 |
| Workflow concurrency control | Two pushes in quick succession can interleave a partially copied manifest with a running deploy. | 05-05 task 3 |

## Exclusions (not gaps)

| Item | Reason |
|---|---|
| Full observability stack, blue-green deploys, multi-broker HA | Deferred to v2 as INFRA-V2-01/02/03 in REQUIREMENTS.md and re-confirmed in CONTEXT.md's Deferred Ideas |
| Pre-merge schema-compatibility CI check, compatibility-mode rationale doc | Deferred to v2 as SCHEMA-V2-01/02 during Phase 4 |
| Java 25 bump, dependency vulnerability scan, PMD/Checkstyle/SpotBugs, alert-service exploration, sequence diagram | Reviewed and explicitly left deferred in CONTEXT.md's Reviewed Todos — no topical overlap with infra deployment |
| Flyway / Liquibase / any migration framework | Explicitly out of scope per RESEARCH.md Pitfall C; belongs to a future epic |
| Self-hosted CI runner, AWS OIDC, Protobuf, separate registry container, dual-format topic migration | Listed in REQUIREMENTS.md's Out of Scope table with reasons |

**Result: no unplanned items. No feature was reduced, staged, or deferred without an explicit exclusion reason above.**
