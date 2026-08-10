---
created: 2026-08-10T20:00:00.000Z
title: Kafka-sourced ActivityEvent fields are persisted with no re-validation and no producer-authentication boundary
area: backend
severity: security
files:
  - src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumer.java:61
---

## Problem

`/claude-security` scan (2026-08-10, medium effort, whole-repo `attack-surface` scope, run
against phase 07.1's finished HEAD) finding **F2** (medium severity, confidence medium, 2/3
adversarial-panel votes):

`ActivityLogConsumer.onActivityEvent` (`:61`) persists `boardId`/`userId`/`columnId`/`taskId`
straight off the Avro `SpecificRecord` deserialized from the topic, with no re-validation that
these ids still refer to real, related entities. Nothing in `application.properties` or the
compose/deploy config enforces SASL authentication or per-topic ACLs restricting who is allowed
to *produce* to `kanban.activity` — any client that can reach the broker on its listener port can
write an event this consumer will trust and persist into `activity_log`.

## Why this is deferred, not fixed now

This is fundamentally an infrastructure/network-boundary gap, not an application-code gap the
service layer can close on its own — the fix is "only the app itself (and no one else) can reach
the broker's produce API," which is an authn/authz-at-the-broker concern, not a Java-code concern.

**Important — do not assume `REQUIREMENTS.md` INFRA-03/INFRA-08 already close this.** They are
the *closest* existing requirements, but neither actually covers the specific gap:

- INFRA-03 (self-hosted Redpanda, Phase 5) provisions the broker itself — it says nothing about
  SASL/ACL configuration restricting *which clients* may produce to `kanban.activity`.
- INFRA-08 (network firewall audit, Phase 5 success criterion 5: "Redpanda's 9092 must never be
  internet-facing") closes the *network*-level boundary (nothing outside the VM's private network
  can reach the broker's port at all) but says nothing about *application-layer* authorization
  once a client is already inside that network boundary — e.g. another process/container
  co-located on the same Oracle VM, or a future second consumer service (see the already-filed,
  unrelated todo exploring a second `kanban.activity` consumer as a tech-exploration vehicle).

So even after Phase 5 ships INFRA-03 and INFRA-08 as currently scoped, this specific gap —
SASL/ACL restricting *who* may produce to the topic, independent of network reachability — remains
open. This todo exists so that gap stays visible and doesn't get silently assumed-closed when
Phase 5 completes.

Deferred rather than fixed in this phase because: it requires broker-side configuration (SASL
mechanism choice, ACL rules) that has no meaning against this phase's local docker-compose
Redpanda (single-node, no auth configured anywhere yet) — there is no real trust boundary to
secure locally, only against the eventual Phase 5 production broker. Severity is medium, not
high, and this phase did not introduce or worsen the gap (`ActivityLogConsumer`'s trust-the-event
shape predates 07.1).

## Solution

When picked up (likely alongside or shortly after Phase 5's INFRA-03/INFRA-08 work, since it needs
a real broker to configure against): enable SASL authentication on the production Redpanda broker,
provision separate credentials for the producer (`KafkaEventPublisher`) and any future consumer,
and add ACL rules restricting `kanban.activity` produce rights to the app's own producer identity.
Consider also adding lightweight application-layer sanity checks in `ActivityLogConsumer` — e.g.
confirming `boardId` still resolves to a real board before trusting a derived write — as defense
in depth, independent of the broker-level fix, though that alone would not close the underlying
authorization gap this todo tracks.
