#!/usr/bin/env bash
# Gate: every k8s/ kustomization root renders and validates against the pinned Kubernetes/CRD
# schemas (Phase 13 plan 01, D-12). Same shape as scripts/verify-compose-ports.py and
# scripts/verify-caddy-image-tag.py: a committed, re-runnable check, not a comment restating an
# invariant that nothing enforces.
#
# WHY: D-12 requires "a pre-merge CI job renders both overlays and validates them with kubeconform
# pinned to the Kubernetes version of the pinned k3s release." Without this, a manifest that
# Kustomize happily renders but that violates the Kubernetes API schema (a typo'd field, a wrong
# type, a missing required key) would only be caught by Flux failing to apply it live -- on the
# real cluster, after merge. This gate catches that pre-merge, against the same schema version
# the actual cluster runs.
#
# SCOPE: every directory under k8s/ that holds its own kustomization.yaml, discovered by find, not
# hardcoded -- a new overlay/base directory is covered automatically as soon as it exists. Each is
# rendered with the pinned kubectl (`kubectl kustomize <dir>`) and piped into the pinned
# kubeconform, validated against the Kubernetes core schemas plus the CRD kinds this phase uses
# (Flux, Traefik, cert-manager, Prometheus Operator, k3s HelmChartConfig).
#
# KNOWN HOLES, enumerated now rather than left to be rediscovered:
#   * This validates SCHEMA shape only -- it cannot catch a semantically wrong-but-valid value
#     (e.g. a Service selector that matches no pods, a Secret name that doesn't exist at apply
#     time). Those are runtime/invariant concerns, not schema concerns; see
#     scripts/verify-k8s-invariants.py for the invariant layer this gate does not cover.
#   * It validates against a SNAPSHOT of the CRD catalog, pinned to one commit SHA
#     (CRDS_CATALOG_COMMIT below). If a CRD's real schema changes upstream (a new Flux/
#     cert-manager/Traefik/Prometheus-Operator release), this gate keeps validating against the
#     OLD shape until CRDS_CATALOG_COMMIT is bumped deliberately. That is the intended trade --
#     pinning is what makes "green" mean the same thing on every run (T-13-01) -- but it means a
#     real upstream CRD schema change is invisible here until someone bumps the pin.
#   * `kubectl kustomize` runs entirely client-side; it never contacts a real cluster and cannot
#     catch anything that requires live admission (a ValidatingWebhookConfiguration, a
#     ResourceQuota, an actually-existing Secret/ConfigMap a manifest references by name).
#
# Pinned tool versions (kubectl, kubeconform, helm), each with a recorded sha256, downloaded
# on demand to a per-version cache directory and verified before use:
#   * kubectl v1.36.4 (linux/amd64) -- matches the pinned k3s release (v1.36.4+k3s1) this phase
#     installs (13-RESEARCH.md Standard Stack). Downloaded from the official dl.k8s.io release
#     bucket; sha256 fetched from that same bucket's own .sha256 sidecar file and re-verified
#     against a recorded literal below (belt-and-suspenders: a compromised bucket could serve a
#     matching-but-wrong pair, so the literal is checked-in, not just "trust the sidecar").
#   * kubeconform v0.8.0 (linux-amd64) -- latest release as of 2026-09-25 (this script's write
#     date). sha256 verified against the release's own CHECKSUMS file, recorded below.
#   * helm v4.3.0 (linux-amd64) -- latest release as of 2026-09-25. Pinned but downloaded ONLY on
#     `--tool helm`, never by the default validation path or by CI's k8s-manifests-valid job (D-9:
#     "no helm binary in CI" -- helm here is for LOCAL chart inspection by later plans only).
#     Downloaded from get.helm.sh (Helm's own release CDN), NOT the GitHub release's own asset
#     list -- confirmed live 2026-09-25 that the GitHub release for v4.3.0 publishes only
#     PGP-signed `.asc`/`.sha256.asc`/`.sha256sum.asc` sidecar files for every platform, with no
#     `.tar.gz` binary attached to the release at all; the real artifact lives at get.helm.sh,
#     whose own published `.sha256sum` sidecar matches the value pinned below.
#
# Kubernetes schema version: kubeconform's default schema location
# (https://raw.githubusercontent.com/yannh/kubernetes-json-schema) was confirmed live on
# 2026-09-25 to serve `v1.36.4-standalone-strict` (HTTP 200 on a real schema file at that path) --
# no substitution to a newer v1.36.x needed.
#
# CRDs-catalog: pinned to commit ad3b08c5045129d7bb1eeffd8e61719b2c8dd1e2 (datreeio/CRDs-catalog
# `main` HEAD as of 2026-09-25), never `main` itself -- a future catalog change must not silently
# alter what a green run of this gate means (T-13-01).
#
# CRD kinds this phase uses, each HEAD-checked live at the pinned commit above on 2026-09-25 and
# confirmed present (all returned HTTP 200, so the `-skip` list below is empty):
#   helm.cattle.io/HelmChartConfig (v1), traefik.io/IngressRoute + Middleware (v1alpha1),
#   cert-manager.io/Certificate + ClusterIssuer (v1), monitoring.coreos.com/ServiceMonitor +
#   PodMonitor (v1), helm.toolkit.fluxcd.io/HelmRelease (v2), source.toolkit.fluxcd.io/
#   HelmRepository + GitRepository (v1), kustomize.toolkit.fluxcd.io/Kustomization (v1),
#   image.toolkit.fluxcd.io/ImageRepository + ImagePolicy + ImageUpdateAutomation (v1).
# `-ignore-missing-schemas` is never used (D-12 spirit: a genuinely missing schema must fail
# loudly, not pass silently) -- if a future kind's schema goes missing at the pinned commit, add
# it to KUBECONFORM_SKIP_KINDS below with a dated reason, never blanket-ignore.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

