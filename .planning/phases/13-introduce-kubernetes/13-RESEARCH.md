# Phase 13: Introduce Kubernetes - Research

**Researched:** 2026-09-25
**Domain:** k3s / Flux GitOps / Traefik+cert-manager / kube-prometheus-stack+Loki on a single memory-constrained VPS, migrating a live production Docker Compose stack
**Confidence:** MEDIUM — the Kubernetes-native facts (chart mechanics, CRD shapes, k3s flags) are HIGH confidence (official docs, live VPS verification). The **interim dual-runtime topology** (Q1) and the **final memory budget** (Q8) are the two lowest-confidence, highest-risk areas — both rest partly on reasoning from documented mechanisms rather than an actual k3s install, because installing k3s on the live production VPS is outside a read-only research session's authority. Both are flagged for empirical confirmation as the phase's own Wave 0.

## Summary

This phase replaces a well-instrumented, heavily-measured Docker Compose stack with a real, single-node k3s cluster, GitOps-deployed by Flux, fronted by Traefik+cert-manager, observed by kube-prometheus-stack+Loki+Alloy. The technology choices are all standard and well-documented individually; the risk in this phase is **entirely in the composition** on a 7.8 GiB/no-swap box that must run two runtimes concurrently during the D-02 interim.

Two findings materially change the plan's shape from what CONTEXT.md's "Claude's Discretion" section leaves open:

1. **The interim edge-sharing problem has a clean answer that avoids a two-LB conflict entirely**: keep Caddy (Compose) as the sole owner of host ports 80/443 through the *entire* interim, and have Caddy reverse-proxy the nonprod hostname to Traefik's in-cluster address (ClusterIP or a firewalled-off NodePort) rather than trying to make both Caddy and Traefik bind 80/443 at once. The VM's own `DOCKER-USER` iptables chain already firewalls off every port except 80/443 from the public internet [VERIFIED: infra/vm/docker-user-firewall.sh, live `iptables -S DOCKER-USER` on netcup-prod] — so a Traefik NodePort in the 30000+ range is automatically unreachable from outside the VM with **zero firewall changes needed**, which is the load-bearing fact that makes this topology safe.
2. **`main` currently has no branch protection** [VERIFIED: `gh api repos/RudVlad473/kanban-board-backend/branches/main/protection` → 404 "Branch not protected", run live this session] — so D-15's "if `main` has branch protection, the Flux key needs a narrow bypass" clause does not currently apply. Flux's `ImageUpdateAutomation` can push directly to `main` with no bypass configuration. This should be re-checked at execution time in case protection is added before this phase lands.

The memory budget (Q8) is tight but plausible under `resources.requests` (which is what Kubernetes actually schedules against) — **if** the new observability stack is trimmed hard and Alertmanager stays disabled. It does **not** obviously fit if the interim runs a full second copy of the observability stack alongside the untouched Compose one; Q8's own section below has the numbers and the recommended trim.

