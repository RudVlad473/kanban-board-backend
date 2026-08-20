---
created: 2026-08-20T00:00:00.000Z
title: "Production deploys get no automated post-deploy health verification (nonprod does)"
area: ci
severity: moderate
files:
  - .github/workflows/deploy.yml
---

## Problem

Filed from a 33-agent ASVS 4.0.3 Level 2 audit (ASVS V1.14.4).

`deploy.yml`'s `health-check-nonprod` job (polls the live nonprod endpoint after deploy) has no
production equivalent — `deploy-to-netcup` and `register-schemas-production` have no downstream
health-check job.

## Solution

Add a `health-check-production` job mirroring `health-check-nonprod`'s structure (poll the
production health endpoint post-deploy, fail the run on non-2xx/timeout). Note this compounds with
the logging/alerting gap in `2026-08-20-no-remote-log-shipping-structured-logging-or-alerting.md` —
a broken prod deploy could currently go undetected by both the pipeline and any monitoring layer.
