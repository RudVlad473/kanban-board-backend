---
phase: 05-infra-migration
plan: 03
subsystem: infra
tags: [netcup, oracle-pivot, neon, duckdns, mcp, arm64, firewall]

# Dependency graph
requires:
  - phase: 05-infra-migration (plan 05-02, wave 1)
    provides: "docker-compose.prod.yml, Caddyfile with {$APP_DOMAIN} placeholder, .env.prod.example, arm64 buildx CI target"
provides:
  - "Provisioned, hardened Netcup production VM (159.195.114.230, Vienna) replacing the capacity-constrained Oracle Cloud target"
  - "Neon Postgres project (kanban-board-db, aws-eu-central-1/Frankfurt), empty database, pooled + direct endpoints identified"
  - "DuckDNS hostname (kanban-board-rud-vlad-473.duckdns.org) resolving to the VM, verified via two independent resolvers"
  - "docs/INFRA_RUNBOOK.md — full non-secret record of provider, access, firewall, database, and DNS state"
  - "linux/amd64 deploy.yml build target (was arm64, an Oracle-era leftover that would have silently broken deploy)"
affects: [05-04-tracer-deploy, 05-05-cicd-pipeline, 05-06-external-audit]

# Actuals
actuals:
  tokens: unknown (spans 3 non-contiguous sessions; per-session token accounting not captured)
  tasks: 3
  commits: 4

# Tech tracking
tech-stack:
  added: [neon-mcp (user-scope, OAuth), duckdns]
  removed: []
  patterns:
    - "When a locked provisioning decision (Oracle region/shape/IP) is invalidated by external reality (structural capacity shortage), replace it procedurally — screen alternatives against the same constraints, pick, document the rationale and the screened-out options — rather than stalling the phase waiting for the original target to become available."
    - "A newly available MCP tool that COULD return a credential-bearing value (Neon's get_connection_string) is treated as the same never-cross boundary as direct console/credential access, even though nothing prevents calling it — verify state (region, emptiness, host-name shape) through the tool, but leave secret retrieval to the human, exactly as the plan's original D-02 guided-execution model intended before this tool existed."

