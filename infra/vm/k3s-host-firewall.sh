#!/usr/bin/env bash
# Applies, checks and removes the KANBAN-INGRESS mangle-table ingress filter for k3s NodePorts and
# hostPorts. Installed at /usr/local/sbin/k3s-host-firewall.sh per infra/vm/README.md and invoked
# by k3s-host-firewall.service. See docs/INFRA_RUNBOOK.md's "Edge hardening on k3s -- Plan 13-08"
# section for the live evidence this ruleset works.
#
# --- Decisions a future reader would otherwise reverse (load-bearing; read before editing) -----
#
# WHY mangle, NOT DOCKER-USER's filter/FORWARD approach: DOCKER-USER (infra/vm/docker-user-
# firewall.sh) is a Docker-managed hook point that only ever sees Docker's own DNAT'd traffic.
# k3s's NodePorts and hostPorts are DNAT'd by kube-proxy/kube-router's own iptables rules in the
# `nat` table (KUBE-NODEPORTS) and by the CNI's hostPort plugin (CNI-HOSTPORT-DNAT) -- neither
# passes through DOCKER-USER at all, and neither of Docker's chains exists in the CNI's path. The
# one hook point that runs BEFORE both of those NAT chains, regardless of which one eventually
# claims the packet, is `mangle PREROUTING` -- filtering there means the decision is made before
# either NAT chain gets a chance to rewrite the destination, so it covers every future NodePort
# or hostPort a k3s Service could ever open, not just the ones enumerated today.
#
# WHY POSITION 1 IN PREROUTING: `-I mangle PREROUTING 1` (not `-A`, append) puts the jump ahead of
# every existing PREROUTING rule -- including kube-router's own NAT bookkeeping. A jump added
# later in the chain would let some fraction of NAT'd traffic already choose its DNAT target
# before this filter ever runs, which would make "unreachable" an intermittent claim rather than
# an absolute one.
#
# ALLOW-LIST SOURCE: the three ports below (22, 80, 443) are read from the live `iptables -S
# INPUT` allow-list captured 2026-09-26 (Plan 13-08 Task 1's checkpoint evidence) and hard-coded
# here rather than derived at apply time -- INPUT governs host daemons (sshd), a materially
# different, slower-changing surface than what k3s exposes via Services, so mirroring it as a
# literal is the correct trade: a change to INPUT's own allow-list is a deliberate, reviewed event
# that should also touch this file, not something this script should silently re-derive live.
#
# RULE ORDER: RETURN for established/related, then RETURN for the allow-list, then RETURN for
# icmp, then DROP, then a final RETURN. The established/related RETURN must precede the DROP or
# every reply packet on an already-permitted connection (a NodePort response, an outbound image
# pull's return traffic) gets dropped -- and it fails as a hang, not an error, exactly the same
# failure shape docker-user-firewall.sh's own header describes for its own ordering.
#
# ATOMIC APPLY: mirrors docker-user-firewall.sh's `iptables-restore --noflush` shape (an in-
# fragment `-F` line makes the chain's own replacement atomic and idempotent) -- re-running apply
# produces exactly six rules in KANBAN-INGRESS, never twelve, and --noflush leaves every other
# mangle chain (including PREROUTING itself, whose jump is added/removed separately) untouched.
#
# DEAD-MAN SWITCH: `apply --dead-man <minutes>` arms a transient `systemd-run --on-active`
# timer that runs `remove` if not cancelled -- protects a live-apply window against a firewall
# rule that turns out to cut SSH. Mirrors the precedent in docker-user-firewall's own quick task
# 260906-feq (an armed `fw-rollback` timer, disarmed only once the ruleset was confirmed working
# from a FRESH SSH connection, not the connection that applied it).
#
# IPv4 ONLY: k3s is installed single-stack (infra/vm/k3s/config.yaml); there is no IPv6 CNI path
# to filter. The Docker-era ip6tables gap (docs/INFRA_RUNBOOK.md's Layer 3 section) is a separate,
# already-tracked todo, re-evaluated in 13-10 when Docker itself stops -- this script does not
# attempt to close it.
#
# WHAT THIS SCRIPT DELIBERATELY DOES NOT CHECK: it never touches INPUT, DOCKER-USER, or any nat-
# table chain -- structurally incapable of it, since KANBAN-INGRESS lives in mangle PREROUTING
# only. It does not verify which Kubernetes Services are actually live; that is the runtime
# exposure inventory (`k3s kubectl get svc -A`), a separate check documented in the same runbook
# section this script's header points to.
set -euo pipefail

readonly EXT_IF="eth0"
readonly CHAIN="KANBAN-INGRESS"
readonly TABLE="mangle"
readonly PREROUTING_JUMP="-I PREROUTING 1 -i ${EXT_IF} -j ${CHAIN}"
# Layer 1 allow-list, read live from `iptables -S INPUT` on 2026-09-26 (Task 1 checkpoint
# evidence): 22 (SSH), 80/443 (Traefik's websecure/web NodePorts, and any future HTTP-01 solve).
readonly ALLOWED_TCP_PORTS="22 80 443"

usage() {
  echo "Usage: $0 {apply|check|remove} [--dead-man <minutes>]" >&2
  exit 2
}

# The ruleset, in enforcement order. `apply` and `check` both build off this single definition so
# the two can never drift from each other -- same discipline as docker-user-firewall.sh.
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
