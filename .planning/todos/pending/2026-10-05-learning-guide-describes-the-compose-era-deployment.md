---
created: 2026-10-06T00:00:00.000Z
title: "Learning chapters 00, 05, 06, 07, 08, 09, 10 and 11 still describe the Docker Compose / Caddy / Promtail deployment"
area: docs
severity: minor
files:

  - docs/learning/00-README.md
  - docs/learning/05-api-layer.md
  - docs/learning/06-security-and-sessions.md
  - docs/learning/07-events-and-activity-feed.md
  - docs/learning/08-testing-strategy.md
  - docs/learning/09-build-quality-and-ci.md
  - docs/learning/10-infrastructure-and-deployment.md
  - docs/learning/11-observability.md
---

## Problem

The k3s cutover (2026-09-26) replaced the Docker Compose deployment, the Caddy edge and Promtail, but
most of the learning guide's prose still describes them. Quick task 261005-sun only dealt with the
diagrams: it dropped the five inline diagrams that drew the removed topology (06 Caddy rate limit,
09 CI job graph, 10 Compose networks, 10 Compose deploy sequence, 11 Compose observability) and
replaced each with one sentence pointing at the current PNG. It left the prose around them alone,
because rewriting a chapter is a separate seam.

Per-chapter mention counts, measured 2026-10-06 with word-boundary, case-insensitive matches over
`docs/learning/*.md` (`\bcaddy\b|\bCaddyfile\b`, `\bdocker[ -]compose\b|\bcompose\b`, `\bpromtail\b`,
and the literal `deploy-to-netcup`):

| Chapter | Caddy | Compose | Promtail | `deploy-to-netcup` |
|---|---|---|---|---|
| 00 README | 20 | 8 | 4 | 0 |
| 05 API layer | 1 | 7 | 0 | 0 |
| 06 Security and sessions | 39 | 5 | 0 | 0 |
| 07 Events and activity feed | 0 | 8 | 0 | 1 |
| 08 Testing strategy | 10 | 2 | 0 | 0 |
| 09 Build quality and CI | 40 | 22 | 0 | 4 |
| 10 Infrastructure and deployment | 77 | 95 | 1 | 3 |
| 11 Observability | 30 | 50 | 37 | 0 |

Chapter 01's 9 Compose matches are the local-development `docker-compose.yml`, which still exists, so
they are not stale. Chapters 02, 03 and 04 have none.

`Caddyfile`, `docker/` and `docker-compose.prod.yml` no longer exist, so every line-anchored link into
them is dead as well as stale.

## Solution

Rewrite each affected section against the live manifests (`k8s/`, `.github/workflows/deploy.yml`) in
the chapter's own six-part shape, one chapter per commit. Start with 10 and 11, which carry about
80% of the matches. Replace the one-sentence pointers left by 261005-sun with prose, and decide per
chapter whether the section should be rewritten or deleted as history (`docs/history/` already holds
the cutover account). The pointers to find: `rg -n 'replaced on 2026-09-26' docs/learning`.

Chapter 00's decision tables (SEC-20, INFRA-04 to INFRA-09, OBS-03 to OBS-21, CI-26) record decisions
about the removed stack. Mark each as reversed or superseded rather than deleting it, because the
guide's convention is to keep reversed decisions visible.
