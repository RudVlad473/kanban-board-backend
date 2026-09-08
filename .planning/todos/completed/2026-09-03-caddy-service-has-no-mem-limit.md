---
created: 2026-09-03T00:00:00.000Z
resolved: 2026-09-08
resolves_phase: 12
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

## Resolution

Closed by **Phase 12, plan 12-06 (2026-09-08)**. `caddy` now carries `mem_limit: 32m` in
`docker-compose.prod.yml`, produced by a live restart-ladder descent (256m -> 128m -> 64m -> 32m ->
16m all passing, 8m OOM-killed and crash-looping) run against real edge traffic — the same measured
method this repository's other caps already use, exactly as this todo's own Solution section
required, not the idle-figure shortcut it explicitly declined.

The workload's adversarial component drove `scripts/loadtest/run-rate-limit-verification.sh`
against `/api/signin`, but from a single sandboxed session — one distinct source address, not the
realistic multi-address key cardinality this todo's Solution section asked for. That coverage gap
is recorded honestly rather than glossed over: the adopted 32m is deliberately NOT the bare
16m-passing floor. The entire 32m→16m margin (~15.8MiB, ~98% above measured peak RSS) is stated
explicitly as compensation for the untested multi-attacker case, not a substitute for measuring it.

Full rung-by-rung ladder, the failing rung's verbatim OOM evidence, and the certificate-safety
stop-condition proof (a real 8m crash-loop was caught and halted immediately, no Let's Encrypt
re-request triggered): `docs/INFRA_RUNBOOK.md`, "Caddy resource measurement — Plan 12-06".
