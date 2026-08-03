# Stack Research

**Domain:** Infra migration (Oracle Cloud A1 Flex + self-hosted Redpanda + Neon serverless Postgres + GitHub Actions CI/CD) and Kafka Schema Registry (Avro) added to an existing Spring Boot 3.5.0 / Java 21 backend
**Researched:** 2026-08-03
**Confidence:** MEDIUM-HIGH (official docs confirmed for Redpanda/Neon/Confluent client mechanics; Oracle's free-tier limit change is confirmed by multiple independent outlets but Oracle itself never published a changelog, so treat the exact numbers as "current best evidence, cross-checked," not vendor-guaranteed)

This file covers ONLY the NEW additions needed for the v1.2 infra-migration + schema-registry milestone. It supersedes the prior v1.1-scoped content that lived in this file (Kafka producer/consumer foundation — spring-kafka, Testcontainers, `apache/kafka-native` for local dev), which is preserved in git history and remains valid/unchanged; that stack is NOT re-researched here. Also out of scope (already validated, no changes): Spring Boot 3.5.0, Java 21, Spring Data JPA/Hibernate, Spring Security + Spring Session JDBC, `@Version` optimistic locking, MapStruct, springdoc-openapi, Lombok, ULID Creator, Vavr, Guava, REST Assured, H2.

## Recommended Stack

### Core Technologies

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| Oracle Cloud VM.Standard.A1.Flex | 2 OCPU / 12 GB RAM (Always Free, post-June-15-2026 halving) | Compute host replacing the deleted AWS EC2 instance | Only remaining zero-cost ARM compute tier after Oracle silently cut the prior 4 OCPU/24 GB allocation in half; confirmed current by InfoQ, Linuxiac, heise.de, and TerminalBytes independently (Oracle itself published no changelog/announcement — documentation was simply updated, and some users report existing instances being reclaimed without notice). New signups for Always Free remain open as of this research date. |
| Ubuntu 22.04 LTS (or newer LTS) | latest point release | VM OS image | Default `ubuntu` user (vs. Oracle Linux's `opc`), and the broadest community documentation for Docker-on-OCI setup specifically for this pairing |
| Docker Engine + Compose v2 plugin (installed from Docker's official apt repo, NOT Ubuntu's `docker.io` package) | current stable | Container runtime, matches the existing `docker-compose.yml` conventions | Ubuntu's distro-packaged `docker.io` is older and lacks the `docker compose` v2 subcommand this project's tooling assumes; Docker's own apt repo must be added |
| Redpanda | v26.2.x (latest stable line, released July 2026, supported to July 2027) | Kafka-protocol-compatible broker replacing local-only `apache/kafka-native` for the deploy target | Kafka-wire-protocol compatible — the existing spring-kafka producer/consumer/DLQ code needs zero changes. Ships Schema Registry, HTTP proxy, and admin API built into the same broker binary, so the schema-registry goal needs no extra service to stand up |
| Neon serverless Postgres | current Neon platform (Postgres 16/17-compatible) | Production DB replacing the deleted RDS/EC2-hosted Postgres | Scale-to-zero fits a near-zero-cost personal project; the standard `org.postgresql:postgresql` JDBC driver already in `build.gradle` plus Spring Data JPA/Hibernate work completely unmodified — Neon is wire-compatible Postgres, not a proprietary API |
| Confluent `kafka-avro-serializer` + Apache Avro + `gradle-avro-plugin` | serializer in the ~7.7.x/7.8.x line (tracks Confluent Platform; verify exact patch against Confluent's published interoperability matrix at merge time); `org.apache.avro:avro` latest 1.12.x; plugin `com.github.davidmc24.gradle.plugin.avro` ~1.9.1 | Avro schema definition, codegen, and wire-format (de)serialization against a Confluent-API-compatible schema registry | Redpanda's built-in Schema Registry is explicitly documented as API-compatible with Confluent's Schema Registry — Confluent's own serializer/deserializer classes work against it with zero code changes, so there is no reason to introduce a separate, non-Confluent client library |

### Supporting Libraries

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Caddy | current stable (v2.x) | Reverse proxy + automatic HTTPS (Let's Encrypt) in front of the app on the Oracle VM | Use instead of Nginx for this project — a portfolio/personal deployment doesn't need Nginx's advanced routing, and Caddy's one-line-per-domain Caddyfile with zero-config auto-TLS is meaningfully less operational surface for a single-service VM |
| `appleboy/ssh-action` (GitHub Action) | latest | Executes remote SSH commands (`docker compose pull && up`) from a GitHub Actions job | Deploy step of the CI/CD pipeline — see GitHub Actions notes below |
| `docker/build-push-action` + `docker/login-action` (GitHub Actions) | latest | Build the existing `Dockerfile` image and push it to GHCR | Build step; GHCR auth uses the auto-issued `GITHUB_TOKEN`, no manual PAT needed for same-repo pushes |
| Redpanda Console (`redpandadata/console`) | current stable | Optional web UI for browsing topics/schemas | Nice-to-have only — see "Stack Patterns by Variant" for when to skip it given the tight 12 GB budget |

## Installation

### Gradle additions (`build.gradle`)

```gradle
plugins {
    id 'com.github.davidmc24.gradle.plugin.avro' version '1.9.1'
}

repositories {
    mavenCentral()
    maven {
        url 'https://packages.confluent.io/maven/'
    }
}

dependencies {
    implementation 'org.apache.avro:avro:1.12.0'
    // Verify exact patch against Confluent's published Kafka-client interoperability
    // matrix before merge -- Spring Boot 3.5.0 manages kafka-clients 3.8.1 via
    // spring-kafka 3.3.x, which lines up with the Confluent Platform ~7.7.x/7.8.x line.
    implementation 'io.confluent:kafka-avro-serializer:7.7.1'
}
```

`.avsc` schema files go in `src/main/avro/`; the plugin generates Java classes into `build/generated-main-avro-java` at compile time. Extend this project's existing ErrorProne `excludedPaths` regex (currently scoped to MapStruct's `build/generated/**` output — see `build.gradle`) to also cover the Avro codegen output, since neither is hand-written code.

### Deploy-target `docker-compose.yml` changes (conceptual — the full compose file gets authored during phase planning, not here)

```yaml
services:
  redpanda:
    image: docker.redpanda.com/redpandadata/redpanda:v26.2.x   # pin exact patch, do not float :latest
    command:
      - redpanda start
      - --smp 1
      - --memory 1G
      - --overprovisioned          # required: broker does not get dedicated cores on this shared VM
      - --schema-registry-addr 0.0.0.0:8081
      - --kafka-addr internal://0.0.0.0:9092,external://0.0.0.0:19092
      - --advertise-kafka-addr internal://redpanda:9092,external://<VM_PUBLIC_HOST>:19092
    restart: unless-stopped
    volumes:
      - redpanda-data:/var/lib/redpanda/data   # same named-volume pattern as the existing kafka-data volume
```

No Postgres container is needed in the deploy target — Neon replaces it entirely; only the app's `DB_HOST`/JDBC URL and credentials change to point at Neon.

## Alternatives Considered

| Recommended | Alternative | When to Use Alternative |
|-------------|-------------|--------------------------|
| Redpanda (self-hosted, single-node) | Confluent Cloud / Aiven Kafka (managed) | If the "self-hosted" constraint is dropped and a small recurring cost is acceptable — removes broker-ops burden entirely, but this milestone's stated goal is a cost-guarded self-hosted stack |
| Direct (unpooled) Neon connection string with HikariCP | Pooled (`-pooler`) Neon connection string | Only if running many short-lived app instances/serverless functions each opening fresh connections — not this project's shape (one long-running Spring Boot process already pooling via HikariCP) |
| Confluent `kafka-avro-serializer` against Redpanda's built-in registry | Apicurio Registry + its Confluent-compatible Java Serde | Only if pluggable storage/multi-format governance beyond this project's needs mattered, or avoiding Confluent's non-Maven-Central repository was a hard requirement — not a real constraint here |
| Avro (schema files + codegen) | Protobuf (`.proto` + protoc) | If cross-language consumers were planned, or minimizing the Gradle-plugin surface mattered more than schema readability — this project is Java-only, and Avro's schema-first workflow maps closely onto the existing sealed `ActivityEvent` record pattern |
| GitHub Actions: GitHub-hosted runner builds, SSH-pushes/pulls to the VM | Self-hosted GitHub Actions runner installed on the Oracle VM itself | Rejected here: doubles resource contention on an already resource-constrained 12 GB VM, and runs arbitrary workflow code directly on a public-facing box — worse security posture for a personal project with no other reason to need it |
| Caddy reverse proxy | Nginx | If finer-grained routing/rewrite rules become necessary later — not needed for a single backend service today |

## What NOT to Use

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| Running `apache/kafka-native` alongside or instead of Redpanda in the deploy target | The whole point of this milestone is replacing it there; keeping both wastes the already-scarce 12 GB budget | Redpanda only, in the deploy target compose file (local dev compose can keep `apache/kafka-native` if desired — it's unaffected by this milestone) |
| Floating `:latest` tags for Redpanda or the app's GHCR image in the deploy compose file | Non-reproducible deploys; a broker version bump could silently change behavior on the next `docker compose pull` | Pin exact version tags/digests — consistent with this project's existing pinning discipline (ErrorProne, `apache/kafka-native:4.3.1`) |
| Neon's pooled (`-pooler`) connection string with HikariCP | PgBouncer transaction-mode pooling underneath the pooled endpoint doesn't reliably support session-level features/prepared-statement lifecycles; stacking two pool layers (HikariCP + PgBouncer) for a single always-on process adds risk with no benefit | Neon's direct/unpooled connection string, with a tuned (small) HikariCP pool size |
| AWS OIDC / IAM role-assumption patterns in the GitHub Actions workflow | Not applicable — Oracle Cloud is not AWS; there is no equivalent identity federation in play | A plain SSH key stored as a GitHub Actions repo secret, used via `appleboy/ssh-action` |
| Assuming the OCI Security List alone opens external access | Oracle's Ubuntu images additionally ship a default-deny VM-level `iptables` ruleset (via `netfilter-persistent`/oci-utils) that still blocks inbound traffic even after the cloud-level Security List/NSG is opened — the single most commonly hit gotcha across every Oracle-Cloud-Docker setup guide surveyed | Open ports at BOTH layers: the OCI Security List/NSG ingress rules (TCP 22/80/443, `0.0.0.0/0`) AND the VM's own `iptables`/`ufw` rules |
| Treating Oracle's Always Free A1 allocation as contractually guaranteed to stay at 2 OCPU/12 GB | Oracle changed this once already (4 OCPU/24 GB → 2 OCPU/12 GB, June 2026) with zero announcement; some users reported instances reclaimed/disabled without warning | Design the deploy footprint with headroom margin, and treat any further reduction as a known operational risk to monitor going forward, not something to architect defensively around right now |

## Stack Patterns by Variant

**If RAM headroom is tight after app + Redpanda + Neon-client overhead on the 12 GB VM:**
- Skip Redpanda Console (the optional web UI, `redpandadata/console`) — it is not required for the schema-registry or Kafka pipeline to function, only for humans browsing topics/schemas
- Because Console adds roughly 200-300 MB RAM on top of an already resource-constrained shared VM, and `rpk` (Redpanda's CLI, bundled in the broker image) covers the same inspection needs from an SSH session

**If the schema-registry migration needs to be gradual (avoid a hard cutover on the 5 `ActivityEvent` types):**
- Register Avro schemas and switch the producer first, but keep the consumer able to handle both JSON (in-flight/older messages) and Avro (new messages) during a transition window
- Because a hard flag-day cutover risks the DLQ or dedup consumer choking on a wire format it wasn't built to parse if any messages are still in-flight during the deploy

## Version Compatibility

| Package A | Compatible With | Notes |
|-----------|------------------|-------|
| Spring Boot 3.5.0 → spring-kafka 3.3.x | kafka-clients 3.8.1 (managed) | Confirmed via Spring for Apache Kafka's own compatibility notes; this project should NOT need to override Boot's managed kafka-clients version for Redpanda compatibility, since Redpanda targets standard Kafka wire-protocol versions |
| `io.confluent:kafka-avro-serializer` ~7.7.x/7.8.x | kafka-clients ~3.7-3.8 | Roughly aligns with Spring Boot 3.5.0's managed kafka-clients 3.8.1; re-verify the exact patch against Confluent's published interoperability matrix (`docs.confluent.io/platform/current/installation/versions-interoperability.html`) when the schema-registry phase is actually planned, since Confluent ships new patches frequently |
| Redpanda v26.2.x Schema Registry | Confluent Schema Registry REST API (wire-compatible) | Documented directly by Redpanda — no Redpanda-specific client library needed; Confluent's `KafkaAvroSerializer`/`KafkaAvroDeserializer` work unmodified |
| Neon Postgres | `org.postgresql:postgresql` driver (already in `build.gradle`), Spring Data JPA/Hibernate (unchanged) | Standard Postgres wire protocol; only the JDBC URL/credentials change, plus a mandatory `sslmode=require` |

## Sources

- [Redpanda Requirements and Recommendations (official docs)](https://docs.redpanda.com/current/deploy/deployment-option/self-hosted/manual/production/requirements) — HIGH, official
- [Start a Single Redpanda Broker with Redpanda Console in Docker — Redpanda Labs (official)](https://docs.redpanda.com/labs/docker-compose/single-broker/) — HIGH
- [Redpanda Schema Registry overview (official docs)](https://docs.redpanda.com/current/manage/schema-reg/schema-reg-overview/) — HIGH, confirms API-compatibility with Confluent clients
- [Redpanda Release Notes (official)](https://docs.redpanda.com/current/reference/releases/) — HIGH, confirms v26.2 is current stable as of July 2026
- [Neon: Connection pooling (official docs)](https://neon.com/docs/connect/connection-pooling) — HIGH
- [Neon: Choosing your connection method (official docs)](https://neon.com/docs/connect/choose-connection) — HIGH, source for pooled-vs-direct/HikariCP guidance
- [Neon: Connect securely / SSL requirements (official docs)](https://neon.com/docs/connect/connect-securely) — HIGH
- [Neon: Connection latency and timeouts (official docs)](https://neon.com/docs/connect/connection-latency) — HIGH, source for the sub-second cold-start claim
- [Confluent: Schema Evolution & Compatibility Types (official docs)](https://docs.confluent.io/platform/current/schema-registry/fundamentals/schema-evolution.html) — HIGH
- [Confluent: Apache Avro for Kafka serdes (official docs)](https://docs.confluent.io/platform/current/schema-registry/fundamentals/serdes-develop/serdes-avro.html) — HIGH
- [davidmc24/gradle-avro-plugin (official GitHub repo)](https://github.com/davidmc24/gradle-avro-plugin) — HIGH
- [appleboy/ssh-action (official GitHub Action repo)](https://github.com/appleboy/ssh-action) — HIGH
- [Oracle Cloud Free Tier official page](https://www.oracle.com/cloud/free/) — HIGH, confirms Always Free is still open for new signups
- [Redpanda: Produce and consume Avro Messages with Redpanda schema registry (official blog)](https://www.redpanda.com/blog/produce-consume-apache-avro-tutorial) — HIGH
- [InfoQ: Oracle Quietly Halves Free Tier Ampere A1 Compute Limits](https://www.infoq.com/news/2026/07/oracle-cloud-free-tier-limits/) — MEDIUM (reputable tech press, independently corroborated)
- [Linuxiac: Oracle Quietly Cuts Free Tier Ampere A1 Resources in Half](https://linuxiac.com/oracle-quietly-cuts-free-tier-ampere-a1-resources-in-half/) — MEDIUM, corroborating
- [heise online: Oracle halves free cloud resources](https://www.heise.de/en/news/Oracle-halves-free-cloud-resources-11334516.html) — MEDIUM, corroborating
- [TerminalBytes: Oracle Cloud free tier 2026 changes](https://terminalbytes.com/oracle-cloud-free-tier-changes-2026/) — the specific 4→2 OCPU / 24→12 GB numbers, independent blog but cross-checked against the three sources above — treated as verified via cross-checking, not a single-source claim
- [AutoMQ: Which Kafka Schema Registry is Right for Your Architecture in 2026?](https://www.automq.com/blog/kafka-schema-registry-confluent-aws-glue-redpanda-apicurio-2025) — MEDIUM (vendor blog, used only for Apicurio-as-alternative framing)
- Community setup guides for Oracle Cloud + Docker networking (oneuptime.com, syncbricks.com, angelosantarella.gitlab.io) — LOW-MEDIUM individually, but converged independently on the same "Security List + VM-level iptables both required" finding, which raises confidence in that specific claim despite no single official Oracle doc stating it plainly

---
*Stack research for: Kanban Board Backend v1.2 (Infra Migration & Schema Registry)*
*Researched: 2026-08-03*
