---
phase: quick
plan: 260906-feq
type: execute
wave: 1
depends_on: []
files_modified:
  - infra/vm/docker-user-firewall.sh
  - infra/vm/docker-user-firewall.service
  - infra/vm/README.md
  - docs/INFRA_RUNBOOK.md
  - docs/INFRA_ARCHITECTURE.md
  - docs/diagrams/infra-packet-path-scenario.mmd
  - docs/diagrams/infra-packet-path-scenario.png
  - .planning/todos/pending/2026-09-05-docker-user-chain-empty-on-the-vm.md
autonomous: false
requirements: [SEC-DOCKER-USER-01]
user_setup: []

estimate:
  tokens: 95000
  raw_tokens: 62000
  tasks: 3
  confidence: low

must_haves:
  truths:
    - "A throwaway Docker-published host port on the VM is REACHABLE from off-box before the rules are applied and UNREACHABLE (SYN dropped, connection times out) after — measured from this dev box, not from the VM."
    - "The DROP rule's own packet counter increments during the off-box probe, proving the packet reached the VM and DOCKER-USER — not the Netcup Cloud Firewall — dropped it."
    - "Production and nonprod HTTPS health endpoints answer 200 and `ssh netcup-prod` still succeeds after the rules are applied."
    - "The rules survive `systemctl restart docker` without manual reapplication."
    - "docs/INFRA_ARCHITECTURE.md and docs/diagrams/infra-packet-path-scenario.mmd no longer describe DOCKER-USER as empty, and the PNG is regenerated from the edited source in the same commit."
  artifacts:
    - infra/vm/docker-user-firewall.sh
    - infra/vm/docker-user-firewall.service
    - infra/vm/README.md
    - docs/diagrams/infra-packet-path-scenario.png
  key_links:
    - "systemd unit ExecStart -> /usr/local/sbin/docker-user-firewall.sh apply (the committed script is the single source of the ruleset; the unit only invokes it)"
    - "PartOf=docker.service -> unit re-runs whenever dockerd restarts and rebuilds its chains"
    - "docs/diagrams/*.mmd edit -> scripts/render-diagrams.sh -> committed *.png (the .mmd is never hand-edited without a re-render)"
---

<objective>
Fill the empty `DOCKER-USER` chain on the production VM with a real inbound policy for
Docker-published ports, make it survive dockerd restarts and reboots via a committed,
version-controlled systemd unit, prove it with an off-box negative test that demonstrably fails
without the rules, and update the two artifacts that currently say the chain is empty — in the
same change, with the diagram re-rendered.

Purpose: today the only thing between a published container port and the internet is the Netcup
Cloud Firewall, which lives outside this repository and is reviewed in no pull request. This adds
a second enforcing layer that IS version-controlled.

Output: `infra/vm/` (script + unit + README), an updated Firewall section in the runbook, an
updated packet-path Scenario in the architecture doc, a re-rendered diagram, and the originating
todo closed with a Resolution.
</objective>

<live_observations>
Read from the production VM at 2026-09-06T09:06Z, satisfying this task's requirement that any
scope depending on live firewall state be authorized from a live observation rather than from the
runbook's possibly-stale prose.

