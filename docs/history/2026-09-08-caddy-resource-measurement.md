# Caddy resource measurement — Plan 12-06 (2026-09-08)

`caddy`'s `mem_limit` — absent entirely before this plan — is set here using the same
restart-ladder method as every other cap on this VPS, but under a materially different threat
model: this is the ONE container on this host that every request path traverses, so it is also
the ONE ladder in this phase whose failing rung takes the public edge down for both application
environments and Grafana simultaneously. This section records the full evidence, including the
certificate-safety stop condition this ladder is the first to actually exercise.

## Workload used to measure

One workload cycle, fired in order, per rung: (1) the repository's established 54-request burst
through the public HTTPS API against BOTH environments, (2) a full Grafana session (login, all
three dashboards loaded at the widest time range) through the third site block, (3) the
adversarial component — the actual point of this cap — repeated requests aimed at `/api/signin`
via the repository's own pinned `scripts/loadtest/run-rate-limit-verification.sh`, plus lighter manual
probes at intermediate rungs, (4) a settle of at least 20 seconds before the final reading.

**Coverage limit, stated honestly rather than implied:** the adversarial component was run from a
single sandboxed session and therefore covered only ONE distinct source address (egress IP
`185.24.11.165`). The rate limiter's memory driver grows only with DISTINCT `{remote_host}` keys
— one source address allocates exactly one key regardless of request volume — so this ladder does
NOT exercise the limiter's true attacker-influenced worst case. The adopted value's margin (below)
is the stated compensation for this measurement gap, not a substitute for actually measuring it.

## Iteration ladder

Ladder descended from a 256m Iteration-0 starting point (against a recorded ~20MiB idle baseline
already noted in the `redpanda` service's own comment):

| Cap | Pass/Fail | Steady/peak RSS | % of cap | `RestartCount` |
|---|---|---|---|---|
| 256m | ✓ | 18.74MiB | 7.3% | 0 |
| 128m | ✓ | 16.15MiB | 12.6% | 0 |
| 64m | ✓ | 15.4MiB | 24.1% | 0 |
| 32m (adopted) | ✓ | 16.27MiB (16.17MiB on independent re-verify) | 50.5–50.8% | 0 |
| 16m | ✓ | 11.34MiB (survived the full adversarial flood) | 70.9% | 0 |
| 8m | ✗ | — | — | crash-loop, `RestartCount=5` within ~1s |

Every passing rung was confirmed with: `RestartCount=0` sustained across the full
recreate+workload+20s-settle cycle (not a startup snapshot), all three site blocks serving HTTPS
with valid certificates, and both public health endpoints returning 200. The rate limiter's auth
zone was specifically re-confirmed still rejecting past its threshold at 256m, 16m, and 32m
(twice, including the final independent re-verification) via
`scripts/loadtest/run-rate-limit-verification.sh` — 6 real 429s on production, 0 on nonprod (the general
zone still admits) at the adopted 32m value.

Verbatim failure signal, 8m:
```
oom-kill:constraint=CONSTRAINT_MEMCG,...,task=caddy,pid=133865,uid=0
Memory cgroup out of memory: Killed process 133865 (caddy) total-vm:1306744kB, anon-rss:7348kB, file-rss:21436kB, shmem-rss:0kB, UID:0 pgtables:196kB oom_score_adj:0
```

## Adopted floor

**32m** — deliberately NOT the bare 16m passing floor, which cleared only ~41% margin above its
measured peak and sits one rung above the failing 8m. Headroom above the measured ~16.17MiB peak
RSS is ~15.8MiB (~98% margin). The entire 32m→16m gap is deliberate ATTACKER-INFLUENCE MARGIN, not
additional ladder evidence — compensation for the one-source-address coverage limit stated above,
not a substitute for measuring the true multi-attacker case. Independently re-verified from a
fresh `--force-recreate` through a full workload+20s-settle cycle with `RestartCount=0` sustained.
All three site blocks confirmed serving HTTPS with valid certificates at 32m (`openssl s_client`:
production cert expires 2026-11-14, nonprod 2026-11-16, monitoring 2026-12-06).

**Falsifier:** if the rate limiter's zones, keys or windows in the `Caddyfile` change, this
measurement's adversarial component no longer matches the mechanism it was designed to exercise,
and this cap should be re-derived rather than assumed to still hold.

## Step below the floor

**8m.** The kernel OOM-killed the `caddy` process within approximately one second of startup and
the container crash-looped (`RestartCount=5` observed before the descent was stopped). This is the
genuinely adjacent, disqualifying rung — 16m passed cleanly with real margin.

## Certificate safety during the descent

`/data` is a named volume, so every recreate in this ladder reused all three existing Let's
Encrypt certificates rather than re-requesting them — repeated re-requests trigger a week-long
Let's Encrypt rate-limit ban (T-05-12). This plan's own stop condition — descend immediately to
the last known-passing rung the instant a crash-loop is observed, never let it continue looping —
was exercised for real at the 8m rung: `RestartCount=5` was observed within about a second, the
descent was stopped immediately, and caddy was restored to 16m (then to the adopted 32m) without
any further recreate cycles at 8m. No certificate re-request was triggered — the stop happened
fast enough that the volume was never actually at risk — but this is the live proof that the stop
condition works as designed, not merely a documented precaution. A future reader repeating this
ladder should treat any `RestartCount` increment above 0 during a rung as an immediate stop
signal, not something to observe through a second cycle.

## Host coexistence

`free -m`, all THIRTEEN containers running, at the adopted 32m value, immediately after a workload
cycle:

```
               total        used        free      shared  buff/cache   available
Mem:            7945        3003         233          41        5047        4942
Swap:              0           0           0
```

`available: 4942MiB` (independently re-confirmed at 4942MiB by the orchestrator moments after the
ladder finished), comfortably clear of the 1024MiB gate used by every prior ladder on this box —
this reading also reflects plan 12-05's seven caps now deployed alongside this one. Both public
health endpoints returned 200 throughout every rung; `app`, `postgres` and `redpanda` — none of
which this plan touched — all held `RestartCount=0` the entire session, confirming the ladder's
brief live-edge interruptions on each recreate never propagated to the application containers
behind Caddy.

## Measurement date

2026-09-08.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
