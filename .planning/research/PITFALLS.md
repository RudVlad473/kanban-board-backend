# Pitfalls Research

**Domain:** Adding self-hosted infra (Oracle Cloud A1 Flex VM + self-hosted Redpanda + Neon serverless Postgres + GitHub Actions SSH deploy) and a Schema Registry (Avro/Protobuf) in front of an already-shipped, working JSON Kafka pipeline with 5 sealed-interface event types, a dead-letter topic, and idempotent consumption — to an existing Spring Boot 3.5/Java 21 app.
**Researched:** 2026-08-03
**Confidence:** LOW-MEDIUM (general web sources plus official vendor docs — Redpanda, Neon, Oracle, Confluent — not independently cross-verified per claim; see Sources)

## Critical Pitfalls

### Pitfall 1: Redpanda's default resource auto-detection assumes it owns the whole VM, and it doesn't here

**What goes wrong:**
Redpanda (built on Seastar) auto-detects all visible memory and cores at startup and greedily claims most of it: it reserves only `max(1.5 GiB, 7% of RAM)` for "the OS" via `--reserve-memory` and divides the rest across cores for its own allocator, with real-world reports of it consuming up to 80% of available RAM. On a shared 2 OCPU/12 GB VM that also runs the Spring Boot JVM (with its own heap + off-heap + metaspace) and the OS itself, Redpanda's un-tuned defaults will starve the JVM process, which shows up as the Linux OOM killer terminating either Redpanda or the Spring Boot process (whichever crosses the cgroup/system memory line first) — often the app, not Redpanda, because Redpanda claimed memory first at boot.

**Why it happens:**
Redpanda's defaults and most quick-start guides (including its own Docker Compose examples) are written for either a dedicated bare-metal/VM deployment or a dev laptop with headroom to spare — not for "one more container sharing a 12 GB box with a JVM app and the OS."

**How to avoid:**
Explicitly set `--overprovisioned` (tells Redpanda other processes share the host: disables thread/memory pinning, reduces busy-polling) and cap Redpanda's memory explicitly via `--memory` / `rpk cluster config set redpanda.memory.available_memory <N>G` rather than letting it auto-detect. Budget the 12 GB explicitly up front: e.g., ~1–1.5 GB reserved for the OS, a fixed, capped JVM heap (`-Xmx`) for the Spring Boot container, and an explicit, smaller memory ceiling for Redpanda (2 core minimum per Redpanda's own docs implies ≥4 GB just for Redpanda at the stated 2 GB/core floor) — leaving no floating "whoever grabs it first" contention. Set Docker/systemd memory limits (cgroup) on both containers so a leak in one can't starve the other.

