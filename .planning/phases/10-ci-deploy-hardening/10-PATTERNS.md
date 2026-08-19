# Phase 10: CI & Deploy Hardening - Pattern Map

**Mapped:** 2026-08-19
**Files analyzed:** 8 (all modified, no new files — this phase is config/workflow/hook surgery)
**Analogs found:** 8 / 8 (every target file's closest analog is another section of an already-established sibling pattern in the same repo)

## File Classification

| Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `.github/workflows/secret-scan.yml` (add TruffleHog job/step — HARDEN-02) | CI workflow (event-driven) | event-driven (push/PR trigger → scan → hard-gate) | same file's existing `secret-scan` job (gitleaks step) | exact — same file, same trigger, same hard-gate posture |
| `.github/workflows/deploy.yml` (digest-pin `appleboy/*` — HARDEN-03) | CI workflow / deploy config | event-driven | `secret-scan.yml`'s pinned gitleaks image reference (line 77) | exact — same `@<sha>` + version-comment convention, cross-file |
| `.github/workflows/deploy.yml` (`run-tests` cache — HARDEN-04) | CI workflow | event-driven | `security-scan.yml`'s `Set up Java` step (lines 63-68, already has `cache: 'gradle'`) | exact — literally the same step shape, missing one line |
| `.github/workflows/security-scan.yml` (checkout/setup-java bump, stale comment fix — HARDEN-06) | CI workflow | event-driven | `deploy.yml`'s `run-tests` job's `Checkout code`/`Set up Java` steps (lines 34-45) | exact — same step names/shapes, different pinned versions |
| `.github/dependabot.yml` (add `github-actions` ecosystem — HARDEN-01) | Config (declarative) | batch (scheduled) | same file's existing `gradle` ecosystem entry (lines 18-26) | exact — same file, same `updates:` list shape |
| `.githooks/pre-commit` (outside-main-tree stdin fallback — HARDEN-05) | Utility / shell hook | event-driven (pre-commit trigger) | same file's existing bind-mount branch (lines 85-90) | exact — same file, extends existing `case`-style branching already implied by the `if/else` mount-root check (lines 56-61) |
| `src/main/resources/application.properties` + `application-test.properties` (Secure flag — HARDEN-07) | Config | request-response (session cookie attribute) | the same two files' adjacent `http-only`/`same-site` lines (already `true`/`strict`) | exact — same property block, same file, sibling line |
| `README.md` (restructure — HARDEN-08) | Documentation | N/A | current `README.md` (131 lines) + `docs/ARCHITECTURE.md`/`docs/diagrams/` for linked depth | role-match — no other README-shaped analog exists in-repo; structure follows CONTEXT.md D-01..D-04 directly |
| `.github/workflows/deploy.yml` / `security-scan.yml` (wrapper-validation, folded todo) | CI workflow | event-driven | `security-scan.yml`'s existing first-class preflight step pattern ("Verify NVD_API_KEY is configured", lines 46-55) | role-match — "fail loudly before the real work starts" pattern, not a literal analog |
| `build.gradle` (verification-metadata staleness check, folded todo) | Config / build tooling | batch | `security-scan.yml`'s `dependencyCheckAnalyze` step (network-bound, report-driven gate) | role-match — nearest existing "Gradle task as a CI gate" shape |

## Pattern Assignments

### `.github/workflows/secret-scan.yml` — add TruffleHog (HARDEN-02, D-08/D-09)

**Analog:** same file's existing `secret-scan` job (this file, lines 1-95, already read in full — no re-read needed)

**Trigger pattern to mirror** (lines 21-26):
```yaml
on:
  push:
    branches:
      - master
  pull_request:
  workflow_dispatch: {}
```
Reuse this identical `on:` block for the new job/step — D-09 requires the same trigger as gitleaks.

**Docker-direct pinned-image invocation pattern to mirror** (lines 71-81):
```yaml
- name: Scan full history for secrets
  run: |
    mkdir -p .gitleaks-reports
    docker run --rm \
      -v "${{ github.workspace }}:/repo" \
      -w /repo \
      ghcr.io/gitleaks/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f \
      git --redact --no-banner --verbose \
        --baseline-path /repo/.gitleaks-baseline.json \
        -f json -r /repo/.gitleaks-reports/full-history.json \
        /repo
```
TruffleHog's new step should follow this exact shape (pinned `ghcr.io/...@sha256:...` image, `docker run --rm -v "${{ github.workspace }}:/repo" -w /repo`), diverging only in scan range (diff-scoped `--since-commit`/base-head, not full history — see RESEARCH.md Pattern 2/Pitfall 5) and in exit-code handling (TruffleHog's own non-zero/183 already hard-fails, no `--exit-code` remap needed the way gitleaks needed one).

