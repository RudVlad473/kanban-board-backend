# External Network Audit — Plan 05-06 Task 1 (2026-08-17)

Proves INFRA-08 by external measurement rather than by reading rule lists — Docker inserts
forwarding rules that bypass the input chain a local `iptables -L` listing shows, so only a scan
run from off-VM, against the composite result of both firewall layers, is evidence.

**Execution note, consistent with the "Manual deploy — Plan 05-04" precedent above:** this audit
was run directly by the orchestrating agent from its own machine (genuinely off-VM, genuinely not
on the same network as the Netcup VM), not hand-typed by a human pasting results back one command
at a time as the plan's default guided-execution protocol specifies. The environment for this
session has direct SSH access to `netcup-prod` and `gh` CLI access to the repository, which made
guided step-by-step human relay unnecessary for the read-only and reversible steps below; the two
genuinely destructive/downtime-causing steps (Docker daemon restart, VM reboot) were still gated
on explicit human confirmation before being triggered, consistent with this project's
credential/downtime handling norms.

## Scan tooling

- `nmap` was tried first (installed via `winget`) but produced unreliable false-negatives on
  Windows without the Npcap packet-capture driver — its `-sT` connect-scan mode intermittently
  misreported known-open ports as filtered, caught by cross-checking against a working `curl`
  HTTPS request that succeeded while `nmap` reported 443 filtered. Abandoned in favor of a
  purpose-built Python `socket.connect_ex` concurrent TCP-connect scanner (stdlib only, no
  install needed) — functionally equivalent to `nmap -sT`, and empirically cross-checked reliable
  (see the two-pass IP comparison below).
- Full range 1-65535 scanned three independent times: IP pass 1, an immediate IP pass 2 re-run,
  and a hostname pass 1. Shape: `ThreadPoolExecutor` with 400 concurrent workers, 1.5s per-port
  timeout, calling `socket.connect_ex((target, port))` for every port 1-65535; `0` classified
  open, `ECONNREFUSED`/10061 closed, anything else (timeout/other) filtered.

## Full-range scan results (1-65535, three independent passes)

| Pass | Target | Started (UTC) | Ended (UTC) | Ports probed | Open | Closed | Filtered |
|------|--------|---------------|-------------|---------------|------|--------|----------|
| IP pass 1 | `159.195.114.230` | 2026-08-17T08:31:27Z | 2026-08-17T08:35:46Z | 65535 | 22, 80 | 0 | 65533 |
| IP pass 2 (immediate re-run) | `159.195.114.230` | 2026-08-17T08:36:22Z | 2026-08-17T08:40:29Z | 65535 | 22, 80, 443 | 0 | 65532 |
| Hostname pass 1 | `kanban-board-rud-vlad-473.duckdns.org` | 2026-08-17T08:40:48Z | 2026-08-17T08:44:58Z | 65535 | 22, 80, 443 | 0 | 65532 |

**IP pass 1 had a false negative on port 443** — caught immediately, not silently accepted: an
independent isolated `connect_ex` check against port 443 alone, plus a `curl` HTTPS request to
the hostname, both succeeded, proving 443 was actually open the whole time and pass 1's own
concurrency (400 simultaneous connections) caused a transient miss on that one port. IP pass 2
(re-run immediately after, same parameters) came back clean — `22, 80, 443` — confirming pass 1's
miss was a scan artifact, not a real state change between the two passes. The hostname pass came
back clean on its first attempt.

**Conclusion: exactly 22, 80, 443 open on both the IP and the hostname; every other port across
the full 1-65535 range returns no response (filtered) rather than a service — confirmed by 3
independent full-range scans totaling 196,605 port probes, with the one observed false negative
independently caught and explained, not glossed over.**

## Direct-connection probes (individual, not part of the range sweep)