**Warning signs:**
- No `--overprovisioned` flag or `redpanda.memory.available_memory` cap set anywhere in the Redpanda startup config/Docker Compose.
- No explicit `-Xmx` on the Spring Boot JVM (defaults to a fraction of *total visible* host memory, which double-counts against Redpanda's own claim).
- `dmesg`/`docker logs` showing OOM-killer events after deploy, or Redpanda/app container restarting under load with no application-level error logged.

**Phase to address:**
Infra-migration phase, specifically the step that provisions/configures the Redpanda container — this must be decided before first deploy, not discovered via an outage.

---

### Pitfall 2: Redpanda's documented minimum (2 GB/core) already consumes the VM's entire CPU headroom before the app runs

**What goes wrong:**
Redpanda's own sizing docs state each broker needs at least 2 GB of RAM per core, and its lower-throughput guidance still assumes "2 cores + 1 NVMe disk" as a *minimum*, i.e., that pair of cores is assumed dedicated to Redpanda. This project's target VM has only 2 OCPUs total (post-June-2026 halving) shared with the Spring Boot app and OS — there is no spare core for Redpanda even at its stated floor. Naively following "official minimums" without adjusting for co-location will under-provision the app (CPU starvation, GC pauses, request latency spikes under any concurrent load) even if memory is tuned correctly per Pitfall 1.

**Why it happens:**
Vendor "minimum requirements" pages are written assuming Redpanda is the only workload on the box; this project's constraint (single VM, three co-resident workloads: Redpanda, Spring Boot, OS) is an edge case vendor docs don't address, and it's easy to read "2 cores minimum" as "fine, we have 2 cores" without noticing that leaves zero for everything else.

**How to avoid:**
Treat this as a resource-fraction problem, not a "does it start" problem: with `--overprovisioned` set (Pitfall 1) and `--smp` explicitly capped (e.g. `--smp 1` to intentionally limit Redpanda to a single logical core), accept and document a real CPU-tradeoff rather than assuming default tuning is safe. Load-test the deploy target under realistic request volume (not just "does it boot") before trusting it in the roadmap's definition of done — this project's actual traffic is a personal/portfolio-scale kanban board, which is likely genuinely low enough for shared 2-OCPU scheduling to work, but that must be verified, not assumed from vendor minimums written for a different scenario.

**Warning signs:**
- Roadmap or plan treats "Redpanda boots and topics work" as sufficient verification, without a concurrent-load check against the app's own API latency.
- No `--smp` flag set (Redpanda defaults to using all visible cores, contending directly with the JVM's own thread pool / virtual-thread carrier pool for CPU time).

**Phase to address:**
Infra-migration phase (resource budget decision), verified again once GitHub Actions CI/CD is live and a real deploy can be load-checked.

---

### Pitfall 3: Tempting to "compensate" for only 2 OCPUs by leaning harder into Java virtual threads — this collides with the project's own known HikariCP bug

**What goes wrong:**
CLAUDE.md already documents an unfixed HikariCP + Spring Boot virtual-threads bug in this codebase. Independently, research confirms this is a known, real class of problem: on Java 21–23 (this project is on Java 21), a virtual thread that blocks inside a `synchronized` block — which some JDBC driver / HikariCP internals still use — stays *pinned* to its carrier thread; if that block wraps a blocking I/O call (a DB query), the carrier thread is stuck too, and roughly 50–80% of virtual threads can end up pinned under DB-heavy load, collapsing exactly the concurrency benefit virtual threads were meant to provide. (JDK 24 removes the root cause via JEP 491, but this project is pinned to Java 21.) Given only 2 OCPUs post-halving, there will be a natural temptation to reach for virtual threads to "do more with less," which would activate this exact known-bad interaction under the new resource constraint rather than avoid it.

**Why it happens:**
"Fewer cores → try virtual threads for higher concurrency per core" is a reasonable-sounding but wrong inference here, because the bottleneck this project already has isn't thread scarcity — it's a documented pinning bug specific to this stack's HikariCP/JDBC combination on Java 21.

**How to avoid:**
Explicitly do NOT enable virtual threads as a response to the OCPU reduction. Keep the existing platform-thread request-handling model; if CPU/throughput pressure becomes real, address it via Redpanda/JVM memory-CPU budgeting (Pitfalls 1–2) and connection-pool sizing (Pitfall 4), not via a JDK concurrency model change that this project has already flagged as broken for its exact HikariCP setup. If virtual threads are ever revisited, that decision belongs to a dedicated phase with its own regression testing — not a side effect of an infra-migration phase under resource pressure.

**Warning signs:**
- Any PR/plan in this milestone that enables `spring.threads.virtual.enabled=true` (or equivalent) "to help with the reduced OCPU count."
- No explicit callout in the phase's plan/PR description addressing why virtual threads are *not* being touched, given the temptation is foreseeable.

**Phase to address:**
Infra-migration phase — this is a "don't do X" guardrail to state explicitly in the phase's scope/out-of-scope, not a feature to build.

---

### Pitfall 4: HikariCP pool sized for "always-on Postgres" assumptions breaks against Neon's scale-to-zero cold start

**What goes wrong:**
Neon's compute suspends entirely after a configurable idle period (default 5 minutes) and takes roughly 500ms–a few seconds to wake on the next query. A HikariCP pool with a `connectionTimeout` tuned for an always-warm RDS/EC2-hosted Postgres (the deleted deploy target) — typically a low value like the Hikari default 30s is actually generous, but pools sometimes get tuned tighter for "fail fast" behavior — can time out during exactly the first request after any idle period, surfacing as sporadic, hard-to-reproduce `SQLTransientConnectionException`/timeout errors that look like flaky infra rather than an expected, documented cold-start cost of the new stack. This is a regression risk specifically because it "worked fine" against the old always-on Postgres and won't be caught unless idle-period behavior is deliberately tested.
Separately, connecting HikariCP to Neon's **direct** (non-pooled) connection string for ordinary app traffic — rather than the `-pooler` connection string — bypasses Neon's own PgBouncer layer, which is what actually masks most cold-start latency from the app; using the direct string for everyday CRUD traffic gives up that mitigation for no benefit.

**Why it happens:**
Cold-start latency doesn't exist at all against a traditional always-on Postgres instance, so there's no prior pattern in this codebase to reuse or extend — the existing HikariCP config was written and tuned entirely against RDS. Neon's pooled-vs-direct connection-string distinction is easy to miss because both are valid, both connect successfully in a quick smoke test, and the difference only manifests under idle-then-request timing.

**How to avoid:**
Use Neon's pooled (`-pooler`) connection string for the app's HikariCP datasource. Set `connectionTimeout` comfortably above observed wake latency (a few seconds of margin, not Hikari's bare default) and treat the first connection attempt after idle as a legitimate slow path, not an error. Reserve the direct (non-pooled) connection string only for operations pgbouncer's transaction-mode pooling doesn't support (the new pre-merge DDL verification step, `CREATE INDEX CONCURRENTLY`, any Flyway/DDL script) — do not point the app's runtime pool at the direct string. If Neon's paid-plan option to configure idle timeout down to 1 minute (or up, to reduce cold-start frequency) is available on the plan tier used, decide deliberately whether frequent cold starts (cost-optimal) or a longer idle window (fewer cold starts, more compute-hours billed) matches this project's cost-guard intent — don't leave it at whatever the default happens to be without a documented choice.

**Warning signs:**
- HikariCP `connectionTimeout` left at or below Hikari's bare default with no explicit widening for Neon.
- App's `application.properties`/environment config pointing HikariCP at the non-`-pooler` Neon host.
- No test or manual verification step that exercises "first request after N minutes idle" against the actual Neon instance (a local H2/Testcontainers-only test suite cannot catch this — it's specific to the real deploy target).

**Phase to address:**
Infra-migration phase (Neon connection configuration step) — should be paired with the new pre-merge DDL verification step's design, since that step needs the *direct* connection string while the app's runtime datasource needs the *pooled* one.

---

### Pitfall 5: HikariCP pool size copied from the old EC2/RDS config now double-dips against Neon's own connection multiplexing, or conflicts with the DDL step's need for a direct connection

**What goes wrong:**
A HikariCP `maximumPoolSize` sized for direct-to-Postgres access (common guidance: pool size tied to `(core_count * 2) + effective_spindle_count` or similar) doesn't map cleanly onto a pooled Neon connection, where PgBouncer in front of Postgres is already multiplexing thousands of client-side connections onto a much smaller number of real Postgres backend connections. Over-sizing HikariCP "just in case" against a pooled endpoint wastes pool slots without protecting anything (PgBouncer, not HikariCP, is now the layer actually gating real backend connections) and can itself become a bottleneck if PgBouncer's own backend-connection ceiling is smaller than assumed.

**Why it happens:**
The existing HikariCP configuration was tuned once, against the deleted AWS RDS target, and the instinct during a "no-code-change" infra migration (as PROJECT.md explicitly frames this: "no JPA/Hibernate code changes") is to leave pool sizing untouched too — but pool sizing is a deployment-topology concern, not application code, and the topology genuinely changed (direct Postgres → PgBouncer-fronted serverless Postgres).

**How to avoid:**
Re-derive HikariCP pool sizing for the new topology rather than carrying the old number forward unexamined: keep the pool modest (this is a single-instance personal/portfolio app, not a fleet of instances competing for a shared connection budget) and confirm the chosen `maximumPoolSize` sits comfortably under whatever backend-connection ceiling Neon's pooled endpoint documents for the plan tier in use.

**Warning signs:**
- `maximumPoolSize` in `application.properties` unchanged from whatever value was set for the old RDS deployment, with no comment/decision recorded about why it's still correct for Neon.
- No plan step that reads Neon's own pooled-connection limits for the tier before finalizing pool size.

**Phase to address:**
Infra-migration phase (Neon connection configuration step) — a five-minute config review, but only if someone remembers to do it given the "no code changes needed" framing invites skipping it.

---

### Pitfall 6: GitHub Actions → Oracle VM SSH deploy repeats the exact insecure defaults every quick tutorial shows

**What goes wrong:**
Without AWS OIDC available, the natural fallback is an SSH-key-based deploy action (e.g. `appleboy/ssh-action`), and the most commonly copy-pasted tutorial configuration sets `StrictHostKeyChecking=no` with no `known_hosts` pinning "to make the CI run green faster" — this silently accepts any host key presented by whatever IP the workflow connects to, which is a real MITM opening (an attacker who can intercept the connection, or a misconfigured DNS/IP reuse scenario, can impersonate the deploy target and receive the app's deploy artifacts/secrets). A second common mistake specific to this scenario: generating the SSH keypair *on* the Oracle VM itself and copying the private key back into GitHub Secrets, rather than generating it locally and only ever placing the public key on the server — this means the private key existed on a shared/less-trusted machine at some point in its life.

**Why it happens:**
`StrictHostKeyChecking=no` is what nearly every "GitHub Actions + SSH deploy" blog post shows first, because pinning `known_hosts` correctly on a first-time CI run genuinely requires an extra step (fetching the host key out-of-band and storing it as a secret/workflow input) that tutorials skip for brevity.

**How to avoid:**
Generate the deploy keypair locally (not on the Oracle VM), add only the public key to the VM's `authorized_keys` for a dedicated, minimally-privileged deploy user (not the default `opc`/root account used for everything else). Fetch the Oracle VM's host key once (`ssh-keyscan`) and store it as a `known_hosts` GitHub Secret/step input so the workflow can verify the host identity on every run instead of disabling the check. Scope the deploy user's permissions to only what the deploy script needs (restart the app container, pull the new image) — not full root — so a leaked key has bounded blast radius.

**Warning signs:**
- Workflow YAML containing `StrictHostKeyChecking=no` or `-o UserKnownHostsFile=/dev/null`.
- Deploy SSH key logged into the CI, or generated by a step that runs `ssh-keygen` on the target VM itself.
- Deploy user is the same account used for interactive/admin access to the VM, rather than a separate, scoped account.

**Phase to address:**
Infra-migration phase (CI/CD pipeline step) — this is foundational to the deploy mechanism and should be correct from the first working deploy, not retrofitted after.

---

### Pitfall 7: Oracle Cloud's three independently-evaluated network layers (Security List + NSG + OS firewall) mean "I opened the port" doesn't mean the port is actually reachable — or worse, is reachable when it shouldn't be

**What goes wrong:**
Unlike AWS's single security-group model, OCI evaluates a subnet-wide Security List, a resource-scoped Network Security Group, and the instance's own OS-level firewall (iptables/firewalld) as three separate, additive (OR'd) layers, with no deny rules available in either cloud-level construct — a block at any single layer produces a silent connection timeout rather than an explicit rejection, and rules are additive so an NSG cannot be used to *narrow* what a Security List already permits. In practice this produces two opposite failure modes on this project: (a) hours spent debugging "the app isn't reachable" when the actual block is an OS-level iptables rule nobody thought to check because "the security list looks right," and (b) the inverse and more dangerous mistake — assuming a restrictive NSG is protecting a port, while the broader Security List attached to the subnet already allows it from `0.0.0.0/0`, silently exposing something (e.g. Redpanda's 9092 listener, or a debug endpoint) to the public internet despite an NSG that looks locked down.

**Why it happens:**
OCI's default Security Lists (created automatically with a new VCN) commonly ship permissive default rules, and the mental model carried over from AWS ("one security group, allow/deny is centralized there") doesn't map onto OCI's additive multi-layer model — leading to a false sense that tightening the NSG alone is sufficient.

**How to avoid:**
Explicitly audit all three layers together before considering the deploy "done": tighten the VCN's default Security List to only the ports genuinely needed publicly (almost certainly just 443/80 for the app, and 22 restricted to a known IP range or removed entirely in favor of a bastion/rotating-IP approach if feasible), keep Redpanda's Kafka listener (9092) and any Postgres-adjacent ports bound only to the instance's private/loopback interface (the app talks to Redpanda over `localhost` or the Docker-internal network — nothing about this project's design requires Redpanda's port to be internet-reachable at all), and verify with an actual external port scan (or `curl`/`nc` from an outside host) rather than trusting the console's rule list alone, since the console shows configured intent, not verified reachability.

**Warning signs:**
- Default VCN Security List still has its original permissive inbound rules unreviewed since VCN creation.
- Redpanda's 9092 (or Postgres, if ever locally relevant) port has any Security List or NSG rule allowing inbound from `0.0.0.0/0`.
- No external verification step (port scan / reachability test from outside the VM) in the deploy checklist — only "I looked at the console rules."

**Phase to address:**
Infra-migration phase (VM provisioning/networking step) — must be verified before the VM's public IP is treated as "live," not discovered later via a security incident.

---

### Pitfall 8: Silently leaving Oracle A1 Flex resource assumptions un-reverified against the tenancy's actual current allocation

**What goes wrong:**
Oracle cut the Always Free Ampere A1 allowance from 4 OCPU/24 GB to 2 OCPU/12 GB on June 15, 2026, with no public announcement — some existing tenancies discovered the change only when an instance was unexpectedly shut down or resized, and Oracle support gave conflicting answers about whether the new limit applies uniformly to all tenancies (including pay-as-you-go) or only fresh free-tier signups. Planning this milestone strictly against "2 OCPU/12GB" as a known-good, stable number risks being wrong in either direction: the operator's actual tenancy might still be grandfathered at the old 4/24 allocation (in which case resource budgeting in Pitfalls 1–2 is overly conservative), or a currently-running instance could be silently resized/reclaimed mid-milestone if the tenancy is affected retroactively.

**Why it happens:**
This is an externally-imposed, undocumented platform change happening concurrently with this milestone's planning — the kind of moving-target constraint that's easy to treat as a fixed input ("we have 2 OCPU/12GB, full stop") when it may not actually be fixed for this specific tenancy.

**How to avoid:**
Before finalizing resource budgets (Pitfalls 1–2), check the actual current instance shape/limits in the OCI console for this specific tenancy rather than assuming the publicly-reported 2 OCPU/12GB figure applies unmodified. Build the Redpanda/JVM memory budget to be adjustable (config values, not hardcoded assumptions baked into a Dockerfile) so it can be revised without a full redeploy if the tenancy's real allocation turns out to differ from the assumption. Treat the free-tier VM as a "verify before and after deploy" resource, not a "set once" one, given Oracle's own inconsistent public communication about this exact change.

**Warning signs:**
- Resource budget/config hardcodes "2 OCPU/12GB" as gospel without a note about verifying the tenancy's actual current shape.
- No re-check step scheduled if the milestone spans more than a few days (given Oracle's precedent of silent mid-flight changes).

**Phase to address:**
Infra-migration phase (VM provisioning step, first action) — a five-minute console check that should happen before, not after, resource-budget decisions are locked in.

---

### Pitfall 9: Assuming Confluent/Redpanda Schema Registry's default BACKWARD compatibility mode is "just on" without deciding it matches this pipeline's actual evolution needs

**What goes wrong:**
Schema Registry's out-of-the-box default compatibility mode is `BACKWARD` (a new schema must be able to read data written with the immediately-prior schema — this is what lets a consumer rewind to the beginning of a topic and still deserialize old messages with the latest schema). Left un-set, every one of the 5 `ActivityEvent` record types inherits this default silently. If a future schema change for any event type happens to need `FORWARD` semantics instead (old consumers must be able to read data produced by a newer schema — relevant if a producer deploys ahead of a consumer, which is realistic in a single-VM deploy where the app container might restart mid-rollout), the default `BACKWARD` mode will *reject* that change at registration time with an opaque compatibility-check failure, discovered at deploy time rather than during design.

**Why it happens:**
BACKWARD is genuinely the sensible default for most systems and most tutorials don't dwell on it, so it's easy to never explicitly decide "is BACKWARD actually right for our 5 event types" and just inherit whatever the registry ships with.

**How to avoid:**
Explicitly decide and document (not just accept the default) which compatibility mode applies to the `kanban.activity` subject(s) for each of the 5 sealed `ActivityEvent` types, based on this project's actual deploy pattern (single VM, producer and consumer are the same app process redeployed together — so `FULL` compatibility, the strictest, may actually be affordable and safer here than the default `BACKWARD`, since there's no independent producer/consumer deploy cadence to accommodate). Set the mode explicitly in the Schema Registry subject config rather than relying on the global default, so it's a visible, reviewable decision in the phase's plan.

**Warning signs:**
- No explicit `compatibility` setting recorded anywhere for the new subjects — relying entirely on registry default.
- Schema registration failing at deploy time with a compatibility-check error that wasn't anticipated during design/review.

**Phase to address:**
Schema Registry phase (initial schema design/registration step) — a one-time decision made once, at introduction, cheaper to get right up front than to renegotiate after the 5 types have real Avro schemas registered.

---

### Pitfall 10: Avro's strict "no undefined fields" rule silently breaks the existing JSON contract's implicit permissiveness

**What goes wrong:**
The existing pipeline's 5 sealed-interface event record types were designed and have shipped as JSON, where fields can be added/omitted with only an implicit, convention-based agreement (exactly the risk this migration exists to close, per PROJECT.md). Avro is strict in the opposite direction: a field either has an explicit default or it's required — there is no equivalent to "just omit this optional JSON field and let the consumer's deserializer quietly ignore it." Any of the 5 event types that currently rely on Java's sealed-record/JSON-null-omission conventions (e.g., an optional field left null or absent in some event variants) needs deliberate Avro schema field defaults (commonly `null` unions, `["null", "string"], "default": null`) — and importantly, a field whose *default* value is `null` becomes ambiguous with a field that was *explicitly set* to `null`, which the existing JSON contract may currently distinguish semantically (e.g., "not yet assigned" vs. "explicitly cleared").

**Why it happens:**
JSON's permissiveness is exactly what let 5 event types ship quickly without a shared schema in the first place; migrating to Avro's stricter model surfaces every place that permissiveness was silently relied on, which is invisible until the Avro schema is actually written field-by-field against the real historical event shapes.

**How to avoid:**
Before writing each Avro schema, enumerate every field of all 5 existing record types and explicitly classify each as required-with-no-default, or optional-with-an-explicit-default — do not default everything to nullable-with-null-default reflexively, since that's exactly the ambiguity trap described above. Where the JSON contract currently uses field absence to mean something distinct from an explicit null, either introduce an explicit sentinel/wrapper in the Avro schema (e.g., a dedicated "was this field ever set" flag) or confirm no current event actually depends on that distinction (likely, but must be confirmed against real historical event data, not assumed).

**Warning signs:**
- Avro schema written by mechanically converting the Java record's field list without cross-checking against what values have actually been produced/consumed historically for each field (including whether any field has ever legitimately been `null` vs. absent).
- No test asserts round-trip fidelity of the actual existing 5 sealed record types (not synthetic examples) through the new Avro serializer/deserializer pair.

**Phase to address:**
Schema Registry phase (schema authoring step, before first registration).

---

### Pitfall 11: The existing dead-letter topic's "byte-fidelity guarantee" (proven for JSON) needs independent re-verification for Avro — it does not carry over automatically

**What goes wrong:**
PROJECT.md states the existing DLT preserves byte-fidelity for poison JSON messages — this was proven against the JSON pipeline specifically. Once `KafkaAvroDeserializer` (or a Protobuf equivalent) is introduced, the failure mode that lands a message on the DLT changes fundamentally: a message can now fail *specifically because* it can't be decoded against a registered schema (unknown schema ID, incompatible writer/reader schema, wire-format magic-byte corruption) rather than because of a JSON parse error. The critical, easy-to-miss detail: a message that has already failed Avro deserialization cannot be safely re-serialized through the same `KafkaAvroSerializer` path to publish to the DLT — if the DLT-publishing recoverer is implemented (or left, from the JSON-era code) using the same typed serializer as the main pipeline, it will itself throw trying to re-encode a payload it just failed to decode, and the "byte-fidelity" guarantee silently breaks exactly for the poison-message case it exists to handle.

**Why it happens:**
The DLT's existing implementation and test suite were built and proven entirely against JSON deserialization failures; nothing in that existing work exercises what happens when the failure is a schema-registry-specific one (unknown schema ID, registry unreachable, writer/reader incompatibility), so the gap is invisible until Avro is actually introduced and a real poison Avro message is produced.

**How to avoid:**
Explicitly reconfigure the DLT-publishing path to use `ErrorHandlingDeserializer` wrapping the new `KafkaAvroDeserializer`/Protobuf deserializer, with `DeadLetterPublishingRecoverer` configured to serialize the DLT-bound record using a raw byte-array serializer (not the same Avro/Protobuf-aware serializer used for the main pipeline) — this is the only way to preserve the original, possibly-undecodable bytes exactly as the JSON-era DLT did. Write a new test (this project already has convention for testing against real infra, not mocks — reuse that discipline) that specifically produces a message engineered to fail Avro/schema-registry deserialization (e.g., wrong/unregistered schema ID in the wire-format magic byte) and asserts it lands on the DLT with byte-for-byte fidelity, re-proving the guarantee for the new format rather than assuming it inherited automatically from the JSON-era work.

**Warning signs:**
- DLT-publishing recoverer configured with the same Avro-aware serializer used elsewhere in the pipeline, rather than a raw byte-array serializer.
- The existing DLT byte-fidelity test (proven for JSON) is treated as still covering this guarantee post-migration, with no new Avro-specific poison-message test added.
- No test exercises "schema registry unreachable at consume time" as a distinct failure mode from "message bytes are corrupt" — both should land safely on the DLT, but by different code paths worth verifying separately.

**Phase to address:**
Schema Registry phase (consumer/DLT integration step) — this is the single highest-risk item in the schema registry migration specifically because it silently regresses an already-proven guarantee rather than failing to build a new one.

---

### Pitfall 12: Registering Avro schemas for the 5 already-shipped event types against the real, already-populated topic without a rollback/compatibility rehearsal

**What goes wrong:**
The 5 `ActivityEvent` types have already shipped to a real topic with real historical JSON messages sitting in it (per PROJECT.md: proven end-to-end against real infra, not mocks, and already in production use as of v1.1). Registering new Avro schemas and cutting producers/consumers over without first rehearsing the cutover against a copy of this real topic (or at minimum its real historical event shapes) risks two failure classes only visible with real data: (1) a historical JSON message shape that doesn't cleanly map to the newly-authored Avro schema (see Pitfall 10) causing consumer failures on old, already-committed messages if any replay/rewind ever happens; (2) a subject-naming-strategy mismatch (Schema Registry supports `TopicNameStrategy`, `RecordNameStrategy`, `TopicRecordNameStrategy` — picking the wrong one for a topic that carries multiple distinct sealed-interface subtypes on one topic can cause schemas to collide or fail to resolve per-subtype).

**Why it happens:**
Schema Registry compatibility checks validate *new schema vs. previously registered schema*, not *new schema vs. every historical message byte actually sitting in the topic* — so a schema can pass the registry's compatibility check while still failing against specific historical data if the sealed interface's 5 subtypes were multiplexed onto a single topic in a way the chosen subject-naming-strategy doesn't cleanly separate.

**How to avoid:**
Follow the general safe-migration pattern found for this exact scenario (dual-write phase → backfill/convert historical data → migrate consumers one-by-one → cut producers over → decommission old path after a grace period) rather than a hard cutover. Explicitly choose and test `RecordNameStrategy` or `TopicRecordNameStrategy` (not the default `TopicNameStrategy`) given 5 distinct sealed-interface record types are known to share (or may share) one topic, so each subtype gets its own schema subject rather than colliding. Before cutover, run the new Avro (de)serializer against a sample pulled from the real historical topic data (not synthetic test fixtures) to catch Pitfall 10-style field-shape mismatches against what actually shipped.

**Warning signs:**
- No plan step to sample/replay real historical topic data through the new Avro schema before full cutover — only synthetic/newly-constructed test events are exercised.
- Subject naming strategy left at Schema Registry's default without a deliberate decision recorded for a topic carrying multiple sealed-interface subtypes.

**Phase to address:**
Schema Registry phase (cutover step) — the highest-stakes single decision in this pitfall list, because unlike a greenfield Avro rollout, this one must not break consumption of data that's already real and already shipped.

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|-----------------|------------------|
| Leaving Redpanda at fully auto-detected memory/CPU config ("it started, ship it") | Zero tuning work, fastest path to a working demo | OOM kills or CPU starvation of the app under any real concurrent load, discovered in production rather than during setup | Never for the actual deploy target; acceptable only for a throwaway local smoke test that will never see real traffic |
| Copying the old EC2/RDS HikariCP pool config unchanged onto Neon ("no code changes needed" per PROJECT.md, so why touch config) | Saves a config review step, matches the "zero JPA/Hibernate changes" framing | Cold-start timeouts and/or wasted pool slots against a topology (pooled serverless Postgres) the old config was never designed for | Never — this is a five-minute review, not a code change, and the framing that inspired skipping it doesn't actually apply to connection-pool tuning |
| `StrictHostKeyChecking=no` in the GitHub Actions SSH deploy step | Gets CI green fastest, matches most tutorials | MITM exposure on every deploy run indefinitely | Never for a real deploy target; arguably tolerable for a fully throwaway, secrets-free scratch VM only |
| Leaving Schema Registry compatibility mode at its global default rather than deciding per-subject | No extra config, ships faster | A future schema change may be silently rejected at the worst possible time (mid-deploy), or worse, an incompatible change is silently *allowed* if the wrong mode was inherited | Acceptable only as a conscious, documented choice that the default happens to match this project's actual needs — never as an unexamined default |
| Reusing the same Avro (de)serializer for the DLT-publishing path as the main pipeline | Less code, one serializer to maintain | Breaks byte-fidelity for exactly the poison-message case the DLT exists to handle (Pitfall 11) | Never |
| Hard cutover from JSON to Avro without a dual-write/backfill phase | Faster migration, less code to write temporarily | Risk of breaking consumption of already-shipped historical data with no rollback path | Acceptable only if the topic can be safely truncated/replayed from scratch with no data-loss consequence (unlikely here, since v1.1 already treats this activity log as real, already-verified production data) |

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|-------------|-----------------|-------------------|
| Redpanda on a shared VM | Leaving memory/CPU auto-detection on, assuming Redpanda's stated "minimums" already account for co-located workloads | Set `--overprovisioned`, explicit `--memory`/`--smp` caps, and cgroup limits on both containers |
| HikariCP → Neon | Pointing the app's runtime datasource at Neon's direct (non-`-pooler`) connection string | Use the `-pooler` string for the app; reserve the direct string only for the DDL-verification step and migrations |
| HikariCP → Neon | `connectionTimeout` left at a value tuned for always-on RDS, too tight for Neon's cold-start wake latency | Widen `connectionTimeout` with margin above observed wake latency; treat first-request-after-idle as an expected slow path |
| GitHub Actions → Oracle VM | SSH deploy step with `StrictHostKeyChecking=no`, keypair generated on the target VM itself | Pin `known_hosts` via `ssh-keyscan` stored as a secret; generate keys locally; use a scoped deploy-only user |
| Oracle Cloud networking | Treating NSG rules as authoritative/restrictive without checking the (often permissive-by-default) Security List and OS firewall too | Audit and verify all three layers together; confirm with an actual external reachability test, not just console rule review |
| Confluent/Redpanda Schema Registry | Leaving compatibility mode at the global default (`BACKWARD`) without deciding it fits this pipeline's actual producer/consumer deploy cadence | Explicitly set and document compatibility mode per subject based on this project's single-VM, co-deployed producer/consumer reality |
| Avro DLT integration | DLT-publishing recoverer re-using the same schema-aware serializer as the main pipeline | Configure `DeadLetterPublishingRecoverer` with a raw byte-array serializer specifically for the DLT path |
| Redpanda's built-in Schema Registry vs. Confluent client libraries | Assuming full drop-in compatibility with Confluent's Java client for Protobuf (especially schemas with map fields) or assuming identical namespace-tag handling for Avro | Test the actual client library against Redpanda's registry specifically (not just against Confluent's) before committing to Protobuf with map fields; expect Avro namespace-tag comparison differences |

## Performance Traps

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|-----------------|
| Redpanda's default memory auto-claim on a shared VM | OOM-killer terminating the app or Redpanda under load; intermittent, hard-to-reproduce crashes that correlate with traffic bursts, not code changes | Explicit `--memory`/`--overprovisioned`/cgroup limits (Pitfall 1) | As soon as both processes are under any concurrent load simultaneously — not a "scale" threshold, a "first real traffic" threshold |
| HikariCP pool sized for direct-Postgres access against a PgBouncer-fronted Neon endpoint | Either wasted idle pool slots, or (if oversized enough) hitting Neon's backend-connection ceiling under burst load | Re-derive pool size for the pooled topology; check the tier's documented backend-connection ceiling | Only under concurrent load spikes; invisible in single-request manual testing |
| Reflexive nullable-with-null-default Avro fields for every optional JSON field | Consumers silently misinterpret "field was never set" vs. "field was explicitly cleared" for historical vs. new events | Explicit per-field classification against real historical event shapes (Pitfall 10) | Only surfaces when a field that used to carry "absent vs. null" semantics is actually exercised in that ambiguous way — easy to miss until real data is checked |
| Reusing Avro-aware serializer for DLT publishing | DLT recoverer itself throws on poison messages, losing the exact failures it exists to preserve | Byte-array serializer specifically for the DLT path (Pitfall 11) | As soon as the first genuinely undecodable Avro message arrives — not a load/scale issue, a correctness issue that manifests on the very first poison message |

## Security Mistakes

| Mistake | Risk | Prevention |
|---------|------|------------|
| `StrictHostKeyChecking=no` in the GitHub Actions → Oracle VM SSH deploy step | MITM interception of deploy traffic/secrets on every CI run | Pin `known_hosts` via a fetched host-key secret; never disable host verification |
| Deploy SSH keypair generated on the target VM and copied into GitHub Secrets | Private key existed on a less-trusted machine at some point; harder to prove it was never exposed | Generate keypair locally, only ever place the public half on the server |
| Redpanda's Kafka listener (9092) accidentally reachable from `0.0.0.0/0` due to OCI's additive Security List + NSG model | Public internet can read the entire activity-event stream (includes `userId`s) or inject forged events | Bind Redpanda's listener to the private/internal interface only; verify with an external reachability test, not just console rules |
| Assuming a restrictive NSG alone protects a port when the subnet's Security List already allows it broadly | False sense of security — the more permissive of the two constructs always wins (OR logic, no deny rules) | Audit Security List + NSG + OS firewall together as one set, not independently |
| DLT-publishing path re-using the main pipeline's Avro serializer, causing it to throw and potentially get skipped/swallowed on poison messages | Loses the audit trail for exactly the malformed/malicious messages a DLT exists to capture | Byte-array serializer for the DLT path specifically (Pitfall 11) |

## UX Pitfalls

Not directly applicable — this milestone is infrastructure/schema-registry work with no user-facing surface change (per PROJECT.md, "no producer/consumer/DLQ code changes needed" for the Redpanda swap, and the Schema Registry work is a serialization-layer change behind the existing `GET /boards/{boardId}/activity` endpoint). The closest analog is operational visibility, covered under Performance Traps and the "Looks Done But Isn't" checklist below.

## "Looks Done But Isn't" Checklist

- [ ] **Redpanda deployed and topics work:** Often missing explicit memory/CPU tuning for the shared VM — verify `--overprovisioned` and explicit `--memory`/`--smp` are set, not left at auto-detected defaults, and that a concurrent-load test (not just a boot check) has been run against the real deploy target.
- [ ] **Neon connection configured:** Often missing the pooled-vs-direct distinction — verify the app's runtime HikariCP datasource uses the `-pooler` string and the DDL-verification step uses the direct string, and that `connectionTimeout` has been widened and tested against a real cold-start (not just a warm-instance smoke test).
- [ ] **GitHub Actions deploy pipeline green:** Often missing real host-key verification — verify `known_hosts` is pinned (not `StrictHostKeyChecking=no`) and the deploy key was generated locally, not on the target VM.
- [ ] **Oracle VM "reachable" and "secure":** Often verified only via the OCI console's rule list — verify with an actual external reachability test (port scan / `curl` from outside) against all three layers (Security List, NSG, OS firewall) together, and re-check the tenancy's actual current OCPU/RAM allocation given Oracle's undocumented June 2026 change.
- [ ] **Schema Registry wired up, 5 Avro schemas registered:** Often missing a real historical-data compatibility check — verify the new schemas were tested against samples of the real, already-shipped topic data (not just synthetic fixtures), and that compatibility mode was a deliberate per-subject decision, not an inherited default.
- [ ] **Dead-letter topic "still works" after the Avro migration:** Often assumed to inherit the JSON-era byte-fidelity proof automatically — verify a *new* test specifically engineers an Avro/schema-registry-specific failure (bad schema ID, registry unreachable) and confirms byte-for-byte DLT preservation, since this is a different failure class than the JSON-era test covered.
- [ ] **Pre-merge DDL verification step "ported" to the new deploy target:** Often assumed to be a drop-in replacement of the old (AWS) script — verify it actually runs against Neon's *direct* (non-pooled) connection string and accounts for Neon's cold-start latency in whatever CI timeout it runs under.

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---------|----------------|-----------------|
| Redpanda/app OOM-killed in production due to un-tuned memory (Pitfall 1) | LOW | Add `--overprovisioned` and explicit memory caps, redeploy; no data loss expected since the activity log is supplementary, not the system of record |
| Neon cold-start timeouts surfacing as intermittent errors (Pitfall 4) | LOW | Widen `connectionTimeout`, switch to the pooled connection string if not already, redeploy |
| SSH deploy key or host verification found to be insecurely configured (Pitfall 6) | MEDIUM | Rotate the deploy key immediately, regenerate locally, re-pin `known_hosts`, audit CI logs for any prior exposure |
| Discovering Redpanda's port was publicly reachable (Pitfall 7) | MEDIUM | Immediately correct the Security List/NSG binding to internal-only, verify externally, treat as a config-hygiene incident given synthetic/portfolio data is at stake, not real user financial data |
| Avro schema rejected at registration due to unanticipated compatibility-mode conflict (Pitfall 9) | LOW | Revise the schema or explicitly change the subject's compatibility mode after review — caught at registration time, before any data is at risk, precisely because Schema Registry enforces this synchronously |
| DLT byte-fidelity found broken for Avro poison messages post-migration (Pitfall 11) | MEDIUM | Reconfigure `DeadLetterPublishingRecoverer` to use a byte-array serializer, add the missing poison-message test, redeploy; audit any DLT messages lost in the gap (acceptable loss for a non-critical activity log, but should be documented) |
| Historical JSON event data found incompatible with a newly-registered Avro schema after cutover (Pitfall 12) | HIGH | Roll back producers to JSON (if the dual-write/rollback path was actually kept available per the recommended migration pattern) while the schema is fixed; this is the single most expensive recovery in this list, which is exactly why Pitfall 12's prevention (rehearse against real historical data before cutover) matters most |

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---------|-------------------|----------------|
| Redpanda default memory auto-claim starves co-located app (Pitfall 1) | Infra-migration phase | `--overprovisioned` and explicit memory caps set; concurrent-load test run against real deploy target with no OOM events |
| Redpanda's documented minimums already consume all available CPU (Pitfall 2) | Infra-migration phase | `--smp` explicitly capped; app latency measured under concurrent load on the actual shared VM, not just "it boots" |
| Temptation to enable virtual threads to compensate for reduced OCPUs (Pitfall 3) | Infra-migration phase | Phase plan/PR explicitly states virtual threads are out of scope, given the known HikariCP interaction |
| HikariCP cold-start timeout against Neon (Pitfall 4) | Infra-migration phase | `connectionTimeout` widened; a real test/manual check exercises a post-idle-period request against the actual Neon instance |
| HikariCP pool sizing not re-derived for pooled Neon topology (Pitfall 5) | Infra-migration phase | Pool size documented as a deliberate decision referencing Neon's pooled-connection ceiling, not carried over from the old RDS config unexamined |
| Insecure SSH deploy key/host verification (Pitfall 6) | Infra-migration phase (CI/CD step) | Workflow YAML pins `known_hosts`; deploy key confirmed generated locally, not on the target VM |
| OCI multi-layer network misconfiguration (Pitfall 7) | Infra-migration phase (VM provisioning step) | External reachability test confirms only intended ports are reachable, across Security List + NSG + OS firewall together |
| Un-reverified Oracle A1 Flex resource assumption (Pitfall 8) | Infra-migration phase (first provisioning step) | Actual tenancy shape checked in-console before resource budgets are finalized |
| Schema Registry compatibility mode left at unexamined default (Pitfall 9) | Schema Registry phase | Compatibility mode explicitly set and documented per subject before first schema registration |
| Avro's strict field-default model breaking implicit JSON conventions (Pitfall 10) | Schema Registry phase | Every field of all 5 event types classified (required vs. defaulted) against real historical event shapes, not mechanically converted |
| DLT byte-fidelity guarantee not re-verified for Avro (Pitfall 11) | Schema Registry phase | New test specifically engineers an Avro/schema-registry deserialization failure and confirms byte-for-byte DLT preservation |
| Hard cutover of already-shipped event types without rehearsal against real historical data (Pitfall 12) | Schema Registry phase | New Avro schemas tested against a sample of real historical topic data before producer cutover; subject-naming-strategy explicitly chosen for the 5-subtype-per-topic reality |

## Sources

- [Requirements and Recommendations | Redpanda Self-Managed](https://docs.redpanda.com/current/deploy/redpanda/manual/production/requirements/) — official docs, higher confidence
- [Sizing Guidelines | Redpanda Self-Managed](https://docs.redpanda.com/current/deploy/redpanda/manual/sizing/) — official docs, higher confidence
- [Need for speed: 9 tips to supercharge Redpanda (Redpanda blog)](https://www.redpanda.com/blog/top-performance-considerations-redpanda) — vendor blog, LOW-MEDIUM confidence
- [Cluster Configuration Properties | Redpanda Streaming](https://docs.redpanda.com/current/reference/properties/cluster-properties/) — official docs, higher confidence
- [Solving challenges caused by Out Of Memory (OOM) Killer in Linux (Redpanda blog)](https://www.redpanda.com/blog/solve-out-of-memory-killer-events) — vendor blog, LOW-MEDIUM confidence
- [Redpanda in Production: 3 Traps I Fell Into (and How to Avoid Them) — DEV Community](https://dev.to/devflex-pro/redpanda-in-production-3-traps-i-fell-into-and-how-to-avoid-them-4h91) — web, LOW confidence
- [Redpanda Schema Registry | Redpanda Self-Managed](https://docs.redpanda.com/current/manage/schema-reg/schema-reg-overview/) — official docs, higher confidence
- [Incompatibility with Confluent Schema Registry client · Issue #5771 · redpanda-data/redpanda (GitHub)](https://github.com/redpanda-data/redpanda/issues/5771) — primary source (issue tracker), MEDIUM confidence
- [RedPanda's Schema Registry handles redundant namespace tags in Avro differently · Issue #11912 (GitHub)](https://github.com/redpanda-data/redpanda/issues/11912) — primary source, MEDIUM confidence
- [TSB-2025-18 Schema Registry failures with Confluent Java client and protobuf schemas — Redpanda Support](https://support.redpanda.com/hc/en-us/articles/29731017915671-TSB-2025-18-Schema-Registry-failures-when-using-the-Confluent-Java-client-for-Schema-Registry-and-protobuf-schemas) — official vendor advisory, higher confidence
- [Connection latency and timeouts - Neon Docs](https://neon.com/docs/connect/connection-latency) — official docs, higher confidence
- [What Postgres hosting options automatically pause the database when there are no active connections? - Neon FAQs](https://neon.com/faqs/postgres-hosting-options-auto-pause-database) — official docs, higher confidence
- [Connection pooling - Neon Docs](https://neon.com/docs/connect/connection-pooling) — official docs, higher confidence
- [Choosing your connection method - Neon Docs](https://neon.com/docs/connect/choose-connection) — official docs, higher confidence
- [Neon Postgres Review: Serverless PostgreSQL That Actually Scales to Zero — Medium](https://medium.com/@philmcc/neon-postgres-review-serverless-postgresql-that-actually-scales-to-zero-ee14d4e109ba) — web, LOW confidence
- [How to Tune HikariCP for Maximum Throughput in Spring Boot](https://oneuptime.com/blog/post/2026-01-25-tune-hikaricp-maximum-throughput-spring-boot/view) — web, LOW confidence
- [The Hidden Scaling Problem in Microservices: Why HikariCP Alone Can Crash Your Database Under High Traffic — Medium](https://thegeekplanets.medium.com/the-hidden-scaling-problem-in-microservices-why-hikaricp-alone-can-crash-your-database-52e463e768f5) — web, LOW confidence
- [Managing deploy keys - GitHub Docs](https://docs.github.com/en/authentication/connecting-to-github-with-ssh/managing-deploy-keys) — official docs, higher confidence
- [Using secrets in GitHub Actions - GitHub Docs](https://docs.github.com/actions/security-guides/using-secrets-in-github-actions) — official docs, higher confidence
- [8 GitHub Actions Secrets Management Best Practices - StepSecurity](https://www.stepsecurity.io/blog/github-actions-secrets-management-best-practices) — web, LOW confidence
- [Security Lists vs NSGs in OCI — You're Probably Using the Wrong One — Medium](https://medium.com/@sayeedamodix/security-lists-vs-nsgs-in-oci-youre-probably-using-the-wrong-one-and-here-s-the-proof-301cdc8b3ad4) — web, LOW-MEDIUM confidence (cross-checked against official OCI docs below)
- [Network Security Groups - Oracle Docs](https://docs.oracle.com/en-us/iaas/Content/Network/Concepts/networksecuritygroups.htm) — official docs, higher confidence
- [Security Lists - Oracle Docs](https://docs.oracle.com/en-us/iaas/Content/Network/Concepts/securitylists.htm) — official docs, higher confidence
- [Oracle Quietly Halves Free Tier Ampere A1 Compute Limits with No Public Announcement - InfoQ](https://www.infoq.com/news/2026/07/oracle-cloud-free-tier-limits/) — tech press, MEDIUM confidence
- [Oracle Cloud free tier 2026: 4 OCPU/24GB cut to 2 OCPU/12GB — TerminalBytes](https://terminalbytes.com/oracle-cloud-free-tier-changes-2026/) — web, LOW-MEDIUM confidence (cross-checked against InfoQ/heise)
- [Oracle halves free cloud resources — heise online](https://www.heise.de/en/news/Oracle-halves-free-cloud-resources-11334516.html) — tech press, MEDIUM confidence
- [Always Free A1.Flex instance disabled — Oracle Cloud Customer Connect community](https://community.oracle.com/customerconnect/discussion/966208/always-free-a1-flex-instance-disabled-shows-contact-customer-support-to-reenable-after-trial-end) — primary source (user report), LOW-MEDIUM confidence
- [Schema Evolution & Compatibility Types | Backward, Forward, Full, Transitive — Confluent Documentation](https://docs.confluent.io/platform/current/schema-registry/fundamentals/schema-evolution.html) — official docs, higher confidence
- [Apache Avro for Kafka | Serialization, Schema, KafkaAvroSerializer — Confluent Documentation](https://docs.confluent.io/platform/current/schema-registry/fundamentals/serdes-develop/serdes-avro.html) — official docs, higher confidence
- [Kafka SerDes | Supported Formats, Subject Naming Strategies — Confluent Documentation](https://docs.confluent.io/platform/current/schema-registry/fundamentals/serdes-develop/index.html) — official docs, higher confidence
- [How to Handle Schema Evolution with Kafka and Avro — oneuptime](https://oneuptime.com/blog/post/2026-01-24-handle-schema-evolution-kafka-avro/view) — web, LOW confidence
- [Dead letter queues in Kafka: patterns and pitfalls — Factor House](https://factorhouse.io/articles/dead-letter-queues-kafka/) — web, LOW-MEDIUM confidence
- [Kafka Connect Deep Dive – Error Handling and Dead Letter Queues — Confluent blog](https://www.confluent.io/blog/kafka-connect-deep-dive-error-handling-dead-letter-queues/) — vendor blog, MEDIUM confidence
- [How to Handle Kafka Consumer Deserialization Errors — oneuptime](https://oneuptime.com/blog/post/2026-01-24-handle-kafka-consumer-deserialization-errors/view) — web, LOW confidence
- [Java 24 Fixes the Last Virtual-Threads Problem: synchronized Without Pinning — Marvin Richter](https://marvin-richter.de/en/blog/virtual-threads-java-24-synchronized-fix/) — web, LOW-MEDIUM confidence
- [Java Virtual Threads: The Pinning Problem, the Deadlock, and the Fix in Java 24 — Shubham Raizada's Blog](https://shbhmrzd.github.io/java/concurrency/virtual-threads/2026/04/25/java-virtual-threads-pinning-and-the-deadlock-problem.html) — web, LOW-MEDIUM confidence
- [JDBC and Virtual Threads problems with Hibernate in Java — Luca Berton](https://lucaberton.com/blog/jdbc-virtual-threads-hibernate-java-problems/) — web, LOW confidence
- Project-specific reasoning (existing documented HikariCP + virtual-threads bug, resource split with the app JVM, deleted AWS deploy target, already-proven DLT byte-fidelity for JSON) synthesized from `.planning/PROJECT.md` and CLAUDE.md, not externally sourced.

**Note on confidence:** No dedicated MCP research provider (Context7/Exa/Tavily) was configured/available for this environment's live query; all findings were fetched via general web search (Brave-backed), so per the classify-confidence seam every general web claim here is tagged LOW confidence except where explicitly marked as official vendor documentation (Redpanda, Neon, GitHub, Oracle, Confluent) or a primary source (vendor GitHub issue tracker, official support advisory), which the seam and this project's own established convention (see the prior Kafka-feature PITFALLS.md research) treat as more reliable, though still not independently cross-verified per individual claim. The June 2026 Oracle free-tier reduction (Pitfall 8) is corroborated across three independent tech-press sources (InfoQ, heise, TerminalBytes) plus a primary user report on Oracle's own community forum, and is treated as MEDIUM confidence accordingly.

---
*Pitfalls research for: Infra migration (Oracle Cloud + Redpanda + Neon + GitHub Actions SSH deploy) and Schema Registry (Avro/Protobuf) migration added to an existing, working Spring Boot/Kafka system*
*Researched: 2026-08-03*
