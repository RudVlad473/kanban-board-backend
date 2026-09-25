# 2026-09-24 — Netcup VPS network diagnostics (rescue system)

Diagnostic evidence gathered against the production VM (`v2202608397723499373`,
159.195.114.230) from Netcup's rescue system, per Netcup's own troubleshooting
instructions. Distinct methodology from the [2026-09-05 nonprod SYN-loss
incident](../2026-09-05-nonprod-syn-loss/) (that one used a client-side connect
prober; this one used the rescue system's own DHCP/MTR/iperf3 tooling) — kept
separate rather than folded in, since it is not yet established whether they
share a root cause.

| File | What it is |
| --- | --- |
| `diagnostics.txt` | Full rescue-system session: DHCP connectivity test, MTR loss analysis (both directions), and a 10-second iperf3 throughput test |

## Findings

- **DHCP**: no DHCP server answered on the rescue system's segment; only a link-local
  (APIPA) fallback address was obtained. Static addressing works fine — likely expected
  rescue-environment behavior, not evidence of a host network fault.
- **MTR**: both directions show a single hop with high reported ICMP loss (hop 4
  server-side, hop 5 and hop 11 client-side), while every hop downstream of each stays
  near 0% loss. Loss that does not compound forward is consistent with those routers
  deprioritizing/rate-limiting their own ICMP TTL-exceeded replies rather than dropping
  real traffic — likely a red herring, not a real path problem.
- **iperf3**: throughput averaged 260 Mbit/s over 10s with 344 TCP retransmissions and a
  visible bitrate dip in the final three 1-second intervals (222 → 206 → 199 Mbit/s).
  Retransmits are triggered by actual packet loss/reordering TCP observed, not an
  ICMP-probe artifact — **this is the one measurement here indicating a real, if
  intermittent, throughput issue on the path.**

## Status: open, not yet filed as a todo

This evidence was gathered but never written up into a tracked follow-up. The iperf3
retransmit/bitrate-dip finding is the one worth acting on; the DHCP and MTR findings are
most likely expected/non-issues per the reasoning above. Moved here from a loose,
unreferenced `docs/netcup-report/` directory during the 2026-09-25 docs cleanup — no
prior doc or todo referenced it.
