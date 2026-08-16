# Production Infrastructure Runbook

This document records the actual, currently-provisioned state of the production VM for v1.2's
Infra Migration milestone (Phase 5). It exists so a future session (human or agent) can answer
"what is this box, and how is it locked down" without re-deriving it from scratch. No secrets, no
private key material, and no connection strings are recorded here — see `.env.prod.example` for
the shape of what production actually needs, populated separately and never committed.

## Provider and host

| Field | Value |
|-------|-------|
| Provider | Netcup |
| Product | VPS Lite 2 G12s (ordered), shown in the Netcup SCP as "VPS 1000 G12" — Netcup's internal/panel naming differs from the marketing/order-page name; same underlying product, not a discrepancy in what was provisioned |
| Datacenter | Vienna, Austria |
| Public IPv4 | `159.195.114.230` |
| Public IPv6 | `2a0a:4cc0:61:67c::/64` |
| Hostname (Netcup-assigned) | `v2202608397723499373` |
| Spec (ordered) | 4 vCPU / 8 GB RAM / 160 GB NVMe SSD, hourly billing |
| Spec (as provisioned, measured) | 4 vCPU, 7.8 GiB RAM, 251 GB disk on `/dev/vda4` — disk exceeds the advertised 160 GB; RAM/CPU match |
| OS | Debian GNU/Linux 13 (trixie), kernel `6.12.101+deb13-amd64` |

**Provider history:** this VM replaces an originally-planned Oracle Cloud `eu-zurich-1`
`VM.Standard.A1.Flex` target. Oracle's Always Free ARM capacity in that region proved structurally
unavailable (200+ automated provisioning attempts across 10+ hours, zero successes — the region is
single-availability-domain with no ETA on capacity). See `.planning/phases/05-infra-migration/`
plan 05-03's SUMMARY for the full pivot rationale. **Architecture note:** Oracle's target was
ARM64/Ampere; Netcup's is x86_64. `.github/workflows/deploy.yml`'s Docker build step was originally
cross-compiled for `linux/arm64` via QEMU and has been corrected to build `linux/amd64` natively —
if any other artifact in this repo is found assuming ARM64, treat it as a bug from the same root
cause, not a new problem.

## Access

- SSH, key-only. `PasswordAuthentication no` and `PermitRootLogin prohibit-password` are both set
  in `/etc/ssh/sshd_config` — root login requires the key, password auth is rejected outright
  (verified: a forced-password-auth connection attempt gets `Permission denied (publickey)`).
- The server's `~/.ssh/authorized_keys` contains exactly one key, labelled
  `kanban-backend-prod-netcup` — a dedicated, no-passphrase ED25519 keypair generated specifically
  for this server (local path: `~/.ssh/id_ed25519_netcup_prod`), distinct from any personal admin
  key. No-passphrase is a deliberate trust-model choice enabling non-interactive automation, not an
  oversight — the private key file is itself a bearer credential for root access.
- A local `~/.ssh/config` `Host netcup-prod` entry wraps the IP/user/identity-file/`IdentitiesOnly`
  so `ssh netcup-prod` works without repeating `-i` — a plain `ssh root@<ip>` will fail with
  `Permission denied (publickey)` unless the client's default identity happens to match, since the
  server does not have any personal key installed.
- Docker access is root-only; no separate non-root deploy user or `docker` group member exists.

## Firewall — two independent layers

Both layers enforce the identical policy: allow inbound TCP 22 (SSH), 80 (HTTP, Let's Encrypt
challenge + redirect), 443 (HTTPS); default-deny everything else inbound. Neither layer opens
8080 (app), 8081 (Schema Registry), or 9092 (Kafka) — those stay internal-only, reachable only over
the Docker Compose network once the stack is deployed.

### Layer 1: OS-level (`iptables`, `nft` backend)

```
Chain INPUT (policy DROP)
ACCEPT  ctstate RELATED,ESTABLISHED
ACCEPT  in lo
ACCEPT  tcp dpt:22
ACCEPT  tcp dpt:80
ACCEPT  tcp dpt:443
```

Persisted via `iptables-persistent`/`netfilter-persistent` (`netfilter-persistent save`, rules live
in `/etc/iptables/rules.v4`). Verified to survive a full reboot. No ICMP allow rule exists at this
layer by design — the plan's spec only calls for TCP 22/80/443, so this box does not answer `ping`
even though it is fully reachable on those three ports.