Separate `connect_ex` calls, 5s timeout each, against both the IP and the hostname, targeting the
ports the plan specifically names as a response-is-a-failure test: 19092 (Kafka external broker
address), 8081 (Schema Registry), 9092 (Kafka internal-listener naming), 33145 (Redpanda RPC),
8080 (app direct), 5432 (Postgres — not applicable to this stack, since production's database is
Neon, included deliberately for a clean negative control), and 3000.

All 14 probes (7 ports x 2 targets) timed out after ~5.0-5.8s with zero response — Windows errno
10035 (`WSAEWOULDBLOCK`) meaning the connection attempt was still pending at timeout, i.e. no
SYN-ACK was ever received on any of them. No port produced a service response. This satisfies the
plan's "a response is a failure" criterion directly: none occurred.

## Layer 1 — OS-level `iptables`, re-verified at audit time via SSH to `netcup-prod`

```
Chain INPUT (policy DROP)
1  ACCEPT  ctstate RELATED,ESTABLISHED
2  ACCEPT  in lo
3  ACCEPT  tcp dpt:22
4  ACCEPT  tcp dpt:80
5  ACCEPT  tcp dpt:443
```

Matches plan 05-03's recorded intent exactly — zero drift found. `/etc/iptables/rules.v4` (the
persisted on-disk ruleset `netfilter-persistent save` writes) matches the live ruleset
byte-for-byte; `netfilter-persistent.service` is enabled.

## Layer 2 — Netcup Cloud Firewall, re-verified at audit time via the SCP web panel

No CLI or API exists for this layer (Netcup does not expose one) — the operator confirmed this
directly in the SCP web console, since this layer has no read-only automation surface. "Firewall
active" toggle: ON. Rule policies, top to bottom (first match wins): (1) "netcup Mail block"
[Netcup default, unrelated to this app] — drops outgoing SMTP/465/587; (2) "netcup Ping allow"
[Netcup default] — allows incoming/outgoing ICMP + ICMPv6 ping; (3) "Default" (the custom policy
plan 05-03 created) — ACCEPT incoming HTTP/80, ACCEPT incoming HTTPS/443, ACCEPT incoming SSH/22,
exactly matching recorded intent, zero drift, and no leftover debug rules from plan 05-04's
manual deploy session; (4) implicit system rules — drop all incoming, accept all outgoing.

**No unexpected rule found at either layer, and no drift requiring restoration was found — this
audit's "record drift and restore" branch was not exercised because there was nothing to
restore.**

**Note on layer count:** only 2 network layers exist for this deployment (OS `iptables` + Netcup
Cloud Firewall), not 3. The plan's "all three network layers" language is stale, carried over from
the superseded Oracle Cloud Security-List/NSG/OS-firewall three-layer model this project pivoted
away from in plan 05-03 (see "Provider history" above). This runbook has documented the 2-layer
model since plan 05-03 (see "Firewall — two independent layers" above); this audit's structure
follows that, not the plan text's stale 3-layer framing.

## Restart and reboot persistence

Both performed live against production, with explicit human confirmation before triggering,
since both cause brief real downtime:

1. **`sudo systemctl restart docker`** on `netcup-prod` — all 3 containers (`app`, `caddy`,
   `redpanda`) came back `Up 27 seconds (healthy)` / `(healthy)` within seconds. Post-restart:
   `iptables` rules unchanged; an external HTTPS request to the hostname returned `404`
   (Caddy/the app responding correctly again, `404` because the request path had no matching
   route — not an error state).
2. **`sudo reboot`** on `netcup-prod` — the VM came back within under 90 seconds (`uptime` showed
   "up 0 min" at the first post-reboot check). Post-reboot: `iptables` rules were restored
   identically via `netfilter-persistent` (enabled and active); the Docker service was active and
   enabled; all 3 containers came back healthy automatically within 15 seconds (the `restart:
   unless-stopped` policy proven working, not merely configured); and a fresh 10-port targeted
   scan plus an external HTTPS request both confirmed the exact same 22/80/443-only profile as
   before the reboot.

## Audit date

2026-08-17.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
