# Requirements: Kanban Board Backend — v1.2 Infra Migration & Schema Registry

**Defined:** 2026-08-03
**Core Value:** Make the app reachable and cheaply/reliably deployable again after the AWS EC2/RDS deletion, and close the schema-evolution risk flagged during v1.1 (SEED-001) — without diluting the backend-depth showcase already built.

## v1 Requirements

### Infra Migration

- [ ] **INFRA-01**: App is deployed on an Oracle Cloud Always Free A1 Flex VM via Docker, with `restart: unless-stopped` and healthchecks on the app container
- [ ] **INFRA-02**: Production database is Neon serverless Postgres (pooled connection string, `sslmode=require`, HikariCP sized for Neon's cold-start/pooling behavior), replacing the deleted RDS/EC2-hosted Postgres with no JPA/Hibernate code changes
- [ ] **INFRA-03**: Kafka broker in the deploy target is a self-hosted single-node Redpanda instance, with explicit `--overprovisioned`/`--memory`/`--smp` resource caps so it cannot starve the co-resident app JVM on the shared VM
- [ ] **INFRA-04**: App is publicly reachable over real HTTPS via a Caddy reverse proxy with automatic TLS (not bare HTTP/IP)
- [ ] **INFRA-05**: GitHub Actions builds, pushes, and deploys to the Oracle VM automatically on merge to `master`, using newly-generated SSH credentials (the dead AWS-era secrets are not reused)
- [ ] **INFRA-06**: A pre-merge DDL verification step runs against Neon's direct connection string before merge, replacing the AWS-target check acknowledged as superseded at v1.1 close
- [ ] **INFRA-07**: Docker log drivers are capped (`max-size`/`max-file`) so unbounded app/Redpanda logs cannot fill the free-tier VM's disk
- [ ] **INFRA-08**: OCI's three network layers (Security List, NSG, OS firewall) are audited together and externally verified (port scan/curl from outside) so only 80/443 are publicly reachable — Redpanda's 9092 must never be internet-facing

### Schema Registry

- [x] **SCHEMA-01**: Each of the 5 `ActivityEvent` types (`TaskCreatedEvent`, `TaskMovedEvent`, `TaskDeletedEvent`, `BoardCreatedEvent`, `ColumnCreatedEvent`) has an explicit, versioned Avro schema, registered via a build/CI step rather than producer auto-registration
- [x] **SCHEMA-02**: A mapping layer translates between the existing plain `ActivityEvent` sealed records and Avro-generated `SpecificRecord` classes at both the publish and consume boundaries, with zero change to the sealed-interface/exhaustive-switch pattern in application code
- [ ] **SCHEMA-03**: The Kafka producer (`KafkaEventPublisher`) and consumer (`ActivityLogConsumer`/`KafkaConsumerConfig`) use Confluent's Avro serializer/deserializer against Redpanda's built-in, Confluent-API-compatible Schema Registry
- [ ] **SCHEMA-04**: A compatibility mode (BACKWARD or FULL) is explicitly configured for the activity-log topic's schema subject(s) — not left at the registry's out-of-the-box default
- [ ] **SCHEMA-05**: The dead-letter topic's byte-fidelity guarantee is re-verified under Avro — poison messages are dead-lettered via a dedicated raw byte-array serializer (not the Avro-aware one), proven by a new test
- [ ] **SCHEMA-06**: Before cutover, the new schemas are rehearsed against a sample of real historical activity-log data (not just synthetic fixtures) to catch field-default/strictness mismatches Avro's stricter model could introduce

## v2 Requirements

Deferred to a future release. Tracked but not in this milestone's roadmap.

### Infra Polish

- **INFRA-V2-01**: Full observability stack (Prometheus/Grafana + alerting) — disproportionate to actual traffic; `Actuator /health` + a free external uptime pinger is the proportionate choice for now
- **INFRA-V2-02**: True zero-downtime blue-green deploys — resource cost (running two app instances during deploy) outweighs benefit on a single free-tier VM with near-zero concurrent traffic
- **INFRA-V2-03**: Multi-broker Redpanda cluster / true HA — meaningless on a single VM; if the VM dies, a multi-broker cluster on the same box dies with it

### Schema Registry Polish

- **SCHEMA-V2-01**: Pre-merge schema-compatibility CI check (registry `/compatibility` API call before merge) — mirrors the DDL-verification pattern but deferred as polish, not required for the pipeline to function correctly
- **SCHEMA-V2-02**: Documented compatibility-mode rationale (Javadoc-style paragraph explaining the BACKWARD/FULL choice, matching `KafkaEventPublisher`'s existing D-01/D-02 convention) — deferred as documentation polish

## Out of Scope

Explicitly excluded from this milestone. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Generic/pluggable event-schema envelope (any event type without a new schema) | Directly contradicts v1.1's own Out of Scope decision rejecting this for the same 5 event types — loses compile-time safety for no gain |
| Long-lived dual-format (JSON+Avro) parallel topic migration | Overkill for a single producer/consumer under one deploy unit — both sides switch format in the same merge, no independently-deployed second consumer needs a gradual cutover |
| Protobuf instead of Avro | A legitimate alternative, not a wrong choice, but Avro is the pragmatic default given Confluent/Redpanda's Avro-first tooling maturity; neither format has push-button mapping from a Java sealed interface anyway |
| A separately-deployed Confluent Schema Registry container | Redpanda's built-in registry is Confluent-API-compatible and avoids a second service on an already resource-constrained VM |
| Self-hosted CI runner on the Oracle VM | Resource contention with the app/Redpanda plus security exposure on a public box; GitHub-hosted runners are the right call |
| AWS OIDC / any AWS-specific deploy mechanism | The project is off AWS entirely — SSH-key-based deploy via `appleboy/ssh-action` replaces it |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| SCHEMA-01 | Phase 4 | Complete |
| SCHEMA-02 | Phase 4 | Complete |
| SCHEMA-03 | Phase 4 | Pending |
| SCHEMA-04 | Phase 4 | Pending |
| SCHEMA-05 | Phase 4 | Pending |
| SCHEMA-06 | Phase 4 | Pending |
| INFRA-01 | Phase 5 | Pending |
| INFRA-02 | Phase 5 | Pending |
| INFRA-03 | Phase 5 | Pending |
| INFRA-04 | Phase 5 | Pending |
| INFRA-05 | Phase 5 | Pending |
| INFRA-06 | Phase 5 | Pending |
| INFRA-07 | Phase 5 | Pending |
| INFRA-08 | Phase 5 | Pending |

**Coverage:**

- v1 requirements: 14 total
- Mapped to phases: 14 (Phase 4: 6, Phase 5: 8)
- Unmapped: 0 ✓

---
*Requirements defined: 2026-08-03*
*Last updated: 2026-08-04 after roadmap creation (v1.2: Phase 4 Schema Registry, Phase 5 Infra Migration)*
</content>
