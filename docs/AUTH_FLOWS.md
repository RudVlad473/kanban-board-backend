# Authentication Flows

This document is for a **frontend or QA engineer writing tests against this API**, on the
assumption of no JVM or Spring background. It answers one question: *what does a client actually
observe when it calls the two authentication routes, and what will silently break an automated
test suite driving them?* It is a **Scenarios (+1)** view per
[DIAGRAM_CONVENTIONS.md](DIAGRAM_CONVENTIONS.md) -- end-to-end, client-observable, traced at the
HTTP boundary rather than through Spring's internal machinery.

[docs/ARCHITECTURE.md](ARCHITECTURE.md) already carries a signin sequence diagram, drawn for a
different reader and a different question: a *security reviewer* asking "is this endpoint safe?",
naming findings by id (F1, F6, D-08) and drawing the BCrypt timing equalizer and the accepted
TOCTOU window in detail. That diagram stays the authoritative security-review artifact. This
document is complementary, not competing -- it draws the same underlying code from the client's
side of the wire, adds `POST /api/signup` (not drawn there at all), and closes with the
session/cookie/CORS facts a Playwright suite needs and a security review does not.

## Sign up

**`POST /api/signup`** -- one of only two routes reachable without a session (the other is
`/signin`).

### Request body

| Field | Required | Constraint |
|---|---|---|
| `email` | yes | a syntactically valid email address |
| `password` | yes | 8-64 characters; at least one uppercase letter, one lowercase letter, one digit, and one special character |
| `displayName` | no | if present, must be non-blank and 3-32 characters |

### Responses

| Status | Code | Cause |
|---|---|---|
| `201` | -- | Account created and immediately signed in |
| `400` | `VALIDATION_FAILED` | Request body fails bean validation (runs before the handler method body) -- response carries a per-field `errors` map |
| `409` | `DUPLICATE_RESOURCE` | `email` is already registered (the checked, expected path) |
| `409` | `DATA_INTEGRITY_VIOLATION` | A race between two simultaneous signups for the same address -- the database's unique constraint backstops the checked guard above; the loser of the race lands here instead |
| `401` | `BAD_CREDENTIALS` | The account was created, but automatically signing it in immediately afterward failed for any reason -- the just-created account is rolled back (deleted) and the client sees a generic credentials failure, not a 403, despite an intermediate access-denied exception internally |

![Sequence diagram: signup and auto-authentication](diagrams/auth-signup-scenario.png)
<sub>[diagram source](diagrams/auth-signup-scenario.mmd)</sub>

**What this means for a test:** two traps are easy to miss. First, the `201`'s `Location` header
names `/api/users/me`, and that route has no `GET` handler yet -- a test that follows the
`Location` header will fail, not because of a test bug. Second, running the same signup request
twice against a database that is not reset between runs will hit the `409` arm on the second run
rather than the `201` arm the first time produced -- a suite that assumes a clean signup on every
run must either randomize the email address per run or reset state between runs.
