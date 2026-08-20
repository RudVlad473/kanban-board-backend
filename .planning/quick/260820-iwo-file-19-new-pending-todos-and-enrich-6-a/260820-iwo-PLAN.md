---
quick_id: 260820-iwo
type: quick
files_modified:
  - .planning/todos/pending/2026-08-20-add-rate-limiting-to-signin-to-bound-brute-force-volume.md
  - .planning/todos/pending/2026-08-20-idor-same-user-chain-consistency-boardid-columnid-not-c.md
  - .planning/todos/pending/2026-08-20-verify-csrf-defense-with-a-real-cross-origin-rejection-t.md
  - .planning/todos/pending/2026-08-20-security-response-headers-csp-and-unreliable-hsts-behind.md
  - .planning/todos/pending/2026-08-18-500-problemdetail-detail-carries-raw-exception-message.md
  - .planning/todos/pending/2026-08-13-ratchet-failbuildoncvss-after-a-real-dependency-check-baseline.md
  - .planning/todos/pending/2026-08-20-password-min-max-length-undersized-against-asvs.md
  - .planning/todos/pending/2026-08-20-password-composition-regex-blocks-unicode-only-pass.md
  - .planning/todos/pending/2026-08-20-no-password-change-capability-exists-anywhere-in-api.md
  - .planning/todos/pending/2026-08-20-no-breached-password-check-or-strength-meter-on-signup.md
  - .planning/todos/pending/2026-08-20-no-mfa-second-factor-enrollment-path.md
  - .planning/todos/pending/2026-08-20-no-notification-on-auth-detail-changes-credential-rese.md
  - .planning/todos/pending/2026-08-20-no-secret-pepper-on-top-of-bcrypt-salt.md
  - .planning/todos/pending/2026-08-20-no-security-event-logging-on-auth-and-access-control.md
  - .planning/todos/pending/2026-08-20-no-remote-log-shipping-structured-logging-or-alerting.md
  - .planning/todos/pending/2026-08-20-no-session-revocation-or-reauth-before-destructive-act.md
  - .planning/todos/pending/2026-08-20-dockerfile-runs-as-root-no-user-directive.md
  - .planning/todos/pending/2026-08-20-no-branch-protection-on-master.md
  - .planning/todos/pending/2026-08-20-no-prod-post-deploy-health-verification.md
  - .planning/todos/pending/2026-08-20-internal-kafka-hop-has-no-sasl-auth-or-tls.md
  - .planning/todos/pending/2026-08-20-no-secrets-vault-for-runtime-prod-secrets-no-rotation.md
  - .planning/todos/pending/2026-08-20-swagger-openapi-docs-reachable-in-prod-no-profile-gate.md
  - .planning/todos/pending/2026-08-20-no-content-type-validation-on-rest-endpoints.md
  - .planning/todos/pending/2026-08-20-no-data-classification-or-self-service-export-delete.md
  - .planning/todos/pending/2026-08-20-no-documented-backup-restore-runbook-for-prod-db.md
  - .planning/STATE.md
---

<objective>
Documentation-only follow-up to a 33-agent ASVS 4.0.3 Level 2 audit (results synthesized in a
published artifact for user review). No `src/main`/`src/test` changes.

1. Enrich 6 already-filed pending todos with a short "ASVS 4.0.3 cross-reference" note near the
   top of each (do not rewrite existing Problem/Solution content, except the two items below that
   explicitly call for a content update alongside the note).
2. File 19 new pending todos under `.planning/todos/pending/`, dated 2026-08-20, matching this
   repo's exact frontmatter convention (`created`, `title`, `area`, `severity`, `files`) and
   Problem/Solution prose style — one file per finding group, no bundling.
3. Update `.planning/STATE.md`'s "Still open and out of v1.3 scope" bullet list with
   representative entries for the 12 moderate-severity new todos, and refresh its item-count
   pointer and Pending Todos narrative.

**Trade-offs considered before this plan (per project directive):**

| Approach | Pros / Cons | Why picked |
|---|---|---|
| A: 2 tasks (enrich-all, file-all-19) + STATE.md task | Pro: fewest tasks. Con: a single 19-file task risks a bloated per-task context footprint and makes partial-failure recovery ambiguous (which of 19 independent writes landed if the task is interrupted). | Rejected — works against the ~30% context target and this repo's own preference for verifiable, bounded task scope (see `docs/SESSION_LESSONS.md` git-hygiene-during-execution lessons). |
| B: enrich task + 2 new-todo tasks split 1-10 / 11-19 + STATE.md task (4 tasks total) | Pro: matches the stated "one task per part plus a STATE.md task" shape while keeping each new-todo task's file count balanced (10 and 9) and thematically coherent — group 1 is entirely ASVS auth/session/logging chapters (V2/V3/V7), group 2 is entirely infra/deploy/config/docs. Con: one more task than approach A. | **Picked** — bounded, independently verifiable, natural split along the item numbering already used above; each task's `<verify>` can grep-count files within its own theme without cross-task ambiguity. |
| C: 26 micro-tasks (one per file touched) | Pro: maximal per-item isolation. Con: explodes task count ~6.5x past the "up to 4 tasks" ceiling for no real safety benefit — these are independent, non-file-conflicting creates; per-item isolation buys nothing a single grouped task doesn't already give. | Rejected — directly violates the stated task-count constraint. |

