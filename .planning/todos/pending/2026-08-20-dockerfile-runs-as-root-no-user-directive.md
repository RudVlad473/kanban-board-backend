---
created: 2026-08-20T00:00:00.000Z
title: "Container runs as root — no USER directive in the Dockerfile"
area: security
severity: moderate
files:
  - Dockerfile
---

## Problem

Filed from a 33-agent ASVS 4.0.3 Level 2 audit (ASVS V1.2.1, V1.14.5).

Both build and runtime stages of the repo-root `Dockerfile` (17 lines) omit `USER` entirely; the
runtime `ENTRYPOINT` therefore runs as `eclipse-temurin:21-jre-jammy`'s default root user.

## Solution

Add a non-root `USER` directive to the runtime stage (create a dedicated app user/group via
`RUN addgroup`/`adduser`, `chown` the app jar/working dir to it, `USER appuser` before
`ENTRYPOINT`). Verify the container still starts and serves traffic under the new uid, and no
file-permission regressions occur.
