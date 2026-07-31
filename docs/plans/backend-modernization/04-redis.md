# Epic 4 — Redis: cache the expensive board fetch + rate-limit auth endpoints

[← back to plan index](README.md) · Effort: 3–5 days · Priority: **Medium-high**

**Why this and not just "add Redis":** the earlier report flagged Redis as "used but shallow —
verify or deepen." There is currently no Redis dependency in the project at all, so for this
codebase it's a real gap, not just a depth question. Two features, chosen because both have a
genuine reason to exist and both demonstrate *different* Redis capabilities (caching vs. rate
limiting), which is more defensible in an interview than one caching demo.

## Tasks

- Add `spring-boot-starter-data-redis` and add a `redis` service to `docker-compose.yml`.
- **Cache the `GET /boards/{boardId}/full` endpoint from [Epic 2](02-n-plus-one-optimistic-locking.md)**
  with `@Cacheable` (cache key on `boardId`), and invalidate with `@CacheEvict` on every mutation
  in `TaskService`, `ColumnService`, `BoardService` that touches that board's subtree. This is the
  "cache invalidation is the hard part" story — be ready to explain how you keep the cache from
  going stale when a task moves.
- **Rate-limit `/signin` and `/signup`** using Redis (either Bucket4j with a Redis backend, or a
  hand-rolled fixed-window counter using `INCR` + `EXPIRE`) keyed by IP or email, to blunt brute-
  force/credential-stuffing attempts. This ties directly back to the existing credential-hashing
  production work — it's a natural, coherent extension of a security story already in place.
- Write a test asserting the 6th signin attempt within the window returns 429, and a test asserting
  the board cache is actually hit (no DB query) on a second `GET .../full` call, then evicted after
  a task move.