**Non-obvious trade-offs:**
- *Security:* several new todos discuss credentials-adjacent topics (secrets vault, BCrypt pepper).
  Per this repo's pre-commit gitleaks gate, content must reference these as concepts and env-var
  *names* (e.g. `PASSWORD_PEPPER`, `DB_PASS`) only — never a literal secret-shaped value — or the
  commit is refused before formatting/tests even run.
- *Docs drift / maintainability:* STATE.md's existing bullet list is explicitly "representative,"
  not exhaustive — Task 4 appends the 12 moderate-severity items individually but bundles the 7
  minor ones into one summary bullet, mirroring the file's own established pattern (see its
  existing bundled `[minor]` bullets), rather than letting the list grow unbounded per audit round.
- *No runtime/perf impact:* zero `src/main`/`src/test` files touched — this is purely additive
  markdown, so there is no state-invalidation or performance surface to reason about beyond
  keeping the 26 independent file writes bounded per task (context budget, not runtime cost).
</objective>

<task id="1">
  <name>Enrich 6 already-filed todos with ASVS 4.0.3 cross-references</name>
  <files>
    .planning/todos/pending/2026-08-20-add-rate-limiting-to-signin-to-bound-brute-force-volume.md
    .planning/todos/pending/2026-08-20-idor-same-user-chain-consistency-boardid-columnid-not-c.md
    .planning/todos/pending/2026-08-20-verify-csrf-defense-with-a-real-cross-origin-rejection-t.md
    .planning/todos/pending/2026-08-20-security-response-headers-csp-and-unreliable-hsts-behind.md
    .planning/todos/pending/2026-08-18-500-problemdetail-detail-carries-raw-exception-message.md
    .planning/todos/pending/2026-08-13-ratchet-failbuildoncvss-after-a-real-dependency-check-baseline.md
  </files>
  <action>
For each file, insert a new `## ASVS 4.0.3 cross-reference` section immediately after the
frontmatter closing `---` and before the existing `## Problem` heading. Do not touch any other
existing heading or paragraph except where explicitly noted below.

1. **rate-limiting-to-signin** — cross-reference note: the ASVS audit independently found this
   same absent-rate-limiting gap from 3 angles — V2.2.1 (Authentication, brute force on signin,
   the original scope), V8.1.4 (Data Protection, general abnormal-request-volume detection), and
   V11.1.4 (Business Logic, anti-automation on business flows like mass board/task creation, not
   just login). **Also update** the existing `## Problem` and `## Solution` sections in place (not
   just the new note) to broaden scope: the guard should cover general request-volume abuse across
   authenticated business endpoints, not only `POST /signin` — three independent ASVS chapters
   converging on the same missing control is evidence the narrower framing undersold it.

2. **idor-same-user-chain-consistency** — cross-reference note only, no scope change: ASVS
   V1.4.5 (Access Control Architecture), V4.2.1 (Operation Level Access Control), and V13.1.4
   (Generic Web Service Security) all independently converged on the identical
   `OwnershipVerifierService` leaf-id gap already described in this todo's Problem section —
   corroborating signal for the existing `moderate` severity rating.

3. **verify-csrf-defense** — cross-reference note only, no scope change: ASVS V4.2.2 and V13.2.3
   re-confirm the same finding (SameSite=Strict + CORS allowlist reasoning sound, no end-to-end
   cross-origin-rejection test exists yet) — no new information beyond what this todo already says.

4. **security-response-headers-csp-and-unreliable-hsts** — cross-reference note plus two content
   changes to the existing `## Problem` section:
   - **New confirmed finding, add it**: ASVS V14.4.6 confirms `Referrer-Policy` is also unset —
     Spring Security's `ReferrerPolicyConfig` does not enable by default, unlike
     `FrameOptionsConfig`/`HstsConfig` which do.
   - **Correction, add it clearly labeled as a correction**: ASVS V14.4.7 confirms
     `X-Frame-Options` DOES fire by default via Spring Security —
     `FrameOptionsConfig.enable()` sets `XFrameOptionsHeaderWriter` with `DENY` mode
     unconditionally — so the gap on that specific header is narrower than the original todo might
     have implied; do not claim `X-Frame-Options` is missing.
   - Cite ASVS V14.4.3 (CSP), V14.4.5 (HSTS), V14.4.6 (Referrer-Policy), V14.4.7
     (X-Frame-Options), and V3.4.4 (cookie `__Host-` prefix — a related but separate
     cookie-hardening item also found by this audit: confirmed genuinely absent,
     `Secure`+`Path=/` already met, no `Domain` attribute set) in the cross-reference note.

