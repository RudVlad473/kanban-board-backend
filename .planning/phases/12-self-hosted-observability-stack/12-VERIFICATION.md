---
phase: 12-self-hosted-observability-stack
verified: 2026-09-08T13:15:00Z
status: passed
score: 21/21 truths verified (2 via accepted override)
covered_files: [".planning/phases/12-self-hosted-observability-stack/12-01-PLAN.md", ".planning/phases/12-self-hosted-observability-stack/12-01-SUMMARY.md", ".planning/phases/12-self-hosted-observability-stack/12-02-PLAN.md", ".planning/phases/12-self-hosted-observability-stack/12-02-SUMMARY.md", ".planning/phases/12-self-hosted-observability-stack/12-03-PLAN.md", ".planning/phases/12-self-hosted-observability-stack/12-03-SUMMARY.md", ".planning/phases/12-self-hosted-observability-stack/12-04-PLAN.md", ".planning/phases/12-self-hosted-observability-stack/12-04-SUMMARY.md", ".planning/phases/12-self-hosted-observability-stack/12-05-PLAN.md", ".planning/phases/12-self-hosted-observability-stack/12-05-SUMMARY.md", ".planning/phases/12-self-hosted-observability-stack/12-06-PLAN.md", ".planning/phases/12-self-hosted-observability-stack/12-06-SUMMARY.md", ".planning/phases/12-self-hosted-observability-stack/12-CONTEXT.md", ".planning/todos/completed/2026-09-03-caddy-service-has-no-mem-limit.md", ".planning/todos/pending/2026-08-20-no-remote-log-shipping-structured-logging-or-alerting.md", ".planning/todos/pending/2026-09-08-cadvisor-grafana-and-caddy-mem-limits-need-a-longer-observation-window-re-ladder.md", "Caddyfile", "docker-compose.nonprod.yml", "docker-compose.prod.yml", "docker/grafana/provisioning/dashboards/dashboards.yaml", "docker/grafana/provisioning/dashboards/json/cadvisor.json", "docker/grafana/provisioning/dashboards/json/node-exporter-full.json", "docker/grafana/provisioning/dashboards/json/postgres-exporter.json", "docker/grafana/provisioning/datasources/datasources.yaml", "docker/loki/loki-config.yaml", "docker/prometheus/prometheus.yml", "docker/promtail/promtail-config.yaml", "docs/INFRA_ARCHITECTURE.md", "docs/INFRA_RUNBOOK.md", "docs/diagrams/infra-physical-deployment.mmd", "docs/diagrams/infra-physical-deployment.png"]
covered_digest: "v1:sha256:0c2bbcd07966f0d43e2cbcceed33b0eb2713ca92b9a718afa9dcd011062da0cd"
overrides_applied: 2
overrides:
  - must_have: "Grafana's admin password comes from .env.prod, and Grafana dashboards render real data through the authenticated UI (login-confirmed), not just via the underlying Prometheus/Loki APIs"
    reason: "Scoped by the phase's own must_haves as a UI-layer confirmation item, not a blocking automated check. Data-source-level flow for all three dashboards (node-exporter-full, cadvisor, postgres-exporter) has been independently confirmed FLOWING with real data across all three verification passes. The .env.prod GRAFANA_ADMIN_PASSWORD-vs-deployed-instance credential drift is a known, separately-tracked concern (unchanged across all three passes, no commit has touched it) that does not gate phase completion per explicit direction accompanying this final pass."
    accepted_by: "RudVlad473 (via task brief for this final verification pass)"
    accepted_at: "2026-09-08T13:15:00Z"
re_verification:
  previous_status: gaps_found
  previous_score: "19/21 truths fully verified, 1 partial (credential drift), 1 partial (caddy tracking gap)"
  gaps_closed:
    - "caddy's disclosed single-source-address adversarial-testing coverage gap had no follow-up tracking todo, unlike cadvisor/grafana — RESOLVED: commit 855dd6b renamed and extended the pending todo to explicitly cover caddy's 32m cap, distinguishing its disclosed-gap/no-live-instability status from cadvisor/grafana's active-incident status, and recommending the same longer-observation-window re-ladder plus a genuinely multi-source-address adversarial test"
  gaps_remaining: []
  regressions: []
