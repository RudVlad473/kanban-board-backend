# Decommission Record — Plan 11-06 (2026-08-26)

The managed provider (Neon) was deleted after the self-hosted instance was confirmed working
end-to-end, per D-07's explicit gate. This section records what was checked before deletion, what
was deleted, and what is honestly no longer recoverable.

## Gate verified live, before the decision was presented (D-07)

All five conditions were checked live in this session, not inferred from earlier plan summaries:
production health `200`; nonprod health `200`; both databases at `8`/`8` successful Flyway
migrations; the latest default-branch GitHub Actions run (`32985965535`, plan 11-05) fully green,
including both Flyway verification jobs; and neither GitHub Environment's database secrets/variables
nor either VM `.env` file resolved to the provider's hostname (`DB_HOST` in both was already
`postgres`; the only remaining `DB_*` values pointed at the self-hosted names/roles).

## Decision (D-07)

**Operator's verbatim answer:** "Delete the project now (recommended per locked D-07)" — presented
with the one-way consequence stated explicitly (re-adopting the provider later means provisioning
entirely from scratch: new project, new endpoint, new credentials, nothing carried across) and the
context that the provider's data was already abandoned (D-04) and its compute was quota-blocked
until 2026-09-01 regardless, so keeping it dormant would not have restored a usable fallback.

## Part A — what was deleted

| Field | Value |
|---|---|
| Project name | `kanban-board-db` |
| Project ID | `floral-union-23715140` |
| Organization | Rudenko Vladimir (`org-red-moon-37279582`) |
| Region | `aws-eu-central-1` (Frankfurt) |
| Postgres version | 18 |
| Compute (production branch) | `ep-delicate-bird-b2lni8pr`, autoscaling 0.25-2 CU |
| Branches | `production` (`br-divine-waterfall-b2864di0`, primary, created 2026-08-14) and `nonprod` (`br-still-shadow-b2r7ez0l`, created 2026-08-18) |

Deletion date: 2026-08-26. Confirmed via two independent checks against Neon's own control-plane
API: `list_projects` searched for the project name/id returned an empty result set, and a direct
`describe_project` call against the same project ID returned `HTTP 404`.

**A methodological finding, recorded because it corrects an assumption this plan's own text made:**
the plan's acceptance criteria expected a real Postgres-protocol connection attempt against the
former endpoint to fail "at name resolution or connection, not at authentication." Tested live: a
`psql` connection to the former direct host, with a deliberately wrong password, returned `ERROR:
password authentication failed for user 'kanban_prod_app'` — the auth-failure shape the plan warned
against. A control test against a hostname that had **never existed** (`ep-totally-fake-nonexistent-xyz123...`)
produced the byte-identical error. This proves Neon's edge proxy does not distinguish "wrong
password" from "endpoint does not exist" at the connection-protocol level — an anti-enumeration
design choice, the same principle this repository's own `ResetController` applies to its reset
token header. The control-plane API result (empty list + `404`), not the connection-protocol
behavior, is the authoritative evidence of deletion here.

## Part B — credential revocation

Checked and confirmed absent: no `neonctl`/`neon` CLI binary installed on the operator's machine, no
Neon CLI config files, no `NEON_API_KEY`/`NEON_TOKEN` reference in any local shell profile. The
repository- and CI-facing surface (GitHub Environment secrets/variables, both VM `.env` files) was
already confirmed clean by the D-07 gate check above, before deletion. Nothing was found to revoke.

## Part C — what was verified unaffected

Both public HTTPS health endpoints returned `200` immediately after the deletion (re-checked, not
assumed from the pre-deletion gate). Neither GitHub Environment nor either VM `.env` file was
touched by this task — the check above confirms none of them still referenced the provider, and
none needed editing as a result of the deletion itself.

## Part D — what is NOT recoverable

Every row abandoned under D-04's fresh-start decision (plan 11-02) — every board, task, subtask,
user account and activity-log entry the managed provider held — no longer exists anywhere, in any
form. Re-adopting Neon, or any managed provider, from this point forward means provisioning a new
project from scratch; nothing about the deleted project (its id, its branches, its data) carries
across.

## Decommission date

2026-08-26.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
