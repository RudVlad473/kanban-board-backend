# Phase 10: CI & Deploy Hardening - Context

**Gathered:** 2026-08-19
**Status:** Ready for planning

<domain>
## Phase Boundary

The repository's CI, secret-scanning, and session-cookie configuration close the eight hardening
gaps accumulated across v1.2 (HARDEN-01..08, all locked in REQUIREMENTS.md), plus three
additional supply-chain/CI-reliability gaps folded in during this discussion. The README stands
on its own as a full architecture showcase. No new capabilities — this phase closes accumulated
CI/deploy/security-tooling debt, not the nonprod build itself (already shipped, Phases 8-9).

</domain>

<decisions>
## Implementation Decisions

### README shape (HARDEN-08)
- **D-01:** README lands in the middle ground between the current 116-line trimmed front door
  (quick task 21, 2026-08-06) and a full reversal of that split — more descriptive and structured
  than today, but still organized/scannable, not a wall of text. — **Reversibility:** reversible —
  a markdown file, trivially re-edited later.
- **D-02:** Embed exactly one top-level architecture/system diagram directly in README (GitHub
  renders Mermaid natively); the rest of `docs/diagrams/`'s diagrams stay linked, not embedded.
- **D-03:** README leads with production reality + the CI/CD pipeline (Netcup/Neon/Redpanda,
  CI gates, the nonprod environment) — differentiates from a typical portfolio CRUD project —
  ahead of testing-architecture depth.
- **D-04:** "Why this technology" rationale (Testcontainers over H2, Flyway, gitleaks over
  TruffleHog, Netcup over Oracle/AWS/GCP/Hetzner, etc.) gets short one-line inline callouts next
  to each major stack choice (e.g., in the existing Stack table / Engineering highlights section)
  — not omitted, not a full decision log. Full reasoning stays in STATE.md/git history for anyone
  who wants depth.
- Cover list carried from the source todo (all should be touched, weighted per D-03): CI/CD
  pipeline and deploy strategy, pre-commit hooks and CI quality gates (Spotless, Error Prone,
  JaCoCo, OWASP dependency-check, secret scanning — including this phase's TruffleHog addition),
  testing architecture (tiers, Testcontainers), the `docs/diagrams/` diagrams, local dev, stack
  table with inline rationale.

### Digest-pinning scope (HARDEN-03)
- **D-05:** Digest-pin only the third-party GitHub Actions — `appleboy/scp-action`,
  `appleboy/ssh-action` (materially higher publisher-compromise risk; both run with real SSH keys
  against production/staging VMs). First-party GitHub/Docker actions (`actions/checkout`,
  `actions/setup-java`, `actions/cache`, `actions/upload-artifact`, `docker/setup-buildx-action`,
  `docker/build-push-action`) keep tag-only trust, documented as a deliberate, explicit
  risk-acceptance comment in the workflow file(s) rather than silently left unaddressed. —
  **Reversibility:** reversible but costly to maintain — a digest pin needs a two-step
  lookup-then-edit on every future version bump instead of a one-line tag edit; that cost
  recurs indefinitely, not once.
- **D-06:** Pin format is `@<sha>  # v5.x.x` (digest + human-readable version comment) — matches
  the exact pattern `secret-scan.yml` already uses for its pinned `gitleaks` image reference, so
  the convention is internally consistent across the repo.
- **D-07:** Planning/research must explicitly verify that Dependabot's new `github-actions`
  ecosystem entry (HARDEN-01) correctly opens PRs bumping both the digest and the version comment
  for the newly-pinned third-party actions — HARDEN-01 and HARDEN-03 land in the same phase and
  must compose correctly together, not just pass their own acceptance criteria independently.

### TruffleHog gating philosophy (HARDEN-02)
- **D-08:** A TruffleHog-verified-live credential hard-fails the CI job — no `continue-on-error`,
  same posture as gitleaks' existing hard-gate. TruffleHog only flags a subset (verified-live) of
  what gitleaks already catches, so hard-gating adds no new false-positive risk beyond what
  gitleaks already tolerates. — **Reversibility:** reversible — a workflow YAML step, can be
  relaxed to report-only later without touching any other file.
- **D-09:** Runs on every push/PR, added as a second scan step/job in `secret-scan.yml`, same
  trigger as the existing gitleaks job — a live-credential check is highest-value right when the
  credential lands, not a week later on a schedule.
- **D-10:** Confirmed: TruffleHog's CI-only placement is safe. The network-call-per-finding
  concern that ruled TruffleHog out as the *primary*/pre-commit scanner (quick task 260816-hn1,
  Decision 1) was specifically about a developer's local machine phoning out with real credentials
  during a commit — a GitHub-hosted CI runner making the same verification calls carries no
  equivalent exposure. No further research needed on this point.

### Gitleaks worktree fix scope (HARDEN-05)
- **D-11:** Ship a real code fix in `.githooks/pre-commit` this phase (not just a documented
  limitation), even though the source todo itself called this "not urgent" — the requirement is
  now locked in REQUIREMENTS.md, so Phase 10 delivers it as a functional fix.
- **D-12:** Approach: detect when the worktree's common ancestor (`dirname "$(git rev-parse
  --path-format=absolute --git-common-dir)"`) is not a clean mountable subtree containing both the
  work tree and the git-dir, and fall back to piping the staged diff to gitleaks via stdin in that
  case, instead of the bind-mounted git-dir scan the nested-worktree path uses today. Documented
  trade-off (carried from the source todo): the stdin-fallback path loses path-based allowlist
  context for that specific case only — accepted, since it's the less-common path (this repo's
  own convention nests worktrees under `.claude/worktrees/`, which the existing bind-mount path
  already handles correctly).

