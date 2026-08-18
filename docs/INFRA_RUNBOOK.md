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

## Manual deploy — Plan 05-04 Task 3 (2026-08-16) — Redpanda resource caps, measured

### Workload used to measure

A 54-request burst fired in rapid sequential succession through the public HTTPS API against the
live production stack: 6 columns, 24 tasks (4 per column), 24 subtasks (1 per task) — each
mutation producing a real Avro-serialized Kafka publish against the production registry and a
real consumer-side persist. Not an idle-stack measurement; not a synthetic load-test tool, since
this app's real traffic shape (a personal/portfolio kanban board) is what the caps need to be
correct for, not a stress-test peak.

### Measured figures

| | Idle baseline | Under burst (6 samples) |
|---|---|---|
| `redpanda` RSS | 347.8MiB (~17% of its 2G internal cap) | unchanged at 347.8MiB |
| `redpanda` CPU | 0.43% | peaked 7.3-8.6% (of one `--smp 1`-pinned core, not the host's 4) |
| `app` RSS | 458.6MiB (~14.9% of its 3g `mem_limit`) | grew ~12MiB to 471.1MiB (~15.3%) |
| `app` CPU | 0.26% | peaked 3.5-6.2% |
| `caddy` RSS/CPU | 19.9MiB / 0.00% | unchanged (~20MiB, <0.2%) |
| Host memory | 1.3GiB used / 6.4GiB available of 7.8GiB total | unchanged — this workload does not move the needle at the host level |

Shape measured against: Netcup VPS Lite 2 G12s, 4 vCPU / 7.8GiB RAM (05-03's verified figure).

### Correction made

The `--smp 1` / `--memory 2G` values themselves were **left unchanged** — the measurement showed
redpanda using under 18% of its memory cap and a small fraction of its single-core CPU ceiling even
under this burst, so there was no measured basis to tighten or loosen either value. The prior
budget math (redpanda 2G + app 3G = 5G, leaving 2.8G of the 7.8GiB host for Caddy + the OS) is now
measurement-verified rather than merely asserted; `docker-compose.prod.yml`'s comment above the
broker command block was rewritten from "floor pending Task 3" to the measured basis above.

One genuine gap was found and closed: the `redpanda` service had no Docker-level cgroup `mem_limit`
at all — only Redpanda's own internal `--memory 2G` (Seastar's own allocator accounting), with no
second backstop the way the `app` service already has (`mem_limit: 3g`). 05-RESEARCH.md's Pitfall 1
explicitly recommends cgroup limits on *both* co-resident containers. Added `mem_limit: 2200m`.

**Found live, by trying the obvious value first and it breaking startup:** setting `mem_limit`
numerically equal to `--memory 2G` (i.e. `mem_limit: 2g`) made the container fail every restart
attempt: `Could not initialize seastar: std::runtime_error (insufficient physical memory: needed
2147483648 available 2078277632)`. A cgroup limit exactly equal to Seastar's own requested usable
memory leaves no room for cgroup accounting overhead — Seastar's own probe saw ~66MiB less than the
2048MiB it asked for at an exactly-2GiB cgroup ceiling, and refused to start smaller rather than
silently degrade. `2200m` (~150MiB of headroom above the internal request) fixed it — confirmed by
a live restart reaching `(healthy)` within the existing 15s `start_period`. This is exactly the "a
cap that makes the broker unhealthy is a failed correction, not a tighter one" case the plan's own
`<done>` criterion names — caught and fixed before being left in that state, not shipped broken.

Real worst-case host budget with the new backstop in place: `app` 3G + `redpanda` 2200m = ~5.15G,
leaving ~2.65GiB of the measured 7.8GiB host for Caddy + the OS.

### Re-verification after the fix

- `docker compose -f docker-compose.prod.yml ps`: `app` `Up (healthy)`, `caddy` `Up`, `redpanda`
  `Up 21s (healthy)` — recreated cleanly on the corrected `mem_limit`.
- Off-VM: `curl -o /dev/null -w '%{http_code}' https://kanban-board-rud-vlad-473.duckdns.org/api/actuator/health`
  → `200`, confirming the end-to-end HTTPS path from Task 1 still works after the restart.
- `rpk registry subject list` after the restart still returns 14 subjects — the named Docker volume
  (`redpanda-data`) persisted the registry's data across the container recreation, not just the
  broker process restarting clean.
- Post-fix `docker stats`: `redpanda` `345.5MiB / 2.148GiB` (`MEM USAGE / LIMIT` now correctly
  shows the new cgroup ceiling instead of the host's full 7.759GiB, confirming the limit is
  actually in effect, not merely present in the YAML).
- No memory- or CPU-constrained finding surfaced that the caps couldn't resolve — there was nothing
  to work around beyond the `mem_limit`-value fix above.
- `grep -v '^\s*#' docker-compose.prod.yml` shows explicit `mem_limit`, `--smp`, `--memory` and
  `--overprovisioned` values for the broker.
- `grep -rc "spring.threads.virtual" src/main/resources/` → 0 across every file; no
  virtual-threads setting was introduced to compensate for anything.

## Deploy user setup — Plan 05-05 Task 1 (2026-08-16)

A dedicated, minimally-privileged deploy identity was created for GitHub Actions, replacing the
root-only access model this VM had until now (see "Access" above — before this, there was no
non-root user and no `docker` group member at all).

### What was done

- New user `deploy`, created via `adduser --disabled-password --gecos ""` (no password login), added
  to the `docker` group (`usermod -aG docker deploy`). Confirmed it can run `docker compose` and
  cannot `sudo` without a password (`sudo -n true` fails as expected).
- A new, dedicated ed25519 keypair (`netcup_deploy_key`) generated on the operator's own machine —
  never on the VM — and its public half appended to `/home/deploy/.ssh/authorized_keys`. Distinct
  from the pre-existing personal admin key (`id_ed25519_netcup_prod`); either can be revoked
  independently.
- The deploy artifacts (`docker-compose.prod.yml`, `Caddyfile`, `.env.prod`) were moved from
  `/root/` (where Plan 05-04's manual deploy had left them, since that deploy pre-dates this
  dedicated-user setup) to a new `/opt/deploy/kanban-board-backend/` directory, owned by `deploy`.
  `/root` is not an appropriate directory to hand ownership of to a second user.

### Deviation found and fixed: Compose project name was directory-derived

**What went wrong:** Docker Compose derives its project name (and, from that, every named volume's
actual name) from the CWD basename unless pinned explicitly. The containers Plan 05-04 started from
`/root` were named `root-app-1`/`root-caddy-1`/`root-redpanda-1` under project `root`, with volumes
`root_redpanda-data`/`root_caddy-data`/`root_caddy-config`. Moving the compose file to
`/opt/deploy/kanban-board-backend/` silently changed the *default* project name to
`kanban-board-backend` for any future `docker compose up` run from there — which would create a
**second, unrelated** set of containers and fresh, empty named volumes rather than recognizing the
already-running ones.

**Caught by:** running `docker compose ps` as `deploy` from the new directory returned nothing for
containers that were demonstrably running (confirmed via plain `docker ps`), and the containers'
`com.docker.compose.project`/`working_dir` labels showed `root`/`/root` — not a permissions issue,
a genuine second project.

**Fix:** added a top-level `name: kanban-board-backend` key to `docker-compose.prod.yml` (Compose
Spec v2+), pinning the project name in the file itself, independent of whatever directory it's run
from. This is the same category of fix as the pre-existing `.env` vs `.env.prod` auto-load gotcha
documented above — never trust an implicit, directory/filename-derived default for something a
future move or rename can silently change.

**Cutover performed (real downtime, seconds-to-low-minutes, not the planned zero-downtime path):**
old `root`-project containers stopped and removed (`docker compose -p root ... down`); stack
brought up fresh under the pinned name. This created empty `kanban-board-backend_*` volumes,
**losing the 14 registered Avro schemas** (they remained intact in the now-orphaned
`root_redpanda-data` volume) — caught immediately via `rpk registry subject list` returning zero
subjects. Fixed by copying `root_redpanda-data`'s contents into `kanban-board-backend_redpanda-data`
via a one-off `alpine` container (`cp -a`), then restarting `redpanda` — `rpk registry subject list`
confirmed all 14 subjects back. Caddy also got a **fresh** Let's Encrypt certificate during this
cutover (its old cert/config volumes were likewise orphaned) — succeeded on the first attempt, no
rate-limit risk incurred, so its orphaned `root_caddy-*` volumes were left alone rather than risking
another cert request to "fix" something already working.

The orphaned `root_redpanda-data`, `root_caddy-data`, `root_caddy-config` volumes still exist on the
VM (not deleted) — safe to remove once this cutover has been running stable for a while, not done as
part of this task.

### A second incident during the same cutover: the app container's healthy restart

While bringing `app` back up immediately after the redpanda volume migration, it was explicitly
stopped as a side effect of `docker compose stop app redpanda` (issued together with redpanda,
since `app` depends on the registry). The very next `docker compose up -d app` attempt appeared to
leave it stopped rather than restarting — most likely `app`'s `depends_on: redpanda: condition:
service_healthy` racing a `redpanda` that had only been up ~10 seconds and not yet reported healthy.
Resolved by explicitly confirming `redpanda` was `(healthy)` first, then re-running
`docker compose up -d app` and observing it continuously for over 2 minutes (past the ~90-second mark
where the previous instance died) before considering it stable. Verified via `docker events`/`docker
inspect` (not assumed) that the earlier stop was this session's own command and not an external
process — no cron job, systemd timer, or other session on the VM was responsible.

### Re-verification after both fixes

- `docker compose ps`: `app`/`redpanda` `(healthy)`, `caddy` `running`, stable for 2+ minutes of
  continuous observation.
- Off-VM: `curl .../api/actuator/health` → `200`.
- `rpk registry subject list` → 14 subjects, matching the pre-cutover count exactly.

### Repository secret inventory (names and purpose only — no values recorded here)

| Secret name | Purpose | Origin |
|---|---|---|
| `NETCUP_SSH_KEY` | Private half of the dedicated deploy keypair; authenticates the deploy job as `deploy` | Generated locally (`ssh-keygen -t ed25519`), never present on the VM before being appended to `deploy`'s `authorized_keys` |
| `NETCUP_DEPLOY_USER` | The non-root deploy identity (`deploy`) the SSH/SCP deploy steps authenticate as | Created on the VM this task |
| `NETCUP_HOST` | The VM's public IPv4 (`159.195.114.230`) | Already known (plan 05-03) |
| `NETCUP_HOST_FINGERPRINT` | SSH host key fingerprint, pins the deploy connection against MITM — never disabled | `ssh-keyscan` + `ssh-keygen -lf` against the real host |
| `DB_HOST` / `DB_NAME` / `DB_USER` / `DB_PASS` | Neon's **direct** (non-pooled) endpoint, split into fields — consumed by plan 05-05 Task 2's Flyway migration-verification job | Neon dashboard, direct connection string |

**Deviation from the plan's acceptance criteria, recorded deliberately:** the plan calls for six
secrets under names distinct from the AWS-era ones specifically so a future reader can't confuse a
live secret with a stale one by name alone. `DB_HOST`/`DB_NAME`/`DB_USER`/`DB_PASS` **reuse the
AWS-era secret names** (operator's explicit choice — "reused variable names from last year") rather
than new ones. `DB_NAME`/`DB_USER`/`DB_PASS` were updated the same day this task ran; `DB_HOST` was
updated slightly later in the same session after an initial gap where its timestamp lagged the other
three — confirmed via `gh secret list` (names/timestamps only, values never seen) before treating it
as done. If these are ever audited later, do not assume a `DB_*`-named secret is safe by name alone —
check its last-updated timestamp against this entry's date (2026-08-16).

**Also deliberately not registered:** a secret for Neon's **pooled** connection string. The original
plan/research assumed one was needed for the app's runtime config, but that config already lives in
`.env.prod` on the VM (plan 05-04) and nothing in this workflow writes or reads it — the pooled
secret would have had zero consumers. Dropped as unnecessary rather than registered for its own sake.

## Automated deploy — Plan 05-05 Task 2 and Task 3 (2026-08-16)

CI/CD now builds, verifies the schema, and deploys automatically on every push to
`master` — the sequence plan 05-04 proved by hand is now the pipeline, not a
runbook a human follows.

### Task 2 — Flyway migration verification (INFRA-06)

A new `flyway-verify` job runs the official `flyway/flyway:11.7.2` CLI image
against Neon's **direct** connection string (`DB_HOST`/`DB_NAME`/`DB_USER`/`DB_PASS`
secrets), applying `src/main/resources/db/migration`'s checked-in migrations, and
gates `deploy-to-netcup` in the dependency graph.

- **Guard proven by a deliberate failing run, then reverted** (not by reading the
  code): commit `125eebb` temporarily pointed the guard's comparison at a value
  containing the pooler marker; the job failed as designed (run `31961059446`,
  `CI/CD with Docker` red). Commit `0a8571e` reverted it immediately; the next run
  (`31961405448`) passed clean.
- **Idempotency proven by repeated real success**, not asserted: the job has now
  succeeded on four separate real pushes to `master` against the same,
  already-migrated Neon database — `77f02a0` (first run, applied the migrations),
  then `125eebb`'s revert, `56f093c`, and `595ec08` — Flyway's own
  `flyway_schema_history` tracking makes every run after the first a no-op
  success, exactly as designed.

### Task 3 — Deploy job rewrite and cleanup jobs (INFRA-05)

`deploy-to-ec2` (dead since 2026-08-04, `if: false`) was deleted outright and
replaced with `deploy-to-netcup`, targeting this document's Netcup VM via
`appleboy/scp-action@v1.0.0` (copies `docker-compose.prod.yml`/`Caddyfile`) and
`appleboy/ssh-action@v1.2.5` (runs `docker compose pull app && up -d`), both
authenticating as the non-root `deploy` user with the plan 05-05 Task 1 keypair,
host key pinned by fingerprint on both steps. Both action tags were re-confirmed
against their GitHub releases at execution time (`v1.2.5`/`v1.0.0` were each
still the latest release).

- **Proven green end-to-end on a real push** (commit `56f093c`, run
  `31962045626`): `build-and-push-docker-image` → `flyway-verify` →
  `deploy-to-netcup` → `cleanup-old-images` all succeeded. Confirmed by direct
  inspection, not by trusting the green checkmark alone:
  - `docker inspect` on the VM showed the running `app` container's image as
    `rudenkovladimir/kanban-board-backend:56f093c` — matching the pushed
    commit's short SHA exactly.
  - Off-VM `curl https://kanban-board-rud-vlad-473.duckdns.org/api/actuator/health`
    → `200 {"status":"UP"}`.
- **A second, genuinely independent push converged cleanly** (commit `595ec08`,
  run `31963539949`, triggered by an unrelated bug-fix documented below) —
  `flyway-verify` succeeded again (a third idempotent run), `deploy-to-netcup`
  succeeded again, and the off-VM health check still returned `200` afterward.
  This is the proof the plan's own acceptance criteria required: a push that
  changes nothing the deploy consumes still converges the VM rather than
  erroring, because the image tag itself always changes per commit even when
  the compose manifest and Caddyfile do not.
- Both cleanup jobs' `needs:` now name `deploy-to-netcup`. `cleanup-old-images`
  (`if: success()`) has fired for real on both pushes above, proving the
  dependency-graph rewiring actually resumed its gating (previously it
  permanently skipped, since `deploy-to-ec2` was `if: false`).
  `cleanup-unused-image` (`if: failure()`) is wired identically but was **not**
  exercised by a genuine deploy failure in this session — its correctness rests
  on the same `needs:`/`if:` idiom proven live on the success side, not on a
  live failure-path test. (An earlier version of this section's source todo
  claimed a deliberate fingerprint-mismatch test had proven this; that claim
  was false and has been corrected in place — see
  `.planning/todos/completed/2026-08-04-re-enable-and-rewrite-the-disabled-deploy-job-after-phase-5.md`.)

### Two real bugs found live during this task, one fixed and one deferred

Neither was caught by the workflow file simply parsing or by the job reporting
green — both were only visible by reading the job's own log output, which is
exactly why this section documents live evidence rather than describing intent.

1. **`cleanup-old-images`' DELETE URL was missing the repository-name path
   segment** (`$DOCKERHUB_USER/tags/$TAG/` instead of
   `$DOCKERHUB_USER/$DOCKERHUB_REPOSITORY/tags/$TAG/`) — every delete 404'd
   silently on the job's first-ever real run (commit `56f093c`'s run), a bug
   present since the job was first written but latent the whole time the job
   was permanently skipped. **Fixed** in commit `595ec08`, using the same
   `base_image_name` output the list call two lines above it already uses.