**Hard-gate posture (no continue-on-error) — precedent is the whole file's own header comment** (lines 8-18): explicitly documents *why* this job has no `continue-on-error`, contrasting with `security-scan.yml`'s schedule-driven report-only posture. Copy this same load-bearing-comment habit for the TruffleHog step: state explicitly why D-08 makes this a hard gate (verified-live findings only narrow what gitleaks already flags).

**Artifact upload pattern to mirror** (lines 88-94):
```yaml
- name: Upload redacted scan report
  if: always()
  uses: actions/upload-artifact@v4
  with:
    name: gitleaks-report
    path: .gitleaks-reports/full-history.json
    if-no-files-found: warn
```
Reuse `if: always()` + `actions/upload-artifact@v4` + `if-no-files-found: warn` for TruffleHog's own report upload.

---

### `.github/workflows/deploy.yml` — digest-pin `appleboy/*` (HARDEN-03, D-05/D-06)

**Analog:** `secret-scan.yml` line 77's gitleaks pin (already the repo's own precedent D-06 explicitly cites)

**Pin format to copy exactly:**
```yaml
ghcr.io/gitleaks/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f
```
becomes, for the six `appleboy/*` call sites in `deploy.yml` (verified line numbers: 242, 252, 317, 370, 392 — six total across `deploy-to-netcup`/`register-schemas-production`/`deploy-to-nonprod`):
```yaml
uses: appleboy/scp-action@ff85246acaad7bdce478db94a363cd2bf7c90345  # v1.0.0
uses: appleboy/ssh-action@0ff4204d59e8e51228ff73bce53f80d53301dee2  # v1.2.5
```
(SHAs from RESEARCH.md — re-verify live via `gh api repos/appleboy/scp-action/git/refs/tags/v1.0.0 --jq .object.sha` before committing, per that doc's own instruction.)

**Existing call-site shape to preserve, only the `uses:` line changes** (lines 242-257):
```yaml
      - name: Copy Compose manifest and Caddyfile to the VM
        uses: appleboy/scp-action@v1.0.0
        with:
          host: ${{ secrets.NETCUP_HOST }}
          username: ${{ secrets.NETCUP_DEPLOY_USER }}
          key: ${{ secrets.NETCUP_SSH_KEY }}
          fingerprint: ${{ secrets.NETCUP_HOST_FINGERPRINT }}
          source: "docker-compose.prod.yml,Caddyfile"
          target: "/opt/deploy/kanban-board-backend/"

      - name: Deploy via Docker Compose
        uses: appleboy/ssh-action@v1.2.5
        with:
          host: ${{ secrets.NETCUP_HOST }}
          ...
```

**Risk-acceptance comment for untouched first-party actions** — no in-repo analog exists (first time this repo documents a deliberate non-pin); use RESEARCH.md's drafted comment verbatim (Architecture Patterns, Pattern 1) placed near the top of `deploy.yml`, mirroring this file's existing habit of load-bearing inline rationale comments (see the ARM64/QEMU comment at line 73-74, the concurrency-group comment at lines 225-230).

---

### `.github/workflows/deploy.yml` `run-tests` — Gradle cache (HARDEN-04)

**Analog:** `security-scan.yml` lines 63-68 (already has the target line)

**Copy directly:**
```yaml
- name: Set up Java
  uses: actions/setup-java@v5
  with:
    java-version: '21'
    distribution: 'temurin'
    cache: 'gradle'
```
into `deploy.yml`'s `run-tests` job (currently lines 41-45, missing only `cache: 'gradle'`). Keep `actions/setup-java@v5` (deploy.yml's own current pin) — do not downgrade to `security-scan.yml`'s `@v4`; that downgrade is HARDEN-06's job on the *other* file, not this one.

---

### `.github/workflows/security-scan.yml` — version bump + stale comment (HARDEN-06)

**Analog:** `deploy.yml`'s own `run-tests` job (lines 34-45) — the up-to-date sibling this file should match

