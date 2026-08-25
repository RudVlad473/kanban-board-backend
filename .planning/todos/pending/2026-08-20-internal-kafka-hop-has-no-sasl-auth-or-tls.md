---
created: 2026-08-20T00:00:00.000Z
title: "Internal Kafka / schema-registry hop has neither SASL auth nor TLS"
area: security
severity: moderate
files:

  - docker-compose.prod.yml
  - docs/INFRA_ARCHITECTURE.md

audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## Problem

Filed from a 33-agent ASVS 4.0.3 Level 2 audit (ASVS V1.2.2, V9.2.2, V1.9.1).

Redpanda's command block in `docker-compose.prod.yml` sets only
`--kafka-addr`/`--advertise-kafka-addr`/`--rpc-addr` with no `--sasl` or TLS listener flag. The
same finding applies to the Caddy-to-app internal hop (also plain HTTP, per
`docs/INFRA_ARCHITECTURE.md`'s own documented trade-off). Compensating control: neither app nor
redpanda publishes a host port (Docker-internal only) — real gap against the letter of the
requirement, exploitability requires an attacker already inside the VM's Docker network.

## Solution

Add SASL/SCRAM and/or TLS to the internal Redpanda listener and, resources permitting, the
Caddy-to-app hop. If deferred, document the accepted-risk compensating control (Docker-internal-
only networking) explicitly in `docs/INFRA_ARCHITECTURE.md` rather than leaving it an implicit
assumption.
