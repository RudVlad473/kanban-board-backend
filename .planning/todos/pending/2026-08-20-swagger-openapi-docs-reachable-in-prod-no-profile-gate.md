---
created: 2026-08-20T00:00:00.000Z
title: "Swagger / OpenAPI docs are reachable in production with no profile gate"
area: security
severity: moderate
files:
  - src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java
  - src/main/java/com/vrudenko/kanban_board/constant/ApiPaths.java
  - src/main/resources/application.properties
---

## Problem

Filed from a 33-agent ASVS 4.0.3 Level 2 audit (ASVS V14.1.3, V14.2.2).

`SecurityConfiguration`'s requestMatchers for the Swagger docs path and its wildcard, plus
`ApiPaths.SWAGGER_UI`'s wildcard, are matched with `.permitAll()` and no `@Profile` gate anywhere
in that class or `application.properties` — free API-surface reconnaissance for anyone who
discovers the URL.

## Solution

Gate Swagger/OpenAPI UI and docs endpoints behind a non-production profile check
(`springdoc.api-docs.enabled`/`springdoc.swagger-ui.enabled=false` in a production-specific
properties file, or an explicit `@Profile("!production")` guard around the `permitAll` matcher).
Add a test asserting a production-profile request to both paths returns 404/403.