| Fact | Observed value | Why it matters here |
|------|----------------|---------------------|
| `iptables -S DOCKER-USER` | `-N DOCKER-USER` and nothing else | The gap is still open. Confirmed live, not assumed. |
| `iptables -S FORWARD` | `-P FORWARD DROP`, then `-j DOCKER-USER`, then `-j DOCKER-FORWARD` | DOCKER-USER is evaluated first on every forwarded packet — the correct insertion point. |
| External interface | `eth0`, `159.195.114.230/22`, default route `via 159.195.112.1 dev eth0` | The rules scope to `eth0`; every other in-interface is a Docker bridge. |
| `iptables -t nat -S DOCKER` | DNAT for tcp/80 and tcp/443 only, both to `172.18.0.2` | Exactly two published host ports exist. An 80/443-only permit is complete, not a partial guess. |
| Caddy `docker ps` ports | `0.0.0.0:80->80/tcp`, `[::]:80->80/tcp`, `0.0.0.0:443->443/tcp`, `[::]:443->443/tcp`, plus bare `443/udp` and `2019/tcp` | `443/udp` (HTTP/3) and `2019` carry no host mapping — exposed, not published. A TCP-only permit does not break anything currently reachable. |
| Docker bridges | `br-02b3362148e1` (compose default, caddy+app), `br-5ac55b7c8cee` (kanban-edge), `br-b670ee54b952` (nonprod), `br-f555a74fdb25` (kanban-db); `docker0` DOWN | Inter-container and egress traffic must be returned unfiltered — hence the `! -i eth0` early RETURN. |
| `-m conntrack --ctorigdstport 80` | Loads successfully (tested on a temporary chain, which was then removed) | Lets the rule express the host-published port rather than the post-DNAT container port. |
| `iptables-persistent` / `netfilter-persistent` | Installed, enabled, active; `/etc/iptables/rules.v4` dated Aug 14, contains `:DOCKER-USER - [0:0]` with no rules plus a stale snapshot of Docker's own runtime chains | This is why persistence goes through a systemd unit, not `netfilter-persistent save` — see the trade-off matrix. |
| `ip6tables -S INPUT` / `-S FORWARD` | `-P INPUT ACCEPT`, `-P FORWARD ACCEPT`, `DOCKER-USER` also empty | **New finding, outside this task's stated scope.** See `<ipv6_finding>`. |
| `docker info` | `EnableUserlandProxy: true`, five `docker-proxy` processes including two bound `-host-ip ::` | The IPv6 path terminates on a host socket (INPUT), not on FORWARD — which is why the v6 hole is a different fix. |
| Off-box probe from this box | `159.195.114.230:80` OPEN; this box has **no IPv6 egress** (`curl -6 ifconfig.me` returned empty, no default v6 route) | The v4 off-box test is runnable from here. A v6 off-box test is not. |
| This box's public IPv4 | `135.136.51.234` | Needed only if the Netcup-window fallback in Task 1 fires. |
| `systemd-run` | `/usr/bin/systemd-run` present | The auto-rollback safety net is available. |
| Free host port | `49999` unused | The canary port. |
</live_observations>

<design>
## The ruleset (the deliverable; task actions reference this section by name)

Applied to the IPv4 `filter` table's `DOCKER-USER` chain, in this exact order:

```
iptables -F DOCKER-USER
iptables -A DOCKER-USER -m conntrack --ctstate RELATED,ESTABLISHED -j RETURN
iptables -A DOCKER-USER ! -i eth0 -j RETURN
iptables -A DOCKER-USER -p tcp -m conntrack --ctorigdstport 80 -j RETURN
iptables -A DOCKER-USER -p tcp -m conntrack --ctorigdstport 443 -j RETURN
iptables -A DOCKER-USER -j DROP
```

Rule by rule, with the traffic each one exists to protect:

