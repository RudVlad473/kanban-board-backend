# Feature Research

**Domain:** Kanban board REST API — nested board-read endpoint + optimistic-locking conflict handling
**Researched:** 2026-07-31
**Confidence:** HIGH (endpoint scoping is a well-understood REST pattern with direct precedent in Trello/Jira docs; optimistic-locking client-contract is a design decision this research grounds in industry norms, not a single universal standard)

## Context Recap

This is a small, single-owner-per-board portfolio API (no soft-delete/archive columns exist anywhere in the entity model — deletes are hard cascading deletes; confirmed via codebase read). That fact alone eliminates an entire category of "what does full-board include/exclude" questions that plague Trello/Jira (they have archive states, this app doesn't). Scope findings accordingly: don't import Jira's/Trello's problems if this codebase doesn't have the underlying feature that causes them.

## Feature Landscape

### Table Stakes (Users Expect These)

Features the two target endpoints are unusable/incomplete without.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Single nested GET returning board → columns → tasks → subtasks in one response | This is the entire point of the `/full` endpoint — a client rendering the initial board view must not have to make N+1 round trips itself (explicitly called out in the epic spec). Every real board product (Trello `?cards=open&lists=open`, Jira board issue endpoint, Linear's GraphQL nested query) offers exactly this: one call, full initial-render payload. | MEDIUM | Core deliverable. Must resolve the collection-fetch strategy (see Anti-Features below for the naive-JOIN-FETCH trap). |
| Ordered columns and ordered tasks-within-column in the response | Board UIs are positional; a full-board payload with unordered lists/tasks is unusable for rendering. | LOW | Just `ORDER BY position` (or whatever ordering column exists) in the query/stitch step — trivial once the fetch strategy is chosen. |
| Version/optimistic-lock field surfaced on Task/Column responses touched by move/reorder | If the client is expected to detect/react to conflicts, it needs to know what version it's holding. Every optimistic-locking API (Salesforce OCAPI, most ETag-based REST APIs) exposes the version/ETag to the client for this exact reason. | LOW | Since `@Version` is being added to `TaskEntity`/`ColumnEntity`, the DTOs for those entities must include it (as a plain field or as an `ETag`/`If-Match` header — plain field is simpler and matches this codebase's existing flat-DTO convention). |
| 409 Conflict on concurrent conflicting update, not a 500 or silent overwrite | This is the entire reason optimistic locking is being added per the epic spec. Silent overwrite (last-write-wins) is the exact bug being fixed; a 500 is a mapping bug, not a proper conflict signal. Universally, optimistic-locking APIs map version-mismatch to 409 (Salesforce OCAPI, generic REST convention per RFC 7231/9110). | LOW | `ObjectOptimisticLockingFailureException` → `@ExceptionHandler` in `GlobalExceptionHandler` → 409. Already scoped in PROJECT.md. |
| Ownership/access check still applied on `/full` (same as every other endpoint) | This app's whole security model is ownership-based; a nested endpoint that skips that check would be a regression, not a feature. | LOW | Reuse `OwnershipVerifierService.verifyOwnershipOfBoard` — no new pattern needed. |

### Differentiators (Nice Polish, Likely Out of Scope for This Project)

Valuable in a real product with growth/scale pressure, but not required to make Epic 2's deliverables correct or interview-defensible at this project's size.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Pagination / lazy-loading of tasks or subtasks for very large boards | Real products (Jira, Trello) cap or paginate board payloads because production boards can have thousands of cards; an unbounded nested fetch becomes a multi-MB response. | MEDIUM–HIGH | **Explicitly not needed here.** This is a portfolio/personal project — boards will have a handful of columns and tens of tasks, not thousands. Building pagination for a full-board fetch at this scale is solving a problem that doesn't exist yet and would itself look like over-engineering in a review. Mention in interview as "I know Trello/Jira paginate at scale; didn't add it here because board sizes in this app don't warrant it — here's the threshold I'd add it at." |
| Retry-with-backoff or auto-merge on 409 (client-side) | Sophisticated clients (per OCAPI guidance) don't just show an error — they refetch the latest state and retry the specific operation, sometimes transparently. | MEDIUM (client-side, not in this backend's scope at all) | This is a **frontend/client** concern, not backend. Backend's job stops at returning a well-formed 409 with enough information (current version, or just a clear conflict body) for the client to decide. Do not build server-side auto-merge — see Anti-Features. |
| Field-level/partial conflict detection (only reject if the *specific fields the client is touching* changed, e.g. JSON Patch + version) | More forgiving UX — e.g., two users editing different fields of the same task wouldn't conflict. | HIGH | Overkill for a drag-and-drop reorder scenario, where the entire point is that *position* changed — whole-row versioning is the correct, standard tool for exactly this case (this is what `@Version` optimistic locking is designed for). Don't reach for field-level diffing here. |
| Depth/expand query params on `/full` (e.g. `?include=subtasks` to control nesting depth) | Real GraphQL-style or Jira-style APIs let clients control how much gets nested/expanded. | MEDIUM | Nice REST hygiene but not required for a single-board fetch with a fixed, small entity depth (board→column→task→subtask is only 3 levels, all needed for initial render). Skip; revisit only if a consumer ever needs a shallow board list without subtask detail. |
| ETag / `If-Match` header-based concurrency instead of a body `version` field | Slightly more "RESTful"/HTTP-native than a body field; used by some APIs (per the general REST 409 pattern researched). | MEDIUM | Valid alternative, but this codebase's convention is flat DTOs with plain fields, and JPA's `@Version` naturally maps to a body field the client echoes back on PUT. Switching to ETag headers here would be inventing a new convention mid-epic for no functional gain — skip. |

