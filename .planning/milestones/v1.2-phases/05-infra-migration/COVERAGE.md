# API Coverage — Phase 5: Infra Migration

No external API integration: this phase provisions and wires infrastructure (Oracle Cloud console, Neon dashboard, GitHub repo secrets, DuckDNS) as human-operated setup surfaces, and adds no SDK, client library, or callable external API that the application code invokes at runtime.

## Why the detector's signal does not reflect an integration here

The deterministic scan fires on infrastructure vocabulary that appears throughout this phase's scope, but every external surface named is either a one-time human console action or a CI-time credentialed connection — none is a capability surface the codebase calls:

| External surface | Role in this phase | Callable API surface added to `src/`? |
|---|---|---|
| Oracle Cloud console | Human provisions a VM, Reserved IP, Security List and NSG | No |
| Neon dashboard | Human creates a project and copies connection strings | No — the runtime connection is plain JDBC/PostgreSQL, already in use since before this milestone |
| GitHub Actions / repo secrets | CI orchestration and credential storage | No |
| DuckDNS (or equivalent) | Human registers a subdomain and sets one A record | No — no updater client is added, because plan 05-03 provisions a Reserved static IP |
| Let's Encrypt | Certificates obtained automatically by Caddy | No — Caddy is a proxy container, not application code |
| Redpanda Schema Registry | Already integrated in Phase 4 | No new capability — this phase only repoints an existing configured URL |
| Docker Hub registry API | Already used by the pre-existing cleanup jobs | No new capability — plan 05-05 repairs a truncated call in an existing job |

Fabricating a capability matrix for infrastructure that is not a callable API surface would produce rows nobody can integrate or opt out of, which is the opposite of what this gate exists to make visible.

## Where the equivalent rigour lives instead

The gaps this checkpoint guards against — un-enumerated surface, un-decided omissions — are covered for this phase by the multi-source coverage audit in `.planning/phases/05-infra-migration/05-SOURCE-AUDIT.md`, which enumerates every ROADMAP goal element, every requirement ID, every RESEARCH finding and every CONTEXT decision, and records each as covered or explicitly excluded.
