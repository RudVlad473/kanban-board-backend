---
created: 2026-08-20T00:00:00.000Z
title: "No secrets-vault for runtime production secrets; no stated key/secret rotation cadence"
area: security
severity: moderate
files:
  - docker-compose.prod.yml
  - docs/INFRA_RUNBOOK.md
---

## Problem

Filed from a 33-agent ASVS 4.0.3 Level 2 audit (ASVS V1.6.1, V1.6.2, V1.6.3, V6.4.1).

CI/build-time secrets are properly vault-managed (GitHub Actions encrypted secrets). Runtime
production DB credentials (`DB_HOST`/`DB_USER`/`DB_PASS`, etc.) are instead read from a
mode-protected plaintext `.env.prod` file on the VM's local disk via `docker-compose.prod.yml`'s
`--env-file` — not vault-managed at rest, no rotation-via-API capability. `docs/INFRA_RUNBOOK.md`'s
secret inventory documents ad hoc last-updated timestamps per secret but no stated rotation
cadence or policy.

## Solution

Evaluate a lightweight secrets approach appropriate to this VPS's scale (e.g. sops-encrypted
`.env.prod` decrypted at deploy time) instead of a plaintext file at rest. Separately, write a
stated rotation cadence/policy into `docs/INFRA_RUNBOOK.md`'s existing secret inventory as a cheap
first step independent of the tooling change.
