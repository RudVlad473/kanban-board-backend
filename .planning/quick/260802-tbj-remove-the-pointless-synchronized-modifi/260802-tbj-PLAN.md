---
phase: quick/260802-tbj
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java
autonomous: true
requirements: [QUICK-260802-tbj]

estimate:
  tokens: 14000
  raw_tokens: 14000
  tasks: 1
  confidence: low

must_haves:
  truths:
    - "`RandFlakeGenerator.generateRandflake()` declares no mutual-exclusion modifier — its signature is exactly `public String generateRandflake()`."
    - "The generated id is byte-for-byte the same construction as before: `(millisSinceCustomEpoch << 23) | threadLocalRandom23Bits`, rendered with `Long.toString(id, 36)`. No change to bit layout, radix, length distribution, or entropy source."
    - "Every entity insert path still produces a non-null, non-blank id — the full test suite, which creates users, boards, columns, tasks, subtasks and activity-log rows through real Spring wiring against H2, is green."
    - "No method or block in `src/main` declares mutual exclusion any more — the anchored, comment-immune scan over `src/main/**/*.java` returns zero code hits."
    - "A line comment in the class records why the removed lock was unnecessary, so the next reader (or agent) does not reinstate it on the mistaken assumption that an id generator needs one."
    - "`./gradlew spotlessCheck` passes and `./gradlew test` passes, including ErrorProne's ERROR-severity hard gate on `compileJava`."
    - "Exactly one source file changed; `build.gradle`, `application.properties`, and every test file are untouched."
  artifacts:
    - "src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java"
  key_links:
    - "`RandFlakeGenerator.generateRandflake()` -> `BaseEntity.id` via `@RandFlakeId` / `@IdGeneratorType(RandFlakeGenerator.class)` — this annotation chain is the *only* path by which the method is ever invoked (there are zero direct callers anywhere in `src/`), and it sits on the `@MappedSuperclass` every entity extends. Any behavioral change here surfaces as broken inserts across all six entity types at once, which is exactly why the full suite is the right gate."
    - "the removed monitor -> memory visibility of this class's state — the monitor's happens-before edge was only load-bearing if the method read or wrote shared mutable state. It has no instance fields, and both statics are `final long` primitives. If a mutable field (a sequence counter, a last-timestamp) is ever added to this class, the locking question reopens and this removal must be revisited; that is precisely what the new comment is there to flag."
    - "`ThreadLocalRandom.current()` -> per-thread confinement — *this*, not the monitor, is what makes concurrent calls safe. Swapping it for a shared `Random`/`SecureRandom` field later would not reintroduce a correctness bug (both are thread-safe) but would reintroduce the contention this change removes."
---

<objective>
Delete the `synchronized` modifier from `RandFlakeGenerator.generateRandflake()` and leave a short comment in its place explaining why no lock is required — so the next reader does not put it back.

Purpose: this method is the Hibernate `IdentifierGenerator` behind `@RandFlakeId`, so it runs on every single entity insert in the application. It guards no shared mutable state: the class has no instance fields, its two statics are `final` primitives, its randomness comes from thread-confined `ThreadLocalRandom`, and everything else is a local. The monitor buys nothing and costs a real CAS pair plus a serialization point on the hottest write path in the app.

Output: one modifier removed and one comment added in one file. No new test, no new dependency, no config change, no behavioral change.

No separate tracer task: the change *is* a single line in a single file, and its verify already exercises the full vertical slice (Hibernate id generation -> entity persist -> H2 round trip) via the existing suite. A thinner slice does not exist.

Non-negotiable gate: Step 1 of the task re-derives the "no shared mutable state" claim from the live source before touching anything, and halts if any assumption fails. The prior research (`.planning/quick/260802-ryf-.../260802-ryf-RESEARCH.md`, Finding 1) reached the same conclusion, but it is not accepted on trust here.
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@docs/CODE_STYLE.md
@src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java
@src/main/java/com/vrudenko/kanban_board/config/RandFlakeId.java
@src/main/java/com/vrudenko/kanban_board/entity/BaseEntity.java
</context>

<interface_context>
Grounding facts verified against the live repo during planning — do not re-derive from scratch, but Step 1 of the task re-asserts the load-bearing ones mechanically.