2. **After that fix, every delete is rejected `{"message":"unauthorized"}`**
   (confirmed live, run `31963539949`) — Docker Hub's Hub API v2 does not
   accept the job's `-u user:token` Basic auth for a mutating `DELETE`; the
   preceding `GET` list call "succeeding" with the same `-u` flag is not
   evidence Basic auth works, since it is a public repository and an
   unauthenticated `GET` on a public repo's tags succeeds regardless. **Not
   fixed this session** — the correct fix needs a JWT token exchange via
   `POST /v2/users/login/` that was not safely testable without live
   credential access. Filed as
   `.planning/todos/pending/2026-08-16-cleanup-old-images-delete-calls-rejected-unauthorized.md`,
   not gating Phase 5 (the plan's actual acceptance criteria required the
   cleanup jobs to be correctly wired and to fire, not that their deletes
   succeed). Practical effect: Docker Hub tags continue accumulating
   unbounded, same as while `deploy-to-ec2` was disabled — a tidiness/storage
   issue, not a deploy-correctness or security issue.

## External Network Audit — Plan 05-06 Task 1 (2026-08-17)

Proves INFRA-08 by external measurement rather than by reading rule lists — Docker inserts
forwarding rules that bypass the input chain a local `iptables -L` listing shows, so only a scan
run from off-VM, against the composite result of both firewall layers, is evidence.