**Primary recommendation:** Do the interim exactly as described in point 1 (Caddy stays the only public edge until the single prod maintenance window); pin k3s to `v1.36.4+k3s1` (today's `stable` channel [VERIFIED: `update.k3s.io/v1-release/channels`, queried live]); use `flux install` + a manually-created `GitRepository` over SSH with a deploy key (not `flux bootstrap github`, which needs a PAT) to satisfy D-15's "no new CI credentials" constraint; hand-write Postgres/Redpanda StatefulSets carrying the exact same tuning flags already measured in Compose, changing only the advertised-address value (StatefulSet DNS, not Compose service-name DNS); and treat the memory budget as an open risk requiring a live measurement checkpoint before the nonprod cutover is declared complete, per CONTEXT.md's own "confirm it on the real box before prod moves" instruction.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Public HTTPS edge / TLS termination | Ingress (Traefik, post-cutover) | Edge proxy (Caddy, interim only) | D-13 makes Traefik the final owner; Caddy is a deliberate, temporary bridge during D-02 |
| Certificate issuance | Ingress/cert-manager | — | ACME HTTP-01 solved via Traefik-created ephemeral Ingress; cert-manager owns renewal |
| Rate limiting (`/api/signin`,`/api/signup`) | Ingress (Traefik Middleware) | — | Moves 1:1 from Caddy's `rate_limit` directive to a Traefik `Middleware` CRD scoped to the prod `IngressRoute` only |
| Application (Spring Boot) | Workload (Deployment) | — | Unchanged responsibility, new packaging |
| Postgres | Data / StatefulSet | — | Same shared-instance-two-roles model (Phase 11 D-01), same tier, new runtime |
| Redpanda | Data / StatefulSet | — | Per-environment broker, same tier, new runtime |
| Schema registration | Init/Job (Data tier boundary) | Workload (fallback: initContainer) | D-14 requires ordering before the app; Job immutability is a real pitfall — see Pitfall 3 |
| Metrics collection | Observability (kube-prometheus-stack) | — | Prometheus Operator + ServiceMonitors replace static `prometheus.yml` scrape config |
| Log collection | Observability (Alloy DaemonSet) | — | Replaces Promtail's docker.sock discovery with k8s pod-log discovery |
| Dashboards / public links | Observability (Grafana) | — | Same Grafana, new packaging; datasource UID and DB continuity are the load-bearing constraints (D-07) |
| Deploy orchestration | GitOps (Flux) | CI (image build only) | CI's responsibility shrinks to build+push; Flux owns apply+promote |
| Manifest correctness gate | CI (kubeconform) | — | Pre-merge, pure function of committed YAML — same shape as today's `invariant-checks.yml` |

## Standard Stack

### Core

| Component | Version | Purpose | Why Standard |
|-----------|---------|---------|---------------|
| k3s | `v1.36.4+k3s1` (current `stable` channel) [VERIFIED: `curl https://update.k3s.io/v1-release/channels`, queried live 2026-09-25] | Single-node Kubernetes distribution | Purpose-built for exactly this box class (single node, constrained RAM); bundles containerd, flannel, CoreDNS, Traefik, ServiceLB, local-path-provisioner, metrics-server in one binary [CITED: docs.k3s.io] |
| Flux | v2 (latest 2.x; `flux2` chart pinned `v2_6_4` in the docs corpus available this session) [CITED: fluxcd.io/flux/installation] | GitOps controller set | CNCF-graduated, native image-automation controllers satisfy D-15/D-16 without a third-party tool |
| Traefik | k3s-packaged (currently ships Traefik v3.x as of k3s's own addon manifest) [CITED: k3s HelmChartConfig docs] | Ingress controller | Already the k3s default (D-13 keeps it rather than swapping to ingress-nginx) |
| cert-manager | latest v1.1x stable (verify exact patch at install time via `kubectl apply -f https://github.com/cert-manager/cert-manager/releases/latest/download/cert-manager.yaml`) [CITED: cert-manager.io install docs] | ACME certificate lifecycle | De facto standard K8s cert automation; only real Traefik-integrated option besides hand-rolled ACME |
| kube-prometheus-stack | latest chart from `prometheus-community/helm-charts` | Prometheus Operator + Prometheus + Alertmanager (disabled) + Grafana + kube-state-metrics | D-07 names it explicitly; standard "batteries included" k8s monitoring stack |
| Grafana Alloy | latest chart from `grafana/helm-charts` | Log collection (replaces Promtail) | D-07 prefers it; Promtail is formally EOL upstream since 2026-03-02 [VERIFIED: docker-compose.prod.yml promtail comment, this repo's own prior research] |
| Loki | latest chart, `deploymentMode: SingleBinary` (chart ≥12.0.0 renamed this to `Monolithic` — verify chart version at install time and use the matching key name) [CITED: grafana.com/docs/loki/setup/install/helm] | Log storage/query | Matches current single-binary Loki deployment shape; D-07 names it explicitly |
| kubeconform | latest release, with `-schema-location default -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'` | CI manifest validation | D-12 names it; CRDs-catalog covers Flux/cert-manager/Prometheus-Operator kinds out of the box [CITED: datreeio/CRDs-catalog README, kubeconform issue #77 discussion] |

### Supporting

| Component | Version | Purpose | When to Use |
|-----------|---------|---------|-------------|
| `local-path-provisioner` | k3s-bundled | Dynamic PVCs on node-local disk | Default StorageClass for Postgres/Redpanda/Grafana/Prometheus/Loki PVCs — single node means no cross-node RWX need |
| Prometheus Operator ServiceMonitor/PodMonitor CRDs | shipped by kube-prometheus-stack | Declarative scrape config | Replaces `docker/prometheus/prometheus.yml` static config |
| Flux `image-reflector-controller` + `image-automation-controller` | shipped with `flux install --components-extra=image-reflector-controller,image-automation-controller` | Poll Docker Hub, commit tag bumps | D-15's mechanism |
| k3s `HelmChartConfig` CRD | k3s-bundled `helm-controller` | Customize the k3s-packaged Traefik chart in place | Needed to set `externalTrafficPolicy: Local` and add the RateLimit middleware without replacing Traefik entirely |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| k3s-packaged Traefik + HelmChartConfig | Uninstall k3s's Traefik, install a separate Traefik Helm release via Flux `HelmRelease` | More control over version/values, but D-09 already routes third-party charts through `HelmChart`/`HelmRelease` "whichever fits the D-14 Flux setup" — the k3s-packaged one + `HelmChartConfig` is strictly less new surface and is what D-13 ("Traefik (k3s default)") implies |
| Promtail | Grafana Alloy (chosen, D-07) | Alloy is heavier per-pod than Promtail but is the maintained path; Promtail is EOL |
| `flux bootstrap github` | `flux install` + manual `GitRepository`/SSH deploy key (chosen) | `bootstrap github` needs a GitHub PAT at bootstrap time even for SSH sync mode [VERIFIED via Context7 fluxcd.io docs: "The Flux CLI will use the GitHub PAT to set a deploy key"] — conflicts with D-15's "CI gains no new credentials." The manual path needs no PAT at all: generate an SSH keypair by hand, add the public half as a GitHub deploy key via the repo UI/`gh` CLI (a one-time **human** action, not a CI credential), store the private half as a k8s Secret |
| Hand-written Redpanda StatefulSet | Official Redpanda Helm chart / Redpanda Operator | The chart auto-derives `--smp`/`--memory` from `resources.limits` (80% allocated to Redpanda) [CITED: docs.redpanda.com/current/manage/kubernetes/k-manage-resources] — convenient, but D-09 scopes "own workloads" to hand-written Kustomize, and the existing measured flags (`--overprovisioned --smp 1 --memory 2G`) should be carried over literally rather than re-derived |

**Installation (k3s host):**
```bash
curl -sfL https://get.k3s.io | INSTALL_K3S_VERSION=v1.36.4+k3s1 \
  INSTALL_K3S_EXEC="--secrets-encryption --write-kubeconfig-mode 644 --default-local-storage-path /var/lib/rancher/k3s/storage --disable metrics-server" \
  sh -
```
`--disable metrics-server`: k3s bundles a `metrics-server` addon by default [CITED: k3s docs]; kube-prometheus-stack's Prometheus supersedes it for this phase's purposes (no HPA in scope) and running both wastes ~30-40MB for no consumer. `--secrets-encryption` and `--write-kubeconfig-mode` are named directly by D-10. `INSTALL_K3S_VERSION` pins per D-12 ("never `latest`").

## Package Legitimacy Audit

This phase installs Helm charts/binaries, not npm/pip packages — `package-legitimacy check` targets language-ecosystem registries and doesn't apply directly. Applying the same scrutiny by hand:

| Component | Registry/Source | Age | Popularity signal | Source Repo | Verdict | Disposition |
|-----------|------------------|-----|--------------------|--------------|---------|-------------|
| k3s | `get.k3s.io` (Rancher/SUSE) | 7+ yrs, CNCF sandbox | Industry-standard edge k8s distro | github.com/k3s-io/k3s | OK | Approved |
| Flux v2 | fluxcd.io | 5+ yrs, CNCF Graduated | Widely deployed GitOps tool | github.com/fluxcd/flux2 | OK | Approved |
| cert-manager | cert-manager.io | 7+ yrs, CNCF Incubating | De facto standard | github.com/cert-manager/cert-manager | OK | Approved |
| kube-prometheus-stack | `prometheus-community/helm-charts` | 6+ yrs | Most-used Prometheus Operator chart | github.com/prometheus-community/helm-charts | OK | Approved |
| Grafana Loki / Alloy | `grafana/helm-charts` | Grafana Labs official | First-party Grafana Labs charts | github.com/grafana/helm-charts, github.com/grafana/alloy | OK | Approved |
| kubeconform | `yannh/kubeconform` | 4+ yrs, widely used in k8s CI | github.com/yannh/kubeconform | OK | Approved |
| `datreeio/CRDs-catalog` | GitHub raw content, no auth | Actively maintained community catalog | github.com/datreeio/CRDs-catalog | OK (used only as a schema source, not executed) | Approved — pin to a commit SHA in CI, not `main`, so a future catalog change can't silently alter what a green kubeconform run means |

**Packages removed due to [SLOP] verdict:** none.
**Packages flagged as suspicious [SUS]:** none — every component above is a widely-known, long-established project. No `checkpoint:human-verify` gate is warranted purely on legitimacy grounds; the memory-budget and interim-topology risks (Pitfalls 1, 2, 8) are the real gates this phase needs, and they are called out below as `checkpoint:human-verify`/spike candidates independent of this audit.

## Architecture Patterns

### System Architecture Diagram — Interim state (D-02: nonprod on k3s, prod still on Compose)

```
Internet (159.195.114.230, ports 80/443 only — DOCKER-USER firewall, VERIFIED)
        │
        ▼
   Caddy (Compose, unchanged) ── owns 80/443 for the WHOLE interim
        │
        ├── APP_DOMAIN (prod)          → app:8080 (Compose, unchanged)
        ├── APP_DOMAIN_MONITORING      → grafana:3000 (Compose, unchanged — old stack still authoritative)
        └── APP_DOMAIN_NONPROD         → 127.0.0.1:<Traefik NodePort>  ◄── NEW bridge hop
                                              │
                                              ▼
                                     Traefik (k3s, ClusterIP/NodePort svc)
                                              │
                                              ▼
                                     app-nonprod Service (k3s namespace kanban-nonprod)
                                              │
                                              ▼
                                     app-nonprod Pod ──► Postgres (STILL on Compose)
                                              │             via Service-without-selector
                                              │             + manually-defined Endpoints
                                              │             → 172.17.0.1:5432 (docker0 gateway)
                                              ▼
                                     redpanda-nonprod StatefulSet (k3s, fresh, empty topics — D-06)
```
NodePort is unreachable from the internet by construction (only 80/443 pass `DOCKER-USER`, VERIFIED live) — no firewall change needed for this hop to be safe.

### System Architecture Diagram — Final state (post D-08 gate, Compose fully removed)

```
Internet
   │
   ▼
Traefik (k3s Ingress, owns 80/443 directly, ServiceLB/klipper-lb, externalTrafficPolicy: Local)
   │
   ├── cert-manager-issued certs (production Let's Encrypt issuer)
   ├── RateLimit Middleware (scoped to prod IngressRoute /api/signin,/api/signup only)
   │
   ├── APP_DOMAIN          → app Service (kanban-prod)      → app Pod ─┐
   ├── APP_DOMAIN_NONPROD  → app-nonprod Service (kanban-nonprod) → app-nonprod Pod ─┤
   └── APP_DOMAIN_MONITORING → grafana Service (monitoring)                          │
                                                                                       ▼
                                                              Postgres StatefulSet (kanban-data ns)
                                                              ◄── NetworkPolicy: only 2 app ns + exporter
   Each app namespace also owns its own Redpanda StatefulSet (D-06/D-11)

   kube-prometheus-stack (monitoring ns): Prometheus Operator, Prometheus, Grafana, kube-state-metrics
   Grafana Alloy (DaemonSet, all namespaces) → Loki (monitoring ns)
   Flux (flux-system ns): source/kustomize/helm/notification + image-reflector/image-automation
     └── polls Docker Hub → commits sortable tag bumps to main → reconciles both overlays
```

### Recommended Project Structure

```
k8s/
├── base/
│   ├── app/                 # Deployment, Service, ConfigMap (shared shape)
│   ├── postgres/            # StatefulSet, headless Service, PVC template, init ConfigMap
│   ├── redpanda/            # StatefulSet, headless Service, PVC template
│   ├── schema-registration/ # Job or initContainer patch (see Pitfall 3)
│   └── kustomization.yaml
├── overlays/
│   ├── nonprod/              # namespace kanban-nonprod, env-specific patches, ImagePolicy ref
│   └── prod/                 # namespace kanban-prod, env-specific patches, ImagePolicy ref
├── platform/                 # cluster-scoped, not per-env: namespaces, NetworkPolicies,
│                              #   HelmChartConfig (Traefik), Middleware, ClusterIssuers
└── flux-system/               # GitRepository, Kustomizations, ImageRepository/ImagePolicy/
                                 #   ImageUpdateAutomation, HelmRepository + HelmRelease for
                                 #   kube-prometheus-stack/Loki/Alloy/cert-manager
```

### Pattern 1: Cross-runtime Service (interim Postgres access from k3s)

**What:** A Kubernetes `Service` with no `selector`, paired with a manually-authored `Endpoints` object pointing at a non-cluster IP:port, gives in-cluster pods a stable DNS name (`postgres.kanban-data.svc.cluster.local`) for a workload that lives outside Kubernetes entirely.
**When to use:** Exactly the D-02 interim window, where `app-nonprod` (k3s) must reach `postgres` (still Compose) before D-05's migration happens.
**Example:**
```yaml
# Source: kubernetes.io/docs/concepts/services-networking/service/#services-without-selectors (standard pattern)
apiVersion: v1
kind: Service
metadata:
  name: postgres
  namespace: kanban-data
spec:
  ports:
    - port: 5432
      targetPort: 5432
---
apiVersion: v1
kind: Endpoints
metadata:
  name: postgres          # MUST match the Service name exactly
  namespace: kanban-data
subsets:
  - addresses:
      - ip: 172.17.0.1     # docker0 bridge gateway on netcup-prod — VERIFIED live this session
    ports:
      - port: 5432
```
Requires binding the Compose `postgres` service to `172.17.0.1:5432` (docker0 gateway only — never `0.0.0.0`, which would also bind eth0 and violate the existing no-published-ports invariant) instead of leaving it unpublished, e.g. `ports: ["172.17.0.1:5432:5432"]`. This is a **new** exception to `scripts/verify-compose-ports.py`'s current "no service publishes a port" rule and needs an explicit, scoped, dated exception (mirroring how `scripts/verify-caddy-image-tag.py`'s exceptions are documented) — flagged in Pitfall 1.

### Pattern 2: Traefik RateLimit Middleware translated from Caddy's zones

**What:** Traefik's `RateLimit` middleware is a token bucket (`average` refills per `period`, `burst` caps the bucket size), not Caddy's fixed window (`events` per `window`). The two are not identical semantics — a token bucket has no hard reset at a window boundary, so it is *smoother*, not stricter or looser on average.
**When to use:** Replacing the Caddy `zone auth` directive scoped to `/api/signin`/`/api/signup` on the **prod** `IngressRoute` only (per D-13's explicit scope — nonprod stays unlimited, matching today).
**Example:**
```yaml
# Source: doc.traefik.io/traefik/reference/routing-configuration/kubernetes/crd/http/middleware (Context7, official)
apiVersion: traefik.io/v1alpha1
kind: Middleware
metadata:
  name: auth-rate-limit
  namespace: kanban-prod
spec:
  rateLimit:
    average: 20        # matches Caddy's `events 20`
    period: 5m          # matches Caddy's `window 5m`
    burst: 20            # allow the full budget up front, closest analog to a fixed window's "allow-all-at-window-start"
    sourceCriterion:
      ipStrategy: {}     # defaults to the direct connecting IP — equivalent to Caddy's {remote_host},
                          # since no CDN/LB sits in front (same falsifier Caddyfile's own comment states)
```
`verify-rate-limit.yml`'s thresholds must be **re-derived empirically against Traefik's actual behavior**, not copied — D-13 says this explicitly, and the token-bucket-vs-fixed-window difference is exactly why (a token bucket recovering capacity continuously will pass a burst test that assumes a hard 5-minute reset).

### Pattern 3: Ordering a one-shot Job before a Deployment in Flux — Job immutability pitfall

**What:** `Job.spec.template` is immutable — `kubectl apply` (and therefore Flux's `Kustomization` reconciliation) fails on a second apply of a Job with the same name but a changed image tag, which is exactly what happens every deploy once the app's image tag changes (D-15's whole point).
**When to use:** D-14's `register-schemas-production` in-cluster Job, ordered before the app rollout.
**Recommendation:** Prefer an **initContainer on the app's own Deployment pod template** over a separate Job. This sidesteps the immutability problem entirely (initContainers are part of the Pod template, which *is* meant to change every deploy) and still satisfies "ordered before the app" — an initContainer that fails blocks the main container from starting, which is a stronger ordering guarantee than a Flux `Kustomization.dependsOn` (which only orders *reconciliation*, not pod scheduling). If a separate Job is kept instead (matching CI's current job-per-deploy shape more literally), suffix the Job name with a content hash of the image tag (Kustomize's built-in `configMapGenerator`-style hash suffixing does this for ConfigMaps/Secrets automatically; a Job needs the same trick applied by hand via `nameSuffix` per overlay generation, or `ttlSecondsAfterFinished` + `kubectl delete job --ignore-not-found` as a pre-step in the Flux `Kustomization`'s own reconciliation, which Flux does NOT do automatically) — the initContainer route avoids this entirely and is the recommendation.

### Anti-Patterns to Avoid

- **Two ingress controllers fighting for 80/443 during the interim:** Do not attempt to have Traefik bind host ports while Caddy is still running. Pattern 1's cross-runtime Service technique and the Caddy-proxies-to-Traefik topology above avoid this outright.
- **Re-deriving Redpanda's `--smp`/`--memory` from the official chart's resource-based auto-calculation:** loses the already-measured, incident-tested values (`--memory 2G`/`2200m` cgroup cap for prod, `512M`/`900m` for nonprod — VERIFIED, `docker-compose.prod.yml`/`docker-compose.nonprod.yml`, this session) for no benefit; carry the flags over literally as StatefulSet container args.
- **Trusting `resources.limits` sums for a "does it fit" judgment:** Kubernetes schedules against `requests`, not `limits`; today's Compose stack already runs with `mem_limit`s summing to ~9.1 GiB against 7.8 GiB total RAM [VERIFIED: CONTEXT.md baseline] — that overcommit pattern is legitimate and should continue under `limits`, but `requests` must be set from *measured* usage (the restart-ladder discipline) and their sum must genuinely fit. See Q8/memory budget below.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| ACME certificate lifecycle | A custom cert-renewal cron/script (what Caddy did implicitly) | cert-manager `Certificate`/`ClusterIssuer` | Renewal, failure retry, staging→prod promotion are all solved, tested problems |
| Ordering pod-to-pod dependencies | A shell-script "wait for X" sidecar | Kubernetes readiness/liveness probes + `initContainers` (Pattern 3) | The kubelet already implements exactly this ordering contract |
| Sortable image tags | Hand-rolled tag-bump script in CI | Flux `ImageRepository`/`ImagePolicy` (Pattern shown above) | Flux's image-reflector already handles registry polling, caching, and numerical/semver ordering correctly |
| CI manifest correctness | A hand-written YAML linter | kubeconform + CRDs-catalog | Schema validation against the real k8s/CRD OpenAPI schemas, not regex |
| Client-IP-aware rate limiting at the edge | A custom Traefik plugin | Traefik's built-in `RateLimit` middleware (Pattern 2) | Already implements token-bucket limiting with pluggable source criteria |

**Key insight:** every "custom" piece this migration might be tempted to write (a wait-for-postgres script, a manual cert renewal timer, a hand-rolled deploy-key rotation) already has a first-class Kubernetes-native mechanism. The actual hard, genuinely novel work in this phase is the **interim bridging** (Pattern 1) and the **memory budget** — those have no off-the-shelf answer because they are specific to this VPS's constraints, not general Kubernetes problems.

## Runtime State Inventory

This is a migration phase (Compose → k3s, one VPS). Every category below was checked explicitly.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| **Stored data** | `kanban_prod`/`kanban_nonprod` Postgres databases on the `postgres-data` Docker named volume (9.5 MB + 20 MB, D-05 baseline). Redpanda `_schemas` topic (durable Avro schema state) on `redpanda-data`/`redpanda-nonprod-data` volumes — everything else in Redpanda is empty (0 retained `kanban.activity` messages, per CONTEXT.md baseline). Grafana's `grafana-data` volume holds `grafana.db` (SQLite) — the two public-dashboard share tokens (`7db62007…`, `5a72e5df…`) live **only** here, not in any committed JSON [VERIFIED: README.md, `scripts/verify-public-dashboards.py` docstring]. Prometheus TSDB and Loki chunk store on their own named volumes. | Postgres: `pg_dump`/`pg_restore` per D-05. Redpanda: fresh empty topics + schema re-registration per D-06/D-14 (no data migration — nothing durable to move besides schemas). Grafana: either carry `grafana.db` into the new PVC (Pattern needed, no chart flag exists for this — see Pitfall 6) or recreate the two public shares and update README (D-07 offers both; a decision, not a default). Prometheus/Loki: start fresh (Phase 12 already classified this non-critical). |
| **Live service config** | None found outside git for this phase's scope — unlike a typical n8n/Grafana-UI-config scenario, every current config surface (Caddyfile, prometheus.yml, loki-config.yaml, promtail-config.yaml, Grafana dashboards/datasources) is already committed and bind-mounted (Phase 12 established this discipline; quick task 260908-sj9 fixed a prior drift). The one genuine "config that lives outside git" item is Grafana's own public-dashboard share tokens (see Stored data row above) — those are UI-generated, not provisioning-file-generated. | No action beyond the Grafana share-token handling above. |
| **OS-registered state** | `infra/vm/docker-user-firewall.service` (systemd unit, `PartOf=docker.service`) re-applies the DOCKER-USER iptables ruleset on every boot [VERIFIED, read this session]. No pm2, no Task Scheduler equivalent (Linux VPS), no launchd. | This unit's ruleset is written in terms of Docker's own DNAT/FORWARD mechanics and does **not** need to change for k3s's own pod traffic (see Pitfall 1) — but it **does** need a new explicit rule if Traefik's HTTP-01 solver or the final cutover changes which process owns 80/443 in a way that bypasses Docker's DNAT path entirely. Re-verify live after k3s is installed, don't assume unchanged. |
| **Secrets/env vars** | `.env.prod`/`.env.nonprod` on the VM (never committed) hold `DB_PASS`, `POSTGRES_SUPERUSER_PASS`, `GRAFANA_ADMIN_PASSWORD`, `MONITORING_DB_PASS`, Redpanda has no auth configured today (internal-only listener). GitHub Environment secrets (`NETCUP_SSH_KEY`, `DOCKERHUB_TOKEN`, `DB_USER`/`DB_PASS` per environment) back the SSH/Flyway-verify/deploy jobs. | D-10: recreate every credential as a plain k8s Secret via `kubectl create secret generic … --from-env-file` directly on the VM (not through CI, not committed) — this is a **code-edit-only** concern (no data migration; the credential *values* stay identical, only their storage mechanism changes). D-14/D-15 retire the SSH deploy jobs (`NETCUP_*` secrets for `deploy-to-netcup`/`deploy-to-nonprod`), but `flyway-verify*` keeps using `NETCUP_SSH_KEY` (D-14 says its SSH-tunnel *target* changes, not that the key goes away) — do not delete that secret. |
| **Build artifacts / installed packages** | `docker/caddy/` custom image + `kanban-board-caddy` Docker Hub repo (D-13 removes entirely). No pip egg-info/compiled-binary equivalents in this repo (pure Java/Gradle + Docker images). Gradle's own build artifacts are unaffected by this phase. | Delete `docker/caddy/`, stop publishing to `rudenkovladimir/kanban-board-caddy` (D-13). Existing tags in that Docker Hub repo are not force-deleted by this phase (out of scope — pruning is optional cleanup, not a migration requirement). |

**Canonical question answered:** after every file is updated, the runtime state that still needs deliberate handling — not caught by a source-tree rename — is: the two Postgres databases' actual rows, Redpanda's `_schemas` topic content, Grafana's public-dashboard share tokens, and every `.env.prod`/`.env.nonprod` credential value (re-homed as Secrets, values unchanged).

## Common Pitfalls

### Pitfall 1: `verify-compose-ports.py`'s "nothing is published" invariant breaks for the interim Postgres bridge
**What goes wrong:** Pattern 1 requires binding Postgres to `172.17.0.1:5432` — a real `ports:` entry the current invariant script (per its own stated scope, "only caddy may publish host ports 80/443") will reject.
**Why it happens:** The invariant was written before any cross-runtime bridging need existed.
**How to avoid:** Add a narrow, dated, docstring-documented exception (matching this repo's own established pattern for `.gitleaks.toml` false positives and `scripts/verify-caddy-image-tag.py`'s documented exceptions) scoped specifically to `postgres` binding `172.17.0.1` (never `0.0.0.0`) — and **remove the exception in the same commit that deletes the Compose Postgres service** once D-05's migration completes, so the invariant doesn't silently stay weakened after the bridge is no longer needed.
**Warning signs:** CI red on the plan that adds the bridge; if the exception is still present after `docker-compose.prod.yml` is deleted entirely (D-04), that's a leftover to catch in review.

### Pitfall 2: klipper-lb/ServiceLB and `externalTrafficPolicy: Local` — documented gap, needs live proof
**What goes wrong:** A 2021 klipper-lb GitHub issue reports the source IP appearing as the cluster CIDR's first address even with `externalTrafficPolicy: Local` set [CITED: github.com/k3s-io/klipper-lb/issues/14, fetched live this session, unresolved as filed].
**Why it happens:** That report describes a NAT'd cloud-LB scenario. k3s's own docs describe a *different* code path for a directly-attached public IP (no cloud NAT in front): "If NAT is not involved, traffic is intercepted and forwarded to the Service's ClusterIP" [CITED: k3s docs, `networking-services.md`, via Context7] — which is this VPS's actual topology (public IPv4 assigned directly to `eth0`, no cloud load balancer). The docs also warn explicitly: for `externalTrafficPolicy=local` in NAT environments, do **not** set `node-external-ip` — implying the non-NAT case (this VPS) is the one where the setting is expected to work as documented.
**How to avoid:** Set `externalTrafficPolicy: Local` via the Traefik `HelmChartConfig` (shown below) and **prove it empirically** with two distinct source IPs, exactly as D-13 already requires as an acceptance criterion — don't rely on the docs' description alone given the contradicting community report.
```yaml
apiVersion: helm.cattle.io/v1
kind: HelmChartConfig
metadata:
  name: traefik
  namespace: kube-system
spec:
  valuesContent: |-
    service:
      spec:
        externalTrafficPolicy: Local
```
**Warning signs:** The RateLimit middleware's per-source bucketing collapses to a single shared bucket across all clients (exactly the failure mode D-13 names) — this is directly observable in the rate-limit verification workflow's two-hostname test if extended to assert on distinct client behavior.

### Pitfall 3: Job immutability blocks the schema-registration ordering — see Pattern 3 above.

### Pitfall 4: Redpanda's `--advertise-kafka-addr`/`--advertise-rpc-addr` must change shape, not just hostname
**What goes wrong:** Compose's flags use the flat Compose service name (`redpanda:19092`) because Compose's DNS is flat per-project. Copying that pattern verbatim into a StatefulSet (e.g. advertising a bare `redpanda-0` or the pod IP) breaks client reconnection after any pod restart, because pod IPs are not stable and a bare short name doesn't resolve cluster-wide.
**Why it happens:** StatefulSet pods get stable identity through a **headless Service**, not through the pod name alone — the resolvable, stable address is `<pod-ordinal>.<headless-service>.<namespace>.svc.cluster.local`.
**How to avoid:** Advertise `redpanda-0.redpanda.kanban-prod.svc.cluster.local:19092` (namespaced per environment, matching D-11), not `redpanda:19092`. Schema Registry advertise-address needs the equivalent treatment.
**Warning signs:** Consumers/producers connect once (bootstrap works, since that hits the ClusterIP-fronting Service) then fail on metadata refresh, since the broker advertises an address the client can't route to.

### Pitfall 5: kube-prometheus-stack's control-plane `ServiceMonitor`s fail permanently on k3s
**What goes wrong:** The chart ships `ServiceMonitor`s for `kubeControllerManager`, `kubeScheduler`, `kubeEtcd`, `kubeProxy` that target ports/endpoints only present on a "vanilla" multi-binary control plane. k3s runs these as goroutines inside one process, bound to `localhost` — the ServiceMonitors' targets are permanently `down`.
**Why it happens:** k3s's architecture (documented, well-known community gotcha [CITED via WebSearch synthesis — not an official prometheus-community doc, treat as MEDIUM confidence]).
**How to avoid:** Set `kubeControllerManager.enabled: false`, `kubeScheduler.enabled: false`, `kubeEtcd.enabled: false`, `kubeProxy.enabled: false` in the chart values (k3s uses kine/SQLite, not etcd, reinforcing why the etcd monitor can never work here).
**Warning signs:** Four permanently-down Prometheus targets in the `monitoring` namespace immediately after install — check `kubectl get servicemonitors -n monitoring` and Prometheus's own Targets page as a Wave 0 gate.

### Pitfall 6: No chart flag exists to seed Grafana's SQLite DB with the old public-dashboard tokens
**What goes wrong:** Assuming `grafana.persistence.existingClaim` or similar magically restores the old `grafana.db` content — it only points at a PVC, it does not seed one.
**Why it happens:** The kube-prometheus-stack Grafana subchart's persistence options manage the *volume*, not its *initial content* [confirmed by WebSearch across grafana/helm-charts issues this session — no flag exists for seeding from an external file].
**How to avoid:** Either (a) pre-create the PVC, run a one-off Job/Pod during the D-03 window that copies the SCPed `grafana.db` file into the new PVC's path before Grafana's own Pod starts, or (b) accept D-07's explicitly-offered alternative — recreate the two public shares fresh and update README's two links in the same change. Option (b) is simpler and lower-risk; flag this as a decision for the planner/user rather than defaulting silently.
**Warning signs:** The two README links 404 or show "Dashboard not found" after cutover if neither option is executed.

### Pitfall 7: Dashboard PromQL panels silently show "No data" after the label rewrite
**What goes wrong:** cAdvisor/Docker-sourced dashboards use labels like `name`/`container_label_*`; kube-prometheus-stack's kubelet-embedded cAdvisor scrape uses `namespace`/`pod`/`container`. A panel that isn't rewritten renders an empty graph with no error.
**Why it happens:** Same failure class `scripts/verify-public-dashboards.py`'s own docstring already documents for template variables — a query that matches nothing returns HTTP 200 with an empty result, indistinguishable from a genuinely idle metric [VERIFIED: script docstring, read this session].
**How to avoid:** Rewrite every panel's label matchers by hand against the new kubelet cAdvisor label set, then verify against a live Prometheus instance (the existing gate's own documented "KNOWN HOLES" already admit this can't be caught by the static JSON check alone).
**Warning signs:** A dashboard that renders its shell fine but every panel is blank.

## Code Examples

### ImagePolicy for D-15's sortable tag scheme (`main-<run_number>-<sha7>`)
```yaml
# Source: fluxcd.io/flux/guides/sortable-image-tags (Context7, official)
apiVersion: image.toolkit.fluxcd.io/v1
kind: ImagePolicy
metadata:
  name: kanban-board-backend-prod
  namespace: flux-system
spec:
  imageRepositoryRef:
    name: kanban-board-backend
  filterTags:
    pattern: '^main-(?P<num>[0-9]+)-[a-f0-9]{7}$'
    extract: '$num'
  policy:
    numerical:
      order: asc   # highest extracted number == latest run_number == newest
```
CI's tag-generation step changes from `image_tag=${GITHUB_SHA::7}` to something like `image_tag=main-${{ github.run_number }}-${GITHUB_SHA::7}` — `run_number` is already a monotonically increasing per-workflow-file counter GitHub provides for free, satisfying "sortable" without adding a timestamp dependency.

### Flux bootstrap without a GitHub PAT (satisfies D-15's "no new CI credentials")
```bash
# Source: fluxcd.io/flux/installation/bootstrap/github, "Bootstrap without a GitHub PAT" (Context7, official)
ssh-keygen -t ed25519 -f flux-deploy-key -C "flux@kanban-board-backend" -N ""
gh repo deploy-key add flux-deploy-key.pub --repo RudVlad473/kanban-board-backend --title flux-deploy-key --allow-write
flux install --components-extra=image-reflector-controller,image-automation-controller
kubectl create secret generic flux-system \
  --namespace flux-system \
  --from-file=identity=flux-deploy-key \
  --from-file=identity.pub=flux-deploy-key.pub \
  --from-literal=known_hosts="$(ssh-keyscan github.com)"
flux create source git flux-system \
  --url=ssh://git@github.com/RudVlad473/kanban-board-backend \
  --branch=main \
  --secret-ref=flux-system
flux create kustomization flux-system \
  --source=GitRepository/flux-system \
  --path=./k8s/flux-system \
  --prune=true
```
Branch protection check performed live this session: `gh api repos/RudVlad473/kanban-board-backend/branches/main/protection` → `404 Branch not protected` [VERIFIED]. No bypass configuration is currently needed for `ImageUpdateAutomation` to push to `main`; re-check at execution time.

### cert-manager staging → production issuer switch
```yaml
# Source: cert-manager.io tutorials (Context7, official)
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-staging
spec:
  acme:
    server: https://acme-staging-v02.api.letsencrypt.org/directory
    email: andrejopa387@gmail.com
    privateKeySecretRef:
      name: letsencrypt-staging
    solvers:
      - http01:
          ingress:
            ingressClassName: traefik
```
Validate all three hostnames against `letsencrypt-staging` first (untrusted chain, but proves the HTTP-01 solve path end-to-end through Traefik), then switch each `Certificate`/ingress-shim annotation's `issuerRef.name` to a second `letsencrypt-production` `ClusterIssuer` (identical shape, `server: https://acme-v02.api.letsencrypt.org/directory`) and delete the old `Secret` to force reissue against the real CA, per D-13.

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| Promtail (Docker log discovery via docker.sock) | Grafana Alloy (k8s-native pod log discovery) | Promtail formally EOL 2026-03-02 [VERIFIED: repo's own prior comment, docker-compose.prod.yml] | No docker.sock-equivalent mount needed in k8s — Alloy reads pod logs via the kubelet's own log directory, a materially smaller attack surface than the current promtail's `docker.sock:ro` mount |
| Loki chart key `deploymentMode: SingleBinary` | Renamed `Monolithic` as of community chart ≥12.0.0 | Chart release, exact date not independently verified this session | Verify chart version pinned at install time and use the matching key — an old tutorial using `SingleBinary` against a new chart will silently no-op |
| `flux bootstrap github` (PAT-based) | `flux install` + manual `GitRepository`/deploy key for PAT-averse setups | Documented as a first-class alternative in current Flux docs, not a new discovery | This is the mechanism D-15 needs; not a deprecation, just the less-traveled documented path |

**Deprecated/outdated:** Promtail (superseded by Alloy, per Grafana Labs' own migration guidance cited above).

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|----------------|
| A1 | The specific memory figures for k3s core (~700MB), Flux (~250MB), cert-manager (~150MB), Traefik (~100MB), kube-prometheus-stack (~1.4GB), Loki+Alloy (~450MB) in the Memory Budget section are WebSearch-derived ranges, not measured on this VPS | Memory Budget below | The steady-state and interim budgets could be meaningfully wrong in either direction; this is explicitly flagged as needing a Wave 0 live-measurement checkpoint before the nonprod cutover is declared complete, per CONTEXT.md's own instruction |
| A2 | k3s pods can route to the Docker `docker0` bridge gateway IP (`172.17.0.1`) with no additional iptables rule, because `DOCKER-USER`'s `! -i eth0 -j RETURN` rule allows non-external-interface traffic through | Pattern 1 / Pitfall 1 | If flannel's own iptables rules or routing table entries block this path, the interim Postgres bridge doesn't work as designed and needs an alternate mechanism (e.g. `hostNetwork: true` on a small proxy pod) — this needs live verification once k3s is actually installed, which this read-only research session could not do |
| A3 | Traefik's `RateLimit` `average`/`burst`/`period` triple as configured in Pattern 2 approximates Caddy's fixed-window zones closely enough to preserve the intended bcrypt-cost protection | Pattern 2 | If the token-bucket's continuous refill allows a materially higher sustained rate than the fixed window did, the auth-endpoint protection is weaker than today's — D-13 already mandates re-deriving `verify-rate-limit.yml`'s thresholds empirically, which would catch this |
| A4 | k3s-packaged Traefik version is v3.x as of the current k3s stable release | Standard Stack | Cosmetic — affects which Traefik CRD API version (`traefik.io/v1alpha1` is stable across v2/v3) to target; low risk |
| A5 | kube-prometheus-stack's control-plane ServiceMonitors failing on k3s (Pitfall 5) is accurate current behavior, not a stale community report | Pitfall 5 | If k3s has since shipped compatible endpoints, disabling these ServiceMonitors merely loses metrics nobody needed anyway (safe over-caution) rather than breaking anything |

## Open Questions

1. **Does observability (kube-prometheus-stack+Loki) move to k3s at the same time as nonprod (D-02), or wait until the prod cutover (D-03)?**
   - What we know: D-07 describes the target end state; D-01..D-16 don't explicitly sequence *when* the observability migration happens relative to the nonprod/prod split.
   - What's unclear: If it moves early, it needs to observe the still-Compose prod containers cross-runtime (another bridge, same shape as Pattern 1) to give useful signal during the risky interim (D-08's "24 hours zero OOMKilled" gate needs monitoring active). If it moves late, the interim runs blind on cluster-level metrics, and the memory budget doesn't need to carry two observability stacks simultaneously.
   - Recommendation: Move a **minimal** kube-prometheus-stack (Prometheus + node-exporter + kube-state-metrics only, Grafana deferred) with nonprod, so the interim has cluster-level OOM/restart visibility without paying Grafana's ~700-800MB footprint twice. Bring the full stack (Grafana, Loki, Alloy) online at the prod cutover, alongside the old stack's decommission. This should be confirmed with the user/planner, not assumed silently — it materially changes the interim memory budget below.

2. **Which Grafana public-dashboard continuity option (carry `grafana.db` vs. recreate shares) does the user want?**
   - What we know: Both are viable (D-07 names both); Pitfall 6 has the mechanism for the harder one.
   - What's unclear: No preference stated in CONTEXT.md.
   - Recommendation: Default to recreating shares + updating README (simpler, lower-risk) unless the user specifically wants the exact same URLs preserved.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|--------------|-----------|---------|----------|
| k3s | Entire phase | ✗ (not installed) | — | Install per Standard Stack; VERIFIED not present on netcup-prod this session |
| Docker Engine | D-02 interim, D-04 decommission | ✓ | Live, 13 containers running | — |
| `kubectl`, `flux`, `helm` (CLI) | Cluster ops | ✗ (not installed on VPS) | — | Install alongside k3s; D-09 explicitly forbids CI from needing a `helm` binary — cluster-side install is fine |
| SSH access (`netcup-prod`) | All cutover steps | ✓ | Key-only, verified working this session | — |
| Existing `DOCKER-USER` firewall (80/443-only) | Interim topology safety (Pattern 1) | ✓ | VERIFIED live: exactly 5 rules, matches committed script | — |
| DuckDNS domains (3 hostnames) | cert-manager HTTP-01 | ✓ | VERIFIED: README.md, live Caddyfile | — |
| GitHub branch protection on `main` | Flux `ImageUpdateAutomation` push | ✓ (none configured) | VERIFIED live: 404 "Branch not protected" | Re-check at execution time |

**Missing dependencies with no fallback:** k3s and the CLI tooling — both install cleanly per the Standard Stack section; not a blocker, just not yet done.

## Validation Architecture

> `workflow.nyquist_validation` not found as explicitly `false` in `.planning/config.json` — included per the framework default.

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + REST Assured (existing app-level suite, unaffected by this phase) + `kubeconform` (new, manifest-level) + shell/`kubectl`-based smoke checks (new, cluster-level) |
| Config file | `k8s/**/kustomization.yaml` (new); no existing config file covers cluster manifests |
| Quick run command | `kubectl kustomize k8s/overlays/nonprod \| kubeconform -strict -summary -schema-location default -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' -kubernetes-version 1.36.4` |
| Full suite command | Same, run against both overlays, plus `flux check`, plus the existing `./gradlew test` |

### Phase Requirements → Test Map

This phase has no formal REQUIREMENTS.md — D-01..D-16 stand in as the requirement set.

| Decision | Behavior | Test Type | Automated Command | File Exists? |
|----------|----------|-----------|---------------------|---------------|
| D-08.1 | Public health endpoints, `uptime-check`, `verify-rate-limit`, `verify-public-dashboards.py`, full GitOps deploy all green | integration/smoke | Existing workflows + `flux get kustomizations -A` | ✅ (existing) / ❌ Wave 0 (Flux-specific gate) |
| D-08.2 | Row-count parity pre/post migration | manual-only (one-shot cutover check) | SQL snapshot diff per D-05's own row-count comment | ❌ Wave 0 — write the snapshot SQL |
| D-08.3 | 24h zero `OOMKilled`/restarts | manual-only, time-bound | `kubectl get pods -A -o json \| jq '.items[].status.containerStatuses[].restartCount'` sampled over 24h | ❌ Wave 0 |
| D-12 | Both overlays validate against pinned k8s version | automated, pre-merge | kubeconform command above | ❌ Wave 0 — new CI job |
| D-13 (rate limit) | Prod-only, per-client limiting proven with 2 distinct source IPs | integration | Extend `scripts/loadtest/run-rate-limit-verification.sh`-equivalent against Traefik | ❌ Wave 0 |
| D-06 | `activity-log` consumer lag 0, DLT empty before broker stop | manual-only (cutover runbook step) | `rpk group describe activity-log` / `rpk topic consume kanban.activity.dlt --num 0` | ❌ Wave 0 — documented in cutover runbook |

### Sampling Rate
- **Per task commit:** `kubectl kustomize <overlay> | kubeconform ...` (seconds)
- **Per wave merge:** kubeconform on both overlays + `flux check` + existing `./gradlew test`
- **Phase gate:** Full D-08 checklist, including the 24h observation window, before Compose is decommissioned (D-04)

### Wave 0 Gaps
- [ ] New CI job running kubeconform against both rendered overlays
- [ ] Pre-cutover row-count snapshot SQL script
- [ ] Cutover runbook section documenting the `rpk` lag/DLT checks (D-06)
- [ ] A minimal live check that kube-prometheus-stack's control-plane ServiceMonitors are correctly disabled (Pitfall 5) — `kubectl get servicemonitors -n monitoring -o name` plus a Prometheus Targets-page spot check
- [ ] Live proof of `externalTrafficPolicy: Local` preserving distinct client IPs (Pitfall 2) — extend the existing rate-limit verification harness

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|----------------|---------|--------------------|
| V2 Authentication | No change | Application-level auth unaffected by this infra migration |
| V3 Session Management | No change | Spring Session JDBC unaffected; DB connection string changes only |
| V4 Access Control | Yes | Kubernetes `NetworkPolicy` replaces Docker network isolation for the shared-Postgres access boundary (D-11's "only the two app namespaces and the exporter") |
| V5 Input Validation | No change | Application-level, unaffected |
| V6 Cryptography | Yes | cert-manager + Let's Encrypt — never hand-roll certificate issuance; k3s `--secrets-encryption` for etcd/kine-equivalent secret-at-rest encryption (D-10) |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|-----------------------|
| A compromised app pod reaching Postgres directly, bypassing the app's own auth | Elevation of Privilege | `NetworkPolicy` restricting port 5432 ingress to the two app namespaces + exporter only (D-11) |
| Flux's write-scoped deploy key leaking (repo compromise) | Spoofing / Tampering | Key is scoped to this single repo only (D-15), stored as a cluster Secret with `--secrets-encryption` at rest, never in CI |
| ACME HTTP-01 challenge hijack via an exposed NodePort during the interim | Spoofing | The interim topology (Pattern 1) keeps Traefik's ports unreachable from the internet except through Caddy's own already-TLS'd proxy — the DOCKER-USER firewall structurally prevents direct NodePort exposure |
| A stale/removed Compose `verify-compose-ports.py` exception left in place after Postgres migrates (Pitfall 1) | Tampering | Explicit removal step tied to the D-05 migration commit, called out above |

## Sources

### Primary (HIGH confidence)
- `/k3s-io/docs` (Context7) — HelmChartConfig customization, ServiceLB/externalTrafficPolicy behavior, `--disable`, local-path-provisioner defaults, `INSTALL_K3S_VERSION` usage
- `/websites/fluxcd_io_flux` (Context7) — bootstrap without PAT, ImagePolicy sortable-tag pattern, ImageUpdateAutomation push config
- `/cert-manager/website` (Context7) — Issuer/ClusterIssuer manifests, HTTP01 solver ingress config
- `update.k3s.io/v1-release/channels` — queried live this session for the current `stable` k3s version
- `gh api repos/RudVlad473/kanban-board-backend/branches/main/protection` — queried live this session
- Live read-only SSH to `netcup-prod`: `free -h`, `docker ps`, `iptables -S FORWARD/DOCKER-USER`, `docker network inspect` subnets, `docker stats`
- This repo, read directly this session: `docker-compose.prod.yml`, `docker-compose.nonprod.yml`, `Caddyfile`, `.github/workflows/deploy.yml`/`verify-rate-limit.yml`/`invariant-checks.yml`, `docker/postgres-init/01-create-databases-and-roles.sh`, `docker/grafana/provisioning/datasources/datasources.yaml`, `scripts/verify-public-dashboards.py`, `infra/vm/docker-user-firewall.sh`, `README.md`, `.planning/phases/13-introduce-kubernetes/13-CONTEXT.md`, `.planning/STATE.md`

### Secondary (MEDIUM confidence)
- WebSearch, cross-checked against official docs where available: kube-prometheus-stack control-plane ServiceMonitor issue on k3s, Redpanda's official resource-flag derivation, kubeconform + CRDs-catalog usage pattern, Traefik RateLimit CRD shape, klipper-lb GitHub issue #14 (fetched directly)

### Tertiary (LOW confidence)
- WebSearch-only, not cross-checked against an authoritative source: all memory-footprint figures in the Memory Budget section below (Flux total footprint range, cert-manager per-component memory, kube-prometheus-stack minimal-values memory) — explicitly flagged, see Assumptions Log A1

---

## Q8 — Memory Budget

All "existing" figures below are VERIFIED (live `docker stats` this session, cross-checked against CONTEXT.md's own 2026-09-25 baseline — the two agree within measurement noise). All "new platform" figures are ASSUMED/CITED ranges from WebSearch, **not measured on this hardware** — see Assumption A1. Host: 7.8 GiB total, **4.9 GiB available, no swap** [VERIFIED, CONTEXT.md baseline + live `free -h` this session].

### Final steady-state (Compose fully decommissioned, D-08 gate passed)

| Workload | `requests.memory` (recommend = measured peak) | `limits.memory` (recommend = today's compose cap) | Basis |
|----------|-----------------------------------------------|----------------------------------------------------|-------|
| k3s core (server+agent+containerd+kubelet+flannel+CoreDNS, metrics-server disabled) | ~500 MiB | ~700 MiB | ASSUMED range, WebSearch |
| Traefik | ~60 MiB | ~150 MiB | ASSUMED range, WebSearch |
| cert-manager (controller+webhook+cainjector) | ~80 MiB | ~150 MiB | CITED range (small-cluster examples found this session) |
| Flux (6 controllers, tuned) | ~150 MiB | ~300 MiB | CITED range ("<100MB tuned" to "~360MB untuned") |
| Prometheus Operator + kube-state-metrics | ~130 MiB | ~250 MiB | ASSUMED |
| Prometheus | ~256 MiB | ~400 MiB | Matches today's already-measured standalone Prometheus (256Mi adopted, VERIFIED) |
| Grafana | ~547 MiB | ~768 MiB | Matches today's already-measured value (VERIFIED, live `docker stats`) |
| node-exporter (DaemonSet, 1 node) | ~20 MiB | ~32 MiB | Matches today's value (VERIFIED) |
| Loki | ~94 MiB | ~256 MiB | Matches today's value (VERIFIED) |
| Grafana Alloy | ~80 MiB | ~150 MiB | ASSUMED (successor to Promtail's 60Mi, more featureful) |
| Postgres | ~70 MiB | 256 MiB | VERIFIED, today's value |
| postgres-exporter | ~20 MiB | 32 MiB | VERIFIED |
| Redpanda (prod) | ~450 MiB | 2200 MiB | VERIFIED |
| Redpanda (nonprod) | ~335 MiB | 900 MiB | VERIFIED |
| app (prod) | ~490 MiB | 3072 MiB | VERIFIED |
| app (nonprod) | ~431 MiB | 1024 MiB | VERIFIED |
| **Sum of `requests`** | **~4263 MiB** | | Must fit under ~4.9 GiB available minus k3s's own reserved allocatable slice |
| **Sum of `limits`** | | **~10589 MiB** | Overcommitted, matching today's already-accepted pattern (9.1 GiB limits vs 7.8 GiB total) — legitimate under k8s's model since only `requests` gate scheduling |

**Verdict:** the `requests` sum (~4.26 GiB) leaves only ~600 MiB of the 4.9 GiB available headroom — **tight but plausible**, contingent entirely on the ASSUMED platform-overhead figures (k3s+Traefik+cert-manager+Flux+Prometheus-Operator+kube-state-metrics ≈ 1.2 GiB of that sum) landing close to reality. This is a genuine risk, not a comfortable margin, and is exactly why CONTEXT.md calls for confirming it on the real box before the prod cutover.

### Interim transient peak (D-02: nonprod on k3s, prod still on Compose)

This is the harder number, and depends on Open Question 1's resolution.

| Scenario | Approx. `requests` sum | Fits in 4.9 GiB? |
|----------|--------------------------|---------------------|
| **Minimal interim observability** (Prometheus+node-exporter+kube-state-metrics on k3s only; old Grafana/Loki/Prometheus/promtail/cadvisor/node-exporter/postgres-exporter stay on Compose, unchanged) | k3s platform (~500) + Traefik (~60) + cert-manager (~80) + Flux (~150) + mini-Prometheus stack (~400) + nonprod app (~431) + nonprod redpanda (~335) **on k3s**, PLUS existing Compose prod stack minus nonprod's old containers (app ~490 + postgres ~70 + postgres-exporter ~20 + redpanda-prod ~450 + caddy ~26 + grafana ~547 + prometheus ~196 + loki ~94 + promtail ~60 + node-exporter ~18 + cadvisor ~72) | **~4000 MiB** — plausible, roughly matches today's already-measured ~2.9 GiB used plus the new k3s-side additions |
| **Full interim observability** (both full kube-prometheus-stack+Loki+Alloy AND the full old Compose stack run concurrently) | Adds Grafana (~547) + full Prometheus (~256) + Loki (~94) + Alloy (~80) on top of the minimal scenario | **~5000 MiB — likely exceeds the 4.9 GiB available budget** |

**Verdict / planning blocker flag:** the **full interim observability** scenario does not fit and should **not** be planned as the default. Recommend Open Question 1's minimal path (defer Grafana/Loki/Alloy until the prod cutover) as a locked decision before planning proceeds, not left as researcher discretion — this materially changes which plans exist in the nonprod wave.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — every component is official, documented, widely deployed
- Interim topology (Pattern 1): MEDIUM — mechanism is standard Kubernetes (Service-without-selector) but the specific docker0-gateway routing (A2) is unverified on this exact host
- Memory budget: LOW-MEDIUM — existing-workload figures are VERIFIED; new-platform figures are WebSearch estimates requiring live confirmation
- Pitfalls: MEDIUM-HIGH — each is either a documented mechanism (Job immutability, StatefulSet DNS) or a cited community report with a plausible-but-unconfirmed resolution (klipper-lb)

**Research date:** 2026-09-25
**Valid until:** ~14 days for the fast-moving facts (k3s/Flux/chart versions, klipper-lb issue status) — re-verify `update.k3s.io/v1-release/channels` and branch-protection status immediately before planning starts if more than a few days have elapsed. ~30 days for the architectural patterns and mechanisms, which are stable.
