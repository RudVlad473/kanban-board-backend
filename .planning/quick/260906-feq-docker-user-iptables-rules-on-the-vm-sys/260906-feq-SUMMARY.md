---
phase: quick
plan: 260906-feq
subsystem: infra
tags: [iptables, docker-user, firewall, systemd, netcup, iptables-restore]

requires:
  - phase: 11
    provides: self-hosted Postgres/Redpanda VM topology this ruleset applies to
provides:
  - A version-controlled DOCKER-USER inbound firewall policy for Docker-published ports (80/443),
    installed and self-reapplying via a systemd unit
  - An off-box, before/after falsification test proving the policy actually filters production
    traffic, with packet-counter attribution
  - Documentation (runbook, architecture doc, packet-path diagram) correcting the "DOCKER-USER is
    empty" claim that stood since 2026-09-05
  - A filed, evidence-carrying todo for the separately-scoped IPv6 gap this change does not close
affects: [infra, security, docs-diagrams]

actuals:
  tokens: 11714
  tasks: 4
  commits: 2

tech-stack:
  added: []
  patterns:
    - "iptables-restore --noflush + an in-fragment -F <chain> line for an atomic, idempotent
      single-chain replace that leaves every other chain untouched — verified empirically against
      the live VM before adopting, not assumed from the man page"
    - "systemd oneshot unit with PartOf=docker.service to reapply a firewall policy after every
      dockerd restart, not just at boot"

key-files:
  created:
    - infra/vm/docker-user-firewall.sh
    - infra/vm/docker-user-firewall.service
    - infra/vm/README.md
    - .planning/todos/pending/2026-09-06-ipv6-published-ports-have-no-host-level-firewall.md
  modified:
    - docs/INFRA_RUNBOOK.md
    - docs/INFRA_ARCHITECTURE.md
    - docs/diagrams/infra-packet-path-scenario.mmd
    - docs/diagrams/infra-packet-path-scenario.png
    - scripts/verify-compose-ports.py
    - .planning/todos/completed/2026-09-05-docker-user-chain-empty-on-the-vm.md (moved from pending/)

key-decisions:
  - "Netcup Cloud Firewall was masking the canary port during the BEFORE probe (curl exit 28) — the
    operator opened a temporary, scoped Netcup console rule for the probe source IP for the
    duration of the test, then removed and confirmed removal, rather than resting the Layer-3 claim
    on packet counters alone"
  - "Reboot proof: operator chose to reboot production now and re-verify with the three discovery
    commands (closing the todo's second requirement in full), rather than deferring to the next
    natural reboot"
  - "IPv6: operator chose to leave the ip6tables/docker-proxy gap open, document it precisely, and
    file a dedicated todo, rather than mirror the ruleset into ip6tables unverifiably from a box
    with no IPv6 egress"
  - "Atomic apply implemented as iptables-restore --noflush plus an explicit -F DOCKER-USER line
    inside the restore fragment (not flush-then-append), verified live against the VM to be
    idempotent (exactly 5 rules on repeat application) before writing it into the script"

requirements-completed: [SEC-DOCKER-USER-01]

coverage:
  - id: D1
    description: "DOCKER-USER carries a default-drop policy for Docker-published ports, proven by
      an off-box canary probe inverting from reachable to timed-out across the apply"
    requirement: "SEC-DOCKER-USER-01"
    verification:
      - kind: manual_procedural
        ref: "off-box curl probe against 159.195.114.230:49999 before/after apply; iptables -L
          DOCKER-USER -n -v packet counters"
        status: pass
    human_judgment: true
    rationale: "Requires reading live production firewall counters and off-box probe output against
      a specific point in time — not reproducible as an automated regression test."
  - id: D2
    description: "The ruleset is version-controlled (infra/vm/docker-user-firewall.sh) and
      self-reapplies via a systemd unit on docker restart and on reboot, with no manual step"
    requirement: "SEC-DOCKER-USER-01"
    verification:
      - kind: manual_procedural
        ref: "systemctl restart docker-user-firewall (hand-flush test); systemctl restart docker;
          full VM reboot — all three followed by docker-user-firewall.sh check"
        status: pass
    human_judgment: false
  - id: D3
    description: "Documentation (runbook Firewall section, architecture doc's packet-path Scenario
      and trust-boundary prose, the diagram) no longer describes DOCKER-USER as empty"
    verification:
      - kind: other
        ref: "grep for 'chain is empty'/'empty as of' across docs/INFRA_RUNBOOK.md and
          docs/INFRA_ARCHITECTURE.md; ./scripts/render-diagrams.sh --check infra-packet-path-scenario"
        status: pass
    human_judgment: false

duration: 27min
completed: 2026-09-06
status: complete
---

# Quick Task 260906-feq: DOCKER-USER iptables rules on the VM Summary

