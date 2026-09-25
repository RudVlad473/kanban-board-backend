# 09 — Build, quality gates and CI

This layer turns the source tree into a tested, scanned, pinned Docker image and moves that image to
the servers. It matters because each gate here decides what can reach production, and each gate has
a documented reason and a documented limit.

**Read first:** [10 — Infrastructure and deployment](10-infrastructure-and-deployment.md) for the
servers, Compose stacks and network topology that the pipeline deploys to. This chapter covers only
the pipeline side.

**Main code:**

- [`build.gradle`](../../build.gradle), [`gradle/wrapper/gradle-wrapper.properties`](../../gradle/wrapper/gradle-wrapper.properties),
  [`gradle/verification-metadata.xml`](../../gradle/verification-metadata.xml), [`lombok.config`](../../lombok.config)
- [`.githooks/pre-commit`](../../.githooks/pre-commit), [`.gitleaks.toml`](../../.gitleaks.toml)
- [`.github/workflows/`](../../.github/workflows/) (six workflows), [`.github/dependabot.yml`](../../.github/dependabot.yml)
- [`scripts/verify-*.py`](../../scripts/) (invariant gates and their self-tests)
- [`Dockerfile`](../../Dockerfile), [`.dockerignore`](../../.dockerignore), [`docker/caddy/Dockerfile`](../../docker/caddy/Dockerfile)
- [`docs/CODE_STYLE.md`](../CODE_STYLE.md) (judgement-level rules)

## Summary of decisions

| ID | Decision | Main reason |
|----|----------|-------------|
| CI-01 | A check is a hard, per-commit gate only if its verdict is a pure function of the commit | A gate that turns red with no code change blocks unrelated work |
| CI-02 | Java 21 toolchain; Gradle wrapper pinned by SHA-256 and validated in CI before it runs | Closes a supply-chain gap that a version number alone leaves open |
| CI-03 | Pin tools and plugins to exact reviewed versions; leave Spring-managed dependencies unversioned | Upstream releases must not change a verdict; Spring BOM moves related artifacts together |
| CI-04 | Commit `gradle/verification-metadata.xml` with a SHA-256 for every resolved artifact | A version pin does not catch a same-version artifact substitution |
| CI-05 | Lombok and MapStruct run as compile-time annotation processors | No runtime reflection; generated code is plain bytecode |
| CI-06 | `commons-lang3` at `implementation` scope, guarded by `verifyRuntimeDependencyFloor` | A test-only pin left the production classpath on a CVE version |
| CI-07 | Exclude two transitive artifacts from `kafka-avro-serializer` | One forked `kafka-clients`; the other broke `GET /api/docs` with a 500 |
| CI-08 | Spotless with Google Java Format AOSP and an explicit 5-group import order | Formatting is mechanical, so a machine enforces it |
| CI-09 | Error Prone, pinned, gate strength chosen from a measured run; test sources stricter on 5 checks | Compile-time bug classes Spotless cannot see, with no surprise reds |
| CI-10 | JaCoCo ratchet (90% instruction, 90% line, 75% branch) wired to `test` with `finalizedBy` | CI never runs `check`; a drift alarm must fire on the command CI runs |
| CI-11 | Judgement rules live in `docs/CODE_STYLE.md`; the rules that can be checked go to ArchUnit | Prose drifts; an ArchUnit rule fails the build |
| CI-12 | The build auto-installs the hook through `core.hooksPath`; the hook checks and never auto-fixes | No manual setup step; no silent rewrite of staged files |
| CI-13 | Hook order: secret scan, then `spotlessCheck`, then `fastTest`; a scanner that cannot run refuses the commit | Refuse a credential in seconds, not after four minutes of tests |
| CI-14 | `fastTest` excludes classes by `@Tag("kafka")`/`@Tag("realSocket")`; both test tasks use 2 forks | Opt-in exclusion; 2 forks measured faster, 4 forks not |
| CI-15 | gitleaks (Docker image, tag and digest pinned) scans the staged diff locally and full history in CI | Fast staged mode, one committed config, no new prerequisite |
| CI-16 | `.gitleaks.toml` extends defaults with rule-scoped allowlists; one real old credential sits in a baseline | Narrow, evidence-cited exemptions; never a path exemption |
| CI-17 | TruffleHog verifies live credentials in CI only, diff-scoped, with a field-allowlist report scrub | Verification phones out; its report holds live values |
| CI-18 | `deploy.yml` runs on push to `main` only, with docs/planning paths ignored | A PR trigger would arm the deploy jobs |
| CI-19 | One image build pushes two tags (production and nonprod repositories), short-SHA tag, no `latest`, amd64 only | Both environments run byte-identical content |
| CI-20 | Flyway verification runs on the VM against the real databases, before any deploy | An empty CI database cannot detect migration drift |
| CI-21 | GitHub Environments scope every secret; nonprod has its own VM user and key; no approval gates | A shared credential grants shell access to production's directory |
| CI-22 | Digest-pin only the third-party `appleboy/*` actions | They hold real SSH keys; first-party actions stay tag-trusted by recorded choice |
| CI-23 | Every `appleboy/ssh-action` script starts with `set -e` | The action has no fail-fast input; `script_stop` was silently ignored |
| CI-24 | Production registers Avro schemas in a job after deploy; nonprod registers inside its deploy, before the app starts | Do not add a new production failure mode; make nonprod's guarantee literal |
| CI-25 | Prune old Docker Hub tags after a good deploy; delete the new tag by digest after a failed deploy | Keep one active image per repository |
| CI-26 | Build the Caddy image in CI with `load`, prove it, validate the Caddyfile, then push | A broken edge image must never reach the registry |
| CI-27 | A separate `invariant-checks.yml` runs pure-function Python gates on pull requests, each with a self-test where one exists | Make drift fail on the PR, and prove each gate can still fire |
| CI-28 | OWASP dependency-check is report-only, weekly, off every developer path | Its verdict drifts with NVD; it is the heaviest task in the build |
| CI-29 | Uptime probe every 15 minutes; rate-limit verification on manual dispatch only | Date outages; do not spend bcrypt and IP budget on every deploy |
| CI-30 | Dependabot: grouped Gradle PRs, GitHub Actions, Caddy base images; app base images excluded | Remediate advisories; one verification-metadata regeneration per batch |
| CI-31 | Multi-stage `Dockerfile`: Gradle JDK build stage, `eclipse-temurin:21-jre-jammy` runtime | Small runtime image; the old `openjdk` image stopped resolving |

## The rule that decides which checks may block

### What it is

The pipeline sorts every check into one of two classes. A check that gives the same verdict for the
same commit forever may block a commit or a merge. A check whose verdict can change with no code
change runs on a schedule and reports only.

### How it works

