# Phase 13: Introduce Kubernetes - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-09-25
**Phase:** 13-introduce-kubernetes
**Areas discussed:** Cluster location & ambition, Workloads that move, Manifest authoring, Deploy pipeline & edge

---

## Cluster location & ambition

### Where does the cluster run and how far does it go?

| Option | Selected |
|--------|----------|
| Local kind only (Epic 7) | |
| k3s on VPS, nonprod only (recommended) | |
| Full production cutover to k3s | |

**User's choice:** Full production cutover from Compose to k3s on the Netcup VPS (overrides Epic 7's local-only scope)

### In what order do environments move?

| Option | Selected |
|--------|----------|
| Nonprod first then prod, same phase (recommended) | |
| Both at once | |
| Prod only, nonprod stays on Compose | |

**User's choice:** Nonprod first, through at least one CI deploy cycle, then production with the same manifests/different overlay, same phase

### Production downtime tolerance at cutover?

| Option | Selected |
|--------|----------|
| Short announced window (recommended) | |
| Side-by-side then flip upstream | |
| Strict zero downtime | |

**User's choice:** Short announced maintenance window; one prod stack in memory at a time, no side-by-side

### What happens to Compose after prod is on k3s?

| Option | Selected |
|--------|----------|
| 7-day rollback window then removal (recommended) | |
| Decommission immediately on verification | |
| Keep Compose permanently as fallback | |

**User's choice:** Decommission immediately once k3s prod is verified: compose files, Compose deploy jobs, and Docker volumes removed; no rollback window

---

## Workloads that move

### Where does Postgres run; does prod data carry over?

| Option | Selected |
|--------|----------|
| StatefulSet + dump/restore (recommended) | |
| StatefulSet fresh start | |
| Outside k3s via systemd | |

**User's choice:** In-cluster StatefulSet on k3s local-path; pg_dump/pg_restore both DBs during D-03 window; recreate least-privilege roles from docker/postgres-init first (measured: 38 users, 33 boards, 984 tasks, 2261 activity rows, 9.5MB prod / 20MB nonprod)

### How does Redpanda move?

| Option | Selected |
|--------|----------|
| Fresh StatefulSet + re-register (recommended) | |
| Migrate volume/mirror topics | |
| Official Redpanda chart/operator | |

**User's choice:** Fresh single-replica StatefulSet per env; schemas re-registered by register-schemas-production; runbook: stop app, confirm lag 0 + empty DLT, then stop old broker (measured: activity topic 0 retained msgs, DLT empty, lag 0)

### How does the observability stack move?

| Option | Selected |
|--------|----------|
| Same components hand-configured, carry grafana.db (recommended) | |
| kube-prometheus-stack chart | |
| Exempt from D-04, keep on Compose | |

**User's choice:** kube-prometheus-stack Helm chart; Loki via separate chart + collector (Alloy preferred); README public dashboard links must keep working; chart memory measured via restart ladder

### What gates Compose/volume deletion?

| Option | Selected |
|--------|----------|
| Checks + parity + 24h soak (recommended) | |
| Checks + parity, same-day delete | |
| Option 1 + restore drill | |

**User's choice:** Automated checks green + row-count parity + 24h zero OOMKilled/restarts; pg_dump file kept on VPS disk until gate passes

---

## Manifest authoring

### How are the app's own manifests written?

| Option | Selected |
|--------|----------|
| Kustomize base+overlays, Helm for third-party (recommended) | |
| Own Helm chart | |
| Raw YAML per env | |

**User's choice:** Kustomize base k8s/base + overlays nonprod/prod via kubectl apply -k; third-party charts as k3s HelmChart resources, no helm binary in CI

### How are secrets handled?

| Option | Selected |
|--------|----------|
| Plain Secrets from env files (recommended) | |
| Sealed Secrets | |
| SOPS+age/ksops | |

**User's choice:** Plain k8s Secrets created on VPS from existing env files (kubectl create secret --from-env-file); never in git or CI; k3s --secrets-encryption

### How are environments isolated in-cluster?

| Option | Selected |
|--------|----------|
| Namespaces + shared Postgres + NetworkPolicy (recommended) | |
| Postgres per env namespace | |
| Single namespace, nameSuffix | |

**User's choice:** Namespaces kanban-prod, kanban-nonprod, kanban-data, monitoring; one shared Postgres in kanban-data; NetworkPolicy restricts 5432 to app namespaces + postgres-exporter; Redpanda per app namespace

### How are manifests validated pre-merge?

| Option | Selected |
|--------|----------|
| Render + kubeconform (recommended) | |
| Render only | |
| Plus server-side dry-run over SSH | |

**User's choice:** CI renders both overlays with kubectl kustomize, validates with kubeconform pinned to the k3s release's k8s version; k3s version pinned

---

## Deploy pipeline & edge

### What terminates public TLS?

| Option | Selected |
|--------|----------|
| Caddy in-cluster, Traefik disabled (recommended) | |
| Traefik + cert-manager | |
| caddy-ingress-controller | |

**User's choice:** Traefik (k3s default) Ingress + cert-manager; Caddy edge, custom image and build job removed; rate limit as Traefik RateLimit middleware on prod signin/signup only; client IP preservation must be proven; LE staging issuer first

### How does CI deploy?

| Option | Selected |
|--------|----------|
| Push via SSH kubectl apply -k (recommended) | |
| Pull-based GitOps Flux/Argo | |
| Expose 6443 to runners | |

**User's choice:** Pull-based GitOps; post-deploy steps (register-schemas) become in-cluster Jobs; flyway-verify stays in CI

### Which GitOps controller, who commits tag bumps?

| Option | Selected |
|--------|----------|
| Flux + image automation (recommended) | |
| Argo CD, CI commits bump | |
| Flux, CI commits bump | |

**User's choice:** Flux + image-reflector/automation; cluster commits bumps with repo-scoped write deploy key stored as Secret; CI gets no new credentials; image tags must become sortable (e.g. main-<run_number>-<sha7>)

### How does a new image reach prod?

| Option | Selected |
|--------|----------|
| Independent tracking (recommended) | |
| Gated promotion | |
| Prod via manual PR | |

**User's choice:** Both envs track every main image independently via two ImagePolicies (today's semantics)

---

## Phase size

| Option | Selected |
|--------|----------|
| Split at D-02 seam into Phase 13 (nonprod) + Phase 14 (prod) | |
| Keep as one phase | ✓ |
| Explore more gray areas | |

## Claude's Discretion

k3s install method/version, measured resource limits, log collector, kubeconform CRD handling, Docker/k3s coexistence during interim, fate of Compose-specific invariant scripts.

## Deferred Ideas

Automated volume backups, alerting, nonprod→prod promotion gating, phase split.