**Execution note, consistent with the "Manual deploy — Plan 05-04" precedent above:** this audit
was run directly by the orchestrating agent from its own machine (genuinely off-VM, genuinely not
on the same network as the Netcup VM), not hand-typed by a human pasting results back one command
at a time as the plan's default guided-execution protocol specifies. The environment for this
session has direct SSH access to `netcup-prod` and `gh` CLI access to the repository, which made
guided step-by-step human relay unnecessary for the read-only and reversible steps below; the two
genuinely destructive/downtime-causing steps (Docker daemon restart, VM reboot) were still gated
on explicit human confirmation before being triggered, consistent with this project's
credential/downtime handling norms.

### Scan tooling

- `nmap` was tried first (installed via `winget`) but produced unreliable false-negatives on
  Windows without the Npcap packet-capture driver — its `-sT` connect-scan mode intermittently
  misreported known-open ports as filtered, caught by cross-checking against a working `curl`
  HTTPS request that succeeded while `nmap` reported 443 filtered. Abandoned in favor of a
  purpose-built Python `socket.connect_ex` concurrent TCP-connect scanner (stdlib only, no
  install needed) — functionally equivalent to `nmap -sT`, and empirically cross-checked reliable
  (see the two-pass IP comparison below).
- Full range 1-65535 scanned three independent times: IP pass 1, an immediate IP pass 2 re-run,
  and a hostname pass 1. Shape: `ThreadPoolExecutor` with 400 concurrent workers, 1.5s per-port
  timeout, calling `socket.connect_ex((target, port))` for every port 1-65535; `0` classified
  open, `ECONNREFUSED`/10061 closed, anything else (timeout/other) filtered.

### Full-range scan results (1-65535, three independent passes)

| Pass | Target | Started (UTC) | Ended (UTC) | Ports probed | Open | Closed | Filtered |
|------|--------|---------------|-------------|---------------|------|--------|----------|
| IP pass 1 | `159.195.114.230` | 2026-08-17T08:31:27Z | 2026-08-17T08:35:46Z | 65535 | 22, 80 | 0 | 65533 |
| IP pass 2 (immediate re-run) | `159.195.114.230` | 2026-08-17T08:36:22Z | 2026-08-17T08:40:29Z | 65535 | 22, 80, 443 | 0 | 65532 |
| Hostname pass 1 | `kanban-board-rud-vlad-473.duckdns.org` | 2026-08-17T08:40:48Z | 2026-08-17T08:44:58Z | 65535 | 22, 80, 443 | 0 | 65532 |

