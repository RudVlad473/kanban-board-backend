---
created: 2026-09-24T00:00:00.000Z
title: "Netcup VPS shows intermittent TCP retransmits and a bitrate dip under sustained iperf3 throughput"
area: infra
severity: minor
files:
  - docs/incidents/2026-09-24-netcup-network-diagnostics/diagnostics.txt
  - docs/incidents/2026-09-24-netcup-network-diagnostics/README.md
---

## Problem

Filed while triaging an orphaned `docs/netcup-report/` directory (found untracked, zero
references anywhere in the repo) during the 2026-09-25 docs cleanup — the evidence itself
predates the todo. A rescue-system network diagnostic session was run against the production VM
(`v2202608397723499373`, 159.195.114.230) on 2026-09-24, per Netcup's own troubleshooting
instructions, covering DHCP connectivity, MTR loss analysis, and a 10-second iperf3 throughput
test. Full raw output: `docs/incidents/2026-09-24-netcup-network-diagnostics/diagnostics.txt`.

Two of the three findings are most likely non-issues (recorded in the incident README for
context): no DHCP server answered on the rescue system's segment (expected — static addressing
works fine in the real deployment), and both MTR runs show high *reported* ICMP loss at single
hops that doesn't compound downstream — consistent with those routers deprioritizing their own
ICMP TTL-exceeded replies, not real packet loss.

The third finding is the one worth acting on:

```
[  5]   0.00-10.00  sec   310 MBytes   260 Mbits/sec  344            sender
[  5]   0.00-10.04  sec   307 MBytes   257 Mbits/sec                  receiver
```

iperf3 throughput averaged 260 Mbit/s over 10s with **344 TCP retransmissions**, and a visible
bitrate dip in the final three 1-second intervals (222 → 206 → 199 Mbit/s, down from a steady
~290-300 Mbit/s in the first six seconds). Retransmits are triggered by actual packet
loss/reordering TCP itself observed, not an ICMP-probe artifact — this is a real, if
intermittent, throughput/loss signal on the path, separate from the already-tracked
[2026-09-05 nonprod SYN-loss incident](../../docs/incidents/2026-09-05-nonprod-syn-loss/) (that
one is about connection establishment failing outright; this one is about degraded throughput on
an established connection). It is not yet established whether the two share a root cause.

## Solution

Not urgent (10s of degraded throughput mid-test, not an outage), but worth a repeat measurement
to see if it's a one-off or a recurring pattern:

1. **Re-run the iperf3 test** from the rescue system (or, better, from a location with a public
   iperf3 server on the other end, to rule out anything specific to the original test's target)
   for a longer duration (60s+) to see if the retransmit/dip pattern recurs, is periodic, or was
   a one-off.
2. **Correlate timing** against the VM's own resource state at the time (CPU steal time,
   network interface error counters via `ip -s link`) — if it's Netcup-side contention/rate-limiting
   rather than a real path issue, that would show up as CPU steal or interface drops coinciding
   with the dip.
3. **If it recurs**, file a Netcup support ticket with the reproduction, following the same
   evidence-first pattern as the 2026-09-05 incident's `netcup-ticket.txt`.
4. **If it doesn't recur**, downgrade this to a non-issue and close — a single 10-second sample
   with an unremarkable retransmit count is thin evidence on its own.
