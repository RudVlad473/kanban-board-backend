---
created: 2026-09-08T00:00:00.000Z
title: "Grafana's deployed admin password no longer matches .env.prod"
area: infra
severity: minor
files:
  - .env.prod
---

## Problem

Found during Phase 12 plan 12-05's restart-ladder measurement, still open at phase close.

The VM's deployed Grafana instance's persisted admin account password does not match the current
`.env.prod` `GRAFANA_ADMIN_PASSWORD` value (differing length: 18 vs. 16 characters). Grafana only
applies `GF_SECURITY_ADMIN_PASSWORD` on first boot — a later credential rotation in `.env.prod`
without a matching reset of Grafana's own persisted admin account (stored in the `grafana-data`
named volume) silently falls out of sync, and container recreates since then haven't corrected it
because the volume already holds an admin account.

This blocked a full authenticated Grafana UI dashboard render during Phase 12's measurement and
verification work — data-presence was confirmed instead via direct Prometheus/Loki API calls, and
Phase 12's final verification pass accepted this as a documented override rather than a blocker,
but "a human can actually log into Grafana and see the dashboards" remains unconfirmed at the UI
layer.

## Solution

Either:
1. Reset Grafana's admin password via its own admin CLI/API (`grafana-cli admin reset-admin-password
   <new-password>` run inside the container, or the `/api/admin/users/{id}/password` API) to match
   the current `.env.prod` value, or
2. Rotate `.env.prod`'s `GRAFANA_ADMIN_PASSWORD` to match whatever Grafana's persisted account
   currently holds (harder — the current value would need to be recovered or reset first anyway).

Option 1 is simpler and doesn't require guessing the current live value. After fixing, verify with
an actual authenticated login through the public Grafana URL (`https://<monitoring-hostname>/login`)
and confirm all three dashboards render real data — closing the gap Phase 12's verification left
open.

## Resolution

Superseded rather than fixed in place: Phase 13's k3s cutover replaces the whole Grafana instance
this todo describes (Compose's `grafana-data` named volume and its persisted admin account are
gone entirely once 13-10 deletes the Compose stack). The new Grafana (kube-prometheus-stack, plan
13-03/13-07) takes its admin password from Kubernetes Secret `monitoring/grafana-admin`, built
directly from `.env.prod`'s `GRAFANA_ADMIN_PASSWORD` at activation time (13-07 Task 1, D-10) — there
is no first-boot-only persisted account to drift out of sync with `.env.prod` going forward,
because the Secret is the single source of truth on every reconcile.

Verified live (13-07 Task 1): authenticated `GET /api/user` against the new Grafana with the
`.env.prod` password returned `"login":"admin","isGrafanaAdmin":true` — closing this todo's own
stated verification bar (an actual authenticated login, all three dashboards rendering real data,
confirmed again in 13-07 Task 2's per-panel diagnosis pass).
