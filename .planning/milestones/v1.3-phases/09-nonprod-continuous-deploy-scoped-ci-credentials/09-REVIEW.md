---
phase: 09-nonprod-continuous-deploy-scoped-ci-credentials
reviewed: 2026-08-19T00:00:00Z
depth: standard
files_reviewed: 5
files_reviewed_list:
  - .github/workflows/deploy.yml
  - docs/ARCHITECTURE.md
  - docs/INFRA_RUNBOOK.md
  - src/main/java/com/vrudenko/kanban_board/config/ProblemDetailOpenApiCustomizer.java
  - src/test/java/com/vrudenko/kanban_board/config/ProblemDetailOpenApiCustomizerTest.java
findings:
  critical: 0
  warning: 4
  info: 3
  total: 7
status: issues_found
---

# Phase 09: Code Review Report

**Reviewed:** 2026-08-19T00:00:00Z
**Depth:** standard
**Files Reviewed:** 5
**Status:** issues_found

## Summary

`.github/workflows/deploy.yml` (233 lines added this phase) and `docs/INFRA_RUNBOOK.md` (351 lines
added) are the actual scope of this phase's changes — confirmed via `git diff --stat` against
`diff_base`. `docs/ARCHITECTURE.md`, `ProblemDetailOpenApiCustomizer.java`, and
`ProblemDetailOpenApiCustomizerTest.java` show **zero diff** in this commit range; they were reviewed
per the file list but carry no phase-09 changes to find bugs in, and none were found in them on a
read-through (no findings recorded against those three files below).

**On the context note's three specific questions:**

1. **`set -e` placement** — confirmed correctly placed and complete. All three
   `appleboy/ssh-action` script blocks (`deploy-to-netcup`, `register-schemas-production`,
   `deploy-to-nonprod`) have `set -e` as the literal first line of `script:`. No gaps found.
2. **Other order-dependent failure modes** — none found where `set -e` now aborts a failure that
   should be tolerated. The one place a failure is genuinely meant to be tolerated
   (`health-check-nonprod`'s per-attempt `curl ... || echo "000"`) already handles it correctly via
   the `||` fallback, independent of the ssh-action fix (this step runs under GitHub Actions' own
   default `bash --noprofile --norc -eo pipefail {0}`, which is a different failure-mode surface than
   the ssh-action's plain remote shell — worth knowing when reasoning about this file, since the two
   `set -e` mechanisms are not the same one). All three `docker compose run --rm` calls inside the
   ssh-action scripts are unconditionally fatal by design (a mid-script failure should stop the
   script), so `set -e` is the correct behavior everywhere it now applies in this file.
3. **General CI/CD correctness/security** — see Warnings below. No blocker-level defect found: no
   injection vector reachable by untrusted (non-maintainer) input (this workflow triggers only on
   `push` to `master`, never `pull_request`, and no `${{ github.event.* }}` user-controlled string is
   ever interpolated into a `run:`/`script:` body), no logic error that misroutes a nonprod
   credential/tag/URL at production or vice versa (every nonprod/production axis — Docker Hub
   repository, `.env.*` file, `NETCUP_DEPLOY_USER`, environment name, image name output — was traced
   end-to-end and stays correctly scoped throughout).

## Warnings

### WR-01: `DOCKERHUB_TOKEN` interpolated directly into `run:` script text instead of via `env:`

**File:** `.github/workflows/deploy.yml:83, 496, 595`
**Issue:** Three steps splice `${{ secrets.DOCKERHUB_TOKEN }}` directly into the shell script text
(`docker login` at line 83; the JSON login payload built in `cleanup-old-images` at line 496 and
`cleanup-old-images-nonprod` at line 595), rather than passing it through an `env:` block and
referencing `$DOCKERHUB_TOKEN`. This is inconsistent with the safer pattern already used for
`DB_HOST`/`DB_NAME`/`DB_USER`/`DB_PASS` in `flyway-verify`/`flyway-verify-nonprod` (both passed via
`env:`). Direct `${{ }}` interpolation into a script body is the same mechanism GitHub's own
documented script-injection class of bug relies on for user-controlled strings; here the value is an
org-controlled secret rather than attacker input, but if the token literal ever contains a `"` (it is
embedded inside a double-quoted JSON string at lines 496/595), the shell/JSON construction breaks —
a functional bug, not just a hardening gap, and one that would only surface at the worst possible
time (a credential rotation lands a token with different formatting).
**Fix:**
```yaml
      - name: Log in to Docker Hub
        env:
          DOCKERHUB_TOKEN: ${{ secrets.DOCKERHUB_TOKEN }}
        run: echo "$DOCKERHUB_TOKEN" | docker login -u $DOCKERHUB_USER --password-stdin
```
Apply the same `env:` pattern to the two `cleanup-old-images*` jobs' login-token exchange.

### WR-02: Third-party SSH/SCP actions pinned by mutable tag, not commit SHA — now used by 3 jobs holding production SSH keys

**File:** `.github/workflows/deploy.yml:242, 252, 317, 370, 392`
**Issue:** `appleboy/scp-action@v1.0.0` and `appleboy/ssh-action@v1.2.5` are pinned by release tag.
Tags are mutable in git/GitHub — a compromised or hijacked upstream repository could move the tag to
point at malicious code without changing the version string in this file, and that code would run
with `NETCUP_SSH_KEY`, `NETCUP_HOST_FINGERPRINT`, and `NETCUP_DEPLOY_USER` in scope (i.e., the ability
to exfiltrate the deploy private key or run arbitrary commands as `deploy`/`deploy-nonprod` on the
production VM). This phase tripled the exposure: before Phase 9 only `deploy-to-netcup` used this
action pattern; now `register-schemas-production` and `deploy-to-nonprod` do too. This mirrors the
supply-chain concern this project has already flagged for Gradle dependencies (see the most recent
commit, "docs: capture two Gradle supply-chain hardening todos") — the same reasoning applies to
third-party GitHub Actions holding deploy credentials.
**Fix:** Pin to the full commit SHA for both actions (e.g.,
`appleboy/ssh-action@<40-char-sha>  # v1.2.5`), matching GitHub's and OpenSSF Scorecard's documented
recommendation for third-party actions that handle secrets.

### WR-03: No `permissions:` block — `GITHUB_TOKEN` runs at its default (unscoped) permission level

**File:** `.github/workflows/deploy.yml:1-15`
**Issue:** The workflow declares no top-level or job-level `permissions:` key, so every job's
implicit `GITHUB_TOKEN` runs at whatever the repository's default token permission setting is (which
can be broader than `read`-only depending on repo/org settings, and is not visible from this file
alone). This workflow now runs nine jobs, several of which execute third-party actions
(`appleboy/ssh-action`, `appleboy/scp-action`, `docker/build-push-action`) against production
infrastructure — an over-permissioned default token is unnecessary attack surface if any of those
actions is ever compromised, independent of WR-02 above.
**Fix:** Add a minimal top-level permissions block:
```yaml
permissions:
  contents: read
```

