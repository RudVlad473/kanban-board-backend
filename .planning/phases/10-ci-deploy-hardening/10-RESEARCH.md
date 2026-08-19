# Phase 10: CI & Deploy Hardening - Research

**Researched:** 2026-08-19
**Domain:** GitHub Actions CI/CD hardening, secret-scanning tooling, Spring Session cookie config, Gradle supply-chain integrity, README documentation
**Confidence:** MEDIUM-HIGH (all in-repo claims verified by direct file reads this session; external tool/library claims a mix of CITED and one genuinely open live-CI question)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**README shape (HARDEN-08)**
- **D-01:** README lands in the middle ground between the current 116-line trimmed front door
  (quick task 21, 2026-08-06) and a full reversal of that split — more descriptive and structured
  than today, but still organized/scannable, not a wall of text. Reversible.
- **D-02:** Embed exactly one top-level architecture/system diagram directly in README (GitHub
  renders Mermaid natively); the rest of `docs/diagrams/`'s diagrams stay linked, not embedded.
- **D-03:** README leads with production reality + the CI/CD pipeline (Netcup/Neon/Redpanda, CI
  gates, the nonprod environment) — ahead of testing-architecture depth.
- **D-04:** "Why this technology" rationale gets short one-line inline callouts next to each major
  stack choice — not omitted, not a full decision log. Full reasoning stays in STATE.md/git history.
- Cover list (weighted per D-03): CI/CD pipeline and deploy strategy, pre-commit hooks and CI
  quality gates (Spotless, Error Prone, JaCoCo, OWASP dependency-check, secret scanning including
  this phase's TruffleHog addition), testing architecture, `docs/diagrams/`, local dev, stack table
  with inline rationale.

**Digest-pinning scope (HARDEN-03)**
- **D-05:** Digest-pin ONLY the third-party GitHub Actions — `appleboy/scp-action`,
  `appleboy/ssh-action` (materially higher publisher-compromise risk; both run with real SSH keys
  against production/staging VMs). First-party GitHub/Docker actions (`actions/checkout`,
  `actions/setup-java`, `actions/cache`, `actions/upload-artifact`, `docker/setup-buildx-action`,
  `docker/build-push-action`) keep tag-only trust, documented as a deliberate, explicit
  risk-acceptance comment in the workflow file(s). Reversible but costly to maintain.
- **D-06:** Pin format is `@<sha>  # v5.x.x` (digest + human-readable version comment) — matches
  the exact pattern `secret-scan.yml` already uses for its pinned `gitleaks` image reference.
- **D-07:** Planning/research must explicitly verify that Dependabot's new `github-actions`
  ecosystem entry (HARDEN-01) correctly opens PRs bumping both the digest and the version comment
  for the newly-pinned third-party actions — HARDEN-01 and HARDEN-03 land in the same phase and
  must compose correctly together.

**TruffleHog gating philosophy (HARDEN-02)**
- **D-08:** A TruffleHog-verified-live credential hard-fails the CI job — no `continue-on-error`,
  same posture as gitleaks' existing hard-gate.
- **D-09:** Runs on every push/PR, added as a second scan step/job in `secret-scan.yml`, same
  trigger as the existing gitleaks job.
- **D-10:** Confirmed: TruffleHog's CI-only placement is safe (no developer-machine network
  exposure). No further research needed on this point.

**Gitleaks worktree fix scope (HARDEN-05)**
- **D-11:** Ship a real code fix in `.githooks/pre-commit` this phase (not just a documented
  limitation).
- **D-12:** Approach: detect when the worktree's common ancestor
  (`dirname "$(git rev-parse --path-format=absolute --git-common-dir)"`) is not a clean mountable
  subtree containing both the work tree and the git-dir, and fall back to piping the staged diff
  to gitleaks via stdin in that case. Documented trade-off: the stdin-fallback path loses
  path-based allowlist context for that specific case only — accepted.

### Claude's Discretion
- Exact README section ordering/wording beyond the leadwith-production-reality decision (D-03).
- Which single diagram from `docs/diagrams/` is the "top-level" one embedded in README (D-02).
- Whether the digest-pinning risk-acceptance comment (D-05) lives inline per-`uses:` line or as
  one workflow-level comment block.
- Exact CI job/step placement for the two folded Gradle supply-chain todos within
  `deploy.yml`/`security-scan.yml`'s existing job graph.

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope. All three additional todos considered for folding
(NVD_API_KEY CI bug, Gradle dependency verification metadata, Gradle wrapper integrity validation)
were folded in rather than deferred.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| HARDEN-01 | `.github/dependabot.yml` gains a `package-ecosystem: "github-actions"` entry | See Standard Stack / Architecture Patterns — exact YAML block, and the Dependabot comment-sync caveat that must be verified post-merge (D-07) |
| HARDEN-02 | CI runs a TruffleHog live-credential verification pass in `secret-scan.yml` | See Architecture Patterns (Pattern 2) — Docker-direct invocation mirroring gitleaks, `--results=verified`, exit code 183, diff-scoped (not full-history) scan rationale |
| HARDEN-03 | `uses:` references in `deploy.yml`/`security-scan.yml` pinned to commit digests per D-05's third-party-only scope | See Architecture Patterns (Pattern 1) — real fetched commit SHAs for both `appleboy/*` actions, plus the risk-acceptance comment for first-party actions |
| HARDEN-04 | `deploy.yml`'s `run-tests` job's `Set up Java` step sets `cache: 'gradle'` | See Code Examples — one-line addition, `security-scan.yml`'s existing usage is the template |
| HARDEN-05 | Pre-commit gitleaks hook works from a worktree created outside the main repo tree | See Architecture Patterns (Pattern 3) — concrete `case` detection + `git diff --cached \| gitleaks stdin` fallback, verified against gitleaks' own documented stdin mode |
| HARDEN-06 | `security-scan.yml`'s stale comment and outdated `checkout@v3`/`setup-java@v4` corrected | See Common Pitfalls — must re-verify via `workflow_dispatch`, not wait for the Monday cron |
| HARDEN-07 | Session cookie `Secure` flag set in both `application.properties` and `application-test.properties` | See Common Pitfalls (Pitfall 4) — direct proof from reading the actual test fixtures that this will NOT break the E2E/MockMvc suites, resolving the tension with the 2026-08-10 todo's original "leave test profile false" recommendation |
| HARDEN-08 | README expanded into a full architecture showcase | See Architecture Patterns (Pattern 4) — section shape, diagram candidate, inline-rationale sourcing |
| (folded) NVD_API_KEY bug | Diagnose and fix `security-scan.yml`'s failing secret resolution | See Common Pitfalls (Pitfall 7) and Open Questions — root cause NOT resolvable by static research, needs a live diagnostic CI run |
| (folded) Gradle verification metadata | Add `gradle/verification-metadata.xml` | See Code Examples — exact command, CI staleness-check shape |
| (folded) Gradle wrapper integrity | Pin `distributionSha256Sum`, add `gradle/actions/wrapper-validation` | See Code Examples — real fetched SHA-256 for Gradle 8.11.1, exact action version |
</phase_requirements>

