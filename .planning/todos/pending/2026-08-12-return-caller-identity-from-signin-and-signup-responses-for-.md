---
created: 2026-08-12T10:13:53.337Z
title: Return caller identity from signin and signup responses for BFF consumption
area: backend
severity: major
files:
  - src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java:70-135
---

## Problem

`POST /signin` returns `ResponseEntity<Void>` (200, empty body) and `POST /signup` returns
`ResponseEntity<String>` (201, empty body — the `Location` header is built from
`request.getRequestURI()`, which is just `/signup` again, not a usable resource URI). The only
thing either endpoint returns is the `Set-Cookie: JSESSIONID=...` header — an opaque session
cookie with no encoded user info (verified: it's a plain servlet session id, not a JWT; the
session itself is server-side state in Postgres via Spring Session JDBC).

There is also no `GET /users/me` identity endpoint — `UserController` only exposes
`GET /users/me/theme`, nothing that returns id/email/displayName.

Surfaced from a conversation with a frontend team building a BFF (backend-for-frontend) in front
of this API: their `/signup`/`/signin` route handlers have no documented way to learn which user
just authenticated, since the backend gives them nothing but an opaque cookie. This shapes their
session payload, their MSW mock responses, and every downstream route handler that needs to know
"who is this."

Three options were discussed for how the BFF could learn the caller's identity:
1. BFF re-derives it from the login form (it already has the email from the request, but not the
   backend's internal ULID `userId`)
2. Add a separate `GET /users/me` endpoint the BFF calls right after a successful signin
3. **Chosen**: change `/signin`/`/signup` themselves to return a small identity payload

## Solution

Change `POST /signin` and `POST /signup` to return a small JSON body (at minimum: `id`, `email`,
`displayName`) instead of `Void`/empty `String`, alongside the existing `Set-Cookie` header they
already set. Likely reuses (or is shaped like) the existing `UserResponseDTO` pattern this
codebase already has for other read endpoints — check `dto/user_dto/` for a existing DTO to reuse
or a new minimal one to add. `AuthenticationController.signin`/`signup` (lines ~70-135) are both
built through the shared private `authenticate()` helper (line 137) that already has access to the
authenticated `UserEntity`/`userId` at the point the session is established — the DTO mapping can
happen right there before returning `ResponseEntity.ok()`/`ResponseEntity.created()`.

Needs its own investigation/design pass before implementation (not scoped in detail here): exact
response shape, whether `signup`'s `Location` header should also be fixed to point at a real
resource URI while this is touched, and whether existing tests
(`AuthenticationE2ETest`/`AuthenticationTest`) asserting an empty body need updating as part of
this change.