### WR-04: Neon pooler guard passes silently when `DB_HOST` is unset/empty

**File:** `.github/workflows/deploy.yml:137-151, 184-192`
**Issue:** `[[ "$DB_HOST" == *"-pooler"* ]]` evaluates false when `$DB_HOST` is an empty string (e.g.,
a misconfigured or not-yet-populated environment secret), so the guard prints "Guard passed" and lets
the job continue into a malformed `FLYWAY_URL` (`jdbc:postgresql://:5432/?sslmode=require`). The job
still fails overall (Flyway will error on the malformed connection string), but the failure surfaces
several steps later as an opaque connection error instead of the guard's own clear, purpose-built
message. This applies identically to both `flyway-verify` and `flyway-verify-nonprod`.
**Fix:**
```bash
if [ -z "$DB_HOST" ]; then
  echo "::error::DB_HOST is empty -- secret not resolved for this environment. Refusing to proceed."
  exit 1
fi
if [[ "$DB_HOST" == *"-pooler"* ]]; then
  ...
```

## Info

### IN-01: Unquoted shell variable expansions in `docker login`

**File:** `.github/workflows/deploy.yml:83`
**Issue:** `docker login -u $DOCKERHUB_USER --password-stdin` leaves `$DOCKERHUB_USER` unquoted.
Harmless today given the literal value (`rudenkovladimir`, no whitespace/glob characters), but
inconsistent with the quoting discipline used elsewhere in this same file (e.g., `"$DB_HOST"`,
`"$NEXT_URL"`, `"$TAG"`).
**Fix:** `docker login -u "$DOCKERHUB_USER" --password-stdin` for consistency.

### IN-02: Reviewed files with zero diff in this phase's commit range

**File:** `docs/ARCHITECTURE.md`, `src/main/java/com/vrudenko/kanban_board/config/ProblemDetailOpenApiCustomizer.java`, `src/test/java/com/vrudenko/kanban_board/config/ProblemDetailOpenApiCustomizerTest.java`
**Issue:** `git diff --stat` against `diff_base` (`f027715d6d008d5f077760b6b9b2aa26fac1f283^..HEAD`)
shows no changes to these three files. They were read per the supplied file list and no defects were
found in them on inspection (the `ProblemDetailOpenApiCustomizer`/test pair in particular is
internally consistent: the schema's `required` set matches what both envelope producers actually
guarantee, the `code` enum is derived from `ErrorCode` rather than hand-listed, and the coverage test
correctly walks every declared HTTP method per path). Flagging only so the reviewed-file list is not
mistaken for phase-09 changed-file evidence.
**Fix:** N/A — informational; consider excluding unchanged files from the review's `files:` input in
future phases scoped this narrowly, to avoid reviewer time spent confirming a no-op diff.

### IN-03: `cleanup-old-images` (production) still performs no HTTP status check on its `DELETE` call

**File:** `.github/workflows/deploy.yml:568-570`
**Issue:** `cleanup-unused-image`'s manifest-delete `curl -s -X DELETE ...` (production path) has no
status-code check at all, while its nonprod twin (`cleanup-unused-image-nonprod`, lines 661-666,
added this phase) does check and warn on non-2xx. This asymmetry is already tracked by the project as
T-09-14/Phase-10 scope per `docs/INFRA_RUNBOOK.md`'s own note (line ~656-660), so this is not a new
finding — recorded here only for completeness since the asymmetry is visible in this phase's diff.
**Fix:** No action needed for this phase; already deferred and documented.

---

_Reviewed: 2026-08-19T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