## Summary

This phase is almost entirely YAML/shell/properties surgery on files that already exist and
already follow established in-repo conventions — there is very little genuinely new technology to
learn, and the highest-value research finding is that **several of the eight requirements have a
concrete, load-bearing detail that is not obvious from reading the todos alone**: (1) the literal
success-criterion text in the phase description ("every `uses:` reference... resolves to an
immutable commit digest") is **superseded** by CONTEXT.md's D-05, which narrows digest-pinning to
only the two `appleboy/*` actions — the planner must follow D-05, not the phase-description text;
(2) HARDEN-07's session-cookie flip is **provably safe** for both test tiers in this specific
codebase, because both `AbstractAppE2ETest` (REST Assured) and `AbstractAppMockMvcTest` manually
extract and replay the session cookie as a plain header rather than relying on an
RFC-6265-compliant automatic cookie jar — the `Secure` attribute is never consulted by either test
harness, so the 2026-08-10 todo's "test profile should stay `false`" recommendation is now
obsolete and can be overridden; (3) the folded NVD_API_KEY bug's root cause is **not resolvable by
static research** — it requires a live `workflow_dispatch` diagnostic run and must be sequenced as
its own task with a checkpoint, not assumed fixable from a plan-time YAML edit alone.

Everything else is template-following: HARDEN-04's cache addition, HARDEN-06's version bump, and
both folded Gradle-supply-chain todos have a single well-documented, one-shot command or
one-line YAML addition each, verified this session against real upstream sources (a real fetched
SHA-256 for the pinned Gradle distribution, real fetched commit SHAs for both `appleboy/*` tags,
and the current `gradle/actions/wrapper-validation@v6` release).

**Primary recommendation:** Treat HARDEN-01 and HARDEN-03 as one composite task (per D-07) with a
follow-up verification checkpoint after the first Dependabot PR lands (known `dependabot-core`
edge-case bugs mean "the ecosystem entry exists" is not sufficient proof it works); treat the
folded NVD_API_KEY bug as its own gated task with a temporary diagnostic step, not a blind fix;
treat every other HARDEN item as an independent, narrowly-scoped, low-risk config change.

## Architectural Responsibility Map

This phase touches CI/CD tooling and one backend config value — not a client/server request path
— so the standard Browser/Frontend/API/CDN/DB tier table does not map cleanly. Adapted below:

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Dependabot version updates (HARDEN-01) | CI/CD Pipeline (GitHub platform config) | — | Repo-level GitHub feature, not application code |
| Action digest pinning (HARDEN-03) | CI/CD Pipeline (workflow YAML) | — | Supply-chain trust boundary for the deploy/scan workflows themselves |
| TruffleHog live-credential scan (HARDEN-02) | CI/CD Pipeline (workflow YAML) | Secret-Scanning Tooling | Runs alongside the existing gitleaks job in `secret-scan.yml` |
| Gradle build cache (HARDEN-04) | CI/CD Pipeline (workflow YAML) | — | Pure CI wall-clock optimization, no runtime behavior change |
| Gitleaks worktree fix (HARDEN-05) | Local Dev Tooling (`.githooks/`) | — | Runs on a developer machine at commit time, never in CI |
| Stale comment/version bump (HARDEN-06) | CI/CD Pipeline (workflow YAML) | — | Documentation + dependency hygiene inside one workflow file |
| Session cookie `Secure` flag (HARDEN-07) | **API/Backend** (Spring Boot application config) | — | The only capability in this phase that is genuine application-tier config, not CI tooling |
| README expansion (HARDEN-08) | Documentation | — | No code/CI surface; purely `README.md` content |
| NVD_API_KEY fix (folded) | CI/CD Pipeline (GitHub secret store + workflow YAML) | — | Secret-resolution defect, not an application concern |
| Gradle dependency-verification metadata (folded) | Build Tooling (`build.gradle`, `gradle/`) | CI/CD Pipeline | Artifact-integrity boundary distinct from Action-integrity (HARDEN-03) |
| Gradle wrapper integrity (folded) | Build Tooling (`gradle/wrapper/`) | CI/CD Pipeline | Wrapper script/jar tamper-detection, runs before any Gradle invocation |

**Why this matters for planning:** only HARDEN-07 touches `src/main/`. Every other item in this
phase is workflow YAML, a shell script, `build.gradle`/`gradle/`, or `README.md` — meaning the
usual "does this belong in the API tier or the client tier" misassignment risk this map exists to
catch is largely absent here. The real risk in this phase is sequencing (D-07's composition
requirement) and scope-creep (see Pitfall 8), not tier misplacement.

## Standard Stack

### Core
No new production/runtime dependencies. `application.properties`/`application-test.properties`
only flip an existing boolean (`server.servlet.session.cookie.secure`); no new Spring property or
Spring Session module is introduced.

### Supporting (CI/CD tooling — GitHub Actions ecosystem, not npm/pypi/crates)

| Tool | Version (verified this session) | Purpose | When to Use |
|------|-----------------------------------|---------|-------------|
| `gradle/actions/wrapper-validation` | `@v6` [CITED: github.com/gradle/wrapper-validation-action releases] | Validates `gradlew`/`gradlew.bat`/`gradle-wrapper.jar` haven't been tampered with | Add as first step, before any `./gradlew` invocation, in both `deploy.yml`'s `run-tests` job and `security-scan.yml` (folded todo) |
| `trufflesecurity/trufflehog` (Docker image, `ghcr.io/trufflesecurity/trufflehog`) | Pin to a specific released tag+digest at plan/execute time — do NOT use the marketplace action's default `@main` ref | Verified-live-credential scanning (HARDEN-02) | CI-only, `secret-scan.yml`, mirroring gitleaks' own direct-Docker-invocation pattern for consistency |
| `.github/dependabot.yml` `package-ecosystem: "github-actions"` | Native GitHub feature, no version to pin | Opens PRs bumping Action tags/digests | HARDEN-01 |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Docker-direct TruffleHog invocation | `trufflesecurity/trufflehog@main` marketplace action | The marketplace action's documented default `@main` ref is an unpinned mutable branch reference — directly contradicts this phase's own digest-pinning rationale (HARDEN-03) if adopted as-is. If the marketplace action is preferred for its `base`/`head` diffing convenience, it must be pinned to a release tag+digest, not left at `@main`. |
| Detect-and-fallback stdin scan (D-12) | Mount two separate Docker volumes (work tree + git-dir) and reconstruct the relative `commondir` link inside the container | D-12 already rejected this in favor of the simpler stdin fallback — recorded here only so the planner doesn't re-litigate it |
| Repo-level digest-pinning for all first-party actions | Org-level "SHA pinning enforcement" policy (GitHub, Aug 2025 feature) [CITED: github.blog/changelog/2025-08-15] | Out of scope — that's an org/enterprise admin setting, not something a single-repo workflow file controls, and D-05 already explicitly chose the narrower per-repo scope |

**Installation:** No package manager installation — these are `uses:` references in workflow YAML
and one boolean property flip. No `npm install`/`pip install`/`cargo add` applies to this phase.

**Version verification:** `gradle/actions/wrapper-validation@v6` and the `appleboy/*` commit SHAs
below were verified live this session (GitHub API / release pages, 2026-08-19). Re-verify the
TruffleHog Docker image digest at execution time — image digests for a moving tag like `latest`
change on every rebuild, and even a pinned release tag's digest must be looked up fresh (see Code
Examples for the exact lookup command).

