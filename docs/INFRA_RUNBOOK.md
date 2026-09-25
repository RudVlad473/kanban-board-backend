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

## Maintenance note

If the provider, IP, OS, spec, or firewall policy changes, update this document in the same
change — it is the single checked-in description of what the production host actually is, as
opposed to what any given plan intended it to be. **File list (13-02 addition):** `infra/vm/k3s/`
(k3s config + pinned install wrapper) now sits alongside `infra/vm/docker-user-firewall.*` as the
VM-provisioning files this document describes.
