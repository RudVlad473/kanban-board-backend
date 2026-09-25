# Nonprod reset endpoint — Plan 08-02 (2026-08-18)

The profile-gated `POST /api/admin/reset` endpoint built in plan 08-02 (`ResetController` +
`ResetService` + `ResetTruncateService` + `NonprodResetSecurityConfiguration`, all
`@Profile("nonprod")`) was rolled onto the live nonprod stack from "Nonprod bring-up — Plan
08-01" above and proven end-to-end against the real deployed hostname: a correct-token call
genuinely empties both nonprod's Postgres tables and its Kafka activity topics, a wrong or
absent token is rejected identically, the same call against production does not return 204, and
the Kafka consumer survives the reset. Every observation below is a captured live command output
from this rollout, not an inference from the code.

## Rollout sequence, in order

1. **Reset token generated on the VM, never off it:** `openssl rand -base64 48 | tr -d '\n'`
   executed over SSH as `deploy`, the resulting 64-character value written directly into
   `/opt/deploy/kanban-board-nonprod/.env.nonprod` as `APP_RESET_TOKEN` via a remote `sed`
   substitution that never round-tripped the value through this local session. Confirmed
   afterward: `stat -c '%a %U:%G' .env.nonprod` -> `600 deploy:deploy`; the token's length was
   checked (`64`) without ever printing its value.
2. **`IMAGE_TAG` set to this plan's commit's published tag** (`777cb27`, the tag CI run
   `32141273073` built and pushed to Docker Hub after Tasks 1-2 merged to `master`) and only
   `app-nonprod` rolled:
   `docker compose -f docker-compose.nonprod.yml --env-file ./.env.nonprod --profile nonprod up -d app-nonprod`.
   `kanban-nonprod-app` reached `(healthy)` on the first health check cycle.
3. **Profile activation confirmed from the boot log**, not assumed from the env file:
   `docker logs kanban-nonprod-app | grep -i profile` ->
   `The following 1 profile is active: "nonprod"`.
