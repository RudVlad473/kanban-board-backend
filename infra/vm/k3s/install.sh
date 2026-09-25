#!/usr/bin/env bash
# Installs k3s v1.36.4+k3s1 on this host, pinned and checksum-verified. Plan 13-02 (D-12: never
# `latest`). Idempotent -- k3s's own install.sh is itself idempotent (re-running with the same
# INSTALL_K3S_VERSION is a no-op if that version is already active), and this wrapper refuses to
# run as non-root before doing anything.
#
# Usage: sudo bash infra/vm/k3s/install.sh
#
# The k3s install.sh fetched here is the upstream https://get.k3s.io script itself -- it is not
# an installer for THIS specific version; INSTALL_K3S_VERSION (below) pins which release it fetches
# and runs. Verifying this wrapper's own downloaded copy of that script against a committed sha256
# closes the supply-chain gap of "curl | sh" fetching different content at execution time than
# what was reviewed here.
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "FATAL: this script must run as root (it writes /etc/rancher/k3s and installs a systemd service)." >&2
  exit 1
fi

readonly K3S_VERSION="v1.36.4+k3s1"
# sha256 of https://get.k3s.io as fetched and verified 2026-09-25 (plan 13-02). Re-verify and
# update this pin deliberately if the upstream installer script changes.
readonly K3S_INSTALLER_SHA256="e5cc3b3d9dfc1662c2d9be6da5abc9a4cd317d6abc3a5ffc02e3dd3248207fee"

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly CONFIG_SRC="${SCRIPT_DIR}/config.yaml"
readonly CONFIG_DEST="/etc/rancher/k3s/config.yaml"

echo "Installing k3s config to ${CONFIG_DEST}..."
mkdir -p "$(dirname "${CONFIG_DEST}")"
install -o root -g root -m 0644 "${CONFIG_SRC}" "${CONFIG_DEST}"

echo "Fetching k3s installer..."
TMP_INSTALLER="$(mktemp)"
trap 'rm -f "${TMP_INSTALLER}"' EXIT
curl -sfL https://get.k3s.io -o "${TMP_INSTALLER}"

ACTUAL_SHA256="$(sha256sum "${TMP_INSTALLER}" | awk '{print $1}')"
if [ "${ACTUAL_SHA256}" != "${K3S_INSTALLER_SHA256}" ]; then
  echo "FATAL: k3s installer sha256 mismatch: expected ${K3S_INSTALLER_SHA256}, got ${ACTUAL_SHA256}" >&2
  echo "Refusing to run an unverified installer script. If this is a legitimate upstream change," >&2
  echo "re-verify the new script by hand and update K3S_INSTALLER_SHA256 above deliberately." >&2
  exit 1
fi

echo "Installer verified. Running k3s install at pinned version ${K3S_VERSION}..."
INSTALL_K3S_VERSION="${K3S_VERSION}" sh "${TMP_INSTALLER}"

echo "k3s install complete. Waiting for node readiness..."
for _ in $(seq 1 30); do
  if k3s kubectl get nodes --no-headers 2>/dev/null | grep -qw Ready; then
    echo "Node is Ready."
    exit 0
  fi
  sleep 2
done

echo "FATAL: node did not become Ready within 60s." >&2
exit 1
