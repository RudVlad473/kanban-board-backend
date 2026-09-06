#!/usr/bin/env bash
# Applies, checks and displays the DOCKER-USER inbound policy for this VM's Docker-published
# ports (80 and 443 today). Installed at /usr/local/sbin/docker-user-firewall.sh per
# infra/vm/README.md and invoked by docker-user-firewall.service. See docs/INFRA_RUNBOOK.md's
# Firewall Layer 3 section for the live evidence this ruleset works, and quick task 260906-feq's
# SUMMARY.md for the off-box before/after proof.
#
# --- Decisions a future reader would otherwise reverse (load-bearing; read before editing) -----
#
# WHY A SYSTEMD UNIT, NOT `netfilter-persistent save`: `iptables-save` captures the ENTIRE filter
# table, so saving now would also freeze a snapshot of Docker's own runtime-managed chains
# (DOCKER, DOCKER-BRIDGE, DOCKER-CT, DOCKER-FORWARD) alongside this chain's five rules. The
# `/etc/iptables/rules.v4` already on this VM (dated 2026-08-14) is exactly that kind of stale
# snapshot -- a `:DOCKER-USER - [0:0]` chain with no rules, plus dockerd chains that have since
# diverged. Boot-time restore from that file also races dockerd's own chain rebuild, since
# netfilter-persistent's restore runs before docker.service starts. A systemd unit with
# `PartOf=docker.service` re-applies AFTER dockerd every time, deterministically, and this script
# touches nothing Docker owns.
#
# WHY `--ctorigdstport`, NOT `--dport`: DOCKER-USER evaluates packets AFTER Docker's DNAT, so a
# rule matching `--dport` matches the CONTAINER port, not the published HOST port. Today's
# mappings are identity (80->80, 443->443) so both forms behave identically -- which is exactly
# why a `--dport` bug would not surface in testing. `--ctorigdstport` reads conntrack's record of
# the pre-DNAT destination port and stays correct for a mapping like `8443:443`, which this
# repository has not made yet but could.
#
# RULE ORDER: the RELATED,ESTABLISHED return and the non-external-interface return must both
# precede the final DROP, or container egress (image pulls, Caddy's ACME renewals, either app
# reaching Redpanda) breaks -- and it breaks as a hang, not an error, since the outbound SYN
# leaves fine and only the reply dies. Their order RELATIVE TO EACH OTHER does not matter: for
# this five-rule set they match mutually exclusive traffic, so swapping them changes nothing.
#
# ATOMIC APPLY: `apply` replaces the chain via a single `iptables-restore --noflush` transaction
# carrying an explicit `-F ${CHAIN}` line ahead of the five `-A` lines. `--noflush` on the command
# leaves every chain NOT mentioned in the fragment alone (confirmed live against this VM's
# FORWARD/DOCKER-FORWARD chains, 2026-09-06); the in-fragment `-F` makes the mentioned chain's own
# replacement atomic and idempotent -- re-running `apply` produces exactly 5 rules, never 10.
# Verified empirically before adopting this shape, not assumed from the man page.
#
# TCP-ONLY, IPv4-ONLY: nothing here touches ip6tables or UDP. `ip6tables -P INPUT ACCEPT` is a
# known, separately-tracked gap -- IPv6 traffic to a published port terminates on docker-proxy's
# INPUT-governed host socket, not on FORWARD, so this DOCKER-USER change structurally cannot reach
# it (see the todo filed alongside quick task 260906-feq). Enabling Caddy's HTTP/3 will require
# publishing 443/udp AND adding a matching RETURN rule here; forgetting the second half re-opens
# the DROP for that traffic.
#
# WHAT THIS SCRIPT DELIBERATELY DOES NOT CHECK: it never touches INPUT (SSH's chain) -- structurally
# incapable of it, since DOCKER-USER lives on FORWARD. It does not verify Docker's own DNAT rules
# match what this VM's compose files publish; that is `scripts/verify-compose-ports.py`'s job
# against the committed compose files, not this script's job against the live chain.
set -euo pipefail

# Single point of edit for a NIC rename.
readonly EXT_IF="eth0"
readonly CHAIN="DOCKER-USER"

usage() {
  echo "Usage: $0 {apply|check|show}" >&2
  exit 2
}

# The ruleset, in enforcement order. `apply` and `check` both build off this single definition so
# the two can never drift from each other.
expected_rules() {
  cat <<RULES
-A ${CHAIN} -m conntrack --ctstate RELATED,ESTABLISHED -j RETURN
-A ${CHAIN} ! -i ${EXT_IF} -j RETURN
-A ${CHAIN} -p tcp -m conntrack --ctorigdstport 80 -j RETURN
-A ${CHAIN} -p tcp -m conntrack --ctorigdstport 443 -j RETURN
-A ${CHAIN} -j DROP
RULES
}

cmd_apply() {
  local restore_input
  restore_input=$(
    echo "*filter"
    echo ":${CHAIN} - [0:0]"
    echo "-F ${CHAIN}"
    expected_rules
    echo "COMMIT"
  )
  if ! printf '%s\n' "$restore_input" | iptables-restore --noflush; then
    echo "FATAL: iptables-restore failed applying ${CHAIN} -- aborting rather than leaving a partial policy live" >&2
    exit 1
  fi
  # Belt-and-braces: confirm the resulting rule count exactly matches what was just requested.
  # If iptables-restore ever behaves unexpectedly (e.g. after an iptables-nft backend change),
  # this catches a partial or duplicated policy loudly instead of leaving it live silently.
  local actual_count expected_count
  actual_count=$(iptables -S "${CHAIN}" | grep -c '^-A' || true)
  expected_count=$(expected_rules | grep -c '^-A')
  if [ "$actual_count" -ne "$expected_count" ]; then
    echo "FATAL: ${CHAIN} has ${actual_count} rules after apply, expected ${expected_count} -- do not trust this policy" >&2
    exit 1
  fi
  echo "Applied ${expected_count} rules to ${CHAIN}."
}

cmd_check() {
  local live expected
  live=$(iptables -S "${CHAIN}" 2>/dev/null | grep '^-A' || true)
  expected=$(expected_rules)
  if [ "$live" != "$expected" ]; then
    echo "DRIFT DETECTED in ${CHAIN}:" >&2
    echo "--- expected ---" >&2
    echo "$expected" >&2
    echo "--- live ---" >&2
    echo "$live" >&2
    exit 1
  fi
  echo "${CHAIN} matches the expected ruleset."
}

cmd_show() {
  iptables -L "${CHAIN}" -n -v --line-numbers
}

case "${1:-}" in
  apply) cmd_apply ;;
  check) cmd_check ;;
  show) cmd_show ;;
  *) usage ;;
esac
