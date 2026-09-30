---
audit_acknowledged:
  milestone: v1.4
  at: 2026-09-30
---

# Reproduce the two-source-IP per-client rate-limit proof

Recorded once in 13-08, never automated or reproduced (13-VERIFICATION truth, D-13). Re-run against the prod signin path with the runner IP plus a second IP and read Traefik's JSON access log ClientHost.
