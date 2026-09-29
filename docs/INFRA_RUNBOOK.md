# Production Infrastructure Runbook

This document records the actual, currently-provisioned state of the production VM for v1.2's
Infra Migration milestone (Phase 5). It exists so a future session (human or agent) can answer
"what is this box, and how is it locked down" without re-deriving it from scratch. No secrets, no
private key material, and no connection strings are recorded here — see `.env.prod.example` for
the shape of what production actually needs, populated separately and never committed.

## History

Dated, one-off infrastructure session records (deploys, incidents, resource measurements, cutovers) live in [`docs/history/`](history/), one file per event, named `YYYY-MM-DD-<slug>.md`. This file holds only durable how-to/current-state reference material.

## Provider and host

| Field | Value |
|-------|-------|
| Provider | Netcup |
| Product | VPS Lite 2 G12s (ordered), shown in the Netcup SCP as "VPS 1000 G12" — Netcup's internal/panel naming differs from the marketing/order-page name; same underlying product, not a discrepancy in what was provisioned |
| Datacenter | Vienna, Austria |
| Public IPv4 | `159.195.114.230` |
| Public IPv6 | `2a0a:4cc0:61:67c::/64` |
| Hostname (Netcup-assigned) | `v2202608397723499373` |
| Spec (ordered) | 4 vCPU / 8 GB RAM / 160 GB NVMe SSD, hourly billing |
| Spec (as provisioned, measured) | 4 vCPU, 7.8 GiB RAM, 251 GB disk on `/dev/vda4` — disk exceeds the advertised 160 GB; RAM/CPU match |
| OS | Debian GNU/Linux 13 (trixie), kernel `6.12.101+deb13-amd64` |

**Provider history:** this VM replaces an originally-planned Oracle Cloud `eu-zurich-1`
`VM.Standard.A1.Flex` target. Oracle's Always Free ARM capacity in that region proved structurally
unavailable (200+ automated provisioning attempts across 10+ hours, zero successes — the region is
single-availability-domain with no ETA on capacity). See `.planning/phases/05-infra-migration/`
plan 05-03's SUMMARY for the full pivot rationale. **Architecture note:** Oracle's target was
ARM64/Ampere; Netcup's is x86_64. `.github/workflows/deploy.yml`'s Docker build step was originally
cross-compiled for `linux/arm64` via QEMU and has been corrected to build `linux/amd64` natively —
if any other artifact in this repo is found assuming ARM64, treat it as a bug from the same root
cause, not a new problem.

## Access

- SSH, key-only. `PasswordAuthentication no` and `PermitRootLogin prohibit-password` are both set
  in `/etc/ssh/sshd_config` — root login requires the key, password auth is rejected outright
  (verified: a forced-password-auth connection attempt gets `Permission denied (publickey)`).
- The server's `~/.ssh/authorized_keys` contains exactly one key, labelled
  `kanban-backend-prod-netcup` — a dedicated, no-passphrase ED25519 keypair generated specifically
  for this server (local path: `~/.ssh/id_ed25519_netcup_prod`), distinct from any personal admin
  key. No-passphrase is a deliberate trust-model choice enabling non-interactive automation, not an
  oversight — the private key file is itself a bearer credential for root access.
- A local `~/.ssh/config` `Host netcup-prod` entry wraps the IP/user/identity-file/`IdentitiesOnly`
  so `ssh netcup-prod` works without repeating `-i` — a plain `ssh root@<ip>` will fail with
  `Permission denied (publickey)` unless the client's default identity happens to match, since the
  server does not have any personal key installed.
- Docker access is root-only; no separate non-root deploy user or `docker` group member exists.

## Firewall — three layers

**UPDATED 2026-09-06 (quick task 260906-feq)** — `DOCKER-USER` no longer carries "nothing at all";
it is now a real, version-controlled layer (Layer 3 below). The table's third row previously read
"Empty — no rules at all"; that has changed, and this section changes with it rather than being
left describing a chain that no longer matches reality.

| Observation | Command | Consequence |
|-------------|---------|-------------|
| `-A PREROUTING -m addrtype --dst-type LOCAL -j DOCKER` present; the `DOCKER` chain DNATs published ports to their container | `iptables -t nat -S PREROUTING` | DNAT'd traffic traverses `FORWARD`, **not** `INPUT` |
| `-P INPUT DROP` plus `--dport 80/443` ACCEPTs (the ruleset below) | `iptables -S INPUT` | Governs host-level daemons only — sshd on :22. Decorative for anything Docker publishes |
| Five rules: allow established, allow non-`eth0`, allow TCP 80/443 (`--ctorigdstport`), drop the rest | `iptables -S DOCKER-USER` | The chain Docker guarantees it will not touch now carries a real, version-controlled policy for container-published traffic — see Layer 3 below |

Operationally: a `ports:` key added to any service in a deployed compose file is a public exposure
first gated by the compose-file review gate, then by Layer 3 below if it slips through anyway, and
finally by the Netcup Cloud Firewall (Layer 2), which lives outside this repository, is not
reviewed in pull requests, and is not under version control. As of quick task 260905-qxi,
`scripts/verify-compose-ports.py` makes the compose-file half of that exposure a reviewed decision
instead of a silent one: it fails CI on a pull request that adds a `ports:` key to any service
outside `caddy` in `docker-compose.prod.yml`, on any service at all in `docker-compose.nonprod.yml`,
on `network_mode: host` anywhere, or on `caddy` publishing beyond 80/443.

What is still open, genuinely, after this change:

1. **The Netcup Cloud Firewall (Layer 2) remains outside version control.** It is not reviewed in
   pull requests and this repository cannot confirm its live ruleset from code — Layer 3 closes the
   in-VM gap, not this one.
2. **IPv6 is not covered by Layer 3 at all.** `ip6tables -P INPUT ACCEPT` is still the live policy,
   and `docker-proxy` binds `[::]:80`/`[::]:443` directly — inbound IPv6 to a published port
   terminates on that host socket and is evaluated by `ip6tables INPUT`, never by `FORWARD`, so
   nothing this task added can reach it. Every published port is reachable over IPv6 with no
   host-level filtering today. Measured during 260906-feq's planning, deliberately out of that
   task's scope (unverifiable from a box with no IPv6 egress — confirmed via `curl -6 ifconfig.me`
   returning empty with no default v6 route), and tracked in a dedicated todo rather than silently
   left uncovered. See Layer 3 below for the full measured values.
3. **Layer 3 reads only the committed script, same caveat as the compose gate.** A `docker run -p`
   issued by hand on the VM, or a manual `iptables -F DOCKER-USER` followed by no reapplication
   before the systemd unit next fires, is invisible until `docker-user-firewall.sh check` is run —
   see Layer 3's re-verification command below.

### Layer 1: OS-level (`iptables`, `nft` backend)

```
Chain INPUT (policy DROP)
ACCEPT  ctstate RELATED,ESTABLISHED
ACCEPT  in lo
ACCEPT  tcp dpt:22
ACCEPT  tcp dpt:80
ACCEPT  tcp dpt:443
```

Persisted via `iptables-persistent`/`netfilter-persistent` (`netfilter-persistent save`, rules live
in `/etc/iptables/rules.v4`). Verified to survive a full reboot. No ICMP allow rule exists at this
layer by design — the plan's spec only calls for TCP 22/80/443, so this box does not answer `ping`
even though it is fully reachable on those three ports. As the correction above states, this
ruleset's practical reach ends at host daemons (sshd) — it does not evaluate traffic to a
Docker-published port at all.

### Layer 2: Netcup Cloud Firewall (SCP-managed, stateful)

