# Production cutover to k3s — Plan 13-06 (2026-09-26)

D-01/D-03/D-05/D-06 executed live: production moved from Docker Compose to k3s in one
maintenance window. The window ran longer than planned (~59 min vs. the estimated 30-45 min)
because of a real operator error mid-window — see **Incident: premature multi-window push**
below, documented in full per this project's verify-before-claiming convention. Public
production was down (HTTP 502) for part of the window as a direct result of that incident; no
production data was lost at any point.

## 1. Window timeline

| Marker | Time (UTC) | Note |
|---|---|---|
| T_start | 2026-09-26T07:46:01Z | Secrets created (`kanban-data/postgres-init`, `kanban-prod/app-env`), immediately after `docker compose stop app` |
| Incident window (public 502) | ~08:04:41Z – ~08:15:34Z | See incident section — public prod unreachable while Caddy's `app` DNS lookup failed and the in-cluster edge had not yet taken over |
| Real dump/restore completed | ~08:11:40Z | Both databases dumped from Compose, restored in-cluster, parity confirmed |
| ServiceLB re-enabled / edge takeover | ~08:14:08Z | `systemctl restart k3s` with `servicelb` removed from `disable:`; `svclb-traefik` pod Running within ~78s |
| Public HTTPS 200 confirmed | 2026-09-26T08:15:39Z | First off-box `curl` returning `{"status":"UP"}` over a production LE cert |
| T_end | 2026-09-26T08:45:06Z | All edge/cert/NetworkPolicy checks passed |

**Downtime:** public production served HTTP 502 from roughly 08:04:41Z (Caddy's `app` DNS lookup
started failing after `docker compose stop app`, which is *earlier* than the plan intended — see
incident) through 08:15:39Z (first confirmed 200 over the real in-cluster edge) — approximately
**11 minutes** of hard public outage, embedded inside the ~59-minute total window.

## 2. Broker drain evidence (D-06)

Captured before stopping the Compose Redpanda broker (window step 2):

```
$ docker exec kanban-board-backend-redpanda-1 rpk group describe activity-log
GROUP                  activity-log
STATE                  Empty
MEMBERS                0
TOTAL-LAG              0

TOPIC            PARTITION  CURRENT-OFFSET  LOG-START-OFFSET  LOG-END-OFFSET  LAG   ...
kanban.activity  0          2335            2335              2335            0

$ docker exec kanban-board-backend-redpanda-1 rpk topic describe kanban.activity.dlt -p
PARTITION  LEADER  EPOCH  REPLICAS  LOG-START-OFFSET  HIGH-WATERMARK
0          0       8      [0]       0                 0
```

`activity-log` lag 0 on every partition, DLT high-watermark equals log-start-offset (empty).
Both conditions passed before `docker compose stop redpanda` ran.

## 3. Dump inventory

All files under `/root/k3s-cutover-20260926/` on the VM, 0700 permissions, sha256 recorded in
`SHA256SUMS` in the same directory.

| File | Size | sha256 (first 16 hex chars) |
|---|---|---|
| `kanban_prod.dump` | 103,928 bytes | `81d3e1eeaa7df9c4` |
| `kanban_nonprod.dump` | 16,573 bytes | `fbaf19a37678b9b7` |
| `globals.sql` | (reference only, `--no-role-passwords`) | `c3836f84f7717820` |
| `counts-kanban_prod-before.txt` | 72 bytes | `0f83e563846146db` |
| `counts-kanban_nonprod-before.txt` | 61 bytes | `fa95d4a92e3aa9cf` |
| `counts-kanban_prod-after.txt` | (matches before) | included in `SHA256SUMS` |
| `counts-kanban_nonprod-after.txt` | (matches before) | included in `SHA256SUMS` |

`sha256sum -c SHA256SUMS` verified all 7 files `OK` at documentation time.

**Retained on the VM disk, outside any Docker/k3s volume, until the D-08 gate closes in 13-10.**

## 4. Row-count parity (D-08.2)

Both `diff`s empty — exact match between the Compose snapshot (taken with `app` stopped, no
writers) and the post-restore in-cluster count.

| Table | kanban_prod | kanban_nonprod |
|---|---|---|
| activity_log | 2261 | 0 |
| boards | 43 | 0 |
| columns | 249 | 0 |
| subtasks | 983 | 0 |
| tasks | 984 | 0 |
| users | 49 | 0 |

