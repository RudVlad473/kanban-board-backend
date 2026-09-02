---
quick_id: 260902-vjo
status: complete
subsystem: infra
tags: [github-actions, uptime-monitoring, documentation, netcup, oom-triage]
dependency-graph:
  requires: []
  provides: [console-staleness-triage-procedure, scheduled-uptime-probe]
  affects: [docs/INFRA_RUNBOOK.md, .github/workflows/uptime-check.yml]
tech-stack:
  added: []
  patterns: [env-indirected-run-script-no-interpolation, retry-with-sleep-no-short-circuit, folded-scalar-env-list]
key-files:
  created:
    - .github/workflows/uptime-check.yml
  modified:
    - docs/INFRA_RUNBOOK.md
decisions:
  - "Triage section inserted between ## Access and ## Firewall (not folded into the firewall gotcha, not appended at end-of-file) — findable under the pressure this content is only ever read under."
  - "Cron interval */15, not */5 — GitHub's 5-minute floor is nominal only given scheduler queueing jitter, so */15 buys nearly the same real latency for a third of the Actions-tab noise."
  - "permissions: {} (not contents: read) since this job never checks out the repository — same shape as deploy.yml's health-check-nonprod, flagged as an intentional divergence rather than silent drift."
  - "Script built to accumulate failures and always evaluate every URL (no short-circuit) — a production outage masking a nonprod outage is the one failure this probe cannot be allowed to have."
metrics:
  duration: "~40 minutes"
  completed: "2026-09-02"
actuals:
  tokens: 2839
  tasks: 3
  commits: 2
---

# Quick Task 260902-vjo: Netcup Console Staleness Triage + Uptime Probe Summary

Two deliverables closing out a 2026-09-02 false alarm where a frozen Netcup SCP console screenshot
of a week-old, already-fixed OOM incident was read as a live outage: a new triage section in
`docs/INFRA_RUNBOOK.md` that lets a future reader date what that console shows before escalating,
and a scheduled `.github/workflows/uptime-check.yml` GitHub Actions probe of both public health
endpoints so "is it actually down?" has a cheap, independent, standing answer.

## What Was Built

