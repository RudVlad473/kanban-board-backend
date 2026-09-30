# infra/vm/

VM-provisioning files for the production host, version-controlled rather than a hand-typed shell
session that leaves no trace once it closes:

- `k3s-host-firewall.{sh,service}` — Plan 13-08 (D-13, D-04, D-11): the `KANBAN-INGRESS`
  `mangle PREROUTING` filter guarding k3s's NodePorts/hostPorts. It is the only forward-path
  filter on the VM since Plan 13-10 disabled Docker and retired its `DOCKER-USER` policy
  (`docker-user-firewall.{sh,service}`, deleted from this directory; the quick task 260906-feq
  design lives on in `docs/INFRA_RUNBOOK.md`). See `k3s-host-firewall.sh`'s own header for why
  `mangle`, why position 1, the dead-man switch, and IPv4-only scope, and `docs/INFRA_RUNBOOK.md`'s "Edge hardening on k3s — Plan 13-08" section
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

## Install: `k3s-host-firewall` (manual — see "Why this is not wired into deploy.yml" below)

Uses the dead-man-switch precaution quick task 260906-feq established for the (now retired)
`DOCKER-USER` install: arm the rollback timer, confirm SSH still works from a **fresh**
connection, only then disarm it.

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
ssh netcup-prod '/usr/local/sbin/k3s-host-firewall.sh check'
```

Exits non-zero and prints the expected-vs-live diff on any mismatch. A mangle-table counter
(`iptables -t mangle -L KANBAN-INGRESS -n -v -x`) only moves for packets that survive the Netcup
Cloud Firewall (Layer 2), so attributing a drop to this chain needs a temporary Layer-2 rule; see
`docs/INFRA_RUNBOOK.md`'s "Edge hardening on k3s — Plan 13-08" section.

## Why this is not wired into deploy.yml

`k3s-host-firewall` is installed by hand on the VM and deliberately not automated as part of the
CI/CD deploy pipeline. Two reasons, equally true:

1. **No review gate at apply time.** An auto-applied firewall change on every push would mean a
   production firewall rule change ships the moment a PR merges, with no equivalent to the manual
   verification (off-box probe, packet-counter attribution, health-endpoint checks) the ruleset was
   proven against before going live.
2. **iptables is host-global.** The one k3s cluster serves both environments on the same VM behind
   the same chain, so there is no per-environment firewall to deploy to. A change affects both
   environments simultaneously, which is exactly the kind of change that should require a
   deliberate, reviewed, manually-triggered step rather than riding along on an unrelated code
   deploy.

A change to the ruleset should be re-installed by hand, following the install step above, after
review — the same discipline as any other production-affecting infrastructure change on this VM.