(`kanban_nonprod` was already empty pre-cutover — no nonprod data existed to migrate, matching
13-05's own D-02 tracer state.)

## 5. Certificate issuance (staging → production)

All three Certificates validated via HTTP-01 against `letsencrypt-staging` (13-04/W1), then W3
flipped `issuerRef.name` to `letsencrypt-production` for all three. Live-verified issuers, off-box:

| Hostname | Issuer | Not Before |
|---|---|---|
| `kanban-board-rud-vlad-473.duckdns.org` | `C=US, O=Let's Encrypt, CN=YR2` | 2026-09-26T07:15:39Z |
| `kanban-board-rud-vlad-473-nonprod.duckdns.org` | `C=US, O=Let's Encrypt, CN=YR2` | 2026-09-26T07:05:57Z |
| `kanban-board-rud-vlad-473-monitoring.duckdns.org` | `C=US, O=Let's Encrypt, CN=YR1` | 2026-09-26T07:15:39Z |

No STAGING issuer anywhere at documentation time. The monitoring hostname needed a genuinely new
fix mid-window — see **Gaps found and closed live** below — since nothing in the phase before
this point created a route to actually *serve* `grafana-tls`.

## 6. CI retarget (run IDs)

- Initial `workflow_dispatch` proving the new SSH-forward mechanism: run `36229677270` — both
  `flyway-verify` and `flyway-verify-nonprod` failed on host-key fingerprint verification (a real
  bug in this plan's own new code, fixed live — see Gaps section).
- Fix commit `9d675e5` triggered a real `push` run (it touches `.github/workflows/deploy.yml`,
  outside the `k8s/**` paths-ignore): run `36230184883` — **all jobs green**, including
  `flyway-verify`/`flyway-verify-nonprod` through the pinned-fingerprint SSH forward to
  `10.43.54.32:5432`, proving kube-router admits node-originated CI traffic to `postgres-0`.
- `sshd_config.d/kanban-ci-tunnel.conf` installed and validated (`sshd -t` exit 0) on the VM,
  `systemctl reload ssh` applied.

## 7. NetworkPolicy proof (D-11)

Both probes run with a 3-second startup delay (see Gaps section for why — a genuine pod-startup
DNS/CNI-readiness race, not a policy bug):

```
$ k3s kubectl run np-probe-pos-v2 --image=postgres:16 -n kanban-prod --restart=Never --rm -i \
    --command -- sh -c "sleep 3 && pg_isready -h postgres.kanban-data.svc.cluster.local -t 5"
postgres.kanban-data.svc.cluster.local:5432 - accepting connections

$ k3s kubectl run np-probe-neg-v2 --image=postgres:16 -n monitoring --restart=Never --rm -i \
    --command -- sh -c "sleep 3 && pg_isready -h postgres.kanban-data.svc.cluster.local -t 5"
postgres.kanban-data.svc.cluster.local:5432 - no response
```

An unlabelled pod in `kanban-prod` (an allowed namespace) succeeds; an unlabelled pod in
`monitoring` (not carrying `kanban-board/postgres-client: "true"`) fails. Both probe pods
self-deleted (`--rm`).

## 8. Compose state

`docker ps` empty on the VM (verified multiple times through the window). `docker volume ls`
still lists all 8 Compose volumes (`kanban-board-backend_{caddy-config,caddy-data,grafana-data,
loki-data,postgres-data,prometheus-data,redpanda-data}`, `kanban-board-nonprod_redpanda-nonprod-data`)
— **nothing deleted**. Compose deletion is gated by D-08 in 13-10, not this plan.

---

## Incident: premature multi-window push

**What happened.** This plan's Task 2 staged four sequentially-dependent commits (W1→W2→W3→W4)
on one local branch (`cutover-13-06`), specifically so each could be pushed to `main`
individually at its own gated point in the maintenance window — W2 only after a `docker ps`
empty-guard proved Compose (Caddy included) was fully stopped, W3 only after staging-issuer
certificate validation, W4 only at the CI-retarget step. After pushing W1 alone
(`git push origin edc3eb6:main`) and building a follow-up bug fix on top of it, that fix was
pushed with `git push origin <fix-sha>:main` — but `<fix-sha>` resolved to the **tip of the whole
local branch**, which by that point already contained W2, W3 and W4 committed on top of W1 (built
that way deliberately, for Task 2's own gate-checking). The push silently included all three
later commits along with the intended fix.

Flux reconciled everything within seconds of the push landing:

- **W2 unsuspended `apps-prod`** before any dump/restore had happened. The in-cluster `app`
  pod for production started against an empty, freshly-`initdb`'d database and ran Flyway
  against it, creating a real (but data-less) schema — confirmed live:

  ```
  $ k3s kubectl exec -n kanban-data postgres-0 -- psql -U kanban_admin -d kanban_prod -c "\dt"
                        List of relations
   Schema |           Name            | Type  |      Owner
  --------+---------------------------+-------+-----------------
   public | activity_log              | table | kanban_prod_app
   public | boards                    | table | kanban_prod_app
   ... (9 tables, flyway_schema_history included)

  $ k3s kubectl exec -n kanban-data postgres-0 -- psql -U kanban_admin -d kanban_prod -c "SELECT count(*) FROM users;"
   count
  -------
       0
  ```

  This is exactly the T-13-27 scenario the plan's W1/W2 split exists to prevent.

- **W2 also removed Traefik's interim `web` NodePort 30080 pin** while Caddy (still running on
  Compose) was its only consumer for nonprod traffic — the exact ordering hazard the split was
  designed to prevent, though in practice `docker compose stop app` (window step 1, which had
  already run for an unrelated reason) had already broken Caddy's upstream DNS resolution for the
  `app` service by this point, so the pin's removal was not independently observed to cause
  additional damage.

- **W3 flipped all three Certificates to the production Let's Encrypt issuer** before any
  staging-issuer validation happened in this window (13-04 had already validated them against
  staging in a prior plan, which is why production issuance actually succeeded cleanly once
  Traefik took over the HTTP-01 solver path — but the *order* the window intended was violated).

- **W4's full CI/deploy.yml rewrite landed** (Compose deploy jobs removed, Flyway retargeted)
  before the actual data migration had happened.

**Confirmed public impact.** Production served HTTP 502 starting shortly after `docker compose
stop app` (window step 1) removed the `app` container's entry from Docker's embedded DNS —
confirmed via Caddy's own logs:

```
{"level":"error","...,"msg":"dial tcp: lookup app on 127.0.0.11:53: no such host",
 "request":{...,"host":"kanban-board-rud-vlad-473.duckdns.org","uri":"/api/actuator/health"},
 "status":502}
```

```
$ curl -s -o /dev/null -w "%{http_code}\n" https://kanban-board-rud-vlad-473.duckdns.org/api/actuator/health
502
```

**Confirmed NOT lost — verified before touching anything.** Docker Compose's own production
Postgres was never stopped during this window until after the real dump was taken (window step 6
happened *after* the emergency data migration below, not before it):

```
$ docker exec kanban-board-backend-postgres-1 psql -U kanban_admin -d kanban_prod -c "SELECT count(*) FROM users; SELECT count(*) FROM boards;"
 count
-------
    49
 count
-------
    43
```

```
$ docker volume ls | grep kanban
local     kanban-board-backend_caddy-config
local     kanban-board-backend_caddy-data
local     kanban-board-backend_grafana-data
local     kanban-board-backend_loki-data
local     kanban-board-backend_postgres-data
local     kanban-board-backend_prometheus-data
local     kanban-board-backend_redpanda-data
local     kanban-board-nonprod_redpanda-nonprod-data
```

No `pg_dump`/`pg_restore` had run and no `/root/k3s-cutover-<date>/` directory existed at the
moment the incident was discovered — the in-cluster database's emptiness was purely from Flyway
building a fresh schema, never from a failed restore.

**Recovery, decided and directed by the operator (not improvised alone):** the executing agent
stopped immediately on discovering the 502 and the empty in-cluster schema, gathered the evidence
above, and reported it rather than attempting a unilateral fix. The operator explicitly chose to
push forward rather than roll back — pushing forward through the already-landed W2/W3/W4 state
was judged the faster path back to a served production, since every minute of the 502 was real,
ongoing user-facing downtime. Concretely: the in-cluster `kanban_prod`/`kanban_nonprod` databases
(both still holding only the empty Flyway-created schema, confirmed zero real rows) were dropped
and recreated cleanly under their existing owning roles; the actual dump/restore then ran for
real against Compose's still-fully-intact data (Section 3/4 above); the remaining Compose
containers were stopped; `servicelb` was re-enabled and k3s restarted to complete the edge
takeover; and every remaining plan step (certificate validation, CI retarget, NetworkPolicy
proof) was completed from that point forward, in order.

**The generalizable lesson — recorded durably in [`docs/SESSION_LESSONS.md`](../SESSION_LESSONS.md#8-push-a-staged-multi-commit-cutover-by-explicit-sha-per-gate-never-by-branch-tip)**
(lesson 8): when a plan stages multiple sequentially-dependent commits on one local branch
specifically so they can be pushed individually at separate gated points, push each one by its
**explicit commit SHA** (`git push origin <sha>:main`), never by branch tip or `HEAD` — a
branch-tip push silently includes every later commit already made on top, collapsing every
remaining gate into one push regardless of intent.

## Gaps found and closed live

Three real, pre-existing bugs surfaced only once this plan's own code ran for the first time
against a live cluster — none were introduced by the premature-push incident above, and all were
fixed and re-verified in place rather than routed around:

1. **`k8s/data/postgres/postgres.yaml`'s readinessProbe used `$(POSTGRES_USER)`** inside an
   `exec.command` shell string. That is Kubernetes' field-reference expansion syntax, which only
   expands inside `command`/`args`/`env.value` fields — never inside a probe's own shell string.
   The container's `sh -c` parsed it as command substitution instead (attempting to run
   `POSTGRES_USER` as a command), which failed and substituted nothing, producing
   `pg_isready: error: too many command-line arguments (first is "postgres")`. Fixed to real
   shell variable expansion (`$POSTGRES_USER`, no parens) — the container's own environment
   already carries it via `envFrom: postgres-init`.

2. **`k8s/platform/cert-manager/cert-manager.yaml`'s `HelmRepository`/`HelmRelease` carried no
   `metadata.namespace`**, and the directory's `kustomization.yaml` sets no top-level namespace
   either, so `kubectl apply` rejected the `HelmRepository` outright:
   `HelmRepository/jetstack namespace not specified: the server could not find the requested
   resource`. Fixed to the convention `k8s/monitoring/controllers/` already establishes:
   `HelmRepository` lives in `flux-system`, cross-referenced by the `HelmRelease`'s
   `sourceRef.namespace`.

3. **No `IngressRoute` anywhere in the phase serves `grafana-tls` for the monitoring hostname's
   SNI.** `certificate-monitoring.yaml` (13-04) creates the Certificate and gets it issued, but
   13-07's own plan text already assumes `k3s kubectl get ingressroute grafana -n monitoring`
   exists by the time it runs. Closed with a minimal placeholder `IngressRoute grafana` /
   `IngressRoute grafana-http` pair, using the exact names 13-07 expects, backed by a
   `grafana-placeholder` Service with no Endpoints (Traefik answers 503 — a clean "nothing here
   yet" response) until 13-07 replaces the backend with the real Grafana Service.

4. **`flyway-verify`/`flyway-verify-nonprod`'s new SSH-forward mechanism (this plan's own W4)
   had a host-key fingerprint comparison bug**, caught the first time it ran in CI:
   `ssh-keyscan -H` (no `-t`) scans every host key algorithm the server offers, so
   `ssh-keygen -lf` prints one fingerprint per algorithm; the original code collapsed them with
   `sort -u` and compared the whole multi-line string against one pinned fingerprint, which
   always failed even against the correct host. Fixed to check whether the pinned fingerprint
   is one of the scanned lines (`grep -qxF`).

5. **The NetworkPolicy positive probe initially failed** with the identical `no response` the
   negative probe correctly produces, which looked like a policy bug. Diagnosed as a genuine
   pod-startup race: `kubectl run --rm -i` executes the container's command essentially
   immediately at start, before CoreDNS/CNI routing has finished settling for the new pod,
   especially soon after a `systemctl restart k3s`. A live, longer-lived diagnostic pod
   (`kubectl exec` into an already-Running pod) succeeded immediately; re-running both probes
   with a 3-second `sleep` before the check produced the correct positive/negative pair
   consistently. Not a data-loss risk, not fixed in any manifest — just a timing note for the
   next person who reaches for `kubectl run --rm` right after a cluster restart.
