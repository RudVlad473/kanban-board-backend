---
phase: 10-ci-deploy-hardening
verified: 2026-08-19T19:42:45Z
resolved: 2026-08-19T19:45:00Z
status: passed
score: 8/8 requirements verified; the one process gap (unpushed commit a90ae75) is now resolved
behavior_unverified: 0
overrides_applied: 0
gaps: []
resolved_gaps:
  - truth: "The repository state that satisfies HARDEN-01's Dependabot-cost 'soften' decision (grouped gradle updates) is live on origin/master, matching this session's own claim that 'everything is pushed to origin/master'"
    original_status: failed
    resolution: "git push origin master. Confirmed: `git log --oneline -1 origin/master` now resolves to a90ae75, matching local HEAD exactly. The `groups: gradle-updates` block in .github/dependabot.yml is live on GitHub."
deferred: []
---

# Phase 10: CI & Deploy Hardening Verification Report

**Phase Goal:** The repository's CI, secret-scanning, and session-cookie configuration close the eight
hardening gaps accumulated across v1.2, and the README stands on its own as a full architecture
showcase.
**Verified:** 2026-08-19T19:42:45Z
**Status:** gaps_found
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (ROADMAP Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Dependabot raises update PRs for outdated GitHub Actions, not only Gradle dependencies | VERIFIED | `.github/dependabot.yml` has a second `updates:` entry, `package-ecosystem: "github-actions"`. **Live proof, not just config presence:** `gh pr list` shows 4 real open PRs Dependabot opened against `github-actions` (`#7` actions/upload-artifact, `#8` actions/cache, `#9` docker/build-push-action, `#10` actions/checkout), alongside the pre-existing 5 gradle-ecosystem PRs (`#1`-`#5`). D-07's "must compose with the digest pins" concern is moot for these 4 PRs since none touch the digest-pinned `appleboy/*` lines. |
| 2 | CI's secret scanning distinguishes a live, currently-exploitable credential from a merely pattern-matched string, and the pre-commit gitleaks hook scans a staged diff correctly when invoked from a worktree created outside the main repo tree | VERIFIED | `secret-scan.yml`'s `verified-credential-scan` job (TruffleHog, digest-pinned `ghcr.io/trufflesecurity/trufflehog:3.97.0@sha256:...`, hard gate, `--results=verified`) exists as a sibling to the pattern-match `secret-scan` (gitleaks) job. **Live confirmed:** latest push run `32293313711` (commit `00dd644`) shows both `secret-scan` and `verified-credential-scan` jobs green. SUMMARY's throwaway-PR asymmetry test (gitleaks failed on a synthetic AWS-shaped string, TruffleHog passed since it wasn't live) is plausible given the job's `--results=verified` design and is consistent with the job definition read directly. `.githooks/pre-commit` has a `case "$GIT_TOPLEVEL" in "$MOUNT_ROOT"\|"$MOUNT_ROOT"/*) ... *) ... esac` branch; the `*)` fallback pipes `git diff --cached` into gitleaks' `stdin` mode for the out-of-tree case, read directly and confirmed structurally sound. |
| 3 (narrowed by CONTEXT.md D-05) | Every `appleboy/*` `uses:` reference in `deploy.yml` resolves to an immutable commit digest; first-party actions keep tag-only trust behind an explicit risk-acceptance comment; `security-scan.yml`'s stale comment/action versions are corrected; `deploy.yml`'s `run-tests` job caches Gradle | VERIFIED | `grep -c 'uses: appleboy/(scp\|ssh)-action@[0-9a-f]{40}'` → 5/5, zero tag-referenced `appleboy/*` remain. A dated `# Risk-acceptance (Phase 10, HARDEN-03, D-05)` comment block (lines 16-23) names all 6 first-party actions kept tag-trusted and states the SSH-key blast-radius reasoning. `security-scan.yml` uses `actions/checkout@v5` / `actions/setup-java@v5`, matching `deploy.yml`'s `run-tests` job exactly (both read directly). `cache: 'gradle'` present in `deploy.yml`'s `run-tests` `Set up Java` step (line 69). |
| 4 | Session cookies carry the `Secure` flag in both `application.properties` and `application-test.properties`, and authenticated flows still pass end-to-end against a TLS-served environment | VERIFIED | `server.servlet.session.cookie.secure=true` present in both files (direct grep). **Independently re-ran** `./gradlew test --tests "*SessionCookieAttributesE2ETest*"` in this verification session (not trusted from SUMMARY): `tests="1" failures="0" errors="0"` — a real-socket signin asserts `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/`, and `Max-Age` all directly off the wire `Set-Cookie` header, not inferred from config. Live nonprod round-trip (deploy run `32288799429`, commit `50bc2a1`) is corroborated by `gh run list` showing that run's `success` conclusion, consistent with the SUMMARY's claimed live signup+cookie check. |
| 5 | A newcomer reading only the README can see the system's architecture, stack, and deployment shape without opening `docs/` | VERIFIED | `README.md` is 269 lines, 12 `## ` sections (`What this is` → `Production deployment` → `CI/CD pipeline & deploy strategy` → `Quality & security gates` → `Stack` → ... → `Documentation`), one embedded ` ```mermaid ` block (line 38). Test-count claim (424) independently re-derived via the exact command the SUMMARY cites (`grep -rcE '@(Test\|ParameterizedTest)\b' src/test/java --include=*.java`, summed) — matches exactly. Diagram render was live-confirmed post-merge by the orchestrator with a real headless browser per the session context (not independently re-run here — no browser tool available to this verifier; treated as credible given the specific, falsifiable detail reported: a genuine WebFetch false-negative correctly diagnosed and superseded). |

