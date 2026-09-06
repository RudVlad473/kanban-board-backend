---
created: 2026-09-05T00:00:00.000Z
title: "DOCKER-USER chain is empty on the VM — no runtime layer governs container-published ports"
area: security
severity: moderate
files:
  - docs/INFRA_RUNBOOK.md
---

## Problem

Filed from quick task 260905-qxi (compose published-ports gate). Verified live on the production
VM on 2026-09-05, reproducible with:

```
iptables -t nat -S PREROUTING   # -A PREROUTING -m addrtype --dst-type LOCAL -j DOCKER
iptables -S INPUT               # -P INPUT DROP, plus 80/443 ACCEPTs
iptables -S DOCKER-USER         # empty — no rules at all
```

Docker's own DNAT jump sends every published port through `FORWARD`, never `INPUT`, so the `-P
INPUT DROP` policy and its 80/443 ACCEPTs (`docs/INFRA_RUNBOOK.md`'s Firewall § Layer 1) govern
host-level daemons only — sshd on :22 — and are decorative for anything Docker publishes.
`DOCKER-USER` is the one chain Docker guarantees it will not touch on `docker` restart or `compose
up`, which makes it the correct place to put a container-traffic policy that survives those
operations. It currently carries nothing, so no OS-level layer on this VM governs container
traffic at all; the only thing standing between a published container port and the internet today
is the Netcup Cloud Firewall (Layer 2), which lives outside this repository and is not reviewed in
pull requests.

## Solution

Add rules to `DOCKER-USER` on the VM restricting inbound container-destined traffic to the same
policy already documented for Layer 1 (22/80/443 accepted, everything else dropped) — a genuine
second enforcing layer for anything Docker publishes, not merely a comment claiming one exists.
Two things make this a real fix rather than a one-off:

1. **Persistence.** These rules must survive the same events the existing Layer 1 rules already do
   — a reboot and a `netfilter-persistent save`/reload — the same discipline documented for the
   existing `iptables-persistent` setup (`docs/INFRA_RUNBOOK.md`'s Firewall § Layer 1). A rule
   added by hand and never persisted is invisible after the next reboot, which is a worse trap than
   not having the rule at all.
2. **Re-verification.** After adding the rules, reboot the VM and re-run the same three commands
   used to find this gap (`iptables -t nat -S PREROUTING`, `iptables -S INPUT`, `iptables -S
   DOCKER-USER`) to confirm the ruleset survived and still matches intent, the same pattern
   `docs/INFRA_RUNBOOK.md`'s existing Layer 1 verification already follows.

This is deliberately split from `scripts/verify-compose-ports.py` (quick task 260905-qxi) rather
than folded into it: that gate reads the COMMITTED compose file and makes a `ports:` addition a
reviewed pull-request decision; this item is about the RUNNING host, and no CI check against a git
repository can reach into a live VM's iptables state. The two are complementary layers, not
substitutes — closing this item does not make that gate redundant, and merging that gate does not
close this item.

## Resolution

Closed by **quick task 260906-feq (2026-09-06)**. `DOCKER-USER` now carries a real, version-controlled
policy: allow `RELATED,ESTABLISHED`, allow non-`eth0` traffic, allow TCP to the two published ports
(80/443, matched via `--ctorigdstport` so the rule reads the pre-DNAT host port rather than the
post-DNAT container port), drop the rest. Source of truth: `infra/vm/docker-user-firewall.sh`.

Both of this todo's original requirements were met, by two separate mechanisms:

1. **Persistence** — met by `infra/vm/docker-user-firewall.service`, a `Type=oneshot`,
   `RemainAfterExit=yes` systemd unit with `PartOf=docker.service`, NOT by
   `netfilter-persistent save` as this todo originally suggested. That suggestion was deliberately
   overridden: `iptables-save` captures the entire filter table, so saving now would also freeze a
   snapshot of Docker's own runtime-managed chains (`DOCKER`, `DOCKER-BRIDGE`, `DOCKER-CT`,
   `DOCKER-FORWARD`) alongside this chain's five rules — the `/etc/iptables/rules.v4` already on
   this VM (dated 2026-08-14) is exactly that kind of stale snapshot, and its boot-time restore
   races dockerd's own chain rebuild since netfilter-persistent runs before `docker.service`.
   `PartOf=docker.service` instead re-applies the ruleset deterministically after every dockerd
   restart — verified against both a hand-flush + unit restart and a full `systemctl restart docker`
   (all 6 containers cycled, health endpoints returned `200` afterward) — and this repository now
   holds the reviewed source of truth this todo asked for.
2. **Re-verification** — met by re-running the same three commands this todo specified
   (`iptables -t nat -S PREROUTING`, `iptables -S INPUT`, `iptables -S DOCKER-USER`) immediately
   after a genuine VM reboot (confirmed via `uptime -s`, not a stale SSH session), per an explicit
   operator decision to close this requirement in full rather than defer it — ~59s from reboot
   command to both health endpoints confirmed `200`. `DOCKER-USER` showed all five rules with no
   manual reapplication; `docker-user-firewall.sh check` and `systemctl is-enabled`/`is-active`
   both confirmed clean.

The fix was additionally proven with an off-box falsification test this todo did not originally ask
for: a throwaway canary container on a port nothing legitimately serves answered `200` from off-box
before the rules were applied and timed out (`curl` exit 28) after, with the chain's own DROP-rule
packet counter incrementing by exactly the SYNs the after-probe generated while the 80/443
RETURN-rule counters kept rising under real concurrent traffic — attributing the drop specifically
to `DOCKER-USER`, not to the packet never arriving. Full evidence trail:
`.planning/quick/260906-feq-docker-user-iptables-rules-on-the-vm-sys/260906-feq-SUMMARY.md`.

**Scope not closed by this change, tracked separately:** the IPv6 path. `ip6tables -P INPUT ACCEPT`
remains this VM's live policy and `docker-proxy` binds `[::]:80`/`[::]:443` directly — inbound IPv6
to a published port terminates on that host socket (`ip6tables INPUT`), never on `FORWARD`, so
nothing added here can reach it. Every published port remains reachable over IPv6 with zero
host-level filtering. See the new todo filed alongside this one for the measured values and the
explicit decision to leave it open.
