---
created: 2026-08-12T00:00:00.000Z
title: POST /signup's Location header names a URI with no GET handler (GET /users/me does not exist)
area: backend
severity: minor
files:
  - src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java
  - src/main/java/com/vrudenko/kanban_board/controller/UserController.java
---

## Problem

Quick task 260812-hs4 fixed `POST /signup`'s `Location` header to point at
`${server.servlet.context-path}/users/me` (the caller-identity resource, per D-02/D-04) instead of
back at `/signup`. That target does not currently resolve: `UserController` only exposes
`GET /users/me/theme`, nothing at the bare `GET /users/me` path. Dereferencing the header today
returns a 404.

This is accepted as a strict improvement over the status quo, not a regression: today's
`/signup` `Location` also does not resolve (`GET /signup` doesn't exist either -- the route is
POST-only), and it is additionally meaningless (it names the route that created the resource, not
the resource itself). See 260812-hs4-PLAN.md's design rationale trade-off 7 and threat T-hs4-05
(accepted, not mitigated) for the full reasoning at the time this was decided.

Deliberately not fixed by adding the missing endpoint in that same task: a `GET /users/me`
identity endpoint was option 2 of the three options the *source* todo
(`2026-08-12-return-caller-identity-from-signin-and-signup-responses-for-.md`) considered, and
option 3 (change signin/signup's own response bodies) was explicitly chosen instead. Adding the
endpoint now, as a side effect of fixing the `Location` header, would blur that decision.

## Solution

Not scoped here. If a `GET /users/me` endpoint is ever added for an independent reason (a
BFF wanting to re-fetch identity without a fresh signin, for example), this `Location` header
will start resolving for free with zero further changes. If it is decided that this header should
resolve on its own merits, add a minimal `GET /users/me` handler to `UserController` mirroring the
existing `GET /users/me/theme` pattern.