**`RandFlakeGenerator.java` (34 lines, read in full):** `public class RandFlakeGenerator implements IdentifierGenerator`. Two fields, both `private static final long` with compile-time-constant initializers: `RANDOM_BITS = 23L` and `CUSTOM_EPOCH = 1672531200000L`. **Zero instance fields.** Two methods: the `@Override public String generate(SharedSessionContractImplementor session, Object object)` required by the Hibernate SPI, which does nothing but `return generateRandflake();`, and `public synchronized String generateRandflake()` on line 24. The body is four statements — `Instant.now().toEpochMilli() - CUSTOM_EPOCH`, `ThreadLocalRandom.current().nextLong(1L << RANDOM_BITS)`, a shift-or combine, and `Long.toString(id, 36)`. All four write only locals. No blocking call, no I/O, no field write anywhere in the class.

**Call graph (verified by case-insensitive grep for `generateRandflake` across all of `src/`):** exactly two hits, both inside `RandFlakeGenerator.java` itself — the declaration on line 24 and the single call on line 21. **No caller outside the class, in `src/main` or `src/test`.**

**Instantiation (verified):** `grep -rn "RandFlakeGenerator"` over `src/` returns two hits — the class declaration, and `@IdGeneratorType(RandFlakeGenerator.class)` in `RandFlakeId.java:9`. There is **no `new RandFlakeGenerator()`** and **no `extends RandFlakeGenerator`** anywhere. Hibernate constructs it reflectively from the annotation; nothing else can hold a reference to the instance whose monitor is in play.

**Reach (verified):** `RandFlakeId.java` is a `@Retention(RUNTIME)` annotation targeting `FIELD`/`METHOD`, applied at `BaseEntity.java:14` as `@Id @RandFlakeId protected String id;`. `BaseEntity` is the `@MappedSuperclass` behind `UserEntity`, `BoardEntity`, `ColumnEntity`, `TaskEntity`, `SubtaskEntity`, and `ActivityLogEntity` — so this generator is on the insert path for every persisted row in the application.

**`synchronized` census (verified):** an exact-token grep over the **entire** `src/` tree (main *and* test) returns **one** hit: line 24 of this file. There is therefore no `synchronized (someGenerator) { }` block anywhere pairing with this monitor from the outside, and no second lock site in the codebase to consider. Grepping the same tree for `Lock`, `volatile`, and `AtomicLong` also returns nothing. After this change the count in `src/` becomes zero.

**No existing test for this class (verified):** `find src/test -iname '*RandFlake*' -o -iname '*Generator*'` returns nothing. The generator is covered only indirectly, through every fixture that persists an entity.

**Build gates (verified from `build.gradle`):** Spotless 7.0.2, `googleJavaFormat().aosp()`, target `src/**/*.java` — 4-space indentation, and it will reformat the signature line if the modifier is removed sloppily. ErrorProne 2.50.0 runs as a javac plugin: `compileJava` is a **hard gate** (ERROR-severity findings fail the build), `compileTestJava` is demoted to warnings via `options.errorprone.allErrorsAsWarnings = true`. A prior quick task (`260802-qr8`) already touched this exact file, removing an unused `TIMESTAMP_BITS` constant and preserving its intent as the plain comment now sitting above `RANDOM_BITS` — so the file has recent precedent for exactly the "explain the reasoning in a comment rather than in a dead code artifact" move this plan makes.

**Test-suite shape:** `./gradlew test` runs the whole suite, which includes four Testcontainers-backed Kafka E2E classes (`ActivityLogConsumerE2ETest`, `ActivityLogDeadLetterE2ETest`, `ActivityLogIdempotencyE2ETest`, `ActivityReadE2ETest`) — these need a running Docker daemon and dominate wall-clock time. `TaskServiceTest` is a plain `@SpringBootTest extends AbstractAppTest` on in-memory H2 with no Docker dependency, which makes it the right fast smoke gate to run *first*.
</interface_context>

<design_rationale>
Per `.claude/CLAUDE.md`: alternatives considered, trade-off matrix, and the non-obvious trade-offs behind the chosen approach.

## Alternatives considered

**Approach A (chosen) — delete the modifier, add a comment recording why no lock is needed.** Signature becomes `public String generateRandflake()`.