1. **RELATED,ESTABLISHED RETURN** — reply packets for connections a container opened outbound
   (image pulls, Caddy's ACME renewals, either app reaching Redpanda) arrive on `eth0` and would
   otherwise reach the final DROP. Omitting this rule breaks container egress, and it breaks it
   asymmetrically: the SYN leaves fine and only the reply dies, so it presents as a hang rather
   than as a firewall error.
2. **`! -i eth0` RETURN** — everything not entering from the internet (Caddy to app on
   `br-02b3362148e1`, either app to Postgres on `br-f555a74fdb25`, container egress SYNs) leaves
   the chain untouched. This keeps the policy a statement about inbound internet traffic only.
3. and 4. **`--ctorigdstport 80` / `443` RETURN** — the two ports Docker actually publishes.
5. **DROP** — everything else arriving from the internet for a container.

Nothing here touches `INPUT`, and the script must never touch it: SSH is a host daemon governed by
`INPUT`, DOCKER-USER lives on `FORWARD`, so this change is structurally incapable of locking out
SSH. That is a property to preserve, not a licence to skip the rollback timer — a wrong 80/443
rule takes the site down even though it cannot take access down.

## Alternate approaches considered

| Approach | Pros / Cons | Why picked or rejected |
|----------|-------------|------------------------|
| **A. Committed script + systemd oneshot unit, `After=`/`Requires=`/`PartOf=docker.service`** (chosen) | **+** The ruleset becomes a reviewed file in this repository — the exact thing the originating todo says the Netcup layer is not. **+** `PartOf=` re-applies on `systemctl restart docker`, the likeliest wipe event. **+** A `check` subcommand gives the runbook a drift command. **−** Two new files plus a manual install step on the VM. | Picked. It is the only option that makes the policy reviewable in a pull request, which is the todo's actual complaint. |
| **B. `netfilter-persistent save` into `/etc/iptables/rules.v4`** (the originating todo's own suggestion) | **+** Zero new files; reuses tooling already installed and enabled. **−** `iptables-save` captures the whole filter table, so the saved file would freeze a snapshot of Docker's runtime-managed `DOCKER`, `DOCKER-BRIDGE`, `DOCKER-CT` and `DOCKER-FORWARD` chains alongside ours — the file already on the box does exactly this and is a month stale. **−** Boot-time restore runs before `docker.service`, so a restored-then-rebuilt table is a race nobody reviews. **−** The policy stays invisible to code review. | Rejected, deliberately overriding the todo's stated solution. Its Persistence requirement is honoured by a mechanism that does not also snapshot chains Docker owns. |
| **C. `ufw` plus the community `ufw-docker` integration** | **+** Well-trodden; handles DOCKER-USER for you. **−** Installs a second, opinionated firewall front-end on a box whose Layer 1 rules are already hand-maintained under `iptables-persistent`; two managers of one table is how rules silently disappear. **−** Adds a dependency for five rules. | Rejected. The complexity is not proportional to a five-rule policy on a single-purpose VM. |

## Non-obvious trade-offs, stated rather than discovered later

- **DOCKER-USER sees post-DNAT destination ports.** A mapping like `8443:443` would appear in
  `FORWARD` as dport 443, so a naive `--dport` rule would silently permit host port 8443. Today's
  mappings are identity (80 to 80, 443 to 443) so both forms behave the same — which is precisely
  why the bug would not surface in testing. `--ctorigdstport` matches the original host port and is
  therefore correct for a mapping this repository has not made yet.
- **The Netcup Cloud Firewall can mask the negative test.** If it already drops the canary port
  upstream, the off-box probe times out identically whether or not DOCKER-USER exists — a green
  result that proves nothing. Task 1 measures the before-state first for exactly this reason and
  branches on the observation instead of assuming.
- **Packet counters are the attribution evidence.** Per-rule counters from
  `iptables -L DOCKER-USER -n -v` distinguish "DOCKER-USER dropped it" from "it never arrived". A
  pass without a counter increment is an unproven pass.
- **`systemctl restart docker` restarts every container.** It is part of the mandatory verification
  and it costs a short production blip. Budget for it; do not run it casually.
- **UDP and ICMP inbound to containers become unreachable.** Correct today, since nothing is
  published over UDP, and a real constraint tomorrow: enabling Caddy's HTTP/3 will require
  publishing 443/udp *and* adding a matching RETURN rule here. This belongs in the script header so
  the second half is not forgotten.
</design>

<ipv6_finding>
Measured during planning, not previously known, and materially in scope for the documentation half
of this task: on IPv6 the VM has `-P INPUT ACCEPT` and `-P FORWARD ACCEPT`, and `docker-proxy`
binds `[::]:80` and `[::]:443`. Because the containers hold no IPv6 address, inbound IPv6 to a
published port terminates on the host's `docker-proxy` socket and is evaluated by **ip6tables
INPUT**, whose policy is ACCEPT — not by FORWARD, and therefore not by anything this task adds.

Consequence for this plan: closing the IPv4 gap and then writing "a runtime layer now governs
container-published ports" would be false. Task 3's documentation must state that the policy added
here is IPv4-only, and must name what is deliberately still open. The IPv6 closure itself
(mirroring the v4 INPUT ruleset into ip6tables and flipping its policy to DROP) is a separate
live-firewall change on a path this box cannot verify off-box at all — it has no IPv6 egress — and
it is not in this task's brief. It is surfaced at the Task 2 checkpoint for a decision, and filed
as a todo carrying the evidence above regardless of how that decision goes.
</ipv6_finding>

<execution_context>
@~/.claude/gsd-core/workflows/execute-plan.md
@~/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@.planning/todos/pending/2026-09-05-docker-user-chain-empty-on-the-vm.md
@docs/INFRA_RUNBOOK.md
@docs/INFRA_ARCHITECTURE.md
@docs/diagrams/infra-packet-path-scenario.mmd
@scripts/render-diagrams.sh
</context>

<tasks>

<task type="tracer">
  <name>Task 1: Apply the ruleset live and prove it off-box, before and after</name>
  <files>(no repository files — this task operates on the VM and produces the evidence Task 3 cites)</files>
  <precondition>`ssh -o ConnectTimeout=10 -o BatchMode=yes netcup-prod true` exits 0, and this box can still reach 159.195.114.230 on port 80.</precondition>
  <action>
Run the falsification protocol end to end, so the fix is pinned by a test that demonstrably fails
without it. Work over `ssh netcup-prod`; run every off-box probe from this dev box, never from the
VM — a loopback probe cannot see a DNAT bypass.

Step 1, start the canary. On the VM, run a detached, auto-removing container publishing host port
49999 to container port 80, using an image already present on the box so nothing is pulled:
`rudenkovladimir/kanban-board-caddy:2.11.4-rl5625512f` with the command
`caddy respond --listen :80 --status-code 200 --body fw-canary`. Name it `fw-canary`.

Step 2, establish the control. From the VM, confirm the canary genuinely listens — a request to
`http://localhost:49999/` must return the canary body. Without this control a broken canary reads
as a working firewall. Also confirm Docker created a DNAT entry for 49999 in
`iptables -t nat -S DOCKER`.

Step 3, measure the BEFORE state from off-box. From this dev box, probe
`http://159.195.114.230:49999/` with an 8-second cap, recording HTTP status, total time and curl's
exit code. Interpret strictly: status 200 with the canary body means the hole is real and off-box
visible; curl exit 7 means the packet reached the VM but was refused; curl exit 28 means it was
filtered before reaching any listener.
  - If status 200, proceed to step 4 — the probe can attribute a change to Layer 3 on its own.
  - If exit 28, the Netcup Cloud Firewall is masking the port and this probe is structurally
    unable to test DOCKER-USER. Stop and raise it to the user as a decision, presenting: (i)
    temporarily permit tcp/49999 from source 135.136.51.234 only in the Netcup SCP firewall policy
    for the duration of the test, then remove it and verify the removal; or (ii) accept the off-box
    probe as a no-regression check only, rest the Layer-3 claim on packet counters alone, and say
    exactly that in the runbook. Do not silently pick one.

Step 4, arm the safety net before touching anything. Schedule a transient rollback that flushes
DOCKER-USER after 300 seconds using `systemd-run --on-active=300 --unit=fw-rollback`, so a ruleset
that takes the site down self-clears without needing a working session.

Step 5, apply the ruleset from the design section exactly, in order, by hand for this task; Task 2
turns it into the committed script. Then dump `iptables -S DOCKER-USER` and check it against the
six expected lines.

Step 6, measure the AFTER state from off-box with the identical probe from step 3. Required
result: curl exit 28, a timeout. An exit 7 means the SYN still reached the container and the rule
is not on the path; a 200 means the rule is not matching at all. Either is a failure, not a pass.

Step 7, attribute the drop. Read `iptables -L DOCKER-USER -n -v --line-numbers` and require that
the final DROP rule's packet counter increased across the step 6 probe. Separately require that
the 80 and 443 RETURN rules' counters are non-zero and rising under normal site traffic — that is
what proves the chain is genuinely on the live packet path rather than an inert chain nothing
traverses.

Step 8, confirm nothing regressed: both public health endpoints return 200
(`https://kanban-board-rud-vlad-473.duckdns.org/api/actuator/health` and the equivalent `-nonprod`
host), and a fresh `ssh netcup-prod true` succeeds.

Step 9, disarm the rollback timer and remove the canary container. Confirm both are gone.

Record every measured value — statuses, curl exit codes, timings, counter readings. Task 3's
documentation must cite real numbers, not restate intent.
  </action>
  <verify>
    <automated>ssh -o ConnectTimeout=15 -o BatchMode=yes netcup-prod 'iptables -S DOCKER-USER; echo ---; docker ps --format "{{.Names}}"' | tee /tmp/feq-t1.txt; grep -c -- '-A DOCKER-USER' /tmp/feq-t1.txt | grep -qx 5 &amp;&amp; grep -q 'j DROP' /tmp/feq-t1.txt &amp;&amp; ! grep -q 'fw-canary' /tmp/feq-t1.txt</automated>
    <human-check>The recorded BEFORE probe shows the canary port reachable from off-box (or the Netcup-masking branch was raised and answered), and the AFTER probe shows a timeout with the DROP rule's counter incremented.</human-check>
  </verify>
  <done>DOCKER-USER carries the five rules from the design section; the off-box canary probe changed from reachable to timing out across the apply; the DROP rule's counter incremented during the after-probe; both health endpoints return 200; SSH still works; the rollback timer and the canary container are both removed.</done>
  <reversibility rating="reversible">`iptables -F DOCKER-USER` restores the prior state exactly, and the armed rollback timer does it automatically if the session is lost.</reversibility>
</task>

<task type="auto">
  <name>Task 2: Commit the script and systemd unit, install them, prove they survive a docker restart</name>
  <files>infra/vm/docker-user-firewall.sh, infra/vm/docker-user-firewall.service, infra/vm/README.md</files>
  <action>
Turn Task 1's hand-applied ruleset into version-controlled, self-reapplying infrastructure.

Write `infra/vm/docker-user-firewall.sh` under `set -euo pipefail` with three subcommands. `apply`
flushes DOCKER-USER then appends the five rules from the design section in order — flush-then-append
is what makes it idempotent, and it is safe precisely because Docker never places anything in this
chain. `check` compares the live `iptables -S DOCKER-USER` output against the expected ruleset and
exits non-zero on any difference. `show` prints the chain with per-rule packet counters, for the
runbook's re-verification step. Hold the external interface name in a single variable at the top so
a NIC rename is a one-line edit rather than a five-line hunt.

Give the script a header carrying the decisions a future reader would otherwise reverse: why
persistence is a systemd unit rather than `netfilter-persistent save` (the saved file would also
snapshot Docker's runtime-managed chains — the stale `/etc/iptables/rules.v4` already on the box is
the evidence); why `--ctorigdstport` rather than `--dport`; that the conntrack RETURN must stay
first or container egress breaks in a way that presents as a hang; and that this policy is TCP-only
and IPv4-only, so enabling HTTP/3 will require publishing 443/udp and adding a matching rule here.
State plainly what is deliberately not checked, following the precedent set by
`scripts/render-diagrams.sh`'s own header.

Write `infra/vm/docker-user-firewall.service` as a `Type=oneshot` unit with `RemainAfterExit=yes`,
`ExecStart=/usr/local/sbin/docker-user-firewall.sh apply`, `After=docker.service`,
`Requires=docker.service`, `PartOf=docker.service`, and `WantedBy=multi-user.target`. `PartOf` is
the load-bearing directive: without it the unit runs at boot only, and a `systemctl restart docker`
leaves the chain in whatever state dockerd left it.

Write `infra/vm/README.md` covering the install command (copy the script to
`/usr/local/sbin/docker-user-firewall.sh` mode 0755 and the unit into `/etc/systemd/system/`, then
`daemon-reload` and `enable --now`), the drift-check command, and an explicit statement that this
is installed by hand and deliberately not wired into `deploy.yml` — an auto-applied firewall change
on every push has no review gate at apply time, and iptables is host-global while that workflow's
scp targets are per-environment.

Install both files on the VM, enable the unit, then prove it with two tests that fail without it.
First, flush DOCKER-USER by hand and `systemctl restart docker-user-firewall`, confirming the chain
is rebuilt. Second, `systemctl restart docker` and confirm the chain is still correct afterwards
with no manual step, using the script's own `check` subcommand as the oracle. After the docker
restart, re-confirm both health endpoints return 200 — that restart cycles every container.
  </action>
  <verify>
    <automated>bash -n infra/vm/docker-user-firewall.sh &amp;&amp; grep -q 'PartOf=docker.service' infra/vm/docker-user-firewall.service &amp;&amp; grep -q 'ExecStart=/usr/local/sbin/docker-user-firewall.sh apply' infra/vm/docker-user-firewall.service &amp;&amp; ssh -o ConnectTimeout=15 -o BatchMode=yes netcup-prod '/usr/local/sbin/docker-user-firewall.sh check &amp;&amp; systemctl is-enabled docker-user-firewall.service'</automated>
  </verify>
  <done>Both files exist in the repository and are installed on the VM; the unit is enabled; `check` exits 0 after a hand-flush plus unit restart, and again after a full `systemctl restart docker`; both health endpoints return 200 afterwards.</done>
  <reversibility rating="reversible">`systemctl disable --now docker-user-firewall` plus `iptables -F DOCKER-USER` returns the box to its pre-task state.</reversibility>
</task>

<task type="checkpoint:decision">
  <name>Checkpoint: reboot proof and IPv6 scope</name>
  <decision>
Two decisions the executor must not make alone. Ask both in one message, as a short numbered list
in the message body — plain text, never a multi-choice tool.

**Decision 1, the reboot.** The originating todo asks for a reboot plus a re-run of the three
discovery commands, because that is the only real proof of the boot-ordering path. The
`systemctl restart docker` already done in Task 2 proves only the restart path. A reboot costs
roughly 60 to 90 seconds of production and nonprod downtime.

**Decision 2, IPv6.** Task 1's live reads found `ip6tables -P INPUT ACCEPT` with `docker-proxy`
bound on `[::]:80` and `[::]:443`, so every published port is reachable over IPv6 with no host
filtering at all — and the DOCKER-USER work in this plan does not touch that path, because IPv6
terminates on a host socket governed by INPUT rather than on FORWARD. This box has no IPv6 egress,
so no off-box IPv6 probe can be run from here.
  </decision>
  <options>
**Decision 1 (reboot):**
1. Reboot now and re-verify with the three discovery commands, closing the todo's second
   requirement in full. Costs 60 to 90 seconds of downtime on both environments.
2. Defer to the next natural reboot; record the boot-path claim in the runbook as explicitly
   unproven, with the command that would prove it. Costs nothing now, leaves a persistence claim
   resting on the unit file rather than on evidence.

Recommended: 1. An unproven persistence claim is precisely the trap the todo calls worse than
having no rule at all.

**Decision 2 (IPv6):**
1. Close it in this same change: mirror the v4 INPUT ruleset into ip6tables and set its policy to
   DROP. Larger blast radius than anything else in this plan, and unverifiable from this box.
2. Leave it open, document it precisely as what this layer deliberately does not cover, and file a
   todo carrying the measured values. The todo gets filed under either option.

Recommended: 2. An unverifiable firewall change is not an improvement, and option 2 still surfaces
the finding rather than burying it.
  </options>
  <resume-signal>The user answers with a number for each decision. If no answer is given, proceed as option 1 for the reboot and option 2 for IPv6.</resume-signal>
  <done>Both decisions recorded, with the chosen option written into the Task 3 documentation.</done>
</task>

<task type="auto">
  <name>Task 3: Update the runbook, the architecture doc and the diagram, re-render, and close the todo</name>
  <files>docs/INFRA_RUNBOOK.md, docs/INFRA_ARCHITECTURE.md, docs/diagrams/infra-packet-path-scenario.mmd, docs/diagrams/infra-packet-path-scenario.png, .planning/todos/pending/2026-09-05-docker-user-chain-empty-on-the-vm.md</files>
  <action>
Every claim below that says the chain is empty is now false and must change in this one commit.
Run `./scripts/render-diagrams.sh --check infra-packet-path-scenario` first and record the result,
so the before-state of the diagram is known rather than assumed.

In `docs/diagrams/infra-packet-path-scenario.mmd`, replace the `docker_user` node's label — it
currently reads that the chain is empty as of a date — with the policy actually in force: the
established-return, the non-eth0 return, the two permitted published ports, and the default drop,
dated 2026-09-06. Keep the label within the layout rules in `docs/DIAGRAM_CONVENTIONS.md`; consult
that file before changing node text, since it is what governs these flowcharts. Then re-render with
`./scripts/render-diagrams.sh infra-packet-path-scenario` — the digest-pinned Docker renderer named
in that script's header, never a `pnpm dlx` substitute, per its own decision record. Commit the
regenerated PNG in the same commit as the `.mmd` edit.

In `docs/INFRA_ARCHITECTURE.md`, update three places, not one. The packet-path Scenario section's
paragraph asserting the chain is empty becomes a statement of the policy in force, citing the
measured evidence from Task 1 — the before and after off-box probe results and the DROP counter
reading — because a claim that a firewall works is worth exactly the measurement behind it. The
paragraph that frames this as a tracked, dated claim should now record that the tracked item closed
and how it was proven. The trust-boundary prose above the Physical view, which says the external
Netcup firewall is the only layer governing traffic to a published container port, is now wrong and
must say that a second, version-controlled layer exists. Add the IPv6 caveat from this plan's IPv6
section as an explicit statement of what this layer does not cover — silence there would read as
coverage.

In the Maintenance Note, the bullet about the VM's iptables facts currently anticipates going stale
when someone adds rules. That has now happened, so rewrite it to name the new source of truth
(`infra/vm/docker-user-firewall.sh`) and the drift command that detects divergence between the
committed ruleset and the live chain.

In `docs/INFRA_RUNBOOK.md`, the Firewall section's three-row observation table has a row asserting
the chain is empty; replace it with the policy now in force and its consequence. The "What is still
open" paragraph must be rewritten to reflect what genuinely remains open — the Netcup layer being
outside version control, plus the IPv6 path — rather than the DOCKER-USER gap that is now closed.
Add a Layer 3 subsection after Layer 2 carrying the ruleset, the systemd unit and its install path,
the deliberate choice not to use `netfilter-persistent save` with its reason, the re-verification
commands, and the reboot evidence or its explicit absence per the checkpoint decision.

Finally, move the todo file from `.planning/todos/pending/` to `.planning/todos/completed/`,
appending a Resolution section that states what was added, the evidence that proves it, and which
of its two original requirements (persistence, re-verification) were met by which mechanism —
including the deliberate override of its own `netfilter-persistent save` suggestion and why. File a
new todo for the IPv6 finding carrying the measured values.

Note for budgeting: the pre-commit hook runs gitleaks, then Spotless, then the full Java test
suite, even on a docs-and-shell-only change.
  </action>
  <verify>
    <automated>./scripts/render-diagrams.sh --check infra-packet-path-scenario &amp;&amp; grep -q 'ctorigdstport' docs/diagrams/infra-packet-path-scenario.mmd &amp;&amp; grep -q 'infra/vm/docker-user-firewall.sh' docs/INFRA_RUNBOOK.md &amp;&amp; grep -q 'infra/vm/docker-user-firewall.sh' docs/INFRA_ARCHITECTURE.md &amp;&amp; test -f .planning/todos/completed/2026-09-05-docker-user-chain-empty-on-the-vm.md &amp;&amp; test ! -f .planning/todos/pending/2026-09-05-docker-user-chain-empty-on-the-vm.md &amp;&amp; git diff --cached --name-only | grep -q 'infra-packet-path-scenario.png'</automated>
    <human-check>Read the rendered PNG and confirm the DOCKER-USER node states the live policy and that no title or node overlap was introduced by the longer label.</human-check>
  </verify>
  <done>The mermaid source, its regenerated PNG, both docs and the todo are updated in one commit; `--check` passes for the re-rendered diagram; no remaining prose in either doc describes the chain as empty; the IPv6 caveat appears in both the architecture doc and a newly filed todo.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| internet to eth0 | Untrusted packets reach the VM's external NIC; the Netcup Cloud Firewall is the only prior filter and is outside this repository. |
| eth0 to container (DNAT/FORWARD) | The boundary this task adds enforcement at. Currently unpoliced by anything version-controlled. |
| operator workstation to VM (SSH) | Root-equivalent control plane; the change is applied through it. |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-feq-01 | Information disclosure | Any Docker-published host port other than 80/443 | high | mitigate | The DOCKER-USER default DROP from the design section, proven by the off-box canary probe in Task 1. |
| T-feq-02 | Denial of service | Caddy on 80/443 | high | mitigate | The two `--ctorigdstport` RETURN rules, plus the armed `systemd-run` rollback timer and the post-apply health-endpoint checks in Task 1 steps 4 and 8. |
| T-feq-03 | Denial of service | Container egress (ACME renewal, image pulls, Kafka) | high | mitigate | The RELATED,ESTABLISHED RETURN placed first, and the `! -i eth0` RETURN; verified by both health endpoints answering after the docker restart in Task 2. |
| T-feq-04 | Tampering | The live ruleset drifting from the committed one | medium | mitigate | `docker-user-firewall.sh check` plus the `PartOf=docker.service` reapplication; the drift command is recorded in the runbook by Task 3. |
| T-feq-05 | Information disclosure | The IPv6 path to published ports (`ip6tables -P INPUT ACCEPT`) | high | accept | Out of this task's brief and unverifiable from a box with no IPv6 egress. Explicitly documented as not covered and filed as a todo (Task 3); escalated at the checkpoint for the user's decision rather than decided silently. |
| T-feq-06 | Elevation of privilege | SSH lockout while applying rules | low | accept | Structurally impossible: sshd is a host daemon on INPUT and this change touches only FORWARD's DOCKER-USER. The script never writes to INPUT. |

No package-manager installs occur in this plan, so the package-legitimacy gate does not apply.
</threat_model>

<verification>
- `ssh netcup-prod '/usr/local/sbin/docker-user-firewall.sh check'` exits 0.
- The off-box canary probe result inverted across the apply (reachable before, timeout after), with
  the DROP rule's packet counter incremented.
- Both public health endpoints return 200 and `ssh netcup-prod true` succeeds, after the apply and
  again after `systemctl restart docker`.
- `./scripts/render-diagrams.sh --check infra-packet-path-scenario` passes against the regenerated
  PNG.
- No prose in `docs/INFRA_RUNBOOK.md` or `docs/INFRA_ARCHITECTURE.md` still describes DOCKER-USER
  as empty.
- The originating todo is in `.planning/todos/completed/` with a Resolution; an IPv6 todo is filed.
</verification>

<success_criteria>
A published container port other than 80/443 is unreachable from the internet because of a rule
that lives in this repository, that reapplies itself when dockerd restarts, and whose effect was
measured from off-box both before and after it existed — with the two documents and one diagram
that described the old state updated in the same commit, and the diagram re-rendered through the
pinned renderer.
</success_criteria>

<output>
Create `.planning/quick/260906-feq-docker-user-iptables-rules-on-the-vm-sys/260906-feq-SUMMARY.md` when done.
</output>
