# Phase 8: Isolated Nonprod Environment, Live and Resettable - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-18
**Phase:** 8-Isolated Nonprod Environment, Live and Resettable
**Areas discussed:** Reset endpoint auth, Reset target state, Neon branch data, Nonprod hostname, CORS placeholder origin, Fallback-VPS cost gate

---

## Reset endpoint auth

| Option | Description | Selected |
|--------|-------------|----------|
| Shared-secret header | Nonprod-only env var (e.g. RESET_TOKEN) checked against a request header | ✓ |
| Existing session auth | Require a signed-in nonprod user, reusing Spring Security's session mechanism | |
| No auth (hostname obscurity) | Rely on the DuckDNS hostname being unlisted | |
| IP allowlist at Caddy | Restrict at the reverse-proxy layer to known IPs | |

**User's choice:** Shared-secret header
**Notes:** None

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, profile-gated | Controller/bean only registers under a nonprod-only Spring profile | ✓ |
| No, just auth-gated | Same code runs everywhere, protected only by never configuring the secret in prod | |

**User's choice:** Yes, profile-gated
**Notes:** None

---

## Reset target state

| Option | Description | Selected |
|--------|-------------|----------|
| Seeded fixture | Truncate then reseed a known baseline (demo user + board) | |
| Genuinely empty | Truncate to zero rows, no reseed | ✓ |

**User's choice:** Genuinely empty
**Notes:** The follow-up question about whether a seeded fixture should also produce Kafka/activity-log entries was moot once "Genuinely empty" was chosen — user's answer was "no fixture."

---

## Neon branch data

| Option | Description | Selected |
|--------|-------------|----------|
| Schema-only / empty | Branch created empty, structure only | ✓ |
| Full copy of production data | Neon branching copies production's actual rows | |

**User's choice:** Schema-only / empty
**Notes:** Consistent with the Reset target state decision above.

---

## Nonprod hostname

| Option | Description | Selected |
|--------|-------------|----------|
| -nonprod suffix | kanban-board-rud-vlad-473-nonprod.duckdns.org | ✓ |
| -staging suffix | kanban-board-rud-vlad-473-staging.duckdns.org | |
| Something else | Freeform | |

**User's choice:** -nonprod suffix
**Notes:** Matches the milestone's own "nonprod" terminology.

---

## CORS placeholder origin

| Option | Description | Selected |
|--------|-------------|----------|
| Local dev ports | http://localhost:5173,http://localhost:3000 — matches CorsConfig.java's existing default | ✓ |
| A guessed future frontend URL | e.g. https://kanban-board-frontend.vercel.app | |
| Something else | Freeform | |

**User's choice:** Local dev ports
**Notes:** None

---

## Fallback-VPS cost gate

| Option | Description | Selected |
|--------|-------------|----------|
| Stop and ask first | Pause and report if colocation measurement fails; user approves the new recurring cost before anything is provisioned | ✓ |
| Provision automatically | Provision the fallback VPS without waiting for a check-in | |

**User's choice:** Stop and ask first
**Notes:** Flagged in CONTEXT.md with a `one-way` reversibility rating so the planner inserts a `checkpoint:decision` before any task that would provision the fallback VPS.

---

## Claude's Discretion

- Exact directory/Compose-project-name/container-name/network-name/volume-name choices for the nonprod stack (must differ from production per research Pitfall 1, specific names left to Claude)
- Iteration procedure and starting values for the Redpanda memory-floor live-measurement pass
- Exact shape/storage location of the RESET_TOKEN value

## Deferred Ideas

None — discussion stayed within Phase 8 scope. CI automation and GitHub Environments (Phase 9) and the 8 hardening todos (Phase 10) were not discussed here, as they belong to later phases.
