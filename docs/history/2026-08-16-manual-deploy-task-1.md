# Manual deploy — Plan 05-04 Task 1 (2026-08-16)

The full stack is live: `caddy`, `app`, and `redpanda` all running, `app` and `redpanda` reporting
`(healthy)` (`caddy` has no healthcheck defined, so plain `running` is its correct state). One real
HTTPS request from the public internet reaches the application over a Let's Encrypt certificate,
and a write made through the public API survives an app container restart.

**Execution note:** this task was executed by the agent directly over the human's own SSH session
(`ssh netcup-prod`), not hand-typed by the human as the plan's execution constraint originally
specified — the human explicitly authorized this mid-session after confirming SSH connectivity,
trading the plan's default human-in-the-loop safety net for speed. No `.env.prod` contents,
connection strings, or credentials were read or requested at any point.

## Sequence, in order

1. **Copy artifacts to the VM** (`docker-compose.prod.yml`, `Caddyfile` via `scp`; `.env.prod`
   created directly on the VM by the human from `.env.prod.example`, populated with the direct
   Neon endpoint, real domain, and image tag — never committed, never pasted into any session).
2. **Bring up Redpanda:**
   `docker compose --env-file ./.env.prod -f docker-compose.prod.yml up -d redpanda`
   — waited for `(healthy)` via `rpk cluster health`.
3. **Register the 14 Avro schemas** as a one-off container on the VM's own Compose network,
   reusing the already-pulled `app` image rather than tunneling a local `./gradlew registerSchemas`
   through SSH — reaches `redpanda` by its Compose service name directly, no port published, no
   tunnel needed:
   ```
   docker compose -f docker-compose.prod.yml --env-file ./.env.prod run --rm --entrypoint java app \
     -Dloader.main=com.vrudenko.kanban_board.config.AvroSchemaRegistrar \
     -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher http://redpanda:8081
   ```
   Spring Boot's `PropertiesLauncher` (shipped unpacked at the jar root of every Boot fat jar)
   launches a different main class than the manifest's declared one — a documented but
   previously-untested-in-this-repo technique. Worked on the first attempt: `Registered 14 Avro
   schemas against http://redpanda:8081`. Verified independently (not just trusting the log line)
   via `docker compose exec redpanda rpk registry subject list` — returned exactly 14 subjects,
   one per `ActivityEvent` type. **Deviation from plan text:** `05-04-PLAN.md` and `STATE.md` both
   say "5 subjects" — stale, predates quick task `260811-s5e`'s expansion from 6 to 14 event types.

   **Superseded by Plan 09-03 (2026-08-19):** this step now runs automatically on every push to
   `master`, via the `register-schemas-production` job in `.github/workflows/deploy.yml` — the
   identical invocation shown above, run by CI instead of by hand. See "## Automated Avro schema
   registration — Plan 09-03" below for the full detail. The command above remains documented
   (not deleted) because it is still the correct procedure for a manual bring-up or a recovery
   from a wiped registry volume — this is a historical record of what was actually executed, per
   this file's own convention.
4. **Re-confirmed iptables 80/443** (`sudo iptables -L INPUT -n -v --line-numbers`) before starting
   Caddy — asked for earlier in the session but never actually pasted back at the time; confirmed
   present this time (rules 3–5: `tcp dpt:22`, `tcp dpt:80`, `tcp dpt:443`, all `ACCEPT`, ahead of
   the default-`DROP` policy).
5. **Started the app:** `docker compose --env-file ./.env.prod -f docker-compose.prod.yml up -d app`.
   Booted in 11.2s, reported `(healthy)`. Flyway applied migrations **V1 through V7** against the
   genuinely empty Neon database — **deviation from plan text:** `05-04-PLAN.md`/`STATE.md` say
   "V1-V4"; stale, predates migrations V5 ("add position subtask version theme board name
   uniqueness"), V6 ("change activity log event id to varchar"), V7 ("add board optimistic locking
   version column") added by later quick tasks. `flyway_schema_history` shows 7 successful rows,
   not 4 — this is the plan text being outdated, not a deploy defect.
6. **Started Caddy:** `docker compose --env-file ./.env.prod -f docker-compose.prod.yml up -d caddy`.
   Obtained its Let's Encrypt certificate for `kanban-board-rud-vlad-473.duckdns.org` on the
   **first** attempt (HTTP-01 challenge, ~6 seconds from request to `certificate obtained
   successfully` in the container log) — no rate-limit risk incurred.
7. **Verified end-to-end from off-VM:**
   - `curl https://kanban-board-rud-vlad-473.duckdns.org/api/actuator/health` → `200`,
     `{"status":"UP"}`.
   - Certificate issuer (via `openssl s_client` + `x509 -noout -issuer`): `O=Let's Encrypt, CN=YE1`,
     subject matches the hostname, accepted by curl's default trust store with no `-k` flag needed
     (genuinely publicly trusted, not self-signed).
   - Plain `http://` request → `308` redirect to the `https://` URL, no content served over plain
     HTTP.
8. **Proved the database path is real:** signed up a user (`tracer-deploy-260816@example.com`) and
   created a board (`Tracer Deploy Board`, id `8o5uls3ouvpc`) through the public HTTPS API,
   restarted the `app` container (`docker compose restart app`), waited for `(healthy)` again
   (24s), then re-fetched `/api/boards` with the *same* session cookie — board still present,
   `200`. The session itself also survived the restart unprompted, a live confirmation that
   `spring.session.store-type=jdbc` is really persisting sessions to Neon and not just
   configured to.

## Deviations from the prepared instructions, and why

- **`.env.prod` root cause:** Docker Compose only auto-loads a file literally named `.env`; a file
  named `.env.prod` sitting in the same directory is silently ignored unless `--env-file` is passed
  explicitly. Every one of the file's 7 variables showed as an unset/blank-string warning until
  `--env-file ./.env.prod` was added to the command line. Not an `export`-prefix issue and not a
  Compose v1/v2 mismatch (both hypotheses considered and ruled out) — simpler than either.
- **Schema registration mechanism:** the plan anticipated an SSH tunnel from the operator's local
  machine running `./gradlew registerSchemas`. Executed differently (Step 3 above) because the
  agent had direct root SSH access this session, making a same-VM, same-Compose-network one-off
  container both simpler and lower-risk (no tunnel, no container-IP lookup, no port ever opened).
- **Stale plan numbers:** both the expected subject count (5 → 14) and the expected migration count
  (4 → 7) in `05-04-PLAN.md`/`STATE.md` predate later quick tasks. Verified against the live
  registry/database rather than the plan text, per this task's own instruction not to assume the
  plan was right.

## Not yet done (tracked in `.planning/phases/05-infra-migration/`)

- Task 3: measure actual resource usage under load and correct Redpanda's memory/SMP caps against
  the verified shape.
- Re-point CI/CD (`.github/workflows/deploy.yml`'s disabled `deploy-to-ec2` job) at this host —
  plan 05-05.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
