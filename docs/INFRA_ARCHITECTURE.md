# Infrastructure Architecture

This document describes the production deployment topology introduced by v1.2's Infra Migration
milestone (Phase 5): a Netcup VPS Lite 2 G12s VM (Vienna, x86_64) running the application, a
self-hosted Redpanda broker, and Caddy for automatic public HTTPS, backed externally by Neon
serverless Postgres (Frankfurt) and delivered via GitHub Actions. The original target was Oracle
Cloud's Always Free A1 Flex (ARM64); it was replaced after Oracle's free-tier capacity in the
planned region proved structurally unavailable — see `docs/INFRA_RUNBOOK.md` and Phase 5 Plan
05-03's SUMMARY for the pivot rationale.

Per [`docs/DIAGRAM_CONVENTIONS.md`](DIAGRAM_CONVENTIONS.md), each diagram below is one deliberate
Kruchten 4+1 view, not an ad hoc mix of concerns. The two views chosen are the ones that matter
most for this deployment: **Physical/Deployment** (what runs where, on what hardware) and
**Scenario (+1)** (the one end-to-end flow — push to master, deploy — traced across the other
views).

## Physical/Deployment View

Maps software to physical/hardware nodes, with every node labelled by its platform/CPU
architecture — the discipline this project adopted specifically because a plain "what talks to
what" diagram would not have surfaced the CI pipeline building an x86_64-only image for an ARM64
deploy target (see `DIAGRAM_CONVENTIONS.md`'s own note on this).

![Flowchart: physical/deployment view of the production topology](diagrams/infra-physical-deployment.png)
<sub>[diagram source](diagrams/infra-physical-deployment.mmd)</sub>

**Externally reachable vs. internal-only:** only Caddy's ports 80 and 443 are published on the
VM's host network and reachable from the internet (443 serves traffic, 80 exists solely for the
Let's Encrypt HTTP-01 challenge and Caddy's automatic HTTP→HTTPS redirect). The app's port 8080
and Redpanda's Kafka (`19092`) and Schema Registry (`8081`) listeners publish no host port at all
— every edge among `caddy`, `app`, and `redpanda` stays on the Compose-internal Docker network and
never crosses the VM's trust boundary. This is the entirety of INFRA-08: the only two host ports
punched through the VM's network layers by Docker are 80 and 443.

**Where TLS terminates:** Caddy terminates public TLS at the VM boundary using an automatically
obtained, publicly trusted Let's Encrypt certificate (no self-signed/internal TLS). Traffic from
Caddy to the app inside the VM is plain HTTP — safe only because that hop never leaves the
internal Docker network. The connection from the app to Neon is a second, independently encrypted
hop: TLS required (`sslmode=require`) with channel binding (`channel_binding=require`), terminated
between the app container and Neon's endpoint, unrelated to Caddy's certificate.

**Stateful components and where state lives:** the VM itself holds no user data of record — every
piece of durable application state lives either in Neon (the system of record for all domain data)
or in two named Docker volumes scoped to operational/transport concerns: Redpanda's data volume
(the Kafka log and Schema Registry's internal topics — replayable operational state, not the
source of truth) and Caddy's certificate/state volume (so container recreation reuses the existing
Let's Encrypt certificate instead of triggering a rate-limited re-request). Losing either named
volume loses operational continuity, not user data.

## Scenario (+1) View — Delivery Path

Traces one key end-to-end scenario — push to `master` through to a running deploy — across the
other views, confirming they stay consistent with each other. **This diagram describes the target
state after plan 05-05 lands, not what is live today** — the build job already builds and pushes a
`linux/amd64` image on every push to master (confirmed working, no QEMU cross-compilation needed
since Netcup's x86_64 matches `ubuntu-latest` runners natively), but the deploy and
DDL-verification jobs themselves are still built by later plans in this phase (05-04, 05-05); the
existing `deploy-to-ec2` job remains disabled (`if: false`), targeting a host that no longer
exists.

![Sequence diagram: delivery path from push to master to a running deploy](diagrams/infra-delivery-scenario.png)
<sub>[diagram source](diagrams/infra-delivery-scenario.mmd)</sub>

**Externally reachable vs. internal-only (delivery path):** the GitHub Actions runner reaches
Docker Hub and Neon's direct endpoint over the public internet (both require real network
egress); the SSH hop to the VM is authenticated via a pinned host-key fingerprint, not left to
`StrictHostKeyChecking` defaults. None of these delivery-path connections touch Redpanda or the
app's internal-only listeners — the pipeline only ever talks to the VM's SSH port and to Caddy's
public HTTPS is not part of the delivery path itself.

**Where TLS terminates (delivery path):** the SSH connection from the runner to the VM is
encrypted end-to-end by SSH itself, independent of Caddy's certificate. The `ddl-verify` job's
connection to Neon uses the same TLS-required, channel-binding-required posture as the app's own
runtime connection, over Neon's **direct** (non-pooled) endpoint specifically for this job — the
pooled endpoint does not support the DDL-verification job's needs (see `05-RESEARCH.md`'s
Pitfall C).

**Stateful components (delivery path):** Docker Hub holds the built image tags (build artifacts,
not user data); GitHub Actions itself holds no durable state between runs. No step in this
pipeline writes application data — `ddl-verify` only applies idempotent schema DDL, and the deploy
step only replaces running containers.

## Maintenance Note

This document describes `docker-compose.prod.yml`, `Caddyfile`, and the `build-and-push-docker-image`
job in `.github/workflows/deploy.yml` (specifically its `linux/arm64` platform target). If any of
those three files' topology, ports, or delivery flow changes, update this document in the same
change — it is the single checked-in description of what actually runs where.