**Score:** 5/5 ROADMAP success criteria truths verified.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|---|---|---|---|---|
| HARDEN-01 | 10-01, 10-04 | Dependabot `github-actions` ecosystem entry | SATISFIED | Config present + **live PRs #7-#10** prove it's actually raising updates. Grouping refinement (soften decision) correct on disk but **not yet pushed to origin** — see Gaps. |
| HARDEN-02 | 10-02 | TruffleHog live-credential verification pass | SATISFIED | Job present, hard-gated, digest-pinned, live green on latest push (`32293313711`). |
| HARDEN-03 | 10-01 | `deploy.yml`/`security-scan.yml` digest pinning (narrowed to `appleboy/*` per D-05) | SATISFIED | 5/5 real call sites pinned; risk-acceptance comment present and dated. |
| HARDEN-04 | 10-01 | `deploy.yml`'s `run-tests` Gradle cache | SATISFIED | `cache: 'gradle'` present, matching `security-scan.yml` precedent. |
| HARDEN-05 | 10-02 | Pre-commit gitleaks hook, out-of-tree worktree | SATISFIED | `case` branch with `stdin`-mode fallback read directly and structurally sound. |
| HARDEN-06 | 10-03 | `security-scan.yml` stale comment/action fix + NVD_API_KEY | SATISFIED | Pins match; live `dependency-check` job green (`32280511632`) with report artifact, diagnostic step confirmed removed from the current file. |
| HARDEN-07 | 10-05 | `Secure` session cookie flag | SATISFIED | Both properties files confirmed; real-socket test independently re-run and passing. |
| HARDEN-08 | 10-06 | README architecture showcase | SATISFIED | Structure, section count, embedded diagram, and re-derived facts (test count) all confirmed against the live file. |

All 8 HARDEN-* requirements from `.planning/REQUIREMENTS.md`'s Phase 10 row set are covered by exactly one plan each; no orphans.

### Required Artifacts

