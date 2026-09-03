---
created: 2026-09-03T00:00:00.000Z
title: "The caddy service has no mem_limit, and its memory is now attacker-influenced"
area: infra
severity: minor
files:

  - docker-compose.prod.yml
  - docs/INFRA_RUNBOOK.md

---

## Problem

Filed from quick task 260903-dvp (edge rate limiting).

`caddy` is the only service in `docker-compose.prod.yml` without a `mem_limit`. That was
unremarkable while it was a stock reverse proxy with a fixed working set (~20 MB RSS measured).
Adding the rate limiter changed the shape: the handler holds per-key state keyed on client address,
so an attacker with many source addresses now influences how much memory the edge allocates.

The bound was measured during that task and is not alarming, which is why this is minor rather
than a defect. Per-key state is a preallocated ring of `maxEvents` `time.Time` values (24 B each),
so 120 + 20 events is ~3.1 KB per address touching both zones; 100k distinct addresses inside one
window is roughly 350 MB against ~2.65 GiB documented host headroom. The module also sweeps expired
keys every 60s by default, so that figure is a per-window peak rather than accumulation.

## Solution

Do NOT simply add a cap — that is exactly what was declined during 260903-dvp. Every other
`mem_limit` in that file carries a measured rung ladder in `docs/INFRA_RUNBOOK.md` (see the
`postgres` service's comment for the established shape: descend rungs against a real workload until
one fails, then step back up), and an unmeasured cap on the only container answering :443 risks
OOM-killing the edge — turning a bounded memory concern into a total outage.

So: measure first, using the project's own established method. Drive the limiter to a realistic
key cardinality (`scripts/loadtest/` is the obvious starting point, extended to vary the source
address rather than repeat one), watch `docker stats` for the `caddy` container across the sweep
interval, and pick a rung from the observed peak with the same headroom factor the other services
used. Then write the ladder into the runbook alongside the others.

Alternative worth considering while measuring: lowering the general zone's `events` bounds the
per-key ring directly, and may be cheaper than a container cap.
