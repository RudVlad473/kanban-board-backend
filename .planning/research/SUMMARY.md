# Project Research Summary

**Project:** Kanban Board Backend v1.2 — Infra Migration & Schema Registry
**Domain:** Self-hosted infra migration (Oracle Cloud A1 Flex + Redpanda + Neon serverless Postgres + GitHub Actions CI/CD) combined with Kafka Schema Registry (Avro) adoption on an existing Spring Boot 3.5.0 / Java 21 backend
**Researched:** 2026-08-03
**Confidence:** MEDIUM

## Executive Summary

This milestone bolts two largely independent workstreams onto an already-shipped, working system: (1) replacing the deleted AWS EC2/RDS deploy target with a free-tier Oracle Cloud A1 Flex VM running self-hosted Redpanda and Neon serverless Postgres, redeployed via GitHub Actions, and (2) introducing a Kafka Schema Registry (Avro) in front of the existing 5-event-type activity-log pipeline, closing the "convention-based agreement" risk flagged in v1.1. Neither area requires new application frameworks or touches the controller/service/repository stack; Redpanda is Kafka-wire-protocol-compatible (zero producer/consumer code changes) and Neon is wire-compatible Postgres (zero JPA/Hibernate code changes). The real work is concentrated in configuration, deployment topology, and — for the schema registry specifically — a genuinely new mapping layer between the project's deliberately plain `ActivityEvent` sealed records and Avro's schema-first, codegen-based serialization model.

The recommended approach is Schema Registry first, Infra Migration second, because ~95% of the schema-registry work (5 `.avsc` schemas, Gradle Avro codegen, producer/consumer mapping layers, DLT-under-Avro re-verification) can be built and fully verified against the existing local docker-compose stack with no dependency on the OCI VM, Neon, or GitHub Actions changes existing yet. This ordering also cleanly separates an app-logic-adjacent phase (new mapping code, real architectural decisions about domain-event vs. wire-format boundaries) from a pure-ops phase (VM provisioning, Redpanda compose swap, Neon connection config, CI/CD retargeting), matching the project's existing one-epic-per-PR review discipline.