**Filled the previously-empty `DOCKER-USER` chain with a version-controlled default-drop policy for Docker-published ports (80/443), proved it with an off-box before/after falsification test with packet-counter attribution, made it self-reapplying via a systemd unit verified across a docker restart AND a full VM reboot, and corrected the runbook/architecture-doc/diagram trio that had described the chain as empty since 2026-09-05.**

## Performance

- **Duration:** 27 min
- **Started:** 2026-09-06T09:47:03Z
- **Completed:** 2026-09-06T10:14:00Z (approx)
- **Tasks:** 4/4 (3 execution tasks + 1 checkpoint with two decisions)
- **Files modified:** 11 (3 created under `infra/vm/`, 1 new todo filed, 1 todo moved+edited, 5 docs/script files edited)

## Accomplishments

- `DOCKER-USER` now carries five rules (RELATED,ESTABLISHED return, non-`eth0` return, TCP 80/443 return via `--ctorigdstport`, default DROP), applied by hand first and proven with a live off-box canary test: `curl` against a throwaway published port went from `200` (canary body) to `exit 28` (timeout) across the apply, with the DROP rule's own packet counter incrementing by exactly the after-probe's SYN count while staying flat under concurrent legitimate 80/443 traffic — clean attribution, not a coincidence.
- Turned the hand-applied ruleset into `infra/vm/docker-user-firewall.sh` (`apply`/`check`/`show` subcommands, atomic `iptables-restore --noflush` + in-fragment `-F` for idempotent single-chain replace — verified empirically against the live VM before committing to that shape) and `infra/vm/docker-user-firewall.service` (`PartOf=docker.service`), installed and enabled on the VM.
- Proved persistence three ways: a hand-flush + unit restart, a full `systemctl restart docker` (all 6 containers cycled), and — per the operator's explicit decision at the checkpoint — a genuine VM reboot, re-running the same three discovery commands (`iptables -t nat -S PREROUTING`, `iptables -S INPUT`, `iptables -S DOCKER-USER`) that originally found the gap. All three passed with no manual reapplication.
- Updated `docs/INFRA_RUNBOOK.md` (new Layer 3 subsection with full evidence), `docs/INFRA_ARCHITECTURE.md` (packet-path Scenario rewrite, trust-boundary prose, Maintenance Note), and `docs/diagrams/infra-packet-path-scenario.mmd`/`.png` (re-rendered through the pinned renderer) — none of the three still describes the chain as empty.
- Closed the originating todo (`.planning/todos/pending/2026-09-05-docker-user-chain-empty-on-the-vm.md` → `completed/`) with a Resolution documenting both requirements (persistence, re-verification) and which mechanism met each, explicitly noting the deliberate override of the todo's own `netfilter-persistent save` suggestion.
- Filed a new todo for the IPv6 finding (`ip6tables -P INPUT ACCEPT`, `docker-proxy` bound on `[::]:80`/`[::]:443`, terminates on INPUT not FORWARD — structurally unreachable by this change), per the operator's decision to leave it open rather than close it unverifiably from a box with no IPv6 egress.

## Task Commits

