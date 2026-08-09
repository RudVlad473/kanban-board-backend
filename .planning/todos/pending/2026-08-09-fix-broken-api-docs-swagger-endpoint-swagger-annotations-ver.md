---
created: 2026-08-09T20:44:30.943Z
title: Fix broken /api/docs Swagger endpoint (swagger-annotations version conflict)
area: backend
severity: major
files:
  - build.gradle:132
---

## Problem

`GET /api/docs` (SpringDoc's OpenAPI JSON endpoint, and by extension the Swagger UI)
500s for every caller with:

```
Handler dispatch failed: java.lang.NoSuchMethodError: 'java.lang.Class[] io.swagger.v3.oas.annotations.Parameter.validationGroups()'
```

Root cause: `io.confluent:kafka-avro-serializer:7.8.9` transitively pulls in
`io.confluent:kafka-schema-registry-client:7.8.9`, which depends on
`io.swagger.core.v3:swagger-annotations:2.1.10` — the pre-jakarta swagger-annotations
artifact. It shares the exact same package (`io.swagger.v3.oas.annotations`) as
`swagger-annotations-jakarta:2.2.30`, which SpringDoc 2.8.8 actually needs and which is
also present on the classpath. Gradle doesn't recognize these as the same module (different
artifact IDs), so both jars land on the runtime classpath, and whichever jar's `Parameter`
class the JVM classloader happens to load first wins — in practice the older 2.1.10 class,
which is missing the `validationGroups()` method SpringDoc calls, hence the
`NoSuchMethodError`.

Confirmed live via `./gradlew dependencies --configuration runtimeClasspath` and by running
the full docker-compose stack (2026-08-09, while generating an OpenAPI spec for frontend
consumption) — see `kanban-board-openapi.json` handoff.

## Solution

Exclude the shadowing artifact from the Confluent dependency in `build.gradle` (verified
working):

```gradle
implementation('io.confluent:kafka-avro-serializer:7.8.9') {
    exclude group: 'org.apache.kafka', module: 'kafka-clients'
    exclude group: 'io.swagger.core.v3', module: 'swagger-annotations'
}
```

After the change, re-run `./gradlew dependencies --configuration runtimeClasspath | grep swagger-annotations`
to confirm only `swagger-annotations-jakarta:2.2.30` remains, then hit `GET /api/docs`
against a running instance to confirm a 200 with a valid OpenAPI document.

Secondary, unrelated finding from the same session worth folding in if this todo is picked
up: there's no `.dockerignore` in the repo, so `docker build`/`docker compose build` sends
the entire working tree (including `.claude/worktrees/`) as build context. This failed
outright once when a worktree's Gradle lock file was held by a concurrent process. Add a
`.dockerignore` with at least `.git`, `.gradle`, `.claude`, `.planning`, `build`.