The hard gates are Spotless, Error Prone, JaCoCo, ArchUnit, `verifyRuntimeDependencyFloor`, gitleaks,
TruffleHog and the `scripts/verify-*.py` invariant gates. The only report-only gate is OWASP
dependency-check. The comment in
[`build.gradle` (OWASP plugin block)](../../build.gradle#L45-L50) states the difference:

```groovy
// Its verdict is not a pure function of the source tree: every other gate here (Spotless,
// Error Prone, JaCoCo, ArchUnit, verifyRuntimeDependencyFloor) yields the same verdict for
// the same commit forever. This one does not -- a green commit goes red days later when a
// new advisory publishes against an unchanged dependency, with zero code change.
```

### Why we chose it

**CI-01.** A new CVE in a transitive dependency is not something the committer wrote. If the scan
blocked the deploy, an advisory published on Monday would block an unrelated fix on Tuesday
([`security-scan.yml` header](../../.github/workflows/security-scan.yml#L1-L15)). The same argument
gives gitleaks its hard gate. The scanner version is pinned and the config is committed. So its
verdict is a pure function of the commit
([`secret-scan.yml` header](../../.github/workflows/secret-scan.yml#L8-L18)).

### Trade-offs and limits

- A pin is what makes a scanner deterministic. If someone replaces the gitleaks digest with `:latest`,
  a new upstream rule can red an unchanged commit. The hook comment calls this pin "load-bearing"
  ([`.githooks/pre-commit`](../../.githooks/pre-commit#L24-L31)).
- The report-only scan fails nothing. A real CVE can sit in the report until a human reads it.

### Where this is recorded

- [260816-hn1-PLAN.md, Decision 3](../../.planning/quick/260816-hn1-wire-up-secret-scanning-gitleaks-truffle/260816-hn1-PLAN.md)
  (quick task that added secret scanning): "The version pin is what earns the hard gate."
- [10-CONTEXT.md, "Established Patterns"](../../.planning/milestones/v1.3-phases/10-ci-deploy-hardening/10-CONTEXT.md).

## Gradle build, toolchain and pinning

### What it is

The build is one Gradle project in Groovy DSL ([`settings.gradle`](../../settings.gradle) sets
`rootProject.name = 'kanban-board'`). It applies Spring Boot 3.5.16, the Spring dependency-management
plugin, Spotless, Error Prone, JaCoCo, the Avro code generator and OWASP dependency-check.

### How it works

1. The [`java.toolchain`](../../build.gradle#L66-L70) block sets `languageVersion = 21`. Gradle uses
   a Java 21 compiler even when the host JDK is a different version.
2. The wrapper downloads Gradle 8.11.1. The file
   [`gradle-wrapper.properties`](../../gradle/wrapper/gradle-wrapper.properties) pins
   `distributionSha256Sum`. A wrong checksum stops the wrapper before it runs.
3. In CI, the step `gradle/actions/wrapper-validation@v6` runs directly after checkout and before
   any `./gradlew` call ([`deploy.yml` run-tests](../../.github/workflows/deploy.yml#L63-L70),
   [`security-scan.yml`](../../.github/workflows/security-scan.yml#L67-L72)). It compares
   `gradlew`, `gradlew.bat` and `gradle-wrapper.jar` to Gradle's published checksums.
4. [`gradle/verification-metadata.xml`](../../gradle/verification-metadata.xml) holds 619
   `<component>` entries, each with SHA-256 values. It sets `verify-metadata=true` and
   `verify-signatures=false`. Gradle refuses an artifact whose content does not match.

The regeneration command is recorded in
[`build.gradle`](../../build.gradle#L114-L126):

```
./gradlew --write-verification-metadata sha256 --refresh-dependencies spotlessCheck test
```

### Why we chose it

- **CI-02.** A wrapper that already ran cannot be validated after the fact, so validation comes
  first ([`deploy.yml`](../../.github/workflows/deploy.yml#L63-L68)). The Gradle wrapper integrity
  todo was folded into Phase 10
  ([10-CONTEXT.md, "Folded Todos"](../../.planning/milestones/v1.3-phases/10-ci-deploy-hardening/10-CONTEXT.md)).
- **CI-03.** Tool versions are exact. The Error Prone comment gives the reason. A floating version lets
  an upstream release with no local code change add a new ERROR-severity check. That new check
  can then fail CI and the Docker build "on its own" ([`build.gradle`](../../build.gradle#L684-L688)). JaCoCo
  (`0.8.12`) and the Avro plugin (`1.9.1`) follow the same rule. Spring-managed artifacts
  (Flyway, the PostgreSQL driver, `spring-kafka`, Testcontainers) stay unversioned, so that a
  Spring Boot bump moves them together ([`build.gradle`](../../build.gradle#L151-L156)).
- **CI-04.** An exact version pin catches a different version. It does not catch an artifact that
  someone republished under the same `group:name:version`
  ([`build.gradle`](../../build.gradle#L104-L112)).

### Alternatives we rejected

- A minimal `help` task to generate the metadata. It under-resolves configurations, so the file
  passes locally and fails the first time CI resolves a configuration that `help` never touched.
- Generation without `--refresh-dependencies`. The first attempt served buildscript POMs from a warm
  cache, and the next cold run failed on artifacts that no dependency names directly. The team
  reproduced this with no code change ([`build.gradle`](../../build.gradle#L118-L126)).
- A separate CI step that checks whether the metadata is stale. It was not built, because Gradle
  already refuses an unknown artifact at resolution time
  ([`build.gradle`](../../build.gradle#L128-L135)).

### Trade-offs and limits

- Every dependency bump fails with a verification error until a human regenerates the file. This is
  expected behavior, and it is why Dependabot groups Gradle updates into one PR (**CI-30**).
- Signature verification is off. The file checks content hashes, not publisher identity.
- `-sources.jar` and `-javadoc.jar` files are trusted by regex, because only IDE tooling reads them.

### How we test it

The metadata is exercised on every `./gradlew` run. Plan 10-04 proved the failure path on a scratch
branch: a bumped dependency failed `./gradlew spotlessCheck test --refresh-dependencies` with Gradle's
own verification error
([10-04-SUMMARY.md](../../.planning/milestones/v1.3-phases/10-ci-deploy-hardening/10-04-SUMMARY.md)).

### Where this is recorded

- [10-CONTEXT.md](../../.planning/milestones/v1.3-phases/10-ci-deploy-hardening/10-CONTEXT.md),
  [10-04-SUMMARY.md](../../.planning/milestones/v1.3-phases/10-ci-deploy-hardening/10-04-SUMMARY.md).
- Commit `6431f34` (distribution checksum and wrapper validation).

## Dependencies and why each major one exists

### What it is

The [`dependencies`](../../build.gradle#L136-L286) block. The table lists the major non-Spring
dependencies and the reason the sources give.

| Dependency | Scope | What it does here | Recorded reason |
|------------|-------|-------------------|-----------------|
| Lombok 1.18.36 | `compileOnly` + `annotationProcessor` | Generates getters, setters, builders, `equals`/`hashCode` at compile time | Reason not recorded |
| MapStruct 1.5.3.Final | `implementation` + `annotationProcessor` | Generates `*MapperImpl` classes for entity/DTO mapping | Reason not recorded |
| Vavr 0.10.4 | `implementation` | `io.vavr.control.Try` in `AuthenticationController` and `GlobalExceptionHandler` | Reason not recorded |
| Guava 32.0.1-android | `implementation` | `@VisibleForTesting` in four services, `Ints` in `TaskService`/`ColumnService` | Reason not recorded |
| commons-lang3 3.18.0 | `implementation` | No direct `src/main` import; tests use `RandomStringUtils` | Floor for a transitive CVE (**CI-06**) |
| commons-collections4 4.5.0 | `implementation` | Only tests import it (`ListUtils`) | Reason not recorded |
| Avro 1.12.1 + Avro Gradle plugin 1.9.1 | `implementation` / plugin | Compiles `src/main/avro/*.avsc` to `SpecificRecord` classes | Plugin is final and complete for `.avsc` codegen |
| `kafka-avro-serializer` 7.8.9 | `implementation` | Confluent serializer and Schema Registry client | Not on Maven Central, so the Confluent repository is declared |
| springdoc 2.8.8 | `implementation` | OpenAPI document at `/api/docs` | — |
| ArchUnit 1.4.2 | `testImplementation` | Architecture rules (**CI-11**) | — |
| `ulid-creator` 5.2.0 | `implementation` | No import in `src/main` or `src/test` | Reason not recorded |

### How it works

Lombok and MapStruct are annotation processors. They run inside `javac` and write ordinary Java
source or bytecode. At runtime no reflection is needed for them. This has two effects on the gates:

- Error Prone excludes `build/generated/**` and `build/generated-main-avro-java/**`
  ([`build.gradle`](../../build.gradle#L707-L712)).
- JaCoCo excludes `**/mapper/*MapperImpl.class` and `**/event/avro/Avro*.class`
  ([`build.gradle`](../../build.gradle#L393-L407)). Lombok members are inline, so they cannot be
  excluded by path. [`lombok.config`](../../lombok.config) sets
  `lombok.addLombokGeneratedAnnotation = true`, and JaCoCo's built-in filter skips members with
  `@lombok.Generated`.

### Why we chose it

- **CI-05.** The sources do not record why Lombok and MapStruct were chosen. The code shows the
  consequence: both need build-level exclusions in Error Prone and JaCoCo, and `lombok.config`
  exists only for JaCoCo's benefit ([`lombok.config`](../../lombok.config)).
- **CI-06.** The team first bumped commons-lang3 from 3.0 to 3.18.0 as `testImplementation` in
  Phase 4. Spring's dependency-management plugin forced the old 3.0 pin project-wide, and
  Testcontainers' `commons-compress` needs `ArrayFill` (added in 3.11). The result was a
  `NoClassDefFoundError` in three Kafka E2E tests. The test-scope fix never touched
  `runtimeClasspath`, which is the classpath `bootJar` packages. That classpath resolved 3.17.0,
  which carries CVE-2025-48924. Quick task 260813-q1i moved the line to `implementation` and added
  a guard ([`build.gradle`](../../build.gradle#L183-L208)).
- **CI-07.** Two excludes on `kafka-avro-serializer`
  ([`build.gradle`](../../build.gradle#L238-L258)):
  - `kafka-clients`, so that Spring Boot's BOM version stays the only one on the classpath.
  - `io.swagger.core.v3:swagger-annotations` (pre-Jakarta 2.1.10). It shares a package with
    `swagger-annotations-jakarta` 2.2.30. The JVM loaded the older `Parameter` class, which lacks
    `validationGroups()`, and every `GET /api/docs` returned 500 with `NoSuchMethodError`.
- The Avro plugin is pinned to 1.9.1 because it is the final release of an archived project. It
  does all the `.avsc` codegen this project needs ([`build.gradle`](../../build.gradle#L16-L21)).

### Alternatives we rejected

For the commons-lang3 guard, [260813-q1i-PLAN.md](../../.planning/quick/260813-q1i-add-a-dependency-vulnerability-scan-owas/260813-q1i-PLAN.md)
rejected three options:

- A JUnit test that reads the library version. The test JVM uses `testRuntimeClasspath`, which was
  already correct, so the test "would have been green throughout the entire period the production
  classpath was vulnerable."
- Rely on `dependencyCheckAnalyze`. It is report-only and weekly.
- Fix the pin and write no check. That leaves the same silent-drift shape in place.

### Trade-offs and limits

- `verifyRuntimeDependencyFloor` has one entry today. It fails loudly if the coordinate disappears
  from `runtimeClasspath`, so it cannot silently stop checking
  ([`build.gradle`](../../build.gradle#L507-L512)).
- The OWASP plugin 12.x and later is on the Gradle Plugin Portal only, not on Maven Central. A
  future `pluginManagement { repositories { mavenCentral() } }` block would break plugin resolution
  ([`build.gradle`](../../build.gradle#L33-L37)).

### How we test it

- [`verifyRuntimeDependencyFloor`](../../build.gradle#L514-L583) resolves
  `configurations.runtimeClasspath` and compares major, minor and patch numbers element by element.
  The `test` task `dependsOn` it ([`build.gradle`](../../build.gradle#L329)), so it fails in seconds,
  before the suite starts.
- The `/api/docs` defect has its own fix record:
  `.planning/todos` item `2026-08-09-fix-broken-api-docs-swagger-endpoint-swagger-annotations-ver.md`
  (named in the [`build.gradle` comment](../../build.gradle#L243-L244)).

### Where this is recorded

- [260813-q1i-SUMMARY.md](../../.planning/quick/260813-q1i-add-a-dependency-vulnerability-scan-owas/260813-q1i-SUMMARY.md):
  Spring Boot 3.5.0 to 3.5.16, OSV advisory baseline 70 to 5 findings.

## Formatting, static analysis and coverage

### What it is

Four build-level gates run on every compile or test:

- Spotless (formatting)
- Error Prone (compile-time bug patterns)
- JaCoCo (coverage ratchet)
- ArchUnit (architecture rules, see the next section)

### How it works

**Spotless.** The [`spotless`](../../build.gradle#L72-L90) block applies Google Java Format in AOSP
style (4-space indentation) to `src/**/*.java`, formats annotations, removes unused imports and sets
the import order:

```groovy
importOrder('java', 'javax', 'com.vrudenko', '', '\\#')
```

The groups are `java.*`, `javax.*`, first-party `com.vrudenko.*`, everything else, then static
imports. `'\\#'` is Groovy escaping for the literal token `\#` that Spotless uses for static imports.
The `javax` group matches zero imports today and stays as future-proofing.

**Error Prone.** Error Prone is a `javac` plugin, not a separate task
([`build.gradle`](../../build.gradle#L677-L712)). It runs on every path that compiles:
`./gradlew test`, CI, and the Dockerfile's `./gradlew bootJar`. `compileTestJava` promotes five
checks to errors: `FutureReturnValueIgnored`, `StringCaseLocaleUsage`, `MissingOverride`,
`NotJavadoc`, `DefaultCharset` ([`build.gradle`](../../build.gradle#L726-L733)).

**JaCoCo.** The agent attaches to `test` only. `fastTest` sets `jacoco { enabled = false }`
([`build.gradle`](../../build.gradle#L378-L380)). The ratchet
[`jacocoTestCoverageVerification`](../../build.gradle#L462-L489) sets three minimums:

| Counter | Measured baseline (2026-08-12) | Minimum |
|---------|-------------------------------|---------|
| INSTRUCTION | 91.23% | 0.90 |
| LINE | 90.93% | 0.90 |
| BRANCH | 78.62% | 0.75 |

The `test` task has `finalizedBy jacocoTestCoverageVerification`
([`build.gradle`](../../build.gradle#L312-L321)).

### Why we chose it

- **CI-08.** Formatting is mechanical, so the build enforces it. The reason for the import-group
  order is recorded in [`docs/CODE_STYLE.md` rule 10](../CODE_STYLE.md#L435-L441). First-party
  imports sit third on purpose. The rule text exists so that nobody "corrects" it toward the
  more common first-party-last order.
- **CI-09.** Error Prone finds bug classes that formatting cannot: null dereferences, ignored return
  values, misused APIs. Its gate strength came from a measured run. Quick task 260802-qr8 found 5
  main-source findings (3 `UnusedVariable`, 1 `StringCaseLocaleUsage`, 1 `FutureReturnValueIgnored`)
  and fixed all 5 in source. Test sources had 27 findings, and quick task 260803-v23 took them to
  zero. The five checks became errors only after that
  ([`build.gradle`](../../build.gradle#L698-L706)). The promotion names five checks, not "all
  warnings are errors", so a version bump cannot red the build with an unrelated new check.
- **CI-10.** CI runs `./gradlew test` and `./gradlew spotlessCheck` as two steps. It never runs
  `check`, and the JaCoCo plugin does not wire verification into `check` by itself. Without
  `finalizedBy`, the ratchet "would silently never fire". `finalizedBy` runs after the `.exec` file
  exists and still fails the build. The minimums sit a few points below the baseline, so ordinary
  code-shape noise does not fail the next PR.

### Alternatives we rejected

- **Error Prone vs. SpotBugs.** SpotBugs analyzes bytecode in a separate `spotbugsMain` task. That
  task is not on the `test` or `bootJar` path, so CI would need edits. It also reports on synthetic
  Lombok methods with no useful source position. It stays a possible complement for
  `find-sec-bugs` ([260802-qr8-PLAN.md](../../.planning/quick/260802-qr8-add-errorprone-for-compile-time-bug-dete/260802-qr8-PLAN.md)).
- **Error Prone vs. `-Xlint:all -Werror`.** `-Xlint` has about a dozen categories and no null or
  ignored-return analysis. `-Werror` would fail on Spring and Vavr generic warnings that nobody can
  act on.
- **JaCoCo with an immediate hard threshold.** A guessed number on a codebase with Lombok, MapStruct
  and Avro bytecode is either trivial or wrong
  ([260812-eg8-PLAN.md](../../.planning/quick/260812-eg8-investigate-test-coverage-gap-tracking-j/260812-eg8-PLAN.md)).
- **ArchUnit "every class has a test" rule instead of JaCoCo.** It proves a test class exists, not
  that routes are exercised. It would have missed the zero-coverage `atLeastOneFieldPopulated()`
  validators that JaCoCo found.

### Trade-offs and limits

- The JaCoCo ratchet is a drift alarm, not a proof of adequacy. A green result means "no class went
  dark since 2026-08-12" ([`build.gradle`](../../build.gradle#L455-L461)).
- The ratchet deliberately accepts two known gaps: `atLeastOneFieldPopulated()` on
  `UpdateTaskRequestDTO` and `UpdateSubtaskRequestDTO`.
- `compileJava` has no promoted checks. A warning-severity `EscapedEntity` finding at
  `UserMapper.java:23` remains ([`build.gradle`](../../build.gradle#L722-L725)).

### How we test it

The gates test themselves on each run: `./gradlew spotlessCheck` and `./gradlew test` (which runs
`jacocoTestCoverageVerification` after it). The coverage report is at
`build/reports/jacoco/test/html/index.html`.

### Where this is recorded

- [260802-qr8-PLAN.md](../../.planning/quick/260802-qr8-add-errorprone-for-compile-time-bug-dete/260802-qr8-PLAN.md),
  [260803-v23-SUMMARY.md](../../.planning/quick/260803-v23-hard-gate-compiletestjava-error-prone-fi/260803-v23-SUMMARY.md),
  [260812-eg8-SUMMARY.md](../../.planning/quick/260812-eg8-investigate-test-coverage-gap-tracking-j/260812-eg8-SUMMARY.md)
  (operator chose "option-a": ratchet at the measured baseline).

## Judgement rules: `docs/CODE_STYLE.md` and ArchUnit

### What it is

[`docs/CODE_STYLE.md`](../CODE_STYLE.md) records 13 rules that Spotless cannot check. Each rule has
a statement, a **Why** line and a bad/good code example
([`CODE_STYLE.md`, "Adding a rule"](../CODE_STYLE.md#L649-L651)). Some rules also have an ArchUnit
rule that fails `./gradlew test`.

### How it works

The main rules, with their reasons from the file:

| Rule | Statement | Why (from the file) | Enforced by |
|------|-----------|---------------------|-------------|
| [2](../CODE_STYLE.md#L40-L44) | Load entities through the ownership-verified `findById(userId, id)`, never `repository.findById` | This is the whole access-control model, and the type system does not enforce it | `LayeringArchTest.domain_services_must_load_through_ownership_verified_findById` |
| [4](../CODE_STYLE.md#L116-L226) | No mocks; test against real Spring wiring and Testcontainers PostgreSQL | A mocked repository bypasses the ownership chain and JPA behavior the tests exist to protect | Convention |
| [5](../CODE_STYLE.md#L249-L253) | `@Nested` per method under test; `should<Outcome>_when<Condition>`; AAA comments | The method is the unit of navigation; no separate `@DisplayName` to drift | Convention |
| [6](../CODE_STYLE.md#L290-L294) | `Update*RequestDTO` has `@JsonInclude(NON_NULL)` and `@NotNull Long version` | A missing `version` silently disables optimistic locking | Convention |
| [7](../CODE_STYLE.md#L332-L371) | Unwrap `Optional` with an `isEmpty()` guard, not `orElseThrow` | Consistency; a second check fits beside the guard as a peer | `LayeringArchTest` (the `findById` part) |
| [8](../CODE_STYLE.md#L373-L377) | Test setup must be fully automated | A manual step is a step every new machine forgets | Convention; cited by the hook, gitleaks and hooks-path decisions |
| [10](../CODE_STYLE.md#L435-L441) | Five import groups | Records why first-party sits third | Spotless |
| [11](../CODE_STYLE.md#L513-L517) | Every `@RestController` has class-level `@Validated` | It decides which exception Spring throws, and so which error envelope the client gets | `LayeringArchTest.rest_controllers_must_carry_class_level_validated` |
| [13](../CODE_STYLE.md#L592-L619) | Test classes live in a named subpackage | 11 test files drifted into the root package before the rule existed | `TestPlacementArchTest.test_classes_must_not_reside_directly_in_the_root_package` |

### Why we chose it

**CI-11.** The file says an unwritten convention "silently reopens every few sessions"
([rule 13](../CODE_STYLE.md#L607-L616)). Where a rule can be checked by structure, an ArchUnit rule
holds it; where it needs judgement, the prose and its example hold it. Rule 8 is the rule that the
build decisions in this chapter cite most. The hooks-path bootstrap, the gitleaks Docker image and
the rejection of detect-secrets all refer to it.

### Trade-offs and limits

- `LayeringArchTest` is "a floor, not a ceiling" ([rule 7](../CODE_STYLE.md#L371)).
- `TestPlacementArchTest` catches the root package only. A test in the wrong subpackage passes.

### How we test it

- [`LayeringArchTest`](../../src/test/java/com/vrudenko/kanban_board/architecture/LayeringArchTest.java#L77-L211):
  `controllers_must_not_reach_into_repositories`,
  `domain_services_must_load_through_ownership_verified_findById`,
  `rest_controllers_must_carry_class_level_validated`,
  `mutating_handlers_must_bind_request_dto_parameters_from_the_body`.
- [`TestPlacementArchTest`](../../src/test/java/com/vrudenko/kanban_board/architecture/TestPlacementArchTest.java#L45).

Both run in `fastTest`, so the pre-commit hook catches a violation before CI.

## The pre-commit hook

### What it is

[`.githooks/pre-commit`](../../.githooks/pre-commit) is a POSIX shell script. It runs three gates in
a fixed order and stops at the first failure. It never changes a staged file.

### How it works

```mermaid
flowchart TD
    A[git commit] --> B{gitleaks on staged diff<br/>pinned Docker image}
    B -- exit 0 --> C{./gradlew spotlessCheck}
    B -- exit 2: finding --> R1[Refuse: remove or allowlist]
    B -- other exit --> R2[Refuse: scanner could not run]
    C -- fail --> R3[Refuse: run spotlessApply]
    C -- pass --> D{./gradlew fastTest<br/>compile + untagged tests}
    D -- fail --> R4[Refuse]
    D -- pass --> E[Commit created]
```

1. **Install.** A block at the end of `build.gradle` runs at configuration time on any `./gradlew`
   call ([`build.gradle`](../../build.gradle#L749-L775)). It reads the effective
   `core.hooksPath`. If the value is not `.githooks`, it writes it with `git config --local`. It
   skips the step when `.git` does not exist, and it never fails the build.
2. **Secret scan.** The hook runs gitleaks in the pinned image
   `ghcr.io/gitleaks/gitleaks:v8.30.1@sha256:c00b6bd0...` with `git --staged --redact --verbose
   --exit-code 2` ([`.githooks/pre-commit`](../../.githooks/pre-commit#L63-L143)).
3. **Three-way result.** Exit 0 means clean. Exit 2 means a finding. Any other code means "did not
   run", for example a stopped Docker daemon. Both non-zero outcomes refuse the commit
   ([`.githooks/pre-commit`](../../.githooks/pre-commit#L145-L164)).
4. **Format.** `./gradlew spotlessCheck < /dev/null`
   ([`.githooks/pre-commit`](../../.githooks/pre-commit#L166-L171)).
5. **Compile and test.** `./gradlew fastTest < /dev/null`. This compiles main and test sources
   (so Error Prone runs) and runs every test class without a `kafka` or `realSocket` tag
   ([`.githooks/pre-commit`](../../.githooks/pre-commit#L173-L185)).

**Worktree handling.** A git worktree's private git directory holds a relative `commondir` path.
If the hook mounts only that directory into the container, gitleaks scans 0 commits and reports
clean. The hook mounts the common parent of the git directory and the work tree instead, and passes
`GIT_DIR` and `GIT_WORK_TREE` as container paths. For a worktree outside the repository tree, no
common parent exists. The hook then pipes `git diff --cached` into `gitleaks stdin`
([`.githooks/pre-commit`](../../.githooks/pre-commit#L33-L62)).

### Why we chose it

- **CI-12.** Rule 8 of `CODE_STYLE.md` forbids a manual setup step, so the build installs the hook
  itself (quick task 260802-pw0). The first version of the hook ran `spotlessApply` and re-staged
  the files. Commit `199300f` replaced this with `spotlessCheck`: the hook now fails with an
  instruction and never rewrites a staged file. This is a reversed decision.
- **CI-13.** The secret scan runs first because `spotlessCheck` and `fastTest` take about four minutes
  together. A staged credential must be refused in seconds. A scanner that cannot run refuses the
  commit. The reason: a gate that silently skips "is worse than no gate, because it is believed"
  ([260816-hn1-PLAN.md, Decision 2](../../.planning/quick/260816-hn1-wire-up-secret-scanning-gitleaks-truffle/260816-hn1-PLAN.md)).
- **`< /dev/null`.** Without it, Gradle blocked for more than 80 minutes with flat CPU when the hook
  started it. A direct run finished in 17 seconds (commit `199300f`,
  [`docs/SESSION_LESSONS.md` lesson 3](../SESSION_LESSONS.md)).
- **Out-of-tree worktree fallback.** Phase 10 (requirement HARDEN-05) shipped a real fix, not a
  documented limitation ([10-CONTEXT.md, D-11/D-12](../../.planning/milestones/v1.3-phases/10-ci-deploy-hardening/10-CONTEXT.md)).

### Trade-offs and limits

- `git commit --no-verify` skips the hook. CI's `secret-scan.yml` exists partly to catch this.
- The `stdin` fallback loses path context. This costs nothing today, because every allowlist entry
  in `.gitleaks.toml` is rule-scoped with `regexTarget = "match"`.
- Docker must run to commit. The hook needs it for gitleaks, and `fastTest` needs it for
  Testcontainers.

### How we test it

Quick task 260816-hn1 planted a synthetic AWS key, `AKIATESTFAKEKEY23456`, to prove the refusal
path. That value is now allowlisted in `.gitleaks.toml` by exact match
([`.gitleaks.toml`](../../.gitleaks.toml#L39-L53)). No automated test covers the hook script.

### Where this is recorded

- [260802-pw0-SUMMARY.md](../../.planning/quick/260802-pw0-auto-configure-git-core-hookspath-so-the/260802-pw0-SUMMARY.md),
  commits `f77f131` (first hook), `199300f` (check-only), `0c7a86e` (`@Tag` selection).

## The fast test gate

### What it is

[`fastTest`](../../build.gradle#L343-L381) is a second `Test` task that only the pre-commit hook
uses. CI runs the full `test` task.

### How it works

- `fastTest` excludes JUnit 5 tags `kafka` and `realSocket`. `test` excludes only the `rehearsal`
  tag, which runs under `rehearseHistoricalSchemas` against a real historical database
  ([`build.gradle`](../../build.gradle#L288-L298)).
- Both tasks set `maxParallelForks = 2`. Each fork is a separate JVM, so
  `AbstractPostgresContainerTest` starts one PostgreSQL container per fork.
- `forkEvery` stays at 0. A non-zero value restarts the JVM, and the container, for every class.

### Why we chose it

**CI-14.** Membership is by tag, so a new test class runs in the gate by default. It leaves the gate
only if it truly needs Kafka or a real socket (Phase 7.1 commit `0c7a86e` replaced the older
`*E2ETest` name filter). Two forks came from measurement in quick task 260811-ixj:

| Task | 1 fork (avg) | 2 forks (avg) | 4 forks |
|------|--------------|---------------|---------|
| `test` | 370.5 s | 276.5 s | not adopted |
| `fastTest` | 285.0 s | 242.5 s | 267.0 s average of 277 s and 257 s (not adopted) |

Both 2-fork results beat the documented run-to-run variance of about 18 seconds. The 4-fork runs did
not clear it consistently ([`build.gradle`](../../build.gradle#L299-L310)).

### Trade-offs and limits

`fastTest` is not free of Redpanda. `HistoricalActivityEventReconstructorTest` extends
`AbstractKafkaContainerTest` with no tag, so up to one Redpanda container starts
([`build.gradle`](../../build.gradle#L356-L360)).

### Where this is recorded

[260811-ixj-MEASUREMENTS.md](../../.planning/quick/260811-ixj-investigate-and-implement-test-suite-spe/260811-ixj-MEASUREMENTS.md).

## Secret scanning

### What it is

Three scans, at two places:

| Scanner | Where | Scope | Gate |
|---------|-------|-------|------|
| gitleaks v8.30.1 | Pre-commit hook | Staged diff | Refuses the commit |
| gitleaks v8.30.1 | `secret-scan.yml`, job `secret-scan` | Full history, every push to `main` and every PR | Fails the job |
| TruffleHog 3.97.0 | `secret-scan.yml`, job `verified-credential-scan` | Commit range of the push or PR | Fails the job on a verified live credential |

### How it works

**gitleaks in CI.** The job checks out with `fetch-depth: 0`. A shallow checkout scans one commit
and reports clean, which looks the same as a real clean result
([`secret-scan.yml`](../../.github/workflows/secret-scan.yml#L38-L47)). It runs the same image
reference as the hook, with `--baseline-path /repo/.gitleaks-baseline.json`
([`secret-scan.yml`](../../.github/workflows/secret-scan.yml#L71-L81)). It uploads the redacted JSON
report as an artifact.

**`.gitleaks.toml` policy.**

1. `[extend] useDefault = true`: the file adds to the maintained default rules, so upstream rule
   fixes arrive with each version bump ([`.gitleaks.toml`](../../.gitleaks.toml#L10-L11)).
2. `curl-auth-user` allowlist: 12 of the 13 baseline findings were
   `curl -u "$DOCKERHUB_USER:${{ secrets.DOCKERHUB_TOKEN }}"`, a template reference, not a value.
   The allowlist matches `\$\{\{\s*secrets\.` against the matched text of that rule only
   ([`.gitleaks.toml`](../../.gitleaks.toml#L13-L37)).
3. `aws-access-token` allowlist: the synthetic canary only.
4. `generic-api-key` finding in `application.properties` at commit `5121740f61`: a real local-dev
   PostgreSQL password from 2025-06-05. It is deliberately not allowlisted. The committed baseline
   file suppresses that one fingerprint (commit, file, line) in the CI job only
   ([`.gitleaks.toml`](../../.gitleaks.toml#L55-L72)).

**TruffleHog.** The job resolves `--since-commit` from the event. It uses the PR base SHA, the
push `before` SHA, or `HEAD~1` (for a new branch or a manual run)
([`secret-scan.yml`](../../.github/workflows/secret-scan.yml#L164-L197)). It runs
`--results=verified --json --no-update`. Exit 183 means a verified credential. The raw output holds
the credential value. So the step keeps only named fields with `jq`, and deletes the raw file in
the same step ([`secret-scan.yml`](../../.github/workflows/secret-scan.yml#L212-L261)):

```sh
jq -c '{SourceMetadata: .SourceMetadata, DetectorName: .DetectorName, DetectorDescription: .DetectorDescription, DecoderName: .DecoderName, Verified: .Verified, VerificationFromCache: .VerificationFromCache}' \
  .trufflehog-reports/raw-findings.jsonl > .trufflehog-reports/findings-scrubbed.jsonl
rm -f .trufflehog-reports/raw-findings.jsonl
```

### Why we chose it

- **CI-15.** gitleaks is one static Go binary with an official Docker image, a native staged-diff
  mode and one committed TOML config. The Docker image adds no new prerequisite, because the hook
  already needs Docker for Testcontainers. The hook scans the staged diff, because a full scan
  re-reads 647 commits to validate a three-line change. CI scans full history, because a secret that
  entered history earlier is invisible to the hook
  ([260816-hn1-PLAN.md, Decisions 1-3](../../.planning/quick/260816-hn1-wire-up-secret-scanning-gitleaks-truffle/260816-hn1-PLAN.md)).
  CI calls the image directly, not the vendor's `gitleaks-action`. That action needs a licence
  key for organization use. A direct call also keeps the CI command identical to the hook's.
- **CI-16.** A blanket path exemption for `.planning/` "converts this work into security theatre".
  The 93 KB `STATE.md` was the file that started the task. The baseline and the allowlist are
  different tools. An allowlist hides a finding under every invocation. A baseline hides one
  fingerprint in one workflow, and any new finding still fails.
- **CI-17.** TruffleHog answers a second question: "is this credential live now?" Each candidate
  costs an authentication call to a third-party provider. From a developer's machine at commit time
  that is an exposure; from an ephemeral GitHub runner it is acceptable
  ([10-CONTEXT.md, D-08 to D-10](../../.planning/milestones/v1.3-phases/10-ci-deploy-hardening/10-CONTEXT.md)).
  It scans a range, not full history, so that verification traffic stays in proportion to the
  change. It is a hard gate because it only narrows what gitleaks already flags.

### Alternatives we rejected

- **TruffleHog as the primary scanner.** The recorded reason: at commit time "you want to block on
  suspicion, not confirm exploitation". It became a CI-only second pass in Phase 10.
- **detect-secrets (Yelp).** It needs a Python runtime on every clone, which rule 8 forbids.
- **A Gradle task that downloads a native gitleaks binary.** About 60 lines of per-OS logic for no
  benefit on a machine that already runs Docker.
- **"Warn and skip if not installed."** A security gate that silently no-ops is believed and is
  therefore worse than no gate.
- **A `del(...)` denylist for the TruffleHog report.** A new value-bearing field in a future
  TruffleHog version would leak. The allowlist drops unknown fields by default.
- **Passing `--branch` to TruffleHog.** The runner's checkout is a detached HEAD. So the branch
  name has no local ref, and the scan would fail on every push.

### Trade-offs and limits

- gitleaks detects credential shapes. It does not catch a secret in an unusual format, a secret
  written as a sentence, or a value below the entropy threshold.
- The old local-dev password stays in history. The project carries it as a conscious decision.

### How we test it

- The first real CI run, before the baseline existed, failed as predicted
  ([`secret-scan.yml`](../../.github/workflows/secret-scan.yml#L68-L70)).
- [260816-hn1-MEASUREMENTS.md](../../.planning/quick/260816-hn1-wire-up-secret-scanning-gitleaks-truffle/260816-hn1-MEASUREMENTS.md):
  a full-history scan takes 13.6-19.5 s locally for about 620 commits. Also, `gitleaks dir` does
  not respect `.gitignore`. And `--redact` masks values inside the JSON report too.

### Where this is recorded

- [260816-hn1-PLAN.md](../../.planning/quick/260816-hn1-wire-up-secret-scanning-gitleaks-truffle/260816-hn1-PLAN.md),
  [10-02-SUMMARY.md](../../.planning/milestones/v1.3-phases/10-ci-deploy-hardening/10-02-SUMMARY.md).

## The deploy pipeline (`deploy.yml`)

### What it is

[`deploy.yml`](../../.github/workflows/deploy.yml) ("CI/CD with Docker") tests, builds, verifies
migrations and deploys both environments. It runs on push to `main` only and ignores `docs/**`,
`**/*.md` and `.planning/**` ([`deploy.yml`](../../.github/workflows/deploy.yml#L3-L16)).

### How it works

```mermaid
flowchart LR
    setup --> rt[run-tests]
    rt --> bpi[build-and-push-docker-image]
    rt --> bpc[build-and-push-caddy-image]
    rt --> fv[flyway-verify]
    rt --> fvn[flyway-verify-nonprod]
    bpi --> dn[deploy-to-netcup]
    fv --> dn
    bpc --> dn
    dn --> rsp[register-schemas-production]
    dn --> coi[cleanup-old-images]
    dn -. failure .-> cui[cleanup-unused-image]
    bpi --> dnp[deploy-to-nonprod]
    fvn --> dnp
    dnp --> hcn[health-check-nonprod]
    hcn --> coin[cleanup-old-images-nonprod]
    dnp -. failure .-> cuin[cleanup-unused-image-nonprod]
```

The production path and the nonprod path share only `setup`, `run-tests` and
`build-and-push-docker-image`. Neither path waits on the other.

| Job | Environment | What it does |
|-----|-------------|--------------|
| `setup` | — | Writes the three Docker Hub image base names as job outputs |
| `run-tests` | — | Checkout, wrapper validation, Temurin 21 with Gradle cache, `./gradlew test`, `./gradlew spotlessCheck` |
| `build-and-push-docker-image` | production | Buildx, `linux/amd64`, one build pushed with two tags: `<repo>:<sha7>` and `<repo>-nonprod:<sha7>` |
| `build-and-push-caddy-image` | production | Tag gate, build with `load: true`, prove module, validate Caddyfile, push |
| `flyway-verify` / `-nonprod` | production / staging | SCP migration SQL to the VM; run `flyway/flyway:11.7.2 migrate` in a one-off container on the `kanban-db` network |
| `deploy-to-netcup` | production | SCP seven named files and directories; `docker compose pull app caddy`; `up -d`; `caddy reload`; read back the running config |
| `register-schemas-production` | production | Run `AvroSchemaRegistrar` in a one-off `app` container |
| `deploy-to-nonprod` | staging | SCP the nonprod Compose file; start Redpanda; register schemas; start `app-nonprod` |
| `health-check-nonprod` | staging | Poll `/api/actuator/health` 30 times, 10 s apart |
| `cleanup-*` | production / staging | Prune old tags, or delete a failed tag (**CI-25**) |

**Deploy concurrency.** `deploy-to-netcup` uses the group `deploy-to-netcup-vm` and
`deploy-to-nonprod` uses `deploy-to-nonprod-vm`, both with `cancel-in-progress: false`. A second push
queues. A cancelled run could stop the SCP step half-way and leave a half-copied Compose file on the
VM ([`deploy.yml`](../../.github/workflows/deploy.yml#L401-L412)).

### Why we chose it

- **CI-18.** A `pull_request` trigger "would arm the deploy jobs on every PR"
  ([`invariant-checks.yml`](../../.github/workflows/invariant-checks.yml#L3-L6)). The path filter
  exists because a docs change has no runtime effect. `.github/**` is not ignored, because a
  workflow change needs a real pipeline run to prove it.
- **CI-19.** One build with two tags costs one manifest write, not a second build. Production and
  nonprod therefore run byte-identical image content from two independent repositories
  ([`deploy.yml`](../../.github/workflows/deploy.yml#L21-L24)). The tag is the first 7 characters of
  `GITHUB_SHA`. No `latest` tag exists: Phase 8's first schema-registration attempt failed on the
  assumption that it did ([`deploy.yml`](../../.github/workflows/deploy.yml#L656-L661)). The build
  is `linux/amd64` only, because the Netcup VM is x86_64. The QEMU cross-build for ARM64 was
  removed when the target moved from Oracle A1 Flex to Netcup (a reversed decision,
  [`deploy.yml`](../../.github/workflows/deploy.yml#L114-L119)).
- **CI-20.** The runner has no route to the self-hosted `postgres` service, because it publishes no
  host port. So the job runs Flyway on the VM, over SSH. The project rejected an ephemeral database
  in CI. Such a database "would only prove migrations apply to an empty database". It is "blind to
  the migration drift this gate exists to catch" ([`deploy.yml`](../../.github/workflows/deploy.yml#L249-L266)). A
  `pg_isready` probe runs first, so a network failure shows as a named error. `DB_HOST` and
  `DB_NAME` moved from secrets to variables, so that the log shows the real database name. GitHub
  masks any log line that contains a secret value, which would hide a wrong-environment mistake.
- **CI-24.** Production registers schemas after `up -d`, in its own job. Registration was already a
  manual post-deploy step since v1.2. Making the registry a precondition would add a new failure
  mode: a registry outage would block a deploy that otherwise works. Nonprod registers inside its
  deploy script, before `up -d app-nonprod`, so "registration completes before the app serves
  traffic" is literally true there ([`deploy.yml`](../../.github/workflows/deploy.yml#L531-L546),
  [`deploy.yml`](../../.github/workflows/deploy.yml#L623-L634)).
- **Health poll as a separate job.** Inside `deploy-to-nonprod` it would hold the concurrency lock
  for up to 300 s. The bound is 30 x 10 s. The healthcheck needs 80 s
  (`start_period` 30 s plus 10 s x 5 retries). Image pull, Flyway, DNS and TLS time come on top
  ([`deploy.yml`](../../.github/workflows/deploy.yml#L678-L705)).

### Trade-offs and limits

- **No CI tests on pull requests.** `run-tests` is part of `deploy.yml`, and `deploy.yml` has no
  `pull_request` trigger. A PR (Dependabot PRs included) gets the invariant checks and the secret
  scans, but not `./gradlew test` or `spotlessCheck`. The full suite first runs after merge to
  `main`. The pre-commit `fastTest` gate is the only earlier test run.
- `deploy-to-netcup` SCP has no `rm:`, because `.env.prod` lives in the same directory. A file
  deleted from the repository stays on the VM.
- A bind-mounted file's content is not part of Compose's config hash. So the job reloads Caddy
  explicitly after `up -d`, and reads back the running config through `127.0.0.1:2019`. `localhost`
  failed 5 of 5 times, because BusyBox `wget` tries `::1` and does not fall back
  ([`deploy.yml`](../../.github/workflows/deploy.yml#L489-L529)).
- Every push to `main` deploys both environments. No approval gate exists (**CI-21**).

### How we test it

The pipeline is proven by live runs, recorded in the plan summaries. For example, run `32233904310`
was green end to end across both deploy paths directly after the repository secret sweep
([09-02-SUMMARY.md](../../.planning/milestones/v1.3-phases/09-nonprod-continuous-deploy-scoped-ci-credentials/09-02-SUMMARY.md)).
The invariant gates in the next section test the parts of `deploy.yml` that can be checked as files.

### Where this is recorded

[09-CONTEXT.md](../../.planning/milestones/v1.3-phases/09-nonprod-continuous-deploy-scoped-ci-credentials/09-CONTEXT.md),
[10-CONTEXT.md](../../.planning/milestones/v1.3-phases/10-ci-deploy-hardening/10-CONTEXT.md),
commit `cc77500` (push triggers moved from `master` to `main` after the branch rename).

## Scoped CI credentials and deploy hardening (milestone v1.3, Phases 9 and 10)

### What it is

Phase 9 added the nonprod deploy path and split every credential by environment. Phase 10 closed
eight hardening items (HARDEN-01 to HARDEN-08) and three folded supply-chain items.

### How it works

- **GitHub Environments.** Every job that reads `secrets.*` declares `environment: production` or
  `environment: staging`. Both environments use the same secret names (`NETCUP_SSH_KEY`,
  `NETCUP_DEPLOY_USER`, `DB_USER`, and so on) with different values. After the sweep, the only
  repository-level secret is `NVD_API_KEY`
  ([09-02-SUMMARY.md](../../.planning/milestones/v1.3-phases/09-nonprod-continuous-deploy-scoped-ci-credentials/09-02-SUMMARY.md)).
- **VM identities.** Production deploys as the non-root user `deploy`. Nonprod deploys as
  `deploy-nonprod`, with its own key pair, confined to `/opt/deploy/kanban-board-nonprod/` by Unix
  file permissions.
- **Host key pinning.** Every `scp-action` and `ssh-action` step passes
  `fingerprint: ${{ secrets.NETCUP_HOST_FINGERPRINT }}`.
- **Action pinning.** `appleboy/scp-action@ff85246...  # v1.0.0` and
  `appleboy/ssh-action@0ff4204...  # v1.2.5`. First-party actions stay on tags
  ([`deploy.yml`](../../.github/workflows/deploy.yml#L31-L38)).
- **`set -e`** at the top of every SSH script.

### Why we chose it

- **CI-21.** GitHub Environments scope which secret values a job can read. They do not limit what a
  shared SSH credential can do on the VM. The recorded reason: "a shared credential still grants
  full shell access to production's directory regardless of which job reads it"
  ([09-CONTEXT.md, D-01](../../.planning/milestones/v1.3-phases/09-nonprod-continuous-deploy-scoped-ci-credentials/09-CONTEXT.md)).
  Hence a second Linux user. The environments have no reviewer or wait timer (D-04). They exist for
  secret scoping, not as a release gate.
- **CI-22.** The two `appleboy/*` actions run with real SSH keys to the VM. That is a higher
  publisher-compromise risk than GitHub's and Docker's own actions. A digest pin costs a two-step
  lookup and edit on every bump. So the project pays that cost only where the risk is highest
  ([10-CONTEXT.md, D-05](../../.planning/milestones/v1.3-phases/10-ci-deploy-hardening/10-CONTEXT.md)).
  Dependabot's `github-actions` entry updates the SHA and the version comment together (D-07).
- **CI-23.** `appleboy/ssh-action` v1.2.5 runs `script:` as plain shell with no `set -e`. A first fix
  added `script_stop: true`. That input does not exist, and GitHub Actions ignores unknown `with:`
  keys without an error. The registration step failed with exit 1, and `up -d app-nonprod` still ran,
  twice ([`deploy.yml`](../../.github/workflows/deploy.yml#L458-L469)).

### Alternatives we rejected

- **Reuse the production `deploy` user for nonprod.** Rejected for the shell-access reason above.
- **SSH forced-command or a restricted shell for `deploy-nonprod`.** Rejected as more complexity
  than this project's risk tolerance needs (D-01, D-02).
- **Digest-pin every action.** Rejected for the recurring maintenance cost (D-05).

### Trade-offs and limits

- `deploy-nonprod` is in the `docker` group. Docker group membership is equivalent to root, so the
  file-permission confinement has a known residual risk. The operator accepted it
  ([09-01-SUMMARY.md](../../.planning/milestones/v1.3-phases/09-nonprod-continuous-deploy-scoped-ci-credentials/09-01-SUMMARY.md)).
- One account-wide Docker Hub token is copied into both environments. Its account-wide scope is an
  accepted residual risk.
- The NVD key bug: the `security-scan.yml` preflight failed from 2026-08-17. Plan 10-03 added a
  non-disclosing probe (byte length and truncated SHA-256). The stored value had length 0, and its
  digest matched SHA-256 of the empty string. The owner re-set the secret, and a run with the probe
  removed passed ([10-03-SUMMARY.md](../../.planning/milestones/v1.3-phases/10-ci-deploy-hardening/10-03-SUMMARY.md)).
  The team diagnosed before it re-set the value, because a blind re-set would have destroyed the
  evidence.

### Where this is recorded

[09-CONTEXT.md](../../.planning/milestones/v1.3-phases/09-nonprod-continuous-deploy-scoped-ci-credentials/09-CONTEXT.md),
[10-CONTEXT.md](../../.planning/milestones/v1.3-phases/10-ci-deploy-hardening/10-CONTEXT.md),
[10-01-SUMMARY.md](../../.planning/milestones/v1.3-phases/10-ci-deploy-hardening/10-01-SUMMARY.md),
[`docs/INFRA_RUNBOOK.md`](../INFRA_RUNBOOK.md) ("Nonprod CI deploy identity").

## Docker Hub image retention

### What it is

Four cleanup jobs keep each application repository at one active tag.

### How it works

- `cleanup-old-images` and `-nonprod` run after a good deploy. They get a JWT from
  `https://hub.docker.com/v2/users/login/`, list tags with `page_size=100` and follow `next` until it
  is `null`, and delete every tag except the current one. A non-204 delete fails the job
  ([`deploy.yml`](../../.github/workflows/deploy.yml#L718-L785)).
- `cleanup-unused-image` and `-nonprod` run on `if: failure()`. They get a registry token, read the
  manifest digest with a `HEAD` request, and delete the manifest by digest. A non-2xx result only
  warns ([`deploy.yml`](../../.github/workflows/deploy.yml#L788-L827)).

### Why we chose it

**CI-25.** "We only want to have a single active prod deployment for that project"
([`deploy.yml`](../../.github/workflows/deploy.yml#L725)). The failure-cleanup job only warns,
because it already runs on a failed workflow, and a red result would hide the original failure.

### Trade-offs and limits

Two live bugs shaped the current code:

1. **Basic auth rejected.** The Hub API rejects Basic auth on delete: 29 of 29 deletes returned
   `unauthorized` in run `31963539949` (2026-08-16). The old list request worked only because the
   repository is public.
2. **Pagination.** With 41 tags, the single-request version saw page 1 only. It deleted 9 tags and
   left 32 (2026-08-17).

The Caddy repository has no cleanup job. Its tag is derived from content, so few tags exist, and the
current tag must stay pullable for a cold VM restart.

## Caddy image pipeline

### What it is

[`docker/caddy/Dockerfile`](../../docker/caddy/Dockerfile) builds Caddy 2.11.4 with the
`caddy-ratelimit` module at commit `5625512f...`. CI builds it and pushes it to
`rudenkovladimir/kanban-board-caddy:2.11.4-rl5625512f`.

### How it works

[`build-and-push-caddy-image`](../../.github/workflows/deploy.yml#L170-L247) runs these steps in order:

1. `verify-caddy-image-tag.py` checks the Dockerfile against the Compose file. The job then
   compares the computed image name with the Compose literal.
2. Build with `load: true`, `push: false`, and a GitHub Actions cache.
3. `caddy version` must show `v2.11.4`, and `caddy list-modules` must show `http.handlers.rate_limit`.
4. `caddy validate` runs against the committed `Caddyfile` with placeholder domains.
5. `docker push`.

### Why we chose it

**CI-26.** The from-source `xcaddy` compile runs on the runner, and the VM only pulls. The recorded
reason is that "the VM is a 2GB box with a documented OOM history"
([`deploy.yml`](../../.github/workflows/deploy.yml#L157-L162)). That premise is stale. The comment
dates from the earlier host. The current Netcup VM has 7.8 GiB of RAM
([`INFRA_RUNBOOK.md`](../INFRA_RUNBOOK.md#L24)). Step 3 proves the artifact, because "a green
`xcaddy build` is not evidence the module was linked in". The build and the push are separate, so a broken image never
reaches the registry.

### Where this is recorded

[260903-dvp-PLAN.md](../../.planning/quick/260903-dvp-caddy-edge-rate-limiting/260903-dvp-PLAN.md)
(quick task that added the edge rate limiter).

## Invariant checks and their self-tests

### What it is

[`invariant-checks.yml`](../../.github/workflows/invariant-checks.yml) runs on push to `main` and on
every pull request (with the same path filter as `deploy.yml`). Each job runs one Python gate that
compares committed files. No gate reaches the network.

### How it works

Each job installs PyYAML, runs the self-test (where one exists), then runs the gate. The self-test
feeds in-memory documents into the gate's pure functions. Each case trips exactly one invariant and
asserts that the gate reports it. The fixtures are literals, not values imported from the gate, so
a wrong edit to the gate's allowlist cannot also rewrite the expectations
([`verify-compose-ports-selftest.py`](../../scripts/verify-compose-ports-selftest.py)).

| Gate | Invariant it guards | Incident that caused it | Self-test | Where it runs |
|------|--------------------|-------------------------|-----------|---------------|
| [`verify-caddy-image-tag.py`](../../scripts/verify-caddy-image-tag.py) | I1: builder and runtime `FROM caddy:` versions agree. I2: exactly one `--with caddy-ratelimit@<40-hex>`. I3: Compose tag ends `:<version>-rl<sha[:8]>`. I4: Compose repository is `rudenkovladimir/kanban-board-caddy` | 2026-09-03 review, finding F4: the gate ran only after merge, so drift was mergeable | None | `invariant-checks.yml` and `deploy.yml` |
| [`verify-compose-ports.py`](../../scripts/verify-compose-ports.py) | Only `caddy` publishes host ports, exactly 80 and 443; no `network_mode: host`; no `include:`/`extends:`; every root `docker-compose*.yml` is accounted for (I1-I7) | 2026-09-05: on the VM, Docker's DNAT sends published ports through `FORWARD`, not `INPUT`, so the host firewall is decorative for Docker ports | Yes | `invariant-checks.yml` |
| [`verify-deploy-scp-coverage.py`](../../scripts/verify-deploy-scp-coverage.py) | Every `./` bind mount in a deploy Compose file is in that deploy job's SCP `source:` list; every source exists; fail closed on an unreadable pipeline (I1-I4) | Quick task 260908-sj9: four of six production bind mounts were never copied; production ran config frozen at 2026-09-07 behind 14 green deploys | Yes | `invariant-checks.yml` |
| [`verify-public-dashboards.py`](../../scripts/verify-public-dashboards.py) | Public dashboards reference datasources by uid only, hold no template variables, use only panel types Grafana 13.2.1 ships (I1-I6) | 2026-09-12: all three public dashboards showed "Datasource was not found"; then Angular `graph`/`singlestat` panels showed "Plugin graph not found" | Yes | `invariant-checks.yml` |
| [`verify-postgres-memory-invariant.py`](../../scripts/verify-postgres-memory-invariant.py) | I1: `shared_buffers <= mem_limit / 4`. I2: `shared_buffers + max_connections * work_mem <= 0.85 * mem_limit` | Phase 11 review CR-02: `shared_buffers` 128MB exceeded a 64m `mem_limit` | None | No workflow (manual run) |
| [`verify-postgres-init-quoting.sh`](../../scripts/verify-postgres-init-quoting.sh) | The Postgres init script provisions roles safely with hostile credential values | Phase 11 review CR-01 (injection in string-built SQL) | Is itself an adversarial harness | Manual only, by design |

Current values for the Postgres check: `mem_limit=256MB`, `shared_buffers=64MB`, `work_mem=4MB`,
`max_connections=25`, worst case 164 MB, which is 64.1% of the cap.

### Why we chose it

**CI-27.** Two comments claimed that Caddy tag drift was "unmergeable" and that a bump PR "will
fail CI". Both were false until this workflow existed, because `deploy.yml` never runs on a PR. The
workflow was "written to make the claim true rather than to soften the claim"
([`invariant-checks.yml`](../../.github/workflows/invariant-checks.yml#L1-L16)). Each gate is a pure
function of the commit, so it may block a PR (**CI-01**). The self-test runs first, because a gate
edited into one that cannot fire looks green against a compliant file.

The word "block" has a limit. `main` has no branch protection and no ruleset: `gh api
repos/{owner}/{repo}/branches/main/protection` returns 404 "Branch not protected", and the
rulesets list is empty. A failed gate therefore marks the PR red, but GitHub still lets the owner
merge it. Confirmed by running on 2026-09-23.

### Trade-offs and limits

Each script lists its own known holes in its docstring. The shared ones:

- The gates read committed files, not the running VM. A hand edit on the VM is invisible.
- `verify-caddy-image-tag.py` is not a content hash. A second `--with <other plugin>` line changes the
  image without changing the tag, and nothing downstream catches it.
- An allowlist in a gate (for example `ALLOWED_PUBLISHERS`) can change in the same PR that adds a
  port. The gate makes the change reviewed, not impossible.
- `verify-deploy-scp-coverage.py` proves a path is transferred, not that a running container
  applies its new content.

### How we test it

Run any gate locally from the repository root, for example
`python3 scripts/verify-compose-ports-selftest.py && python3 scripts/verify-compose-ports.py`. All
eight Python scripts exited 0 on the current tree when this chapter was written.

### Where this is recorded

[260905-qxi-PLAN.md](../../.planning/quick/260905-qxi-add-ci-invariant-that-only-caddy-may-pub/260905-qxi-PLAN.md),
[260908-sj9-SUMMARY.md](../../.planning/quick/260908-sj9-fix-deploy-yml-add-docker-grafana-provis/260908-sj9-SUMMARY.md),
[`.planning/phases/11-migrate-database-from-neon-to-self-hosted-postgres/11-REVIEW.md`](../../.planning/phases/11-migrate-database-from-neon-to-self-hosted-postgres/11-REVIEW.md),
[`docs/INFRA_RUNBOOK.md`](../INFRA_RUNBOOK.md) ("Public Grafana dashboards rendered no data").

## Scheduled and manual workflows

### What it is

| Workflow | Trigger | Purpose |
|----------|---------|---------|
| [`security-scan.yml`](../../.github/workflows/security-scan.yml) | Mondays 06:00 UTC, manual | OWASP `dependencyCheckAnalyze` against `runtimeClasspath` |
| [`uptime-check.yml`](../../.github/workflows/uptime-check.yml) | Every 15 minutes, manual | `curl` both public health URLs, require HTTP 200 and `"status":"UP"` |
| [`verify-rate-limit.yml`](../../.github/workflows/verify-rate-limit.yml) | Manual only | Prove production rate-limits `/api/signin` and nonprod does not |

### How it works

- **security-scan.yml.** A preflight fails in seconds if `NVD_API_KEY` is empty
  ([`security-scan.yml`](../../.github/workflows/security-scan.yml#L51-L62)). The secret reaches the
  shell through `env:`, never through `${{ }}` text in the script, because textual interpolation is
  a script-injection surface. The NVD database cache is keyed on the plugin version `12.2.2`. The
  plugin sets `failBuildOnCVSS = 11`, so findings never fail the job; a failed job means the scan
  could not run ([`build.gradle`](../../build.gradle#L589-L629)). The HTML and JSON reports upload as
  an artifact.
- **uptime-check.yml.** 3 attempts per URL, 10 s apart, 20 s `curl` timeout. One environment down
  never hides the other. `permissions: {}`, because the job does not check out the repository.
- **verify-rate-limit.yml.** Runs `scripts/loadtest/run-rate-limit-verification.sh`, which calls
  `pnpm dlx artillery@2.0.24`, with invalid credentials so that a request that passes the limiter
  gets 401 and writes nothing.

### Why we chose it

- **CI-28.** The scan is network-bound, holds an H2 database of vulnerabilities, and is the heaviest
  task in the build. It stays off `test`, `fastTest`, `check` and the hook. Weekly matches
  dependency-check's own recommended cadence. It is report-only until a real baseline exists and
  its false positives are suppressed with evidence. Then the team picks a `failBuildOnCVSS` level
  from that number, the same measure-then-gate order that Error Prone and JaCoCo followed.
  Version 12.2.2, not 13.0.0, is a maturity choice: 13.0.0 was ten days old
  ([`build.gradle`](../../build.gradle#L26-L31)).
- **CI-29 (uptime).** A frozen Netcup console screenshot caused a false outage alarm. A standing
  probe gives dated evidence. `*/5` gives three times the runs for little real gain, because
  GitHub's scheduled runs can start late ([`uptime-check.yml`](../../.github/workflows/uptime-check.yml#L21-L28)).
- **CI-29 (rate limit).** The limiter keys on the TCP peer address. A run spends 20 signin attempts
  from the runner's address and rate-limits it for 5 minutes. From a laptop that would lock the
  developer out. Each run also costs about 20 bcrypt hashes on the VPS, so it does not run on every
  deploy ([`verify-rate-limit.yml`](../../.github/workflows/verify-rate-limit.yml#L1-L21)). The
  workflow comment calls the VPS "a 2 GB VPS". That premise is stale: the current VM has 7.8 GiB
  of RAM ([`INFRA_RUNBOOK.md`](../INFRA_RUNBOOK.md#L24)).

### Alternatives we rejected

- **Scan inside `run-tests`.** A new Tomcat or Spring CVE would block an unrelated deploy.
- **SARIF output.** SARIF's only value is upload to GitHub code scanning, which needs an entitlement
  the project does not assume.
- **Pre-written suppressions (for example, for Guava's `-android` classifier).** Suppressions written
  before a real run create permanent blind spots.
- **Spoof the source address with `X-Forwarded-For` to test the limiter.** The limiter uses
  `{remote_host}` so that a header cannot choose the bucket. Trusting the header would delete the
  control under test.
- **Chain rate-limit verification to every deploy with `workflow_run`.** Rejected for the cost above.

### Trade-offs and limits

- GitHub disables scheduled workflows in a public repository after 60 days of inactivity. The uptime
  probe then stops without notice.
- The uptime probe pages nobody. A red run sits in the Actions tab.
- `management.endpoint.health.show-details=never` means the probe can name the endpoint but never
  the failed subsystem.
- The scan covers `runtimeClasspath` only. A future second production configuration would not be
  scanned by default.

### Where this is recorded

[260813-q1i-PLAN.md](../../.planning/quick/260813-q1i-add-a-dependency-vulnerability-scan-owas/260813-q1i-PLAN.md),
[260902-vjo-SUMMARY.md](../../.planning/quick/260902-vjo-document-netcup-console-staleness-triage/260902-vjo-SUMMARY.md),
[260903-dvp-PLAN.md](../../.planning/quick/260903-dvp-caddy-edge-rate-limiting/260903-dvp-PLAN.md).

## Dependabot

### What it is

[`.github/dependabot.yml`](../../.github/dependabot.yml) configures weekly version updates for three
ecosystems. Security alerts are a separate repository setting, confirmed on.

### How it works

| Ecosystem | Directory | Grouping | Open PR limit |
|-----------|-----------|----------|---------------|
| `gradle` | `/` | One group, `patterns: ["*"]` | 2 |
| `github-actions` | `/` | None | 5 |
| `docker` | `/docker/caddy` | None | 2 |

### Why we chose it

**CI-30.**

- dependency-check reports; Dependabot remediates. The two are "complements, not alternatives"
  ([260813-q1i-PLAN.md](../../.planning/quick/260813-q1i-add-a-dependency-vulnerability-scan-owas/260813-q1i-PLAN.md)).
  The stale Spring Boot BOM caused most of the measured baseline, and Dependabot attacks that cause.
- Every Gradle bump needs a verification-metadata regeneration (**CI-04**). One group means one
  regeneration and one CI run per batch ([`dependabot.yml`](../../.github/dependabot.yml#L25-L37)).
- The `github-actions` entry keeps the digest-pinned `appleboy/*` actions maintained (HARDEN-01).
- The `docker` entry tracks only the two literal `caddy:2.11.4*` tags. It cannot track the plugin
  SHA, because the plugin has only one release tag and master is ahead of it.
- The root `Dockerfile` base images are excluded on purpose, dated 2026-09-03: a bump redeploys
  production and nonprod ([`dependabot.yml`](../../.github/dependabot.yml#L74-L78)).

### Trade-offs and limits

- A Caddy bump PR fails `invariant-checks.yml` until a human updates the Compose image literal.
- A Gradle bump PR does not fail on the PR, because no workflow runs Gradle on a pull request. A
  missing verification-metadata change shows only after merge, when `run-tests` fails on `main`.
- The app base images and the plugin SHA need manual bumps.

## Docker image build

### What it is

The root [`Dockerfile`](../../Dockerfile) has two stages:

```dockerfile
# ---- Build Stage ----
FROM gradle:8.7-jdk21 AS build
WORKDIR /app
COPY . .
RUN chmod +x gradlew
RUN ./gradlew bootJar

# ---- Runtime Stage ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### How it works

- The build stage runs the wrapper, so the build uses Gradle 8.11.1 from the wrapper, not the
  image's own Gradle 8.7. The image supplies the JDK 21. `bootJar` compiles with Error Prone but
  does not run tests; CI's `run-tests` job runs them before this build.
- [`.dockerignore`](../../.dockerignore) is a denylist. It excludes `.git`, `.gradle`, `.claude`,
  `.planning`, `build`, `.githooks`, `.github` and docs. Because `.git` is absent, the hooks-path
  block in `build.gradle` skips itself inside the image.
- The runtime stage holds only a JRE and the jar.
- `docker-compose.prod.yml` pulls `rudenkovladimir/kanban-board-backend:${IMAGE_TAG}`. The deploy job
  exports `IMAGE_TAG`, and a shell variable wins over `--env-file`. The repositories are public, so
  the VM pulls without a login.

### Why we chose it

**CI-31.** The runtime base was `openjdk:21-jdk-slim`. That tag stopped resolving on Docker Hub,
which broke both local `docker compose up` and the deploy pipeline. Commit `79ba149` moved to
`eclipse-temurin:21-jre-jammy`, "the official actively-maintained Adoptium image". The commit
also says a "JRE (not JDK) is sufficient since the runtime stage only runs a prebuilt jar". This
is a reversed decision. The
`.dockerignore` is a denylist so that a new directory the build needs is included by default. It
excludes `.claude` and `.planning` because a live Gradle lock file there once broke `docker build`.

### Alternatives we rejected

- **An allowlist `.dockerignore`.** A new build input would be silently missing
  (260811-nh1-PLAN.md, cited in [`.dockerignore`](../../.dockerignore)).
- **A multi-platform amd64+arm64 image.** Nobody deploys to ARM64
  ([`deploy.yml`](../../.github/workflows/deploy.yml#L143-L146)).

### Trade-offs and limits

- Neither base image carries a digest pin, and Dependabot does not track them. Other images in the
  project do have pins: gitleaks and TruffleHog (tag and digest), the Caddy plugin (commit SHA), and
  the exact tags in `docker-compose.prod.yml`.
- The build stage downloads the Gradle distribution and every dependency on each CI build, because
  no layer caches `~/.gradle`. Reason not recorded.
- The JVM runs with default flags. No `-Xmx` or container-memory flag is set in the Dockerfile.

## Known gaps and open items

1. **No `./gradlew test` on pull requests.** Only push to `main` runs the suite (see the
   `deploy.yml` limits above). [`dependabot.yml`](../../.github/dependabot.yml#L38-L42) says "Every
   Dependabot PR still runs deploy.yml's run-tests job". The code contradicts this: `deploy.yml` has
   no `pull_request` trigger, and Dependabot pushes to its own branches, not `main`. The same applies
   to the claim that a Gradle bump PR fails on verification metadata: nothing runs Gradle on that PR.
   The `on:` blocks of all six workflows show it: only `deploy.yml` runs `./gradlew test`
   ([`deploy.yml`](../../.github/workflows/deploy.yml#L92)), and it runs only on a push to `main`.
   Confirmed by running on 2026-09-23.
2. **`verify-postgres-memory-invariant.py` runs in no workflow.** [`.planning/PROJECT.md`](../../.planning/PROJECT.md)
   says the invariant is "now enforced by a committed script". The script is committed and correct,
   but only a manual run enforces it. It has no self-test. No workflow and no hook calls the
   script. A manual run on 2026-09-23 passed (exit 0, worst case 164 MB, 64.1% of the cap).
   So the gap is an unguarded invariant, not a current violation. Confirmed by running on 2026-09-23.
3. **`verify-caddy-image-tag.py` has no self-test**, unlike the three newer gates.
4. **OWASP dependency-check is still report-only** (`failBuildOnCVSS = 11`). The ratchet waits for a
   triaged baseline.
5. **App base images are not digest-pinned or Dependabot-tracked** (dated, deliberate exclusion).
6. **SpotBugs with `find-sec-bugs`** is noted as a possible complement to Error Prone, not scoped.
7. **One-directional SCP.** A file deleted from the repository stays on the VM.
8. **`ulid-creator` and `commons-collections4`** are `implementation` dependencies with no import in
   `src/main`. No source records whether to keep them.

## Questions to check your knowledge

1. Why is OWASP dependency-check report-only while gitleaks is a hard gate?

   <details><summary>Answer</summary>

   gitleaks runs a pinned version against a committed config, so its verdict is a pure function of
   the commit. The verdict of dependency-check changes when NVD publishes a new advisory, with no code
   change. A blocking gate of that kind would stop unrelated deploys (CI-01, CI-28).
   </details>

2. What does `gradle/verification-metadata.xml` protect against that an exact version pin does not?

   <details><summary>Answer</summary>

   An artifact republished under the same group, name and version with different content. Gradle
   compares the SHA-256 at resolution time and refuses a mismatch (CI-04).
   </details>

3. Why is the JaCoCo ratchet wired with `finalizedBy` on `test` instead of through `check`?

   <details><summary>Answer</summary>

   CI runs `test` and `spotlessCheck` and never `check`. The JaCoCo plugin does not attach
   verification to `check` by itself anyway. `finalizedBy` runs after the `.exec` file exists and
   still fails the build (CI-10).
   </details>

4. The commons-lang3 CVE: why could a JUnit test not detect it?

   <details><summary>Answer</summary>

   Tests run on `testRuntimeClasspath`, which already resolved 3.18.0. The production jar uses
   `runtimeClasspath`, which resolved 3.17.0. Only a check that resolves `runtimeClasspath`
   itself, `verifyRuntimeDependencyFloor`, sees the production value (CI-06).
   </details>

5. Why does the pre-commit hook run gitleaks before `spotlessCheck` and `fastTest`?

   <details><summary>Answer</summary>

   The other two steps take about four minutes. A staged credential must be refused in seconds, and
   the order costs nothing (CI-13).
   </details>

6. What happens in the hook if Docker is not running, and why?

   <details><summary>Answer</summary>

   gitleaks exits with a code other than 0 or 2, and the hook refuses the commit with a "did not run"
   message. A gate that silently skips is believed, which is worse than no gate (CI-13).
   </details>

7. Why is TruffleHog CI-only and diff-scoped, and why does its report use a field allowlist?

   <details><summary>Answer</summary>

   Verification sends candidate credentials to provider APIs; from a developer machine that is an
   exposure. Diff scope keeps those calls in proportion to the change. A verified finding is a live
   credential, and TruffleHog has no redact flag, so only named triage fields are kept (CI-17).
   </details>

8. Why does Flyway verification run on the VM over SSH instead of against a database in CI?

   <details><summary>Answer</summary>

   The runner has no route to the self-hosted `postgres` service. An empty CI database would only
   prove that migrations apply to an empty schema, not to the real, already-migrated one (CI-20).
   </details>

9. GitHub Environments already scope secrets. Why does nonprod need its own Linux user?

   <details><summary>Answer</summary>

   Environments control which secret values a job reads. A shared SSH key still gives full shell
   access to production's directory on the same VM. A second user confined by file permissions
   closes that (CI-21). Docker group membership remains an accepted residual risk.
   </details>

10. Why are only the `appleboy/*` actions digest-pinned?

    <details><summary>Answer</summary>

    They run with real SSH keys to the VM, a higher compromise risk. A digest pin costs a lookup and
    an edit on every bump, so the project pays it only there. The workflow records the risk
    acceptance for first-party actions (CI-22).
    </details>

11. What went wrong with `script_stop: true`, and what is the fix?

    <details><summary>Answer</summary>

    `appleboy/ssh-action` v1.2.5 has no such input, and GitHub Actions ignores unknown `with:` keys.
    A failed schema registration did not stop `up -d app-nonprod`. The fix is `set -e` at the top of
    every script (CI-23).
    </details>

12. Why does production register schemas after deploy, but nonprod before its app starts?

    <details><summary>Answer</summary>

    Production kept its existing order so that a registry outage cannot block a deploy. Nonprod
    registers inside the deploy script so that registration really completes before the app process
    exists (CI-24).
    </details>

13. Why does `invariant-checks.yml` exist when `deploy.yml` already ran the Caddy tag check?

    <details><summary>Answer</summary>

    `deploy.yml` runs only on push to `main`, so the check fired after merge and could only leave
    `main` undeployed. `invariant-checks.yml` runs on PRs, so drift now fails on the PR (CI-27). `main` has no branch
    protection, so the red check does not stop a merge.
    </details>

14. Why does each invariant gate run a self-test first?

    <details><summary>Answer</summary>

    An edit can make a gate unable to fire. Against a compliant file it stays green forever. The
    self-test feeds literal bad inputs and proves each invariant still reports (CI-27).
    </details>

15. Name two reversed decisions in this layer.

    <details><summary>Answer</summary>

    The pre-commit hook changed from `spotlessApply` plus re-stage to check-only (`199300f`). The
    runtime image changed from `openjdk:21-jdk-slim` to `eclipse-temurin:21-jre-jammy` (`79ba149`).
    Also: the image build dropped QEMU ARM64 cross-compilation when the target moved from Oracle A1
    to Netcup.
    </details>