| Artifact | Expected | Status | Details |
|---|---|---|---|
| `.github/workflows/deploy.yml` | 5 digest-pinned `appleboy/*` sites, risk-acceptance comment, `cache: 'gradle'`, warn-only DELETE status checks | VERIFIED | All present, confirmed by direct grep/read; live-green on latest push. |
| `.github/workflows/secret-scan.yml` | `verified-credential-scan` job (TruffleHog, hard-gated, digest-pinned) alongside existing `secret-scan` (gitleaks) | VERIFIED | Present, read in full; live green. |
| `.github/workflows/security-scan.yml` | `actions/checkout@v5`/`setup-java@v5` matching `deploy.yml`; `env:`-indirected `NVD_API_KEY`; no diagnostic step remaining | VERIFIED | Confirmed by direct read; live `dependency-check` job green with report artifact. |
| `.githooks/pre-commit` | `case`-based scan-path selection covering out-of-tree worktrees | VERIFIED | Read in full; branch logic structurally sound. |
| `.github/dependabot.yml` | `github-actions` ecosystem entry; `gradle` entry grouped per the user's "soften" decision | PARTIAL | `github-actions` entry live and proven (4 open PRs). Grouping block correct **on disk** but its carrying commit (`a90ae75`) is **not pushed to origin/master** — see Gaps. |
| `src/main/resources/application.properties` / `application-test.properties` | `server.servlet.session.cookie.secure=true` | VERIFIED | Confirmed by direct grep in both files. |
| `src/test/java/.../SessionCookieAttributesE2ETest.java` | Real-socket assertion of the full cookie contract | VERIFIED | Read in full; independently re-run, 1/1 pass. |
| `README.md` | Production-reality-first architecture showcase, one embedded Mermaid diagram | VERIFIED | 269 lines, 12 sections, 1 mermaid block, facts re-derived and matched. |
| `gradle/wrapper/gradle-wrapper.properties`, `gradle/verification-metadata.xml` | Distribution checksum pin + dependency-verification metadata (folded todos, no formal HARDEN-* ID) | VERIFIED | `distributionSha256Sum` present; `verification-metadata.xml` present (4361 lines); `./gradlew spotlessCheck` passes clean under the active pin. |

### Key Link Verification

| From | To | Via | Status | Details |
|---|---|---|---|---|
| `.github/dependabot.yml` (`github-actions` ecosystem) | `deploy.yml`'s digest-pinned `appleboy/*` references | Dependabot's native SHA+comment bump | WIRED, live-proven | 4 real open PRs against non-`appleboy` first-party actions confirm the ecosystem entry works end-to-end on GitHub, not just parses locally. |
| `secret-scan.yml`'s `verified-credential-scan` | hard gate on push/PR | `exit 1` on TruffleHog status 183 or unexpected, no `continue-on-error` | WIRED, live-green | Confirmed via direct read and the latest live run. |
| `deploy.yml`'s `run-tests`/`security-scan.yml`'s `dependency-check` | Gradle wrapper integrity | `gradle/actions/wrapper-validation@v6` ahead of first `./gradlew` invocation | WIRED | Confirmed present in both files at the correct step position. |
| `application*.properties`'s `cookie.secure=true` | wire-level `Set-Cookie: Secure` | Spring Session's `DefaultCookieSerializer` | WIRED, test-proven | `SessionCookieAttributesE2ETest` independently re-run, passing. |

### Anti-Patterns Found

