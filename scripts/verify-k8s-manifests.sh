#!/usr/bin/env bash
# Gate: every k8s/ kustomization root renders and validates against the pinned Kubernetes/CRD schemas.
#
# Without it, a manifest that Kustomize renders but that violates the Kubernetes API schema (a typo'd
# field, a wrong type, a missing required key) would only be caught by Flux failing to apply it live, on
# the real cluster after merge. This catches it pre-merge, against the schema version the cluster runs.
#
# Known holes:
# This validates SCHEMA shape only: it cannot catch a semantically wrong-but-valid value (a Service
# selector matching no pods, a Secret name that does not exist at apply time). Those are runtime and
# invariant concerns; scripts/verify-k8s-invariants.py is the invariant layer this gate does not cover.
# It validates against a SNAPSHOT of the CRD catalog, pinned to one commit SHA (CRDS_CATALOG_COMMIT). If
# a CRD's schema changes upstream (a new Flux/cert-manager/Traefik/Prometheus-Operator release), the gate
# keeps validating the OLD shape until the pin is bumped deliberately. Pinning is what makes "green" mean
# the same thing on every run, but a real upstream schema change is invisible until someone bumps it.
# `kubectl kustomize` runs entirely client-side and never contacts a cluster, so it cannot catch anything
# that requires live admission (a ValidatingWebhookConfiguration, a ResourceQuota, an existing
# Secret/ConfigMap a manifest references by name).
#
# Decisions:
# Every directory under k8s/ holding its own kustomization.yaml is discovered by find, not hardcoded, so a
# new overlay or base is covered automatically. Each is rendered with the pinned kubectl and piped into
# the pinned kubeconform, validated against the core schemas plus the CRD kinds in use.
# Pinned tool versions (kubectl, kubeconform, helm), each with a recorded sha256, are downloaded on demand
# to a per-version cache directory and verified before use.
# kubectl v1.36.4 (linux/amd64) matches the pinned k3s release (v1.36.4+k3s1). It comes from the
# official dl.k8s.io bucket; its sha256 is fetched from that bucket's own .sha256 sidecar and re-verified
# against the literal recorded below (a compromised bucket could serve a matching-but-wrong pair, so the
# literal is checked in rather than trusting the sidecar).
# kubeconform v0.8.0 (linux-amd64) was the latest release on 2026-09-25; its sha256 is verified against
# the release's own CHECKSUMS file, recorded below.
# helm v4.3.0 (linux-amd64) was the latest release on 2026-09-25. Pinned but downloaded ONLY on `--tool
# helm`, never by the default path or CI's k8s-manifests-valid job: helm here is for LOCAL chart
# inspection. It comes from get.helm.sh (Helm's own release CDN), NOT the GitHub release's asset list:
# confirmed live 2026-09-25 that the v4.3.0 GitHub release publishes only PGP-signed `.asc`/`.sha256.asc`/
# `.sha256sum.asc` sidecars for every platform and no `.tar.gz` at all; get.helm.sh's own `.sha256sum`
# sidecar matches the pinned value.
# Kubernetes schema version: kubeconform's default schema location
# (https://raw.githubusercontent.com/yannh/kubernetes-json-schema) was confirmed live on 2026-09-25 to
# serve `v1.36.4-standalone-strict` (HTTP 200 on a real schema file), so no newer v1.36.x substitute is
# needed.
# CRDs-catalog: pinned to commit ad3b08c5045129d7bb1eeffd8e61719b2c8dd1e2 (datreeio/CRDs-catalog `main`
# HEAD on 2026-09-25), never `main` itself: a future catalog change must not silently alter what a green
# run means.
# CRD kinds in use, each HEAD-checked at that commit on 2026-09-25 (all HTTP 200):
#   helm.cattle.io/HelmChartConfig (v1), traefik.io/IngressRoute + Middleware (v1alpha1),
#   cert-manager.io/Certificate + ClusterIssuer (v1), monitoring.coreos.com/ServiceMonitor +
#   PodMonitor (v1), helm.toolkit.fluxcd.io/HelmRelease (v2), source.toolkit.fluxcd.io/
#   HelmRepository + GitRepository (v1), kustomize.toolkit.fluxcd.io/Kustomization (v1),
#   image.toolkit.fluxcd.io/ImageRepository + ImagePolicy + ImageUpdateAutomation (v1).
# `-ignore-missing-schemas` is never used: a genuinely missing schema must fail loudly. If a kind's schema
# goes missing at the pinned commit, add it to KUBECONFORM_SKIP_KINDS below with a dated reason, never
# blanket-ignore. CustomResourceDefinition itself (a core kind, not a CRD-catalog kind) is skipped as of
# 2026-09-25; see KUBECONFORM_SKIP_KINDS.

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

# Kinds known to be absent from the pinned CRDs-catalog commit, each with a dated reason; a named
# mechanism instead of `-ignore-missing-schemas`.
#
# Decisions:
# CustomResourceDefinition (added 2026-09-25) is a CORE Kubernetes kind (apiextensions.k8s.io/v1), not a
# CRD-catalog kind. `k8s/flux-system/gotk-components.yaml` (flux's own `flux install --export` output)
# embeds the CRD *definitions*, a different kind from the custom resource *instances* (GitRepository,
# Kustomization, ...) the list above covers. Confirmed 2026-09-25 that yannh/kubernetes-json-schema/master
# publishes NO customresourcedefinition-apiextensions-v1.json at any schema version checked (v1.36.4,
# v1.30.0): a structural gap in that repo's coverage, not a version-pin artifact. Skipping it loses
# nothing the Known holes above do not disclose: validating a hand-authored CRD would matter, but every
# CRD here is flux's own unmodified export, never hand-edited.
KUBECONFORM_SKIP_KINDS=("CustomResourceDefinition")

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