deferred: []
advisory: []
human_verification:
  - test: "Log into https://<monitoring-hostname>/login with the admin credential currently recorded in .env.prod on the VM, and confirm the three provisioned dashboards (node-exporter-full, cadvisor, postgres-exporter) render real data through the authenticated UI, not just via the underlying Prometheus/Loki APIs."
    expected: "Login succeeds and each dashboard's panels show live data (as already confirmed at the data-source level in this report)."
    why_human: "12-05-SUMMARY.md discloses the current .env.prod GRAFANA_ADMIN_PASSWORD may not match the deployed Grafana instance's actual persisted admin account; the verifier cannot access or test the real credential value. UNCHANGED across all three passes. Accepted as override (see overrides above) — informational only, does not block phase completion."
---

# Phase 12: Self-hosted observability stack Verification Report

**Phase Goal:** Metrics (CPU/memory/disk, per-container and host-level) and log aggregation are
queryable without any paid SaaS — Prometheus + Grafana + Loki/Promtail + cAdvisor + node_exporter
running as additional containers on the existing Netcup VPS, retention and `mem_limit`s sized
against measured headroom, with the Grafana UI reachable only through Caddy.

**Verified:** 2026-09-08T13:15:00Z (third pass — independent live SSH re-inspection of
netcup-prod, alongside static codebase/git-diff review)
**Status:** passed
**Re-verification:** Yes — final pass, closing the one remaining tracking gap from the prior
(second) pass

## Re-Verification Summary

The prior pass found `cadvisor` and `grafana` fixed and stable (commit `9d13e77`), but flagged one
remaining gap: `caddy`'s own disclosed single-source-address adversarial-testing coverage
limitation had no follow-up tracking todo, unlike `cadvisor`/`grafana`, which each got one via a
new pending todo filed same-day.

This gap is now closed. Commit `855dd6b` (docs-only, `.planning/todos/pending/` rename +
extension, no compose/runbook/Caddyfile changes) renamed
`2026-09-08-cadvisor-and-grafana-need-a-longer-observation-window-re-ladder.md` to
`2026-09-08-cadvisor-grafana-and-caddy-mem-limits-need-a-longer-observation-window-re-ladder.md`
and added a new "Also in scope: caddy's adopted 32m cap" section that:

- Explicitly names caddy's disclosed single-source-address adversarial-coverage limitation
- Distinguishes caddy's status (no live instability, `RestartCount=0`, stable under real traffic)
  from cadvisor/grafana's status at time of filing (actively OOM-looping, an active incident)
- Recommends the same longer-observation-window re-ladder, plus — as an addition specific to
  caddy — a genuinely multi-source-address adversarial test, closing exactly the coverage gap
  12-06's own ladder evidence disclosed
- Is filed correctly in `pending/`, not silently absorbed into a "resolved" claim

Read in full; it adequately covers caddy — the file rename (confirmed via `git show --stat
855dd6b`) shows the old filename gone and the new one present, and the diff is additive prose only
(18 insertions, 1 deletion — the frontmatter `title` line), no scope narrowing.

### Live Spot-Check (this pass)

Independent SSH re-inspection of `netcup-prod`, cross-checking against the exact container ids
confirmed in the prior pass:

| Check | Result |
|---|---|
| `caddy` container id | `d910ad21d334` — SAME as prior pass, `RestartCount=0` |
| `cadvisor` container id | `3e5756a0a01c` — SAME as prior pass, `RestartCount=0` |
| `grafana` container id | `8c66d26d8a9b` — SAME as prior pass, `RestartCount=0` |
| Deployed `mem_limit`s | caddy 32m, cadvisor 128m, grafana 768m — unchanged, matches repo `docker-compose.prod.yml` |
| dmesg OOM entries for these 3 containers | 16 total entries found; ALL predate the current containers' `Created` timestamps once monotonic-to-wall-clock skew (~2h, confirmed via `/proc/uptime` vs `date -u`) is corrected for — every single one belongs to the OLD, pre-fix/pre-recreate container instances. Zero new OOM events since the current containers started. |
| Public app health | `https://kanban-board-rud-vlad-473.duckdns.org/api/actuator/health` → `200` |
| Public monitoring login | `https://kanban-board-rud-vlad-473-monitoring.duckdns.org/login` → `200` |
| caddy activity in the observation window | Real traffic present (rate-limiter actively firing against `remote_ip 185.24.11.165` in recent logs) — not an idle/untested container, genuine production load with zero further instability |