KUBECTL_VERSION="v1.36.4"
KUBECTL_SHA256="8b8f088da2dab964f853b38464033b1be15ede2839eca751482357c45abdd05a"

KUBECONFORM_VERSION="v0.8.0"
KUBECONFORM_SHA256="9bc2bffbf71f261128533edaf912153948b7ff238f9a531ae6d34466ec287883"

HELM_VERSION="v4.3.0"
HELM_SHA256="86584a54def73570558f66f5111cc53dfed56689637ae32c1201205d494f54fb"

CRDS_CATALOG_COMMIT="ad3b08c5045129d7bb1eeffd8e61719b2c8dd1e2"
KUBERNETES_SCHEMA_VERSION="1.36.4"

# Kinds known to be absent from the pinned CRDs-catalog commit, with a dated reason each. Empty
# today (all 14 phase-13 kinds confirmed present 2026-09-25) -- kept as a named, documented
# mechanism rather than adding `-ignore-missing-schemas` if one ever goes missing.
KUBECONFORM_SKIP_KINDS=()

CACHE_ROOT="${XDG_CACHE_HOME:-$HOME/.cache}/kanban-k8s-tools"

log() {
  echo "verify-k8s-manifests: $*" >&2
}

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

# Downloads $2 (a URL) to $3 (a destination file) and verifies its sha256 against $1, unless the
# destination already exists with a matching hash -- idempotent across repeated CI/local runs.
download_and_verify() {
  local expected_sha256="$1" url="$2" dest="$3"
  if [ -f "${dest}" ]; then
    local existing
    existing="$(sha256sum "${dest}" | awk '{print $1}')"
    if [ "${existing}" = "${expected_sha256}" ]; then
      return 0
    fi
    log "cached file at ${dest} has sha256 ${existing}, expected ${expected_sha256} -- re-downloading"
    rm -f "${dest}"
  fi
  mkdir -p "$(dirname "${dest}")"
  log "downloading ${url}"
  curl -sSL -o "${dest}.tmp" "${url}"
  local actual_sha256
  actual_sha256="$(sha256sum "${dest}.tmp" | awk '{print $1}')"
  if [ "${actual_sha256}" != "${expected_sha256}" ]; then
    rm -f "${dest}.tmp"
    fail "sha256 mismatch for ${url}: expected ${expected_sha256}, got ${actual_sha256}"
  fi
  mv "${dest}.tmp" "${dest}"
}

