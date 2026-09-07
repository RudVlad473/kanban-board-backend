---
status: awaiting_human_verify
trigger: "admin reset endpoint fails in nonprod, can you try and debug and see why frontend fails to call it?"
created: 2026-08-26T10:47:16Z
updated: 2026-08-26T12:52:00Z
---

## Current Focus
<!-- OVERWRITE on each update - always reflects NOW -->

hypothesis: CONFIRMED via Neon MCP (list_projects, org-red-moon-37279582): project kanban-board-db (floral-union-23715140) shows quota_reset_at=2026-09-01T00:00:00Z and compute_last_active_at=2026-08-26T10:11:59Z — the compute has been unable to activate since almost the exact second the outage began. User independently confirmed: "neon is done, we've used all the monthly allowance." Root cause is hypothesis (A): Neon Free-tier's 100 CU-h/month allowance is per-PROJECT (shared across every branch), and this repo's spring.datasource.hikari.minimum-idle=1 + keepalive-time=120000 (application.properties:90-95, both nonprod and prod share this one properties file) ping every pooled connection every 2 minutes specifically to defeat Neon's scale-to-zero (documented intent: INFRA-02, avoid cold-start latency) — so BOTH the nonprod and production computes bill continuously, 24/7, against the one shared allowance, instead of suspending when idle.
test: n/a — confirmed directly via Neon MCP list_projects and user confirmation. No further discrimination needed.
expecting: n/a
next_action: AWAITING HUMAN VERIFICATION. Fix applied and both gates green (`./gradlew spotlessCheck test`, BUILD SUCCESSFUL, exit 0). NOT yet committed and NOT yet archived — commit + move to resolved/ + knowledge-base append happen only after the user confirms. Uncommitted change: src/main/resources/application.properties (minimum-idle 1->0, keepalive-time 120000->0, plus decision record). Note that signal 1 of the fix-acceptance guardrail is unobservable until quota_reset_at (2026-09-01T00:00:00Z) — see Resolution.verification; do not record this as proven-fixed.
prior_next_action_now_done: USER DECIDED (2026-08-26T12:30:00Z): soften the Hikari keepalive policy so idle computes can scale to zero, accepting Neon's documented ~hundreds-of-ms cold start on the first request after idle, instead of paying continuously for an always-warm connection. Splitting into separate Neon projects was considered and rejected — verified via Neon's own per-branch usage that each branch already burns ~170 CU-h/month on its own at the current always-on rate, well over the 100 CU-h/month Free allowance per project, so splitting alone would not have prevented recurrence. Apply: relax/remove spring.datasource.hikari.minimum-idle and keepalive-time in application.properties (application.properties:90-95) so HikariCP stops forcing a warm connection and stops pinging it every 2 minutes; update the surrounding rationale comment to document this as a deliberate, revisited trade-off (superseding the original INFRA-02 always-warm rationale) rather than silently deleting it; run ./gradlew spotlessCheck and ./gradlew test; record Resolution.fix and files_changed. NOTE: this does NOT unblock the current outage — Neon returns HTTP 402 (quota already exceeded) regardless of this config change; the outage itself only lifts at quota_reset_at (2026-09-01T00:00:00Z) or if the user upgrades the Neon plan (the user's call, not something to do unilaterally).
bug_class: Bohrbug (deterministic — every DB-touching request fails identically, 100% reproducible on demand)
reasoning_checkpoint:
  hypothesis: "The app cannot open any JDBC connection to Neon; the reset 500 is a downstream symptom, not a defect in the reset path."
  confirming_evidence:
    - "Live POST /api/signin (a path that never touches Kafka or the reset code) returns the verbatim Hikari message 'Connection is not available, request timed out after 30000ms (total=0, active=0, idle=0, waiting=4)'."
    - "Both prod and nonprod /api/actuator/health return 503 DOWN after 30.35s, matching the configured connection-timeout=30000 exactly."
    - "health-check-nonprod required an HTTP 200 from /api/actuator/health and passed at 2026-08-25T12:11:51Z on the current image, so the same code was DB-connected after the last deploy."
  falsification_test: "Any DB-touching endpoint returning normal data, or a Hikari message showing total>0, would disprove it."
  fix_rationale: "NOT YET APPLIED — deliberately. The in-repo candidate fix (relaxing minimum-idle/keepalive so Neon can scale to zero) is only correct under hypothesis (A); applying it before discriminating would be acting on unconfirmed evidence and would not restore service on its own."
  blind_spots: "Neon authenticates at the proxy BEFORE requesting compute start, so a wrong-password probe cannot observe a quota stop. Without valid credentials, VM access, or the Neon console, (A) and (B) cannot be separated remotely."
  candidate_causes:
    - "code: reset/TRUNCATE/Kafka path defect — REFUTED (fails on endpoints that touch neither)"
    - "config: neondb_owner credentials rotated — REFUTED (identical SCRAM salt on both branches proves no reset)"
    - "environment: Netcup VM lost egress to Neon — LIVE"
    - "data/quota: Neon Free per-project 100 CU-h compute allowance exhausted — LIVE, favoured"
  and_gate: "Yes for hypothesis (A): it requires BOTH the free-plan per-project allowance AND this repo's minimum-idle=1/keepalive-time=120000, which defeat Neon's scale-to-zero so two computes bill 24/7 against one shared quota. Neither condition alone causes an outage — which is why the config half is a legitimate in-repo fix even though the trigger is external."
tdd_checkpoint: null

## Symptoms
<!-- Written during gathering, then immutable -->

expected: Frontend's Playwright E2E global-setup (and a standalone CI step) POST to https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api/admin/reset with header X-Reset-Token, and receive 204 No Content, wiping nonprod Postgres + the two Kafka activity topics.
actual: The endpoint returns HTTP 500 (confirmed twice in the same CI run: once via the frontend's e2e/global-setup.ts fetch, once via a raw curl step) instead of 204. This is a real server-side 500, not a browser CORS block — ruled out CORS/auth as the failure mode.
errors: |
  1) e2e/global-setup.ts:27 — "Error: Reset endpoint at https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api/admin/reset returned 500 — the e2e suite refuses to run without a working reset capability. Confirm NONPROD_RESET_TOKEN is correct (see SETUP.md)."
  2) CI step "Reset nonprod state" — `curl -X POST -H "X-Reset-Token: ***" .../api/admin/reset` → "Reset endpoint returned 500, expected 204"