**Methodological note:** `dmesg -T`'s wall-clock timestamps on this host are computed from a
monotonic clock that has drifted ~2 hours ahead of the corrected system wall clock (confirmed:
`/proc/uptime` = 176884s vs. `date -u` elapsed-since-`who -b` ≈ 169680s, a ~7200s/2h gap). Raw
`dmesg` (monotonic, uncorrected) timestamps were cross-referenced against `docker inspect
--format={{.Created}}` (wall-clock) using a computed `boot_wall_epoch = now_wall_epoch -
now_monotonic_seconds` offset, converting each OOM entry to an estimated wall-clock time. All 16
entries convert to times strictly before their respective container's `Created` timestamp (e.g.,
caddy's last OOM burst → ~10:32:33–10:32:41 UTC, vs. `Created: 2026-09-08T10:33:10Z`; cadvisor's
last OOM → ~10:58:23 UTC, vs. `Created: 2026-09-08T10:58:40Z`). This confirms no regression — a
naive `dmesg -T | grep OOM` alone would have shown apparently-future-dated entries and could be
misread as new incidents; the cross-check rules that out.

**No regressions found.** All three containers remain healthy, stable, and unchanged from the
prior pass's confirmed-good state.

## Goal Achievement

### Observable Truths

Truths 1–17, 19, 20 are unchanged from the prior pass (re-confirmed at the live-evidence level in
this pass's spot-check where in scope; otherwise carried forward as no commit touched their
underlying wiring — `git diff --stat 1abe19a..HEAD` remains scoped to
`docker-compose.prod.yml` + `docs/INFRA_RUNBOOK.md` + the renamed todo, confirmed unchanged again
this pass). Only truths 5, 17, 18, and 21 are discussed in detail below since they are the ones
whose status changed or were re-examined.

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1–4, 6–16, 19, 20 | (see prior pass, `12-VERIFICATION.md` history) | ✓ VERIFIED (carried forward, unchanged) | No commit since the prior pass touched Prometheus/Loki/Promtail/Caddyfile config, retention, network topology, mounts, or the architecture doc; live spot-check this pass (health endpoints, container roster, mem_limits) shows no drift |
| 5 | Grafana's admin password comes from `.env.prod`; anonymous access and self sign-up disabled; dashboards render through the authenticated UI | ✓ PASSED (override) | `GF_AUTH_ANONYMOUS_ENABLED=false`, `GF_USERS_ALLOW_SIGN_UP=false`, `GF_SECURITY_ADMIN_PASSWORD` set (config-level, unchanged). UI-level login confirmation remains blocked by the disclosed credential-drift; accepted as override — see frontmatter |
| 17 | Dashboards render real data, not "No data"/error panels | ✓ PASSED (override) | Data-source level FLOWING confirmed for all 3 dashboards (Prometheus/Loki APIs); UI-level render blocked by the same credential drift as truth 5, same override applies |
| 18 | cadvisor, grafana AND caddy `mem_limit` caps are stable under real production load | ✓ VERIFIED | cadvisor (128m) and grafana (768m): live-confirmed `RestartCount=0`, zero new dmesg OOM entries since fix, unchanged from prior pass. caddy (32m): live-confirmed `RestartCount=0`, zero new dmesg OOM entries since its own recreate, genuine real production traffic observed hitting its rate limiter with no further instability. All three now carry parity tracking via the same pending todo for a longer-window/multi-source-address re-ladder — mirroring the exact precedent already accepted for cadvisor/grafana in the prior pass (a todo-tracked residual-uncertainty item is informational, not blocking, once live stability is independently confirmed) |
| 21 | Both folded todos in their honest state; new fix disclosed honestly, not silently absorbed (D-01/D-02) | ✓ VERIFIED | `2026-09-03-caddy-...md` still in `completed/`; `2026-08-20-...md` still in `pending/`; the re-ladder todo now correctly covers all THREE containers (`2026-09-08-cadvisor-grafana-and-caddy-mem-limits-need-a-longer-observation-window-re-ladder.md`), with caddy's section honestly distinguishing "disclosed-gap, no live instability" from cadvisor/grafana's "active-incident" framing — no overclaiming, no silent gap |

**Score:** 21/21 truths verified (19 carried forward + re-confirmed, 2 accepted via override).

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `docker-compose.prod.yml` — 7 observability service blocks | present, wired, measured caps | ✓ VERIFIED | caddy 32m, cadvisor 128m, grafana 768m confirmed live and unchanged; matches repo exactly |
| `docs/INFRA_RUNBOOK.md` — measurement sections + addendum | present | ✓ VERIFIED (unchanged, carried forward) | No commit since prior pass touched this file |
| `.planning/todos/pending/2026-09-08-cadvisor-grafana-and-caddy-mem-limits-need-a-longer-observation-window-re-ladder.md` | present, covers all 3 containers including caddy's distinct disclosed-gap framing | ✓ VERIFIED | Read in full this pass; confirmed adequate — see Re-Verification Summary |
| All other Phase 12 artifacts (Prometheus config, Grafana datasources/dashboards, Caddyfile, Loki/Promtail config, architecture doc) | present, unchanged | ✓ VERIFIED | `git diff` confirms none of these files changed since the prior pass |

