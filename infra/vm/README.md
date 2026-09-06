# infra/vm/

The `DOCKER-USER` inbound firewall policy for the production VM's Docker-published ports (80 and
443 today), as a reviewed, version-controlled ruleset rather than a hand-typed `iptables` session
that leaves no trace once the shell closes. See `docker-user-firewall.sh`'s own header for the
design decisions (why a systemd unit instead of `netfilter-persistent save`, why
`--ctorigdstport`, rule ordering, IPv4/TCP-only scope) and `docs/INFRA_RUNBOOK.md`'s Firewall
Layer 3 section for the live evidence it works.

## Install (manual — see "Why this is not wired into deploy.yml" below)

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

## Drift check

Confirms the live `DOCKER-USER` chain matches exactly what this repository says it should be —
run this after any manual iptables session on the box, or periodically as a sanity check:

```bash
ssh netcup-prod '/usr/local/sbin/docker-user-firewall.sh check'
```

Exits non-zero and prints the expected-vs-live diff on any mismatch. `show` prints the chain with
its per-rule packet counters (`docker-user-firewall.sh show`), useful for confirming the policy is
genuinely on the live packet path rather than an inert chain nothing traverses.

## Why this is not wired into deploy.yml

This is installed by hand on the VM and deliberately not automated as part of the CI/CD deploy
pipeline. Two reasons:

1. **No review gate at apply time.** An auto-applied firewall change on every push would mean a
   production firewall rule change ships the moment a PR merges, with no equivalent to the manual
   verification (off-box probe, packet-counter attribution, health-endpoint checks) this ruleset
   was proven against before going live.
2. **iptables is host-global; `deploy.yml`'s scp targets are per-environment.** The production and
   nonprod app containers run on the same VM behind the same `DOCKER-USER` chain — there is no
   per-environment firewall to deploy to, unlike the compose files and app images `deploy.yml`
   pushes per environment. A firewall change here affects both environments simultaneously, which
   is exactly the kind of change that should require a deliberate, reviewed, manually-triggered
   step rather than riding along on an unrelated code deploy.

A change to this ruleset should be re-installed by hand, following the install step above, after
review — the same discipline as any other production-affecting infrastructure change on this VM.