Configured as a policy named "Default" ("Basic firewall policy") in the Netcup SCP's Firewall
Policies section, assigned to this specific VPS, positioned before the implicit system
`Drop all INCOMING` catch-all. Rules evaluate top-to-bottom, first match wins. Netcup's own
built-in `netcup Mail block` (drops outgoing SMTP/SMTPS/submission — unrelated to this app) and
`netcup Ping allow` (ICMP accept both directions) policies sit ahead of ours in evaluation order
and do not affect ports 22/80/443.

**Known gotcha, observed 2026-08-14:** immediately after first assigning this policy to the VPS,
the server became completely unreachable — not just SSH, but ICMP too — for over 7 minutes, despite
the SCP displaying what was (and remains) a correct ruleset. Toggling the panel's "Firewall active"
switch off restored access instantly; toggling it back on then worked correctly and has stayed
stable since. This points at a stuck sync/propagation state on Netcup's side when a policy is
first assigned, not a rule-configuration mistake. **If this VM (or a future one) ever goes
unexpectedly unreachable right after a Netcup Cloud Firewall change, try an off/on toggle cycle
before assuming the ruleset itself is wrong.**

### Layer 3: `DOCKER-USER` (in-VM, version-controlled) — added 2026-09-06, quick task 260906-feq

The one chain Docker guarantees it will never write rules into itself, which makes it the correct
place for a container-traffic policy that survives `docker` restarts and reboots without racing
dockerd's own chain rebuild. Filled from empty (the state described above through 2026-09-05) after
`.planning/todos/completed/2026-09-05-docker-user-chain-empty-on-the-vm.md` found that no OS-level
layer on this VM governed container-published traffic at all.

**The ruleset**, applied in this order (source of truth: `infra/vm/docker-user-firewall.sh`):

```
iptables -A DOCKER-USER -m conntrack --ctstate RELATED,ESTABLISHED -j RETURN
iptables -A DOCKER-USER ! -i eth0 -j RETURN
iptables -A DOCKER-USER -p tcp -m conntrack --ctorigdstport 80 -j RETURN
iptables -A DOCKER-USER -p tcp -m conntrack --ctorigdstport 443 -j RETURN
iptables -A DOCKER-USER -j DROP
```

The first two RETURN rules must precede the DROP or container egress (image pulls, Caddy's ACME
renewals, either app reaching Redpanda) breaks — and breaks as a silent hang, since the outbound SYN
leaves fine and only the reply dies. `--ctorigdstport` (not `--dport`) matches the pre-DNAT host
port rather than the post-DNAT container port — today's mappings are identity (80→80, 443→443) so
this would not have been caught by testing if it were wrong, which is exactly why it matters for any
future non-identity mapping. TCP-only, IPv4-only: enabling Caddy's HTTP/3 will require publishing
`443/udp` and adding a matching rule here.

**Persistence — deliberately NOT `netfilter-persistent save`.** `iptables-save` captures the entire
filter table, so saving now would also freeze a snapshot of Docker's own runtime-managed chains
(`DOCKER`, `DOCKER-BRIDGE`, `DOCKER-CT`, `DOCKER-FORWARD`) alongside this one's five rules — the
`/etc/iptables/rules.v4` already on this VM (dated 2026-08-14, predating this change) is exactly
that kind of stale snapshot: a `:DOCKER-USER - [0:0]` chain with no rules, plus dockerd chains that
have since diverged. Boot-time restore from that file also races dockerd's own chain rebuild, since
netfilter-persistent's restore runs before `docker.service` starts. Instead, `infra/vm/docker-user-firewall.service`
(`Type=oneshot`, `RemainAfterExit=yes`, `PartOf=docker.service`) re-applies the ruleset via
`docker-user-firewall.sh apply` after every `docker.service` start — including a plain
`systemctl restart docker`, which the systemd unit alone would miss without `PartOf`. Installed by
hand, deliberately not wired into `deploy.yml` — see `infra/vm/README.md` for why (no review gate at
apply time; iptables is host-global while `deploy.yml`'s scp targets are per-environment).

**Re-verification command** (the drift check): `ssh netcup-prod '/usr/local/sbin/docker-user-firewall.sh check'`.
Exits non-zero and prints the expected-vs-live diff on any mismatch.

**Evidence this actually works, measured 2026-09-06 (quick task 260906-feq):**

- A throwaway canary container publishing host port 49999 (no legitimate reason to be reachable)
  answered `HTTP 200` from off-box before the rules were applied, and timed out (`curl` exit 28)
  after — the required inversion. (The Netcup Cloud Firewall initially masked this port entirely,
  making the probe structurally unable to attribute a Layer-3 effect; a temporary, scoped Netcup
  console rule permitting the probe source IP was opened for the duration of the test and removed
  immediately after, confirmed removed by the operator.)
- The chain's DROP-rule packet counter incremented by exactly the SYNs the after-probe generated (7
  packets) and did not increment further under concurrent legitimate 80/443 traffic, while the 80
  and 443 RETURN rules' own counters rose under that same live traffic (0→1 and 6→30 packets
  respectively) — proving the chain is genuinely on the live packet path, not an inert one nothing
  traverses.
- Both public health endpoints stayed at `200` and `ssh netcup-prod true` kept succeeding throughout
  the apply, immediately after, and again after a full `systemctl restart docker` (all 6 containers
  cycled) and a full VM reboot (~59s from reboot command to confirmed-healthy health endpoints).
- **Reboot proof, closing the originating todo's second requirement in full:** the same three
  discovery commands that originally found the gap were re-run immediately after a genuine reboot
  (`uptime -s` confirmed a fresh boot, not a stale SSH session) —
  `iptables -t nat -S PREROUTING` unchanged, `iptables -S INPUT` unchanged, `iptables -S DOCKER-USER`
  showing all five rules with no manual reapplication. `docker-user-firewall.sh check` and
  `systemctl is-enabled`/`is-active docker-user-firewall.service` both confirmed clean immediately
  after.
- An armed `systemd-run --on-active=600 --unit=fw-rollback` transient timer (flushing `DOCKER-USER`
  automatically) protected the live-apply window in Task 1; it was disarmed once the ruleset was
  confirmed working, before the timer could fire.

**What Layer 3 deliberately does not cover — IPv6.** `ip6tables -P INPUT ACCEPT` is still this VM's
live policy, and Docker's `docker-proxy` binds `[::]:80` and `[::]:443` directly. Because the
containers hold no IPv6 address, inbound IPv6 to a published port terminates on that host socket and
is evaluated by `ip6tables INPUT` — never by `FORWARD`, which is the only chain Layer 3 touches. This
means every published port is reachable over IPv6 today with **zero host-level filtering**, exactly
as before this change. Measured during 260906-feq's planning (`docker info` shows
`EnableUserlandProxy: true` with docker-proxy processes bound to `[::]:80`/`[::]:443`), left
deliberately open on operator decision (2026-09-06): mirroring the IPv4 ruleset into ip6tables would
be unverifiable from this dev box, which has no IPv6 egress at all (`curl -6 ifconfig.me` returns
empty, no default v6 route), so a change here could not be proven working the way the IPv4 change
was. Tracked in a dedicated todo carrying these measured values rather than folded silently into
this "closed" gap.

## Edge hardening on k3s — Plan 13-08 (2026-09-26)

