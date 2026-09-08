---
created: 2026-09-08T00:00:00.000Z
title: "cadvisor, grafana and caddy mem_limit caps need a longer-observation-window re-ladder"
area: infra
severity: moderate
files:
  - docker-compose.prod.yml
  - docs/INFRA_RUNBOOK.md
---

## Problem

Filed from Phase 12's own goal-verification pass (2026-09-08), same day as plans 12-05/12-06.

Plan 12-05's restart-ladder measurement adopted `cadvisor: 64m` and `grafana: 384m` using a short
synthetic workload cycle (a burst + dashboard load + a ~20s settle, each rung a few minutes at
most). Real production usage — discovered hours after deployment, during this phase's own
goal-verification pass — found both caps insufficient under sustained real conditions:

- `cadvisor`: 6 separate OOM kills across ~40 minutes of real operation (confirmed via `dmesg` on
  netcup-prod), each showing anon-rss climbing toward the cap before being killed. The original
  ladder's "flat under the burst" characterization held for that short test but not for sustained
  real usage — a slow creep the burst never ran long enough to observe.
- `grafana`: 2 separate OOM kills within ~30 minutes, the second at ~446MiB actual usage — nearly
  double the original ladder's measured ~292MiB peak.

Both were corrected the same day with conservative raised values (`cadvisor: 128m`, `grafana:
768m` — see `docs/INFRA_RUNBOOK.md`'s "Addendum — same-day correction" under the Plan 12-05
section) and confirmed stable in the following hour, but these are corrective values, not a
re-measured floor.

## Solution

Run a genuine restart-ladder descent for both services against a LONGER observation window — real
sustained usage over hours, not a multi-minute synthetic burst — to find each service's actual
measured floor at this scale, following the same method and evidence discipline this repository's
other caps use (see `docs/INFRA_RUNBOOK.md`'s existing measurement sections for the shape). The
raised values (128m/768m) are safe-but-conservative, not evidence of a true floor; a proper
re-ladder may find something lower with real margin, or may confirm these values are close to
correct — either way it closes the gap between "corrected under time pressure" and "actually
measured."

Also worth investigating as part of this: whether `cadvisor`'s per-container housekeeping memory
genuinely grows unboundedly over very long uptimes (a leak-shaped pattern) or reaches a real
steady state above 64m but below 128m — the original ladder's "scales with container count, not
workload" theory may need revision if a longer window shows continued growth rather than a
plateau.

## Also in scope: caddy's adopted 32m cap (added 2026-09-08, same-day phase-close verification)

`caddy`'s plan-12-06 measurement adopted `mem_limit: 32m` with a KNOWN, already-disclosed coverage
gap: its adversarial workload component (driving the rate limiter's per-source-address memory
state) could only be run from a single sandboxed session — one distinct source address, not a
realistic multi-attacker cardinality. The 32m/16m margin above the bare passing floor was stated
explicitly as compensation for that gap, not a substitute for measuring it
(`docs/INFRA_RUNBOOK.md`, "Caddy resource measurement — Plan 12-06"). Unlike `cadvisor`/`grafana`,
caddy's adopted value has NOT shown live instability under real production traffic as of this
todo's filing (confirmed stable, `RestartCount=0`, for 35+ minutes of real internet-facing traffic
during the same verification pass that caught the cadvisor/grafana gaps) — so this is a
disclosed-gap follow-up, not an active-incident correction like the other two. Given caddy is the
sole production ingress and the highest-consequence of the three if its cap proves wrong, it
should get the same longer-observation-window re-ladder treatment, plus — ideally — a genuinely
multi-source-address adversarial test (e.g., from multiple different networks/proxies) rather than
the single-address approximation the original ladder was limited to.