**IP pass 1 had a false negative on port 443** — caught immediately, not silently accepted: an
independent isolated `connect_ex` check against port 443 alone, plus a `curl` HTTPS request to
the hostname, both succeeded, proving 443 was actually open the whole time and pass 1's own
concurrency (400 simultaneous connections) caused a transient miss on that one port. IP pass 2
(re-run immediately after, same parameters) came back clean — `22, 80, 443` — confirming pass 1's
miss was a scan artifact, not a real state change between the two passes. The hostname pass came
back clean on its first attempt.

**Conclusion: exactly 22, 80, 443 open on both the IP and the hostname; every other port across
the full 1-65535 range returns no response (filtered) rather than a service — confirmed by 3
independent full-range scans totaling 196,605 port probes, with the one observed false negative
independently caught and explained, not glossed over.**

### Direct-connection probes (individual, not part of the range sweep)

Separate `connect_ex` calls, 5s timeout each, against both the IP and the hostname, targeting the
ports the plan specifically names as a response-is-a-failure test: 19092 (Kafka external broker
address), 8081 (Schema Registry), 9092 (Kafka internal-listener naming), 33145 (Redpanda RPC),
8080 (app direct), 5432 (Postgres — not applicable to this stack, since production's database is
Neon, included deliberately for a clean negative control), and 3000.

All 14 probes (7 ports x 2 targets) timed out after ~5.0-5.8s with zero response — Windows errno
10035 (`WSAEWOULDBLOCK`) meaning the connection attempt was still pending at timeout, i.e. no
SYN-ACK was ever received on any of them. No port produced a service response. This satisfies the
plan's "a response is a failure" criterion directly: none occurred.

### Layer 1 — OS-level `iptables`, re-verified at audit time via SSH to `netcup-prod`

```
Chain INPUT (policy DROP)
1  ACCEPT  ctstate RELATED,ESTABLISHED
2  ACCEPT  in lo
3  ACCEPT  tcp dpt:22
4  ACCEPT  tcp dpt:80
5  ACCEPT  tcp dpt:443
```

Matches plan 05-03's recorded intent exactly — zero drift found. `/etc/iptables/rules.v4` (the
persisted on-disk ruleset `netfilter-persistent save` writes) matches the live ruleset
byte-for-byte; `netfilter-persistent.service` is enabled.

### Layer 2 — Netcup Cloud Firewall, re-verified at audit time via the SCP web panel

No CLI or API exists for this layer (Netcup does not expose one) — the operator confirmed this
directly in the SCP web console, since this layer has no read-only automation surface. "Firewall
active" toggle: ON. Rule policies, top to bottom (first match wins): (1) "netcup Mail block"
[Netcup default, unrelated to this app] — drops outgoing SMTP/465/587; (2) "netcup Ping allow"
[Netcup default] — allows incoming/outgoing ICMP + ICMPv6 ping; (3) "Default" (the custom policy
plan 05-03 created) — ACCEPT incoming HTTP/80, ACCEPT incoming HTTPS/443, ACCEPT incoming SSH/22,
exactly matching recorded intent, zero drift, and no leftover debug rules from plan 05-04's
manual deploy session; (4) implicit system rules — drop all incoming, accept all outgoing.

**No unexpected rule found at either layer, and no drift requiring restoration was found — this
audit's "record drift and restore" branch was not exercised because there was nothing to
restore.**

**Note on layer count:** only 2 network layers exist for this deployment (OS `iptables` + Netcup
Cloud Firewall), not 3. The plan's "all three network layers" language is stale, carried over from
the superseded Oracle Cloud Security-List/NSG/OS-firewall three-layer model this project pivoted
away from in plan 05-03 (see "Provider history" above). This runbook has documented the 2-layer
model since plan 05-03 (see "Firewall — two independent layers" above); this audit's structure
follows that, not the plan text's stale 3-layer framing.

### Restart and reboot persistence

Both performed live against production, with explicit human confirmation before triggering,
since both cause brief real downtime:

1. **`sudo systemctl restart docker`** on `netcup-prod` — all 3 containers (`app`, `caddy`,
   `redpanda`) came back `Up 27 seconds (healthy)` / `(healthy)` within seconds. Post-restart:
   `iptables` rules unchanged; an external HTTPS request to the hostname returned `404`
   (Caddy/the app responding correctly again, `404` because the request path had no matching
   route — not an error state).
2. **`sudo reboot`** on `netcup-prod` — the VM came back within under 90 seconds (`uptime` showed
   "up 0 min" at the first post-reboot check). Post-reboot: `iptables` rules were restored
   identically via `netfilter-persistent` (enabled and active); the Docker service was active and
   enabled; all 3 containers came back healthy automatically within 15 seconds (the `restart:
   unless-stopped` policy proven working, not merely configured); and a fresh 10-port targeted
   scan plus an external HTTPS request both confirmed the exact same 22/80/443-only profile as
   before the reboot.

### Audit date

2026-08-17.

## Log Rotation Observation — Plan 05-06 Task 2 (2026-08-17)

Observes INFRA-07's disk-bound claim by measurement rather than by re-reading
`docker-compose.prod.yml`'s configuration — a manifest option that was never applied because the
container predates it is a realistic, invisible failure mode this section rules out directly.

### In-effect log configuration, confirmed by inspecting running containers (not the manifest)

```
$ docker inspect kanban-board-backend-app-1 --format '{{.HostConfig.LogConfig.Type}} maxsize={{index .HostConfig.LogConfig.Config "max-size"}} maxfile={{index .HostConfig.LogConfig.Config "max-file"}}'
json-file maxsize=10m maxfile=3

$ docker inspect kanban-board-backend-caddy-1 --format '{{.HostConfig.LogConfig.Type}} {{.HostConfig.LogConfig.Config}}'
json-file map[max-file:3 max-size:10m]

$ docker inspect kanban-board-backend-redpanda-1 --format '{{.HostConfig.LogConfig.Type}} {{.HostConfig.LogConfig.Config}}'
json-file map[max-file:3 max-size:10m]
```

All three running services report the configured driver and both options actually in effect —
`docker-compose.prod.yml`'s `x-logging` anchor is applied to every service, confirmed live, not
assumed from the YAML.

### Attempt 1: drive volume through the app's own request path — real finding, near-zero output

The plan's action text prefers driving log volume through repeated real API calls over writing
synthetic output, "because it exercises the same path real logs take." This was tried first and
is recorded here as a genuine, load-bearing finding, not a discarded attempt:

- 15+ `GET /api/actuator/health` requests: **0 bytes** of app-container log growth.
- Failed signin, 404 on a nonexistent board, and a validation-rejected signup (5 requests each):
  **0 bytes** of log growth for any of them.
- 5 successful, uniquely-named `POST /api/boards` calls: log grew by 20,406 bytes (~4KB/request)
  in one measurement window.
- A follow-up batch of 20 `POST /api/boards` calls reusing an already-taken name (19 of 20 hit a
  409 uniqueness conflict): **0 bytes** of growth, including for the one request that *did*
  succeed.