**Current stale state to replace** (lines 57-68):
```yaml
- name: Checkout code
  uses: actions/checkout@v3

# temurin, not deploy.yml's adopt: adopt is a deprecated distribution alias
# (pending todo 260802-rq5, Unit B already tracks fixing deploy.yml's own use of it).
# Diverging here rather than repeating the deprecated value in a brand-new file.
- name: Set up Java
  uses: actions/setup-java@v4
  with:
    java-version: '21'
    distribution: 'temurin'
    cache: 'gradle'
```
Target shape (matching `deploy.yml`'s current pins, `actions/checkout@v5`/`actions/setup-java@v5`): bump both `uses:` lines, and replace the now-stale "not deploy.yml's adopt" comment — `deploy.yml` no longer uses `adopt` (already fixed, per CONTEXT.md's canonical-refs note) — with an updated comment or remove it if no longer load-bearing.

---

### `.github/dependabot.yml` — add `github-actions` ecosystem (HARDEN-01)

**Analog:** same file's existing `gradle` entry (lines 18-26)

**Copy the shape directly:**
```yaml
  - package-ecosystem: "gradle"
    directory: "/"
    schedule:
      interval: "weekly"
    open-pull-requests-limit: 5
```
New entry (per RESEARCH.md Code Examples):
```yaml
  - package-ecosystem: "github-actions"
    directory: "/"
    schedule:
      interval: "weekly"
    open-pull-requests-limit: 5
```
Also update/remove the file's header comment (lines 13-16) explaining why `github-actions` was deliberately deferred — that rationale ("Phase 5 is actively rewriting deploy.yml") is now stale; the same comment-hygiene habit HARDEN-06 applies to `security-scan.yml` applies here too.

---

### `.githooks/pre-commit` — outside-main-tree stdin fallback (HARDEN-05, D-11/D-12)

**Analog:** same file's own existing mount-root computation and bind-mount invocation (lines 51-91)

**Existing variables to reuse, not recompute** (lines 51-54):
```sh
GIT_TOPLEVEL=$(git rev-parse --show-toplevel)
GIT_COMMON_DIR=$(git rev-parse --path-format=absolute --git-common-dir)
GIT_PRIVATE_DIR=$(git rev-parse --path-format=absolute --git-dir)
MOUNT_ROOT=$(dirname "$GIT_COMMON_DIR")
```

**Existing bind-mount invocation to keep as the "clean" branch** (lines 85-90):
```sh
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "${MOUNT_ROOT}:/repo" \
  -e GIT_DIR="${CONTAINER_GITDIR}" \
  -e GIT_WORK_TREE="${CONTAINER_WORKTREE}" \
  "${GITLEAKS_IMAGE}" \
  git --staged --redact --no-banner --verbose --exit-code 2 "${CONTAINER_WORKTREE}"
GITLEAKS_STATUS=$?
```

**Exact `case` fallback to add** (RESEARCH.md Pattern 3, verified against gitleaks' own documented `stdin` mode):
```sh
case "$GIT_TOPLEVEL" in
  "$MOUNT_ROOT"|"$MOUNT_ROOT"/*)
    # existing bind-mount branch above, unchanged
    ;;
  *)
    MSYS_NO_PATHCONV=1 git diff --cached | docker run --rm -i \
      "${GITLEAKS_IMAGE}" \
      stdin --redact --no-banner --verbose --exit-code 2
    GITLEAKS_STATUS=$?
    ;;
esac
```
The downstream three-way `GITLEAKS_STATUS` branch (lines 93-112, already read) needs **no change** — both paths populate the same variable, and this file's existing refusal-message habit (explicit, developer-facing, points at `.gitleaks.toml` for false positives) should be preserved verbatim.

---

### `application.properties` / `application-test.properties` — Secure cookie flag (HARDEN-07)

**Analog:** the same block's adjacent already-hardened lines, both files

**Current state, both files identical** (`application.properties:127-135`, `application-test.properties:44-52`):
```properties
server.servlet.session.timeout=1m
server.servlet.session.tracking-modes=cookie
server.servlet.session.cookie.http-only=true
server.servlet.session.cookie.secure=false
server.servlet.session.cookie.name=JSESSIONID
server.servlet.session.cookie.path=/
server.servlet.session.cookie.max-age=600
server.servlet.session.cookie.same-site=strict
```
Single-line change in both files: `server.servlet.session.cookie.secure=false` → `server.servlet.session.cookie.secure=true`. `http-only=true`/`same-site=strict` on the same lines are the direct precedent for what a hardened line in this exact block looks like — no new property, no new pattern, just flipping the one remaining boolean to match its siblings.

---

### `README.md` — restructure (HARDEN-08, D-01..D-04)

**No direct in-repo analog** (first and only README in the repo). Structural inputs instead of a code pattern:
- Current 131-line file is the literal starting point — D-01 says "middle ground," not a rewrite from scratch.
- `docs/ARCHITECTURE.md`, `docs/INFRA_ARCHITECTURE.md` are the link targets for depth (D-01) — do not duplicate their content inline.
- `docs/diagrams/infra-physical-deployment.mmd` and `docs/diagrams/infra-delivery-scenario.mmd` are the two open candidates for the one embedded Mermaid diagram (D-02) — read both before choosing (RESEARCH.md Open Question 3, still unresolved).
- Section-cover checklist and weighting are already fully specified in CONTEXT.md's "Cover list" and RESEARCH.md's "Section-cover checklist" — treat those as the outline, not this pattern-mapping pass.

---

## Shared Patterns

### Pinned-image / digest-pin format
**Source:** `.github/workflows/secret-scan.yml` line 77 and `.githooks/pre-commit` line 47 (byte-identical gitleaks reference, deliberately kept in sync)
**Apply to:** HARDEN-02 (TruffleHog image pin), HARDEN-03 (`appleboy/*` action pins)
```
<registry>/<image>:<tag>@sha256:<digest>   # for Docker images
uses: <org>/<action>@<commit-sha>  # v<tag>   # for GitHub Actions
```

### Load-bearing inline rationale comments
**Source:** every workflow file and the pre-commit hook already do this extensively (e.g. `secret-scan.yml` lines 8-18, `pre-commit` lines 33-46, `security-scan.yml` lines 1-15)
**Apply to:** every HARDEN item that changes CI/hook behavior — document *why*, not just *what*, matching this repo's established habit; this is also directly what D-04 asks README to do for stack choices.

### "Fail loudly before real work starts" preflight pattern
**Source:** `security-scan.yml` lines 46-55 (`Verify NVD_API_KEY is configured`)
**Apply to:** the folded NVD_API_KEY diagnostic step (temporary `sha256sum` probe) and the folded wrapper-validation step (must run first, before any `./gradlew` line, per RESEARCH.md Pitfall 6).

### Explicit `environment:` declaration on secret-touching jobs
**Source:** `deploy.yml` line 64 (`environment: production` on `build-and-push-docker-image`)
**Apply to:** any new job this phase adds that touches `secrets.*` (none currently planned to add a *new job* with secrets — TruffleHog needs none, the Gradle/NVD folded todos run inside existing jobs — but this is the convention to follow if scope shifts).

### Hard-gate vs. report-only posture split
**Source:** `secret-scan.yml`'s header comment (lines 8-18) explicitly contrasts itself with `security-scan.yml`'s schedule-driven, report-only OWASP job
**Apply to:** TruffleHog (hard-gate, D-08) — cite this exact contrast in the new step's own comment for consistency with how this repo already explains its two different postures.

## No Analog Found

| File | Role | Data Flow | Reason |
|---|---|---|---|
| `README.md` | documentation | N/A | Only one README exists in the repo; restructure follows CONTEXT.md's decisions directly rather than an existing analog file (see Pattern Assignments section above for the closest available structural inputs). |
| `gradle/verification-metadata.xml` (new file, folded todo) | build config (generated) | batch | No prior Gradle-verification-metadata file exists in this repo; it is machine-generated by `./gradlew --write-verification-metadata sha256` per RESEARCH.md, not hand-authored from a pattern. |

## Metadata

**Analog search scope:** `.github/workflows/`, `.githooks/`, `src/main/resources/*.properties`, `README.md`, `.github/dependabot.yml` — every file CONTEXT.md/RESEARCH.md names as a modification target; no new files are created in this phase except the generated `gradle/verification-metadata.xml`.
**Files scanned:** 8 target files read in full (all ≤ 666 lines, single-pass reads, no re-reads); `deploy.yml`'s `appleboy/*` call sites located via Grep before targeted offset reads.
**Pattern extraction date:** 2026-08-19