4. **Real state created through the public nonprod API** (signup -> board -> column -> task ->
   subtask, each response `201 Created`), then the activity feed read back:
   `GET /api/boards/{boardId}/activity` returned `totalElements: 4`
   (`BOARD_CREATED`, `COLUMN_CREATED`, `TASK_CREATED`, `SUBTASK_CREATED`) — proof the Kafka round
   trip was live and there was genuinely something to clear. Independently queried table counts
   immediately before the reset call (via a one-off `postgres:17-alpine` container on the VM
   reading `.env.nonprod`'s own environment substitution, the same technique "Nonprod bring-up —
   Plan 08-01" used): `users=2 boards=2 columns=2 tasks=1 subtasks=1 activity_log=6
   spring_session=5 spring_session_attributes=5` (the `2`s/`5`s over this task's own `1` new
   signup/board reflect residual fixture rows still live from plan 08-01's own isolation-audit
   writes, never cleaned up before this task — itself proof the target state was not artificially
   pre-emptied for this test). `flyway_schema_history` (`success = true`) held `7` rows.

## Live curl contract

Correct token, from off-VM:
```
curl -isS -X POST -H "X-Reset-Token: <APP_RESET_TOKEN>" \
  https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api/admin/reset
```
```
HTTP/1.1 204 No Content
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Via: 1.1 Caddy
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
X-Xss-Protection: 0

```
No `Set-Cookie` header, empty body.

Wrong token:
```
curl -isS -X POST -H "X-Reset-Token: <wrong value>" \
  https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api/admin/reset
```
```
HTTP/1.1 403 Forbidden
Content-Type: application/problem+json
...
{"type":"about:blank","title":"Forbidden","status":403,"detail":"You do not have access to that nonprod reset endpoint","instance":"/api/admin/reset","code":"ACCESS_DENIED"}
```

No header at all:
```
curl -isS -X POST https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api/admin/reset
```
```
HTTP/1.1 403 Forbidden
Content-Type: application/problem+json
...
{"type":"about:blank","title":"Forbidden","status":403,"detail":"You do not have access to that nonprod reset endpoint","instance":"/api/admin/reset","code":"ACCESS_DENIED"}
```
Byte-identical status and JSON body between the wrong-token and absent-header responses — only
the `Date` header differed. No header-presence oracle.

## Independent verification (Postgres + Kafka)

After the correct-token call, all eight tables were re-queried the same way as the "before"
reading:

| Table | Before | After |
|---|---|---|
| `users` | 2 | 0 |
| `boards` | 2 | 0 |
| `columns` | 2 | 0 |
| `tasks` | 1 | 0 |
| `subtasks` | 1 | 0 |
| `activity_log` | 6 | 0 |
| `spring_session` | 5 | 0 |
| `spring_session_attributes` | 5 | 0 |
| `flyway_schema_history` (`success = true`) | 7 | 7 (unchanged) |

`rpk topic describe kanban.activity -p` inside `kanban-nonprod-redpanda`:
```
PARTITION  LEADER  EPOCH  REPLICAS  LOG-START-OFFSET  HIGH-WATERMARK
0          0       2      [0]       6                 6
```
`rpk topic describe kanban.activity.dlt -p`:
```
PARTITION  LEADER  EPOCH  REPLICAS  LOG-START-OFFSET  HIGH-WATERMARK
0          0       2      [0]       0                 0
```
Both partitions' log-start offset equals their high watermark (the DLT topic had never carried a
record, and the reset correctly treated that as already-empty rather than failing).

## Production negative result

Same path, production hostname, arbitrary token:
```
curl -isS -X POST -H "X-Reset-Token: <arbitrary>" \
  https://kanban-board-rud-vlad-473.duckdns.org/api/admin/reset
```
```
HTTP/1.1 401 Unauthorized
Content-Type: application/problem+json;charset=ISO-8859-1
...
{"type":"about:blank","title":"Unauthorized","status":401,"detail":"Authentication is required","instance":"/api/admin/reset","code":"UNAUTHENTICATED"}
```
Not `403 ACCESS_DENIED` (nonprod's answer to a bad token) and not `204` — production's own
catch-all `SecurityConfiguration` chain's `anyRequest().authenticated()` rule answers the
request instead, because no `ResetController`/`NonprodResetSecurityConfiguration` bean exists in
that context at all. Production's health endpoint: `GET /api/actuator/health` -> `200`
throughout. Production's own `users`/`boards` row counts were independently queried
(`3`/`2`) and matched "Nonprod bring-up — Plan 08-01"'s recorded baseline exactly — unchanged by
any part of this task.

## Consumer survival and idempotency

A fresh user (the prior session's row had just been truncated) signed up and created one more
board (`POST /api/boards`) after the first reset; `GET .../activity` on that new board returned
`totalElements: 1` with a `BOARD_CREATED` event timestamped after the reset — the
`@KafkaListener` container was restarted, not left stopped. Reset was then called two further
times: once against that one-board state (`204`), and once immediately after against the
already-empty result of that call (`204` again) — idempotent both against real remaining state
and against a store already at zero. Final independent re-query: all eight tables `0`,
`flyway_schema_history` still `7`, `kanban.activity` log-start/high-watermark both `7` (one more
event trimmed in by the second reset), `kanban.activity.dlt` still `0`/`0`.

## Operator note — token rotation and production's structural exclusion

The reset token lives only in `/opt/deploy/kanban-board-nonprod/.env.nonprod` (mode `600`, owned
by `deploy`) — nowhere else, not in this repository, not in any CI secret. Rotating it is a
one-line edit to that file (`APP_RESET_TOKEN=<new 32+ character value>`) followed by
`docker compose -f docker-compose.nonprod.yml --env-file ./.env.nonprod --profile nonprod up -d
app-nonprod` to pick it up; the endpoint's `@PostConstruct` guard refuses to start if the new
value is blank or under 32 characters, so a bad rotation fails loudly at boot rather than
silently accepting a weak secret. The endpoint cannot be reached in production under any token
value because the bean is not registered there at all (`@Profile("nonprod")` on the controller,
both services, and the security chain) — this task's production probe above demonstrates that
structurally, not merely by policy: the request never reaches a token comparison, it falls
straight into production's pre-existing, byte-identical `SecurityConfiguration` chain.

## Rollout date

2026-08-18.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