None. Grepped `TBD\|FIXME\|XXX\|TODO\|HACK\|PLACEHOLDER\|not yet implemented\|coming soon` across every file this phase's plans modified (`deploy.yml`, `secret-scan.yml`, `security-scan.yml`, `.githooks/pre-commit`, `dependabot.yml`, both `application*.properties`, `SessionCookieAttributesE2ETest.java`, `README.md`, `gradle-wrapper.properties`, `build.gradle`) — zero matches.

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|---|---|---|---|
| Session cookie carries Secure/HttpOnly/SameSite/Path/Max-Age off the real wire | `./gradlew test --tests "*SessionCookieAttributesE2ETest*"` (run fresh in this session) | `tests="1" failures="0" errors="0"` | PASS |
| `spotlessCheck` passes under the pinned Gradle wrapper distribution | `./gradlew spotlessCheck` | `BUILD SUCCESSFUL` | PASS |
| Latest live push (docs-only, `00dd644`) still triggers a full `deploy.yml` run | `gh run list --workflow=deploy.yml` | `success`, `event: push`, `createdAt: 2026-08-19T19:29:32Z` | PASS (confirms the "no path filter" finding below, not itself a defect) |
| `deploy.yml` job graph on that run includes both production and nonprod paths, all green | `gh run view 32293313767 --json jobs` | 11 success + 2 correctly-skipped `cleanup-unused-image*` (only run `if: failure()`) | PASS |
| Latest `secret-scan.yml` push run: both gitleaks and TruffleHog jobs green | `gh run view 32293313711 --json jobs` | `secret-scan: success`, `verified-credential-scan: success` | PASS |
| Dependabot `github-actions` ecosystem genuinely raising PRs | `gh pr list` | 4 open PRs (#7-#10) against `actions/*`/`docker/*` | PASS |
| `security-scan.yml`'s `dependency-check` job green with NVD key fixed | `gh run view 32280511632 --json jobs` | `dependency-check: success` | PASS |
| README test-count claim (424) independently re-derived | `grep -rcE '@(Test\|ParameterizedTest)\b' src/test/java --include=*.java` summed | `424` | PASS |
| Full phase's local repo state matches what was claimed pushed | `git status` / `git log --oneline -1 origin/master` | Local HEAD `a90ae75` is 1 commit ahead of `origin/master` (`00dd644`) | **FAIL** — see Gaps |

### Probe Execution

No `scripts/*/tests/probe-*.sh` convention exists in this repository and none is declared by this phase's plans. SKIPPED — not applicable.

### Human Verification Required

None. Every ROADMAP success criterion and every HARDEN-* requirement resolved to VERIFIED via direct
file reads, an independently re-run test, and live `gh run`/`gh pr` evidence gathered fresh in this
verification session — not carried over from SUMMARY prose. The one item this pass could not
independently re-verify (the on-GitHub Mermaid render, HARDEN-08) was accepted as credible on the
strength of a specific, falsifiable, and independently plausible detail in the session context (a
correctly diagnosed WebFetch JS-execution limitation) rather than a bare "looks fine" claim; it is
not itself in doubt enough to route to human verification.

### Gaps Summary

**One process gap, not a requirement-content failure.** All 8 HARDEN-* requirements are genuinely
satisfied in the code that exists on disk, and 7 of 8 have direct live-CI corroboration (workflow
runs, open PRs) gathered independently in this session, not merely asserted by SUMMARY.md. The gap is
narrower than "does the feature work": the local `master` branch is one commit ahead of
`origin/master`. That unpushed commit (`a90ae75`) carries the Dependabot gradle-grouping change that
implements the user's own "soften" decision from Plan 10-04's Task 3 checkpoint — the change is
correct and present in the local file, but it is not yet live on GitHub, so Dependabot's dashboard and
its next scheduled run do not yet reflect it. This directly contradicts this session's own stated
context ("Everything is pushed to origin/master"). It does not block any HARDEN-* requirement (the
grouping refinement was explicitly not assigned a formal requirement ID by Plan 10-04's own
frontmatter), but per this project's CLAUDE.md "verify before claiming" directive and the developer's
own recorded git-hygiene preference (push after every closing commit), this should not be silently
absorbed into a passing verdict. **Recommended fix:** `git push origin master`, then re-run this
verification's Dependabot-composition check (`gh api .../dependabot/updates` or wait for the next
scheduled run) to confirm the grouped-PR behavior is live.

**Separately flagged, not a phase blocker (per this task's own instructions):** `deploy.yml`'s
`on: push: branches: [master]` trigger carries no `paths:` filter, so a docs-only push (10-06's
README commit, and again the eventual push of `a90ae75`) triggers the full build → test → both
production and nonprod deploy job graph — confirmed live: the push of commit `00dd644` (a merge of
the docs-only 10-06 branch) produced a complete, successful `deploy.yml` run including
`deploy-to-netcup` and `deploy-to-nonprod`. This is wasteful (an unnecessary redeploy of unchanged
application code) but harmless (every such run this session observed completed green). A `paths:`
filter (or `paths-ignore: ['**.md', 'docs/**']`) on `deploy.yml` would avoid this without weakening
any gate — worth a follow-up todo, not a Phase 10 gap.

---

## Resolution (2026-08-19T19:45:00Z)

The one gap above is closed. `git push origin master` published commit `a90ae75`.
`git log --oneline -1 origin/master` now resolves to `a90ae75`, matching local `master` exactly —
the `groups: gradle-updates` block is live on GitHub. The `deploy.yml` path-filter finding remains
recorded as a non-blocking follow-up
(`.planning/todos/pending/2026-08-19-deploy-yml-has-no-path-filter-so-docs-only-pushes-trigger-a-full-deploy.md`),
not something this phase needed to fix to close.

**Phase 10 is complete: 8/8 HARDEN-* requirements verified, 6/6 plans shipped, all local state
pushed.**

---

_Verified: 2026-08-19T19:42:45Z_
_Verifier: Claude (gsd-verifier)_