5. **500-problemdetail-detail-carries-raw-exception-message** — cross-reference note only, no
   scope change: ASVS V7.4.1 (Error Handling) and V14.3.3 (Unintended Security Disclosure)
   independently re-discovered this exact same `GlobalExceptionHandler.handleGeneralException` gap
   (`ex.getMessage()` copied verbatim into the `ProblemDetail` 500 response) — corroborating
   signal it is worth fixing.

6. **ratchet-failbuildoncvss** — cross-reference note only, no scope change: ASVS V1.14.3
   (Configuration Architecture) independently re-confirmed `build.gradle`'s `failBuildOnCVSS = 11`
   is still functionally disabled (11 is above the 0-10 CVSS scale) and
   `security-scan.yml`'s `dependencyCheckAnalyze` step is still report-only with no
   `continue-on-error` gate change — no new information, corroborates the existing todo is still
   accurate.

Match the prose voice of the existing Problem/Solution sections read for this plan (declarative,
evidence-first, cites exact class/file names) — do not write generic "AI audit says X" filler.
  </action>
  <verify>
For all 6 files, `## ASVS 4.0.3 cross-reference` appears exactly once, positioned before
`## Problem` in each file. For file 1 (rate-limiting), the word "signin" no longer appears as the
sole scope in the Problem/Solution sections without an accompanying broader-scope mention. For
file 4 (security-response-headers), both `V14.4.6` and `V14.4.7` appear, and the text distinguishes
"confirmed absent" (Referrer-Policy) from "fires by default" (X-Frame-Options) rather than
conflating them.
  </verify>
  <done>All 6 files carry the new section; the two files requiring content updates (rate-limiting
  scope broadening, security-headers new-finding + correction) have those changes applied without
  disturbing unrelated existing prose.</done>
</task>

<task id="2">
  <name>File 10 new todos — password policy, MFA, session, and auth-visibility gaps</name>
  <files>
    .planning/todos/pending/2026-08-20-password-min-max-length-undersized-against-asvs.md
    .planning/todos/pending/2026-08-20-password-composition-regex-blocks-unicode-only-pass.md
    .planning/todos/pending/2026-08-20-no-password-change-capability-exists-anywhere-in-api.md
    .planning/todos/pending/2026-08-20-no-breached-password-check-or-strength-meter-on-signup.md
    .planning/todos/pending/2026-08-20-no-mfa-second-factor-enrollment-path.md
    .planning/todos/pending/2026-08-20-no-notification-on-auth-detail-changes-credential-rese.md
    .planning/todos/pending/2026-08-20-no-secret-pepper-on-top-of-bcrypt-salt.md
    .planning/todos/pending/2026-08-20-no-security-event-logging-on-auth-and-access-control.md
    .planning/todos/pending/2026-08-20-no-remote-log-shipping-structured-logging-or-alerting.md
    .planning/todos/pending/2026-08-20-no-session-revocation-or-reauth-before-destructive-act.md
  </files>
  <action>
Create each file with frontmatter `created: 2026-08-20T00:00:00.000Z`, `title`, `area`,
`severity`, `files` (matching `2026-08-20-idor-same-user-chain-consistency-boardid-columnid-not-c.md`'s
exact frontmatter key order/format), followed by `## Problem` (cite the file/evidence given below
verbatim-equivalent — do not soften or generalize it) and `## Solution` (a concrete direction, not
"investigate further"). Use only the ASVS req IDs listed per item; do not invent additional ones.

1. `password-min-max-length-undersized-against-asvs.md` — title "Password minimum/maximum length
   undersized against ASVS's bar", area `security`, severity `minor`, files
   `constant/ValidationConstants.java`, `dto/annotation/Password.java`,
   `dto/user_dto/SignupRequestDTO.java`, `dto/user_dto/SigninRequestDTO.java`. ASVS V2.1.1, V2.1.2.
   Problem: `ValidationConstants.MIN_PASSWORD_LENGTH = 8` (ASVS wants >=12),
   `MAX_PASSWORD_LENGTH = 64` (ASVS wants >=128 permitted), both enforced via `Password.java`'s
   `@Size` applied through Signup/Signin request DTOs. Solution: raise both constants (>=12 min,
   >=128 max); note `BCryptPasswordEncoder` silently truncates input beyond 72 bytes, so raising
   the max above that requires an explicit pre-hash (e.g. SHA-256 the raw password before BCrypt)
   or an equivalent strategy so longer passwords are not silently weakened; update/add a
   validation-bounds test.

