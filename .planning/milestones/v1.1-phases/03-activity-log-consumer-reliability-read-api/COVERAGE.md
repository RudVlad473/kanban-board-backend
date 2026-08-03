# Phase 3 — API Coverage Decision

**Detector result:** `detected: true` — one signal, `{"verb":"(surface)","noun":"api"}`, matched on the
literal phrase `"Activity Log Consumer, Reliability & Read API"` in the phase *name*.

No external API integration: the only "API" in this phase's scope is this application's own REST
surface (`GET /boards/{boardId}/activity`), and the only non-HTTP integration is Apache Kafka —
self-hosted internal messaging infrastructure (a single-node KRaft broker this project runs itself
via `docker-compose.yml` for local dev, and via `org.testcontainers.kafka.KafkaContainer` in tests).
There is no third-party SaaS/vendor API, no SDK against a hosted service, no API key, no vendor
account, and no external capability surface to enumerate. `spring-kafka` is a client library for
infrastructure this project operates, not an integration with someone else's product.

Enumerating a capability matrix for `spring-kafka` (transactions, batch listeners, Kafka Streams,
retryable topics, request/reply, …) would fabricate an opt-in/opt-out ledger for a messaging
framework whose surface this phase deliberately uses a narrow, requirement-driven slice of
(`@KafkaListener`, `NewTopic`, `DefaultErrorHandler`, `DeadLetterPublishingRecoverer`,
`ErrorHandlingDeserializer`) — that is exactly the "full coverage by default" checkpoint's
false-positive case.

**Decision:** no `COVERAGE` matrix produced. Phase proceeds with the scope defined by
ACTLOG-01/02/03, READ-01/02, RELY-01/02, TEST-01/02.

---
*Recorded during Phase 3 planning, 2026-08-02*
