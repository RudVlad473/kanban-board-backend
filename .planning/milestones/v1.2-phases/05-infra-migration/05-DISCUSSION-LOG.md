# Phase 5: Infra Migration - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-04
**Phase:** 5-Infra Migration
**Areas discussed:** Domain name for HTTPS, Secrets generation approach, Manual first deploy vs. immediate automation

---

## Domain name for HTTPS

| Option | Description | Selected |
|--------|-------------|----------|
| Already have a domain | Provide the domain, configure Caddy + DNS A record | |
| Get a free subdomain | DuckDNS or equivalent — real, cert-eligible hostname at zero cost | ✓ |
| Buy a cheap domain now | ~$10-15/yr, reads better for a portfolio link, requires purchase outside this session | |

**User's choice:** Free subdomain (after a clarifying detour).
**Notes:** User initially asked which deployment platform was chosen, referencing that some platforms (per a Gemini conversation) include free subdomains built in. Clarified: we're on Oracle Cloud (self-managed IaaS via Docker Compose + Caddy), not a PaaS like Railway/Render/Fly.io — PaaS platforms bundle free subdomains + automatic HTTPS because you deploy into their managed environment; Oracle Cloud just gives a bare VM with a public IP, which is why this domain question exists at all. This was confirmed as an already-understood, deliberate tradeoff from earlier platform-selection discussion (Oracle's more generous free compute vs. PaaS convenience), not a reason to reconsider the platform. User confirmed free subdomain works given that context. Captured as D-01 in CONTEXT.md.

---

## Secrets generation approach

| Option | Description | Selected |
|--------|-------------|----------|
| Guided step-by-step during execution | As each secret is needed, told exactly what to generate/paste and where | ✓ |
| Generate everything upfront | User front-loads all secrets before execution starts | |

**User's choice:** Guided step-by-step (recommended option).
**Notes:** Claude never handles credential values directly regardless of choice (platform-level restriction) — this only affects sequencing/guidance style. Captured as D-02 in CONTEXT.md.

---

## Manual first deploy vs. immediate automation

| Option | Description | Selected |
|--------|-------------|----------|
| Manual first, then automate | SSH in, docker compose up by hand, confirm stack works, then wire up CI/CD | ✓ |
| Build CI/CD first, let it deploy | Author the pipeline first, let it perform the first deploy | |

**User's choice:** Manual first, then automate (recommended option).
**Notes:** Isolates "does the stack work" from "does the pipeline work" for easier debugging. Captured as D-03 in CONTEXT.md.

---

## Claude's Discretion

- Exact Oracle Cloud A1 Flex provisioning steps (OS image, SSH access setup)
- Whether to verify actual current OCI tenancy shape (2 vs 4 OCPU) as an early task, or plan conservatively for 2 OCPU/12GB
- Exact free-subdomain service choice (DuckDNS or equivalent)
- Production `docker-compose.yml` structure (single file with prod env vars vs. a `.prod.yml` overlay)
- Redpanda resource-cap exact values — deferred to measurement during execution
- GitHub Actions SSH deploy mechanics (`appleboy/ssh-action` per research recommendation)

## Deferred Ideas

- Full observability stack, blue-green deploys, multi-broker Redpanda HA — already deferred to v2 (INFRA-V2-01/02/03) during requirements definition
- Pre-merge schema-compatibility CI check, documented compatibility-mode rationale — already deferred to v2 (SCHEMA-V2-01/02) during Phase 4, unaffected here
- 4 low-confidence todo matches (Java 25 bump, dependency vuln scan, PMD/Checkstyle, and 4 Kafka/schema-adjacent todos) reviewed and left deferred — not topically about infra deployment
</content>