ensure_kubectl() {
  local dir="${CACHE_ROOT}/kubectl-${KUBECTL_VERSION}"
  local bin="${dir}/kubectl"
  if [ ! -x "${bin}" ]; then
    download_and_verify "${KUBECTL_SHA256}" \
      "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/amd64/kubectl" \
      "${bin}"
    chmod +x "${bin}"
  fi
  echo "${bin}"
}

ensure_kubeconform() {
  local dir="${CACHE_ROOT}/kubeconform-${KUBECONFORM_VERSION}"
  local bin="${dir}/kubeconform"
  if [ ! -x "${bin}" ]; then
    local tarball="${dir}/kubeconform.tar.gz"
    download_and_verify "${KUBECONFORM_SHA256}" \
      "https://github.com/yannh/kubeconform/releases/download/${KUBECONFORM_VERSION}/kubeconform-linux-amd64.tar.gz" \
      "${tarball}"
    tar -xzf "${tarball}" -C "${dir}" kubeconform
    chmod +x "${bin}"
  fi
  echo "${bin}"
}

# Downloaded ONLY when explicitly requested via `--tool helm` -- never by the default validation
# path (D-9: no helm binary in CI's k8s-manifests-valid job).
ensure_helm() {
  local dir="${CACHE_ROOT}/helm-${HELM_VERSION}"
  local bin="${dir}/linux-amd64/helm"
  if [ ! -x "${bin}" ]; then
    local tarball="${dir}/helm.tar.gz"
    download_and_verify "${HELM_SHA256}" \
      "https://get.helm.sh/helm-${HELM_VERSION}-linux-amd64.tar.gz" \
      "${tarball}"
    tar -xzf "${tarball}" -C "${dir}"
    chmod +x "${bin}"
  fi
  echo "${bin}"
}

# --tool <kubectl|kubeconform|helm>: prints the pinned binary's path and exits. Used by other
# scripts/CI steps (Task 3's verify-k8s-invariants.py) that need the same pinned kubectl without
# re-implementing this download/verify logic.
if [ "${1:-}" = "--tool" ]; then
  case "${2:-}" in
    kubectl) ensure_kubectl ;;
    kubeconform) ensure_kubeconform ;;
    helm) ensure_helm ;;
    *) fail "--tool requires one of: kubectl, kubeconform, helm (got '${2:-}')" ;;
  esac
  exit 0
fi

KUBECTL_BIN="$(ensure_kubectl)"
KUBECONFORM_BIN="$(ensure_kubeconform)"

# Every directory under k8s/ that holds its own kustomization.yaml, sorted for deterministic
# output. Discovered by find, not hardcoded, so a new base/overlay is covered automatically.
mapfile -t ROOTS < <(find "${REPO_ROOT}/k8s" -type f -name kustomization.yaml -exec dirname {} \; 2>/dev/null | sort)

if [ "${#ROOTS[@]}" -eq 0 ]; then
  fail "no kustomization.yaml found anywhere under k8s/ -- zero roots to validate"
fi

log "validating ${#ROOTS[@]} kustomization root(s) against Kubernetes ${KUBERNETES_SCHEMA_VERSION}, CRDs-catalog@${CRDS_CATALOG_COMMIT}"

KUBECONFORM_ARGS=(
  -strict
  -summary
  -kubernetes-version "${KUBERNETES_SCHEMA_VERSION}"
  -schema-location default
  -schema-location "https://raw.githubusercontent.com/datreeio/CRDs-catalog/${CRDS_CATALOG_COMMIT}/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json"
)
for kind in "${KUBECONFORM_SKIP_KINDS[@]:-}"; do
  [ -n "${kind}" ] && KUBECONFORM_ARGS+=(-skip "${kind}")
done

EXIT_CODE=0
for root in "${ROOTS[@]}"; do
  rel="${root#"${REPO_ROOT}"/}"
  log "rendering and validating ${rel}"
  if ! "${KUBECTL_BIN}" kustomize "${root}" | "${KUBECONFORM_BIN}" "${KUBECONFORM_ARGS[@]}"; then
    echo "FAIL: kubeconform reported violations for ${rel}" >&2
    EXIT_CODE=1
  fi
done

if [ "${EXIT_CODE}" -ne 0 ]; then
  exit "${EXIT_CODE}"
fi

log "all ${#ROOTS[@]} kustomization root(s) valid"
