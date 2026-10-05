#!/usr/bin/env bash
# Apply, check or remove the KANBAN-INGRESS mangle ingress filter for k3s NodePorts and hostPorts.
#
# Installed at /usr/local/sbin/k3s-host-firewall.sh per infra/vm/README.md and invoked by
# k3s-host-firewall.service; docs/INFRA_RUNBOOK.md's "Edge hardening on k3s" section holds the live
# evidence this ruleset works.
#
# Decisions:
# mangle, NOT DOCKER-USER's filter/FORWARD: DOCKER-USER only sees Docker's own DNAT'd traffic. k3s's
# NodePorts and hostPorts are DNAT'd by kube-proxy/kube-router in the `nat` table (KUBE-NODEPORTS) and
# by the CNI hostPort plugin (CNI-HOSTPORT-DNAT); neither passes through DOCKER-USER. `mangle
# PREROUTING` runs before both NAT chains, so the decision precedes any destination rewrite and covers
# every future NodePort or hostPort a Service could open.
# Position 1 in PREROUTING: `-I mangle PREROUTING 1` (not `-A`) puts the jump ahead of every existing
# rule, including kube-router's NAT bookkeeping. A later jump would let some NAT'd traffic pick its DNAT
# target first, making "unreachable" intermittent rather than absolute.
# Allow-list source: ports 22, 80, 443 were read from the live `iptables -S INPUT` allow-list on
# 2026-09-26 and are hard-coded, not derived at apply time. INPUT governs host daemons (sshd), a slower-
# changing surface than k3s Services; a change to INPUT's allow-list is a deliberate event that should
# also touch this file.
# Rule order: RETURN established/related, RETURN the allow-list, RETURN icmp, DROP, final RETURN. The
# established/related RETURN must precede the DROP or every reply packet on a permitted connection (a
# NodePort response, an image pull's return traffic) is dropped, which fails as a hang, not an error.
# Atomic apply: `iptables-restore --noflush` with an in-fragment `-F` makes the chain's replacement
# atomic and idempotent: re-running apply yields exactly six rules in KANBAN-INGRESS, never twelve, and
# leaves every other mangle chain (PREROUTING's jump is added/removed separately) untouched.
# Dead-man switch: `apply --dead-man <minutes>` arms a transient `systemd-run --on-active` timer that
# runs `remove` unless cancelled, protecting a live-apply window against a rule that cuts SSH. Disarm
# only after the ruleset is confirmed from a FRESH SSH connection, not the one that applied it.
# IPv4 only: k3s is installed single-stack (infra/vm/k3s/config.yaml), so there is no IPv6 CNI path to
# filter. The Docker-era ip6tables gap (docs/INFRA_RUNBOOK.md's Layer 3 section) is a separate tracked
# todo; this script does not attempt to close it.
#
# Known holes:
# This script never touches INPUT, DOCKER-USER or any nat-table chain (KANBAN-INGRESS lives in mangle
# PREROUTING only). It does not verify which Kubernetes Services are live; that is the runtime
# exposure inventory (`k3s kubectl get svc -A`), documented in the same runbook section.
set -euo pipefail

readonly EXT_IF="eth0"
readonly CHAIN="KANBAN-INGRESS"
readonly TABLE="mangle"
readonly PREROUTING_JUMP="-I PREROUTING 1 -i ${EXT_IF} -j ${CHAIN}"
# Layer 1 allow-list: 22 (SSH), 80/443 (Traefik websecure/web, and any future HTTP-01 solve).
readonly ALLOWED_TCP_PORTS="22 80 443"

usage() {
  echo "Usage: $0 {apply|check|remove} [--dead-man <minutes>]" >&2
  exit 2
}

# Single definition of the ruleset, in enforcement order; `apply` and `check` both build off it so
# they cannot drift.
expected_rules() {
  # `-m tcp` is written explicitly so this matches `iptables -S`'s own kernel-normalized output.
  #
  # `iptables -A ... -p tcp ... --dport N` accepts the bare form at apply time, but `-S` always
  # echoes it back with an explicit `-m tcp` match extension inserted before `--dport` -- found
  # live (Task 3) when `check` reported drift against every port rule despite `apply` having just
  # installed them moments before. Writing `-m tcp` here up front keeps expected_rules() matching
  # `iptables -S`'s own output byte-for-byte, the same guarantee docker-user-firewall.sh's
  # single-source-of-truth comment promises for its own five rules.
  cat <<RULES
-A ${CHAIN} -m conntrack --ctstate RELATED,ESTABLISHED -j RETURN
$(for p in ${ALLOWED_TCP_PORTS}; do echo "-A ${CHAIN} -p tcp -m conntrack --ctstate NEW -m tcp --dport ${p} -j RETURN"; done)
-A ${CHAIN} -p icmp -j RETURN
-A ${CHAIN} -m conntrack --ctstate NEW -j DROP
-A ${CHAIN} -j RETURN
RULES
}