- 5 successful, uniquely-positioned `POST .../tasks` calls (real Kafka publish + consumer-side
  persist per CLAUDE.md's documented pipeline): **0 bytes** of growth.

**Conclusion, consistent with this codebase's own documented convention** ("Logging not
extensively used in current codebase; focus is on exception handling" — `.claude/CLAUDE.md`,
Logging section): this application does not log per-request at INFO level by default, for either
successful or rejected requests, and Kafka producer/consumer activity does not log per-message in
steady state. The one batch that did show growth is best explained by incidental background
activity (e.g. a Kafka consumer-group heartbeat/rebalance window) coinciding with that test
window, not by the requests themselves — the two later batches of genuinely successful mutations
(one Kafka publish/consume each) produced no measurable growth at all. This is itself a
significant, positive finding for INFRA-07: under this application's actual traffic shape, the
10MB/3-file cap has enormous headroom and is very unlikely to ever bind under organic use — but it
also means the literal "hit the API until it rotates" method the plan suggests is impractical
here without an unrealistic volume of synthetic-shaped traffic against a live production service,
which this audit chose not to do (see Attempt 2, and "Deviation recorded" below).

`/api/actuator/loggers` (which could otherwise raise the log level to generate real request-path
volume) returned `401` unauthenticated and `500` once authenticated — not safely usable for this
purpose without risking touching an endpoint whose behavior under a mutating request wasn't
independently verified beforehand, so this path was not pursued further.

### Attempt 2: prove the rotation mechanism deterministically, without touching the live services

Rather than hammering production with the very large number of requests Attempt 1 shows would be
needed, the rotation mechanism itself was proven using a throwaway container on the same Docker
daemon, with the exact same logging driver and options the three production services use
(`--log-driver json-file --log-opt max-size=10m --log-opt max-file=3`) — this exercises the
identical rotation subsystem the app/caddy/redpanda containers depend on, on the same daemon
version, with zero risk to the running stack:

```
$ docker run -d --name rotation-test --log-driver json-file --log-opt max-size=10m --log-opt max-file=3 \
    alpine sh -c "yes '<120-char line>-rotation-test-line' | head -n 400000"
$ docker wait rotation-test
0
$ sudo ls -la /var/lib/docker/containers/<id>/ | grep json
-rw-r----- 1 root root  4354807 Aug 17 11:07 <id>-json.log
-rw-r----- 1 root root 10000149 Aug 17 11:07 <id>-json.log.1
-rw-r----- 1 root root 10000014 Aug 17 11:07 <id>-json.log.2
```

- **File count held at exactly 3** (current + `.1` + `.2`) — no `.3` file exists, despite 400,000
  lines of output (~76MB estimated at ~190 bytes/line after JSON-log wrapping overhead) having
  been generated, far more than the 3 x 10MB = ~30MB retained across the surviving files
  (4,354,807 + 10,000,149 + 10,000,014 = 24,354,970 bytes retained). The difference — tens of
  megabytes of generated content that is nowhere on disk — is direct evidence the oldest file(s)
  were deleted at rotation rather than the set growing unboundedly, satisfying the plan's own
  "a count that has not yet reached the maximum proves nothing" instruction: this run was driven
  well past the maximum on purpose.
- **Rotation threshold observed:** both completed rotated files sit at ~10,000,000-10,000,149
  bytes — just over the decimal-megabyte threshold (10 x 1000 x 1000), not the binary
  10,485,760-byte (10 MiB) threshold a naive reading of "10m" might suggest. Worth recording as a
  unit-interpretation fact for this Docker version, not a discrepancy in the configured cap.
- Cleaned up immediately after inspection: `docker rm -f rotation-test`. Confirmed zero residue —
  the live app/caddy/redpanda containers and their own log files were never touched by this test.

### Worst-case aggregate bound vs. real disk capacity

| | Value |
|---|---|
| Per-container cap | `max-size: 10m` x `max-file: "3"` = ~30MB (decimal) |
| Services carrying this cap | `app`, `caddy`, `redpanda` (all 3, via the shared `x-logging` anchor) |
| Worst-case aggregate log bound | 3 x ~30MB = ~90MB |
| VM disk capacity (`df -h /`, measured live) | `/dev/vda4`: 251G total, 5.0G used, 236G available, 3% in use |
| Headroom | ~90MB against 236GB available — roughly 0.038% of available disk, i.e. over 2,600x headroom |

The aggregate bound is a genuinely negligible fraction of this VM's actual free disk — INFRA-07's
concern (unbounded log growth filling the free-tier disk) is closed with very large measured
margin, not merely configured margin.

### Deviation recorded: method diverges from the plan's literal suggestion, criteria still met

The plan's action text suggests forcing rotation "driving the application's own request logging
through repeated API calls." Attempt 1 (above) is the good-faith execution of that instruction,
and its near-zero-output result is recorded as a real finding rather than discarded. Given that
result, generating the volume needed to force deletion via genuine external HTTP traffic against
a live production service would have required an impractically large request count for a single
session and would have meant deliberately hammering a personal-scale production app for no
operational reason. Attempt 2 substitutes a same-daemon, same-driver, same-options throwaway
container to prove the underlying mechanism instead, satisfying every acceptance criterion this
task actually cares about (file count bounded at the configured maximum, total bytes bounded by
size x count, oldest file deleted rather than the set growing) without that risk. This is a
documented deviation (Rule 1/2 territory — the plan's literal method didn't work, its underlying
intent was fully met by an equivalent, safer method), not a silently weakened check.

### Measurement date

2026-08-17.

## Decommission Record — Plan 05-06 Task 3 (2026-08-17)

