# Feature Research

**Domain:** (1) Single-VM production deployment of a small Spring Boot app (reverse proxy/TLS, process supervision, CI/CD) and (2) Schema Registry (Avro/Protobuf) in front of an existing 5-event-type Kafka activity-log pipeline
**Researched:** 2026-08-03
**Confidence:** MEDIUM (web-sourced, cross-checked across multiple independent sources including official Confluent/Redpanda/Oracle/Neon docs where cited; a few items — the JSON→Avro gradual-migration pattern and the sealed-interface→Avro mapping specifics — are LOW confidence, no authoritative source found addressing this project's exact shape)

## Context

This milestone has two independent feature areas bolted onto an already-shipped, already-working system. Nothing here is new application logic — it is entirely infrastructure and serialization-format plumbing around code that already works (5 typed domain events, `KafkaEventPublisher`, `ActivityLogConsumer`, Docker Compose local stack). The research below treats each area separately because they have almost no shared dependency: the deploy migration must land first only insofar as there must *be* a reachable environment before "does the schema registry work end-to-end in prod" is even askable, but the schema registry work itself is orthogonal to Caddy/Oracle Cloud/Neon.

**Existing code confirmed as in-scope for touching (schema registry area):**
- `src/main/resources/application.properties` — `spring.kafka.producer.value-serializer=...JsonSerializer` and `spring.kafka.consumer.properties.spring.deserializer.value.delegate.class=...JsonDeserializer` (plus `spring.json.trusted.packages` / `spring.json.use.type.headers`) are the exact lines that get swapped for Avro/Protobuf equivalents.
- `KafkaEventPublisher` (`config/KafkaEventPublisher.java`) — currently typed `KafkaTemplate<String, Object>`, calls `kafkaTemplate.send(topic, key, event)` where `event` is a plain `ActivityEvent` record. This is the one place that would need a serializer-compatible payload type.
- `ActivityLogConsumer` (`activitylog/ActivityLogConsumer.java`) — currently receives a deserialized `ActivityEvent` directly via Spring Kafka's type-header-driven `JsonDeserializer`; an Avro/Protobuf swap changes what shows up at this method boundary unless a translation layer sits in front of it.
- The 5 sealed-permitted records (`TaskCreatedEvent`, `TaskMovedEvent`, `TaskDeletedEvent`, `BoardCreatedEvent`, `ColumnCreatedEvent`) implementing `ActivityEvent` — none of these map automatically to Avro; Avro's Java tooling is schema-first (`.avsc` + codegen plugin) or reflection-based via an annotation on a base type, not "point it at an existing sealed interface and get a union for free."
- `docker-compose.yml` (local Kafka stack) — gains a schema-registry service (Confluent's separate container, or none at all if Redpanda's built-in registry is chosen, since it ships inside every broker with no extra container).

## Feature Landscape

### Table Stakes (Users/Reviewers Expect These)

For a portfolio project, "users" here means a technically literate reviewer (hiring manager, senior engineer) evaluating whether the deploy and the schema registry are real or decorative.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Public HTTPS endpoint, not bare HTTP or an IP:port | An unencrypted or self-signed-cert API reads as unfinished/toy-grade to any reviewer who checks. TLS is the single most visible signal of "this is deployed for real." | LOW–MEDIUM | Caddy in front of the app container, one `Caddyfile` line (`your-domain { reverse_proxy app:8080 }`) gets automatic Let's Encrypt HTTPS. Requires a real DNS A record pointed at the Oracle VM's public IP first — Caddy cannot provision a cert for a name that doesn't resolve to the running box. |
| App survives a crash/restart without manual intervention | Table stakes for "production" in any meaningful sense — a demo that silently stays down after an OOM or a bad deploy is not production. | LOW | `restart: unless-stopped` on the app and Redpanda services in `docker-compose.yml`. Do not use `restart: always` (can unexpectedly restart something a human deliberately stopped). |
| Automated deploy on merge to master (CI/CD), not manual SSH-and-copy | This project already had this at the AWS stage (`.github/workflows/deploy.yml`); regressing to manual deploys after "redeploying with CI/CD" is explicitly in the milestone goal would be a visible step backward. | LOW–MEDIUM | GitHub Actions job: build/push image (or build on the VM), SSH in, `docker compose pull && docker compose up -d`. Brief request-dropping blip during restart is acceptable for a personal project — see Anti-Features below on why true zero-downtime is over-engineering here. |
| Basic reachability/health monitoring | If the free-tier VM or Neon or Redpanda silently goes down, "it's deployed" becomes false without anyone noticing — undermines the whole point of redeploying. | LOW | A Spring Boot Actuator `/actuator/health` endpoint (if not already present, cheap to add) polled by a free external uptime pinger (UptimeRobot, healthchecks.io free tier) or a simple GitHub Actions scheduled workflow hitting the endpoint. No need for Prometheus/Grafana at this scale. |
| Log rotation on the VM | Docker's default `json-file` log driver has no size cap; an unbounded app + Redpanda log will eventually fill the free-tier VM's disk and take the whole stack down. | LOW | Set `max-size`/`max-file` on the `json-file` log driver in `docker-compose.yml` (a few lines per service), or rely on `logrotate` if logs are also written to files. This is a real, concrete risk on a small always-free VM (limited disk), not decorative. |
| Firewall rules opened correctly for the exposed ports | Oracle Cloud's default security list blocks everything but SSH/22 inbound; if this isn't set up correctly the "public HTTPS endpoint" simply doesn't work, at two separate layers. | LOW–MEDIUM | Two layers to configure, both required: (1) the OCI VCN Security List / Network Security Group (console-level ingress rules for 80/443), and (2) the instance's own OS firewall (`iptables`/`firewalld` on Oracle Linux, `ufw` on Ubuntu) — missing either one silently blocks traffic and is a common gotcha specific to OCI. |
| Explicit, versioned schema registration (not ad-hoc auto-registration) | A schema registry whose schemas are never explicitly registered/reviewed (just auto-created by whatever the producer happens to send) isn't meaningfully different from the current "convention-based agreement" this milestone is trying to fix (SEED-001). | LOW–MEDIUM | Confluent/Redpanda Schema Registry supports producer auto-registration, but it's explicitly discouraged for production — register schemas via a build/CI step instead, so a schema change is a reviewable diff, not a runtime side effect. |
| A compatibility mode is set and enforced, not left at whatever the registry's out-of-the-box default silently is | The entire point of adding a registry is "reject a producer/consumer schema mismatch before it corrupts the topic," not just "store schemas somewhere." Leaving compatibility unset/default defeats the purpose. | LOW | `BACKWARD` is the standard default and the right choice for this project's single-producer/single-consumer, same-deploy-unit shape (see Differentiators/dependency notes below for why). |
| All 5 existing event types are representable in the new schema format without silently dropping any | The producer/consumer already handle exactly 5 event types via an exhaustive sealed-interface `switch` with no `default` arm — a schema-registry migration that "forgets" one event type would be a functional regression, not just a format change. | MEDIUM | Each of `TaskCreatedEvent`/`TaskMovedEvent`/`TaskDeletedEvent`/`BoardCreatedEvent`/`ColumnCreatedEvent` needs an explicit Avro/Protobuf schema (either 5 separate schemas + producer-side dispatch, or one schema with a union/oneof) — this is genuinely the bulk of the schema-registry work, not the registry setup itself. |

### Differentiators (Portfolio/Competitive Value)

Not required for the pipeline to function; these are what make the milestone demonstrate real depth rather than "installed a thing."

| Feature | Value Proposition | Complexity | Notes |
|---------|--------------------|------------|-------|
| Automatic HTTPS via Caddy instead of manual Certbot/Nginx cron jobs | Shows judgment about picking the right-sized tool (Caddy's one-line automatic HTTPS) over the more "enterprise-familiar" but more manually-maintained Nginx+Certbot combo — a good signal of pragmatic engineering rather than defaulting to what's most commonly seen in tutorials. | LOW | Caddy is the recommended default here specifically because this is a single-VM, single-domain, no-exotic-routing-needs deployment — exactly Caddy's sweet spot. Nginx would be defensible too but adds manual cert-renewal maintenance for zero added benefit at this scale. |
| Documented compatibility-mode choice with an explicit rationale (BACKWARD, and why) | Distinguishes "understood the schema-evolution tradeoff" from "copy-pasted the registry quickstart." A reviewer skimming the PR description or code comments who sees *why* BACKWARD was chosen (vs FORWARD or FULL) reads as depth, matching the project's existing convention of documenting non-obvious decisions in Javadoc. | LOW (documentation, not code) | This project's Javadoc convention (see `KafkaEventPublisher`'s D-01/D-02 comments) is exactly the right place for this — one paragraph explaining the choice, same style already established. |
| A real Avro/Protobuf schema per event type registered and versioned in CI, not hand-waved | Demonstrates the actual schema-evolution workflow (register → compatibility-check → version bump) the milestone is meant to close the gap on, rather than just switching serializer class names. | MEDIUM–HIGH | This is the genuine "differentiator" of the whole schema-registry effort — the bulk of the real work and the real narrative value (closing SEED-001's flagged risk) lives here, not in which registry vendor is chosen. |
| Redpanda's built-in Schema Registry instead of a separately-deployed Confluent Schema Registry container | Since this project already chose self-hosted Redpanda over Confluent-branded Kafka for the broker itself, using Redpanda's integrated, Confluent-API-compatible registry (no extra container, no extra port, no extra service to keep alive on an already resource-constrained free-tier VM) is the more consistent and lower-footprint choice — a legitimate architectural decision worth stating explicitly, not just "it happened to be there." | LOW (once Redpanda is already the broker) | Confirmed via Redpanda's own docs: the registry is an integrated component of every broker, storing schemas in an internal compacted topic, and is API-compatible with Confluent's REST API/clients — so `KafkaAvroSerializer`/`KafkaAvroDeserializer` (the standard Confluent Java client classes) work unmodified against it. |
| A pre-merge schema-compatibility check in CI (registry `/compatibility` API call before merge) | Mirrors the project's existing pattern of a pre-merge DDL verification step (already planned for the Postgres migration) — applying the same "verify against the real target before merge" discipline to the new Kafka schema surface would be a nice structural parallel worth calling out, not a separate invention. | LOW–MEDIUM | Natural pairing with the milestone's already-planned "new pre-merge DDL verification step against the new deploy target" — same shape of problem (verify compatibility with a live external system before merge), same solution pattern. |

### Anti-Features (Commonly Suggested, Over-Engineering at This Scale)

Things a "proper production setup" checklist would suggest that are actively the wrong call for a single-VM, always-free-tier, personal-portfolio deployment.

| Feature | Why It Gets Suggested | Why It's Wrong Here | Alternative |
|---------|------------------------|----------------------|-------------|
| True zero-downtime blue-green deploys (spin up new container, health-check, atomically swap proxy target, tear down old) | Standard practice at any company with real traffic and SLAs; every "production deploy" guide mentions it. | This VM has one core-2 (or similar) always-free tier's worth of CPU/RAM, already running app + Postgres client + Redpanda; running two full app instances simultaneously during every deploy risks resource exhaustion on the free tier for a project with effectively zero concurrent users. The "downtime" from a plain `docker compose up -d --force-recreate` is a few seconds of dropped requests on a project nobody is hitting concurrently. | Simple restart-based redeploy (`docker compose pull && up -d`) is the right-sized choice; note the tradeoff explicitly (a few seconds of unavailability during deploy) rather than silently accepting it. |
| Full observability stack (Prometheus + Grafana + alerting) | The "correct" companion to any production Kafka pipeline in enterprise contexts, heavily represented in Kafka/Spring Boot tutorials. | Massive resource and maintenance overhead relative to the actual signal needed ("is it up") on a free-tier VM already hosting three services; would itself become the thing that needs maintaining/monitoring. | Actuator `/health` + a free external uptime pinger is proportionate; this is explicitly a personal-project-scale decision, not a permanent architectural stance. |
| Multi-broker Redpanda cluster for HA/replication | Redpanda's own docs recommend 3+ brokers for production. | A single-node broker on a single VM has no meaningful HA story regardless of broker count — if the VM dies, a 3-broker cluster on the same box dies with it. Multi-broker only makes sense across multiple machines, which contradicts the "single self-managed VM" constraint entirely. | Single-node Redpanda, explicitly documented as the accepted tradeoff (matches Redpanda's own "single broker is for dev/small-scale" framing, applied deliberately here rather than by accident). |
| FULL compatibility mode "to be safe" for the schema registry | Sounds like the strictest/safest choice, and "safest" often gets picked by default when unsure. | FULL is the most restrictive mode (requires both backward AND forward compatibility on every change) and is meant for scenarios where producer/consumer upgrade order can't be controlled — not applicable here, where the producer (`KafkaEventPublisher`) and consumer (`ActivityLogConsumer`) live in the *same deployable* and ship together on every merge. FULL would reject legitimate schema changes (e.g., a field removal without a default) that are perfectly safe in a same-deploy-unit setup. | BACKWARD compatibility — matches this project's actual deployment topology (single artifact, single deploy) and is also required if the activity-log topic is ever replayed from the beginning (a real future scenario given the append-only activity feed). |
| A generic/schema-per-message "envelope" format that can hold any future event type without a new schema | Feels forward-compatible and DRY — "why write 5 schemas when 1 generic one avoids future work." | Directly contradicts this project's own established decision (v1.1's Out of Scope table explicitly rejected "generic/pluggable event schema... loses compile-time safety for no gain at 5 event types") — the same reasoning applies with even more force once a schema registry is enforcing structure. | One schema per event type (or one Avro union referencing all 5), keeping the exhaustive-switch/compile-time-safety property the sealed interface already gives in Java, now enforced at the wire level too. |
| Long-lived dual-topic JSON+Avro migration (indefinite parallel writes to both formats) | The textbook-correct way to migrate a large multi-team system without a flag day. | Overkill for a single producer and single consumer under one deploy — there's no second team or independently-deployed consumer that needs a gradual cutover; both sides change together in one PR/merge. | A coordinated single deploy (producer and consumer both switch serializer format in the same merge), with a short overlap/rollback window if genuinely needed — no long-lived dual-topic infrastructure. |
| Protobuf `oneof`/code-first generation as a way to "cleanly" express the sealed interface | Protobuf's stronger forward-compatibility story and more explicit generated code (cited by teams like ClearStreet as their reason to prefer Protobuf over Avro) is a real, legitimate argument. | For *this* project specifically, Avro is the more natural fit: it's the more common default paired with Confluent/Redpanda Schema Registry tooling in Java/Spring shops, and neither format offers push-button mapping from a Java sealed interface regardless — so there's no clear win from Protobuf's extra tooling investment for 5 event types that don't change often. This is a judgment call, not a hard rule; flag it as one, don't treat it as settled. | Avro, chosen as the pragmatic default given the Confluent/Redpanda ecosystem's Avro-first tooling maturity, not because Protobuf is worse. |

## Feature Dependencies

```
[Public HTTPS endpoint (Caddy + DNS)]
    └──requires──> [Oracle Cloud VM reachable] ──requires──> [OCI security-list + OS firewall rules opened for 80/443]

[GitHub Actions CI/CD deploy]
    └──requires──> [SSH access + secrets configured for the new VM] (old EC2 secrets are dead, need replacing, not reusing)
    └──enhances──> [Automated redeploy on merge] (already existed for AWS; must not regress)

[App + Redpanda restart-on-crash]
    └──requires──> [docker-compose.yml restart policies + healthchecks]

[Neon Postgres swap]
    └──requires──> [Connection string / env var change only] (JPA/Hibernate layer untouched, per PROJECT.md)
    └──conflicts──> [Assuming zero cold-start latency] (scale-to-zero has a real ~300ms-few-second first-query cost; must be an accepted/documented tradeoff, not a surprise)

[Schema Registry (Redpanda built-in or Confluent)]
    └──requires──> [Redpanda broker already running] (if using Redpanda's built-in registry — no separate service)
    └──requires──> [Avro/Protobuf schema authored per event type] ──requires──> [Mapping layer between sealed ActivityEvent records and Avro-generated classes]
                       └──requires──> [avro-maven-plugin/avro-gradle-plugin (or reflection-based @Union) added to build.gradle]

[KafkaEventPublisher serializer swap] ──requires──> [application.properties producer.value-serializer change]
[ActivityLogConsumer deserializer swap] ──requires──> [application.properties consumer delegate.value-deserializer change]
    Both changes are coupled: producer and consumer must switch together in the same deploy (see BACKWARD-compatibility dependency note below) since they are the same deployable.

[Compatibility mode = BACKWARD] ──enhances──> [Safe topic replay from offset 0] (the activity feed is append-only and unbounded; replay is a realistic future need)

[Pre-merge schema-compatibility CI check] ──enhances──> [Schema Registry], mirroring the already-planned [pre-merge DDL verification step]
```

### Dependency Notes

- **Public HTTPS requires the VM be reachable first, which requires both OCI firewall layers opened:** this is a two-step gotcha specific to Oracle Cloud (security list at the VCN level, then OS-level firewall on the instance itself) — missing either one silently breaks the "public HTTPS endpoint" table-stakes item with no obvious error message pointing at the cause.
- **GitHub Actions CI/CD cannot reuse the old EC2 secrets:** `EC2_SSH_KEY`/`EC2_HOST`/`EC2_USER` all pointed at the deleted instance; this milestone needs entirely new GitHub Secrets for the Oracle Cloud VM, not a rename of the old ones — a good place for the roadmap to flag a discrete task ("recreate CI/CD secrets for the new host") so it isn't discovered mid-phase as a blocker.
- **Neon's scale-to-zero conflicts with an assumption of consistently low latency:** the milestone should decide, and document, whether to accept the cold-start hit (free/default behavior) or pay to disable auto-suspend — this is a real tradeoff decision belonging in Key Decisions, not something to leave implicit.
- **The schema-registry migration is coupled producer+consumer, not incremental:** because `KafkaEventPublisher` and `ActivityLogConsumer` are both in the same Spring Boot deployable and always ship together, there is no meaningful "consumer lags behind producer for weeks" scenario to design gradual migration around — this is the concrete reason the "dual-topic gradual migration" anti-feature is over-engineering *for this specific codebase's topology*, even though it is a legitimate pattern in general (e.g., Kafka Streams with independently-deployed multi-team consumers).
- **The Avro/sealed-interface mapping layer is the one place with no ready-made shortcut:** no tooling found (Baeldung, Apache Avro docs) that generates Avro schemas directly from a Java sealed interface + records; expect to hand-author 5 `.avsc` files (or one union schema) and either (a) generate Avro `SpecificRecord` classes via `avro-gradle-plugin` and add an explicit mapper between them and the existing `ActivityEvent` records, or (b) use `@org.apache.avro.reflect.Union` reflection-based serialization directly on the interface. This is a real, non-trivial design decision the roadmap should flag as needing its own research/design pass at phase-planning time, not something to estimate as "just swap the serializer."

## MVP Definition

### Launch With (v1 — this milestone)

Minimum to call the deploy "real" and the schema registry "actually adopted," not decorative.

- [ ] Oracle Cloud VM reachable over public HTTPS via Caddy (DNS + firewall correctly opened at both layers) — without this, nothing else in the milestone is externally verifiable
- [ ] App + Redpanda containers with `restart: unless-stopped` and basic healthchecks — the "doesn't silently stay down" bar
- [ ] Neon Postgres wired via env vars only, scale-to-zero tradeoff explicitly accepted/documented (or explicitly paid to disable, if that's the call)
- [ ] Self-hosted single-node Redpanda replacing local-only Kafka, no producer/consumer/DLQ code changes (per PROJECT.md's stated constraint)
- [ ] GitHub Actions CI/CD redeploying on merge to the new host (new secrets, not reused AWS ones)
- [ ] A new pre-merge DDL verification step against the new Neon target
- [ ] Log rotation configured on the VM (disk-fill risk is real and concrete on a free-tier box)
- [ ] Schema Registry stood up (Redpanda built-in, given Redpanda is already the broker choice) with explicit compatibility mode set to BACKWARD
- [ ] All 5 existing event types (`TaskCreatedEvent`/`TaskMovedEvent`/`TaskDeletedEvent`/`BoardCreatedEvent`/`ColumnCreatedEvent`) have Avro (or Protobuf) schemas registered, and `KafkaEventPublisher`/`ActivityLogConsumer` switched over together in one deploy

### Add After Validation (v1.x)

- [ ] A basic external uptime check (free tier UptimeRobot/healthchecks.io, or a scheduled GitHub Actions ping) — add once the endpoint is stable and worth monitoring, not before
- [ ] Pre-merge schema-compatibility CI check against the live registry — natural follow-on once the registry itself is proven working, mirroring the DDL-check pattern

### Future Consideration (v2+)

- [ ] Full observability stack (Prometheus/Grafana/alerting) — explicitly deferred; disproportionate to this project's actual traffic/stakes
- [ ] Multi-broker Redpanda / true HA — deferred; meaningless on a single VM regardless of broker count
- [ ] True zero-downtime blue-green deploys — deferred; resource cost on the free tier outweighs the benefit for a near-zero-concurrency personal project
- [ ] Long-lived dual-format (JSON+Avro) topic migration tooling — deferred; not applicable given the single-deployable producer/consumer topology

## Feature Prioritization Matrix

| Feature | User/Reviewer Value | Implementation Cost | Priority |
|---------|----------------------|----------------------|----------|
| Public HTTPS via Caddy | HIGH | LOW | P1 |
| Restart-on-crash (docker compose policies) | HIGH | LOW | P1 |
| Neon Postgres swap | HIGH | LOW | P1 |
| Self-hosted Redpanda swap | HIGH | LOW–MEDIUM | P1 |
| GitHub Actions CI/CD to new host | HIGH | MEDIUM | P1 |
| Pre-merge DDL check vs new target | MEDIUM | LOW | P1 |
| Log rotation | MEDIUM | LOW | P1 |
| Schema Registry stood up + BACKWARD mode | HIGH (narrative value) | MEDIUM | P1 |
| Avro/Protobuf schemas for all 5 event types + producer/consumer switch | HIGH (this is the actual differentiator) | MEDIUM–HIGH | P1 |
| External uptime monitoring | MEDIUM | LOW | P2 |
| Pre-merge schema-compatibility CI check | MEDIUM | LOW–MEDIUM | P2 |
| Full observability stack | LOW (at this scale) | HIGH | P3 |
| Multi-broker Redpanda | LOW (at this scale) | HIGH | P3 |
| Zero-downtime blue-green deploys | LOW (at this scale) | HIGH | P3 |

**Priority key:**
- P1: Must have for this milestone to be considered genuinely shipped
- P2: Should have, natural immediate follow-on, low cost to add once P1 is stable
- P3: Explicitly deferred — would be over-engineering relative to this project's actual scale and stakes

## Sources

- [Using Caddy as a Reverse Proxy for Spring Boot Applications - Bomberbot](https://www.bomberbot.com/proxy/using-caddy-as-a-reverse-proxy-for-spring-boot-applications/)
- [Streamlining DevOps: Automatic HTTPS Reverse Proxy with Caddy and Docker Compose](https://earezki.com/ai-news/2026-03-02-automatic-https-reverse-proxy-in-one-docker-compose-caddy-your-app/)
- [Nginx vs Caddy in 2026: Which Reverse Proxy Should You Use?](https://privatedevops.com/articles/nginx-vs-caddy-2026-reverse-proxy-comparison)
- [How to Use Docker Compose restart Policy Options](https://oneuptime.com/blog/post/2026-02-08-how-to-use-docker-compose-restart-policy-options/view)
- [Docker Compose Healthcheck: Setup, Examples & Best Practices - Last9](https://last9.io/blog/docker-compose-health-checks/)
- [Docker Compose Production Deployment: Health Checks, Restart Policies, and Resource Limits](https://eastondev.com/blog/en/posts/dev/20260424-docker-compose-production/)
- [Zero-Downtime Deployments with Docker, Nginx, and GitHub Actions - Medium](https://medium.com/@connect.hashblock/zero-downtime-deployments-with-docker-nginx-and-github-actions-e3769ddac7da)
- [Zero Downtime Deployment with Docker Compose in an OCI VPS using GitHub Actions - DEV Community](https://dev.to/thayto/zero-downtime-deployment-with-docker-compose-in-an-oci-vps-using-github-actions-1fbd)
- [Ways to Secure a Network - Oracle Docs](https://docs.oracle.com/en-us/iaas/Content/Network/Concepts/waystosecure.htm)
- [Always Free Resources - Oracle Docs](https://docs.oracle.com/en-us/iaas/Content/FreeTier/resourceref.htm)
- [Neon Postgres Review: Serverless PostgreSQL That Actually Scales to Zero - Medium](https://medium.com/@philmcc/neon-postgres-review-serverless-postgresql-that-actually-scales-to-zero-ee14d4e109ba)
- [Benchmarking latency in Neon's serverless Postgres - Neon Docs](https://neon.com/docs/guides/benchmarking-latency)
- [What are the best Postgres databases for teams that want to stop paying for idle compute - Neon FAQs](https://neon.com/faqs/best-postgres-databases-reduce-idle-compute-costs)
- [Requirements and Recommendations - Redpanda Self-Managed Docs](https://docs.redpanda.com/current/deploy/deployment-option/self-hosted/manual/production/requirements)
- [Start a Single Redpanda Broker with Redpanda Console in Docker - Redpanda Labs](https://docs.redpanda.com/redpanda-labs/docker-compose/single-broker/)
- [Schema Registry for Confluent Platform - Confluent Docs](https://docs.confluent.io/platform/current/schema-registry/index.html)
- [Apache Avro for Kafka | Serialization, Schema, KafkaAvroSerializer - Confluent Docs](https://docs.confluent.io/platform/current/schema-registry/fundamentals/serdes-develop/serdes-avro.html)
- [Schema Registry 101 - Managing Schemas - Confluent Developer](https://developer.confluent.io/learn-kafka/schema-registry/manage-schemas)
- [Schema Evolution & Compatibility Types | Backward, Forward, Full, Transitive - Confluent Docs](https://docs.confluent.io/platform/current/schema-registry/fundamentals/schema-evolution.html)
- [Schema Registry 101 - Testing Schema Compatibility - Confluent Developer](https://developer.confluent.io/learn-kafka/schema-registry/schema-compatibility/)
- [Schema Evolution: 8 Kafka Best Practices - Conduktor](https://www.conduktor.io/glossary/schema-evolution-best-practices)
- [Producing and consuming Avro messages with Redpanda schema registry - Redpanda](https://www.redpanda.com/blog/produce-consume-apache-avro-tutorial)
- [Redpanda Schema Registry - Redpanda Self-Managed Docs](https://docs.redpanda.com/current/manage/schema-reg/schema-reg-overview/)
- [Which Kafka Schema Registry is Right for Your Architecture in 2026? - AutoMQ Blog](https://www.automq.com/blog/kafka-schema-registry-confluent-aws-glue-redpanda-apicurio-2025)
- [Generate Avro Schema From Certain Java Class - Baeldung](https://www.baeldung.com/java-class-generate-avro-schema)
- [Apache Avro 1.11.1 Getting Started (Java)](https://avro.apache.org/docs/1.11.1/getting-started-java/)
- [Avro vs Protobuf vs JSON Schema: Kafka Serialization Compared (2026) - Conduktor](https://www.conduktor.io/glossary/avro-vs-protobuf-vs-json-schema)
- [Avro vs Protobuf for Kafka Schema Registry: The Real-World Trade-Offs](https://sivaro.in/articles/avro-vs-protobuf-for-kafka-schema-registry-the-real-world/)
- Codebase-derived (this repo): `src/main/java/com/vrudenko/kanban_board/config/KafkaEventPublisher.java`, `src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumer.java`, `src/main/java/com/vrudenko/kanban_board/event/ActivityEvent.java`, `src/main/resources/application.properties` (Kafka serializer/deserializer config), `.planning/codebase/INTEGRATIONS.md`

---
*Feature research for: kanban-board-backend v1.2 (Infra Migration & Schema Registry)*
*Researched: 2026-08-03*