reproduction: gh run view 32956845835 --repo RudVlad473/kanban-board-frontend --log-failed (job "e2e", step "Run E2E tests" and step "Reset nonprod state"), or directly curl the live nonprod endpoint with the correct X-Reset-Token.
started: User reports it "worked before, broke recently" — no code changes to ResetController/ResetService/ResetTruncateService themselves in this repo's git history since their introduction (commits 65e3370, 818c14a). Most recent related change is V8__add_boards_created_at.sql (Flyway migration, applied 2026-08-25), adding a NOT NULL created_at column to boards — reviewed and does not appear to break the hardcoded TRUNCATE table list (table names match V1/V3 CREATE TABLE statements exactly: users, boards, columns, tasks, subtasks, activity_log, spring_session_attributes, spring_session).

## Eliminated
<!-- APPEND only - prevents re-investigating after /clear -->

- hypothesis: Frontend fails to call the endpoint due to a CORS misconfiguration (NonprodResetSecurityConfiguration's resetEndpointFilterChain doesn't call .cors(), unlike the main SecurityConfiguration chain) or a shared-secret token mismatch (403).
  evidence: GH Actions run 32956845835 (job "e2e") shows the request actually completing with a genuine HTTP 500 response, both from the frontend's own fetch-based global-setup check and from a plain curl step in the same CI run against the live nonprod host. A CORS block would show as an opaque failed browser fetch with no status code; a token mismatch would be 403 per ResetController.reset()'s AppAccessDeniedException path. Neither matches — a real 500 was returned to both callers.
  timestamp: 2026-08-26T10:47:00Z