D-13 required the new Traefik edge (13-06's cutover) to be no weaker than the Caddy edge it
replaced, and D-04's later Docker teardown will retire `DOCKER-USER` (Layer 3 above) along with
it — so this plan also had to give k3s's NodePorts/hostPorts a host-level filter that does not
depend on Docker at all. Both obligations closed on evidence below.

### Task 1 — authorization checkpoint

Operator chose **proceed** (both the rate-limit re-derivation and the host firewall install, not
the rate-limit-only fallback) after reviewing the consequences: brief 429s on the operator's own
workstation IP during the probe, and a live `mangle PREROUTING` change protected by a dead-man
switch. Evidence gathered before the decision:

- Live `iptables -S INPUT` allow-list: default-DROP policy, explicit ACCEPT for loopback,
  RELATED/ESTABLISHED, and TCP 22/80/443 — the three ports `k3s-host-firewall.sh` hard-codes.
- Traefik's Service (`kube-system/traefik`) was confirmed as the cluster's only non-ClusterIP
  Service, publishing NodePorts 30080 (web) and 30104 (websecure).

### Task 2 — re-derived Traefik rate limits, proven per-client with two public IPs

**Re-derivation.** Traefik's token bucket starts full at `burst` and refills `average` tokens
per `period`, so the worst-case number of requests one client can get accepted in any window W
is `burst + average × W / period`. Each zone was re-derived to keep that worst case at or under
Caddy's own budget for the equivalent window (`k8s/overlays/prod/ingressroute.yaml`,
`k8s/monitoring/configs/ingressroute.yaml` carry the dated per-Middleware derivation comments):

| Middleware | average | period | burst | worst case | Caddy budget |
|---|---|---|---|---|---|
| `auth-rate-limit` | 10 | 5m | 10 | 10 + 10×300/300 = 20 | 20 / 5m |
| `general-rate-limit` | 60 | 1m | 60 | 60 + 60×60/60 = 120 | 120 / 1m |
| `grafana-login-rate-limit` | 10 | 5m | 10 | 10 + 10×300/300 = 20 | 20 / 5m |

**Fixed along the way (Rule 1):** Traefik's access log was still Common Log Format —
13-02's `logs.access.enabled: true` left the chart on its own default, not JSON — which meant a
`ClientHost`/`DownstreamStatus` field lookup against the real log silently matched nothing rather
than erroring. `k8s/platform/traefik/helmchartconfig.yaml` now sets `logs.access.format: json`
(commit `f7f89c5`).

