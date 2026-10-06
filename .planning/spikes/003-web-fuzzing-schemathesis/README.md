---
spike: 003
idea: web-fuzzing
name: web-fuzzing-schemathesis
type: standard
validates: "Given the live /api/docs OpenAPI document, when Schemathesis fuzzes the running app with a session cookie, then it runs end to end and surfaces actionable findings without app changes"
verdict: PARTIAL
related: [001, 002]
tags: [fuzzing, schemathesis, openapi, security-testing]
---

# Spike 003: Web Fuzzing with Schemathesis

## What This Validates
Given the live `/api/docs` document, when Schemathesis (property-based, OpenAPI-driven) fuzzes the
running app using a signed-in session cookie, then it runs end to end and produces actionable
findings. Question behind it: how complex is it to add web fuzzing to this project?

## Research
| Approach | Pros | Cons | Status |
|----------|------|------|--------|
| Schemathesis (`uvx schemathesis`) | Reads the OpenAPI doc the app already publishes; zero Java deps; no install | Python tool beside a Gradle project; quality capped by spec quality | **chosen** |
| OWASP ZAP | Broader (passive scan, auth contexts) | Not installed; heavier; mostly generic payloads, little schema awareness | not tried |
| jqwik / Jazzer in JUnit | Same language, runs in `gradlew test` | Fuzzes units, not the HTTP surface; new dependency (violates "no new frameworks") | not tried |

Only Schemathesis was run; the other two rows are judgment, not measurement.

## How to Run
```bash
export DB_NAME=kanban_board DB_USER=kanban DB_PASS=localdev
docker compose up -d postgres redpanda
export DB_HOST=localhost DB_PORT=5433 SPRING_JPA_HIBERNATE_DDL_AUTO=validate SERVER_PORT=8089
sh ./gradlew bootRun &            # 8080 was occupied on the spike machine
B=http://localhost:8089/api
curl -s -X POST $B/signup -H 'Content-Type: application/json' \
  -d '{"email":"fuzz@example.com","password":"Fuzzer-Passw0rd!","displayName":"Fuzz"}'
C=$(curl -si -X POST $B/signin -H 'Content-Type: application/json' \
  -d '{"email":"fuzz@example.com","password":"Fuzzer-Passw0rd!"}' | rg -o 'JSESSIONID=[^;]+' | head -1)
curl -s $B/docs > out/openapi.json
uvx schemathesis run out/openapi.json --url $B -H "Cookie: $C" --max-examples 25 --workers 1
```

## Investigation Trail
1. Local stack came up with the documented `docker compose` + `bootRun` recipe (~50s to start).
   Port 8080 was already held by an unrelated process; `SERVER_PORT=8089` sidestepped it.
2. First run, full operation set: **24/24 operations selected, 639 cases, 51s**, 45 unique failures,
   11 schema errors.
3. Auth is the only plumbing needed: session cookie passed as a `Cookie` header. The cookie is
   `Secure`, so `curl`'s jar will not replay it over plain http; passing the header explicitly works.
   Cookie `Max-Age=600`, so a long run needs a re-signin.
4. `--exclude-path /logout` matched nothing: verified `/logout` is simply absent from the generated
   doc (only `/signup` and `/signin` are listed). So logout is never fuzzed, and no exclusion is
   needed.
5. Second run (same seed) reproduced the finding classes; counts moved because of session expiry.

## Results
**Feasibility: low effort to start, moderate effort to make it a trustworthy CI gate.**

What the first run actually surfaced (one run, 25 examples per operation, not a saturation run):

| Finding | Count (run 2) | Real API bug? |
|---|---|---|
| 11 operations skipped: "Path parameter 'boardId' is not defined" | 11 ops | **Yes, in the OpenAPI doc.** Nested routes publish no path-parameter schema, so Schemathesis cannot generate requests for them: the deepest, most interesting routes (tasks, subtasks, column reorder) got **no fuzzing at all**. |
| `userId` is published as a **required query parameter** (e.g. `GET .../tasks`), although the server takes identity from the session via `@CurrentUserId` | seen on every operation sampled (1 inspected directly) | Yes, spec leak: the resolver argument is not hidden from springdoc, so clients and fuzzers are told to supply an identity the server ignores. Likely same root cause as the missing path parameters (custom argument resolver confusing parameter introspection); not confirmed. |
| 401 responses violate their schema: `instance` is declared `format: uri`, app returns `/api/boards/0` | 24 | Yes, spec/impl mismatch (RFC 7807 `instance` may be a URI reference; the declared `format` is too strict, or the producer should emit absolute URIs). |
| `/signup` accepted a body with extra junk properties and returned **201**, which the doc does not list | 1 | Doc gap: 201 is missing from the documented statuses; extra-property acceptance is a design question. |
| Signin/signup "rejected schema-compliant request" (400 on `{}` / weak password) | 2 | Spec gap: the schema does not carry the `@Password` / `@AppEmail` constraints, so the generator cannot know. |
| Missing Content-Type / JSON deserialization error | 5 + 5 | Not triaged. |
| TRACE returned 400, expected 405 (run 1) | 1 | Minor; framework default. |
| Server error (500) in run 1 | 1 | **Not reproduced or identified** in run 2's saved output; run 1's output was only tailed. Treat as unconfirmed. |

Side effect worth knowing: fuzzing the create endpoints publishes real Kafka events. The local
schema registry had no subjects registered, so ~60 Avro serialization errors landed in the app log.
That is local-environment noise (not an API bug), but it shows fuzzing is **not read-only** and must
never point at prod or the shared nonprod broker.

## Verdict reasoning
PARTIAL, not VALIDATED: it runs and finds things, but the most valuable result is that the spec is
not yet good enough to fuzz the nested routes, and I did not confirm the one 500. I also did not run
it in CI, did not trial ZAP or an in-JVM fuzzer, and used 25 examples per operation.

## Signal for the Build
- **Smallest useful step:** a nonprod-only GitHub Actions job (or manual script) that boots the app
  against Testcontainers/compose, signs in, and runs `schemathesis` with a pinned version and fixed
  seed. No Gradle or Java change.
- **Prerequisite, and the real cost:** fix the missing path-parameter declarations in the generated
  OpenAPI doc (11 operations), then correct the 401 `instance` format. Until then, coverage is
  roughly half the API.
- **Gate carefully:** start report-only; the first run has dozens of spec-conformance failures that
  would block every PR.
- Pin the Kafka side (point at a throwaway broker or disable event publishing) before any CI use.
- Follow-ups not done: identify the run-1 500; check whether `/logout` is reachable from the doc.
