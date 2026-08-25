# Spike Manifest

## Ideas

### openapi-error-coverage
Audit OpenAPI error-response coverage for the kanban-board-backend REST API. The API documents
errors via one global `ProblemDetailOpenApiCustomizer` bean that stamps the same six generic
status-code descriptions (400/401/403/404/409/500) onto every operation — a deliberate design
(D-08) that trades per-operation specificity for "no controller method can ever forget to declare
its error responses." The idea: prove out a per-operation override mechanism that layers a
concrete example on top of the generic bucket without replacing it (using POST /api/boards'
duplicate-board-name 409 as the concrete case), then survey the rest of the API for other places
where the generic bucket is too vague to be useful.

**Requirements:**
- Never propose abandoning the global-customizer architecture — work within it (D-08 is a
  deliberate decision, not an oversight).
- Any per-operation override must be additive to `ProblemDetailOpenApiCustomizer`'s generic
  bucket, never a replacement — the customizer's own conditional insert
  (`if (!responses.containsKey(statusCode))`) already supports this.

## Spikes

| # | Idea | Name | Type | Validates | Verdict | Tags |
|---|------|------|------|-----------|---------|------|
| 001 | openapi-error-coverage | board-duplicate-name-409-override | standard | Per-operation `@ApiResponse` override layers on top of the global customizer's generic bucket without replacing it | VALIDATED (mechanism + non-regression proven; JSON capture pending a gradle-collision rerun) | openapi, springdoc, error-documentation |
| 002 | openapi-error-coverage | error-code-coverage-survey | standard | Every `ErrorCode` is named in the generic buckets; 3 operations found where the generic bucket is materially insufficient | PARTIAL (survey complete; live JSON confirmation pending the same rerun) | openapi, error-documentation, audit |