key-files:
  created:
    - .planning/phases/05-infra-migration/05-03-SUMMARY.md
  modified:
    - docs/INFRA_RUNBOOK.md
    - .planning/ROADMAP.md
    - .planning/WINDOWS.md
    - .github/workflows/deploy.yml (prior session, this plan's Task 2)
    - docker-compose.prod.yml (prior session, this plan's Task 2 — stale Oracle comments corrected)
  deleted:
    - .planning/HANDOFF.json (one-shot resumption artifact, no longer needed once the plan closes)
    - .planning/phases/05-infra-migration/.continue-here.md (mid-plan checkpoint, superseded by this SUMMARY)

key-decisions:
  - "Pivoted the production hosting target from Oracle Cloud (eu-zurich-1 A1.Flex) to Netcup VPS Lite 2 G12s after 200+ automated provisioning attempts across 10+ hours confirmed the capacity shortage was structural, not transient. Screened and rejected AWS (this project's own billing-risk history — see PROJECT.md Context on the AWS EC2/RDS deletion), GCP/Azure free tiers (region-restricted/time-limited), and Hetzner CX33 (unavailable everywhere checked) before selecting Netcup (4 vCPU/8GB/160GB, hourly billing, no metered-billing risk)."
  - "Netcup's network model is genuinely two layers (OS-level iptables + Netcup Cloud Firewall), not OCI's three (Security List, NSG, OS firewall) that INFRA-08's requirement text and this plan's own must-haves were written against. The two-layer model was configured and externally verified (22 open; 80/443/8080/8081/9092 closed) to the same effective standard, but the requirement's literal wording is now stale — filed as WINDOWS.md ledger entry #2 rather than silently reworded, since fixing REQUIREMENTS.md's prose is out of this task's scope and belongs with 05-06 (INFRA-08's actual owning/verifying plan)."
  - "Fixed .github/workflows/deploy.yml's Docker build platform from linux/arm64 to linux/amd64, dropped the now-unneeded QEMU cross-compile step — confirmed via uname -a that Netcup's VM is x86_64, not Oracle's ARM64/Ampere. A genuine latent bug that would have silently broken 05-04/05-05's deploy if not caught during this plan's own re-validation pass."
  - "Hardened SSH beyond the plan's literal branch coverage — disabled password auth unconditionally (PasswordAuthentication no, PermitRootLogin prohibit-password) even though key-based access was already confirmed working. Leaving password auth enabled on the one internet-facing SSH-adjacent port was a real exposure the plan's spirit clearly intended to close."
  - "Neon project region: aws-eu-central-1 (Frankfurt) — the closest Neon-offered region to the Netcup VM's actual Vienna datacenter. The original Oracle-era plan text assumed Zurich (Oracle's own region); that assumption doesn't survive the provider pivot and was corrected here rather than carried forward unexamined."
  - "Installed the Neon MCP server (hosted OAuth transport, user scope) mid-session at the operator's request, and used it to verify project existence/region/empty-database state and to look up the pooled/direct host-name shapes — but deliberately did NOT call get_connection_string, which would have put a password-bearing connection string directly into the conversation transcript. The human retrieved both connection strings from the Neon console directly instead, preserving the plan's original 'no credential passes through the executor' guarantee even with a new tool capable of breaking it."
  - "DuckDNS subdomain: kanban-board-rud-vlad-473.duckdns.org, A record -> 159.195.114.230. No dynamic-DNS updater/cron/token is running — the VM's IP is static for this deployment's lifetime, so DuckDNS is used purely for a free, cert-eligible hostname, not for its namesake dynamic-update capability. If the VM is ever re-provisioned with a new IP, the record must be updated manually."

patterns-established:
  - "A plan's must-haves written against one provider's specific model (OCI's 3-layer network) do not automatically invalidate on a provider pivot — the intent (multi-layer, externally-verified, minimal exposure) can still be met and verified; the literal wording gap gets logged (WINDOWS.md), not silently patched over or silently ignored."

requirements-completed: []

coverage:
  - id: T1
    description: "The VM's public IP is stable for the deployment's lifetime (Netcup has no OCI-style ephemeral/Reserved IP distinction — the VPS is assigned one persistent IP), or the fallback and its consequences are recorded as an explicit accepted decision."
    requirement: "INFRA-01 (prerequisite)"
    verification:
      - kind: other
        ref: "docs/INFRA_RUNBOOK.md records 159.195.114.230 as the VM's persistent public IPv4; DuckDNS section records the static-IP assumption and the manual-update consequence if the VM is ever re-provisioned"
        status: pass
    human_judgment: false
  - id: T2
    description: "The tenancy's actual CPU/RAM allocation is read from the provider and written down, so plan 05-02's Redpanda caps are confirmed or corrected against a verified number."
    requirement: "INFRA-03 (cross-reference)"
    verification:
      - kind: other
        ref: "docs/INFRA_RUNBOOK.md: 4 vCPU / 7.8 GiB RAM measured on the VM; docker-compose.prod.yml's Redpanda comment updated (prior session) to reflect the measured Netcup budget rather than the original Oracle 12GB figure"
        status: pass
    human_judgment: false
  - id: T3
    description: "The firewall rules survive a VM reboot and a Docker daemon restart."
    requirement: "INFRA-08"
    verification:
      - kind: other
        ref: "docs/INFRA_RUNBOOK.md: iptables rules persisted via netfilter-persistent, verified to survive a full reboot (prior session, Task 2)"
        status: pass
    human_judgment: false
  - id: T4
    description: "All three OCI network layers (Security List, NSG, OS firewall) are configured and each layer's rule set recorded."
    requirement: "INFRA-08"
    verification:
      - kind: other
        ref: "NOT MET AS LITERALLY STATED — Netcup has no Security List/NSG equivalent; its model is two layers (OS iptables + Netcup Cloud Firewall), both configured and recorded in docs/INFRA_RUNBOOK.md. Logged as WINDOWS.md ledger entry #2 (open) rather than silently marked pass, since the requirement's own wording still names OCI-specific layers."
        status: adapted
    human_judgment: true
    rationale: "The underlying intent (multiple independent layers, each consciously configured, externally verified) is satisfied by Netcup's two-layer model at the same effective rigor the plan asked for. The literal 'three layers' language is a casualty of the provider pivot, not a shortcut taken here — flagged rather than either silently claimed as pass or silently reworded away."
  - id: T5
    description: "A free subdomain resolves by DNS A record to the VM's public IP."
    requirement: "INFRA-04 (prerequisite)"
    verification:
      - kind: other
        ref: "nslookup against both the local resolver and Google public DNS (8.8.8.8) this session — both return 159.195.114.230 for kanban-board-rud-vlad-473.duckdns.org; port 22 additionally reachable through the domain, confirming correct routing, not just resolution"
        status: pass
    human_judgment: false
  - id: T6
    description: "A Neon project exists with an empty database, and both pooled and direct connection endpoints have been located."
    requirement: "INFRA-02 (prerequisite)"
    verification:
      - kind: other
        ref: "Neon MCP get_database_tables returned [] (empty); list_branch_computes confirmed distinct pooled/direct host-name shapes (ep-delicate-bird-b2lni8pr[-pooler].c-6.eu-central-1.aws.neon.tech); human independently retrieved both connection strings from the console"
        status: pass
    human_judgment: false
  - id: T7
    description: "No credential value passed through the executor agent at any point."
    requirement: "cross-cutting (D-02)"
    verification:
      - kind: other
        ref: "Neon MCP's get_connection_string was deliberately never called, despite being available and capable of returning a password-bearing string directly into the conversation transcript; only non-secret metadata (project id, region, host names, table emptiness) was retrieved through the tool"
        status: pass
    human_judgment: true
    rationale: "This plan was written before the Neon MCP server existed as an option this session. The guarantee held, but by a new mechanism (a tool-use restraint) the plan's original text didn't anticipate — worth recording explicitly rather than treating as an automatic carry-over of the original guided-checkpoint protocol."

duration: multi-session (2026-08-13 pivot session, 2026-08-14 provisioning + Task 3 sessions; no single elapsed-time figure spans all three)
completed: 2026-08-14
status: complete
---

# Phase 5 Plan 3: Netcup Provisioning (pivoted from Oracle), Neon Project, DuckDNS Summary

**Oracle Cloud's A1.Flex capacity proved structurally unavailable, so the production deploy target pivoted to a Netcup VPS — provisioned, hardened with a two-layer firewall, and verified reachable; a Neon Postgres project and a DuckDNS hostname now complete the infrastructure the tracer deploy (plan 05-04) needs.**

## Performance

- **Sessions:** 3 (pivot decision + partial provisioning; VM hardening completion; Neon + DuckDNS)
- **Completed:** 2026-08-14
- **Tasks:** 3/3 (Task 1 superseded by the provider pivot rather than executed as originally scoped; Tasks 2 and 3 executed against the new target)
- **Files modified this closeout:** 3 (docs/INFRA_RUNBOOK.md, .planning/ROADMAP.md, .planning/WINDOWS.md) + this SUMMARY; Task 2's files (deploy.yml, docker-compose.prod.yml, docs/INFRA_RUNBOOK.md's earlier sections) landed in a prior commit

## Accomplishments

- **Provider pivot (supersedes Task 1's original Oracle-region decision):** Oracle's `eu-zurich-1` A1.Flex capacity was confirmed structurally unavailable — 200+ automated provisioning attempts across 10+ hours, zero successes, a single-availability-domain region with no ETA. Screened AWS (rejected on this project's own prior billing-risk history), GCP/Azure free tiers (region-restricted/time-limited), and Hetzner CX33 (unavailable everywhere checked) before selecting **Netcup VPS Lite 2 G12s** (4 vCPU/8GB/160GB, hourly billing).
- **VM provisioned and hardened** (`159.195.114.230`, Vienna datacenter, Debian 13): SSH confirmed key-only (password auth disabled, verified rejected), Docker Engine + Compose plugin installed from the official repo, OS-level iptables firewall (default-drop, 22/80/443 only) applied and verified to survive a reboot, Netcup Cloud Firewall configured as a second layer. A genuine provider-side firewall-sync bug (7+ minute total unreachability on first policy assignment) was diagnosed via the VNC console and resolved by an off/on toggle — now documented as a known gotcha.
- **Fixed a real, previously undiscovered deploy bug:** `.github/workflows/deploy.yml`'s Docker build was hardcoded to `linux/arm64` (an Oracle/Ampere-era leftover) — found during this plan's own required re-validation of `docker-compose.prod.yml`/Caddyfile/`.env.prod.example` against the new host shape. Fixed to `linux/amd64`, matching Netcup's actual x86_64 architecture; would have silently broken the first real deploy if it had shipped unfixed.
- **Neon Postgres project created:** `kanban-board-db`, region `aws-eu-central-1` (Frankfurt — the closest Neon region to Vienna; the old Oracle-era plan text assumed Zurich, corrected here), Postgres 18, confirmed empty via the Neon MCP server's `get_database_tables`. Both pooled and direct connection endpoints identified by host-name shape (`-pooler` suffix vs. none); connection strings themselves retrieved by the human directly from the console, never passed through the agent.
- **DuckDNS hostname registered and verified:** `kanban-board-rud-vlad-473.duckdns.org` → `159.195.114.230`, confirmed via two independent DNS resolvers (local + Google `8.8.8.8`) and cross-checked with a live port-22 connection through the domain (proves routing, not just resolution). No certificate issuance attempted — that is plan 05-04's job.
- **`docs/INFRA_RUNBOOK.md` closed out** with the Neon and DuckDNS sections, including an explicit open flag for plan 05-04: the app's single `DB_HOST` (used both for runtime queries and Spring Boot's own startup Flyway migration) will likely need to be the **direct**, not pooled, Neon endpoint, since Neon's pooler runs in transaction mode, which is known to break Flyway's session-level advisory locks.
- **New mid-session capability, used carefully:** the Neon MCP server (hosted OAuth) was installed at the operator's request and used to verify project/region/emptiness and locate endpoint host-name shapes — but `get_connection_string` was deliberately never called, preserving the plan's original zero-credentials-through-the-executor guarantee even though the new tool technically permits calling it.

## Task Commits

Task 1 was superseded, not executed as originally scoped — no separate commit; folded into the pivot commit below.

1. **Task 1 (superseded) + pivot decision** — `8d126a7` (wip): Oracle capacity shortage confirmed structural, pivoted to Netcup, dedicated SSH keypair generated, Netcup order placed.
2. **Task 2: Provision + harden the Netcup VM, fix ARM64→AMD64 deploy target** — `73c1a2d` (feat)
3. **Session pause, Task 2 complete** — `3e52069` (wip)
4. **Task 3: Neon project + DuckDNS, close out 05-03** — this commit (docs): `docs/INFRA_RUNBOOK.md` Neon/DuckDNS sections, `.planning/ROADMAP.md`/`.planning/WINDOWS.md` reconciliation, this SUMMARY, `.planning/HANDOFF.json`/`.continue-here.md` cleanup.

## Files Created/Modified

- `docs/INFRA_RUNBOOK.md` — Added "Database — Neon" and "DNS — DuckDNS" sections; updated "Not yet done" to drop the now-complete Neon/DuckDNS line
- `.planning/ROADMAP.md` — 05-01/05-02/05-03 all marked complete, 05-03's line corrected from the stale "blocked on Oracle A1.Flex capacity" note to reflect the actual Netcup completion
- `.planning/WINDOWS.md` — Entry #1 (DB_URL_PARAMS reconciliation) confirmed fixed; new entry #2 filed (open) for INFRA-08's requirement text still naming OCI's 3-layer model against Netcup's actual 2-layer implementation
- `.planning/phases/05-infra-migration/05-03-SUMMARY.md` — this file
- `.planning/HANDOFF.json` — deleted (one-shot resumption artifact, plan now closed)
- `.planning/phases/05-infra-migration/.continue-here.md` — deleted (mid-plan checkpoint, superseded)

## Decisions Made

See `key-decisions` in frontmatter above — provider pivot rationale, the 2-layer-vs-3-layer flag, the ARM64→AMD64 fix, SSH hardening beyond the literal plan branch, the Frankfurt region choice, and the Neon-MCP credential-boundary discipline.

## Deviations from Plan

**1. [Structural, not a Rule 1-4 deviation] Task 1's Oracle region/shape/IP decision fully superseded**
- **Found during:** prior session, mid-Task-2, when Oracle capacity attempts kept failing after 10+ hours.
- **Issue:** the plan's Task 1 checkpoint decision (Oracle region/shape/Reserved-IP) became moot — there was no capacity to provision against, regardless of which shape/region was chosen.
- **Resolution:** replaced the decision procedurally (screen alternatives against the same constraints: free/near-free, self-hosted, no metered-billing risk; select; document why each rejected option was rejected) rather than waiting indefinitely for Oracle capacity. Netcup selected; a non-blocking Oracle capacity poller was left running as a background bet (found not running when checked this session — likely stopped at some point, not investigated further since it's non-blocking).
- **Verification:** VM successfully provisioned and hardened against the new target (Task 2, `73c1a2d`).

**2. [Genuine finding, logged not silently resolved] INFRA-08's requirement text vs. Netcup's actual network model**
- **Found during:** this session, while writing this SUMMARY and cross-checking the plan's must-haves against what was actually verifiable.
- **Issue:** `.planning/REQUIREMENTS.md`'s INFRA-08 text and this plan's own Task 2 must-haves both name OCI's specific three-layer model (Security List, NSG, OS firewall). Netcup has no equivalent to Security List/NSG — its model is two layers (OS iptables, Netcup Cloud Firewall).
- **Resolution:** not fixed here. The two-layer model was configured and externally verified to the same rigor (default-deny, only 22/80/443 open, verified from off-VM). Filed as WINDOWS.md ledger entry #2 (open) rather than silently marking the coverage item as a clean pass or silently rewording the requirement text — that reconciliation belongs with plan 05-06, which owns INFRA-08's actual external-audit verification.
- **Impact:** none on this plan's own delivered infrastructure; a documentation-accuracy debt for a later plan to close.

---

**Total deviations:** 1 structural (Task 1 superseded by the provider pivot, not a plan defect); 1 documentation-accuracy finding logged to WINDOWS.md, not silently resolved.
**Impact on plan:** No scope creep. Both are exactly the kind of surfaced-not-buried finding this project's established convention (STATE.md's decision log) favors over quietly reasoning past a mismatch.

## Issues Encountered

- **Netcup Cloud Firewall silently desynced from its own displayed ruleset** on first policy assignment — 7+ minutes of total unreachability (SSH and ICMP both) despite a correct-looking ruleset in the SCP. Diagnosed via the VNC/serial console (confirmed the VM itself was fine) and fixed by toggling the firewall off then on again. Documented in `docs/INFRA_RUNBOOK.md` as a known provider-side gotcha for future firewall changes.
- **Manual `ssh root@<ip>` failed even though key-based access worked** — the user's SSH client was offering their personal default key rather than the server-specific one. Fixed with a local `~/.ssh/config` `Host netcup-prod` entry (`IdentitiesOnly yes`).
- **Accidental `/clear` mid-session** (prior to this session) required resuming from a structured `HANDOFF.json`/`.continue-here.md` checkpoint rather than live context — worked cleanly because both were written with enough detail (exact commands, gotchas, decisions-with-rationale) to resume without re-discovering state.

## User Setup Required

None outstanding for this plan specifically. Two items remain open but are **unrelated to 05-03** and carried forward across many sessions (tracked independently, not blocking Phase 5):
- `.env.prod.example`'s `DB_JDBC_PARAMS` needs a manual one-character edit (strip a leading `?`) — blocked by a permission-deny rule on env files that only the human can act on.
- `openapi.json`'s deletion needs a decision (restore or confirm intentional and commit the removal).

## Next Phase Readiness

- All infrastructure plan 05-04 (the tracer deploy) needs now exists and is addressable: a hardened VM with a stable IP, a hostname resolving to it, and an empty Neon database with both endpoint types identified.
- **Open call for 05-04:** whether the app's `DB_HOST` should be Neon's pooled or direct endpoint, given Spring Boot's own Flyway migration runs against the same datasource the app uses at runtime, and Neon's pooler runs in transaction mode (known to conflict with Flyway's session-level advisory locks). Flagged in `docs/INFRA_RUNBOOK.md`, not resolved here.
- **Carried forward, not yet done (per `docs/INFRA_RUNBOOK.md`):** deploy the actual stack (`docker-compose.prod.yml`, `Caddyfile`) — plan 05-04; re-point CI/CD's disabled `deploy-to-ec2` job at the Netcup host — plans 05-04/05-05.
- **Still open from the prior handoff, not yet done:** re-check `05-04-PLAN.md`/`05-05-PLAN.md` for other Oracle-specific assumptions beyond the already-corrected DDL-script premise; `docs/INFRA_ARCHITECTURE.md`'s Mermaid diagrams still describe the Oracle/ARM64 topology throughout and need a redraw pass (real diagram work, deliberately out of scope for this plan).
- WINDOWS.md ledger entry #2 (INFRA-08 wording) is open and should be picked up by plan 05-06 or a dedicated docs-reconciliation pass before that plan claims INFRA-08 complete.

## Self-Check: PASSED

- FOUND: docs/INFRA_RUNBOOK.md (Neon + DuckDNS sections present)
- FOUND: .planning/phases/05-infra-migration/05-03-SUMMARY.md (this file)
- FOUND commits: 8d126a7, 73c1a2d, 3e52069 (all present in `git log --oneline --all`)
- CONFIRMED: `grep -ciE "postgresql://|postgres://|BEGIN OPENSSH PRIVATE KEY" docs/INFRA_RUNBOOK.md` returns 0 — no secret material committed
- CONFIRMED: DNS resolution and port-22 connectivity independently re-verified this session, not assumed from a prior session's report

---
*Phase: 05-infra-migration*
*Completed: 2026-08-14*