### Anti-Features (Commonly Requested, Often Problematic)

Things that look like natural extensions of these two features but would be wrong scope for this project.

| Feature | Why It Seems Appealing | Why Problematic | Alternative |
|---------|------------------------|------------------|-------------|
| Naive triple `JOIN FETCH` across `board.columns.tasks.subtasks` in one query | Looks like "the simple, obvious" way to get everything in one query. | Classic Cartesian-product blowup: fetching two `List` collections (`columns`+`tasks`, or `tasks`+`subtasks`) in a single `JOIN FETCH` multiplies rows (Hibernate's well-documented `MultipleBagFetchException` risk, or silently duplicated/ballooned result sets even when it doesn't throw). This is explicitly flagged as a trap in the epic spec. | Use `@BatchSize` on the collections (Hibernate batches secondary `IN (...)` fetches) or do 2–3 separate queries and stitch the object graph in Java. Either is standard; pick one and be ready to explain the tradeoff (fewer queries vs. more predictable row counts). |
| Filtering archived/soft-deleted columns/tasks/subtasks out of `/full` | Trello and Jira both have archive concepts and both have to decide default-exclude behavior for their "get board" endpoints — looks like a "real API" must handle this too. | **This codebase has no soft-delete/archive column on any entity** (confirmed: `deleted`/`archived` fields don't exist in `BoardEntity`/`ColumnEntity`/`TaskEntity`/`SubtaskEntity`). Deletes are hard, cascading DB deletes. Building archive-filtering logic here would be solving a problem the data model doesn't have — pure scope creep for this epic. | If archiving is ever added as a real feature later, handle it then, in its own phase with its own DTO/filter changes. For `/full` today: return exactly what exists in the DB for that board (there is nothing else to exclude). |
| Pagination on `/full`'s nested collections | "Big boards need pagination" is true in general (see Differentiators). | Adds real complexity (cursor/offset params nested inside a nested response, partial-collection semantics, client-side "load more" per column) for a project where board sizes are small and controlled by the project owner. Also fights the endpoint's actual purpose: `/full` exists specifically to avoid multiple round trips for the *initial* render — paginating it reintroduces the round-trip problem it was built to solve. | If/when board size becomes a real concern, add a *separate* lighter "board summary" endpoint (counts only) rather than paginating the nested fetch. Not needed now. |
| Server-side automatic conflict resolution / merge on concurrent reorder (e.g., "smart" re-ranking so both drags "just work") | Feels like better UX than showing the user an error — "why bother the user with a 409 if the server can just figure out where both cards should end up?" | This is genuinely hard (it's the same class of problem as OT/CRDT-based collaborative editing) and is explicitly not what optimistic locking is for. Silently reconciling two conflicting position changes risks producing an order neither user intended, and masks the very race condition the epic is designed to demonstrate and fix. It also isn't how any of the researched precedents (Salesforce OCAPI, generic ETag/version REST APIs) recommend handling this — they all resolve at the HTTP layer (409) and push resolution to the client. | Return 409 with the current server-side state (or at minimum the current version) in the response body. Let the client decide: show the user "someone else moved this card, refresh?" or silently refetch-and-reapply. Both are client concerns, not backend ones. |
| Retry loop / exponential backoff *inside the backend* for optimistic-lock conflicts | Could seem like it "fixes" the conflict for the client automatically. | Backend-side blind retry on a version conflict is nonsensical for a user-driven action like drag-and-drop — the "retry" would just re-read a version the *user's own subsequent action* invalidated, and it hides a real concurrent-edit signal that the client legitimately needs to know about (someone else moved this card). Retry-with-backoff patterns from the research (OCAPI) are explicitly a *client*-side pattern for transient conflicts, not something the server does on the client's behalf for a user-intent conflict. | Backend does exactly one thing: attempt the update, catch `ObjectOptimisticLockingFailureException`, return 409. No server-side retry. |
| Pessimistic locking (`SELECT ... FOR UPDATE`) on Task/Column rows during reorder | Would also "solve" concurrent overwrite, and is a real alternative technique worth knowing about. | Wrong tool for this access pattern: drag-and-drop conflicts are rare (two users moving the *same* card at the *same instant*), long-held row locks on a web-request-scoped transaction risk lock contention/deadlocks for no real benefit at this scale, and the epic spec explicitly asks for optimistic locking (`@Version`) as the technique to demonstrate. | Optimistic locking is correct here — low-contention, short transactions, and it's the JPA-idiomatic mechanism the epic is scoped around. Know pessimistic locking well enough to explain why it's not the right choice here (that contrast is itself the interview-ready explanation the epic spec calls for). |

## Feature Dependencies

```
GET /boards/{id}/full
    └──requires──> Ownership verification (already exists, Finding 1 chain)
    └──requires──> Chosen collection-fetch strategy (@BatchSize or two-query-stitch)
                       └──must avoid──> naive triple JOIN FETCH (Cartesian product)
    └──requires──> Nested DTO shape (new — departs from existing flat-DTO convention)

Optimistic locking (@Version on Task/Column)
    └──requires──> Version field surfaced in Task/Column response DTOs
    └──requires──> ObjectOptimisticLockingFailureException → 409 mapping in GlobalExceptionHandler
    └──enables───> Client-side conflict handling (refetch-and-reapply or user-facing error) — OUT OF THIS BACKEND'S SCOPE

Pagination on /full ──conflicts with──> the endpoint's stated purpose (avoid round trips for initial render)
Archive-filtering on /full ──not applicable──> no archive/soft-delete column exists in this data model
```

### Dependency Notes

- **`/full` requires a fetch-strategy decision before DTO design:** the DTO shape (flat list-of-columns each containing list-of-tasks each containing list-of-subtasks) is the same regardless of whether it's populated via `@BatchSize` or two-query-stitch, but the *service-layer* implementation differs meaningfully, so this decision should be made first in phase planning, not discovered mid-implementation.
- **Optimistic locking requires the version field to reach the client** for the "expected client behavior on 409" question to be answerable at all — if the DTO doesn't expose `version`, the client has no way to send back the version it thinks it's updating, and the 409 becomes the only mechanism (which is acceptable, since the client can just refetch on 409 rather than pre-emptively checking version, but the field should still be present in the read DTO for a well-formed API).
- **Pagination conflicts with `/full`'s purpose:** noted explicitly because it's the most likely piece of accidental scope creep — resist adding it just because "real" board APIs have it.

## MVP Definition (Epic 2 Scope)

### Launch With (this epic)

- [ ] `GET /boards/{boardId}/full` returning board + ordered columns + ordered tasks + ordered subtasks, using `@BatchSize` or two-query-stitch (not naive triple JOIN FETCH) — required, this is the epic's stated deliverable
- [ ] Nested response DTO (new pattern, explicitly justified as a deliberate departure from the flat-DTO convention, per PROJECT.md context)
- [ ] `@Version` on `TaskEntity` and `ColumnEntity`, surfaced in their response DTOs
- [ ] 409 mapping for `ObjectOptimisticLockingFailureException` in `GlobalExceptionHandler`
- [ ] Test proving two concurrent updates to the same task/column throw the locking exception (already scoped in PROJECT.md)

### Add After Validation (future epics, not this milestone)

- [ ] Board-size-aware pagination or a lightweight board-summary endpoint — only if/when real usage shows boards growing large
- [ ] Archive/soft-delete on columns/tasks — only if that becomes a real product feature (currently doesn't exist at all)
- [ ] Client-side conflict-resolution UX (frontend concern, separate from this backend epic)

### Future Consideration (v2+, likely never for a portfolio project)

- [ ] ETag/`If-Match`-header-based concurrency instead of body `version` field — no functional gain for this codebase's conventions
- [ ] Field-level partial-conflict detection — wrong granularity for a whole-row reorder scenario
- [ ] Server-side auto-merge of concurrent reorders — explicitly the wrong tool (see Anti-Features)

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| `/full` nested endpoint (correct fetch strategy) | HIGH | MEDIUM | P1 |
| `@Version` + 409 mapping | HIGH | LOW | P1 |
| Version field in response DTOs | MEDIUM | LOW | P1 |
| Query-count regression test for `/full` | MEDIUM | LOW | P1 (matches existing convention of query-count guard tests) |
| Pagination on `/full` | LOW (at this project's scale) | HIGH | P3 / defer |
| Archive filtering | N/A (feature doesn't exist) | N/A | Not applicable |
| Client-side retry/backoff | LOW (not this repo's concern) | N/A | Out of scope (frontend) |

## Competitor Feature Analysis

| Feature | Trello | Jira (Agile REST API) | This Project's Approach |
|---------|--------|------------------------|--------------------------|
| Full-board nested fetch | `GET /board/{id}?cards=open&lists=open` — query params control which cards/lists are included (open vs all vs closed/archived) | `GET /rest/agile/1.0/board/{boardId}/issue` — separate endpoint per resource type, client must compose; board-level "everything nested" isn't a single call | Single `GET /boards/{id}/full` returning the whole tree in one response — simpler than both, appropriate because there's no archive-state to filter and board sizes are small |
| Archived/closed item filtering | Explicit `open`/`closed`/`all` filter params on cards and lists | Archived issues are NOT reliably excluded by default from board REST responses (a known Jira Cloud gap) — must be filtered via JQL param | Not applicable — no archive concept exists in this data model; every row in the DB for a board is returned |
| Position/order representation | Floating-point `pos` field per card/list; renumbers a local neighborhood when values converge (not a whole-board renumber) | Rank field (LexoRank), also fractional-ordering based | This project already has *some* ordering mechanism per column/task (verify in phase planning) — the optimistic-locking concern (version conflict on concurrent move) is orthogonal to *how* position is encoded; don't conflate the two problems |
| Concurrent-edit conflict handling | Not primarily HTTP 409/optimistic-locking based in its public API design (positions are designed to rarely collide, and when they do, last-write-wins on `pos` is acceptable because the "conflict" is just a slightly-off card order, not data loss) | Standard REST resource versioning is not prominently exposed for issue reordering either — Jira Software's board ordering is more infrastructure-internal | This project explicitly bucks the "make collisions cheap to ignore" approach (which works for Trello's throwaway `pos` floats) and instead does *correctness-first* whole-row optimistic locking via `@Version` — appropriate because the epic's goal is to demonstrate and prevent silent overwrite, which is exactly the JPA/Hibernate depth skill being showcased, not to build a production-scale collision-tolerant board |

## Explicit Answer: 409 Client-Contract Recommendation

**This backend's contract:** on a version conflict during Task/Column update (including drag-and-drop reorder), return **HTTP 409** with a response body containing at minimum the conflict reason (e.g. `"error": "The task was modified by another user"`) — do not attempt server-side merge, retry, or silent overwrite.

**What the client is expected to do (informational — this is a frontend concern, not part of this backend epic, but stating it here makes the contract's purpose reviewable):** the industry-standard pattern for this exact scenario (per OCAPI's documented guidance, which is the clearest published precedent for optimistic-lock 409 handling) is **refetch-and-reapply**, not silent blind retry and not always a raw user-facing error:
1. Client receives 409 on the reorder PATCH/PUT.
2. Client refetches the current state of the affected column(s)/task (or just uses `/full` again).
3. Client reapplies the user's intended drag operation against the fresh state (e.g., "move this task to position 3 in this column") and resubmits.
4. Only if this also conflicts repeatedly (rare) does the client fall back to a user-facing message ("This board was updated elsewhere — please try again").

This backend does not need to implement steps 2–4 — that's the frontend's job. What this backend must guarantee to make that client behavior *possible* is: **a well-formed 409 (not 500), returned promptly, with enough of an error body that the client can distinguish "conflict, please refetch" from other 4xx/5xx failure modes.** That's the actual, scoped, backend-side deliverable, and it's fully covered by the epic's stated task list (map `ObjectOptimisticLockingFailureException` → 409 in `GlobalExceptionHandler`).

## Sources

- [Nested Resources - Trello (Atlassian docs)](https://developer.atlassian.com/cloud/trello/guides/rest-api/nested-resources/) — HIGH confidence, official docs
- [The Trello REST API — Boards group](https://developer.atlassian.com/cloud/trello/rest/api-group-boards/) — HIGH confidence, official docs
- [Trello `pos` field / fractional ordering discussion (Hacker News, referencing Trello engineering)](https://news.ycombinator.com/item?id=10957165) — MEDIUM confidence, community-sourced but consistent with well-known Trello engineering blog content
- [The Jira Software Cloud REST API — Board group](https://developer.atlassian.com/cloud/jira/software/rest/api-group-board/) — HIGH confidence, official docs
- [Jira Cloud archived-issues-in-REST-API community thread](https://community.atlassian.com/forums/Jira-Service-Management/Jira-REST-API-returns-archived-issues-but-does-not-expose/qaq-p/3170667) — MEDIUM confidence, community/support forum, but consistent across multiple threads
- [Salesforce B2C Commerce (OCAPI) Optimistic Locking docs](https://developer.salesforce.com/docs/commerce/b2c-commerce/references/b2c-commerce-ocapi/optimisticlocking.html) — HIGH confidence, official vendor docs, clearest published precedent for 409 + client refetch-and-reapply pattern
- Codebase read: `src/main/java/.../entity/*.java` — confirmed no archive/soft-delete fields exist on any entity (HIGH confidence, primary source — this repo)
- `docs/plans/backend-modernization/02-n-plus-one-optimistic-locking.md` — epic spec, source of the two target features' exact scope (HIGH confidence, primary source)

---
*Feature research for: Kanban board API — full-board nested read + optimistic-locking conflict handling*
*Researched: 2026-07-31*