**Proof A — `verify-rate-limit.yml` green:** run
[36271337736](https://github.com/RudVlad473/kanban-board-backend/actions/runs/36271337736)
(`workflow_dispatch`, head `f7f89c5`), concluded `success` — 25 sequential nonprod signins with
zero 429s, confirming the negative control is unaffected by the prod-only Middleware scoping.

**Proof B — two distinct public client IPs**, read from Traefik's own access log
(`k3s kubectl logs -n kube-system deploy/traefik`) for `/api/signin` around the same run:

| Client IP (/24) | Requests | Status split |
|---|---|---|
| `84.40.156.0/24` (operator workstation) | 1 | 401 ×1 |
| `172.215.209.0/24` (GitHub-hosted runner) | 50 | 401 ×35, 429 ×15 |

Both addresses are public (neither in `10.42.0.0/16` nor the node's own IP), the runner IP alone
took every 429, and the workstation IP's one request returned 401 — proving the rate limit buckets
per client address rather than sharing one bucket across all traffic, and that a legitimately
authenticating client on a different IP is unaffected by another client being throttled.

### Task 3 — `KANBAN-INGRESS`: a k3s-era ingress filter independent of Docker

**Why a new chain, not `DOCKER-USER`.** `DOCKER-USER` only ever sees Docker's own DNAT'd traffic.
k3s's NodePorts and hostPorts are DNAT'd by kube-router's `KUBE-NODEPORTS` (`nat` table) and the
CNI's `CNI-HOSTPORT-DNAT` — neither passes through `DOCKER-USER` at all. The one hook point that
runs before both, regardless of which eventually claims the packet, is `mangle PREROUTING`. See
`infra/vm/k3s-host-firewall.sh`'s own header for the full decision record (why position 1, why
`mangle`, the dead-man switch, IPv4-only scope, and what this script deliberately does not check).

**The ruleset** (source of truth: `infra/vm/k3s-host-firewall.sh`), applied in this order:

```
iptables -t mangle -A KANBAN-INGRESS -m conntrack --ctstate RELATED,ESTABLISHED -j RETURN
iptables -t mangle -A KANBAN-INGRESS -p tcp -m conntrack --ctstate NEW -m tcp --dport 22  -j RETURN
iptables -t mangle -A KANBAN-INGRESS -p tcp -m conntrack --ctstate NEW -m tcp --dport 80  -j RETURN
iptables -t mangle -A KANBAN-INGRESS -p tcp -m conntrack --ctstate NEW -m tcp --dport 443 -j RETURN
iptables -t mangle -A KANBAN-INGRESS -p icmp -j RETURN
iptables -t mangle -A KANBAN-INGRESS -m conntrack --ctstate NEW -j DROP
iptables -t mangle -A KANBAN-INGRESS -j RETURN
iptables -t mangle -I PREROUTING 1 -i eth0 -j KANBAN-INGRESS
```

The 22/80/443 allow-list is a literal copy of the live `iptables -S INPUT` allow-list captured
2026-09-26 (Task 1's checkpoint evidence above), not re-derived at apply time — INPUT governs
host daemons, a slower-changing, separately-reviewed surface from what k3s exposes via Services.

**Install and dead-man switch.** The script and unit were copied to the VM, `apply --dead-man 10`
armed a 10-minute `systemd-run` rollback timer, a **fresh** SSH session confirmed login still
worked, and only then was the timer disarmed — the same discipline as quick task 260906-feq's
`DOCKER-USER` install. `k3s-host-firewall.service` (`Type=oneshot`, `RemainAfterExit=yes`) was
then enabled. No dead-man timer is armed in the current, closed state.

**`systemctl restart k3s` survival.** `iptables -t mangle -S PREROUTING` before and after a full
k3s restart both showed the jump first, with no reapplication needed — neither Docker nor k3s
itself ever touches `mangle PREROUTING`, so nothing races this chain's own state.

**Off-box probe and counter attribution — the proof method's actual gap and resolution.** The
plan's verify method needs an off-box `nc -z` probe against Traefik's `websecure` NodePort
(30104) to fail AND the `KANBAN-INGRESS` DROP counter to increase for that same probe. Netcup's
separate, console-only outer Cloud Firewall (Layer 2 above) already blocks 30104 from the public
internet before packets reach this VM's own iptables at all — so an unmodified off-box probe times
out for the *right general reason* but can never move the Layer 3 counter, since a packet Layer 2
drops never reaches Layer 3. This is the identical structural gap quick task 260906-feq hit
proving `DOCKER-USER` (Layer 3, line ~171 above), resolved the same way: the operator opened a
temporary, scoped Netcup console rule (INCOMING TCP, source `45.134.212.94/32`, destination port
`30104`, ACCEPT) that lets the probe traffic reach the VM's own iptables without bypassing the
rule under test, closed again immediately after this evidence was captured.

Measured with that rule live, 2026-09-27:

- `iptables -t mangle -L KANBAN-INGRESS -v -n -x` DROP counter: **5 → 10 packets** (+5) across
  one `nc -z -w 5 159.195.114.230 30104` probe from the operator's workstation.
- The same probe against NodePort 30104 itself failed (connection timed out) — the required
  inversion, now genuinely attributable to `KANBAN-INGRESS` rather than to Layer 2.
- `nc -z -w 5 159.195.114.230 443` and `nc -z -w 5 159.195.114.230 22` both connected immediately.
- Both public health endpoints stayed `{"status":"UP"}` throughout
  (`kanban-board-rud-vlad-473.duckdns.org` and the `-nonprod` counterpart).

**Runtime exposure inventory**, confirmed the same day:

- `k3s kubectl get svc -A`: the only non-ClusterIP Service in the cluster is `kube-system/traefik`.
- Pods with `hostNetwork` or any `hostPort`: exactly `svclb-traefik-*`.

Both match the "only Traefik is public" invariant this plan set out to prove.

**File list.** `infra/vm/k3s-host-firewall.sh` + `k3s-host-firewall.service` join
`infra/vm/docker-user-firewall.*` as VM-provisioning files this document describes — see
`infra/vm/README.md` for the install convention. D-04's Docker teardown (13-10) can now proceed
without leaving k3s's NodePorts/hostPorts newly exposed.

## Verified state (2026-08-14)

- Docker: `29.7.2` (`docker-ce`, official `download.docker.com/linux/debian` apt repo, not the
  `docker.io` Debian package), `docker-compose-plugin` `v5.4.0` (Compose V2), `docker-buildx-plugin`
  `0.36.1`. `docker.service` enabled and active; survives reboot.
- External port probe (from off-VM): 22 open; 80, 443, 8080, 8081, 9092 all closed/filtered. 80/443
  being closed is expected at this stage — nothing is listening yet (Caddy/the app deploy in a
  later plan, 05-04) — this probe confirms the firewall layers, not the eventual app.
- Both firewall layers independently verified to allow 22 and nothing else currently listening.

## Database — self-hosted PostgreSQL

This section replaces the retired "Database — Neon" section below (kept, in the Decommission
Record further down, only as historical evidence of what was deleted). Neon, the managed provider
that hosted this database through 2026-08-26, was replaced because its Free plan's 100 CU-hour/month
compute allowance is scoped **per project** — shared across production and nonprod as two branches
of one project — and this repository's own always-warm pool settings kept both branches billing
continuously against that one shared allowance until it ran out mid-month, taking both environments
down simultaneously with no deploy. See "Self-hosted Postgres cutover — Plan 11-02" for the full
incident account and cutover evidence, and "Self-hosted Postgres resource measurement — Plan 11-03"
for the measured resource caps. The provider's project itself was deleted in this plan — see the
"Decommission Record" section below.

| Field | Value |
|-------|-------|
| Image | `postgres:16` |
| Host | Netcup VPS Lite 2 G12s (the same VM as the application containers) |
| Databases | `kanban_prod`, `kanban_nonprod` — one shared container, two isolated databases |
| Roles | `kanban_prod_app`, `kanban_nonprod_app` (least-privilege, connect-isolated — names only, never a value, per this file's own convention) |
| Engine settings | `shared_buffers=64MB`, `work_mem=4MB`, `max_connections=25` (corrected from `shared_buffers=128MB`, see "Postgres memory profile correction — Plan 11-08") |
| `mem_limit` | `256m`, measured (corrected from `64m`, see "Postgres memory profile correction — Plan 11-08") |
| Host port | None published — admin access is SSH plus `docker exec`, matching this file's `redpanda` precedent |
| Volume | `postgres-data` (Compose-managed, `kanban-board-backend_postgres-data`) |
| Networks | `default` (production project) and external `kanban-db` (shared cross-project, joined by `postgres` and `app-nonprod`) |

No connection string, role password, or superuser credential is recorded here — see
`.env.prod.example` / `.env.nonprod.example` for the shape of what each environment needs; the real
values live only in `.env.prod` / `.env.nonprod` on the VM (never committed).

The first-boot provisioning script that creates the databases/roles above was hardened after this
live volume was already created — see "Provisioning script hardening — Plan 11-07" further down
for what changed and what it does and does not cover.

## Backups and restore — current coverage and the documented gap (D-12)

**There is no backup of the production database.** Not a reduced backup, not an out-of-date one —
none exists. A container loss or a volume loss on the Netcup VM means total, unrecoverable data
loss of everything in `kanban_prod` and `kanban_nonprod`. A reader skimming this heading for
reassurance should stop here: there isn't any.

**What was lost by leaving Neon.** The managed provider supplied point-in-time recovery as a
platform feature, at no engineering cost to this project. Nothing on the self-hosted instance
replaces it. This is an acknowledged, deliberate regression under D-12 — narrower scope for this
phase, with the automation that would close it explicitly deferred — not an oversight discovered
after the fact.

**The manual procedure that WOULD be used, if a restore were needed today.** Written from this
deployment's real container and volume names, so it is usable under pressure rather than a
description of a procedure:

Dump each database from the running container, over an administrative SSH session as the `deploy`
user, writing to a timestamped file outside the container (never inside — a dump living only in a
container that might be the thing that failed is not a backup):

```bash
# From /opt/deploy/kanban-board-backend, as deploy:
docker exec kanban-board-backend-postgres-1 pg_dump -U kanban_admin -Fc kanban_prod \
  > /opt/deploy/kanban-board-backend/kanban_prod-$(date -u +%Y%m%dT%H%M%SZ).dump
docker exec kanban-board-backend-postgres-1 pg_dump -U kanban_admin -Fc kanban_nonprod \
  > /opt/deploy/kanban-board-backend/kanban_nonprod-$(date -u +%Y%m%dT%H%M%SZ).dump
```

(`-Fc`, the custom archive format, is what `pg_restore` below expects; a plain-SQL dump would need
`psql` instead.) The resulting `.dump` file should be copied off the VM immediately (`scp` to an
operator machine) — leaving it only on the same host the database itself runs on defends against
nothing.

Restoring into a fresh container (the shape a total volume loss would require): bring up a new,
empty `postgres` container against a fresh volume (`docker compose up -d postgres` after `docker
volume rm` on the old, corrupted volume — or, more simply, a wholly new deploy of plan 11-01's
manifest), let the init script provision the empty databases and roles as it does on any first
boot, then:

```bash
docker cp kanban_prod-<timestamp>.dump kanban-board-backend-postgres-1:/tmp/restore.dump
docker exec kanban-board-backend-postgres-1 pg_restore -U kanban_admin -d kanban_prod --clean --if-exists /tmp/restore.dump
```

(repeat for `kanban_nonprod` against its own dump file).

**This procedure is written but has never been executed, as of 2026-08-26.** No test restore has
been performed, into a scratch database or otherwise. An untested restore procedure is worse than
no procedure if it is mistaken for a verified one — the command shapes above are believed correct
from `pg_dump`/`pg_restore`'s documented behavior, not proven against this deployment.

**What closing this gap properly would require**, scoped rather than left vague: a scheduled dump
(a cron job or systemd timer on the VM, or a CI-triggered job, running the `pg_dump` commands above
on a regular cadence), off-host storage for the resulting `.dump` files (this VM is a single point
of failure for both the live data and any backup stored only on it), a retention policy (how many
dated dumps to keep, and for how long), and at least one actually-executed test restore into a
scratch database to prove the procedure works before it is relied on. None of this is built by this
plan — D-12 defers it explicitly.

## DNS — DuckDNS

| Field | Value |
|-------|-------|
| Subdomain | `kanban-board-rud-vlad-473.duckdns.org` |
| A record | `159.195.114.230` (the Netcup VM's public IPv4) |
| Verified (2026-08-14) | Resolves correctly via both the local resolver and Google's public DNS (`8.8.8.8`) — not a stale/cached answer. Port 22 reachable through the domain (confirms it correctly routes to the VM, not just resolves); 80/443 closed, matching the "nothing deployed yet" expectation exactly. |
| Dynamic-update note | The Netcup VM's IP is static for this deployment's lifetime — no DuckDNS auto-updater client/cron/token is running. If the VM is ever re-provisioned with a new IP, the A record must be updated manually. |

Not yet attempted: certificate issuance — that's plan 05-04's job once Caddy is actually deployed
and can run its own HTTP-01 challenge against this hostname.

## Observability on k3s — Plan 13-07 (2026-09-26)

The observability stack (kube-prometheus-stack, Loki, Alloy, postgres-exporter) is live in the
`monitoring` namespace, activated at the prod cutover per D-17. This section records current
state; the live activation session itself, including three real bugs found and fixed only once
the stack reconciled against the actual cluster for the first time, is `docs/history/` material —
see the plan's own commit history (`13-07`-prefixed commits on `main`) for the full account.

### 1. Activation

Both monitoring Kustomizations (`monitoring-controllers`, `monitoring`) unsuspended in 13-07 Task
1. Secrets `monitoring/grafana-admin` (keys `admin-user`/`admin-password`) and
`monitoring/postgres-exporter` (key `password`) created directly on the VM via `kubectl`, built
from `.env.prod`, never committed. All four HelmReleases (`kube-prometheus-stack`, `loki`,
`alloy`, `postgres-exporter`) report `Ready`.

### 2. Targets

17 active Prometheus targets, 0 down, at last verification. No `kube-controller-manager`,
`kube-scheduler`, `kube-etcd` or `kube-proxy` job exists (k3s runs these in-process; Phase
13-RESEARCH.md Pitfall 5). Present: `node-exporter`, `kubelet` (three sub-targets, including
`/metrics/cadvisor`), `kube-state-metrics`, `postgres-exporter-prometheus-postgres-exporter`, and
`monitoring/redpanda` scraped from **both** `kanban-prod` and `kanban-nonprod` namespaces (`env`
label `prod`/`nonprod` respectively, via the PodMonitor's relabeling).

### 3. Log namespaces

`GET /loki/api/v1/label/namespace/values` returns `cert-manager`, `flux-system`, `kanban-data`,
`kanban-nonprod`, `kanban-prod`, `kube-system`, `monitoring` — all six namespaces the must-haves
require, plus `cert-manager` as a bonus.

### 4. Panel diagnosis

Every panel across all three dashboards was queried directly against live Prometheus
(`/api/v1/query`, macros substituted) and, for the public shares, via `/api/ds/query` /
`/api/public/dashboards/<token>/panels/<id>/query`.

| Dashboard | Panels | Data-OK | Diagnosed |
|---|---|---|---|
| VM Host Metrics (node-exporter-full) | 124 | 104 | 20 (below) |
| CPU/Memory & Network Usage (cAdvisor) | 6 | 6 | 0 |
| Postgres Internals | 35 | 35 | 0 |

Three real bugs were found and fixed live, none of them a runtime data problem — all three were
manifest/dashboard-JSON defects that only manifested once the stack ran for real:

1. **Grafana crash-looped on activation** — `sidecar:` sat at the values top level instead of
   nested under `grafana:`, so the chart's own default (`defaultDatasourceEnabled: true`)
   rendered a second, chart-managed datasource ConfigMap also marked `isDefault: true` alongside
   this task's own literal-UID one. Fixed by nesting `sidecar:` under `grafana:`
   (`k8s/monitoring/controllers/kube-prometheus-stack.yaml`).
2. **Grafana's public IngressRoutes 404'd** — the real rendered Service name is
   `kube-prometheus-stack-grafana`, not `kps-grafana` as the header comment assumed;
   `fullnameOverride: kps` only renames objects the parent chart templates directly, not a
   subchart's own naming (`grafana.fullname` reads `.Release.Name`). Fixed in
   `k8s/monitoring/configs/ingressroute.yaml` (all three routes across both IngressRoute
   objects).
3. **postgres-exporter never actually connected**, then never collected `pg_stat_statements`/
   postmaster metrics once it did:
   - The `monitoring` role's `GRANT CONNECT` on `kanban_prod`/`kanban_nonprod` (from
     `k8s/data/postgres/init/02-create-monitoring-role.sh`, which runs only once against an empty
     PGDATA) was silently reverted when 13-06's incident recovery dropped and recreated both
     databases mid-window — a `CREATE DATABASE` resets ACLs to Postgres defaults. Fixed by
     re-applying `GRANT CONNECT ON DATABASE kanban_prod|kanban_nonprod TO monitoring;` directly
     against the running instance (not a manifest change — the init script itself is correct for
     any future first boot).
   - The HelmRelease's `extraArgs` (`--auto-discover-databases`, `--collector.postmaster`,
     `--collector.stat_statements`) sat at the values top level; the chart's Deployment template
     reads `.Values.config.extraArgs`, so none of the three flags ever reached the exporter
     binary. Fixed by nesting `extraArgs` under `config:`
     (`k8s/monitoring/controllers/postgres-exporter.yaml`).

Two label-mismatch bugs in the dashboard JSON itself (Rule 1, not infra):

4. **node-exporter-full + Postgres Internals**: the `instance` label was a placeholder
   (`netcup-prod-node`) that was never the real k3s node name (`v2202608397723499373`). Every
   panel using it returned empty until the literal was corrected in both dashboard JSON files.
5. **node-exporter-full**: every panel's `job` label read `prometheus-node-exporter`; the chart
   deliberately sets `jobLabel: node-exporter` on the ServiceMonitor ("to match standard common
   usage in rules and grafana dashboards" — the chart's own comment). Corrected to `node-exporter`
   across all 284 occurrences.
6. **cAdvisor's two network-traffic panels** filtered on `container!=""`/`container!="POD"`, but
   `container_network_*` metrics are reported per-pod (the pod's shared network namespace), never
   carrying a `container` label — the filter matched zero series by construction. Removed the
   filter, grouped by `namespace`/`pod` instead.

The 20 diagnosed-but-not-fixed node-exporter-full panels (43 individual empty query-targets across
them — several panels overlay multiple series, only some of which are affected) are all genuine,
accepted uncollected-metric gaps — none is a label mismatch or a fixable bug:
- **Kernel/hardware capability absent**: `node_processes_*` (processes collector not compiled
  in/registered), `hwmon`/power-supply/fan-speed metrics (collector runs successfully but finds
  no physical sensors — this is a virtualized VPS), `node_pressure_irq_stalled_seconds_total`
  (IRQ PSI support absent on this kernel; the other four PSI metrics — CPU, memory ×2, IO ×2 —
  all exist and render).
- **Collector never enabled**: `systemd` (units/sockets), `interrupts` (per-IRQ detail),
  `cpufreq` scaling detail, `tcpstat` (`node_tcp_connection_states`, feeds three of the four TCP
  panels — the fourth, TCP Connections, is mostly populated via `netstat`, only its `MaxConn`
  series is absent) — none of these collectors are turned on by the chart's node-exporter
  subchart values, matching the Compose-era deployment's own scope.

### 5. Public shares

All three PUBLIC_DASHBOARDS uids recreated as public shares on the new Grafana (D-18, corrected
to three), verified via `GET /api/dashboards/public-dashboards` and each token's own
`/api/public/dashboards/<token>` (200) plus a real panel query returning data:

| Dashboard uid | README label | Notes |
|---|---|---|
| `rYdddlPWk` (node-exporter-full / VM Host Metrics) | Network & OS | linked in README |
| `pMEd7m0Mz` (cadvisor) | CPU & Memory metrics | linked in README |
| `v5ciIbUZz` (postgres-exporter / Postgres Internals) | — | no README link; URL in `13-07-SUMMARY.md` |

No token beyond what README already publishes is recorded here.

### 6. Todo closures

`2026-09-08-grafana-admin-password-drift-from-env-prod.md` → completed: superseded by the new
Grafana's Secret-sourced admin password (D-10), which has no first-boot-only persisted account to
drift from `.env.prod`. `2026-09-07-rotate-grafana-viewer-password-leaked-in-session.md` →
completed: the new Grafana starts from a fresh `grafana.db` with only `admin` provisioned — no
`viewer` account exists for the leaked credential to still authenticate against.

## k3s resource measurement — Plan 13-09 (2026-09-27)

Every provisional memory value shipped since Phase 13 started (Traefik, Flux's 6 controllers,
cert-manager's 3 components, kube-prometheus-stack, Loki, Alloy, postgres-exporter) is replaced
here with a real restart-ladder measurement on this VM, using the same method Plan 12-05
("Observability stack resource measurement") and Plan 12-06 ("Caddy resource measurement")
established — halve from the provisional starting point, confirm `RestartCount=0` plus a clean
`dmesg`/`lastState` across a full workload cycle at each rung, adopt with headroom, independently
re-verify from a fresh recreate. `verify-k8s-invariants.py --no-provisional` now enforces that no
value in `k8s/` regresses to an unmeasured guess.

### Workload

One cycle, fired at every rung: (1) the repository's established 54-request burst through the
public HTTPS API against both environments (6 columns + 24 tasks + 24 subtasks, after
signup+board); (2) all three Grafana dashboards fetched at once; (3) a wide Loki query across
every namespace; (4) a self-signed Issuer+Certificate issued and deleted in scratch namespace
`ladder-scratch`, exercising cert-manager without ACME rate limits; (5) at least 20s of settle.
Traefik's own ladder additionally ran the repository's adversarial rate-limit workload
(`scripts/loadtest/run-rate-limit-verification.sh`) at every rung, since that is its real peak
path. Flux was suspended for the entire window (root Kustomization first, then every affected
Kustomization/HelmRelease, resumed in reverse order once every value was committed).

**A step the plan's own workload description assumed but this session could not perform**: the
nonprod full reset (`POST /api/admin/reset?fullReset=true`) requires a shared-secret
`X-Reset-Token` header (`APP_RESET_TOKEN`, never committed) that this session did not have and did
not attempt to extract or guess — every other workload step ran as specified. This does not weaken
the ladder's pass/fail signal (restartCount/dmesg/lastState), since the reset step's role in prior
ladders was to exercise Postgres's own peak path, not any of the components measured here.

### Iteration ladder

| Component | Rungs (✓/✗) | Failure signal | Adopted |
|---|---|---|---|
| Traefik | 256Mi✓ → 128Mi✓ → 64Mi✓ → 32Mi✓ → 16Mi✗ | OOMKilled, anon-rss ~14.7MiB + file-rss ~75.3MiB vs 16Mi cap | **64Mi/32Mi** |
| source-controller | 1Gi✓ → 512Mi✓ → 256Mi✓ → 128Mi✗ | OOMKilled, anon-rss ~126.7MiB + file-rss ~57.5MiB vs 128Mi cap | **256Mi/64Mi** |
| kustomize-controller | shared rung with source-controller; clean through 256Mi | — (never failed at a tried rung) | **256Mi/64Mi** |
| helm-controller | shared rung with source-controller; clean through 256Mi | — (never failed at a tried rung) | **256Mi/64Mi** |
| notification-controller | shared rung with source-controller; clean through 256Mi | — (never failed at a tried rung) | **256Mi/64Mi** |
| image-reflector-controller | shared rung with source-controller; clean through 256Mi | — (never failed at a tried rung) | **256Mi/64Mi** |
| image-automation-controller | shared rung with source-controller; clean through 256Mi | — (never failed at a tried rung) | **256Mi/64Mi** |
| cert-manager (controller) | shared rung with cainjector; clean through 64Mi | — (never failed at a tried rung) | **64Mi/16Mi** |
| webhook | shared rung with cainjector; clean through 64Mi | — (never failed at a tried rung) | **64Mi/16Mi** |
| cainjector | 128Mi✓ → 64Mi✓ → 32Mi✗ | OOMKilled, anon-rss ~31.7MiB + file-rss ~25.8MiB vs 32Mi cap | **64Mi/16Mi** |
| prometheus-operator | 64Mi✓ → 32Mi✓ → 16Mi✗ | OOMKilled, anon-rss ~15.1MiB + file-rss ~43.5MiB vs 16Mi cap | **32Mi/25Mi** |
| kube-state-metrics | 64Mi✓ → 32Mi✗ | OOMKilled, anon-rss ~31.5MiB + file-rss ~16.1MiB vs 32Mi cap | **64Mi/32Mi** |
| node-exporter | 16Mi✓ → 8Mi✗ | OOMKilled, anon-rss ~7MiB + file-rss ~10MiB vs 8Mi cap | **16Mi/10Mi** |
| Alloy | 75Mi✓ → 40Mi✗ | OOMKilled, anon-rss ~39.6MiB + file-rss ~108.2MiB vs 40Mi cap | **75Mi/40Mi** |
| postgres-exporter | 16Mi✓ → 8Mi✗ | OOMKilled, both anon+file exceeding the 8Mi cap | **16Mi/10Mi** |
| Prometheus | 512Mi✓ → 384Mi✓ → 320Mi✓ → 288Mi✓ → 256Mi✗ (confirmed live at session start) | OOMKilled, anon-rss climbing to the cap before being killed | **384Mi/288Mi** |
| Grafana | 384Mi✓ → 256Mi✗ | OOMKilled | **768Mi/400Mi** (adopted directly, see below) |
| Loki | 128Mi✓ → 64Mi✗ | OOMKilled, anon-rss ~62.9MiB + file-rss ~75.6MiB vs 64Mi cap | **192Mi/96Mi** |

Loki's 128Mi/192Mi both initially appeared to fail the wide-query function check via HTTP 000; both
were traced to a stale `kubectl port-forward` tunnel pointing at an already-deleted pod sandbox on
this operator's side, not a Loki-side fault — confirmed by re-querying the same endpoint
immediately after re-establishing the tunnel (HTTP 200, real log lines returned) with no
intervening change to Loki itself. Recorded here so a future reader hitting an identical
ambiguous signal checks the tunnel before re-running the ladder.

Prometheus's real floor sits well above its Compose-era same-binary predecessor's 256Mi cap
(Plan 12-05) because it now scrapes the whole k3s cluster (kube-apiserver, etcd, kubelet,
cAdvisor) rather than just app-level metrics — `headStats` at measurement time showed 123,106
series / 217,214 chunks, roughly 7x Plan 12-05's 18,523/56,568. Confirmed failing at 256Mi with
**live evidence gathered before this ladder even began**: `dmesg` on this VM already showed
repeated Prometheus OOM kills at its provisional cap, independent of any action this session took.

Grafana repeated the exact pattern Plan 12-05's own same-day addendum documented for this same
binary: a synthetic burst passes cleanly at 384Mi (anon usage ~297MiB, filling whatever headroom
the cap allows), but that addendum recorded TWO real OOM kills under sustained concurrent
dashboard/API load at that same adopted value, corrected same-day to 768Mi. Rather than repeat
that mistake, 768Mi/400Mi was adopted directly from the historical corrected figure.

### Carried values, re-confirmed

App/Postgres/Redpanda keep their Compose-ladder `limits`; `requests` were re-confirmed from
`max_over_time(container_memory_working_set_bytes{namespace=~"kanban-.*",container!=""}[24h])`
against the now-live kube-prometheus-stack, filtered to currently-running pods:

| Component | 24h peak (live) | Prior request | Action |
|---|---|---|---|
| app (prod) | 532.9MiB | 512Mi | Raised to **576Mi** (peak now exceeds the prior figure) |
| app (nonprod) | 465.3MiB | 512Mi | Unchanged — still correct |
| postgres | 65.5MiB | 128Mi | Unchanged — still correct |
| redpanda (prod) | 370.0MiB | 384Mi | Unchanged — still correct |
| redpanda (nonprod) | 179.9MiB | 320Mi | Unchanged deliberately — 320Mi is anchored to a documented ballooned-`__consumer_offsets`/`_schemas`-backlog crash-loop incident (quick task 260911-gkz), a worst case this 24h calm-load figure does not reproduce and cannot supersede |

### Host coexistence

`free -m` immediately after the full ladder session, all live workloads running:

```
               total        used        free      shared  buff/cache   available
Mem:            7945        5659         398          42        2255        2286
```

Node allocatable memory: `8136004Ki` (≈7945Mi). Sum of every container's memory *request* across
the cluster: **3405Mi (~3.33Gi)**, well under allocatable — roughly 43% utilization at requests,
leaving genuine headroom for burst usage up to each container's own `limits`.

### T0 — the D-08 24h observation clock

Recorded once every measured value was live (Flux resumed, all 9 Kustomizations and 5
HelmReleases `Ready`, `ladder-scratch` deleted) and the cluster held stable for 10+ minutes with
zero restarts across every container:

**T0 = `2026-09-27T10:44:46Z`**

Restart-count snapshot at T0 (`k3s kubectl get pods -A -o json` reduced to
namespace/pod/container=count), all 32 containers at **0**:

```
cert-manager/cert-manager-cert-manager-6dc76c98c4-pzrxx/cert-manager-controller=0
cert-manager/cert-manager-cert-manager-cainjector-86b4d9d65-q5kmq/cert-manager-cainjector=0
cert-manager/cert-manager-cert-manager-webhook-5494776bb-5dzwn/cert-manager-webhook=0
flux-system/helm-controller-6cfc4b5569-cjjv6/manager=0
flux-system/image-automation-controller-547964959d-hvdjv/manager=0
flux-system/image-reflector-controller-698f4f4774-db6ph/manager=0
flux-system/kustomize-controller-78bcd696c4-qhgjq/manager=0
flux-system/notification-controller-6dfdff5777-qnrr7/manager=0
flux-system/source-controller-64cb4f9f98-v74xd/manager=0
kanban-data/postgres-0/postgres=0
kanban-nonprod/app-86bf7556b8-fzb2q/app=0
kanban-nonprod/redpanda-0/redpanda=0
kanban-prod/app-75675978-88hlp/app=0
kanban-prod/redpanda-0/redpanda=0
kube-system/coredns-54996dc9b4-qpgjp/coredns=0
kube-system/helm-install-traefik-5245k/helm=0
kube-system/helm-install-traefik-crd-pxznn/helm=0
kube-system/local-path-provisioner-77b9867795-mt4bn/local-path-provisioner=0
kube-system/svclb-traefik-b5a1f81f-lhg6d/lb-tcp-443=0
kube-system/svclb-traefik-b5a1f81f-lhg6d/lb-tcp-80=0
kube-system/traefik-7fbf6755c7-scxcf/traefik=0
monitoring/alloy-r4fr4/alloy=0
monitoring/alloy-r4fr4/config-reloader=0
monitoring/kps-operator-6bb88fd477-b7kkd/kube-prometheus-stack=0
monitoring/kube-prometheus-stack-grafana-69ff8968bb-jnhkp/grafana=0
monitoring/kube-prometheus-stack-grafana-69ff8968bb-jnhkp/grafana-sc-dashboard=0
monitoring/kube-prometheus-stack-grafana-69ff8968bb-jnhkp/grafana-sc-datasources=0
monitoring/kube-prometheus-stack-kube-state-metrics-77dbff459c-8mf8m/kube-state-metrics=0
monitoring/kube-prometheus-stack-prometheus-node-exporter-s62rw/node-exporter=0
monitoring/loki-0/loki=0
monitoring/loki-0/loki-sc-rules=0
monitoring/postgres-exporter-prometheus-postgres-exporter-7f47ddd5c8-6x4jp/prometheus-postgres-exporter=0
monitoring/prometheus-kps-prometheus-0/config-reloader=0
monitoring/prometheus-kps-prometheus-0/prometheus=0
```

Plan 13-10 should evaluate D-08.3 against the window `[T0, T0+24h]` = `[2026-09-27T10:44:46Z,
2026-09-28T10:44:46Z]`.

### A pre-existing, unrelated bug surfaced by this session

Resuming Flux exposed a real, pre-existing defect this plan did not introduce and does not own:
`k8s/platform/edge/ingressroute-monitoring.yaml` (13-06) and `k8s/monitoring/configs/ingressroute.yaml`
(13-07) both define an `IngressRoute` named `grafana` in namespace `monitoring` — the first a
deliberate placeholder routing to a Service with no Endpoints, the second the real fix routing to
`kube-prometheus-stack-grafana`. Both Kustomizations (`edge`, `monitoring`) are independently
`Ready`; whichever reconciles most recently wins ownership of the live object via server-side
apply, and Flux's periodic reconcile means this can flap indefinitely. Observed live during this
session: `edge` won the race first (public monitoring endpoint returned 503 "no available server"
for a period), corrected by forcing `monitoring` to reconcile again — but nothing prevents `edge`
from winning again on its own 10-minute schedule. Filed as a todo rather than fixed here, since
resolving it requires removing the now-redundant placeholder from `platform/edge` — outside this
plan's `files_modified` scope and a real, if small, architectural decision (which Kustomization
should own this object).

## D-08 gate — Plan 13-10 (2026-09-29)

**Verdict: FAIL — D-04 (Compose deletion) is NOT cleared.** Evaluated 2026-09-29T09:17Z to
09:21Z, about 46.5 h after T0 (`2026-09-27T10:44:46Z`, see "k3s resource measurement — Plan
13-09"). Two items fail on evidence: D-08.1(b) (10 uptime-check runs against a threshold of 90)
and D-08.3 (5 container restarts, all OOMKills, inside the observation window). No threshold was
relaxed. Everything read on the VM was read-only; the only state change was
`gh workflow run verify-rate-limit.yml`.

| Item | Check | Evidence | PASS/FAIL |
|---|---|---|---|
| D-08.1a | Both public health endpoints UP now | `curl` at 09:18Z: prod `{"status":"UP","groups":["liveness","readiness"]}` HTTP 200; nonprod identical, HTTP 200 | PASS |
| D-08.1b | `uptime-check.yml`: every run in [T0, now] succeeded, count >= 90 at the 15-min cadence | `gh run list --workflow uptime-check.yml --created ">=2026-09-27"` gives 10 runs with `createdAt >= T0`, all 10 `success` (event `schedule`), first 2026-09-27T11:31:02Z, last 2026-09-29T06:54:16Z. Count is 10, not >= 90. Gaps between consecutive runs are 2.6 h to 8.2 h, never 15 min; the previous 50 runs back to 2026-09-19 show the same 2-5 h cadence, and the one failure in that history (2026-09-24T11:12:42Z) predates T0. The workflow's own header (a) says GitHub's cron is a floor, not a guarantee. That explains the shortfall but is not a GitHub-side outage shown in a run's log, so the plan's rule says FAIL | FAIL |
| D-08.1c | `gh workflow run verify-rate-limit.yml`, watched, green | Dispatched 2026-09-29T09:18:58Z, run 36548367065 (`workflow_dispatch`), conclusion `success`, job "Production limits signin, nonprod does not" `success` | PASS |
| D-08.1d | `python3 scripts/verify-public-dashboards.py` | Output: `invariants OK -- compose: 3 dashboard(s) checked; k8s: 3 dashboard(s) checked`, rc 0 | PASS |
| D-08.1e | GitOps: pod image equals ImagePolicy latest; tag reached the overlay via fluxcdbot; build run event `push`; pod Ready | ImagePolicy `kanban-board-backend-prod` latestRef tag `main-142-f424526`. Running pod `kanban-prod/app-5f47756674-smvsl`, 1/1 Running, image `rudenkovladimir/kanban-board-backend:main-142-f424526`, started 2026-09-27T10:49:42Z. Overlay bump: fluxcdbot commit `3ac4b68` ("chore(flux): bump images", 2026-09-27T10:47:58Z, `main-141-54249e7` to `main-142-f424526` in the prod and nonprod overlays). Build: deploy.yml run **36313018824** (#142), event `push`, head `f42452679b164e27d44bde63127791eec22e95c8`, all 8 jobs `success`. Nonprod ImagePolicy resolves to the same tag | PASS |
| D-08.2 | `diff` of the 13-06 counts files, both databases | `/root/k3s-cutover-20260926/`: `diff counts-kanban_prod-before.txt counts-kanban_prod-after.txt` empty (activity_log 2261, boards 43, columns 249, subtasks 983, tasks 984, users 49); `kanban_nonprod` diff empty (all six tables 0, so nonprod parity is trivially true) | PASS |
| D-08.3a | Prometheus: `sum(increase(kube_pod_container_status_restarts_total[24h]))` = 0 | At `time=2026-09-28T10:44:46Z` (T0+24h): **4.00** (prometheus 3.00, node-exporter 1.00). At now (2026-09-29T09:19:48Z): 1.00 (node-exporter). The second window still holds a restart, so the last-24h view is not clean either | FAIL |
| D-08.3b | Prometheus: `count(max_over_time(kube_pod_container_status_last_terminated_reason{reason="OOMKilled"}[24h]))` is empty | At T0+24h: **2** series (`monitoring/prometheus-kps-prometheus-0/prometheus`, `monitoring/kube-prometheus-stack-prometheus-node-exporter-s62rw/node-exporter`). At now: 2, same two | FAIL |
| D-08.3c | Kernel journal: `journalctl -k --since "2026-09-27 10:44:46 UTC"` count of "Memory cgroup out of memory" = 0 | **10** lines, i.e. 5 distinct killed processes (each kill is logged twice). Prometheus 3x (2026-09-27T11:00:07Z, 13:00:05Z, 17:00:04Z, anon-rss ~390 MB against a 384Mi limit), node_exporter 2x (2026-09-27T23:51:33Z, 2026-09-28T13:16:33Z, anon-rss ~14.6 MB + file-rss ~5.5 MB against a 16Mi limit). All five are after T0; the last is after T0+24h | FAIL |
| D-08.3d | Every current container's `restartCount` equals the T0 snapshot | 34 containers now; the T0 snapshot lists 34 lines (its prose says 32), of which 32 persist (the two `app` containers were replaced, below) and 30 of those 32 are unchanged. Changed: `prometheus` 0 to 3 (lastState OOMKilled, finished 2026-09-27T17:00:04Z), `node-exporter` 0 to 2 (lastState OOMKilled, finished 2026-09-28T13:16:33Z). Not restarts: the two `app` pods were replaced by the GitOps rollout to `main-142-f424526` at 2026-09-27T10:49:42Z (prod) after T0, restartCount 0 on the new pods | FAIL |
| D-08.4 | Dump directory exists and `sha256sum -c SHA256SUMS` passes | `/root/k3s-cutover-20260926/` mode 0700, 152K total. `sha256sum -c SHA256SUMS`: kanban_prod.dump OK, kanban_nonprod.dump OK, globals.sql OK, four counts files OK, rc 0. The plan text expects ~30 MB; the real size is 152K (kanban_prod.dump 103,928 bytes, kanban_nonprod.dump 16,573 bytes) | PASS |

### Queries, verbatim

Prometheus, port-forward to `svc/kps-prometheus` (9090), instant queries at `time=2026-09-28T10:44:46Z`
and at the evaluation time:

```
sum(increase(kube_pod_container_status_restarts_total[24h]))
count(max_over_time(kube_pod_container_status_last_terminated_reason{reason="OOMKilled"}[24h]))
increase(kube_pod_container_status_restarts_total[24h]) > 0
max_over_time(kube_pod_container_status_last_terminated_reason{reason="OOMKilled"}[24h]) > 0
```

Kernel journal on the VM (journalctl, not dmesg: dmesg timestamps are boot-relative):

```
journalctl -k --since "2026-09-27 10:44:46 UTC" | grep -c "Memory cgroup out of memory"
```

### What the failure means

- **The 13-09 ladder-adopted limits for Prometheus (384Mi) and node-exporter (16Mi) do not hold
  under real load.** Both containers were killed at their own cap. Prometheus died three times in
  the first ~6.3 h after T0, each on a 2-hourly TSDB head-compaction boundary, and has run clean
  for the ~40 h since (last kill 2026-09-27T17:00:04Z). node-exporter died at 2026-09-27T23:51Z
  and 2026-09-28T13:16Z. Both are non-workload monitoring containers: no application, Postgres or
  Redpanda container restarted.
- **D-08.3 needs a fresh clean 24 h window.** T0 is spent. Clearing it needs the two limits
  raised and re-measured, then a new T0, then this gate re-run. That is a monitoring-limit
  change plus a 24 h wait, not an edit to this record.
- **D-08.1b cannot pass as written.** A 15-min cadence gives ~176 runs over 46.5 h, and GitHub
  delivered 10. The same throttling is visible back to 2026-09-19, so a threshold of 90 was not
  reachable with this workflow at any window length under ~10 days. The threshold is unchanged
  here. Whether the gate should instead count health probes from Prometheus/Blackbox, or accept a
  different floor, is an operator decision for the gate's owner, not something this record
  substitutes.
- Unaffected and passing: health endpoints, rate limits, public dashboards, GitOps push deploy,
  row-count parity, dump integrity.

### Follow-up — 2026-09-29 (quick task 260929-fwf, operator decision "1 and 1")

The table above is the evidence for the gate as written on 2026-09-29 and is not edited.

- **D-08.3 — limits raised, window to be re-opened.** `kube-prometheus-stack.yaml` now sets
  Prometheus to 384Mi request / 640Mi limit (was 288Mi / 384Mi) and node-exporter to 16Mi / 32Mi
  (was 10Mi / 16Mi). Sizing came from the live 3-day working-set peak (Prometheus 431 MiB,
  node-exporter 15.8 MiB), not from a new restart ladder: the 13-09 ladder ran minutes per rung,
  shorter than Prometheus's 2-hourly head-compaction cycle, which is exactly where all three
  Prometheus kills landed. The re-opened 24 h window is the verification. **New T0: not yet
  recorded** — it is set when the change has been pushed and Flux has rolled the release, and
  will be added here.
- **D-08.1b — threshold amended to "every run succeeded and count >= 10"**, counted over
  `[2026-09-27T10:44:46Z, now]` (the original T0), not the re-opened window. At the observed ~5
  runs/24h a floor of 10 from a new T0 would fail again at T0+24h by construction, and this item
  tests application availability, which the limit change does not touch. The old result
  (10 runs, all success) meets the amended floor today. Recorded in `13-10-PLAN.md` § Task 1.

## Maintenance note

If the provider, IP, OS, spec, or firewall policy changes, update this document in the same
change — it is the single checked-in description of what the production host actually is, as
opposed to what any given plan intended it to be. **File list (13-02 addition):** `infra/vm/k3s/`
(k3s config + pinned install wrapper) now sits alongside `infra/vm/docker-user-firewall.*` as the
VM-provisioning files this document describes. **File list (13-07 addition):** the "Observability
on k3s" section above now describes `k8s/monitoring/{controllers,configs}/` as the live-reconciled
observability manifests. **File list (13-09 addition):** `k8s/flux-system/controller-resources.yaml`
now joins `k8s/flux-system/gotk-components.yaml` as the manifests describing Flux's own controller
resources -- the former is a strategic-merge patch, the latter stays generator-owned.