The two biggest risk clusters are resource contention on a shrunk, shared 2 OCPU/12 GB VM (Redpanda's default memory/CPU auto-detection assumes it owns the whole box, and will starve the co-resident JVM app if left untuned) and correctness regressions specific to the Avro cutover (the DLT's proven JSON byte-fidelity guarantee does not automatically carry over to Avro poison messages, and Avro's strict field-default model can silently break implicit JSON permissiveness the 5 event types currently rely on). Both are addressable with known, documented mitigations (explicit `--overprovisioned`/`--memory`/`--smp` caps; a byte-array serializer dedicated to the DLT path; per-field classification against real historical event shapes) — the risk is in forgetting to apply them under the "no code changes needed" framing, not in the mitigations being unknown.

## Key Findings

### Recommended Stack

The stack additions are narrowly scoped: Oracle Cloud A1 Flex (2 OCPU/12 GB Always Free, post-June-2026 halving) replaces the deleted EC2 instance; Redpanda v26.2.x (Kafka-wire-compatible, ships Schema Registry built into the broker binary) replaces `apache/kafka-native` in the deploy target only (local dev can keep the existing image); Neon serverless Postgres (wire-compatible, scale-to-zero) replaces RDS; Confluent's `kafka-avro-serializer` + Apache Avro + `gradle-avro-plugin` provide Avro schema authoring, codegen, and (de)serialization against Redpanda's Confluent-API-compatible built-in registry. Caddy is recommended over Nginx for reverse-proxy/auto-TLS given the single-domain, single-service scale. Everything already validated in v1.1 (Spring Boot 3.5.0, Java 21, JPA/Hibernate, Spring Security/Session, MapStruct, springdoc-openapi, Lombok, ULID Creator, Vavr, Guava, REST Assured, H2) is explicitly unchanged.

**Core technologies:**
- Oracle Cloud A1 Flex VM (2 OCPU/12 GB) — compute host — only remaining zero-cost ARM compute tier after Oracle's undocumented free-tier halving; must be re-verified against the actual tenancy before finalizing resource budgets
- Redpanda v26.2.x — Kafka broker + built-in Schema Registry — wire-compatible with existing spring-kafka code, avoids standing up a separate registry container on a resource-constrained VM
- Neon serverless Postgres — production DB — wire-compatible Postgres, only connection string/SSL config changes, not JPA/Hibernate code
- Confluent `kafka-avro-serializer` + Apache Avro + `gradle-avro-plugin` — schema definition, codegen, Avro (de)serialization — works unmodified against Redpanda's Confluent-API-compatible registry
- Caddy — reverse proxy + automatic HTTPS — right-sized for a single-VM, single-domain deployment versus Nginx+Certbot's manual cert-renewal maintenance

### Expected Features

For a portfolio-reviewer audience, "done" means externally verifiable: real HTTPS (not bare HTTP/IP), automated CI/CD redeploy (not manual SSH), basic reachability monitoring, and — for the schema registry specifically — explicitly registered/versioned schemas with a deliberately chosen (not default) compatibility mode covering all 5 existing event types without silently dropping any.

**Must have (table stakes):**
- Public HTTPS endpoint via Caddy with correct DNS + both OCI firewall layers opened
- App + Redpanda `restart: unless-stopped` with basic healthchecks
- Automated GitHub Actions redeploy on merge (new secrets, not reused AWS ones)
- Log rotation on the VM (unbounded logs will fill the free-tier disk)
- Explicit, versioned schema registration with an enforced (not default) compatibility mode
- All 5 existing event types represented in Avro without silently dropping any

**Should have (competitive/differentiator):**
- Documented compatibility-mode rationale (why BACKWARD or FULL, not just "the default")
- Redpanda's built-in Schema Registry instead of a separate Confluent container (lower footprint, consistent with the self-hosted-Redpanda decision)
- A pre-merge schema-compatibility CI check, mirroring the already-planned pre-merge DDL verification step

**Defer (v2+):**
- Full observability stack (Prometheus/Grafana) — disproportionate to actual traffic
- Multi-broker Redpanda / true HA — meaningless on a single VM
- True zero-downtime blue-green deploys — resource cost outweighs benefit at this scale
- Long-lived dual-format (JSON+Avro) topic migration tooling — not applicable given the single-deployable producer/consumer topology

### Architecture Approach

The controller → service → repository → entity stack is entirely unchanged. The three architectural deltas are: the Postgres datasource moves off-box and gains a TLS/cold-start dimension (Neon), the Kafka broker becomes Redpanda in the deploy target only (different docker-compose configuration surface — `KAFKA_*` env vars vs. `rpk`/`redpanda start` CLI flags, not a like-for-like swap), and a Schema Registry client threads into the existing `KafkaEventPublisher`/`KafkaConsumerConfig`/`ActivityLogConsumer` touchpoints via configuration only — except for one genuinely new piece of code: a mapping layer between the plain, dependency-free `ActivityEvent` sealed records and Avro-generated `SpecificRecord` classes, needed because Avro's Java tooling has no push-button path from a Java sealed interface to a schema.

**Major components:**
1. New Avro schema files (5 `.avsc`, one per event type) + Gradle-generated Java classes — new, checked into `build/generated` like MapStruct output
2. Mapping layer (Avro DTO ↔ domain `ActivityEvent`) at the publish and consume boundaries — new code, the single largest net-new application-code piece in this milestone
3. `docker-compose.yml` Redpanda service block — replaced wholesale (different config vocabulary from `apache/kafka-native`), not edited in place
4. `application.properties` datasource block — modified (explicit `sslmode=require`, Hikari pool re-derived for Neon's pooled topology)
5. GitHub Actions `deploy.yml` — modified (new secrets for OCI VM, new pre-merge DDL verification step against Neon)

### Critical Pitfalls

1. **Redpanda's default memory/CPU auto-detection assumes it owns the whole VM** — set `--overprovisioned` and explicit `--memory`/`--smp` caps plus cgroup limits on both containers before first deploy; un-tuned defaults will OOM-kill the co-resident JVM app under real traffic, not just at scale.
2. **HikariCP tuned for always-on RDS breaks against Neon's scale-to-zero cold start** — use Neon's pooled (`-pooler`) connection string for the app's runtime datasource, widen `connectionTimeout` with real margin, and reserve the direct connection string only for the DDL-verification step.
3. **The DLT's proven JSON byte-fidelity guarantee does not automatically carry over to Avro** — re-using the main pipeline's Avro-aware serializer for the DLT-publishing path will itself throw on poison messages; configure `DeadLetterPublishingRecoverer` with a raw byte-array serializer and write a new Avro-specific poison-message test.
4. **Avro's strict field-default model can silently break implicit JSON permissiveness** — classify every field of all 5 event types (required-with-no-default vs. optional-with-explicit-default) against real historical event shapes before writing schemas, rather than mechanically converting the Java record field list.
5. **OCI's three additive network layers (Security List + NSG + OS firewall) mean "I opened the port" doesn't guarantee reachability or safety** — audit all three layers together and verify externally (port scan/curl from outside), especially to ensure Redpanda's 9092 listener is never publicly reachable.

## Implications for Roadmap

Based on research, suggested phase structure:

### Phase 1: Schema Registry (local dev only)
**Rationale:** ~95% of this work can be built and fully verified against the existing local docker-compose stack with zero dependency on the OCI VM, Neon, or GitHub Actions — no reason to block it on infra provisioning, and it's the more code-heavy, more interesting/reviewable phase.
**Delivers:** 5 `.avsc` schemas + Gradle Avro codegen wiring, producer-side and consumer-side mapping layers (Avro DTO ↔ `ActivityEvent`), updated serializer/deserializer config, compatibility mode explicitly chosen and documented per subject, DLT re-verified with a raw byte-array serializer and a new Avro-poison-message test, historical-data compatibility rehearsal (sample real topic data through the new schemas before any cutover).
**Addresses:** Explicit versioned schema registration, enforced compatibility mode, all 5 event types represented without loss (table stakes); documented compatibility rationale, Redpanda-built-in-registry framing (differentiators).
**Avoids:** Pitfalls 9 (unexamined default compatibility mode), 10 (Avro strict-field-default breakage), 11 (DLT byte-fidelity regression), 12 (hard cutover against real historical data without rehearsal).

### Phase 2: Infra Migration (Oracle Cloud + Redpanda + Neon + CI/CD)
**Rationale:** Pure ops/infrastructure work, independent of the schema-registry app-logic changes; landing it second avoids conflating an app-logic-adjacent phase with a pure-ops phase in one PR, matching the project's one-epic-per-PR discipline. The only schema-registry task that lands here is the last-mile cutover: repoint `schema.registry.url` from the local/standalone registry to Redpanda's built-in registry on the OCI VM and re-run Phase 1's verification suite against the real target.
**Delivers:** Oracle Cloud VM provisioned (with tenancy resource shape re-verified in-console first), Redpanda docker-compose service block authored with explicit `--overprovisioned`/`--memory`/`--smp` caps, Neon wired via pooled connection string with re-derived HikariCP sizing and `sslmode=require`, Caddy reverse proxy with automatic HTTPS, GitHub Actions retargeted with locally-generated SSH keys and pinned `known_hosts`, new pre-merge DDL verification step against Neon's direct connection string, log rotation configured, restart policies and healthchecks on app + Redpanda, OCI Security List + NSG + OS firewall audited and externally verified.
**Uses:** Oracle Cloud A1 Flex, Redpanda v26.2.x, Neon serverless Postgres, Caddy, GitHub Actions (`appleboy/ssh-action`, `docker/build-push-action`).
**Implements:** Replaced `docker-compose.yml` Redpanda service block; modified `application.properties` datasource block; modified `deploy.yml`.

### Phase Ordering Rationale

- Schema Registry work has zero dependency on the new deploy target and is the higher-risk, more code-heavy area (new mapping layer, real architectural decision) — sequencing it first means it gets full attention and isolated review before infra-provisioning noise is introduced.
- Infra Migration is entirely ops/config and benefits from Schema Registry being already proven locally, so the only cross-phase task is a narrow, well-scoped cutover step (repoint one URL, re-run one verification suite).
- This ordering directly avoids Pitfall 12's highest-cost failure mode (hard cutover against already-shipped historical data without rehearsal) by forcing the historical-data rehearsal to happen against the stable local stack before any production deploy-target risk is introduced.
- Both phases share a "config change, not code change" temptation (per PROJECT.md's framing) that research flags repeatedly as a place real work gets skipped — each phase's plan should explicitly call out the config-only changes that are nonetheless mandatory (Neon SSL/pool sizing, Redpanda resource caps, DLT serializer).

### Research Flags

Phases likely needing deeper research during planning:
- **Phase 1 (Schema Registry):** The Avro/sealed-interface mapping layer has no ready-made tooling shortcut (confirmed — no Baeldung/Apache Avro doc generates Avro schemas directly from a Java sealed interface + records); this design decision (mapper vs. `@Union` reflection-based serialization) needs its own research/design pass at phase-planning time.
- **Phase 2 (Infra Migration):** Redpanda resource budgeting on the actual (possibly-changed) OCI tenancy shape needs to be verified against the real, current allocation before finalizing `--memory`/`--smp` values — do not plan purely against the publicly-reported 2 OCPU/12 GB figure without an in-console check first.

Phases with standard patterns (skip research-phase):
- **Phase 2 (Infra Migration) — Caddy/GitHub Actions/SSH deploy mechanics specifically:** Well-documented, standard patterns (official docs for `appleboy/ssh-action`, Caddy auto-TLS, Docker restart policies) with no project-specific ambiguity once the security hardening steps (locally-generated keys, pinned `known_hosts`) are applied.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | MEDIUM-HIGH | Official docs confirmed for Redpanda/Neon/Confluent client mechanics; Oracle's free-tier limit change is confirmed by multiple independent outlets but never officially published by Oracle |
| Features | MEDIUM | Cross-checked across official Confluent/Redpanda/Oracle/Neon docs; the JSON→Avro gradual-migration pattern and sealed-interface→Avro mapping specifics are LOW confidence — no authoritative source addresses this project's exact shape |
| Architecture | MEDIUM | Cross-checked against Neon/Redpanda/Spring Kafka official docs and multiple independent sources; no first-party benchmark was run against the actual OCI A1 Flex target |
| Pitfalls | LOW-MEDIUM | General web sources plus official vendor docs, not independently cross-verified per individual claim; the Oracle free-tier reduction is corroborated across three independent tech-press sources plus a primary user report, raising that specific claim to MEDIUM |

**Overall confidence:** MEDIUM

### Gaps to Address

- **Oracle A1 Flex tenancy shape:** Confirm the actual current OCPU/RAM allocation for this specific tenancy in-console before finalizing Redpanda resource budgets — the publicly reported 2 OCPU/12 GB figure may not apply uniformly (some tenancies may be grandfathered, others may still be affected retroactively).
- **Avro mapping-layer design:** No existing tooling or pattern found for mapping a Java sealed interface + records directly to Avro; the choice between hand-authored mapper classes (MapStruct-style, matching existing Entity↔DTO convention) vs. `@org.apache.avro.reflect.Union` reflection-based serialization needs its own design pass at Phase 1 planning time.
- **Exact Confluent serializer patch version:** `io.confluent:kafka-avro-serializer` should be re-verified against Confluent's published interoperability matrix at merge time (the ~7.7.x/7.8.x line is a best estimate, not a locked version, given Confluent ships new patches frequently).
- **Confluent client vs. Redpanda registry edge cases:** Two open GitHub issues note incompatibilities between Confluent's Java client and Redpanda's Schema Registry for Protobuf schemas with map fields and Avro namespace-tag handling — not directly relevant since this project uses Avro without map-field Protobuf, but worth a smoke test against the real Redpanda registry (not just Confluent's) before committing.
- **Compatibility mode decision (BACKWARD vs. FULL):** FEATURES.md recommends BACKWARD as the standard default matching this project's single-deployable topology; PITFALLS.md argues FULL may actually be safer/affordable given producer and consumer are the same app process redeployed together. This is a real, unresolved tension between the two research files — Phase 1 planning must make and document an explicit choice rather than defaulting to either recommendation unreviewed.

## Sources

### Primary (HIGH confidence)
- Redpanda Requirements and Recommendations, Sizing Guidelines, Schema Registry overview (official docs, docs.redpanda.com) — broker/registry mechanics, ARM64 support, sizing
- Neon: Connection pooling, Choosing your connection method, Connect securely, Connection latency (official docs, neon.com/docs) — pooled vs. direct connection guidance, cold-start latency numbers, SSL requirements
- Confluent: Schema Evolution & Compatibility Types, Apache Avro for Kafka serdes (official docs, docs.confluent.io) — compatibility modes, Avro serdes mechanics
- Oracle Cloud Free Tier, Always Free Resources, Security Lists, Network Security Groups (official docs, docs.oracle.com) — networking model, free-tier terms
- davidmc24/gradle-avro-plugin, appleboy/ssh-action (official GitHub repos) — build tooling, CI/CD deploy mechanics
- GitHub Docs: Managing deploy keys, Using secrets in GitHub Actions — SSH deploy key hygiene

### Secondary (MEDIUM confidence)
- InfoQ, heise online: Oracle Quietly Halves Free Tier Ampere A1 Compute Limits — cross-corroborated Oracle free-tier reduction (June 2026, undocumented by Oracle itself)
- Redpanda GitHub issues #5771, #11912; TSB-2025-18 (official support advisory) — Confluent client vs. Redpanda registry edge cases (Protobuf map fields, Avro namespace tags)
- Redpanda blog: Solving OOM Killer events, Need for speed performance tips — resource-tuning vendor guidance

### Tertiary (LOW confidence)
- TerminalBytes: Oracle Cloud free tier 2026 changes — specific 4→2 OCPU / 24→12 GB numbers, cross-checked against tech press but not vendor-confirmed
- Community setup guides for Oracle Cloud + Docker networking, HikariCP tuning blogs, Java virtual-threads pinning writeups — individually LOW confidence, used only where independently converged on the same finding (e.g., the two-layer OCI firewall gotcha)
- Baeldung "Generate Avro Schema From Certain Java Class" — confirms no tooling generates Avro schemas directly from a sealed interface; needs validation during Phase 1 design

---
*Research completed: 2026-08-03*
*Ready for roadmap: yes*