### Layer 2: Netcup Cloud Firewall (SCP-managed, stateful)

Configured as a policy named "Default" ("Basic firewall policy") in the Netcup SCP's Firewall
Policies section, assigned to this specific VPS, positioned before the implicit system
`Drop all INCOMING` catch-all. Rules evaluate top-to-bottom, first match wins. Netcup's own
built-in `netcup Mail block` (drops outgoing SMTP/SMTPS/submission — unrelated to this app) and
`netcup Ping allow` (ICMP accept both directions) policies sit ahead of ours in evaluation order
and do not affect ports 22/80/443.

**Known gotcha, observed 2026-08-14:** immediately after first assigning this policy to the VPS,
the server became completely unreachable — not just SSH, but ICMP too — for over 7 minutes, despite
the SCP displaying what was (and remains) a correct ruleset. Toggling the panel's "Firewall active"
switch off restored access instantly; toggling it back on then worked correctly and has stayed
stable since. This points at a stuck sync/propagation state on Netcup's side when a policy is
first assigned, not a rule-configuration mistake. **If this VM (or a future one) ever goes
unexpectedly unreachable right after a Netcup Cloud Firewall change, try an off/on toggle cycle
before assuming the ruleset itself is wrong.**

## Verified state (2026-08-14)

- Docker: `29.7.2` (`docker-ce`, official `download.docker.com/linux/debian` apt repo, not the
  `docker.io` Debian package), `docker-compose-plugin` `v5.4.0` (Compose V2), `docker-buildx-plugin`
  `0.36.1`. `docker.service` enabled and active; survives reboot.
- External port probe (from off-VM): 22 open; 80, 443, 8080, 8081, 9092 all closed/filtered. 80/443
  being closed is expected at this stage — nothing is listening yet (Caddy/the app deploy in a
  later plan, 05-04) — this probe confirms the firewall layers, not the eventual app.
- Both firewall layers independently verified to allow 22 and nothing else currently listening.

## Database — Neon

| Field | Value |
|-------|-------|
| Project name | `kanban-board-db` |
| Project ID | `floral-union-23715140` |
| Organization | Rudenko Vladimir (`org-red-moon-37279582`) |
| Region | `aws-eu-central-1` (Frankfurt) — closest Neon-offered region to the Netcup VM's Vienna datacenter; Neon has no Austria region |
| Postgres version | 18 |
| Compute | `ep-delicate-bird-b2lni8pr`, autoscaling 0.25–2 CU, `suspend_timeout_seconds: 0` (scale-to-zero) |
| Direct host | `ep-delicate-bird-b2lni8pr.c-6.eu-central-1.aws.neon.tech` |
| Pooled host | `ep-delicate-bird-b2lni8pr-pooler.c-6.eu-central-1.aws.neon.tech` (pooler mode: transaction) |
| Database state | Empty — no schema, no data, confirmed via `get_database_tables` — Flyway owns schema creation on first deploy |

No connection string, user, or password is recorded here — see `.env.prod.example` for the shape
of what production needs; the real values live only in `.env.prod` on the VM (never committed),
populated during plan 05-04.

**Resolved in 05-04 Task 1:** `DB_HOST` in `.env.prod` is the **direct** (non-`-pooler`) host,
confirmed by the app's own boot log (`jdbc:postgresql://ep-delicate-bird-b2lni8pr.c-6.eu-central-1.aws.neon.tech:5432/neondb`).
The transaction-mode pooler concern below was correct — Flyway's session-scoped advisory lock at
boot is unsupported under it — and this single-instance app's small HikariCP pool (max 5) gets
nothing from the pooler's multiplexing anyway, so there was no reason to fight it. This is a
documented divergence from INFRA-02's literal "pooled" wording, not an oversight.

## DNS — DuckDNS

