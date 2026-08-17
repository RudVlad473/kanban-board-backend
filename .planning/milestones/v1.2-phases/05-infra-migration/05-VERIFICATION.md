---
phase: 05-infra-migration
verified: 2026-08-17T00:00:00Z
status: passed
score: 5/5 roadmap success criteria verified (8/8 requirement IDs satisfied)
behavior_unverified: 0
overrides_applied: 0
re_verification: No — initial verification
---

# Phase 5: Infra Migration Verification Report

**Phase Goal:** The app is redeployed on a cost-guarded, always-free/near-free stack — reachable over real HTTPS, backed by Neon and a resource-capped Redpanda broker, deployed automatically on merge to `master` — with Phase 4's Schema Registry repointed from local/standalone to the production Redpanda registry and re-verified against it.

**Verified:** 2026-08-17
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

This verification does not trust any SUMMARY.md claim at face value. Every truth below was
re-checked against either a live external probe run by this verifier (curl/TCP-connect against the
real production host), the actual `.github/workflows/deploy.yml` file, `gh secret list`/`gh run
view` against the real repository, or direct inspection of the running-config artifacts
(`docker-compose.prod.yml`, `application.properties`, `SecurityConfiguration.java`). Where a claim
could not be independently reproduced (e.g. events from a past console session), it is marked as
such rather than accepted on prose alone.

### Observable Truths (ROADMAP Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | App is publicly reachable over real HTTPS (not bare HTTP/IP), running in Docker with `restart: unless-stopped` and a container healthcheck | ✓ VERIFIED | Live: `curl https://kanban-board-rud-vlad-473.duckdns.org/api/actuator/health` → `200 {"status":"UP"}` this session; `curl http://...` → `308` redirect to HTTPS. `docker-compose.prod.yml` sets `restart: unless-stopped` on all 3 services and an app healthcheck asserting the `UP` token (`docker-compose.prod.yml:108-114`). **Note:** the deploy target is a Netcup VPS (Vienna), not the Oracle Cloud A1 Flex VM the roadmap text names — a documented, screened provider pivot (05-03-SUMMARY.md; 10+ hrs / 200+ failed Oracle provisioning attempts) that preserves the substance of SC1 (public HTTPS, Docker, restart policy, healthcheck) on a different always-free-adjacent host. See "Provider Substitution" note below. |
| 2 | Production DB is Neon serverless Postgres via a pooled connection string (`sslmode=require`, HikariCP sized for cold-start/pooling), zero JPA/Hibernate code changes | ✓ VERIFIED | `application.properties` carries `spring.datasource.hikari.*` (5 explicit properties) and a `DB_JDBC_PARAMS`/`DB_URL_PARAMS`-injected query string; `git diff --name-only src/main/java` for 05-01 shows only `SecurityConfiguration.java`/`ApiPaths.java` touched — no entity/repository/mapper change (05-01-SUMMARY.md). Live proof of a real Neon write surviving an app-container restart is documented in `docs/INFRA_RUNBOOK.md`'s "Manual deploy — Plan 05-04 Task 1" section (signup + board create + `docker compose restart app` + re-fetch, board still present). |
| 3 | Kafka broker is a resource-capped, single-node Redpanda instance that cannot starve the app JVM, and Phase 4's Schema Registry verification suite is re-run green against Redpanda's production registry | ✓ VERIFIED | `docker-compose.prod.yml`'s `redpanda` service carries `--overprovisioned`/`--smp 1`/`--memory 2G` plus a `mem_limit: 2200m` cgroup backstop, set from a measured 54-request burst workload against the verified 4 vCPU/7.8GiB Netcup shape (`docs/INFRA_RUNBOOK.md`, "Manual deploy — Plan 05-04 Task 3"). Registry cutover: `grep -rn "schema.registry.url" src/main --include=*.java` finds no hardcoded URL; production registry independently queried live (14 subjects, `BACKWARD` compatibility, live reject/accept pair) and the 5 named Phase 4 test classes ran 28/28 green locally as regression (`docs/INFRA_RUNBOOK.md`, "Task 2"); a real public-API mutation produced a real `activity_log` row through the live pipeline. |
| 4 | A push to `master` triggers an automated GH Actions build-and-deploy using freshly generated SSH credentials (not reused AWS-era secrets), gated by a pre-merge Flyway/DDL verification step against Neon's direct endpoint | ✓ VERIFIED | `.github/workflows/deploy.yml` job graph confirmed live: `deploy-to-netcup` `needs: [build-and-push-docker-image, flyway-verify]`; both `cleanup-old-images`/`cleanup-unused-image` `needs: [deploy-to-netcup, ...]`. `flyway-verify` fingerprint-guards against the pooled endpoint and applies Flyway V1-V7 against Neon's direct connection string. `gh secret list` (live, this session) shows exactly 10 secrets — `DB_HOST/NAME/PASS/USER`, `DOCKERHUB_TOKEN`, `NETCUP_DEPLOY_USER/HOST/HOST_FINGERPRINT/SSH_KEY`, `NVD_API_KEY` — **no** `EC2_SSH_KEY`/`EC2_HOST`/`EC2_USER`. `grep -rciE "EC2_SSH_KEY\|EC2_HOST\|EC2_USER" .github/` returns 0 (verified live). `gh run view 32017867204` (this session): `conclusion: success`, `flyway-verify` and `deploy-to-netcup` both succeeded on head SHA `faacda4`. |
| 5 | Only ports 80/443 (plus 22 for SSH admin) are externally reachable, verified by an outside scan across all network layers; Redpanda's 9092 is never internet-facing; Docker log drivers are capped | ✓ VERIFIED | Live spot-check this session: TCP connect to `159.195.114.230:8080/8081/9092/5432` all closed/filtered; `docs/INFRA_RUNBOOK.md`'s "External Network Audit — Plan 05-06 Task 1" documents 3 independent full-range (1-65535) off-VM scans (196,605 probes) confirming exactly 22/80/443 open on both IP and hostname, with one transient false-negative caught and explained, plus rule persistence proven across a real `systemctl restart docker` and a real `reboot`. Log caps: `docker inspect` on all 3 live containers (documented in "Log Rotation Observation — Plan 05-06 Task 2") shows `json-file`/`max-size=10m`/`max-file=3` in effect; rotation-and-deletion proven deterministically via a throwaway container (file count held at exactly 3 despite ~76MB generated), aggregate bound (~90MB) vs. real available disk (236GB) computed. **Note:** the roadmap/requirement text says "OCI's three network layers"; the actual (pivoted) deploy target has a genuinely different two-layer model (OS `iptables` + Netcup Cloud Firewall), documented as an open wording-only item in `WINDOWS.md` entry #2 — the underlying intent (multiple independently-configured layers, externally verified) is met. |