- **`docs/INFRA_RUNBOOK.md`** — new `## Triage — dating what the Netcup SCP "Screen" console shows
  (2026-09-02)` section, inserted immediately after `## Access` and before `## Firewall — two
  independent layers`. States the frozen-tty1-framebuffer / seconds-since-boot mechanism as its own
  claim; a five-step triage order (curl both public health endpoints first, `docker inspect`,
  `dmesg -T`, UTC-vs-CEST clock normalisation, the per-instance `memory.events`/`memory.peak`
  counters); the concrete `2026-08-26T19:24:34Z` vs `20:28:31 CEST` timezone-trap case that inverts
  the ordering if compared raw; and attributes all four Aug 26 postgres OOM kills to the two
  already-documented, closed measurement sections in this same file ("Self-hosted Postgres resource
  measurement — Plan 11-03" and "Postgres memory profile correction — Plan 11-08"), closing with an
  explicit escalation rule for anything that cannot be dated to one of those two.
- **`.github/workflows/uptime-check.yml`** (new) — `schedule: */15 * * * *` plus `workflow_dispatch`,
  `permissions: {}`, `timeout-minutes: 5`. A single step probes both public
  `/api/actuator/health` endpoints (held in a folded-scalar `HEALTH_URLS` env var, no secrets), up to
  3 attempts per endpoint with a 10s sleep between, treating an endpoint as passing only when the
  status is exactly `200` **and** the body contains the `"status":"UP"` marker. Every value the
  script reads arrives through the job's `env:` block — the `run:` body contains zero `${{ }}`
  interpolation, matching this repo's documented injection-safety rule and letting the exact script
  be extracted and run locally unmodified. On failure it emits one `::error::` annotation per failed
  endpoint (status failure and body-mismatch failure are textually distinct messages) and exits 1;
  a failure on one endpoint never skips evaluating the other.

## Task Commits

1. **Task 1: Add the console-triage section to docs/INFRA_RUNBOOK.md** — `ae9ce63` (docs)
2. **Task 2: Create .github/workflows/uptime-check.yml** — `4613746` (feat)
3. **Task 3: Prove the gate bites** — no code commit (proof-only task; this SUMMARY.md is its
   output artifact, per the plan's own `<output>` spec)

**Plan metadata:** committed separately by the orchestrator (Step 8), not by this executor.

## Files Created/Modified
- `docs/INFRA_RUNBOOK.md` — new Triage section (88 lines added, no other content touched)
- `.github/workflows/uptime-check.yml` — new file (122 lines)

## Decisions Made

See `decisions` in frontmatter above. All four were already locked in the plan's
`<design_alternatives>`; no new architectural decision was made during execution.

## Task 3: Proof — Five Local Branches + Live Dispatch Status

Per the plan's proof requirement ("a check that passes both ways is not covering anything"), the
workflow's own `run:` script was extracted (never retyped) via:

```bash
python3 -c 'import yaml; print(yaml.safe_load(open(".github/workflows/uptime-check.yml"))["jobs"]["uptime-check"]["steps"][0]["run"])' > /tmp/uptime-probe.sh
```

and driven through all five required branches under `sh`, each with the same env vars the job's
`env:` block supplies:

| # | Branch | Command (HEALTH_URLS) | Exit code | Key output |
|---|--------|------------------------|-----------|------------|
| 1 | Positive | Both real endpoints, `ATTEMPTS=3` | **0** | `https://kanban-board-rud-vlad-473.duckdns.org/api/actuator/health: OK (200, UP marker present)` / `...nonprod...: OK (200, UP marker present)` / `Both endpoints OK: ...` |
| 2 | Non-200, reachable host | `.../api/actuator/does-not-exist`, `ATTEMPTS=1` | **1** | `Attempt 1/1: ...does-not-exist returned status 401` / `::error::...does-not-exist returned status 401 (expected 200)` |
| 3 | Unreachable host | `https://this-host-does-not-exist-kanban.invalid/api/actuator/health`, `ATTEMPTS=1` | **1** | `Attempt 1/1: ...invalid/... returned status 000` / `::error::...invalid/... returned status 000 (expected 200)` — proves curl's own non-zero exit converts into a reportable `000`, not a crash or a false pass |
| 4 | 200 with wrong body | `https://example.com/`, `ATTEMPTS=1` | **1** | `Attempt 1/1: https://example.com/ returned 200 but the body did not contain the UP marker` / `::error::https://example.com/ returned status 200 but the response body did not contain the UP marker` — textually distinct from branch 2's "expected 200" message, proving the body is genuinely checked, not just the status |
| 5 | Mixed (prod + example.com) | Real prod URL then `https://example.com/`, `ATTEMPTS=1` | **1** | `https://kanban-board-rud-vlad-473.duckdns.org/api/actuator/health: OK (200, UP marker present)` printed **before** the example.com failure — the healthy endpoint was fully evaluated and passed despite appearing first in a list that ultimately fails, proving no short-circuit |

The temp script was deleted after use (`rm -f /tmp/uptime-probe.sh`); confirmed absent afterward.

**Live `workflow_dispatch` run: EXPLICITLY PENDING.** `workflow_dispatch` is only offered by GitHub
for a workflow already present on the repository's default branch on the remote. Local `main` is 2
commits ahead of `origin/main` (`ae9ce63`, `4613746`) and this executor's isolation constraints
prohibit pushing to `origin` — that is the user's call. The live dispatch cannot happen until the
user pushes. Follow-up commands, to run once pushed:

```bash
git push origin main
gh workflow run uptime-check.yml --ref main
RUN_ID=$(gh run list --workflow uptime-check.yml --limit 1 --json databaseId --jq '.[0].databaseId')
gh run watch "$RUN_ID" --exit-status
```

No green run was observed or claimed by this executor.

## Deviations from Plan

None — plan executed exactly as written. One in-flight self-correction during Task 1 authoring: the
first draft of the lead paragraph line-wrapped the phrase "seconds since boot" across two source
lines, which the automated verify's single-line `grep -c 'seconds since boot'` check could not
match. Caught by re-running the plan's own verify command before committing, fixed by rewrapping
that one line, re-verified, then committed — not a deviation from the plan's intent, just a markdown
line-wrap fixed before the task was considered done.

## Issues Encountered

None.

## User Setup Required

**One manual step remains: pushing this branch to `origin`.** This executor's isolation
constraints explicitly prohibit pushing — see "Task 3" above for the exact three follow-up commands
(`git push origin main`, `gh workflow run uptime-check.yml --ref main`, then watch the run) to run
once the user has reviewed and is ready to push. Until then, the scheduled `*/15 * * * *` cron also
will not fire, since GitHub only activates `schedule` triggers for workflows present on the default
branch.

## Next Phase Readiness

Both deliverables are complete and independently reviewable. `git status --porcelain` shows exactly
the two intended files changed across the two task commits, and `git diff --stat` confirms `src/**`
was never touched. No VPS change, no compose change, no postgres tuning change, no third monitoring
mechanism was introduced — matching the plan's locked scope. The only outstanding action is the
user's own push + live dispatch verification described above; nothing blocks it.

---
*Quick task: 260902-vjo*
*Completed: 2026-09-02*

## Self-Check: PASSED

- FOUND: `docs/INFRA_RUNBOOK.md`
- FOUND: `.github/workflows/uptime-check.yml`
- FOUND: `.planning/quick/260902-vjo-document-netcup-console-staleness-triage/260902-vjo-SUMMARY.md`
- FOUND commit `ae9ce63` in `git log --oneline --all`
- FOUND commit `4613746` in `git log --oneline --all`