- hypothesis: The 500 originates inside ResetService.truncateActivityTopics() — a Kafka AdminClient failure against the resource-capped self-hosted Redpanda (the prior session's leading suspect).
  evidence: The failing call returns in ~31.4s, and the live 500 body from an unrelated DB-touching endpoint (POST /api/signin) names HikariCP explicitly: "Unable to acquire JDBC Connection [HikariPool-1 - ... timed out after 30000ms (total=0, active=0, idle=0, waiting=4)]". 30000ms is the configured spring.datasource.hikari.connection-timeout. Kafka's AdminClient default.api.timeout.ms is 60s and would surface as IllegalStateException("Failed to truncate Kafka topic ..."), which is nowhere in the response. The failure reproduces on endpoints that never touch Kafka at all.
  timestamp: 2026-08-26T11:26:00Z

- hypothesis: The 500 is caused by V8__add_boards_created_at.sql or the recent createdAt commits (6aadda1 / 89fff5d), i.e. a schema/TRUNCATE-list mismatch introduced by recent work.
  evidence: The deploy carrying those changes (run 32845530663, HEAD f835369) completed with flyway-verify-nonprod SUCCESS and health-check-nonprod SUCCESS — the latter requires an HTTP 200 from /api/actuator/health, which includes the DataSource indicator — at 2026-08-25T12:11:51Z. The app was demonstrably healthy and DB-connected WITH those changes deployed. Breakage started later with no intervening deploy.
  timestamp: 2026-08-26T11:33:30Z

- hypothesis: The bug is specific to nonprod (its Neon branch, its container, its NONPROD_RESET_TOKEN, or NonprodResetSecurityConfiguration).
  evidence: Production's /api/actuator/health returns the identical 503 {"status":"DOWN"} after the identical ~30.35s. Production shares no branch, credentials, container, or reset config with nonprod — only the Neon project and the Netcup VM.
  timestamp: 2026-08-26T11:29:30Z

- hypothesis: A connection leak or pool exhaustion under concurrent load is starving the pool.
  evidence: The HikariCP diagnostic string reports total=0, active=0, idle=0. A leak or load-exhaustion presents as total=5 (maximum-pool-size) with active=5; total=0 means no physical connection has ever been established.
  timestamp: 2026-08-26T11:25:30Z

- hypothesis: The nonprod Neon endpoint was deleted, or DNS/network to Neon is broken generally.
  evidence: Direct probe from this workstation resolved both A and AAAA records, completed a TCP connect to :5432, negotiated TLS, and received a Postgres AuthenticationSASL (SCRAM-SHA-256) challenge — so the endpoint exists and Neon's proxy routes to it. (Does not rule out a post-auth compute-start/quota failure.)
  timestamp: 2026-08-26T11:31:30Z

## Evidence
<!-- APPEND only - facts discovered during investigation -->

- timestamp: 2026-08-26T10:47:00Z
  checked: GH Actions run 32956845835 (RudVlad473/kanban-board-frontend, branch gsd/phase-02-board-management, 2026-08-26T10:28:56Z), job "e2e", failed-step logs via `gh run view --log-failed`
  found: Both the Playwright global-setup reset call and a separate raw-curl "Reset nonprod state" CI step against https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api/admin/reset returned HTTP 500 (not 204, not 403, not a network/CORS failure).
  implication: The failure is server-side, inside ResetService.resetAll() or something it calls, on the deployed nonprod instance. Confirms this is NOT a frontend-side CORS/config bug despite the phrasing "frontend fails to call it" — the frontend's fetch worked; the backend errored.

- timestamp: 2026-08-26T10:47:00Z
  checked: src/main/java/com/vrudenko/kanban_board/controller/ResetController.java, service/ResetService.java, service/ResetTruncateService.java, security/NonprodResetSecurityConfiguration.java (full read)
  found: ResetController requires header X-Reset-Token matching app.reset.token (constant-time compare, 403 on mismatch); ResetService.resetAll() stops all @KafkaListener containers, trims kanban.activity/kanban.activity.dlt via AdminClient.deleteRecords(), then calls ResetTruncateService.truncateAll() (a single native TRUNCATE ... CASCADE over a hardcoded table list), then restarts listener containers in a finally block. Any exception from the Kafka AdminClient path (other than UnknownTopicOrPartitionException) is rethrown as IllegalStateException; any exception anywhere in this call chain is uncaught by a specific @ExceptionHandler and falls through to GlobalExceptionHandler's generic Exception.class -> HTTP 500 handler (handler/GlobalExceptionHandler.java:73).
  implication: A 500 here means an unanticipated exception surfaced from either the Kafka AdminClient calls, the Postgres TRUNCATE statement, or the listener stop/start calls — need the actual stack trace (not visible from CI, which only sees the HTTP response) to tell which.

- timestamp: 2026-08-26T10:47:00Z
  checked: `git log` on ResetController.java/ResetService.java/ResetTruncateService.java (no changes since introduction) vs. full recent repo history and migration directory listing
  found: No code changes to the reset path itself. Most recent adjacent change is V8__add_boards_created_at.sql (2026-08-25), which only adds a NOT NULL created_at column with a dropped default to the existing boards table — does not rename/remove any table TRUNCATE depends on.
  implication: If the migration is implicated at all, it would have to be via some deploy-time side effect (e.g., migration failing to apply cleanly on the live nonprod DB, leaving schema in an inconsistent state) rather than a logical TRUNCATE-list mismatch — worth checking nonprod's actual applied schema_version / Flyway history table next if the Postgres path turns out to be the culprit.

- timestamp: 2026-08-26T11:20:00Z
  checked: Live probes of the deployed nonprod host — POST /api/admin/reset with no token, with a wrong token, and GET /api/docs
  found: No token -> HTTP 403 in 0.36s with the correct ProblemDetail envelope ({"code":"ACCESS_DENIED","detail":"You do not have access to that nonprod reset endpoint"}). Wrong token -> identical 403 in 0.37s. GET /api/docs -> HTTP 200 in 0.90s.
  implication: The app process is alive, the `nonprod` profile IS active, ResetController and ResetService beans DO exist, and the shared-secret check works. The 500 therefore originates strictly inside resetService.resetAll(). Also note both these probes are DB-free code paths — which is why they succeed.

- timestamp: 2026-08-26T11:22:00Z
  checked: GH Actions run 32956845835 raw-curl step "Reset nonprod state" wall-clock timestamps (start 2026-08-26T10:31:11.6744236Z, "returned 500" 2026-08-26T10:31:43.1214098Z)
  found: The 500 took ~31.4 seconds to be returned. This is not a fast application-logic failure.
  implication: A ~30s wall clock is the signature of a configured timeout, not a TRUNCATE or an AdminClient error (Kafka AdminClient's default.api.timeout.ms would be 60s). Redirected the investigation from the Kafka path to the JDBC path.

- timestamp: 2026-08-26T11:25:00Z
  checked: POST /api/signin against live nonprod with deliberately bogus credentials (a DB-touching path — UserRepository.findByEmail), vs GET /api/boards unauthenticated
  found: signin -> HTTP 500 after 30.349s, body verbatim {"type":"about:blank","title":"Internal Server Error","status":500,"detail":"Unable to acquire JDBC Connection [HikariPool-1 - Connection is not available, request timed out after 30000ms (total=0, active=0, idle=0, waiting=4)] [n/a]","instance":"/api/signin","code":"INTERNAL_ERROR"}. GET /api/boards hung past a 60s client timeout (HTTP 000).
  implication: ROOT CAUSE CLASS FOUND. The failure is NOT in the reset path at all — the app cannot obtain a JDBC connection for ANY request. `total=0, active=0, idle=0` proves the pool has never successfully opened even one connection (rules out a connection leak or pool exhaustion by concurrent load — those show total=5). The reset 500 is a downstream symptom of a fully-unreachable database.

- timestamp: 2026-08-26T11:27:00Z
  checked: src/main/resources/application.properties datasource pool settings
  found: spring.datasource.hikari.connection-timeout=30000 (line 92), maximum-pool-size=5, minimum-idle=1, keepalive-time=120000.
  implication: The observed 30.35s latency matches connection-timeout=30000 exactly. Confirms the timing signature is HikariCP giving up on acquiring a connection, not a network- or Kafka-level timeout.

- timestamp: 2026-08-26T11:29:00Z
  checked: GET /api/actuator/health on BOTH deployments — nonprod (kanban-board-rud-vlad-473-nonprod.duckdns.org) and production (kanban-board-rud-vlad-473.duckdns.org)
  found: nonprod -> HTTP 503 {"status":"DOWN"} after 30.351s. production -> HTTP 503 {"status":"DOWN"} after 30.348s. Identical status, identical body, identical timing.
  implication: MAJOR SCOPE EXPANSION — production is down too, not just nonprod. These are two separate containers, two separate Neon branches, two separate credential sets, two separate .env files, on the same Netcup VM. A cause that hits both simultaneously must be either (a) Neon project-level, or (b) Netcup-VM-level. It cannot be application code, and it cannot be a per-branch credential problem.

- timestamp: 2026-08-26T11:31:00Z
  checked: Direct reachability of the nonprod Neon endpoint ep-wild-mode-b2atsqpx.c-6.eu-central-1.aws.neon.tech:5432 from this workstation — getaddrinfo, TCP connect, Postgres SSLRequest, TLS handshake, StartupMessage
  found: Resolves to 3 IPv4 (52.28.178.228, 3.126.61.52, 63.186.215.67) and 3 IPv6 addresses. TCP connect succeeded. SSLRequest answered 'S'. TLS negotiated TLS_AES_256_GCM_SHA384. StartupMessage answered with a type-'R' AuthenticationSASL message offering SCRAM-SHA-256-PLUS / SCRAM-SHA-256.
  implication: Neon's proxy is up, the endpoint ID is known to it, and it is willing to authenticate. NOTE THE LIMIT OF THIS EVIDENCE: Neon's proxy performs SCRAM auth BEFORE asking the control plane to start the compute, so a SCRAM challenge does NOT prove the compute can actually start. This probe rules out "endpoint deleted" and "DNS/network path to Neon broken from the public internet", but does NOT rule out a project-level compute/storage quota stop.

- timestamp: 2026-08-26T11:33:00Z
  checked: Backend deploy run 32845530663 (2026-08-25T12:03:31Z, the most recent deploy — HEAD f835369) job-by-job conclusions, and the body of the health-check-nonprod job in .github/workflows/deploy.yml
  found: flyway-verify-nonprod SUCCESS at 12:09:33Z; deploy-to-nonprod SUCCESS at 12:11:21Z; health-check-nonprod SUCCESS at 12:11:51Z. That job polls https://.../api/actuator/health and only exits 0 on an HTTP 200 (deploy.yml:496-506).
  implication: TIME OF BREAK NARROWED. /api/actuator/health includes the DataSource health indicator (it is exactly what returns 503 DOWN now). A 200 at 2026-08-25T12:11:51Z proves the nonprod app was successfully connected to Neon at that moment. There has been NO deploy to either environment since. Therefore the breakage began AFTER 2026-08-25T12:12Z, spontaneously, with zero code or image change — conclusively exonerating V8__add_boards_created_at.sql, the createdAt commits, and the reset code itself.

- timestamp: 2026-08-26T11:34:00Z
  checked: Frontend CI run 32781280841 (2026-08-24T21:46Z), the first "failure" after the last green run — verified its actual failing step
  found: It failed in the `visual` job on Storybook/Playwright locator timeouts, NOT on the reset endpoint.
  implication: Corrects an in-progress assumption — the frontend red streak starting 2026-08-24T21:46 is unrelated visual-regression noise and must NOT be used to bracket this bug. The health-check-nonprod 200 at 2026-08-25T12:11:51Z is the reliable lower bound instead.

- timestamp: 2026-08-26T11:42:00Z
  checked: Full SCRAM-SHA-256 handshake (deliberately wrong password) against BOTH Neon endpoints — nonprod ep-wild-mode-b2atsqpx and production ep-delicate-bird-b2lni8pr
  found: Both completed the handshake and returned SQLSTATE 28P01 "password authentication failed for user 'neondb_owner'". Both returned the IDENTICAL stored salt s=Ku1flcQMTctZ6ZSI1vaWaA==, i=4096.
  implication: (a) Both branches share one inherited `neondb_owner` SCRAM verifier — nonprod was branched from production and the credential was never independently changed. (b) An identical salt on both PROVES no password reset has occurred on either branch since the nonprod branch was created — a Neon password reset generates a fresh random salt. This REFUTES the "credentials rotated" hypothesis, which was otherwise a strong fit (a shared credential is exactly the kind of single thing that could break two environments at once). (c) A fast 28P01-style rejection would still surface as Hikari's 30s pool timeout, because Hikari retries until connection-timeout elapses — so the 30s figure does NOT by itself distinguish a fast rejection from a hanging TCP connect.

- timestamp: 2026-08-26T11:46:00Z
  checked: Neon's documented Free-plan behavior on compute-hour exhaustion (neon.com/faqs/free-plan-limits-and-quotas, neon.com/docs/connect/connection-errors), cross-referenced against application.properties' own pool rationale (lines 67-95)
  found: Neon Free allows 100 CU-hours of compute PER PROJECT PER MONTH; on exhaustion "the project's compute is suspended until the next billing period" — existing connections drop and new ones cannot open. Separately, application.properties deliberately sets minimum-idle=1 and keepalive-time=120000 with the stated intent of holding a warm connection open "across Neon's idle window" — i.e. it intentionally defeats Neon's scale-to-zero, in BOTH environments, 24/7.
  implication: A per-PROJECT quota is a mechanism that takes down every branch of the project simultaneously with no deploy — matching the observed prod+nonprod simultaneity exactly. The keepalive/minimum-idle config makes two computes run around the clock, which burns the shared 100 CU-h/month allowance continuously. This is now the leading hypothesis, and unlike the others it has an in-repo contributing cause that can actually be fixed here.

- timestamp: 2026-08-26T11:50:00Z
  checked: Frontend CI runs between the last green health check and the first confirmed reset 500, verifying per-run that the reset step actually executed (jobs `quality` and `e2e` both run the reset curl and require 204)
  found: Run 32850707841 (2026-08-25T12:59) — `quality` SUCCESS and `e2e` SUCCESS, reset returned 204 at ~13:04Z. Run 32956845835 (2026-08-26T10:10) — reset returned 500. Intermediate runs (16:27, 19:28, 21:50) are NOT usable as evidence: their `e2e` jobs were skipped and their `quality` failures were unrelated (no "expected 204" output).
  implication: BREAK WINDOW = 2026-08-25T13:04Z (last proven-good) to 2026-08-26T10:12Z (first proven-bad), ~21 hours. No deploy to either environment occurred inside this window. Useful for correlating against Neon's usage graph or the VM's own logs.

- timestamp: 2026-08-26T11:52:00Z
  checked: TLS certificate validity on both public hosts (Caddy-managed Let's Encrypt), as an indirect probe of whether the VM retained general outbound internet access during the break window
  found: nonprod cert notBefore=Aug 18 11:09:01 2026, notAfter=Nov 16; production cert notBefore=Aug 16 13:58:33 2026, notAfter=Nov 14.
  implication: INCONCLUSIVE — no renewal was due inside the 08-25/08-26 break window, so Caddy's egress was never exercised then. This probe does not discriminate Neon-quota from VM-egress-loss. Recorded so a later session does not re-attempt it.

- timestamp: 2026-08-26T11:54:00Z
  checked: Whether the running app could have restarted since the break (inference from spring.jpa.hibernate.ddl-auto=validate + Flyway-on-boot, both of which require a live DB connection at startup)
  found: The app is still serving /api/docs (200) and the 403 reset path, so the JVM is up; yet it could not possibly complete a boot right now with the DB unreachable.
  implication: The container has NOT restarted since before the break — it booted successfully (DB reachable) and lost the database while running. IMPORTANT OPERATIONAL WARNING: any `docker compose up/restart` on app or app-nonprod while the DB stays unreachable will fail to boot and leave a crash-loop, turning a clear 500 into a hard outage. This is why the otherwise-decisive `flyway-verify-nonprod` job re-run was NOT triggered unilaterally — GitHub re-runs dependent jobs, which would cascade into deploy-to-nonprod.

- timestamp: 2026-08-26T12:08:00Z
  checked: Neon MCP `list_projects` (search "kanban-board-db") against org-red-moon-37279582, now that the user connected Neon MCP access
  found: Project floral-union-23715140 — quota_reset_at="2026-09-01T00:00:00Z", compute_last_active_at="2026-08-26T10:11:59Z", active_time=1539775s (~427.7h), cpu_used_sec=398389s (~110.6h). The user independently stated, unprompted by this data: "neon is done, we've used all the monthly allowance."
  implication: DISCRIMINATED. compute_last_active_at (10:11:59Z) matches the observed break instant (~10:12Z) to the second — the compute simply never activated again after that timestamp, consistent with a quota-triggered suspension, not a network-egress event (which would show no such precise correlation to a Neon-side timestamp). Combined with the user's direct confirmation, hypothesis (A) is CONFIRMED and (B) (VM egress loss) is ELIMINATED for lack of any supporting signal once (A) was confirmed.

- timestamp: 2026-08-26T12:20:00Z
  checked: Neon MCP `run_sql` (SELECT 1) directly against the nonprod branch (br-still-shadow-b2r7ez0l), to confirm whether the block is a resumable idle-suspend or a hard quota block
  found: HTTP 402 — {"message":"Your account or project has exceeded the compute time quota. Upgrade your plan to increase limits."}
  implication: This is a hard, non-resumable block, not an ordinary scale-to-zero suspend that would resume on the next connection attempt. No config change (relaxing keepalive, splitting projects, self-hosting going forward) un-blocks the CURRENTLY-suspended compute — only quota_reset_at (2026-09-01T00:00:00Z) or upgrading the Neon plan does. Any preventive fix only affects future months.

- timestamp: 2026-08-26T13:55:00Z
  checked: HikariCP 6.3.3 sources (HikariConfig.java, HikariPool.java) extracted from the Gradle cache, after the first fix attempt deleted the `spring.datasource.hikari.keepalive-time` line outright on the assumption that Hikari's default is "disabled"
  found: HikariConfig:55 declares `DEFAULT_KEEPALIVE_TIME = MINUTES.toMillis(2)` and the constructor (:126) assigns it, so an UNSET keepalive-time is 120000ms — byte-identical to the value being removed. HikariPool:499 schedules KeepaliveTask only under `if (keepaliveTime > 0)`, so an explicit 0 is the sole way to disable it. Separately, HikariConfig:1130 coerces an unset/out-of-range minIdle to maxPoolSize, and HikariPool:528 refills only while `idle < config.getMinimumIdle()`. HikariCP's own Javadoc on setKeepaliveTime still claims "default is 0 (disabled)", contradicting the field initializer in the same file.
  implication: GUARDRAIL CAUGHT A NO-OP FIX. Deleting the keepalive-time line would have left the 2-minute ping running unchanged, so the outage would have recurred next billing cycle with the repo looking fixed. Corrected to an explicit `keepalive-time=0`. Also confirms the two mechanisms claimed in the fix rationale are real in this version: minimum-idle=0 survives validation and permanently disables the refill path (the suspend/reconnect wake loop), and idle connections are reaped to zero via HikariPool:830 (`idleTimeout > 0 && minIdle < maxPoolSize`).

## Resolution
<!-- OVERWRITE as understanding evolves -->

root_cause: |
  CONFIRMED (mechanism): The deployed application cannot establish ANY JDBC connection to its Neon
  PostgreSQL database. Every DB-touching request blocks for the configured
  spring.datasource.hikari.connection-timeout=30000 and then fails with
  "HikariPool-1 - Connection is not available, request timed out after 30000ms (total=0, active=0,
  idle=0, waiting=4)". `total=0` proves no physical connection has ever been opened.
  POST /api/admin/reset is NOT defective — it is simply the first DB-touching call the frontend's
  CI makes, so it was the messenger. ResetService/ResetTruncateService/ResetController and
  V8__add_boards_created_at.sql are all exonerated.

  SCOPE: production is down identically (both /api/actuator/health return 503 {"status":"DOWN"}
  after ~30.35s). Two containers, two Neon branches, two .env files — only the Neon PROJECT and the
  Netcup VM are common. Began inside 2026-08-25T13:04Z..2026-08-26T10:12Z with no deploy.

  DISCRIMINATED AND CONFIRMED: (A) Neon Free-plan per-PROJECT compute quota (100 CU-h/month)
  exhausted, suspending the compute for every branch of project kanban-board-db
  (floral-union-23715140) at once. Confirmed via Neon MCP (quota_reset_at=2026-09-01T00:00:00Z,
  compute_last_active_at=2026-08-26T10:11:59Z, matching the observed break instant to the second)
  and via the user's direct confirmation. (B) VM egress loss is eliminated.

  CONTRIBUTING CAUSE (in-repo, fixable): spring.datasource.hikari.minimum-idle=1 +
  keepalive-time=120000 (application.properties:90-95) are shared by both the nonprod and
  production deployments and were deliberately set (INFRA-02) to defeat Neon's scale-to-zero and
  avoid cold-start latency. The side effect: both computes bill CU-hours continuously, 24/7,
  against ONE shared per-project quota, instead of suspending during idle periods — which is why a
  Free-plan allowance sized for intermittent usage was exhausted mid-month.

  REFUTED along the way: credential rotation (identical SCRAM salts on both branches), endpoint
  deletion, DNS failure, Kafka/Redpanda, connection leak, and any recent code/migration change
  (V8__add_boards_created_at.sql and the createdAt commits are exonerated).
fix: |
  IMMEDIATE (unblock the outage): none available in-repo — the project's compute is suspended by
  Neon until quota_reset_at (2026-09-01T00:00:00Z) unless the user upgrades the Neon plan or
  otherwise raises the project's quota. This is a billing/account decision, not a code change, and
  was deliberately NOT taken unilaterally.

  PREVENTIVE (APPLIED 2026-08-26, per the user's decision to accept cold starts over always-warm
  billing): src/main/resources/application.properties — exactly two behavioral changes, both in
  the one properties file shared by nonprod and production (no profile overrides this; verified by
  grep across all properties/yml, and the test profile supplies only the datasource URL via
  Testcontainers @ServiceConnection):
    - spring.datasource.hikari.minimum-idle: 1 -> 0
    - spring.datasource.hikari.keepalive-time: 120000 -> 0
  Both are set to an explicit 0 rather than deleted, which is load-bearing (see the 13:55Z evidence
  entry): an unset keepalive-time defaults to 2 MINUTES in HikariCP 6.3.3, and an unset minimum-idle
  is coerced to maximum-pool-size (5). Deleting the lines would have been a silent no-op/regression.
  The surrounding comment block was rewritten with a dated `Decisions` record superseding INFRA-02's
  always-warm rationale, rather than the old rationale being silently dropped.

  REJECTED alternative (recorded so it is not re-proposed): splitting nonprod and production into
  separate Neon projects. Neon's own per-branch usage showed each branch alone burning ~170
  CU-h/month at the always-on rate, well above a single project's 100 CU-h/month Free allowance —
  so splitting would not have prevented recurrence.
verification: |
  guardrail_verdict: accepted_with_documented_limitation

  signal_2 (fix addresses root cause, not symptom): PASS. Verified at the library-source level
  against HikariCP 6.3.3 in the Gradle cache, not assumed: HikariPool:499 gates KeepaliveTask on
  `keepaliveTime > 0` (so 0 truly stops the 2-minute ping), HikariPool:528 gates pool refill on
  `idle < getMinimumIdle()` (so 0 truly breaks the suspend->reconnect->wake loop), and
  HikariPool:830 reaps idle connections to zero since `minIdle < maxPoolSize`. This directly
  removes the continuous CU-h burn identified as the in-repo contributing cause.

  signal_3 (diff is justified, not deletion-only): PASS. Two value changes plus an expanded,
  dated decision record; no code or behavior deleted without stated rationale.

  signal_4 (regression): PASS. `./gradlew spotlessCheck test` green twice — once before the
  keepalive correction and once after (BUILD SUCCESSFUL, exit 0, 5m27s, including
  jacocoTestCoverageVerification). The suite boots Spring repeatedly against Testcontainers
  Postgres with these exact pool settings, so it also proves the config is accepted at runtime.

  signal_1 (original bug no longer reproduces): NOT OBSERVABLE — documented limitation, not a
  pass. Neon returns HTTP 402 for this project until quota_reset_at (2026-09-01T00:00:00Z), so
  neither the outage nor its absence can be observed now. More fundamentally, the confirmed root
  cause is external quota exhaustion; this fix is PREVENTIVE and its effect is a billing rate over
  time, which no local test can assert.

  signal_5 (bug returns on revert): NOT APPLICABLE for the same reason.

  OUTSTANDING — the real verification is a future observation, and this session must not be read
  as having proven it: after 2026-09-01, confirm via Neon that project floral-union-23715140's
  CU-h consumption stays flat while both environments sit idle (e.g. overnight), rather than
  accruing ~24h/day. That is the falsification test already written into the properties comment.
oracle_type: derived (contract-level — verified the fix against HikariCP's own documented and
  source-level gating conditions; no assertable runtime oracle exists for a billing-rate outcome)
files_changed:
  - src/main/resources/application.properties: minimum-idle 1->0, keepalive-time 120000->0, plus a
    dated Decisions record superseding INFRA-02's always-warm rationale and warning that deleting
    either line silently restores the old behavior.