**Score:** 5/5 roadmap Success Criteria verified, all with live or CI-run evidence gathered independently by this verifier, not only from SUMMARY prose.

### Requirements Coverage (all 8 IDs cross-referenced against REQUIREMENTS.md)

| Requirement | Owning Plan(s) | Description (abridged) | Status | Evidence |
|---|---|---|---|---|
| INFRA-01 | 05-01, 05-02, 05-03, 05-04 | Deployed on Docker with `restart: unless-stopped` + healthcheck | ✓ SATISFIED | Live HTTPS 200 + `docker-compose.prod.yml` restart/healthcheck; deploy target is Netcup, not Oracle (documented pivot) |
| INFRA-02 | 05-01, 05-03, 05-04 | Neon pooled connection, `sslmode=require`, sized HikariCP, zero JPA changes | ✓ SATISFIED | `application.properties` Hikari block; live restart-survival write proof in runbook |
| INFRA-03 | 05-02, 05-04 | Redpanda resource-capped, doesn't starve JVM | ✓ SATISFIED | Measured caps + `mem_limit` backstop, live restart-healthy |
| INFRA-04 | 05-02, 05-03, 05-04 | Real HTTPS via Caddy, automatic TLS | ✓ SATISFIED | Live Let's Encrypt cert, HTTP→HTTPS 308 redirect confirmed this session |
| INFRA-05 | 05-05, 05-06 | Automated GH Actions deploy, fresh SSH creds, no AWS-era secrets reused | ✓ SATISFIED | `gh secret list` clean; `deploy-to-netcup` job green on real runs; Docker Hub tag-pruning bug found+fixed+**live-verified this session** (CI run `32017867204`, `cleanup-old-images` succeeded, 32/32 tags deleted, `FAILED=0`) |
| INFRA-06 | 05-01, 05-04, 05-05 | Pre-merge Flyway/DDL verification against Neon's direct endpoint | ✓ SATISFIED | `flyway-verify` job gates `deploy-to-netcup` in the dependency graph; pooler-marker guard proven live by a deliberate failing run then revert (05-05-SUMMARY.md) |
| INFRA-07 | 05-02, 05-06 | Docker log drivers capped so logs can't fill the disk | ✓ SATISFIED | Deterministic rotation proof + measured aggregate bound vs. real disk (docs/INFRA_RUNBOOK.md). **REQUIREMENTS.md's checkbox for INFRA-07 is still unchecked (`[ ]`) and its tracker table still says "Pending" — stale bookkeeping, not a real gap** (see Anti-Patterns / Findings below). |
| INFRA-08 | 05-03, 05-06 | Network layers audited, externally verified, only 80/443 public | ✓ SATISFIED | 3 independent full-range external scans + reboot/daemon-restart persistence proof; live spot-check this session confirms 8080/8081/9092/5432 all unreachable. **REQUIREMENTS.md's checkbox is also unchecked, and its literal text still names OCI's non-existent-here 3-layer model** — `WINDOWS.md` entry #2 (open) tracks this wording drift; substance is proven. |

