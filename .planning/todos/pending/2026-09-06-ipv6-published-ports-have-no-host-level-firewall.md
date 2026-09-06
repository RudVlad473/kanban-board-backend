---
created: 2026-09-06T00:00:00.000Z
title: "IPv6 published ports have no host-level firewall — DOCKER-USER's new policy cannot reach this path"
area: security
severity: moderate
files:
  - docs/INFRA_RUNBOOK.md
  - docs/INFRA_ARCHITECTURE.md
  - infra/vm/docker-user-firewall.sh
---

## Problem

Filed alongside quick task 260906-feq (DOCKER-USER IPv4 ruleset). Measured live on the production
VM during that task's planning, on 2026-09-06:

```
ip6tables -S INPUT      # -P INPUT ACCEPT
ip6tables -S FORWARD    # -P FORWARD ACCEPT, DOCKER-USER also empty on the v6 side
docker info             # EnableUserlandProxy: true, docker-proxy bound to [::]:80 and [::]:443
```

Because the containers hold no IPv6 address, inbound IPv6 traffic to a published port (80/443)
terminates on the host's `docker-proxy` socket and is evaluated by **`ip6tables INPUT`**, whose
policy is `ACCEPT` — not by `FORWARD`, which is the only chain 260906-feq's `DOCKER-USER` change
touches. Consequence: **every published port is reachable over IPv6 today with zero host-level
filtering**, exactly as before that task's IPv4 fix. The IPv4 closure genuinely does not extend
here; this is a structurally separate gap, not an oversight in the same fix.

This box (the dev machine used to plan and execute 260906-feq) has **no IPv6 egress at all** —
`curl -6 ifconfig.me` returns empty with no default IPv6 route — so no off-box IPv6 probe analogous
to 260906-feq's IPv4 canary test can be run from here. This was the deciding factor in the operator
decision (2026-09-06, at 260906-feq's checkpoint) to leave this open rather than close it in the
same change: an unverifiable firewall change is not an improvement over a documented, honest gap.

## Solution

Two independent halves, either of which closes this on its own or together:

1. **Mirror the IPv4 `DOCKER-USER` ruleset into `ip6tables`.** The same five-rule shape
   (RELATED,ESTABLISHED first, non-`eth0` second, TCP 80/443 via `--ctorigdstport`, DROP last)
   applies to `ip6tables -S DOCKER-USER`, plus setting `ip6tables -P FORWARD DROP` to match the v4
   `FORWARD` policy already in force. This closes the FORWARD-side v6 gap but NOT the docker-proxy
   INPUT path described below — both are needed for full v6 parity with the v4 fix.
2. **Address the `docker-proxy`/INPUT path specifically.** Since IPv6 traffic to a published port
   never reaches FORWARD, closing this gap requires either an `ip6tables INPUT` policy change
   (`-P INPUT DROP` plus matching ACCEPTs for 22/80/443, mirroring the existing IPv4 `Layer 1`) or
   disabling `EnableUserlandProxy` in the Docker daemon config so IPv6 traffic is DNAT'd through
   FORWARD like IPv4 already is (a larger blast-radius change — verify this doesn't break the
   existing IPv4 path or other daemon behavior before adopting).

Either path needs an off-box IPv6 verification method this box cannot provide — a different dev
machine, a cloud shell, or a scriptable third-party IPv6 reachability checker — before it can be
proven the way 260906-feq proved the IPv4 fix (measured before/after, packet-counter attribution).
Do not close this todo on a v6 change nobody could verify actually took effect; that would repeat
exactly the trap 260906-feq's own checkpoint decision was raised to avoid.