chain_exists() {
  iptables -t "${TABLE}" -S "${CHAIN}" >/dev/null 2>&1
}

jump_at_position_1() {
  iptables -t "${TABLE}" -S PREROUTING 2>/dev/null | sed -n 2p | grep -qF -- "-i ${EXT_IF} -j ${CHAIN}"
}

cmd_apply() {
  local restore_input
  restore_input=$(
    echo "*${TABLE}"
    echo ":${CHAIN} - [0:0]"
    echo "-F ${CHAIN}"
    expected_rules
    echo "COMMIT"
  )
  if ! printf '%s\n' "$restore_input" | iptables-restore --noflush; then
    echo "FATAL: iptables-restore failed applying ${CHAIN} -- aborting rather than leaving a partial policy live" >&2
    exit 1
  fi
  local actual_count expected_count
  actual_count=$(iptables -t "${TABLE}" -S "${CHAIN}" | grep -c '^-A' || true)
  expected_count=$(expected_rules | grep -c '^-A')
  if [ "$actual_count" -ne "$expected_count" ]; then
    echo "FATAL: ${CHAIN} has ${actual_count} rules after apply, expected ${expected_count} -- do not trust this policy" >&2
    exit 1
  fi
  if ! jump_at_position_1; then
    iptables -t "${TABLE}" -D PREROUTING -i "${EXT_IF}" -j "${CHAIN}" 2>/dev/null || true
    # shellcheck disable=SC2086 -- PREROUTING_JUMP is a fixed, script-defined flag string, not user input
    iptables -t "${TABLE}" ${PREROUTING_JUMP}
  fi
  echo "Applied ${expected_count} rules to ${CHAIN}, jump confirmed at PREROUTING position 1."

  local deadman_minutes=""
  if [ "${1:-}" = "--dead-man" ]; then
    deadman_minutes="${2:?--dead-man requires a minute count}"
    systemd-run --unit=k3s-fw-rollback --on-active="${deadman_minutes}min" \
      "$0" remove
    echo "Dead-man timer armed: ${CHAIN} will be removed in ${deadman_minutes}m unless disarmed" \
      "(systemctl stop k3s-fw-rollback.timer 2>/dev/null; systemctl reset-failed k3s-fw-rollback.service 2>/dev/null)."
  fi
}

cmd_check() {
  local live expected ok=1
  if ! chain_exists; then
    echo "DRIFT DETECTED: chain ${CHAIN} does not exist in table ${TABLE}" >&2
    ok=0
  else
    live=$(iptables -t "${TABLE}" -S "${CHAIN}" 2>/dev/null | grep '^-A' || true)
    expected=$(expected_rules)
    if [ "$live" != "$expected" ]; then
      echo "DRIFT DETECTED in ${CHAIN}:" >&2
      echo "--- expected ---" >&2
      echo "$expected" >&2
      echo "--- live ---" >&2
      echo "$live" >&2
      ok=0
    fi
  fi
  if ! jump_at_position_1; then
    echo "DRIFT DETECTED: jump to ${CHAIN} is not at PREROUTING position 1" >&2
    echo "--- live PREROUTING ---" >&2
    iptables -t "${TABLE}" -S PREROUTING >&2
    ok=0
  fi
  if [ "$ok" -ne 1 ]; then
    exit 1
  fi
  echo "${CHAIN} matches the expected ruleset, jump confirmed at PREROUTING position 1."
}

cmd_remove() {
  iptables -t "${TABLE}" -D PREROUTING -i "${EXT_IF}" -j "${CHAIN}" 2>/dev/null || true
  if chain_exists; then
    iptables -t "${TABLE}" -F "${CHAIN}"
    iptables -t "${TABLE}" -X "${CHAIN}"
  fi
  echo "Removed ${CHAIN} and its PREROUTING jump."
}

case "${1:-}" in
  apply) shift; cmd_apply "$@" ;;
  check) cmd_check ;;
  remove) cmd_remove ;;
  *) usage ;;
esac