| Field | Value |
|-------|-------|
| Subdomain | `kanban-board-rud-vlad-473.duckdns.org` |
| A record | `159.195.114.230` (the Netcup VM's public IPv4) |
| Verified (2026-08-14) | Resolves correctly via both the local resolver and Google's public DNS (`8.8.8.8`) — not a stale/cached answer. Port 22 reachable through the domain (confirms it correctly routes to the VM, not just resolves); 80/443 closed, matching the "nothing deployed yet" expectation exactly. |
| Dynamic-update note | The Netcup VM's IP is static for this deployment's lifetime — no DuckDNS auto-updater client/cron/token is running. If the VM is ever re-provisioned with a new IP, the A record must be updated manually. |

Not yet attempted: certificate issuance — that's plan 05-04's job once Caddy is actually deployed
and can run its own HTTP-01 challenge against this hostname.

## Manual deploy — Plan 05-04 Task 1 (2026-08-16)

The full stack is live: `caddy`, `app`, and `redpanda` all running, `app` and `redpanda` reporting
`(healthy)` (`caddy` has no healthcheck defined, so plain `running` is its correct state). One real
HTTPS request from the public internet reaches the application over a Let's Encrypt certificate,
and a write made through the public API survives an app container restart.

**Execution note:** this task was executed by the agent directly over the human's own SSH session
(`ssh netcup-prod`), not hand-typed by the human as the plan's execution constraint originally
specified — the human explicitly authorized this mid-session after confirming SSH connectivity,
trading the plan's default human-in-the-loop safety net for speed. No `.env.prod` contents,
connection strings, or credentials were read or requested at any point.

### Sequence, in order

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

### Deviations from the prepared instructions, and why

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

### Not yet done (tracked in `.planning/phases/05-infra-migration/`)

- Task 3: measure actual resource usage under load and correct Redpanda's memory/SMP caps against
  the verified shape.
- Re-point CI/CD (`.github/workflows/deploy.yml`'s disabled `deploy-to-ec2` job) at this host —
  plan 05-05.

## Manual deploy — Plan 05-04 Task 2 (2026-08-16) — Schema Registry cutover verification

The application's Schema Registry configuration was already effectively repointed to production as
a side effect of Task 1's deploy — `docker-compose.prod.yml`'s `app` service sets
`SCHEMA_REGISTRY_URL: http://redpanda:8081` (the internal Compose network address), and both the
producer and consumer properties in `application.properties` resolve that same environment variable
with an identical `localhost:8081` local-dev fallback:

```
spring.kafka.producer.properties.schema.registry.url=${SCHEMA_REGISTRY_URL:http://localhost:8081}
spring.kafka.consumer.properties.schema.registry.url=${SCHEMA_REGISTRY_URL:http://localhost:8081}
```

This task therefore made **zero `src/main` changes** — it is a verification task confirming that
repointing, not a config task performing it.

### Confirmed: no hardcoded registry URL, symmetric subject-name strategy

- `grep -rn "schema.registry.url" src/main --include=*.java` → no matches (exit 1). The only
  literal `"http://localhost:8081"` in `src/main` is `AvroSchemaRegistrar`'s documented CLI-arg
  fallback default (`DEFAULT_SCHEMA_REGISTRY_URL`), used only when neither a CLI arg nor
  `SCHEMA_REGISTRY_URL` is supplied — never reached in production, where the Compose manifest
  always supplies the env var.
- `grep -n "subject.name.strategy" src/main/resources/application.properties` → both producer
  (line 154) and consumer (line 182) resolve the identical value,
  `io.confluent.kafka.serializers.subject.RecordNameStrategy`.

### Production registry state, queried live from inside the VM

Via `docker compose --env-file ./.env.prod -f docker-compose.prod.yml exec redpanda ...`:

- `rpk registry subject list` → 14 subjects (`com.vrudenko.kanban_board.event.avro.Avro*Event`,
  one per `ActivityEvent` type). **Same staleness finding as Task 1's schema-registration step**:
  `04-VERIFICATION.md` (written 2026-08-04) and this plan's own text both say 5 — both predate
  quick task `260811-s5e`'s expansion from 6 to 14 event types. Verified against the live registry,
  not assumed from either document.
- `curl http://localhost:8081/config/com.vrudenko.kanban_board.event.avro.AvroTaskMovedEvent` →
  `{"compatibilityLevel":"BACKWARD"}` — matches `04-VERIFICATION.md`'s recorded compatibility level
  exactly (that report's evidence used the short class name; the actual registered subject is the
  full name per `RecordNameStrategy`, an abbreviation in the report's prose, not a discrepancy in
  what's registered).
- Live BACKWARD enforcement re-proven directly against the registry's read-only
  `/compatibility/subjects/{subject}/versions/latest` dry-run endpoint (no mutation to registry
  state): a modified `AvroTaskMovedEvent` schema with one new required field (no default) added →
  `{"is_compatible":false}`; the unchanged schema submitted against itself → `{"is_compatible":true}`.
  This is the same reject/accept pair `SchemaCompatibilityE2ETest` asserts, exercised via the
  registry's real HTTP API instead of through JUnit.
- Off-VM reachability re-confirmed closed: `Test-NetConnection 159.195.114.230:8081` →
  `TcpTestSucceeded: False`. No port was published and no firewall rule was added or changed to
  make this task's verification easier.

### How the suite reached the registry — decision and why neither of the plan's two offered options was used as-is

The plan's action text offered two options for reaching the internal-only production registry:
running the suite on the VM itself, or an SSH tunnel from the machine running the tests. Neither
turned out to be sufficient on its own, for a reason specific to this codebase's test harness, not
a networking problem: `AbstractKafkaContainerTest` (the shared base class for all five named test
classes) always provisions its **own** ephemeral Testcontainers-managed Redpanda broker+registry
per test-class JVM run — `kafka.start()` in a static initializer, followed by an unconditional
`@DynamicPropertySource` that wires `spring.kafka.{producer,consumer}.properties.schema.registry.url`
straight to that container's own mapped address (`kafka.getSchemaRegistryAddress()`). There is no
existing hook to redirect this at an external, already-running registry — `@DynamicPropertySource`-
registered properties take precedence over everything else Spring resolves, including CLI `-D`
system properties, so no external override is possible without changing this shared class. Neither
running the JVM on the VM nor tunnelling a port to it changes this: the harness would still spin up
its own container regardless of where the JVM runs or what is reachable over the network.

Building a redirect hook (an opt-in env-var override that skips the Testcontainers startup) would
be a real, working fix, but it is a change to shared test infrastructure — outside this task's
declared file scope (`src/main/resources/application.properties`, `docs/INFRA_RUNBOOK.md`) — and
running it would additionally require standing up a full JDK/Gradle build environment on the live
production VM (an image pull, a source checkout, and a live build against production infrastructure)
for a one-time verification. That is a materially larger and riskier undertaking than the
acceptance criteria's actual intent, which is proving the production registry behaves the way Phase
4 verified, not proving these specific JUnit classes can be pointed at an arbitrary external broker.

**Decision:** the three verifications above (direct-API subject/compatibility/enforcement queries
against the live production registry, a live public-API mutation through the real production
pipeline, and a full local regression run of the five named classes against a fresh
Testcontainers-provisioned registry) together cover everything the JUnit-against-production
requirement was meant to prove, without either modifying shared test infrastructure or building a
build toolchain onto the production box. Recorded here as a documented deviation, not a silent
reinterpretation.

### Local regression run of the five named test classes (Testcontainers, not production)

`./gradlew test --tests '*SchemaCompatibilityE2ETest' --tests '*ActivityLogAvroDeadLetterE2ETest'
--tests '*ActivityEventAvroMapperTest' --tests '*SchemaRegistryOutageE2ETest' --tests
'*HistoricalActivityEventReconstructorTest'` — all five classes' JUnit XML: `failures="0"
errors="0"` across every nested group (28 tests total: `SchemaCompatibilityE2ETest` 3,
`ActivityLogAvroDeadLetterE2ETest` 3, `ActivityEventAvroMapperTest` 15,
`SchemaRegistryOutageE2ETest` 1, `HistoricalActivityEventReconstructorTest` 6). The build's overall
`jacocoTestCoverageVerification` task failed on this filtered run (0.41 instructions covered vs. a
0.90 minimum) — expected and not a regression: that gate is sized against the full suite, and this
run deliberately executed only 5 of the codebase's test classes. No test itself failed.

### Live pipeline proof against production

Signed up a fresh user (`tracer-task2-260816@example.com`) through the public HTTPS API, created a
board (`Task2 Registry Tracer Board`, id `8o6ahdxw6k8w`), then queried
`GET /api/boards/8o6ahdxw6k8w/activity`: returned one row, `action: BOARD_CREATED`, `userId`
matching the new user — proving the mutation was Avro-serialized against the production registry by
the producer, and Avro-deserialized and persisted by the consumer, end-to-end through the real
broker and registry, not merely configured to.

## Maintenance note

If the provider, IP, OS, spec, or firewall policy changes, update this document in the same
change — it is the single checked-in description of what the production host actually is, as
opposed to what any given plan intended it to be.
