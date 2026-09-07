# Phase 12: Self-hosted observability stack - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-09-07
**Phase:** 12-self-hosted-observability-stack
**Areas discussed:** Todo folding, Grafana exposure/auth, monitoring scope (prod vs prod+nonprod), cAdvisor host access, retention window, log scope, DB/broker exporter coverage

---

## Todo folding

| Todo | Selected |
|------|----------|
| No remote log shipping / no alerting (2026-08-20) | ✓ folded |
| Caddy has no mem_limit (2026-09-03) | ✓ folded |

**User's choice:** Both folded.
**Notes:** The log-shipping todo is the one this phase was scoped from; the Caddy mem_limit todo was opportunistic since this phase already edits that service block.

---

## Grafana exposure & auth

| Option | Description | Selected |
|--------|-------------|----------|
| Grafana login only | Reverse-proxy to Grafana's own auth, no extra Caddy-layer gate | ✓ |
| Caddy basic_auth in front | Second credential prompt ahead of Grafana's own login | |
| IP allowlist (your IP only) | Caddy only forwards from specific source IPs | |

**User's choice:** Grafana login only (recommended option).
**Notes:** Matches how the app's own endpoints rely on Spring Security alone rather than a Caddy-level gate.

---

## Monitoring scope

| Option | Description | Selected |
|--------|-------------|----------|
| Production + nonprod, one shared stack | Single Prometheus/Loki/Grafana stack, labeled by env | ✓ |
| Production only | Nonprod stays unmonitored for now | |

**User's choice:** Production + nonprod, one shared stack (recommended option).
**Notes:** Mirrors Phase 11's shared-Postgres-instance precedent (D-01 there).

---

## cAdvisor host access

| Option | Description | Selected |
|--------|-------------|----------|
| Include cAdvisor, read-only mounts | Full per-container breakdown via read-only docker.sock/sys/proc mounts | ✓ |
| Host-level only (node_exporter, no cAdvisor) | Skip the mounts entirely, host-wide metrics only | |

**User's choice:** Include cAdvisor, read-only mounts (recommended option).
**Notes:** Accepted as a scoped, known exception — tracked separately from the existing open todo about the app container running as root.

---

## Retention window

| Option | Description | Selected |
|--------|-------------|----------|
| 15 days | Prometheus's own default | |
| 30 days | One full month, still trivial against 222GB free | ✓ |
| 7 days | Tightest window | |

**User's choice:** 30 days.
**Notes:** User chose the larger window over the recommended 15-day default — disk headroom made this an easy call.

---

## Log scope

| Option | Description | Selected |
|--------|-------------|----------|
| All containers | Promtail scrapes every container's Docker json-file log | ✓ |
| App containers only | Just the two app containers | |

**User's choice:** All containers (recommended option).
**Notes:** Covers exactly the infra-incident scenario (Postgres/Redpanda/Caddy logs during an outage) that motivated this phase.

---

## DB/broker metrics

| Option | Description | Selected |
|--------|-------------|----------|
| Host + container only, defer DB/broker internals | node_exporter + cAdvisor only | |
| Include postgres_exporter + Redpanda's native metrics now | Adds query/replication-lag-style visibility now | ✓ |

**User's choice:** Include postgres_exporter + Redpanda's native metrics now.
**Notes:** User chose the broader option over the recommended narrower one — decided not to defer DB/broker-internal visibility to a follow-up.

---

## Wrap-up check

**User's choice:** "Enough — write CONTEXT.md" (recommended option) — no further areas raised.

## Claude's Discretion

- Compose file topology (single `docker-compose.prod.yml` extension vs. a separate `docker-compose.monitoring.yml`)
- Dashboard provisioning mechanism and which community dashboards to import
- Exact container/service naming, network placement, and `mem_limit` values (sized via live restart-ladder measurement, per Phase 11's precedent)

## Deferred Ideas

- Alerting (Alertmanager, notification channels) — explicit out-of-scope boundary for this phase
- Structured/UTC logging format standard across application log statements — other half of the folded log-shipping todo, application-level not infra
- Container-hardening (non-root `USER` directive) — pre-existing unrelated todo, noted only because cAdvisor's mounts are adjacent territory
