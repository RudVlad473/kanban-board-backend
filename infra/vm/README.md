# infra/vm/

VM-provisioning files for the production host, version-controlled rather than a hand-typed shell
session that leaves no trace once it closes:

- `docker-user-firewall.{sh,service}` — the `DOCKER-USER` inbound firewall policy for the
  production VM's Docker-published ports (80 and 443 today). See `docker-user-firewall.sh`'s own
  header for the design decisions (why a systemd unit instead of `netfilter-persistent save`, why
  `--ctorigdstport`, rule ordering, IPv4/TCP-only scope) and `docs/INFRA_RUNBOOK.md`'s Firewall
  Layer 3 section for the live evidence it works.
- `k3s-host-firewall.{sh,service}` — Plan 13-08 (D-13, D-04, D-11): the `KANBAN-INGRESS`
  `mangle PREROUTING` filter guarding k3s's NodePorts/hostPorts once Docker's `DOCKER-USER` chain
  stops covering that traffic path at all. See `k3s-host-firewall.sh`'s own header for why
  `mangle` (not `DOCKER-USER`'s `filter`/`FORWARD` approach), why position 1, the dead-man switch,
  and IPv4-only scope, and `docs/INFRA_RUNBOOK.md`'s "Edge hardening on k3s — Plan 13-08" section
  for the live off-box-probe and packet-counter evidence it works.
- `k3s/{config.yaml,install.sh}` — the pinned, checksum-verified k3s install (Plan 13-02): server
  config (secrets encryption, root-only kubeconfig, metrics-server disabled) plus the install
  wrapper that sha256-verifies its own downloaded copy of `get.k3s.io` before running it. ServiceLB
  was disabled for the D-02 interim and re-enabled Plan 13-06 once Traefik took over the public
  edge from Caddy — see `config.yaml`'s own comment. See `docs/INFRA_RUNBOOK.md`'s "k3s install and
  interim bridge — Plan 13-02" section for the live evidence and findings from installing it.
- `sshd/kanban-ci-tunnel.conf` — Plan 13-06 (D-14, T-13-28): the sshd `Match User` drop-in that
  confines the `deploy`/`deploy-nonprod` CI identities' SSH sessions to a single TCP forward
  destination (the in-cluster Postgres ClusterIP) once `deploy.yml`'s Flyway verification jobs
  start tunneling through this VM instead of running Compose commands over SSH. Install:

  ```bash
  scp infra/vm/sshd/kanban-ci-tunnel.conf netcup-prod:/tmp/
  ssh netcup-prod '
    sudo install -o root -g root -m 0644 /tmp/kanban-ci-tunnel.conf /etc/ssh/sshd_config.d/kanban-ci-tunnel.conf
    sudo sshd -t
    sudo systemctl reload ssh
  '
  ```

## Install: `docker-user-firewall` (manual — see "Why this is not wired into deploy.yml" below)

```bash
scp infra/vm/docker-user-firewall.sh netcup-prod:/tmp/
scp infra/vm/docker-user-firewall.service netcup-prod:/tmp/

ssh netcup-prod '
  sudo install -o root -g root -m 0755 /tmp/docker-user-firewall.sh /usr/local/sbin/docker-user-firewall.sh
  sudo install -o root -g root -m 0644 /tmp/docker-user-firewall.service /etc/systemd/system/docker-user-firewall.service
  sudo systemctl daemon-reload
  sudo systemctl enable --now docker-user-firewall.service
'
```

## Install: `k3s-host-firewall` (manual, same discipline — Plan 13-08)

Uses the same dead-man-switch precaution as the `DOCKER-USER` canary in quick task 260906-feq: arm
the rollback timer, confirm SSH still works from a **fresh** connection, only then disarm it.

```bash
scp infra/vm/k3s-host-firewall.sh netcup-prod:/tmp/
scp infra/vm/k3s-host-firewall.service netcup-prod:/tmp/

ssh netcup-prod '
  sudo install -o root -g root -m 0755 /tmp/k3s-host-firewall.sh /usr/local/sbin/k3s-host-firewall.sh
  sudo install -o root -g root -m 0644 /tmp/k3s-host-firewall.service /etc/systemd/system/k3s-host-firewall.service
  sudo systemctl daemon-reload
  sudo /usr/local/sbin/k3s-host-firewall.sh apply --dead-man 10
'
# From a NEW ssh session (not the one that just ran apply):
ssh netcup-prod 'echo still-reachable'
# Only once the above prints, disarm the dead-man timer and enable the persistent unit:
ssh netcup-prod '
  sudo systemctl stop k3s-fw-rollback.timer 2>/dev/null
  sudo systemctl reset-failed k3s-fw-rollback.service 2>/dev/null
  sudo systemctl enable --now k3s-host-firewall.service
'
```

## Drift check

Confirms the live chain matches exactly what this repository says it should be — run after any
manual iptables session on the box, or periodically as a sanity check:

```bash
ssh netcup-prod '/usr/local/sbin/docker-user-firewall.sh check'
ssh netcup-prod '/usr/local/sbin/k3s-host-firewall.sh check'
```

Both exit non-zero and print the expected-vs-live diff on any mismatch. `docker-user-firewall.sh`
also has a `show` subcommand printing the chain with its per-rule packet counters, useful for
confirming the policy is genuinely on the live packet path rather than an inert chain nothing
traverses; `k3s-host-firewall.sh check` prints the same PREROUTING/chain diff on drift instead.

## Why neither firewall is wired into deploy.yml

Both `docker-user-firewall` and `k3s-host-firewall` are installed by hand on the VM and
deliberately not automated as part of the CI/CD deploy pipeline. Two reasons, equally true of
each:

1. **No review gate at apply time.** An auto-applied firewall change on every push would mean a
   production firewall rule change ships the moment a PR merges, with no equivalent to the manual
   verification (off-box probe, packet-counter attribution, health-endpoint checks) either
   ruleset was proven against before going live.
2. **iptables is host-global; `deploy.yml`'s scp targets are per-environment.** The production and
   nonprod app containers — and the one k3s cluster serving both — run on the same VM behind the
   same chains. There is no per-environment firewall to deploy to, unlike the compose files and
   app images `deploy.yml` pushes per environment. A firewall change here affects both
   environments simultaneously, which is exactly the kind of change that should require a
   deliberate, reviewed, manually-triggered step rather than riding along on an unrelated code
   deploy.

A change to either ruleset should be re-installed by hand, following the relevant install step
above, after review — the same discipline as any other production-affecting infrastructure
change on this VM.