No orphaned requirements found — every INFRA-01..08 ID declared in REQUIREMENTS.md is claimed by at least one of the 6 plans' frontmatter `requirements:` lists, and every plan's declared requirements map to real, checked evidence above.

### Provider Substitution Note (not a gap)

Every plan in this phase, and the roadmap's own success-criteria text, was written assuming Oracle
Cloud's Always Free A1 Flex VM as the deploy target. Plan 05-03's SUMMARY documents a real,
screened pivot to a Netcup VPS after 200+ automated Oracle provisioning attempts across 10+ hours
confirmed the capacity shortage was structural (single-availability-domain region, no ETA). The
pivot is recorded, alternatives were screened and rejected with reasons (AWS — this project's own
prior billing-risk history; GCP/Azure — region/time restricted; Hetzner — unavailable), and every
downstream artifact (Compose manifest comments, `docs/INFRA_ARCHITECTURE.md`'s diagrams, the CI
build's `linux/amd64` platform target correcting an ARM64 leftover, `docs/INFRA_RUNBOOK.md`) was
updated to describe the real target rather than silently left describing Oracle. This verifier
treats the substance of every roadmap Success Criterion (real HTTPS, Docker with restart policy
and healthcheck, Neon, capped Redpanda, automated CI/CD, external network audit) as what actually
matters, and all five hold true against the real (Netcup) infrastructure — the specific cloud
vendor name in the roadmap/requirement text is the only thing now inaccurate, and that inaccuracy
is itself documented in multiple places (05-03-SUMMARY.md, `docs/INFRA_RUNBOOK.md`'s "Provider
history" section, `WINDOWS.md` entry #2).

### Key Link Verification

| From | To | Via | Status | Details |
|---|---|---|---|---|
| `docker-compose.prod.yml` app healthcheck | `SecurityConfiguration.java`'s permitAll matcher | `ApiPaths.ACTUATOR_HEALTH = "/actuator/health"` | ✓ WIRED | Both reference the same path; live HTTPS request to `.../api/actuator/health` returns 200 unauthenticated, confirming the matcher is not shadowed by `anyRequest().authenticated()` |
| Caddy | `app` service | `reverse_proxy app:8080` over internal Compose network, no host port on `app` | ✓ WIRED | Live: plain-HTTP request redirects (308) via Caddy; app itself publishes no host port (verified 8080 unreachable from off-VM) |
| `deploy-to-netcup` job | `flyway-verify` + `build-and-push-docker-image` jobs | `needs:` dependency array | ✓ WIRED | Confirmed via `grep -n "needs:"` on the live workflow file; live run `32017867204` shows all 3 jobs completed in the correct order before `deploy-to-netcup` started |
| `cleanup-old-images`/`cleanup-unused-image` | `deploy-to-netcup` | `needs:` dependency array | ✓ WIRED | Both cleanup jobs' `needs:` name `deploy-to-netcup`, not the removed `deploy-to-ec2`; live run confirms `cleanup-old-images` executed (not skipped) and succeeded |
| Schema registry URL (producer + consumer) | `SCHEMA_REGISTRY_URL` env var | Compose environment injection, no hardcoded URL in `src/main` | ✓ WIRED | `grep -rn "schema.registry.url" src/main --include=*.java` returns no hardcoded literal; live registry query confirms production registry actually holds the 14 subjects |

### Behavioral / Live Spot-Checks (run by this verifier, not sourced from SUMMARY prose)

| Behavior | Command | Result | Status |
|---|---|---|---|
| Public HTTPS health endpoint | `curl -s https://kanban-board-rud-vlad-473.duckdns.org/api/actuator/health` | `200 {"status":"UP"}` | ✓ PASS |
| Plain HTTP redirects to HTTPS | `curl -s -o /dev/null -w '%{http_code} %{redirect_url}' http://...` | `308` → `https://...` | ✓ PASS |
| Non-web ports unreachable from off-VM | TCP connect to `159.195.114.230:{8080,8081,9092,5432}` | all closed/filtered/timeout | ✓ PASS |
| GH Actions secrets contain no AWS-era name | `gh secret list --repo RudVlad473/kanban-board-backend` | 10 secrets, none `EC2_*` | ✓ PASS |
| Live CI job graph and gating | `gh run view 32017867204 --json jobs` | `flyway-verify`→`deploy-to-netcup`→`cleanup-old-images` all `success`, correct order | ✓ PASS |
| Docker Hub tag-pruning fix actually deletes tags | `gh run view 32017867204 --job <cleanup-old-images-id> --log` | 32 `Deleting tag: ...` lines, `FAILED=0`, job concluded success | ✓ PASS |
| Actuator health path wired in Spring Security | `grep -n ACTUATOR_HEALTH ApiPaths.java SecurityConfiguration.java` | constant defined and referenced in the `permitAll` matcher chain | ✓ PASS |

### Anti-Patterns / Findings (none block the phase goal)

| File | Issue | Severity | Impact |
|---|---|---|---|
| `docs/INFRA_RUNBOOK.md` (Decommission Record, "Part C — Docker Hub tag pruning") | Still reads "Not attempted... genuine human-only checkpoint" — this text was **not updated** by the follow-on commits (`8a31d85`, `faacda4`) that actually fixed and live-verified the pruning (CI run `32017867204`, confirmed by this verifier). The runbook is this phase's own designated single source of Task-level evidence, and on this one sub-item it is now factually wrong. | ⚠️ Warning | Does not affect the underlying INFRA-05 truth (proven true by independent live CI evidence, see above), but should be corrected in a follow-up commit so a future reader doesn't trust the stale runbook text over the newer commits/SUMMARY. |
| `.planning/REQUIREMENTS.md` | INFRA-07 and INFRA-08 checkboxes are still `[ ]` and the tracker table still says "Pending", despite both being demonstrably complete (05-06-SUMMARY.md `requirements-completed: [INFRA-05, INFRA-07, INFRA-08]`, and this verifier's own live confirmation above). | ⚠️ Warning | Stale project bookkeeping only; recommend a trivial follow-up commit ticking both boxes and updating the tracker table row to "Complete" now that Phase 5 is closing. |
| `.planning/WINDOWS.md` (entry #2) | Still `status: open` — flags that INFRA-08's literal requirement text names OCI's 3-layer model, which no longer applies after the Netcup pivot. | ⚠️ Warning | Wording-only; the underlying 2-layer model is configured and externally verified to the same rigor. Recommend closing this ledger entry (mark `fixed`) alongside the REQUIREMENTS.md checkbox update, since 05-06 was explicitly named as its owning plan and that reconciliation was never actually performed. |

No `TBD`/`FIXME`/`XXX` debt markers found in any file modified by this phase's plans. No stub
implementations, hollow props, or hardcoded-empty data patterns found — every artifact inspected
(Compose manifest, Caddyfile, workflow file, SecurityConfiguration, application.properties) reflects
real, live-verified configuration rather than placeholder content.

### Human Verification Required

None. Every must-have truth in this phase was verifiable either via a live network probe this
verifier ran directly against the real production host, or via `gh` CLI queries against real GitHub
Actions run history and repository secrets — no visual, UX, or purely-subjective judgment call was
required.

### Gaps Summary

No gaps block phase goal achievement. All 5 ROADMAP success criteria and all 8 INFRA-01..08
requirement IDs are demonstrably true against the live, real production infrastructure, independently
re-verified by this agent (not merely asserted by SUMMARY.md prose) via direct HTTPS/TCP probes, `gh`
CLI queries against real secrets and a real recent CI run, and direct file inspection of the current
`.github/workflows/deploy.yml`, `docker-compose.prod.yml`, `application.properties`, and
`SecurityConfiguration.java`.

Three non-blocking documentation-accuracy findings are recorded above (stale Part C text in
`docs/INFRA_RUNBOOK.md`, stale checkboxes in `REQUIREMENTS.md`, one open `WINDOWS.md` wording-drift
entry) — all describe *documentation* lagging behind an already-proven-true reality, not missing or
broken functionality. Recommended as a small follow-up quick task before/at milestone close, not as
a blocker to shipping this phase.

---

_Verified: 2026-08-17_
_Verifier: Claude (gsd-verifier)_
