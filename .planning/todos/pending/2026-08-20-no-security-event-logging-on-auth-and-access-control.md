---
created: 2026-08-20T00:00:00.000Z
title: "No security-relevant event logging on the authentication / access-control paths"
area: security
severity: moderate
files:
  - src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java
  - src/main/java/com/vrudenko/kanban_board/security/LogoutHandler.java
  - src/main/java/com/vrudenko/kanban_board/service/OwnershipVerifierService.java
---

## Problem

Filed from a 33-agent ASVS 4.0.3 Level 2 audit (ASVS V1.2.3, V7.1.3, V7.1.4, V7.2.1, V7.2.2).

Zero `log.*` calls exist anywhere in these three classes — confirmed by direct read and a
repo-wide grep (only `ResetService`, `AvroSchemaRegistrar`, `KafkaEventPublisher`, and
`KafkaConsumerConfig` log anything at all). A real credential-stuffing or unauthorized-access
attempt today leaves zero forensic trail; distinct from the missing rate-limiting guard, this is
about post-hoc visibility, not prevention.

## Solution

Add SLF4J structured logging (matching the existing 4 call sites' library choice) at signin
success/failure, signout, and ownership-denial points — log the userId and outcome, never the raw
password or session token. Keep messages structured/greppable, anticipating a future
log-aggregation consumer (see
`2026-08-20-no-remote-log-shipping-structured-logging-or-alerting.md`).
