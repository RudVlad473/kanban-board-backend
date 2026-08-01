# Epic 6 — Observability: Actuator + Micrometer + Prometheus

[← back to plan index](README.md) · Effort: 2–3 days · Priority: **Medium**

**Why this is here even though it wasn't explicitly requested:** the project currently has *zero*
observability — no Actuator, no metrics endpoint, nothing. That's exactly the kind of "how would
you know this is unhealthy in prod" gap that any system-design review surfaces, and it's cheap to
close.

## Tasks

- Add `spring-boot-starter-actuator` and `micrometer-registry-prometheus`.
- Expose `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus` — but make sure
  `SecurityConfiguration` explicitly locks these down (don't just permit-all them; either restrict to
  a local/internal network assumption you document, or put them behind an actuator-specific auth
  rule) — leaving actuator endpoints wide open is itself a real-world security mistake worth
  avoiding and worth being able to say you avoided.
- Add a `prometheus` + `grafana` service to `docker-compose.yml` with one basic dashboard
  (request rate, p95 latency, JVM memory, and — nice touch tying back to
  [Epic 2](02-n-plus-one-optimistic-locking.md) — Hibernate query count/time via Micrometer's
  Hibernate metrics binder).
- Add a custom metric: counter for Kafka events published ([Epic 1](01-kafka-activity-feed.md)) and
  a gauge for activity-log consumer lag, since "how do you know your consumer is falling behind"
  is a natural follow-up once Kafka is in the picture.
