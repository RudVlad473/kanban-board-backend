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