### Key Link Verification

Unchanged from the prior pass. No wiring was touched by commit `855dd6b` (todo-file rename/
extension only, zero compose/config/code changes). Live spot-check this pass re-confirms Caddy→
Grafana, Grafana→Prometheus/Loki, and the public health/login endpoints all resolve correctly.

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| caddy/cadvisor/grafana container ids unchanged from prior pass | `docker ps --format` | Same 3 ids as prior pass | ✓ PASS |
| `RestartCount=0` for all 3 | `docker inspect --format={{.RestartCount}}` | `0` / `0` / `0` | ✓ PASS |
| `mem_limit`s unchanged and match repo | `docker inspect --format={{.HostConfig.Memory}}` | `33554432` / `134217728` / `805306368` | ✓ PASS |
| No new OOM events since current containers started | dmesg (monotonic) cross-referenced against `Created` via computed clock-skew offset | All 16 entries predate their container's `Created` timestamp | ✓ PASS |
| Public app health endpoint | `curl .../api/actuator/health` | `200` | ✓ PASS |
| Public monitoring login endpoint | `curl .../login` | `200` | ✓ PASS |
| Old todo filename gone, new filename present | `ls` both paths | old: not found; new: found | ✓ PASS |
| Todo-rename commit is docs-only | `git show --stat 855dd6b` | 1 file changed (the todo), 18 insertions/1 deletion | ✓ PASS |

### Requirements Coverage

| Requirement | Status | Evidence |
|-------------|--------|----------|
| D-01 | ✓ SATISFIED (unchanged) | Todos annotated honestly; logs queryable |
| D-02 | ✓ SATISFIED | mem_limit mechanism present and live-stable for all 11 capped services including caddy; residual longer-window/multi-source-address re-ladder now tracked via todo for all three affected containers, mirroring the accepted precedent from the prior pass |
| D-03 | ✓ SATISFIED (override) | Mechanism correct; live credential drift accepted as override — see frontmatter |
| D-04 | ✓ SATISFIED (unchanged) | Single instances, all env-labeled targets UP including nonprod |
| D-05 | ✓ SATISFIED (unchanged) | Mounts/privilege posture correct; cadvisor's cap fixed and stable |
| D-06 | ✓ SATISFIED (unchanged) | Least-privilege role confirmed; both brokers UP |
| D-07 | ✓ SATISFIED (unchanged) | 30-day retention confirmed |
| D-08 | ✓ SATISFIED (unchanged) | All 13 containers, both Compose projects, confirmed in Loki |

### Anti-Patterns Found

None found in the diff scope for this pass (todo-file rename/extension only — no TBD/FIXME/XXX,
no placeholder returns, no debt markers). The added todo section is transparent about caddy's
disclosed-gap-vs-active-incident distinction, consistent with this project's disclosure
conventions.

### Human Verification Required

See frontmatter `human_verification`. One item remains, unchanged in kind from both prior passes:
the Grafana admin-credential drift blocks an authenticated UI confirmation of dashboard rendering.
This is accepted as an override per the explicit direction accompanying this final pass — it is a
UI-layer confirmation item, already covered at the data-source level, and does not gate phase
completion. It remains listed here for transparency, not as a blocker.

The caddy longer-observation-window item from the prior pass is no longer a human-verification
blocker: it is now properly tracked as ordinary follow-up work via the pending todo, exactly
mirroring how cadvisor/grafana's own residual re-ladder uncertainty was already accepted as
non-blocking once live stability was independently confirmed.

### Gaps Summary

None remaining. The one gap identified in the prior pass — caddy's disclosed adversarial-testing
coverage limitation lacking follow-up tracking parity with cadvisor/grafana — is closed: commit
`855dd6b` extends the existing pending todo to explicitly and adequately cover caddy, honestly
distinguishing its disclosed-gap/no-live-instability status from cadvisor/grafana's prior
active-incident status. This pass's independent live spot-check confirms no regression: all three
containers remain at their previously-verified `mem_limit`s, `RestartCount=0`, no new OOM events
since their respective recreates (confirmed via a monotonic-clock-skew-corrected dmesg
cross-reference against each container's `Created` timestamp), and both public health/login
endpoints return `200`. The phase goal is achieved.

---

_Verified: 2026-09-08T13:15:00Z (third/final pass, independent live SSH inspection of
netcup-prod)_
_Verifier: Claude (gsd-verifier)_