Each execution task was committed atomically (Task 1 produced no repository changes — it operated entirely on the VM and its evidence is cited by Task 3's documentation):

1. **Task 1: Apply the ruleset live and prove it off-box, before and after** — no commit (VM-only; see evidence above and in `docs/INFRA_RUNBOOK.md`'s new Layer 3 section)
2. **Task 2: Commit the script and systemd unit, install them, prove they survive a docker restart** — `65c8409` (feat)
3. **Checkpoint: reboot proof and IPv6 scope** — no commit (two human decisions recorded above; reboot executed and re-verified per Decision 1)
4. **Task 3: Update the runbook, the architecture doc and the diagram, re-render, and close the todo** — `f47f912` (docs)

**Plan metadata:** SUMMARY.md commit handled by the orchestrator per this task's execution constraints (quick-task docs artifacts are not committed by the executor).

## Files Created/Modified

- `infra/vm/docker-user-firewall.sh` - `apply`/`check`/`show` subcommands; atomic idempotent chain replace; decision-record header
- `infra/vm/docker-user-firewall.service` - `Type=oneshot`, `RemainAfterExit=yes`, `PartOf=docker.service`
- `infra/vm/README.md` - install steps, drift-check command, why not wired into `deploy.yml`
- `docs/INFRA_RUNBOOK.md` - Firewall section retitled "three layers"; new Layer 3 subsection with full measured evidence
- `docs/INFRA_ARCHITECTURE.md` - packet-path Scenario rewritten with policy + evidence + IPv6 caveat; trust-boundary prose and Maintenance Note updated
- `docs/diagrams/infra-packet-path-scenario.mmd` / `.png` - `DOCKER-USER` node states the live policy, re-rendered through the pinned renderer
- `scripts/verify-compose-ports.py` - KNOWN HOLES comment repointed at `infra/vm/docker-user-firewall.sh`'s `check` subcommand
- `.planning/todos/completed/2026-09-05-docker-user-chain-empty-on-the-vm.md` - moved from `pending/`, Resolution appended
- `.planning/todos/pending/2026-09-06-ipv6-published-ports-have-no-host-level-firewall.md` - new todo, carries the measured IPv6 values

## Decisions Made

- **Netcup masking (mid-Task-1, ad hoc checkpoint):** the BEFORE probe against the canary port initially timed out (`exit 28`), meaning the Netcup Cloud Firewall was masking the port before any DOCKER-USER rule existed — a green AFTER result would have proven nothing. Per the plan's explicit "do not silently pick one" instruction, this was raised to the user. **Chosen: option 1** — the operator opened a temporary, scoped Netcup console rule (source IP only) for the duration of the test, confirmed it live, and removed it afterward, confirmed by the operator. This made the off-box probe genuinely attributable to Layer 3.
- **Decision 1 (reboot), at the plan's formal checkpoint:** **option 1** — reboot now and re-verify with the three discovery commands. Executed: ~59s from reboot command to both health endpoints confirmed `200`; all three discovery commands and the script's own `check` oracle confirmed the policy survived with no manual reapplication.
- **Decision 2 (IPv6), at the same checkpoint:** **option 2** — leave the IPv6 gap open, document it precisely in both docs, and file a todo carrying the measured values, rather than mirror the ruleset into ip6tables unverifiably.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking issue] Plan's canary command used a nonexistent Caddy CLI flag**
- **Found during:** Task 1, Step 1 (starting the canary container)
- **Issue:** The plan's exact command was `caddy respond --listen :80 --status-code 200 --body fw-canary`. This Caddy build's `respond` subcommand has no `--status-code` flag (confirmed via `caddy respond --help` on the box) — the container exited immediately (code 1).
- **Fix:** Corrected to `--status 200`, the actual flag name. Container then ran correctly and passed its own control check (local `curl` returned `200` with the canary body).
- **Files modified:** None (VM-only command correction, not a repository file)
- **Verification:** `docker ps` showed `fw-canary` up; local `curl http://localhost:49999/` returned `200`/`fw-canary`
- **Committed in:** N/A (no repository files touched by Task 1)

---

**Total deviations:** 1 auto-fixed (Rule 3 — blocking issue)
**Impact on plan:** No scope creep. The fix was a one-word CLI flag correction required to make Task 1's own test infrastructure work at all; it did not change the ruleset, the script, or any documentation content.

## Issues Encountered

None beyond the deviation and the two decision points documented above, both of which the plan itself anticipated and explicitly required routing to the human rather than resolving silently.

## User Setup Required

None - no external service configuration required. (The temporary Netcup SCP console rule opened and removed during Task 1 was a one-time manual step performed by the operator as part of Decision-point resolution, not a standing setup requirement.)

## Next Phase Readiness

- The IPv6 gap (`ip6tables -P INPUT ACCEPT`, `docker-proxy` on `[::]:80`/`[::]:443`) is filed as `.planning/todos/pending/2026-09-06-ipv6-published-ports-have-no-host-level-firewall.md`, carrying the measured values and both candidate fixes (mirror the FORWARD-side ruleset into ip6tables; separately address the docker-proxy/INPUT path) — neither of which this box can verify off-box, so it should be picked up from a machine or service with IPv6 egress.
- `infra/vm/docker-user-firewall.sh check` is now the standing drift-detection command for this ruleset — worth wiring into a periodic check (e.g. alongside the existing uptime-check.yml cron) in a future phase if drift detection should be automated rather than manual.
- No blockers for other in-flight work: this change touches only `FORWARD`/`DOCKER-USER` and is structurally incapable of affecting `INPUT` (SSH) or any other host service.

## Self-Check: PASSED

- FOUND: `infra/vm/docker-user-firewall.sh`
- FOUND: `infra/vm/docker-user-firewall.service`
- FOUND: `infra/vm/README.md`
- FOUND: `.planning/todos/completed/2026-09-05-docker-user-chain-empty-on-the-vm.md`
- FOUND: `.planning/todos/pending/2026-09-06-ipv6-published-ports-have-no-host-level-firewall.md`
- MISSING (expected): `.planning/todos/pending/2026-09-05-docker-user-chain-empty-on-the-vm.md` (moved, confirmed)
- FOUND commit `65c8409` in `git log --oneline`
- FOUND commit `f47f912` in `git log --oneline`
- Live VM check: `docker-user-firewall.sh check` exits 0 as of this summary's writing

---
*Phase: quick*
*Completed: 2026-09-06*