2. `password-composition-regex-blocks-unicode-only-pass.md` — title "Password composition regex
   blocks Unicode-only passwords and violates ASVS's no-composition-rules guidance", area
   `security`, severity `moderate`, files `dto/annotation/Password.java`. ASVS V2.1.4, V2.1.9.
   Problem: `Password.java`'s `@Pattern` regex mandates at least one ASCII lowercase, uppercase,
   digit, and special character — rejects a valid Unicode-only password (e.g. all-Cyrillic or
   all-emoji with no ASCII special char) and directly contradicts ASVS's explicit guidance to drop
   composition-class requirements in favor of length. Solution: remove the `@Pattern` composition
   constraint entirely from `Password.java`, keep only the `@Size` length bounds (from item 1's
   fix); remove/update any test asserting composition-rule rejection; add a test proving a
   sufficiently long Unicode-only password now passes.

3. `no-password-change-capability-exists-anywhere-in-api.md` — title "No password-change
   capability exists anywhere in the API", area `security`, severity `moderate`, files
   `controller/UserController.java`, `security/AuthenticationController.java`,
   `service/UserService.java`. ASVS V2.1.5, V2.1.6. Problem: `UserController` exposes only
   GET/PUT theme; `AuthenticationController` exposes only signin/signup;
   `UserService` has no `changePassword`/`updatePassword` method — once created, a password can
   never be changed. Solution: add a change-password endpoint requiring current-password
   re-verification (`passwordEncoder.matches`) before re-encoding the new one, per V2.1.6's
   re-auth-before-change requirement; add controller + service tests; note the shared
   re-auth plumbing this creates is reusable by item 10 below (destructive-action re-auth).