## Package Legitimacy Audit

> This phase installs no npm/PyPI/crates packages — `gsd_run query package-legitimacy check` does
> not cover the GitHub Actions Marketplace / container-registry ecosystem this phase touches. The
> table below substitutes manual verification against each tool's official source, per the same
> spirit as the package-legitimacy gate.

| Tool | Registry/Org | Evidence | Verdict | Disposition |
|------|--------------|----------|---------|-------------|
| `gradle/actions` (incl. `wrapper-validation`) | github.com/gradle (official Gradle organization) | [CITED: github.com/gradle/actions, github.com/gradle/wrapper-validation-action] — maintained by Gradle's own org, successor to the older standalone `gradle/wrapper-validation-action` repo | OK | Approved — first-party for the build tool already in use |
| `trufflesecurity/trufflehog` | github.com/trufflesecurity (Truffle Security Co.) | [CITED: github.com/trufflesecurity/trufflehog, ghcr.io/trufflesecurity/trufflehog] — widely-adopted (60k+ stars), AGPL-3.0 licensed, this exact tool name is the one the 2026-08-16 todo and CONTEXT.md both name explicitly (not an LLM-invented package) | OK, but **new to this repo** | Approved for use, but per the package-legitimacy protocol's spirit for a newly-introduced third-party tool, the planner should add a `checkpoint:human-verify` before the first live CI run that could hard-fail the pipeline (D-08 makes this a hard gate — a bad first invocation blocks all future pushes) |
| `appleboy/scp-action`, `appleboy/ssh-action` | github.com/appleboy | Already in production use since Phase 5 — not new to this phase, only their pin mechanism changes (tag → digest) | OK | Approved — no new trust surface, only a stricter pin on an already-trusted dependency |

**Packages removed due to [SLOP] verdict:** none.
**Packages flagged as suspicious [SUS]:** none — all three tools are established, named explicitly
in human-authored todos/CONTEXT.md (not discovered via this session's own websearch as a novel
suggestion), and two of the three are already running in this repo's CI today.

## Architecture Patterns

### Pattern 1: Third-party Action digest-pinning (HARDEN-03, D-05/D-06)

**What:** Replace `uses: appleboy/scp-action@v1.0.0` and `uses: appleboy/ssh-action@v1.2.5` with
digest-pinned references, in the exact `@<sha>  # v<version>` format `secret-scan.yml`'s gitleaks
reference already uses. Leave every first-party action (`actions/checkout@v5`,
`actions/setup-java@v5`, `docker/setup-buildx-action@v3`, `docker/build-push-action@v6`,
`actions/cache@v4`, `actions/upload-artifact@v4`) as tag-only, with a documented risk-acceptance
comment (per D-05 — this OVERRIDES the phase description's literal success-criterion #3 text,
which predates the CONTEXT.md discussion and is stale).

**When to use:** Any `uses:` line in `deploy.yml`/`security-scan.yml` referencing an
`appleboy/*` action.

**Real commit SHAs fetched this session** [VERIFIED: api.github.com/repos/appleboy/scp-action/git/refs/tags/v1.0.0 and api.github.com/repos/appleboy/ssh-action/git/refs/tags/v1.2.5, fetched 2026-08-19]:

```yaml
# appleboy/scp-action@v1.0.0 -> commit ff85246acaad7bdce478db94a363cd2bf7c90345
uses: appleboy/scp-action@ff85246acaad7bdce478db94a363cd2bf7c90345  # v1.0.0

# appleboy/ssh-action@v1.2.5 -> commit 0ff4204d59e8e51228ff73bce53f80d53301dee2
uses: appleboy/ssh-action@0ff4204d59e8e51228ff73bce53f80d53301dee2  # v1.2.5
```

These SHAs appear in three call sites each in `deploy.yml` (`deploy-to-netcup`,
`register-schemas-production`, `deploy-to-nonprod` for `ssh-action`; `deploy-to-netcup` and
`deploy-to-nonprod` for `scp-action`) — all six sites need the same substitution.

Re-verify at execution time with:
```bash
gh api repos/appleboy/scp-action/git/refs/tags/v1.0.0 --jq .object.sha
gh api repos/appleboy/ssh-action/git/refs/tags/v1.2.5 --jq .object.sha
```

