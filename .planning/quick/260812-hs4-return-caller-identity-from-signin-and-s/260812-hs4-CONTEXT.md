# Quick Task 260812-hs4: Return caller identity from signin and signup responses for BFF consumption - Context

**Gathered:** 2026-08-12
**Status:** Ready for planning

<domain>
## Task Boundary

`POST /signin` currently returns `ResponseEntity<Void>` (200, empty body) and `POST /signup`
returns `ResponseEntity<String>` (201, empty body — `Location` header is built from
`request.getRequestURI()`, which just points back at `/signup`). Neither endpoint gives the
caller any encoded identity beyond the opaque `Set-Cookie: JSESSIONID=...` session cookie.
Change both endpoints to return a small JSON identity payload alongside the existing
`Set-Cookie` header, sourced from the `UserEntity` the shared private `authenticate()` helper
(`AuthenticationController`, ~line 137) already has in hand at the point the session is
established.

</domain>

<decisions>
## Response DTO shape
- Reuse the existing `UserResponseDTO` (`dto/user_dto/UserResponseDTO.java` — id, email,
  displayName, theme) instead of adding a new minimal DTO. It's already the established
  read-endpoint shape and `UserMapper` already maps `UserEntity` → this DTO by name; no new
  class needed. This is slightly more than the todo's stated minimum (id/email/displayName) —
  accepted trade-off in favor of pattern consistency over exact minimalism.

## Signup Location header
- Fix it as part of this task. `POST /signup`'s `Location` header currently resolves to
  `request.getRequestURI()` (`/signup` again), not a usable resource URI. Point it at a real
  resource URI (e.g. `/api/users/me` or `/api/users/{id}`) now that the handler has the created
  user's id available at the same point the identity DTO is built.

## Existing test updates
- Update `AuthenticationTest`/`AuthenticationE2ETest` in this task. Both currently assert empty
  response bodies on signin/signup; the DTO change breaks those assertions regardless, so fixing
  them (and adding coverage for the new identity payload fields) is part of "done," not a
  follow-up.

### Claude's Discretion
- Exact resource URI shape for the fixed `Location` header (`/api/users/me` vs `/api/users/{id}`
  vs another existing route) — pick whichever matches an existing, already-routable endpoint.
- Whether `UserResponseDTO`'s `theme` field being present in the signin/signup payload needs any
  special handling — it shouldn't, since `UserMapper` already maps it unconditionally and
  `UserEntity.theme` is NOT NULL.

</decisions>

<specifics>
## Specific Ideas

No specific requirements beyond the decisions above — reuse the existing `UserResponseDTO` /
`UserMapper` pattern already used by other read endpoints.

</specifics>

<canonical_refs>
## Canonical References

- Todo: `.planning/todos/pending/2026-08-12-return-caller-identity-from-signin-and-signup-responses-for-.md`
- `src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java:70-135` (signin/signup handlers), `~137` (shared `authenticate()` helper)
- `src/main/java/com/vrudenko/kanban_board/dto/user_dto/UserResponseDTO.java` (DTO to reuse)

</canonical_refs>