4. `no-breached-password-check-or-strength-meter-on-signup.md` — title "No breached-password
   check or strength meter on signup", area `security`, severity `minor`, files
   `dto/annotation/Password.java`, `dto/user_dto/SignupRequestDTO.java`. ASVS V2.1.7. Problem: a
   case-insensitive grep for `breach|pwned|haveibeenpwned|zxcvbn|strength` across `src/main`
   matches only unrelated BCrypt-strength-parameter references
   (`BeanConfiguration`'s `security.bcrypt.strength`) — no such check exists. Solution: integrate a
   k-anonymity HaveIBeenPwned range-query check at signup (no full password ever leaves the
   server) or a local strength estimator if an offline approach is preferred for this VPS's
   traffic profile; reject known-breached passwords with a clear validation message; add a test
   using a known-breached test password asserting rejection and a strong/unique one asserting
   acceptance.

5. `no-mfa-second-factor-enrollment-path.md` — title "No MFA / second-factor enrollment path",
   area `security`, severity `moderate`, files `security/UserAuthenticationProvider.java`. ASVS
   V2.3.2. Problem: `UserAuthenticationProvider` is the sole `AuthenticationProvider`, doing a
   single BCrypt password comparison; no TOTP/U2F/FIDO/WebAuthn code anywhere (grep confirmed).
   Solution: add an opt-in TOTP-based second factor — enrollment endpoint generating/storing a
   per-user TOTP secret, a verification step inserted into signin when enrolled, recovery-code
   issuance; scope as opt-in (not mandatory) to avoid a breaking signin-UX change; note this is
   large enough to likely warrant its own phase rather than a single quick task when picked up.

6. `no-notification-on-auth-detail-changes-credential-rese.md` — title "No notification on
   auth-detail changes (credential reset, new-device login)", area `security`, severity `minor`,
   files `build.gradle`, `security/AuthenticationController.java`. ASVS V2.2.3. Problem: no SMTP
   client, mail library, or notification dispatch exists anywhere in `src/main` (`build.gradle`
   carries no mail-starter dependency) — downstream of having no email infrastructure at all yet,
   not a narrow oversight. Solution: explicitly note this is blocked on building email/notification
   infrastructure first; do not attempt a partial implementation now. Once email infra exists (e.g.
   `spring-boot-starter-mail` + a transactional provider), wire notifications on credential-reset
   and new-session/new-device events.

7. `no-secret-pepper-on-top-of-bcrypt-salt.md` — title "No secret pepper on top of BCrypt's own
   per-hash salt", area `security`, severity `minor`, files `config/BeanConfiguration.java`,
   `mapper/UserMapper.java`. ASVS V2.4.5. Problem: `BeanConfiguration`'s `passwordEncoder` bean
   takes only an `int strength` parameter (`security.bcrypt.strength`, default 10), no
   secret/pepper value; `UserMapper` calls `passwordEncoder.encode()` directly with no additional
   HMAC/KDF step. Solution: add an application-level pepper (HMAC the raw password with a
   server-side secret before BCrypt hashing, sourced from an env var such as `PASSWORD_PEPPER`,
   never committed) following this project's existing runtime-secret pattern
   (`docker-compose.prod.yml --env-file`); note this pepper needs the same rotation/storage care
   flagged in the secrets-vault todo (item 15 below).

8. `no-security-event-logging-on-auth-and-access-control.md` — title "No security-relevant event
   logging on the authentication / access-control paths", area `security`, severity `moderate`,
   files `security/AuthenticationController.java`, `security/LogoutHandler.java`,
   `service/OwnershipVerifierService.java`. ASVS V1.2.3, V7.1.3, V7.1.4, V7.2.1, V7.2.2. Problem:
   zero `log.*` calls anywhere in these three classes — confirmed by direct read and a repo-wide
   grep (only `ResetService`, `AvroSchemaRegistrar`, `KafkaEventPublisher`,
   `KafkaConsumerConfig` log anything at all). A real credential-stuffing or unauthorized-access
   attempt today leaves zero forensic trail; distinct from the rate-limiting gap, this is about
   post-hoc visibility, not prevention. Solution: add SLF4J structured logging (matching the
   existing 4 call sites' library choice) at signin success/failure, signout, and
   ownership-denial points — log the userId and outcome, never the raw password or session token;
   keep messages structured/greppable, anticipating item 9's future log-aggregation consumer.

9. `no-remote-log-shipping-structured-logging-or-alerting.md` — title "No remote log shipping,
   no structured/UTC logging standard, no alerting on unusual activity", area `infra`, severity
   `moderate`, files `docker-compose.prod.yml`,
   `docs/plans/backend-modernization/06-observability.md`. ASVS V1.7.1, V1.7.2, V7.3.3, V7.3.4,
   V11.1.7, V11.1.8. Problem: `docker-compose.prod.yml`'s `json-file` logging driver caps logs at
   30MB/container with no remote destination; no sentry/logstash/elk/datadog/cloudwatch/
   papertrail/loki/grafana integration exists (grep confirmed) except one unimplemented backlog
   line. Solution: **point at the existing planned-but-unimplemented backlog item at
   `docs/plans/backend-modernization/06-observability.md` (Prometheus+Grafana) rather than
   proposing a redundant new observability initiative** — when that plan is picked up, ensure it
   also covers structured/UTC logging and remote shipping/retention beyond the current 30MB local
   cap, and basic alerting on unusual auth-failure volume (feeding off item 8's new log events).

10. `no-session-revocation-or-reauth-before-destructive-act.md` — title "No self-service session
    revocation, and no re-authentication before destructive actions", area `security`, severity
    `moderate`, files `security/SecurityConfiguration.java`, `controller/BoardController.java`.
    ASVS V3.3.4, V3.7.1. Problem: no controller exposes a list-sessions or
    revoke-session/revoke-all endpoint despite `SecurityConfiguration`'s
    `MAX_CONCURRENT_SESSIONS = 2` explicitly designing for multi-session use. Separately,
    `BoardController`'s cascading delete (removes all columns/tasks/subtasks) is gated by
    `@PreAuthorize("isAuthenticated()")` only, identical to a read — no step-up/re-auth check for
    irreversible actions. Solution: two independent fixes: (a) add a self-service
    `GET /api/users/me/sessions` (list via the existing `SpringSessionBackedSessionRegistry`) and
    a revoke-one/revoke-all endpoint; (b) require a re-auth step (reusing item 3's
    change-password verification plumbing) before `BoardController`'s cascading delete executes.
  </action>
  <verify>10 files exist under `.planning/todos/pending/` with the exact names listed in
  `&lt;files&gt;`; each has non-empty `## Problem` and `## Solution` sections and a `files:` frontmatter
  list; `severity:` values are exactly `minor` or `moderate` as specified per item (2, 3, 5, 8, 9,
  10 are `moderate`; 1, 4, 6, 7 are `minor`) — no item inflated or deflated.</verify>
  <done>All 10 files created, frontmatter-valid, matching the existing repo convention.</done>
</task>

<task id="3">
  <name>File 9 new todos — infra, deployment, config, and API-hygiene gaps</name>
  <files>
    .planning/todos/pending/2026-08-20-dockerfile-runs-as-root-no-user-directive.md
    .planning/todos/pending/2026-08-20-no-branch-protection-on-master.md
    .planning/todos/pending/2026-08-20-no-prod-post-deploy-health-verification.md
    .planning/todos/pending/2026-08-20-internal-kafka-hop-has-no-sasl-auth-or-tls.md
    .planning/todos/pending/2026-08-20-no-secrets-vault-for-runtime-prod-secrets-no-rotation.md
    .planning/todos/pending/2026-08-20-swagger-openapi-docs-reachable-in-prod-no-profile-gate.md
    .planning/todos/pending/2026-08-20-no-content-type-validation-on-rest-endpoints.md
    .planning/todos/pending/2026-08-20-no-data-classification-or-self-service-export-delete.md
    .planning/todos/pending/2026-08-20-no-documented-backup-restore-runbook-for-prod-db.md
  </files>
  <action>
Same frontmatter/prose conventions as Task 2 (`created: 2026-08-20T00:00:00.000Z`, matching key
order, evidence-first Problem, concrete Solution).

11. `dockerfile-runs-as-root-no-user-directive.md` — title "Container runs as root — no USER
    directive in the Dockerfile", area `security`, severity `moderate`, files `Dockerfile`. ASVS
    V1.2.1, V1.14.5. Problem: both build and runtime stages of the repo-root `Dockerfile` (17
    lines) omit `USER` entirely; the runtime `ENTRYPOINT` therefore runs as
    `eclipse-temurin:21-jre-jammy`'s default root user. Solution: add a non-root `USER` directive
    to the runtime stage (create a dedicated app user/group via `RUN addgroup`/`adduser`, `chown`
    the app jar/working dir to it, `USER appuser` before `ENTRYPOINT`); verify the container still
    starts and serves traffic under the new uid, and no file-permission regressions occur.

12. `no-branch-protection-on-master.md` — title "No branch protection on master", area `ci`,
    severity `moderate`, files `.github/workflows/deploy.yml`. ASVS V1.10.1. Problem: `gh api
    repos/RudVlad473/kanban-board-backend/branches/master/protection` returns live HTTP 404
    (confirmed 2026-08-20) — no required reviews, no required status checks, no
    force-push/history-rewrite restriction on the branch `deploy.yml` deploys straight from.
    Solution: enable GitHub branch protection on `master` (require PR review and/or the existing
    test/build/security-scan workflows as required status checks at minimum; consider restricting
    force-push and branch deletion). This is a GitHub repository setting, not a code change — cite
    the `deploy.yml` trust boundary it protects (unreviewed pushes currently flow straight to
    `deploy-to-netcup`).

13. `no-prod-post-deploy-health-verification.md` — title "Production deploys get no automated
    post-deploy health verification (nonprod does)", area `ci`, severity `moderate`, files
    `.github/workflows/deploy.yml`. ASVS V1.14.4. Problem: `deploy.yml`'s `health-check-nonprod`
    job (polls the live nonprod endpoint after deploy) has no production equivalent —
    `deploy-to-netcup` and `register-schemas-production` have no downstream health-check job.
    Solution: add a `health-check-production` job mirroring `health-check-nonprod`'s structure
    (poll the production health endpoint post-deploy, fail the run on non-2xx/timeout); note this
    compounds with item 9's logging/alerting gap — a broken prod deploy could currently go
    undetected by both the pipeline and any monitoring layer.

14. `internal-kafka-hop-has-no-sasl-auth-or-tls.md` — title "Internal Kafka / schema-registry hop
    has neither SASL auth nor TLS", area `security`, severity `moderate`, files
    `docker-compose.prod.yml`, `docs/INFRA_ARCHITECTURE.md`. ASVS V1.2.2, V9.2.2, V1.9.1. Problem:
    redpanda's command block in `docker-compose.prod.yml` sets only
    `--kafka-addr`/`--advertise-kafka-addr`/`--rpc-addr` with no `--sasl` or TLS listener flag;
    the same finding applies to the Caddy-to-app internal hop (also plain HTTP, per
    `docs/INFRA_ARCHITECTURE.md`'s own documented trade-off). Compensating control: neither app nor
    redpanda publishes a host port (Docker-internal only) — real gap against the letter of the
    requirement, exploitability requires an attacker already inside the VM's Docker network.
    Solution: add SASL/SCRAM and/or TLS to the internal Redpanda listener and, resources
    permitting, the Caddy-to-app hop; if deferred, document the accepted-risk compensating control
    (Docker-internal-only networking) explicitly in `docs/INFRA_ARCHITECTURE.md` rather than
    leaving it an implicit assumption.

15. `no-secrets-vault-for-runtime-prod-secrets-no-rotation.md` — title "No secrets-vault for
    runtime production secrets; no stated key/secret rotation cadence", area `security`, severity
    `moderate`, files `docker-compose.prod.yml`, `docs/INFRA_RUNBOOK.md`. ASVS V1.6.1, V1.6.2,
    V1.6.3, V6.4.1. Problem: CI/build-time secrets are properly vault-managed (GitHub Actions
    encrypted secrets). Runtime production DB credentials (`DB_HOST`/`DB_USER`/`DB_PASS` etc.) are
    instead read from a mode-protected plaintext `.env.prod` file on the VM's local disk via
    `docker-compose.prod.yml`'s `--env-file` — not vault-managed at rest, no rotation-via-API
    capability, and `docs/INFRA_RUNBOOK.md`'s secret inventory documents ad hoc last-updated
    timestamps per secret but no stated rotation cadence or policy. Solution: evaluate a
    lightweight secrets approach appropriate to this VPS's scale (e.g. sops-encrypted `.env.prod`
    decrypted at deploy time) instead of a plaintext file at rest; separately, write a stated
    rotation cadence/policy into `docs/INFRA_RUNBOOK.md`'s existing secret inventory as a cheap
    first step independent of the tooling change.

16. `swagger-openapi-docs-reachable-in-prod-no-profile-gate.md` — title "Swagger / OpenAPI docs
    are reachable in production with no profile gate", area `security`, severity `moderate`, files
    `security/SecurityConfiguration.java`, `constant/ApiPaths.java`,
    `src/main/resources/application.properties`. ASVS V14.1.3, V14.2.2. Problem:
    `SecurityConfiguration`'s requestMatchers for the Swagger docs path and its wildcard, plus
    `ApiPaths.SWAGGER_UI`'s wildcard, are matched with `.permitAll()` and no `@Profile` gate
    anywhere in that class or `application.properties` — free API-surface reconnaissance for
    anyone who discovers the URL. Solution: gate Swagger/OpenAPI UI and docs endpoints behind a
    non-production profile check (`springdoc.api-docs.enabled`/`springdoc.swagger-ui.enabled=false`
    in a production-specific properties file, or an explicit `@Profile("!production")` guard
    around the `permitAll` matcher); add a test asserting a production-profile request to both
    paths returns 404/403.

17. `no-content-type-validation-on-rest-endpoints.md` — title "No explicit Content-Type
    validation on REST endpoints", area `backend`, severity `minor`, files
    `controller/BoardController.java`, `controller/ColumnController.java`,
    `controller/TaskController.java`, `controller/SubtaskController.java`,
    `handler/GlobalExceptionHandler.java`. ASVS V13.1.5, V13.2.5. Problem: a grep across all
    controller classes for `consumes=`/`produces=` returns zero matches;
    `GlobalExceptionHandler`'s `Exception.class` catch-all has no arm for
    `HttpMediaTypeNotSupportedException` or `HttpMediaTypeNotAcceptableException`, so a request
    with an unexpected content type never gets the 406/415 ASVS expects. Solution: add explicit
    `consumes = MediaType.APPLICATION_JSON_VALUE` to `@PostMapping`/`@PutMapping`/`@PatchMapping`
    handlers across all controllers; add dedicated `@ExceptionHandler` arms for both exceptions in
    `GlobalExceptionHandler`, routed through the same RFC 7807 `ProblemDetail` envelope as every
    other exception; add a test sending an unexpected `Content-Type` and asserting 415.

18. `no-data-classification-or-self-service-export-delete.md` — title "No formal data
    classification; no self-service data export/delete/consent capture", area `security`,
    severity `minor`, files `entity/UserEntity.java`, `dto/user_dto/SignupRequestDTO.java`,
    `service/UserService.java`. ASVS V1.8.1, V1.8.2, V8.3.2, V8.3.3, V8.3.4, V8.3.5, V8.3.8.
    Problem: point protections exist and are individually sound (BCrypt hashing, `@JsonIgnore` on
    `UserEntity`'s password hash field, TLS to the DB) but were never traced to a named
    classification/retention policy document; `SignupRequestDTO` has no consent/terms-acceptance
    field; `UserService.deleteById` already cascades correctly (via
    `boardService.deleteAllByUserId`) but no controller route exposes account deletion or data
    export to the user themselves. Solution: two independent, both-worth-doing tracks: (a) write a
    short data-classification/retention policy naming what's collected (email, display name,
    password hash, board/task content) and tying it to the existing point protections already in
    place; (b) add self-service `DELETE /api/users/me` (wired to the existing
    `UserService.deleteById` cascade) and `GET /api/users/me/export` (a JSON dump of the user's own
    boards/columns/tasks/subtasks), plus a consent/terms-acceptance field on `SignupRequestDTO` if
    this project has terms to accept.

19. `no-documented-backup-restore-runbook-for-prod-db.md` — title "No documented backup / restore
    runbook for the production database", area `infra`, severity `minor`, files
    `docs/INFRA_RUNBOOK.md`. ASVS V14.1.4. Problem: a grep of `docs/INFRA_RUNBOOK.md` for
    `backup|restore|recovery|PITR` returns only unrelated hits (network/Redpanda
    consumer-group recovery, an iptables note). Neon likely offers point-in-time recovery by
    default as a platform feature, but this has never been confirmed or written down as a tested
    procedure. Solution: confirm Neon's actual PITR/backup retention window for this project's plan
    tier directly (dashboard/docs, don't assume), then write a backup/restore runbook section into
    `docs/INFRA_RUNBOOK.md` documenting the confirmed retention window, the exact restore
    procedure, and — ideally — a once-executed test restore to a scratch branch proving the
    procedure actually works.
  </action>
  <verify>9 files exist under `.planning/todos/pending/` with the exact names listed in
  `&lt;files&gt;`; each has non-empty `## Problem` and `## Solution` sections; `severity:` values are
  exactly `moderate` (11, 12, 13, 14, 15, 16) or `minor` (17, 18, 19) as specified.</verify>
  <done>All 9 files created, frontmatter-valid, matching the existing repo convention. Combined
  with Task 2, all 19 new todos exist and 26 pre-existing pending todos are untouched by these two
  tasks.</done>
</task>

<task id="4">
  <name>Update STATE.md's Pending Todos section</name>
  <files>.planning/STATE.md</files>
  <action>
Edit the `### Pending Todos` section (not any other section):

1. In the "Still open and out of v1.3 scope" intro line, change the item-count pointer from
   `~26 items` to `~45 items` (26 pre-existing pending todos + 19 new ones from this task, none
   resolved).
2. Append these 12 bullets to the existing bullet list (after the current last bullet, same
   `- [tag] description (`filename.md`).` format as the existing bullets):
   - `[security]` Password composition regex requires ASCII-only character classes, blocking
     valid Unicode-only passwords and contradicting ASVS's own no-composition-rules guidance
     (`2026-08-20-password-composition-regex-blocks-unicode-only-pass.md`).
   - `[security]` No password-change endpoint exists anywhere in the API — a password can never
     be changed once set (`2026-08-20-no-password-change-capability-exists-anywhere-in-api.md`).
   - `[security]` No MFA/second-factor enrollment path; BCrypt password comparison is the sole
     authentication factor (`2026-08-20-no-mfa-second-factor-enrollment-path.md`).
   - `[security]` Zero security-event logging on the authentication/access-control paths — a real
     credential-stuffing attempt leaves no forensic trail
     (`2026-08-20-no-security-event-logging-on-auth-and-access-control.md`).
   - `[infra]` No remote log shipping, structured/UTC logging standard, or alerting on unusual
     activity — overlaps the existing unimplemented Prometheus+Grafana backlog item
     (`2026-08-20-no-remote-log-shipping-structured-logging-or-alerting.md`).
   - `[security]` No self-service session revocation and no re-authentication gate before
     destructive actions like a board's cascading delete
     (`2026-08-20-no-session-revocation-or-reauth-before-destructive-act.md`).
   - `[security]` Container runs as root — Dockerfile has no USER directive in either build or
     runtime stage (`2026-08-20-dockerfile-runs-as-root-no-user-directive.md`).
   - `[ci]` No branch protection on master — confirmed live via `gh api`, no required reviews or
     status checks (`2026-08-20-no-branch-protection-on-master.md`).
   - `[ci]` Production deploys get no automated post-deploy health check, unlike nonprod
     (`2026-08-20-no-prod-post-deploy-health-verification.md`).
   - `[security]` Internal Kafka/schema-registry hop has neither SASL auth nor TLS (Docker-internal
     -only networking is a compensating control, not a fix)
     (`2026-08-20-internal-kafka-hop-has-no-sasl-auth-or-tls.md`).
   - `[security]` No secrets vault for runtime production secrets (plaintext `.env.prod` on VM
     disk) and no stated rotation cadence
     (`2026-08-20-no-secrets-vault-for-runtime-prod-secrets-no-rotation.md`).
   - `[security]` Swagger/OpenAPI docs are reachable in production with no profile gate
     (`2026-08-20-swagger-openapi-docs-reachable-in-prod-no-profile-gate.md`).
3. Append one bundled bullet for the 7 minor-severity new todos, matching this section's existing
   bundling convention (e.g. the "Full-system sequence diagram..." bundled bullet already
   present):
   - `[minor]` Password length bounds undersized vs. ASVS; no breached-password check; no secret
     pepper on BCrypt; no auth-detail-change notifications (blocked on missing email infra); no
     Content-Type validation on REST endpoints; no formal data classification or self-service
     export/delete; no documented DB backup/restore runbook — see `.planning/todos/pending/` for
     the 7 minor-severity todos filed 2026-08-20.
4. Append a new `**Update (2026-08-20, later still — ASVS 4.0.3 Level 2 audit):**` paragraph after
   the existing last "Update" paragraph in this section (the one ending "...that same id's real
   parent."), summarizing: a 33-agent ASVS 4.0.3 Level 2 audit cross-referenced the 6 most recent
   security-area pending todos against the ASVS chapter set (all 6 corroborated; one — the
   security-response-headers todo — also gained a new confirmed finding on Referrer-Policy and a
   correction narrowing the X-Frame-Options gap; the rate-limiting todo's scope broadened from
   `/signin`-only to general request-volume abuse per 3 independently converging ASVS chapters);
   19 new pending todos filed (12 moderate, 7 minor) spanning password policy, MFA, session
   revocation, logging/observability, container/infra hardening, and CI governance.

Do not touch any other STATE.md section (Current Position, Blockers/Concerns, Quick Tasks
Completed, Deferred Items, Session Continuity, Operator Next Steps).
  </action>
  <verify>`git diff .planning/STATE.md` shows changes confined to the `### Pending Todos` section
  body; the string `~45 items` appears; all 19 new filenames appear at least once somewhere in the
  section (12 individually-cited moderate ones plus the bundled minor-summary line referencing the
  directory).</verify>
  <done>STATE.md's Pending Todos section reflects the 19 new todos with representative bullets for
  all 12 moderate-severity items, an updated item count, and a summary update paragraph.</done>
</task>