**First-party risk-acceptance comment (Claude's discretion on placement — inline vs block):**
```yaml
# Risk-acceptance (Phase 10, HARDEN-03, D-05): actions/checkout, actions/setup-java,
# docker/setup-buildx-action, docker/build-push-action, actions/cache and actions/upload-artifact
# remain trusted by tag, not digest. Digest-pinning was scoped deliberately to only the two
# appleboy/* actions below, which run with real SSH keys against production/staging VMs -- a
# materially higher publisher-compromise risk tier than these first-party GitHub/Docker actions.
# A digest pin needs a two-step lookup-then-edit on every future version bump instead of a
# one-line tag edit; that maintenance cost is deliberately not paid here.
```

### Pattern 2: TruffleHog CI-only verified-credential scan (HARDEN-02, D-08/D-09)

**What:** A second job/step in `secret-scan.yml`, Docker-direct (mirroring gitleaks' own
established pattern in the same file — avoids introducing a second, differently-shaped invocation
style, and sidesteps the marketplace action's unpinned `@main` default).

**When to use:** Every push to `master` and every PR — same trigger as gitleaks (D-09) — but
scoped to the push/PR's own commit range, NOT full history like gitleaks. This is a deliberate
divergence from gitleaks' full-history scan, and the reason is load-bearing: TruffleHog's
verification step makes a live network call per candidate finding to the credential's own provider
API [CITED: trufflesecurity.com/blog/running-trufflehog-in-a-github-action]. Re-scanning full
history on every push would re-verify (and re-network-call) the same historical findings
repeatedly, unlike gitleaks' regex-only full-history scan which has no per-finding network cost.
Exit code 183 signals verified credentials found [CITED: docs.trufflesecurity.com/scanning-in-ci]
— a plain non-zero exit already hard-fails the step with no extra logic needed for D-08.

**Example (Docker-direct, mirroring gitleaks' pinned-image pattern; exact digest must be looked up
fresh at execution time — see Code Examples):**
```yaml
- name: Scan for verified-live credentials (push range)
  run: |
    docker run --rm -v "${{ github.workspace }}:/repo" -w /repo \
      ghcr.io/trufflesecurity/trufflehog:<pinned-tag>@sha256:<digest> \
      git file:///repo --since-commit ${{ github.event.before }} \
      --branch ${{ github.ref_name }} --results=verified --fail
```
For `pull_request` events, use `github.event.pull_request.base.sha` / `.head.sha` instead of
`before`/`after` — the base/head pair the TruffleHog Action's own `action.yml` uses internally
[CITED: github.com/trufflesecurity/trufflehog blob/main/action.yml].

### Pattern 3: Outside-main-tree worktree detection + stdin fallback (HARDEN-05, D-11/D-12)

**What:** Extend `.githooks/pre-commit`'s existing `MOUNT_ROOT`/`GIT_TOPLEVEL` computation (already
correct for the nested `.claude/worktrees/<name>` case) with a branch that detects when
`GIT_TOPLEVEL` is NOT a subtree of `MOUNT_ROOT`, and falls back to gitleaks' own documented
`stdin` scanning mode.

**Verified against gitleaks' own documentation this session:** `git diff --cached | gitleaks
stdin` is gitleaks' own first-class, documented pre-commit-hook pattern [CITED:
github.com/gitleaks/gitleaks README/docs — "three scanning modes: git, dir, and stdin... A typical
pre-commit hook runs `git diff --cached | gitleaks stdin --no-banner`"] — this is not a novel
workaround, it's the tool's own recommended fallback shape.

**Concrete detection + fallback** (extends, does not replace, the existing bind-mount branch —
`GIT_TOPLEVEL`, `MOUNT_ROOT`, and `GITLEAKS_IMAGE` are already computed earlier in the file):
```sh
case "$GIT_TOPLEVEL" in
  "$MOUNT_ROOT"|"$MOUNT_ROOT"/*)
    # Existing, already-correct path: nested worktree or plain checkout. Unchanged.
    MSYS_NO_PATHCONV=1 docker run --rm \
      -v "${MOUNT_ROOT}:/repo" \
      -e GIT_DIR="${CONTAINER_GITDIR}" \
      -e GIT_WORK_TREE="${CONTAINER_WORKTREE}" \
      "${GITLEAKS_IMAGE}" \
      git --staged --redact --no-banner --verbose --exit-code 2 "${CONTAINER_WORKTREE}"
    GITLEAKS_STATUS=$?
    ;;
  *)
    # Outside-main-tree worktree (HARDEN-05, D-12): MOUNT_ROOT cannot bind-mount both the
    # worktree's private git-dir and its work tree in one mountable subtree. Fall back to
    # gitleaks' own documented stdin mode -- loses path-based allowlist context for this case
    # only (accepted trade-off, D-12), since .gitleaks.toml's rule-scoped allowlists (regexTarget
    # = "match") still apply; only a hypothetical per-PATH allowlist would be affected, and this
    # repo has none.
    MSYS_NO_PATHCONV=1 git diff --cached | docker run --rm -i \
      "${GITLEAKS_IMAGE}" \
      stdin --redact --no-banner --verbose --exit-code 2
    GITLEAKS_STATUS=$?
    ;;
esac
```
The existing three-way `GITLEAKS_STATUS` branch (0=clean, 2=findings, other=tool failure) below
this needs no change — both paths populate the same variable.

**Anti-pattern to avoid:** Do not silently swallow the outside-main-tree case into "scan skipped" —
that reproduces exactly the false-clean failure mode this todo exists to fix (the original bug is
`GIT_WORK_TREE` pointing at a path that doesn't exist inside the container, which gitleaks reports
as "0 commits scanned" rather than erroring).

### Pattern 4: README restructure (HARDEN-08, D-01..D-04)

**What:** Restructure `README.md` (currently 131 lines) to lead with production reality + CI/CD
(D-03), embed exactly one diagram (D-02), keep short inline "why" callouts in the Stack table
(D-04), and link out to `docs/ARCHITECTURE.md`/`docs/INFRA_ARCHITECTURE.md`/`docs/diagrams/` for
depth (D-01).

**Diagram candidate for D-02** (Claude's discretion, but strongly indicated by D-03's own
ordering): `docs/diagrams/infra-physical-deployment.mmd`/`.png` — the only diagram in
`docs/diagrams/` whose subject is the physical/deployment view (Netcup/Neon/Redpanda/Caddy), which
is exactly what D-03 says the README should lead with. The alternative candidate,
`infra-delivery-scenario.mmd`, covers the CI pipeline's own flow and is also plausible — the
planner should read both `.mmd` sources before committing to one, since neither has been opened
this session.

**Section-cover checklist** (all touched per CONTEXT.md's "Cover list", weighted per D-03):
CI/CD pipeline and deploy strategy (heaviest weight) → pre-commit hooks and CI quality gates
(Spotless, Error Prone, JaCoCo, OWASP dependency-check, secret scanning — now including
TruffleHog) → testing architecture (tiers, Testcontainers) → `docs/diagrams/` → local dev → stack
table with inline rationale (lightest weight, since D-04 wants this "short," not expanded).

**Existing rationale to mine, not re-derive** (per the source todo): `.planning/RETROSPECTIVE.md`
and `STATE.md`'s decision log already contain the "why Testcontainers over H2," "why Flyway,"
"why gitleaks over TruffleHog as primary scanner," "why Netcup over Oracle/AWS/GCP/Hetzner"
reasoning — these were not re-read in full this session (out of this research's scope; the
planner/executor should pull short quotes from there rather than re-deriving justifications).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Verified-live credential detection | A custom script calling AWS/GitHub/Stripe APIs to check if a matched secret is live | TruffleHog's built-in `--results=verified` provider-verification | TruffleHog already implements dozens of provider-specific verifiers; re-deriving even one correctly (rate limits, auth flows, provider API shape) is far more surface area than this phase's scope |
| Gradle wrapper tamper detection | A hand-rolled checksum comparison script in the workflow | `gradle/actions/wrapper-validation` + `distributionSha256Sum` in `gradle-wrapper.properties` | Both are Gradle's own first-party, purpose-built mechanisms already documented and versioned; a hand-rolled check would need to track Gradle's own wrapper JAR format across upgrades |
| Dependency artifact integrity | A custom script diffing published checksums against `build.gradle`'s declared versions | Gradle's own `--write-verification-metadata sha256` + `gradle/verification-metadata.xml` | Native Gradle feature, integrates with the existing resolution pipeline instead of running as an out-of-band check that could drift from what Gradle actually resolved |
| GitHub Action digest lookup | Manually pasting a SHA copied from a GitHub UI page | `gh api repos/<org>/<repo>/git/refs/tags/<tag> --jq .object.sha` | Scriptable, reproducible, and avoids a copy-paste transcription error in a security-relevant pin |

**Key insight:** every tool this phase needs already exists and is purpose-built for exactly this
problem (TruffleHog for verification, `gradle/actions` for wrapper/dependency integrity,
Dependabot for update automation) — the work in this phase is wiring, not building.

## Common Pitfalls

### Pitfall 1: Treating the phase description's success-criterion #3 text as authoritative over D-05
**What goes wrong:** The phase description literally says "Every `uses:` reference in `deploy.yml`
and `security-scan.yml` resolves to an immutable commit digest" — a plan that takes this literally
would digest-pin `actions/checkout`, `docker/build-push-action`, etc., contradicting D-05.
**Why it happens:** The success-criterion text was written at roadmap time, before the
`/gsd-discuss-phase` session narrowed scope.
**How to avoid:** CONTEXT.md's Locked Decisions are authoritative over the phase description
per this agent's own consumption rules — D-05's third-party-only scope wins.
**Warning signs:** A plan task that says "pin `actions/checkout@v5`" or similar for a first-party
action is a signal this pitfall has been hit.

### Pitfall 2: Assuming Dependabot's `github-actions` entry "just works" with digest pins
**What goes wrong:** Believing HARDEN-01's addition alone satisfies D-07's composition
requirement.
**Why it happens:** GitHub's Oct 2022 changelog confirms Dependabot DOES update both the SHA and
the version comment for SHA-pinned actions [CITED: github.blog/changelog/2022-10-31] — but multiple
still-open `dependabot-core` issues document real edge cases where this desyncs: updating to an
untagged branch HEAD leaves a stale comment (#14716), or updates land inconsistently across
multiple files referencing the same action (#7376), or an already-incorrect comment isn't corrected
(#7912) [CITED: github.com/dependabot/dependabot-core/issues/14716, /7912, /7376].
**How to avoid:** Add a verification checkpoint after the first real Dependabot PR against a
digest-pinned `appleboy/*` action lands — confirm BOTH the SHA and the comment updated correctly,
not just that a PR opened.
**Warning signs:** A Dependabot PR that only touches the SHA (or only the comment) on one of the
six `appleboy/*` call sites in `deploy.yml`.

### Pitfall 3: Adopting the TruffleHog marketplace action's default `@main` ref
**What goes wrong:** The most commonly copy-pasted example workflow uses
`uses: trufflesecurity/trufflehog@main` [CITED: trufflesecurity.com/blog/running-trufflehog-in-a-github-action]
— an unpinned, mutable branch reference, directly contradicting this same phase's HARDEN-03 intent.
**Why it happens:** It's the tool's own most-copied documentation example.
**How to avoid:** Either pin the marketplace action to a release tag+digest, or (recommended, for
consistency with this repo's established gitleaks pattern in the same file) invoke the
`ghcr.io/trufflesecurity/trufflehog` Docker image directly, pinned tag+digest, matching Pattern 2.
**Warning signs:** Any `uses:` or `docker run` line in this phase's diff with `@main`, `:latest`,
or no digest at all.

### Pitfall 4: Assuming HARDEN-07's `application-test.properties` flip breaks the E2E suite
**What goes wrong:** The 2026-08-10 todo that originally deferred this change explicitly
recommended keeping `application-test.properties` at `secure=false`, reasoning that the
real-socket test tier (REST Assured, `RANDOM_PORT`, plain HTTP) would have its session-cookie
relay broken by RFC 6265's Secure-flag no-replay-over-HTTP rule, the same way a real browser would
refuse to send a `Secure` cookie back over plain HTTP.
**Why this concern does NOT apply here (verified by reading the actual test code this session):**
Both `AbstractAppE2ETest.signin()` (REST Assured) and `AbstractAppMockMvcTest.signinCookie()`
(MockMvc) extract the `Set-Cookie` VALUE from the signin response manually
(`.extract().cookie(COOKIE_NAME)` / `result.getResponse().getCookie(cookieName)`) and replay it on
every subsequent request as an explicit `given().cookie(name, value)` call — this bypasses any
automatic, RFC-6265-aware cookie jar entirely. Neither REST Assured's manual `.cookie(name, value)`
call nor MockMvc (which never uses real network transport at all) consults the `Secure` attribute
before replaying a cookie value supplied this way.
[VERIFIED: src/test/java/com/vrudenko/kanban_board/support/fixtures/AbstractAppE2ETest.java:40-56 — `.extract().cookie(COOKIE_NAME)` then returned as a `Pair<String,String>` for manual replay; src/test/java/com/vrudenko/kanban_board/e2e/board/BoardCreationE2ETest.java:72-330 — every subsequent call uses `given().cookie(cookie.getFirst(), cookie.getSecond())`; src/test/java/com/vrudenko/kanban_board/support/fixtures/AbstractAppMockMvcTest.java:57-66 — `signinCookie()` returns a `jakarta.servlet.http.Cookie` for manual replay, MockMvc never touches real HTTP transport]
**How to avoid:** Flip both properties in the same task; still run the full suite (`./gradlew
test`) to confirm empirically, but do not treat this as a high-risk change requiring a design
workaround — it isn't one for this codebase's specific test-harness shape.
**Residual work:** The success criterion's "authenticated flows still pass end-to-end against a
TLS-served environment" is about the DEPLOYED environment (nonprod, reachable over real HTTPS per
Phase 8), not the test suite — that needs a manual/curl-driven signin-then-authenticated-request
round trip against the live nonprod HTTPS endpoint post-flip, separate from `./gradlew test`.

### Pitfall 5: Full-history TruffleHog scan on every push
**What goes wrong:** Copying gitleaks' full-history (`git --redact ... /repo`) invocation shape
verbatim for TruffleHog would re-verify every historical finding's liveness on every single push,
multiplying network calls to credential-provider APIs for no new information.
**How to avoid:** Scope TruffleHog to the push/PR's own commit range only (see Pattern 2).
**Warning signs:** A `secret-scan.yml` diff where the new TruffleHog step's `docker run` invocation
has no `--since-commit`/`base`/`head` equivalent — i.e., a bare `git file:///repo` with no range.

### Pitfall 6: Wrapper-validation step placed after a `./gradlew` invocation
**What goes wrong:** `gradle/actions/wrapper-validation` exists to catch a tampered
`gradlew`/`gradle-wrapper.jar` BEFORE it executes — placing it after `./gradlew test` or
`./gradlew spotlessCheck` already ran defeats the entire purpose.
**How to avoid:** Insert it as the FIRST step in both `deploy.yml`'s `run-tests` job and
`security-scan.yml`, before "Checkout code" is even relevant to Gradle invocation ordering (it can
sit right after checkout, but strictly before any `./gradlew` line).
**Warning signs:** A diff that appends the wrapper-validation step at the bottom of an existing job
rather than near the top.

### Pitfall 7: Assuming the NVD_API_KEY bug is fixable from a plan-time YAML edit alone
**What goes wrong:** The folded todo's own root-cause analysis is explicitly "not yet
investigated" — candidates include GitHub secret storage/propagation defect, invisible
whitespace/encoding in the stored value, or an org-level secret-policy interaction. None of these
are distinguishable by reading the workflow file statically.
**How to avoid:** Sequence this as its own task: (1) add a temporary `sha256sum`-of-the-secret
diagnostic step (never echo the raw value), (2) dispatch the workflow and read the hash, (3) based
on the result, either re-set the secret with a freshly copied value or investigate further, (4)
remove the diagnostic step once fixed, (5) re-run and confirm a full green `dependencyCheckAnalyze`.
**Additional diagnostic to check first (cheap, rules out a whole bug class):** a related — though
ultimately unrelated in that specific case — GitHub Community report found secrets resolving empty
due to a mis-indented `env:` block scoping the secret to the wrong step
[CITED: github.com/orgs/community/discussions/171773]. This repo's "Verify NVD_API_KEY is
configured" step interpolates `${{ secrets.NVD_API_KEY }}` directly inline in `run:`, not via an
`env:` indirection, so this specific failure mode is already ruled out for THAT step — but the
downstream "Run dependencyCheckAnalyze" step DOES use an `env:` block
(`env: NVD_API_KEY: ${{ secrets.NVD_API_KEY }}` at `security-scan.yml:89-90`); worth a quick visual
indentation check there too before assuming the deeper GitHub-side cause.
**Warning signs:** A plan task that says "fix the NVD_API_KEY secret" with no diagnostic step first.

### Pitfall 8: Scope creep — treating Claude's-discretion items as requiring new user decisions
**What goes wrong:** Four items are explicitly left to Claude's discretion in CONTEXT.md (README
section wording, which diagram to embed, comment placement style, and folded-todo step placement)
— re-litigating these with the user during planning wastes a round-trip CONTEXT.md already closed.
**How to avoid:** Make a reasoned choice (this research offers concrete recommendations for each,
see Pattern 4 and Pattern 1) and document it in the plan; do not add a `checkpoint:human-verify`
for these specific four decisions.

## Code Examples

### HARDEN-04: Gradle cache in `run-tests`
```yaml
# deploy.yml, run-tests job -- one-line addition, matching security-scan.yml's existing usage
- name: Set up Java
  uses: actions/setup-java@v5
  with:
    java-version: '21'
    distribution: 'temurin'
    cache: 'gradle'   # <-- new; setup-java's built-in cache keys on build.gradle/settings.gradle/
                       #     gradle-wrapper.properties by default -- verify this repo's cache-key
                       #     inputs actually cover every dependency-graph-affecting file before
                       #     trusting it (per the folded todo's own verification step)
```

### Folded todo: Gradle wrapper integrity
```properties
# gradle/wrapper/gradle-wrapper.properties -- real SHA-256 fetched this session
# [VERIFIED: downloads.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256, fetched 2026-08-19]
distributionSha256Sum=f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6
```
```yaml
# First step in BOTH deploy.yml's run-tests job and security-scan.yml, before any ./gradlew line
- name: Validate Gradle wrapper
  uses: gradle/actions/wrapper-validation@v6   # [CITED: github.com/gradle/wrapper-validation-action releases]
```

### Folded todo: Gradle dependency verification metadata
```bash
# Generates gradle/verification-metadata.xml from the current, already-reviewed dependency set
./gradlew --write-verification-metadata sha256
```
```yaml
# CI staleness check (folded todo's own suggestion: fast/deterministic -- place in run-tests,
# not the network-bound security-scan.yml)
- name: Verify dependency-verification metadata is current
  run: ./gradlew --write-verification-metadata sha256 --dry-run
  # A non-empty diff/failure here means build.gradle's dependencies changed without regenerating
  # gradle/verification-metadata.xml -- fails loudly rather than silently losing coverage.
```

### HARDEN-01: Dependabot `github-actions` ecosystem entry
```yaml
# .github/dependabot.yml -- new second `updates` entry, alongside the existing gradle entry
  - package-ecosystem: "github-actions"
    directory: "/"
    schedule:
      interval: "weekly"
    open-pull-requests-limit: 5   # matches the existing gradle entry's noise-bound convention
```

### Digest lookup commands (re-run at execution time, do not trust a stale value)
```bash
# GitHub Action commit SHA (for appleboy/* -- HARDEN-03)
gh api repos/appleboy/scp-action/git/refs/tags/v1.0.0 --jq .object.sha
gh api repos/appleboy/ssh-action/git/refs/tags/v1.2.5 --jq .object.sha

# Docker image digest (for TruffleHog -- HARDEN-02), requires Docker or skopeo/crane
docker buildx imagetools inspect ghcr.io/trufflesecurity/trufflehog:<tag> --raw \
  | sha256sum   # or read the digest directly from `docker manifest inspect` output
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| Trusting GitHub Action tags | Digest-pinning at least secrets-touching third-party actions | Accelerated by the March 2025 `tj-actions/changed-files` supply-chain compromise, which leaked secrets from thousands of repositories via a retargeted tag [CITED: multiple sources via StepSecurity/developerwithacat.com writeups] | Directly validates D-05's risk-tiering rationale — this is exactly the attack class digest-pinning `appleboy/*` defends against |
| Dependabot Security Alerts blind spot | GitHub added Aug 2025 org-level policy support for enforcing/checking SHA-pinned actions | 2025-08-15 [CITED: github.blog/changelog/2025-08-15-github-actions-policy-now-supports-blocking-and-sha-pinning-actions] | Not directly actionable this phase (org/enterprise-level setting, this is a single-repo workflow change), but worth knowing the platform is moving in the same direction as D-05 |
| gitleaks-only secret scanning | Layered scanning: regex/entropy (gitleaks) + live-verification (TruffleHog) | This phase (HARDEN-02) | Matches the industry-standard pattern of pairing a broad, fast regex scanner with a narrower, slower, verified-only second pass |

**Deprecated/outdated:** Dependabot's third-party `dependabot-sha-comment-action` [CITED:
github.com/marketplace/actions/dependabot-sha-comment-action] is now redundant — GitHub's own
Dependabot has natively supported updating version comments next to SHA pins since October 2022;
no third-party action is needed for HARDEN-01/HARDEN-03's composition to theoretically work (though
Pitfall 2's real-world edge cases mean "theoretically works" still needs a live-verification
checkpoint).

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `infra-physical-deployment.mmd` is the best D-02 diagram candidate | Architecture Patterns, Pattern 4 | Low — Claude's discretion per CONTEXT.md; if wrong, a trivial swap of which `.mmd` file gets embedded |
| A2 | TruffleHog should be Docker-direct rather than the marketplace action | Architecture Patterns, Pattern 2; Standard Stack | Low-Medium — either approach satisfies HARDEN-02/D-08/D-09; Docker-direct is a consistency recommendation, not a hard requirement. If the marketplace action is preferred, it MUST still be pinned (Pitfall 3) |
| A3 | Gradle's built-in `--write-verification-metadata sha256 --dry-run` correctly detects staleness for CI-gating purposes | Code Examples | Medium — this exact flag combination was not empirically run against this specific repo's `build.gradle` this session; verify behavior (does `--dry-run` actually exit non-zero on a diff, or only print one?) during plan execution before trusting it as a hard CI gate |
| A4 | The NVD_API_KEY root cause is most likely secret-value corruption (whitespace/encoding), not a GitHub-platform-level defect | Common Pitfalls, Pitfall 7 | Low — this is presented as the most likely candidate per the todo's own reasoning, not asserted as confirmed; the diagnostic-first task sequencing already accounts for being wrong here |

**If this table is empty:** N/A — see entries above. All other claims in this research are either
[VERIFIED] (this session's direct file reads / API calls) or [CITED] (a specific, named official
source: GitHub docs/changelog, Gradle docs, TruffleHog/gitleaks own documentation).

## Open Questions

1. **What is the actual root cause of the NVD_API_KEY resolution failure?**
   - What we know: The secret exists at repository scope (`gh secret list` confirms, unchanged
     `updatedAt` across the failure window), the job declares no `environment:` key so
     environment-secret-visibility rules don't apply, and the failing step interpolates the secret
     directly inline (ruling out one common mis-indented-`env:`-block failure mode for that
     specific step).
   - What's unclear: Whether the stored value itself is corrupted (whitespace/encoding), or
     something else is blocking resolution.
   - Recommendation: This is NOT resolvable by further static research — it requires a live
     `workflow_dispatch` diagnostic run (hash-probe step) as its own gated task, per Pitfall 7.

2. **Does `./gradlew --write-verification-metadata sha256 --dry-run` actually fail (non-zero exit)
   on a stale-metadata diff, or does it just print without failing?**
   - What we know: The command generates/would-generate the metadata file; Gradle's dependency
     verification feature itself DOES fail builds on a checksum mismatch once the feature is
     active.
   - What's unclear: The exact CI-gating semantics of the `--dry-run` flag specifically for
     staleness detection (vs. the separately-documented runtime checksum-verification failure mode)
     were not empirically tested against this repo this session.
   - Recommendation: Verify this flag's actual exit-code behavior against this repo's real
     `build.gradle` during plan execution before relying on it as a hard CI gate; a `diff` against
     a freshly-regenerated file may be a more robust staleness check if `--dry-run` doesn't exit
     non-zero on its own.

3. **Which of `infra-physical-deployment.mmd` or `infra-delivery-scenario.mmd` best serves D-02's
   "one top-level architecture/system diagram"?**
   - What we know: Both exist in `docs/diagrams/`; D-03 says README should lead with production
     reality + the CI/CD pipeline.
   - What's unclear: Neither `.mmd` source was opened this session to compare their actual content
     and complexity/readability at README scale.
   - Recommendation: Planner/executor should open both before deciding — this is explicitly
     Claude's discretion per CONTEXT.md, not a user decision to re-litigate.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker | HARDEN-02 (TruffleHog), HARDEN-05 (gitleaks stdin fallback), local `.githooks/pre-commit` | Not probed this session — already a hard requirement for this repo's existing pre-commit hook and CI workflows | — | None; this repo already mandates Docker for its existing gitleaks gate, so this phase adds no new hard dependency |
| `gh` CLI | Digest lookups (Pattern 1), NVD_API_KEY diagnostic (Pitfall 7) | Not probed this session | — | `curl` against the GitHub REST API directly (used this session in place of `gh`) works identically for the read-only lookups this phase needs |
| GitHub Actions platform features (Dependabot, Environments, digest pinning) | HARDEN-01, HARDEN-03 | Platform-level, always available on github.com | — | None needed — these are GitHub-hosted features, not locally-installed tooling |

**Missing dependencies with no fallback:** none identified.
**Missing dependencies with fallback:** `gh` CLI — `curl`+GitHub REST API is an equally valid
fallback, already used to produce this research's own verified SHAs.

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-------------------|
| V2 Authentication | No (this phase doesn't touch signin/signup logic) | — |
| V3 Session Management | **Yes** | HARDEN-07's `Secure` cookie flag is a direct ASVS V3 (session cookie attribute) control; `HttpOnly`/`SameSite=strict` are already set (verified: both properties files already carry `http-only=true`, `same-site=strict`) — this phase closes the last of the three standard cookie-hardening attributes |
| V4 Access Control | No | — |
| V5 Input Validation | No new input surface this phase | — |
| V6 Cryptography | No new cryptography this phase | — |
| V14 Configuration (CI/CD & supply chain, ASVS 5.0's expanded coverage) | **Yes** | HARDEN-01/02/03/05/06 plus both folded Gradle todos are all textbook V14-class controls: pinned/verified build tooling, verified CI dependencies, secret-scanning at multiple gates |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|----------------------|
| Session cookie sent over plain HTTP after TLS downgrade or stray `http://` link | Information Disclosure | `Secure` cookie attribute (HARDEN-07) — cookie is never sent over a non-TLS connection once set |
| CI Action supply-chain compromise (tag retargeted to malicious commit by publisher or a compromised publisher account) | Tampering / Elevation of Privilege | Digest-pinning (HARDEN-03) for the two actions with real production SSH-key access; real-world precedent: `tj-actions/changed-files`, March 2025 [CITED, see State of the Art] |
| A regex-only secret scanner missing a live, exploitable credential shape it wasn't tuned for, or a rotated-dead key wasting response effort | Information Disclosure / Repudiation (false-negative or noisy false-positive) | Layered scanning: gitleaks (regex/entropy) + TruffleHog (live-verification), both hard-gated |
| Tampered Gradle wrapper JAR executing arbitrary code on a CI runner or contributor machine before Gradle itself runs | Tampering / Elevation of Privilege | `gradle/actions/wrapper-validation` + `distributionSha256Sum` pin (folded todo) |
| A compromised artifact republished under the same Maven coordinates+version | Tampering | Gradle dependency-verification metadata (folded todo) — distinct trust boundary from Action integrity |

## Project Constraints (from CLAUDE.md)

- `./gradlew spotlessCheck` and `./gradlew test` must pass (matches existing CI) — every HARDEN
  task that touches Java/Gradle files (none directly do in this phase except the folded
  verification-metadata todo, which touches `build.gradle`) must be verified against this gate.
- `.githooks/pre-commit` runs a pinned `gitleaks` scan of the staged diff first, ahead of
  formatting/tests, and refuses the commit on a detected credential — a genuine false positive
  needs a narrow, evidence-cited `.gitleaks.toml` entry (never a blanket path exemption). This
  phase's own commits (workflow YAML with real-looking SHAs, `docker run` commands referencing
  `sha256:...` digests) are exactly the shape that could trip a false positive in gitleaks' own
  scanner — if that happens, follow the existing `.gitleaks.toml` evidence-cited-entry convention,
  do not weaken the hook.
- PLAN.md creation must document 2 alternate technical approaches, a 3-column trade-off matrix, and
  non-obvious performance/memory/security trade-offs, per this repo's GSD Execution Directives —
  this research's "Alternatives Considered" and "State of the Art" tables are intended as direct
  inputs to that requirement, not a substitute for it.
- GSD workflow enforcement: all file changes in this phase must go through `/gsd-execute-phase`
  after planning — no direct out-of-band edits.

## Sources

### Primary (HIGH confidence — verified this session via direct file read or live API call)
- `.github/workflows/deploy.yml`, `.github/workflows/security-scan.yml`,
  `.github/workflows/secret-scan.yml`, `.github/dependabot.yml`, `.githooks/pre-commit`,
  `.gitleaks.toml`, `src/main/resources/application.properties`,
  `src/main/resources/application-test.properties`, `README.md`,
  `gradle/wrapper/gradle-wrapper.properties` — all read directly this session
- `src/test/java/com/vrudenko/kanban_board/support/fixtures/AbstractAppE2ETest.java`,
  `AbstractAppMockMvcTest.java`, `src/test/java/com/vrudenko/kanban_board/e2e/board/BoardCreationE2ETest.java`,
  `src/test/java/com/vrudenko/kanban_board/security/AuthenticationTest.java` — read directly this
  session to prove Pitfall 4's claim
- `api.github.com/repos/appleboy/scp-action/git/refs/tags/v1.0.0`,
  `api.github.com/repos/appleboy/ssh-action/git/refs/tags/v1.2.5` — fetched live, 2026-08-19
- `downloads.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256` — fetched live, 2026-08-19

### Secondary (MEDIUM confidence — WebSearch verified against an official/authoritative source)
- github.blog/changelog/2022-10-31-dependabot-now-updates-comments-in-github-actions-workflows-referencing-action-versions
- github.blog/changelog/2025-08-15-github-actions-policy-now-supports-blocking-and-sha-pinning-actions
- docs.trufflesecurity.com/scanning-in-ci
- github.com/trufflesecurity/trufflehog (README, action.yml)
- github.com/gitleaks/gitleaks (README — stdin mode)
- github.com/gradle/wrapper-validation-action (releases)
- docs.gradle.org/current/userguide/dependency_verification.html

### Tertiary (LOW confidence — WebSearch only, flagged for validation)
- github.com/dependabot/dependabot-core issues #14716, #7912, #7376 (real bugs, but scope/frequency
  not independently confirmed against this specific repo's future behavior — hence Pitfall 2's
  "add a verification checkpoint" recommendation rather than treating composition as guaranteed)
- github.com/orgs/community/discussions/171773 (anecdotal community report, self-resolved as user
  error — used only as a cheap-to-check diagnostic angle for Pitfall 7, not as a root-cause claim)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new production dependencies; CI tooling versions verified live
- Architecture patterns: HIGH for Patterns 1/3 (verified against real repo code + real fetched
  values), MEDIUM for Pattern 2 (TruffleHog invocation shape is CITED, not yet run against this
  repo), HIGH for Pattern 4 (direct CONTEXT.md decisions, only the diagram choice is open)
- Pitfalls: HIGH for Pitfalls 1/2/3/4/5/6/8 (each backed by a specific verified/cited source),
  MEDIUM for Pitfall 7 (root cause genuinely unknown, correctly flagged as an Open Question rather
  than asserted)

**Research date:** 2026-08-19
**Valid until:** 30 days for the in-repo findings (stable — nothing here depends on fast-moving
external APIs beyond digest values, which are explicitly flagged as "re-verify at execution time"
throughout); 7 days for any hardcoded external tool version/SHA if plan execution slips past
early September 2026.