**Approach B — replace it with a `ReentrantLock`.** Preserve mutual exclusion, but in a form that unmounts a virtual thread instead of pinning its carrier (the migration HikariCP's closed PR #2055 proposed for itself).

**Approach C — leave it exactly as it is.** Status quo; the code works today.

**Approach D — delete the modifier *and* collapse `generateRandflake()` into `generate()` (or make it `static`).** Removes the public method whose instance monitor is the subject of the question, rather than just removing the modifier from it.

## Trade-off matrix

| Approach | Pros / Cons | Why picked |
|----------|-------------|------------|
| **A. Delete the modifier + explanatory comment** | **+** Provably zero semantic change: no instance fields, both statics are `final` primitives, randomness is thread-confined, no blocking call, zero external callers, zero external code synchronizing on the instance — every one of those is grep- or read-verifiable, and Step 1 re-checks them. **+** Removes a monitor CAS pair from the hottest write path in the app (every row insert, all six entity types). **+** One line, trivially reviewable, exactly the shape the prior research asked for. **-** Deletes a happens-before edge, which always deserves scrutiny even when nothing is published through it. **-** Without a comment the next reader sees "unsynchronized id generator" and may reflexively re-add the lock — which is why the comment is part of the task, not optional garnish. | **Picked.** It is the only option that both removes the defect and leaves behind the reasoning that prevents its return, and the "is it safe" question is answerable mechanically rather than by judgement. |
| **B. Swap for `ReentrantLock`** | **+** Loom-friendly: a contending virtual thread parks and unmounts rather than blocking its carrier on JDK 21 (pre-JEP-491). **+** Feels conservative — "we kept the mutual exclusion." **-** Preserves mutual exclusion that protects **nothing**, so it keeps 100% of the serialization cost while adding an object allocation, a field, and an import. **-** Actively misleading: a `ReentrantLock` field is a much louder signal that shared mutable state exists than a `synchronized` keyword is, so it entrenches the exact misconception this change exists to clear up. **-** This project does not have virtual threads enabled (research `260802-ryf` deferred them on HikariCP 6.3.0), so the one genuine advantage is unrealised. | **Rejected.** It is the right refactor for a lock that is needed and the wrong one for a lock that is not. Cargo-culting the Loom migration onto a no-op critical section buys the cost and none of the benefit. |
| **C. Leave as-is** | **+** Zero risk, zero diff, zero review burden. **-** Leaves a permanent serialization point and an uncontended CAS pair on every entity insert, in exchange for nothing. **-** Leaves a standing invitation to misread the class as stateful. **-** Leaves a latent virtual-thread carrier-blocking hazard that the eventual Java 21→25 / Boot 4.x upgrade would otherwise have to re-examine. | **Rejected.** The cost of the change is one line and a full green suite; the cost of keeping it compounds quietly on the busiest path in the codebase. |
| **D. Also inline / make `static`** | **+** Strictly eliminates the "whose monitor?" question rather than answering it. **+** Shrinks the public surface of a config-package class that has no business exposing an id-minting method to arbitrary callers. **-** Two unrelated concerns in one commit; a reviewer can no longer see the locking change in isolation. **-** `generateRandflake()` being `public` may be deliberate (it is a plausible seam for a future direct-minting need, and the pending Snowflake-ID todo may well want exactly that seam). Deleting it is a design decision, not a cleanup. | **Rejected for this task, noted as a follow-up.** The prior research was explicit that this should be "its own trivially-reviewable change"; bundling an API-surface decision into it defeats that. If the pending Snowflake-ID todo lands, the whole class gets revisited anyway. |

## Non-obvious trade-offs

**Memory visibility is the real question, and it is the one thing worth checking carefully.** `synchronized` on an instance method does two things: it excludes other threads, and it establishes a happens-before edge on monitor acquire/release. Removing the keyword removes *both*. Exclusion is provably pointless here (nothing to exclude access to), but the visibility edge deserves the explicit argument: an edge only matters if some state is *published through* it. This method reads `RANDOM_BITS` and `CUSTOM_EPOCH`, which are `static final long` with constant initializers — JLS §13.4.9 constant variables, inlined by javac at every use site, so at runtime no field read even occurs, and even if it did, class initialization already provides the safe-publication guarantee. `Instant.now()` reads the system clock. `ThreadLocalRandom.current()` returns a thread-confined instance. Every other value is a stack local. **There is no state, anywhere in this class, whose visibility the monitor could have been guaranteeing** — and no external caller that could have been piggybacking on the edge for unrelated state, since grep shows zero callers outside the class. That is why this is a genuine no-op and not merely a probable one.

**Uniqueness is not weakened — and this is the trap that makes the change *look* risky.** The instinct "removing a lock from an ID generator will cause duplicate IDs" is correct for a real Snowflake generator, which carries a mutable per-millisecond sequence counter and a last-seen-timestamp field and genuinely needs mutual exclusion (or a CAS loop) to keep them consistent. This class has neither. It fills the low 23 bits from `ThreadLocalRandom` instead. Two calls landing in the same millisecond therefore already collide with probability 2⁻²³ (~1 in 8.4 million) **whether or not the lock is present** — the monitor serialized them in time but never made them distinct. Collision probability after this change is exactly, unchangedly, what it was before. (Distinct threads get distinct `ThreadLocalRandom` seeds, so cross-thread draws are not correlated either.)

**Contention scope is narrower than the prior research claimed, and the commit message should not overclaim.** Research `260802-ryf` called this "a global serialization point on every entity insert." More precisely: `@RandFlakeId` carries `@IdGeneratorType(RandFlakeGenerator.class)` and is applied to `BaseEntity.id` on a `@MappedSuperclass`, so Hibernate instantiates a generator per entity mapping rather than one JVM-wide singleton — the monitor serializes concurrent inserts *of the same entity type*, not of all types against each other. `[ASSUMED — reasoning from Hibernate's per-mapping `@IdGeneratorType` instantiation model; not measured in this repo, and it does not change the decision either way.]` Still a real and pointless serialization point on the busiest write path (task and subtask creates), just not a JVM-wide one. Worth stating so the change is defended accurately rather than dramatically.

**The uncontended cost is not zero.** Biased locking, which made uncontended monitor entry nearly free, was disabled by default in JDK 15 (JEP 374) and removed outright in JDK 18. On Java 21 every call to this method pays a real CAS on monitor enter and another on exit, plus the reordering barrier. Nanoseconds — but nanoseconds multiplied by every row this application will ever insert, in exchange for nothing.

**Virtual threads are a bonus here, not the justification — deliberately so.** On JDK 21, a virtual thread that *contends* on a monitor blocks its carrier (JEP 491 fixes this only in JDK 24). This project is on Java 21 and virtual threads are **not** enabled — research `260802-ryf` deferred them behind the HikariCP 6.3.0 carrier-saturation blocker. So this removal buys Loom nothing today. It is justified entirely on its own merits (a lock protecting nothing, paid for on every insert), and merely happens to retire one future Loom hazard for free. The research was explicit that this must not be "smuggled in under a virtual-threads banner", and the commit message should reflect that framing.

**Security: exactly zero delta, and worth saying why rather than just asserting it.** The change does not touch the entropy source, the bit layout, the radix, or the number of random bits — the ids produced afterwards are drawn from an identical distribution. `ThreadLocalRandom` is a non-cryptographic PRNG and was one before this change too. This is not an exposure in any case, because the ids are not bearer tokens: authorization is enforced server-side on every request through `OwnershipVerifierService`'s ownership chain (`docs/CODE_STYLE.md` rule 2), so guessing another user's task id yields a 403, not access. No new attack surface, no widened one.

**Binary and source compatibility.** `ACC_SYNCHRONIZED` is a method access flag in the class file, but callers do not bind to it — it is an implementation detail, not part of the invocation contract. Removing it is both source- and binary-compatible. The only conceivable break is a caller that relied on the call to establish a happens-before edge for *its own* unrelated state, and grep proves no such caller exists (zero call sites outside the class, and the entire `src/` tree contains no other `synchronized` token to pair with).

**A regression guard was considered and deliberately deferred.** A reflection assertion (`Modifier.isSynchronized(...)`) or, more idiomatically for this repo, an ArchUnit rule in `LayeringArchTest` banning `synchronized` across `src/main`, would stop the modifier being reinstated. Deferred on purpose: a project-wide ban is a *policy* decision with its own blast radius — it would pre-emptively forbid a legitimate future lock — and this repo's convention (`docs/CODE_STYLE.md`, "Adding a rule") is that such policies land as a numbered rule with a Why and a bad-vs-good example, mechanically enforced afterwards. That is a separate, discussable change, not a rider on a one-line deletion. For *this* change, the anchored grep gate in the verify block plus the in-file comment are the guard.
</design_rationale>

<tasks>

<task type="auto">
  <name>Task 1: Drop the mutual-exclusion modifier from RandFlakeGenerator.generateRandflake and record why it was unnecessary</name>
  <files>src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java</files>
  <read_first>
Read `src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java` in full (34 lines) before editing.
  </read_first>
  <action>
<!-- planner-discipline-allow: synchronized -->

**Step 1 — re-confirm the safety argument from the live source. Do not skip this and do not take the prior research on trust.** All six checks must pass. If *any* one of them fails, **halt immediately, make no edit, and report which check failed** — a failure means the class is not what this plan assumes and the removal may not be a no-op.

1. The class declares **zero instance fields**. Every field is `private static final` with a primitive type and a compile-time-constant initializer (expect exactly two: `RANDOM_BITS = 23L`, `CUSTOM_EPOCH = 1672531200000L`).
2. No statement in `generateRandflake()` writes to any field — every assignment target is a local (`timestamp`, `randomBits`, `id`).
3. The body contains **no blocking call**: no I/O, no `wait`/`sleep`/`join`/`park`, no network or database access. (Expect only `Instant.now()`, `ThreadLocalRandom.current().nextLong(...)`, arithmetic, and `Long.toString`.)
4. Randomness comes from `ThreadLocalRandom.current()`, which is thread-confined — **not** from a shared `Random`/`SecureRandom` instance field.
5. `grep -rn "generateRandflake" src/` returns exactly two hits, both inside this file (the declaration and the one internal call from `generate`). There is **no caller outside the class**.
6. `grep -rn "synchronized" src/` returns exactly one hit — the modifier being removed. In particular there is no `synchronized (...) { }` block anywhere pairing with this generator's monitor from outside, and no `extends RandFlakeGenerator` subclass that could be relying on inherited locking.

**Step 2 — make the edit.** Using a scoped `Edit` (never `Write`), change the method signature on line 24 from

    public synchronized String generateRandflake() {

to

    public String generateRandflake() {

and insert this comment block on the lines immediately above it, at the same 4-space indentation as the method, matching the existing in-class comment style (plain `//` line comments, as already used above `RANDOM_BITS`):

    // Deliberately not synchronized. This generator holds no shared mutable state - it has no
    // instance fields, both constants are static final primitives, ThreadLocalRandom is
    // thread-confined, and every other value here is a local. A lock would protect nothing while
    // serializing every entity insert in the application, since this is the IdentifierGenerator
    // behind @RandFlakeId on BaseEntity. Note that a lock never contributed to id uniqueness
    // either: the low bits are random, not a sequence counter, so same-millisecond collisions
    // were always possible at the same probability. If mutable state (a sequence counter, a
    // last-timestamp field) is ever added here, revisit this.

**Constraints that are load-bearing and must not be "cleaned up":**
- Change **only** the signature line and add **only** the comment block above it. The four statements of the method body, the `generate(...)` SPI override, the two constant declarations, the existing comment above `RANDOM_BITS`, the imports, and the package line all come out byte-identical.
- Do **not** convert the comment to Javadoc — the surrounding in-class style is `//` line comments, and a `//` block is what keeps the verify gate's comment-immune anchored scan honest.
- Do **not** introduce a `ReentrantLock`, an `AtomicLong`, a `volatile` field, or any other replacement synchronization primitive. The point is that none is needed.
- Do **not** make the method `static`, do **not** inline it into `generate(...)`, and do **not** reduce its visibility. API-surface changes are a separate, out-of-scope decision (see design rationale, Approach D).
- Do **not** add a test file, an ArchUnit rule, or a `docs/CODE_STYLE.md` entry in this task (see design rationale — deliberately deferred).
- Do **not** touch `build.gradle`, `application.properties`, `RandFlakeId.java`, `BaseEntity.java`, or any test.
- Run `./gradlew spotlessApply` if the added comment or signature needs reflowing; the AOSP formatter is authoritative over the exact wrapping above.

**Step 3 — commit message framing.** Frame the commit as removing a lock that protects no shared state and needlessly serializes entity inserts. Do **not** frame it as a virtual-threads change: virtual threads are not enabled in this project (deferred by research `260802-ryf` behind the HikariCP 6.3.0 blocker), and that research explicitly asked for this to stand as its own independently-defensible change rather than ride under a Loom banner.
  </action>
  <verify>
    <automated>
set -e
F=src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java

# --- Positive gate: the signature is now exactly the unqualified form.
grep -qE '^[[:space:]]*public[[:space:]]+String[[:space:]]+generateRandflake\(\)[[:space:]]*\{' "$F"

# --- Negative gate, comment-immune. The '^\s*[a-z ]*' anchor cannot match a line
# --- starting with '//' or '*', so the new explanatory comment cannot self-invalidate it.
test "$(grep -cE '^[[:space:]]*[a-z ]*\bsynchronized\b' "$F" || true)" = "0"

# --- Same anchored, comment-immune scan across all of src/main: zero code hits.
test "$(grep -rnE --include='*.java' '^[[:space:]]*[a-z ]*\bsynchronized\b' src/main | wc -l | tr -d ' ')" = "0"

# --- No replacement primitive was smuggled in.
test "$(grep -cE '\b(ReentrantLock|AtomicLong|AtomicReference|volatile)\b' "$F" || true)" = "0"

# --- The safety preconditions still hold in the committed file: both fields static final
# --- primitives, thread-confined randomness, SPI override and body intact.
grep -q 'private static final long RANDOM_BITS = 23L;' "$F"
grep -q 'private static final long CUSTOM_EPOCH = 1672531200000L;' "$F"
grep -q 'ThreadLocalRandom.current().nextLong(1L << RANDOM_BITS)' "$F"
grep -q 'Instant.now().toEpochMilli() - CUSTOM_EPOCH' "$F"
grep -q '(timestamp << RANDOM_BITS) | randomBits' "$F"
grep -q 'Long.toString(id, 36)' "$F"
grep -q 'public String generate(SharedSessionContractImplementor session, Object object)' "$F"

# --- The reasoning was recorded so the modifier does not come back.
grep -q 'Deliberately not' "$F"
grep -q 'no shared mutable state' "$F"

# --- Blast radius: exactly one file changed.
test "$(git status --porcelain -- src/ build.gradle | wc -l | tr -d ' ')" = "1"
git status --porcelain -- "$F" | grep -q '^ *M'

# --- Fast functional smoke first (H2, no Docker): proves ids still generate and
# --- entities still persist through the real Hibernate @RandFlakeId path.
./gradlew test --tests 'com.vrudenko.kanban_board.service.TaskServiceTest'

# --- Full project gates. ErrorProne's ERROR-severity hard gate on compileJava runs
# --- as part of both. `test` includes the Testcontainers Kafka E2E classes, so a
# --- Docker daemon must be running; bound this invocation to ~15 minutes.
./gradlew spotlessCheck
./gradlew test

echo RANDFLAKE_LOCK_REMOVED_OK
    </automated>
  </verify>
  <done>`RandFlakeGenerator.generateRandflake()` is declared as `public String generateRandflake()` with no mutual-exclusion modifier, and carries an eight-line `//` comment above it recording that the class holds no shared mutable state, that the removed lock never contributed to id uniqueness, and that the decision must be revisited if a mutable field is ever added. All six Step-1 safety checks were re-confirmed against the live source before the edit. The anchored, comment-immune scan finds zero mutual-exclusion declarations anywhere in `src/main`. No `ReentrantLock`, `AtomicLong`, or `volatile` was introduced as a substitute. The two constants, the four body statements, and the `generate(...)` SPI override are byte-identical to before. `git status --porcelain` shows exactly one modified file under `src/`. `TaskServiceTest` passes, then `./gradlew spotlessCheck` and the full `./gradlew test` both pass, clearing ErrorProne's `compileJava` hard gate. The verify script prints `RANDFLAKE_LOCK_REMOVED_OK`.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| concurrent request threads -> shared generator instance | Multiple HTTP request threads invoke the same Hibernate-owned `RandFlakeGenerator` instance concurrently during entity inserts. Removing a monitor moves this boundary from "serialized" to "genuinely concurrent", so the safety of concurrent access must rest on the code being stateless rather than on the lock. |
| generated entity ids -> API consumers | Ids produced here are returned in API responses and accepted back as path variables. Anything that changed their predictability or collision behavior would cross into an authorization-adjacent concern. |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-quick-tbj-01 | Tampering | `RandFlakeGenerator.generateRandflake()` under concurrent inserts | medium | mitigate | Removing a monitor can introduce a data race if the method touches shared mutable state. Mitigated by proof rather than assertion: Step 1 mechanically re-confirms zero instance fields, no field writes, `static final` primitive constants only, thread-confined `ThreadLocalRandom`, no blocking call, and zero external callers — and **halts without editing** if any check fails. The verify block re-asserts the field declarations and body statements post-edit so a later drift toward mutable state is caught by the gate rather than at runtime. |
| T-quick-tbj-02 | Spoofing | entity id predictability | low | accept | Ids remain 23 random bits from a non-cryptographic PRNG over a millisecond timestamp — identical distribution before and after; the change touches no entropy source, bit width, or radix, so there is a zero delta to accept. Independently, ids are not bearer tokens: every request re-verifies ownership server-side through `OwnershipVerifierService` (`docs/CODE_STYLE.md` rule 2), so a guessed id yields 403, not access. |
| T-quick-tbj-03 | Denial of service | id-collision-driven insert failure | low | accept | Same-millisecond collisions are possible at probability 2⁻²³ and would surface as a primary-key violation. This is **pre-existing and numerically unchanged** by this plan: the removed monitor serialized calls in time but never made their random low bits distinct, because there is no sequence counter. Accepted as out of scope here; the pending Snowflake-ID todo (`2026-08-02-use-snowflake-id-generator-for-activity-log-events.md`) is the correct venue for revisiting the id scheme. |
| T-quick-tbj-04 | Repudiation | reinstatement of the removed lock | low | mitigate | The reasoning behind the removal could be lost, leading a future contributor or agent to re-add the modifier on the mistaken belief that an id generator needs one. Mitigated by the mandatory in-file comment (which also states the revisit condition — mutable state being added) and by the anchored, comment-immune grep gate in the verify block. A project-wide ArchUnit ban was considered and deliberately deferred as a separate policy decision (see design rationale). |
| T-quick-tbj-SC | Tampering | package installs | low | accept | No package-manager install occurs in this plan. No Gradle plugin, no `dependencies` entry, no npm/pip/cargo package is added or changed — `build.gradle` is not touched at all. The build-classpath supply-chain surface is unchanged, so the legitimacy gate does not apply. |
</threat_model>

<verification>
1. The task's verify script prints `RANDFLAKE_LOCK_REMOVED_OK`.
2. `./gradlew spotlessCheck` passes and the full `./gradlew test` passes (Docker daemon running for the four Testcontainers Kafka E2E classes; bound the run to ~15 minutes and investigate rather than wait if it overruns).
3. `git status --porcelain` lists exactly one path: `src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java`. No `build.gradle`, no `application.properties`, no test file, no `docs/` file.
4. `git diff -- src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java` shows exactly one deleted+re-added signature line and one added comment block — no change to the two constant declarations, the four body statements, the `generate(...)` override, or the imports.
5. The anchored, comment-immune scan `grep -rnE --include='*.java' '^[[:space:]]*[a-z ]*\bsynchronized\b' src/` returns nothing across **both** `src/main` and `src/test` — the codebase now has zero mutual-exclusion declarations in code. (A plain unanchored `grep -rn "synchronized" src/` will still show one hit: the new explanatory comment. That is expected and is exactly why the gate is anchored.)
6. Sanity-check a generated id by eye in the test output or H2: still a short base-36 string of the same shape as before the change (no format, length, or radix drift).
7. The Step-1 halt condition was genuinely exercised as a gate, not skipped — the SUMMARY records the six checks and their outcomes.
</verification>

<success_criteria>
- `RandFlakeGenerator.generateRandflake()` no longer declares mutual exclusion, and the codebase contains zero such declarations in `src/main` code.
- The removal was justified by re-derivation from the live source (six mechanical checks), not by deferring to prior research.
- The reasoning survives in the file, including the condition under which it must be revisited, so the modifier is not reflexively reinstated.
- Id generation is byte-identically constructed: same bit layout, same radix, same entropy source, same collision probability. No behavioral change of any kind.
- `./gradlew spotlessCheck` and `./gradlew test` pass, clearing ErrorProne's `compileJava` hard gate.
- Exactly one file changed; the diff is small enough to review at a glance and defensible on its own merits without reference to virtual threads.
- The "optional adjacent cleanup" recommendation in `.planning/quick/260802-ryf-enable-virtual-threads-in-spring-boot-co/260802-ryf-RESEARCH.md` (step 5 of its recommended plan shape) is fully discharged.
</success_criteria>

<output>
Create `.planning/quick/260802-tbj-remove-the-pointless-synchronized-modifi/260802-tbj-SUMMARY.md` when done.
</output>
