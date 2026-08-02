# Quick Task 260802-rq5: Bump Java 21 → 25 — Research

**Researched:** 2026-08-02
**Domain:** JVM toolchain / build-tooling version compatibility
**Confidence:** HIGH on the blocking findings (official Gradle + Spring docs, Docker Hub registry API); MEDIUM on secondary ecosystem items

---

## Verdict: **NO-GO as a single quick task**

Bumping to JDK 25 is not a three-file edit. It is gated behind a **major Gradle upgrade (8.x → 9.1+)**, which is in turn gated behind a **Spring Boot major upgrade (3.5 → 4.x)**, because Spring Boot 3.5's Gradle plugin and `io.spring.dependency-management` both cap at Gradle 8.x. Three additional dependencies (Lombok, MapStruct, Spring Boot patch level) also need bumps before JDK 25 will compile at all.

**Recommend splitting into three units** (see [Recommended Split](#recommended-split)). Unit A and Unit B are genuine quick tasks. Unit C is milestone-sized and should go through `/gsd-phase`, not `/gsd-quick`.

---

## The Blocking Chain

```
Java 25 target
  └─> requires Gradle >= 9.1.0        (both "running Gradle" AND "toolchain")
        └─> Spring Boot 3.5.x Gradle plugin supports only Gradle 7.6.4+ / 8.4+
        └─> io.spring.dependency-management 1.1.x supports only Gradle 6.8–8.x
              └─> requires Spring Boot 4.x   (4.1: Gradle 8.14+/9.x, Java up to 26)
```

Each link is documented below.

---

## Findings

### 1. Gradle 8.11.1 cannot run on, or target, JDK 25 — HARD BLOCKER

`[VERIFIED: docs.gradle.org/current/userguide/compatibility.html]` — official compatibility matrix:

| Java version | First Gradle w/ toolchain support | First Gradle that can *run* on it |
|---|---|---|
| 21 | 8.4 | 8.5 |
| 24 | 8.14 | 8.14 |
| **25** | **9.1.0** | **9.1.0** |
| 26 | 9.4.0 | 9.4.0 |

Two consequences for this repo (wrapper is **8.11.1**, per `gradle/wrapper/gradle-wrapper.properties:3`):

- Gradle 8.11.1 **cannot run on** a JDK 25 daemon JVM — reported failure mode is `Unsupported class file major version 69`.
- The usual escape hatch **does not exist here**: you cannot keep the daemon on JDK 21 and set `JavaLanguageVersion.of(25)` in the toolchain either, because Java 25 *toolchain* support also lands only in 9.1.0. Gradle 8.11.1 doesn't even reach the Java 24 toolchain line (that needs 8.14).

`[VERIFIED: github.com/gradle/gradle/issues/35111]` — "Support Java 25 on Gradle 8" is **closed as not planned**. There will be no Gradle 8.x that runs on Java 25. The upgrade path is 9.x, full stop.

**Corroborating signal:** the official `gradle` Docker Hub repo has **no `8.x-jdk25` tag whatsoever** — every `jdk25` tag is `9.x` (`9-jdk25`, `9.6-jdk25`, `9.6.1-jdk25`, …). `[VERIFIED: hub.docker.com/v2/repositories/library/gradle/tags?name=jdk25]`

### 2. Gradle 9 is blocked by this repo's Spring plugins

`[VERIFIED: docs.spring.io/spring-boot/3.5.16/gradle-plugin/index.html]`, quoted verbatim:

> "Spring Boot's Gradle plugin requires Gradle 7.x (7.6.4 or later) or 8.x (8.4 or later)"

`[VERIFIED: docs.spring.io/spring-boot/3.5.16/system-requirements.html]`:

> "Spring Boot 3.5.16 requires at least Java 17 and is compatible with versions up to and including Java 25."
> Gradle: "Gradle 7.x (7.6.4 or later) and 8.x (8.4 or later)"

So Spring Boot 3.5.x is **Java-25-ready but not Gradle-9-ready**. That is exactly the wrong half — Java 25 is unreachable without Gradle 9.

`[CITED: docs.spring.io/dependency-management-plugin/docs/current/reference/html/]` — `io.spring.dependency-management` (pinned `1.1.6` at `build.gradle:4`) supports **Gradle 6.8+, 7.x, or 8.x** only. Latest release is 1.1.7 (Dec 2024); no Gradle 9 support published. This plugin is optional under Spring Boot 3.x (Gradle's native `platform()` BOM support can replace it), so it is removable rather than an absolute blocker — but it is one more thing the migration has to deal with.

`[VERIFIED: docs.spring.io/spring-boot/system-requirements.html]` — the current line, **Spring Boot 4.1.0**, states:

> "Spring Boot 4.1.0 requires at least Java 17 and is compatible with versions up to and including Java 26."
> Gradle: "Gradle 8.x (8.14 or later) and 9.x"

**This is the only supported destination for a Java 25 Gradle build: Spring Boot 4.x.**

### 3. Spring Boot 3.5.0 is stale and past OSS EOL

`[CITED: github.com/spring-projects/spring-boot/issues/47245]` — "Spring Boot 3.5.5 and Spring Boot 4.0.0 is Java 25 ready." The repo pins **3.5.0** (`build.gradle:3`), which predates Java 25 readiness by five patch releases.

`[ASSUMED]` — the 3.5 line reached OSS end-of-life on 2026-06-30 with 3.5.16 as the final patch (secondary source: HeroDevs version tracker, not a spring.io page). Verify against spring.io/projects/spring-boot#support before acting on it. If true, it strengthens the case for going to 4.x rather than parking on 3.5.16.

### 4. Lombok 1.18.36 does NOT support JDK 25 — HARD BLOCKER

`[CITED: github.com/projectlombok/lombok/issues/3859 + projectlombok.org changelog]` — **Lombok 1.18.40** (released 2025-09-04, ahead of JDK 25 GA) is the version that added JDK 25 support. The repo pins **1.18.36** in four places (`build.gradle:42-45`).

Lombok patches javac internals, so this fails hard at compile time, not subtly at runtime. Expect `java.lang.NoSuchFieldError` / `IllegalAccessError` in the annotation processor, not a graceful degradation. **Minimum 1.18.40; take the latest.**

### 5. MapStruct 1.5.3 should be bumped (soft blocker)

`[CITED: mapstruct.org/documentation/installation/ + mapstruct/mapstruct CI runs]` — current stable is **1.6.3**; upstream CI runs on JDK 25 and 26. MapStruct 1.5.3.Final (`build.gradle:57-58`) predates JDK 23/24/25 entirely.

Separate JDK-23+ gotcha that bites annotation processors generally: **javac defaults to `-proc:none` from JDK 23 onward**, so annotation processing must be explicitly enabled. Gradle's `annotationProcessor` configuration handles this automatically, so this repo is likely unaffected — but it is the single most common "my mappers vanished after the JDK bump" failure, so verify `build/generated/**/*Impl.java` is still produced after any bump. `[ASSUMED — Gradle's automatic handling not re-verified this session]`

### 6. ErrorProne 2.50.0 is fine on JDK 25 — no blocker

`[CITED: github.com/google/error-prone/issues/4867, errorprone.info/docs/installation]` — ErrorProne's **minimum** runtime is JDK 21 as of 2.43.0, and upstream maintains toolchain entries for JDK 21 and JDK 25 plus JDK 26 EA compatibility work. `2.50.0` (`build.gradle:92`) is comfortably past that line.

`[CITED: github.com/tbroyer/gradle-errorprone-plugin]` — the `net.ltgt.errorprone` plugin requires Gradle 7.1+; `5.1.0` (`build.gradle:6`) has no stated Gradle 9 upper bound. Treat as **probably fine on Gradle 9, verify empirically** — the plugin injects `--add-exports` JVM args for javac internals, which is precisely the kind of thing a JDK bump can disturb.

One thing to watch during the actual bump: the ErrorProne gate is **hard on `compileJava`** (`build.gradle:127-140`). A new JDK can surface new ERROR-severity findings and red the build — including inside the Dockerfile's `./gradlew bootJar`. Budget triage time; do not treat a red ErrorProne as "the JDK bump failed."

### 7. Docker base images — both stages available, but pick Noble not Jammy

`[VERIFIED: hub.docker.com/v2/repositories/library/eclipse-temurin/tags]` — these tags exist today:

- `25-jdk`, `25-jdk-noble`, `25-jdk-jammy`, `25-jdk-alpine`
- `25-jre`, `25-jre-noble`, `25-jre-jammy`, `25-jre-alpine`

`[VERIFIED: hub.docker.com/v2/repositories/library/gradle/tags?name=jdk25]` — `9-jdk25`, `9.6-jdk25`, `9.6.1-jdk25`, `9-jdk25-noble`, plus corretto/graal/ubi variants. **No `8.x-jdk25` exists.**

**Retirement-risk flag** (STATE.md records a prior production break when `openjdk:21-jdk-slim` was retired — Phase 2 Plan 03):

- `eclipse-temurin:25-jre-jammy` **exists but is the higher-risk choice.** Jammy is Ubuntu 22.04; Noble (24.04) is the current LTS base. Temurin has historically dropped older distro variants as they age out. **Recommend `eclipse-temurin:25-jre-noble`** for the runtime stage — same publisher, same trust model, longer runway. `[ASSUMED — Temurin's jammy-deprecation timeline not verified; the risk is directional, not scheduled]`
- **Recommend replacing the `gradle:*-jdk*` build stage with `eclipse-temurin:25-jdk-noble`.** The build stage runs `./gradlew`, so the Gradle binary baked into the `gradle` image is **never used** — the image currently declares `gradle:8.7-jdk21` (`Dockerfile:2`) while the wrapper is 8.11.1, a latent inconsistency that has simply never mattered. Switching to a plain Temurin JDK image removes the phantom second Gradle pin and shrinks the retirement surface to a single publisher.

### 8. GitHub Actions — `distribution: 'adopt'` is dead and has no JDK 25

`[CITED: github.com/actions/setup-java, adoptium.net/installation/github-actions]` — legacy AdoptOpenJDK distributions were **removed** from `setup-java`; `adopt` / `adopt-hotspot` must be replaced with `temurin` (and `adopt-openj9` with `semeru`). AdoptOpenJDK stopped shipping releases entirely, so **there is no JDK 25 under `adopt` and never will be.**

`.github/workflows/deploy.yml:31-34` currently uses:

```yaml
uses: actions/setup-java@v4
with:
  java-version: '21'
  distribution: 'adopt'
```

Required target: `actions/setup-java@v5` + `java-version: '25'` + `distribution: 'temurin'`. JDK 25 is available via `setup-java` (downloaded if not in the runner tool cache), so no runner-image dependency.

**Adjacent CI staleness noticed while reading the workflow** (not part of this task, worth a separate capture): `actions/checkout@v3` at lines 28 and 54 is two majors behind and runs on a deprecated Node runtime.

---

## Recommended Split

### Unit A — Dependency modernization (true quick task, do this now)

Stay on **Java 21 and Gradle 8.11.1**. Bump only what is independently correct and unblocks the later work:

| Dependency | From | To | Why now |
|---|---|---|---|
| Lombok | 1.18.36 | latest 1.18.4x | Hard JDK-25 blocker; safe on 21 |
| MapStruct | 1.5.3.Final | 1.6.3 | Hard-ish blocker; safe on 21 |
| Spring Boot | 3.5.0 | 3.5.16 (final 3.5 patch) | 16 patches of fixes; last stop before 4.x |

Verification: `./gradlew spotlessCheck test` green, and confirm MapStruct still generates `*Impl.java` under `build/generated/`.
Risk: LOW. Reviewable as one small PR.

### Unit B — CI/Docker hygiene (true quick task, do this now)

Still on Java 21. Delivers real value and de-risks Unit C:

- `deploy.yml`: `distribution: 'adopt'` → `'temurin'`; `setup-java@v4` → `@v5`.
- `Dockerfile` build stage: `gradle:8.7-jdk21` → `eclipse-temurin:21-jdk-noble` (kills the phantom Gradle pin).
- `Dockerfile` runtime stage: `eclipse-temurin:21-jre-jammy` → `21-jre-noble`.

Risk: LOW–MEDIUM. Touches the live deploy path — `master` auto-deploys to EC2, so this needs a real pipeline run to confirm.

### Unit C — Java 25 (NOT a quick task; route to `/gsd-phase`)

This is a Spring Boot **major** upgrade wearing a JDK bump's clothing:

1. Spring Boot 3.5.16 → 4.x (breaking: package relocations, autoconfiguration changes, Spring Framework 7, Spring Security major, Spring Kafka major).
2. Gradle wrapper 8.11.1 → 9.1.0+ (removed APIs, tightened defaults; the `providers.exec` git-hooks block at `build.gradle:156-182` is already configuration-cache-safe, which helps).
3. Drop or replace `io.spring.dependency-management` (Gradle 9 unsupported).
4. `JavaLanguageVersion.of(21)` → `of(25)`.
5. Dockerfile → `eclipse-temurin:25-jdk-noble` / `25-jre-noble`; CI → `java-version: '25'`.
6. Re-triage ErrorProne findings under the new JDK + new Boot.
7. Re-verify Testcontainers/Kafka E2E (the suite already carries a pinned Docker Engine API workaround, per STATE.md Phase 3 Plan 01).

Also worth confirming during Unit C planning: Spring Boot 4.x's own compatibility with the repo's Testcontainers, ArchUnit 1.4.2, REST Assured 5.5.5, and SpringDoc 2.8.8 — SpringDoc in particular tends to need a major bump alongside a Spring Framework major.

---

## Package Legitimacy Audit

**N/A — no new packages introduced.** Every change discussed is a version bump of a dependency already present in `build.gradle`, from publishers already trusted by this build (Project Lombok, MapStruct, Spring, Gradle, Eclipse Adoptium, Google). No slopsquatting surface.

Version numbers cited above come from official documentation and registry APIs but were **not** resolved against Maven Central in this session (no network dependency-resolution run). The planner should pin exact versions by letting Gradle resolve them, not by copying the numbers here verbatim.

---

## Assumptions Log

| # | Claim | Section | Risk if wrong |
|---|---|---|---|
| A1 | Spring Boot 3.5 hit OSS EOL 2026-06-30 with 3.5.16 as the final patch | Finding 3 | Low — affects urgency framing of Unit C, not the technical blocker |
| A2 | Temurin will retire `*-jammy` variants before `*-noble` | Finding 7 | Low — Noble is the safer default regardless; jammy works today |
| A3 | Gradle's `annotationProcessor` config neutralizes the JDK 23+ `-proc:none` default | Finding 5 | Medium — if wrong, MapStruct mappers silently vanish during Unit C; explicit verification step included |
| A4 | `net.ltgt.errorprone` 5.1.0 works on Gradle 9 | Finding 6 | Medium — no upper bound published; verify empirically during Unit C |

---

## Sources

**Primary (HIGH):**
- docs.gradle.org/current/userguide/compatibility.html — Java/Gradle compatibility matrix
- github.com/gradle/gradle/issues/35111 — "Support Java 25 on Gradle 8", closed as not planned
- docs.spring.io/spring-boot/3.5.16/system-requirements.html — Boot 3.5.16 Java/Gradle support
- docs.spring.io/spring-boot/3.5.16/gradle-plugin/index.html — Boot 3.5.16 Gradle plugin requirement
- docs.spring.io/spring-boot/system-requirements.html — Boot 4.1.0 Java/Gradle support
- hub.docker.com/v2/repositories/library/eclipse-temurin/tags — live tag listing
- hub.docker.com/v2/repositories/library/gradle/tags — live tag listing

**Secondary (MEDIUM):**
- github.com/spring-projects/spring-boot/issues/47245 — Java 25 support, 3.5.5+
- github.com/projectlombok/lombok/issues/3859 — JDK 25 compatibility / 1.18.40
- github.com/google/error-prone/issues/4867 — minimum JDK 21 from 2.43.0
- github.com/actions/setup-java + adoptium.net/installation/github-actions — `adopt` removal
- docs.spring.io/dependency-management-plugin — Gradle 6.8–8.x support
- mapstruct.org/documentation/installation — 1.6.3 stable

**Valid until:** ~2026-09-02 (fast-moving; Gradle and Spring Boot both ship on short cycles)
