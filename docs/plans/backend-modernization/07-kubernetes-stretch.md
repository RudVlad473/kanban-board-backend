# Epic 7 (optional / stretch) — Kubernetes, local only

[← back to plan index](README.md) · Effort: 2–4 days · Priority: **Low** · Do only if time remains

**Deliberately scoped down:** Kubernetes shows up often in postings but at *conversational deploy
depth* for a developer, not cluster-admin depth. Don't over-invest here.

## Tasks

- Write `k8s/deployment.yaml`, `k8s/service.yaml`, `k8s/configmap.yaml`, `k8s/secret.yaml` for
  the app, plus equivalents for Postgres, Kafka, and Redis (or reference Bitnami/official Helm
  charts for those instead of hand-rolling).
- Get it running locally on `kind` or `minikube` — that's the whole bar. Do not attempt an EC2/EKS
  migration; the existing GitHub Actions → DockerHub → single-EC2 pipeline stays as-is for actual
  deployment. This is a local demonstration artifact for interview purposes, not a production
  migration.
- Be able to explain `kubectl get pods`, `kubectl logs`, `kubectl describe`, what a `Service` vs
  `Deployment` vs `ConfigMap`/`Secret` is for, and roughly how a rolling update works — that's the
  whole depth bar for a mid-level developer per the earlier report.