### Claude's Discretion
- Exact README section ordering/wording beyond the leadwith-production-reality decision (D-03).
- Which single diagram from `docs/diagrams/` is the "top-level" one embedded in README (D-02).
- Whether the digest-pinning risk-acceptance comment (D-05) lives inline per-`uses:` line or as
  one workflow-level comment block.
- Exact CI job/step placement for the two folded Gradle supply-chain todos (see Folded Todos)
  within `deploy.yml`/`security-scan.yml`'s existing job graph.

### Folded Todos

**NVD_API_KEY CI bug** (`.planning/todos/pending/2026-08-19-security-scan-yml-nvd-api-key-not-resolving.md`,
severity: moderate) — `security-scan.yml`'s `dependency-check` job has failed at its "Verify
NVD_API_KEY is configured" step since at least 2026-08-17 (predates Phase 9), despite `gh secret
list` confirming the secret exists at repository scope with an unchanged `updatedAt`. Root cause
not yet investigated — candidates: GitHub secret storage/propagation defect, invisible
whitespace/encoding in the stored value, or an org-level secret-policy interaction. The todo's own
suggested first step: add a temporary diagnostic step that prints a `sha256sum` of the secret
(never the raw value) to confirm what's actually stored, before assuming a fix. Folded into Phase
10: diagnose and fix, not just re-triage.

**Gradle dependency verification metadata** (`.planning/todos/pending/2026-08-18-add-gradle-dependency-verification-metadata.md`,
severity: minor) — no `gradle/verification-metadata.xml` exists; exact version pins in
`build.gradle` alone don't catch a compromised artifact republished under the same
coordinates+version at Maven Central or `packages.confluent.io/maven/`. Distinct trust boundary
from HARDEN-03 (CI Action integrity vs. Gradle dependency-artifact integrity). Suggested approach:
`./gradlew --write-verification-metadata sha256`, commit the generated file, wire a CI staleness
check (candidate placement: `run-tests`, fast/deterministic — not the network-bound
`security-scan.yml`).

**Gradle wrapper integrity validation in CI** (`.planning/todos/pending/2026-08-18-add-gradle-wrapper-integrity-validation-to-ci.md`,
severity: minor) — `gradle/wrapper/gradle-wrapper.properties` has `validateDistributionUrl=true`
but no `distributionSha256Sum` pin, and neither `deploy.yml` nor `security-scan.yml` runs
`gradle/actions/wrapper-validation` to confirm `gradlew`/`gradlew.bat`/`gradle-wrapper.jar`
haven't been tampered with. Suggested approach: pin `distributionSha256Sum` for Gradle 8.11.1
(from `services.gradle.org`'s published checksum), add `gradle/actions/wrapper-validation@v4` (or
current) as the first step — before any `./gradlew` invocation — in both `deploy.yml`'s
`run-tests` job and `security-scan.yml`.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Requirements & roadmap
- `.planning/REQUIREMENTS.md` — HARDEN-01..08 full requirement text (locked), traceability table,
  phase-mapping rationale
- `.planning/ROADMAP.md` §"Phase 10: CI & Deploy Hardening" — goal, dependencies (Phase 8 for TLS
  readiness on HARDEN-07, Phase 9 for a settled job graph on HARDEN-03), success criteria

### CI/CD workflow files (current, verified live 2026-08-19 — codebase maps under
`.planning/codebase/` are stale/pre-AWS-migration and should NOT be used for this phase)
- `.github/workflows/deploy.yml` — full current job graph (setup, run-tests, build-and-push,
  flyway-verify[-nonprod], deploy-to-netcup, deploy-to-nonprod, register-schemas-production,
  health-check-nonprod, cleanup-old-images[-nonprod], cleanup-unused-image[-nonprod]). Targets:
  HARDEN-03 (digest-pin `appleboy/scp-action@v1.0.0`, `appleboy/ssh-action@v1.2.5`), HARDEN-04
  (`run-tests` job's `Set up Java` step has no `cache: 'gradle'` today), wrapper-validation
  (folded todo). Note line 655-660: `cleanup-unused-image-nonprod`'s own comment already flags
  "Fixing both copies for real is Phase 10 CI-hardening scope" for the production
  `cleanup-unused-image` job's missing DELETE status check — read before touching that job.
- `.github/workflows/security-scan.yml` — OWASP dependency-check job. Targets: HARDEN-06 (stale
  "temurin, not deploy.yml's adopt" comment — deploy.yml no longer uses `adopt`, already fixed to
  `temurin`; also carries outdated `checkout@v3`/`setup-java@v4`), the folded NVD_API_KEY bug
  (currently fails at the "Verify NVD_API_KEY is configured" step), HARDEN-03 digest-pinning
  scope decision (first-party actions here keep tag-only per D-05), wrapper-validation (folded
  todo).
- `.github/workflows/secret-scan.yml` — gitleaks CI job. Target: HARDEN-02 (add TruffleHog as a
  second step/job here per D-08/D-09). Already demonstrates this repo's digest-pin-with-version-
  comment precedent (`ghcr.io/gitleaks/gitleaks:v8.30.1@sha256:...`) — the pattern D-06 reuses.
  Uses `actions/checkout@v4`, diverging deliberately (per its own comment) from both
  `deploy.yml` (`@v5`) and `security-scan.yml` (`@v3`) — not itself a HARDEN item, but relevant
  context for anyone touching action versions in this phase.
- `.githooks/pre-commit` — gitleaks pre-commit hook. Target: HARDEN-05 (worktree fix, D-11/D-12).
  Current worktree-mounting logic (lines ~50-65) already handles the nested
  `.claude/worktrees/<name>` case correctly; the gap is only the outside-main-tree case.
- `.github/dependabot.yml` — current `gradle`-only ecosystem, with an explicit comment on why
  `github-actions` wasn't added yet (Phase 5's in-flight deploy.yml rewrite — now settled).
  Target: HARDEN-01. Must compose with HARDEN-03 per D-07.
- `gradle/wrapper/gradle-wrapper.properties` — target for the folded wrapper-integrity todo
  (`distributionSha256Sum`).
- `build.gradle` — target for the folded dependency-verification-metadata todo.

### Session cookie config (HARDEN-07)
- `src/main/resources/application.properties` line 131 — `server.servlet.session.cookie.secure=false`
- `src/main/resources/application-test.properties` line 48 — `server.servlet.session.cookie.secure=false`
  (both currently `false`; requirement is to set `true` in both, now that real TLS exists in
  production per Phase 5/8)

### README & docs (HARDEN-08)
- `README.md` — current 116-line front door (post quick-task-21 trim), target for D-01..D-04
- `docs/ARCHITECTURE.md`, `docs/INFRA_ARCHITECTURE.md` — existing depth docs README should link
  to (not duplicate)
- `docs/diagrams/` — existing Mermaid diagrams; one gets embedded per D-02

### Source todos (full problem statements, rejected-approach reasoning, and suggested solutions —
read before planning each corresponding HARDEN item or folded todo)
- `.planning/todos/pending/2026-08-16-expand-readme-into-a-full-project-architecture-showcase.md`
- `.planning/todos/pending/2026-08-16-digest-pin-github-actions-mutable-tags-are-currently-trusted-by-tag-only.md`
- `.planning/todos/pending/2026-08-16-add-a-trufflehog-live-credential-verification-pass-in-ci.md`
- `.planning/todos/pending/2026-08-16-gitleaks-hook-cannot-scan-a-worktree-created-outside-the-main-repo-tree.md`
- `.planning/todos/pending/2026-08-13-add-github-actions-ecosystem-to-dependabot-after-deploy-rewrite.md`
- `.planning/todos/pending/2026-08-16-add-gradle-cache-to-deploy-yml-run-tests-job.md`
- `.planning/todos/pending/2026-08-16-security-scan-yml-stale-comment-and-stale-actions-after-260816-sv1.md`
- `.planning/todos/pending/2026-08-10-set-secure-flag-on-session-cookie-once-real-tls-exists.md`
- `.planning/todos/pending/2026-08-19-security-scan-yml-nvd-api-key-not-resolving.md` (folded)
- `.planning/todos/pending/2026-08-18-add-gradle-dependency-verification-metadata.md` (folded)
- `.planning/todos/pending/2026-08-18-add-gradle-wrapper-integrity-validation-to-ci.md` (folded)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `secret-scan.yml`'s existing gitleaks digest-pin (`ghcr.io/gitleaks/gitleaks:v8.30.1@sha256:...`)
  is the direct template for both HARDEN-03's pin format (D-06) and its full-history-scan/
  hard-gate/`--redact`/baseline-file pattern, which TruffleHog's own CI job (HARDEN-02) should
  mirror for consistency (upload-artifact-on-`always()`, redacted report, etc.).
- `.githooks/pre-commit`'s existing `GIT_COMMON_DIR`/`MOUNT_ROOT`/`REL_WORKTREE` computation is the
  correct-for-the-nested-case logic HARDEN-05's fix extends, not replaces — only the
  outside-main-tree branch needs new code.
- `security-scan.yml`'s dependency-check job already has a working "fail loudly if secret is
  missing" preflight pattern (`Verify NVD_API_KEY is configured` step) that models the kind of
  explicit, no-silent-failure diagnostic the folded NVD_API_KEY bug's suggested fix (temporary
  `sha256sum` probe step) should follow.

### Established Patterns
- Every hard-gated check in this repo (gitleaks at both hook and CI) is a pure function of the
  commit — no `continue-on-error`, no schedule-driven drift. TruffleHog's hard-gate decision
  (D-08) matches this; the OWASP dependency-check job is the deliberate counter-example (report-
  only, schedule-driven, verdict can drift independent of code changes) — useful contrast when
  writing HARDEN-02's job.
- This repo already documents *why* a dependency/tool was chosen inline in workflow-file comments
  (see `secret-scan.yml`'s and `security-scan.yml`'s extensive rationale comments) — D-04's inline
  README callouts extend this same habit to README itself.
- Every environment-scoped secret access in `deploy.yml` already declares `environment:
  production` or `environment: staging` explicitly per job (Phase 9 pattern) — any new job this
  phase adds that touches `secrets.*` should follow the same explicit-environment convention.

### Integration Points
- HARDEN-02 (TruffleHog) integrates into `secret-scan.yml` alongside the existing `secret-scan`
  job — either a new step in that job or a sibling job in the same file, per D-09's "same trigger
  as gitleaks."
- HARDEN-01 (Dependabot) and HARDEN-03 (digest-pinning) both touch `.github/`-adjacent config and
  must be planned together per D-07 — sequencing matters (which lands first affects what the
  other verifies against).
- The folded wrapper-validation todo touches both `deploy.yml` and `security-scan.yml` — a single
  step addition to two files, not a new workflow.

</code_context>

<specifics>
## Specific Ideas

- README: "somewhere in the middle" — descriptive and structured, diagrams linked to docs rather
  than dumped inline (except the one embedded top-level diagram per D-02). Direct user framing,
  preserved verbatim in intent: not a return to a wall-of-text README, not the current terse
  116-line version either.
- Digest-pinning: explicitly scoped to the two `appleboy/*` actions because they carry real SSH
  keys to production/staging VMs — a materially different risk tier than GitHub/Docker
  first-party actions.
- TruffleHog: the user endorsed hard-gating specifically because it only narrows (verifies) what
  gitleaks already flags, not because TruffleHog is independently trustworthy at scale.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope. All four proposed gray areas were selected and
discussed; the three additional todos considered for folding were all folded in (see Folded Todos
above) rather than deferred.

### Reviewed Todos (not folded)
None — the `todo.match-phase` query returned 36 total matches, but only the three highest-signal,
already-flagged-in-STATE.md candidates (NVD_API_KEY bug, Gradle verification metadata, Gradle
wrapper integrity) were surfaced to the user for a fold decision; the remaining ~33 matches were
low-relevance/out-of-domain (backend code cleanup, test flakiness, unrelated security audits) and
were filtered out before presentation rather than individually reviewed-and-rejected with the
user. If a future phase wants to revisit that filtering, the full match list is reproducible via
`gsd_run query todo.match-phase 10`.

</deferred>

---

*Phase: 10-ci-deploy-hardening*
*Context gathered: 2026-08-19*
