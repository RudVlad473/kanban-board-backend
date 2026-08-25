# Spike Conventions

Patterns and stack choices established across spike sessions. New spikes follow these unless the
question requires otherwise.

## Stack

Backend-only project (Spring Boot / Java 21 / Gradle) — no separate spike stack. Spikes that touch
source code work directly in the main tree (no scratch scaffolding needed), verified via the
project's own test harnesses rather than a bespoke spike runner.

## Patterns

**OpenAPI documentation overrides.** This API documents errors via one global
`ProblemDetailOpenApiCustomizer` bean stamping generic per-status descriptions onto every
operation (a deliberate decision, D-08, that rejects per-endpoint annotations by default). A
*specific* operation can still get a concrete example on top of that generic bucket — add a plain
`io.swagger.v3.oas.annotations.responses.ApiResponse` (with `@Content`/`@ExampleObject`) directly
on the controller method. springdoc's annotation-based generation runs before
`GlobalOpenApiCustomizer` beans (confirmed via springdoc's own docs, spike 001), and the
customizer's conditional insert (`if (!responses.containsKey(statusCode))`) leaves any
already-populated status code alone — so the two mechanisms compose without conflict. Never
replace or bypass the global customizer to do this.

**Verifying a generated OpenAPI document.** Use the harness `ProblemDetailOpenApiCustomizerTest`
already establishes: `@SpringBootTest @AutoConfigureMockMvc`, `@Value("${springdoc.api-docs.path}")`,
`mockMvc.perform(get(apiDocsPath))`, parse the response body with Jackson `ObjectMapper`. Don't
autowire the `OpenAPI` bean directly — springdoc caches the built document, so a test holding the
live instance risks mutating state shared with other assertions in the same Spring context.

## Tools & Libraries

`io.swagger.v3.oas.annotations.*` (springdoc-openapi-starter-webmvc-ui's transitive
swagger-annotations dependency) — already on the classpath, no new dependency needed for
per-operation overrides.
