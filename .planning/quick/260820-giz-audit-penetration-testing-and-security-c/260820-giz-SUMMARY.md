---
quick_id: 260820-giz
status: complete
completed: 2026-08-20
---

# Quick Task 260820-giz: OWASP API Security Top 10 audit

**Closed a long-open "needs its own investigation" todo by tracing this codebase's actual test
suite and security wiring against all 10 OWASP API Security Top 10 (2023) categories, producing a
cited covered / assumed-covered-but-unverified / genuinely-untested verdict for each — rather than
continuing to assume coverage. Filed 4 new pending todos for confirmed gaps; zero production code
changed.**

## What was done

**Task 1 (read-only trace, no commit):** traced all 6 originally-named candidate gaps plus the
full OWASP API Security Top 10 against `SecurityConfiguration`, `CorsConfig`,
`OwnershipVerifierService`, `ColumnService`, `ColumnController`/`TaskController`/
`SubtaskController`, all 13 `Save*/Update*RequestDTO` classes, `BoardMapper`,
`application.properties`, `Caddyfile`, and `InjectionAttemptTest`/`AuthorizationGatingTest`/
`AuthenticationTest`, plus targeted greps for rate-limiting and outbound-HTTP-client usage.

**Task 2** (commit `6a77e54`) filed 4 new pending todos for confirmed gaps and closed the
originating todo with a full cited `## Resolution`:

1. **`2026-08-20-idor-same-user-chain-consistency-boardid-columnid-not-c.md`** (severity:
   moderate) — a real, previously-unnamed gap surfaced during planning and confirmed here:
   `OwnershipVerifierService.verifyOwnershipOfColumn/Task/Subtask` only walk up from the *leaf*
   path id; `ColumnController`/`TaskController`/`SubtaskController` never bind or cross-check the
   URL's other ownership-chain segments (`boardId`, `columnId`). A user who owns two boards (A and
   B) can address `PUT /boards/{A}/columns/{columnId-of-B}` and silently mutate B's column — a
   same-user chain-confusion variant `AuthorizationGatingTest.CrossUserSweep` doesn't exercise,
   since that suite only varies the *user*, never chain consistency within one owning user's own
   resources.
2. **`2026-08-20-add-rate-limiting-to-signin-to-bound-brute-force-volume.md`** (severity:
   security) — confirmed genuinely absent: no rate-limiting implementation anywhere in `src/main`
   (grep for rate-limit/throttle/bucket-style terms returned zero matches). `AntiEnumeration` and
   `ConcurrentSessionCeiling` prove different properties; neither bounds volumetric brute force
   against an unauthenticated caller.
3. **`2026-08-20-security-response-headers-csp-and-unreliable-hsts-behind.md`** (severity:
   moderate) — no CSP anywhere in either Spring Security or the `Caddyfile`; HSTS likely never
   fires in production because no `server.forward-headers-strategy`/`ForwardedHeaderFilter` is
   configured, so `request.isSecure()` evaluates false behind Caddy's reverse proxy even for a
   real HTTPS request.
4. **`2026-08-20-verify-csrf-defense-with-a-real-cross-origin-rejection-t.md`** (severity: minor)
   — the CSRF-mitigation reasoning (`SameSite=Strict` cookie + `CorsConfig`'s non-wildcard
   credentialed allowlist) is sound and `SameSite=Strict` is proven on the wire, but no existing
   test proves an actual cross-origin request is rejected end-to-end — reasoning being sound isn't
   the same claim as reasoning being tested.

**Confirmed adequate, folded into the Resolution (no new todo):** DTO mass-assignment (API3) —
all 13 `Save*/Update*RequestDTO` classes checked; none expose a re-parenting/re-owning field, and
`BoardMapper`'s `unmappedTargetPolicy = IGNORE` protects in the correct direction. Inventory
management (API9) — `AuthorizationGatingTest.Completeness`'s reflective route-discovery sweep
guards drift. Dependency CVEs (API9/API8) — stayed a single source of truth: cross-referenced
`2026-08-03-add-dependency-vulnerability-scan.md` (completed) and
`2026-08-13-ratchet-failbuildoncvss-after-a-real-dependency-check-baseline.md` (still pending);
nothing new filed. The remaining categories not named by the original todo (API5, API6, API7,
API10) were each disposed of explicitly as N/A given this app's shape (no roles/admin surface, no
purchase/booking flow, no user-controlled outbound fetch, no third-party API consumption) rather
than skipped by omission.

## Verification

- `git status --short` clean under `src/main`/`src/test` — this was a pure audit/triage task, zero
  production code touched.
- `.planning/todos/completed/2026-08-13-audit-penetration-testing-and-security-coverage-identify-gap.md`
  exists with a `resolved:` date and `## Resolution` section citing all 10 categories; absent from
  `pending/`.
- 4 new todos exist under `.planning/todos/pending/`, each in this repo's established frontmatter
  convention with a specific `## Solution` direction, not a vague "investigate further."
- Pre-commit hooks (gitleaks, spotless, fastTest) ran clean on the closing commit. One transient
  issue: `fastTest` hit a Windows file-lock error from an orphaned `gradlew fastTest` process left
  over from an earlier commit attempt that the harness's 2-minute Bash timeout had killed
  mid-flight; identified via `wmic` (confirmed scoped to this worktree), killed, and the commit
  then succeeded with the full suite green. No hooks were skipped.

## Commits

- `b491f50` — pre-dispatch plan commit (worktree-isolation mechanics).
- `6a77e54` — `docs(quick-260820-giz): close OWASP API Security Top 10 audit todo, file 4 gap todos`.
- Plus this task's own closing docs commit (STATE.md + this SUMMARY.md).

## Next Phase Readiness

No blockers. The originating todo is closed with a citable, re-verifiable Resolution — a future
session doesn't need to re-derive this codebase's OWASP API Security Top 10 posture from scratch.
Four new todos are queued for pickup, ranked by the audit's own severity calls: rate-limiting
(security-severity, genuinely absent control) is the highest-priority follow-up; the IDOR
chain-consistency and security-headers gaps are moderate; the CSRF cross-origin test is a minor,
verification-only gap on top of an already-sound defense.

**Housekeeping note:** the executor's isolated worktree
(`.claude/worktrees/agent-adb81252af3310b85`) left a stray, empty, file-lock-busy directory behind
after cleanup on this Windows setup — git no longer tracks it as a worktree and it holds no
content, but the OS declined to delete the directory itself even after the lock-holding Gradle
daemon was killed. Harmless; a future session (or a manual `rmdir` once nothing has it open) can
clear it.