Closes out INFRA-05 by removing the dead AWS-era credential surface and correcting the committed
documentation to describe the infrastructure that actually exists, now that the Netcup pipeline
has multiple proven-green end-to-end deploys (plan 05-05's SUMMARY).

### Part A — AWS-era secret revocation: already satisfied, nothing to delete

The plan's acceptance criteria call for deleting `EC2_SSH_KEY`, `EC2_HOST`, `EC2_USER`, and the
AWS-scoped database secrets. Verified directly, not assumed:

```
$ gh secret list --repo RudVlad473/kanban-board-backend
DB_HOST                  ...
DB_NAME                  ...
DB_PASS                  ...
DB_USER                  ...
DOCKERHUB_TOKEN          ...
NETCUP_DEPLOY_USER       ...
NETCUP_HOST              ...
NETCUP_HOST_FINGERPRINT  ...
NETCUP_SSH_KEY           ...
NVD_API_KEY              ...
```

Exactly 10 repository secrets exist. `EC2_SSH_KEY`, `EC2_HOST`, and `EC2_USER` are **not present**
— there is nothing to delete. `grep -rciE "EC2_SSH_KEY|EC2_HOST|EC2_USER" .github/` also returns 0
for every file, confirming no live reference exists either.

**This directly contradicts a prior session's `.planning/HANDOFF.json`/`.continue-here.md` claim**
that these secrets were "still present and unrevoked" — that claim was stale or simply wrong. The
live, re-verified state is that this acceptance criterion is already satisfied: no AWS-era secret
survives in repository settings, and none is referenced anywhere in the workflow. Recorded here as
an already-satisfied finding, not as a deletion action that did not actually happen.

### Part B — documentation correction

Corrected every committed file found still describing AWS EC2 as the current deployment target
(a repository-wide `grep -riE "EC2|AWS"` sweep across `*.md`/`*.sql`, narrowed to files making a
present-tense claim about where the app deploys today, as opposed to historical narration of the
AWS deletion itself, which is accurate and left untouched):

- `.claude/CLAUDE.md` — "Platform Requirements" section corrected from "AWS EC2 - Deployment
  target" to name Docker Compose, the Netcup VPS, Neon, self-hosted Redpanda, and Caddy.
- `.planning/codebase/STACK.md` — the same correction applied to the underlying GSD-managed source
  document this section of `.claude/CLAUDE.md` is generated from (the `<!-- GSD:stack-start
  source:codebase/STACK.md -->` marker), so a future stack-doc regeneration does not silently
  reintroduce the stale AWS EC2 line.
- `README.md` (repository root) — "Project status" section rewritten: it previously described the
  whole v1.2 infra migration as still "in flight" against an Oracle Cloud A1 Flex target, which was
  itself stale (the actual pivot landed on Netcup, not Oracle — see "Provider history" above) and
  understated what had actually shipped. Now states the migration is complete and names the real
  target.
- `docs/plans/backend-modernization/02-optimistic-locking-ddl.sql` — annotated (not rewritten) its
  "WHEN TO RUN" section's "master auto-deploys to EC2 on every push" reasoning with a note that
  this host no longer exists; original historical rationale left intact per the plan's own
  instruction not to rewrite superseded provenance text.
- `docs/plans/backend-modernization/STATUS.md` — same annotation applied to its parallel "master
  auto-deploys to EC2 on every push" line in the Key Decisions table, for the same reason.
- `docs/plans/backend-modernization/README.md` — its "Repo" summary line's "single-EC2 Docker
  deploy via GitHub Actions" phrase annotated with a historical note rather than deleted, since the
  surrounding sentence is otherwise a point-in-time description of this plan's original
  assumptions.
- `docs/LOCAL_DEV.md` — corrected two genuinely inaccurate functional claims, not just naming: it
  described the production pipeline as deploying via a single `docker run` of the built image with
  no Kafka broker standing up, and referenced "EC2's constraints" — neither matches the current
  Netcup deploy, which runs the full `docker-compose.prod.yml` stack (including `redpanda`) via
  `docker compose up -d` per plan 05-05.

`docs/INFRA_ARCHITECTURE.md`'s delivery diagram was **not modified by this task** — it was already
promoted from target-state to current-state language by quick task `260816-tqc` earlier the same
day (see that task's commits and `.planning/STATE.md`), independently of this plan. Verified
against the live file rather than assumed from the plan text before treating it as already done.

`.planning/`-scoped files other than `.planning/codebase/STACK.md` (phase summaries, quick-task
records, `.continue-here.md`, `.planning/research/*`, `.planning/milestones/*`) were deliberately
left untouched — they are historical records of what was true or planned at the time they were
written (e.g. "disabled `deploy-to-ec2`" describes a real action taken on a real date), not
present-tense claims about today's deploy target, and rewriting them would destroy the provenance
trail this project's own CLAUDE.md and `docs/SESSION_LESSONS.md` conventions rely on.
`.planning/codebase/STACK.md` was the one exception, corrected specifically because it is a live
generation source for `.claude/CLAUDE.md`, not a historical record.

### Part C — Docker Hub tag pruning: closed 2026-08-17, same-day follow-on

Originally halted here as a genuine human-only checkpoint (no Docker Hub console access in
this session). Resolved without needing that access after all: `cleanup-old-images`' DELETE
calls turned out to have two independent bugs in the job's own code, both found and fixed live
rather than guessed.

1. **`8a31d85`** — Hub API v2 rejects HTTP Basic auth on mutating requests (`DELETE`); added the
   documented login-token exchange (`POST /v2/users/login/`), sending the result as
   `Authorization: JWT <token>`. Its first live run (CI run `32016633112`) succeeded per its own
   status checks but deleted only 9 of 41 accumulated tags.
2. **`faacda4`** — root cause of the partial deletion: Hub API v2 paginates tag listings at
   10/page, and the list call only ever read page 1. Fixed by following the response's `next`
   cursor until `null`.

**Live proof:** CI run [`32017867204`](https://github.com/RudVlad473/kanban-board-backend/actions/runs/32017867204)
(2026-08-17T09:58Z, commit `faacda4`) — `cleanup-old-images` deleted all 32 remaining
non-current tags, the job's own `FAILED` counter (incremented on any non-204 delete response)
stayed at 0, job concluded success with zero `::warning`/`::error` lines.

INFRA-05's Docker Hub tag-pruning must-have is now fully satisfied. See
`.planning/phases/05-infra-migration/05-06-SUMMARY.md` (coverage item D4) and
`.planning/todos/completed/2026-08-16-cleanup-old-images-delete-calls-rejected-unauthorized.md`
for the full resolution writeup.

### Decommission date

2026-08-17.

## Nonprod bring-up — Plan 08-01 (2026-08-18)

A second, production-isolated deployment (`app-nonprod` + `redpanda-nonprod`) is live on this same
Netcup VM, reachable over real Let's Encrypt HTTPS at its own hostname, backed by its own empty
Neon branch and its own Redpanda broker/schema registry, gated behind a `nonprod` Compose profile
in its own Compose project. This section records the identity choices, the exact live sequence
executed, the isolation-audit output, and the CORS proof — every claim below is a reproducible
command output captured during this bring-up, not an inference from the checked-in config.

### Nonprod identities

| Axis | Value |
|---|---|
| Compose file | `docker-compose.nonprod.yml` (repo root, standalone file — see Deviations below) |
| Compose project name | `kanban-board-nonprod` |
| VM directory | `/opt/deploy/kanban-board-nonprod/` (owned by `deploy`, sibling to `/opt/deploy/kanban-board-backend/`) |
| Compose profile | `nonprod` (both services gated; neither exists without `--profile nonprod`) |
| Container names | `kanban-nonprod-app`, `kanban-nonprod-redpanda` |
| Network names | project default `kanban-board-nonprod_default`; shared external `kanban-edge` (joined only by `kanban-nonprod-app` and production's `caddy`) |
| Volume name | `kanban-board-nonprod_redpanda-nonprod-data` |
| Public hostname | `kanban-board-rud-vlad-473-nonprod.duckdns.org` -> `159.195.114.230` (same VM as production) |
| Neon branch | `nonprod`, project `kanban-board-db` (`floral-union-23715140`), created via the Neon Console with `init_source: parent-schema` (schema-only, no production data), direct host `ep-wild-mode-b2atsqpx.c-6.eu-central-1.aws.neon.tech` |
| Image tag | `6755c84` — Docker Hub does **not** publish a `latest` tag for this repository; see Deviations below |

### Sequence, in order

1. **Shared network:** `docker network create kanban-edge` on the VM (did not previously exist).
2. **Caddy joined to it:** edited `docker-compose.prod.yml` (additive-only: new top-level
   `networks:` block, `caddy.networks: [default, kanban-edge]`, `caddy.environment.APP_DOMAIN_NONPROD`)
   and `Caddyfile` (second site block, `reverse_proxy app-nonprod:8080`), `scp`'d both to
   `/opt/deploy/kanban-board-backend/`, appended `APP_DOMAIN_NONPROD=kanban-board-rud-vlad-473-nonprod.duckdns.org`
   to `.env.prod` on the VM, then `docker compose -f docker-compose.prod.yml --env-file ./.env.prod up -d caddy`
   (service named explicitly, not a bare `up -d`). Production's health endpoint and `app`/`redpanda`
   container ids were confirmed unchanged immediately after — the only production container this
   plan recreates is `caddy` itself, once, for the network join.
3. **Neon branch:** the `nonprod` branch already existed (created via the Console before this
   session), schema-only. **Emptied before Flyway ever ran** — a schema-only branch arrives
   carrying the parent's full DDL plus an already-populated `flyway_schema_history`, which makes
   Flyway attempt `V1__init.sql` against tables that already exist and fail. Connected with `psql`
   to the direct (non-pooler) host and ran `DROP SCHEMA public CASCADE; CREATE SCHEMA public;`;
   `\dt` confirmed zero relations before continuing.
4. **`/opt/deploy/kanban-board-nonprod/` created**, owned by `deploy` — required a one-time root
   action (`mkdir` + `chown`) since `/opt/deploy` itself is root-owned and `deploy` cannot `sudo`,
   the same precedent already recorded in "Deploy user setup — Plan 05-05 Task 1" for the sibling
   production directory. `docker-compose.nonprod.yml` copied in; `.env.nonprod` created directly on
   the VM (never pasted into a local file, never written under `.planning/`), mode `600`, owned by
   `deploy`, with `APP_RESET_TOKEN` generated on the VM via `openssl rand -hex 32`.
5. **`redpanda-nonprod` brought up alone:**
   `docker compose -f docker-compose.nonprod.yml --env-file ./.env.nonprod --profile nonprod up -d redpanda-nonprod`.
   Before registering anything, `rpk registry subject list` inside `kanban-nonprod-redpanda`
   returned zero subjects; the same command inside production's `redpanda` returned its existing 14
   — the nonprod broker genuinely started from an empty registry.
6. **14 Avro schemas registered** against the nonprod registry, mirroring the production runbook's
   `PropertiesLauncher` technique:
   ```
   docker compose -f docker-compose.nonprod.yml --env-file ./.env.nonprod --profile nonprod run --rm \
     --entrypoint java app-nonprod -Dloader.main=com.vrudenko.kanban_board.config.AvroSchemaRegistrar \
     -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher http://redpanda-nonprod:8081
   ```
   Log: `Registered 14 Avro schemas against http://redpanda-nonprod:8081`. Verified independently via
   `rpk registry subject list` — 14 subjects, matching production's set of Avro class names exactly
   (an independent registration, not a copy).
7. **`app-nonprod` brought up:**
   `docker compose -f docker-compose.nonprod.yml --env-file ./.env.nonprod --profile nonprod up -d app-nonprod`.
   Reached `(healthy)`. Container log confirmed Flyway migrated the empty branch fresh:
   `Migrating schema "public" to version "1 - init"` through `"7 - add board optimistic locking
   version column"`, `Successfully applied 7 migrations ... now at version v7`.
   `SELECT count(*) FROM flyway_schema_history WHERE success = true;` against the branch returned
   `7`.
8. **Verified end-to-end from off-VM:**
   `curl https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api/actuator/health` -> `200`
   `{"status":"UP"}`; plain `http://` -> `308` redirect to the `https://` URL; production's own
   health endpoint still `200` throughout.

### Isolation-audit output (Task 2)

**Identity axes** — `docker ps -a --format '{{.Names}}\t{{.Label "com.docker.compose.project"}}'`:
```
kanban-nonprod-app               kanban-board-nonprod
kanban-nonprod-redpanda          kanban-board-nonprod
kanban-board-backend-caddy-1     kanban-board-backend
kanban-board-backend-app-1       kanban-board-backend
kanban-board-backend-redpanda-1  kanban-board-backend
```
Exactly two project labels, no container name shared between them.

`docker volume ls --format '{{.Name}}'`:
```
kanban-board-backend_caddy-config
kanban-board-backend_caddy-data
kanban-board-backend_redpanda-data
kanban-board-nonprod_redpanda-nonprod-data
root_caddy-config      <- orphaned leftover from the 05-05 cutover incident, unrelated to this audit
root_caddy-data
root_redpanda-data
```
`kanban-board-backend_*` and `kanban-board-nonprod_*` sets have an empty intersection.

`docker network inspect kanban-edge --format '{{range .Containers}}{{.Name}} {{end}}'`:
```
kanban-nonprod-app kanban-board-backend-caddy-1
```
Exactly the two containers meant to share it — production's `app` and `redpanda` are absent.

**Profile-gate (empty case):** `docker compose -f docker-compose.nonprod.yml --env-file ./.env.nonprod config --services`
(no `--profile` flag) returned no output — neither service is listed. A bare
`docker compose -f docker-compose.nonprod.yml --env-file ./.env.nonprod up -d --dry-run` (still no
`--profile`) refused outright: `no service selected`. Stronger than "creates zero containers" — the
command will not even attempt to select a service without the profile flag.

**Cross-project non-interference:** production's `app`/`redpanda`/`caddy` container ids were
identical before and after a full nonprod cycle (`--profile nonprod stop` then `up -d`); production's
health endpoint answered `200` throughout the cycle.

**Database isolation:** nonprod branch `users`/`boards` counts went `0/0` -> `1/1` after signing up
`nonprod-isolation-260818@example.com` and creating one board through the nonprod public API.
Production's branch stayed at `3/2` across that same write, queried via a one-off `postgres:17-alpine`
container on the VM reading each stack's own `--env-file` (`.env.nonprod` / `.env.prod`) so the
Neon passwords were never typed or displayed outside the VM's own environment substitution.
`.env.nonprod` (`/opt/deploy/kanban-board-nonprod/`) and `.env.prod` (`/opt/deploy/kanban-board-backend/`)
are separate files in separate directories; `DB_HOST` values name different Neon compute endpoints
(`ep-wild-mode-b2atsqpx...` vs. `ep-delicate-bird-b2lni8pr...`).

**Broker/registry isolation:** `kanban.activity` high-watermark on nonprod advanced `0 -> 1 -> 2`
across two bracketing nonprod writes (the board create, then a column create), while production's
own `kanban.activity` watermark stayed at `74` across both — confirmed by reading production's
watermark before and after the second nonprod write (the first write's "before" reading was not
captured in time; the second write brackets cleanly and demonstrates the same non-interference).
`rpk registry subject list` returned 14 subjects on both brokers; a spot-check of
`AvroTaskMovedEvent`'s registered version count (`GET /subjects/.../versions`) returned `[1]` on
both — identical shape, independent histories (`RecordNameStrategy` keys compatibility by Avro
record full name, so registering the same 14 class names on a second broker does not touch
production's own compatibility history).

**CORS proof:** a real credentialed preflight (`OPTIONS /api/boards`, `Origin:
http://localhost:5173`, `Access-Control-Request-Method: GET`) against nonprod returned `200` with
`Access-Control-Allow-Origin: http://localhost:5173` and `Access-Control-Allow-Credentials: true`.
The identical preflight with `Origin: https://evil.example` returned `403` with **no**
`Access-Control-Allow-Origin` header at all — the allow-list is exact-string matching, proven from
real response headers, not inferred from the config file.

**TLS scope proof:** `openssl s_client ... -servername kanban-board-rud-vlad-473-nonprod.duckdns.org`
piped to `openssl x509 -noout -issuer -subject -ext subjectAltName` returned issuer `O=Let's
Encrypt, CN=YE2`, subject `CN=kanban-board-rud-vlad-473-nonprod.duckdns.org`, SAN
`DNS:kanban-board-rud-vlad-473-nonprod.duckdns.org` only (no wildcard). The same check against
production's hostname returned its own distinct certificate (`CN=kanban-board-rud-vlad-473.duckdns.org`)
— both site blocks share the one `caddy-data` volume but obtained separate, independently-scoped
certificates as designed.

### Deviations from the plan text, and why

- **Separate `docker-compose.nonprod.yml` instead of RESEARCH.md's in-place extension of
  `docker-compose.prod.yml`.** Two reasons: (1) NONPROD-01 requires a distinct Compose project
  name, and Compose supports exactly one project name per file — a shared file could not provide
  that identity axis. (2) A shared-project invocation (`docker compose -f docker-compose.prod.yml
  --env-file .env.nonprod --profile nonprod up -d`, the pattern RESEARCH.md's primary
  recommendation implies) targets every enabled service in the project, so Compose would
  re-resolve production's own `app` service against nonprod's `DB_*` values and recreate the live
  production container pointed at the nonprod database unless every invocation named services
  explicitly. A separate file makes that failure mode structurally impossible instead of relying on
  operator discipline. Recorded in `08-01-PLAN.md`'s own `design_alternatives` table before
  execution, not decided ad hoc during this bring-up.
- **`IMAGE_TAG=latest` does not exist on Docker Hub.** The first schema-registration attempt
  (`docker compose ... run --rm --entrypoint java app-nonprod ...`) failed outright —
  `docker.io/rudenkovladimir/kanban-board-backend:latest: not found` — because this repository's
  CI publishes one tag per commit (the short SHA), never a floating `latest` tag. Recovered by
  reading production's own currently-running image (`docker inspect --format '{{.Config.Image}}'
  kanban-board-backend-app-1` -> `rudenkovladimir/kanban-board-backend:6755c84`) and using that
  real tag for nonprod instead. `.env.nonprod.example`'s `IMAGE_TAG` comment was corrected in the
  same commit so a future operator does not hit the same 404 (Rule 1 — a bug in the plan's own
  documented default, fixed inline).
- **Database isolation proof's "before" reading for production was not captured strictly before
  step D's write** — the nonprod signup/board-create happened first, and production's watermark
  was read only afterward. Recovered by bracketing a *second* nonprod write (a column create)
  between two production readings instead: production's `kanban.activity` watermark was read
  immediately before and immediately after that second write and stayed flat at `74` in both
  readings, while nonprod's own watermark advanced `1 -> 2` across the same window — this
  demonstrates the same non-interference claim with a clean before/after bracket, just shifted to
  the second write rather than the first.
- **`/opt/deploy/kanban-board-nonprod/` required a one-time root action** (`mkdir` + `chown deploy:deploy`)
  before `deploy` could `scp` or write into it, since `/opt/deploy` itself is root-owned. This
  mirrors the exact precedent already recorded above in "Deploy user setup — Plan 05-05 Task 1" for
  `/opt/deploy/kanban-board-backend/` — not a new pattern, the same one-time setup step applied to
  a second directory.

### Operator note — deploying nonprod

From `/opt/deploy/kanban-board-nonprod/` on the VM, as `deploy`:
```
docker compose -f docker-compose.nonprod.yml --env-file ./.env.nonprod --profile nonprod up -d
```
Both `--env-file ./.env.nonprod` and `--profile nonprod` must be passed explicitly on every
invocation — Compose only auto-loads a file literally named `.env` (the same gotcha
"Manual deploy — Plan 05-04 Task 1" already documents for `.env.prod`), and the profile flag is
what keeps a bare `up -d` from creating anything at all (see the profile-gate proof above).

### Bring-up date

2026-08-18.

## Nonprod reset endpoint — Plan 08-02 (2026-08-18)

The profile-gated `POST /api/admin/reset` endpoint built in plan 08-02 (`ResetController` +
`ResetService` + `ResetTruncateService` + `NonprodResetSecurityConfiguration`, all
`@Profile("nonprod")`) was rolled onto the live nonprod stack from "Nonprod bring-up — Plan
08-01" above and proven end-to-end against the real deployed hostname: a correct-token call
genuinely empties both nonprod's Postgres tables and its Kafka activity topics, a wrong or
absent token is rejected identically, the same call against production does not return 204, and
the Kafka consumer survives the reset. Every observation below is a captured live command output
from this rollout, not an inference from the code.

### Rollout sequence, in order

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

### Live curl contract

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

### Independent verification (Postgres + Kafka)

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

### Production negative result

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

### Consumer survival and idempotency

A fresh user (the prior session's row had just been truncated) signed up and created one more
board (`POST /api/boards`) after the first reset; `GET .../activity` on that new board returned
`totalElements: 1` with a `BOARD_CREATED` event timestamped after the reset — the
`@KafkaListener` container was restarted, not left stopped. Reset was then called two further
times: once against that one-board state (`204`), and once immediately after against the
already-empty result of that call (`204` again) — idempotent both against real remaining state
and against a store already at zero. Final independent re-query: all eight tables `0`,
`flyway_schema_history` still `7`, `kanban.activity` log-start/high-watermark both `7` (one more
event trimmed in by the second reset), `kanban.activity.dlt` still `0`/`0`.

### Operator note — token rotation and production's structural exclusion

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

### Rollout date

2026-08-18.

## Maintenance note

If the provider, IP, OS, spec, or firewall policy changes, update this document in the same
change — it is the single checked-in description of what the production host actually is, as
opposed to what any given plan intended it to be.
