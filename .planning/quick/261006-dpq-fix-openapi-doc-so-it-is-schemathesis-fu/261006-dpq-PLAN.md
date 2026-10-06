---
phase: quick-261006-dpq
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - src/test/java/com/vrudenko/kanban_board/config/OpenApiParameterCompletenessTest.java
  - src/main/java/com/vrudenko/kanban_board/config/CustomArgumentResolverConfig.java
  - src/main/java/com/vrudenko/kanban_board/config/PathTemplateParameterOpenApiCustomizer.java
  - docs/ARCHITECTURE.md
  - docs/learning/05-api-layer.md
autonomous: true
requirements: [261006-dpq]

estimate:
  tokens: 220000
  raw_tokens: 220000
  tasks: 3
  confidence: low

must_haves:
  truths:
    - "Every operation in the generated /docs document declares an `in: path`, `required: true` parameter for every `{var}` in its path key (24 of 24; 13 of 24 at planning time)"
    - "No operation publishes a `userId` query parameter (0 of 24; 22 of 24 at planning time), and identity still comes only from the session: CurrentUserIdResolver is unchanged"
    - "OpenApiParameterCompletenessTest walks the live document rather than a fixed list. It FAILED on the unfixed code with exactly 11 path-template and 22 userId violations and PASSES on the fix; both directions are recorded in the SUMMARY"
    - "No controller, CurrentUserId or CurrentUserIdResolver source changes, and a future nested controller needs no per-endpoint annotation for either fix (D-08)"
    - "Schemathesis 4.29.3 against the live /api/docs reports zero 'Path parameter ... is not defined' errors and no `userId=` in any reproduction. If the stack cannot run, a 'could not verify live' row with its reason is recorded instead"
    - "`./gradlew spotlessCheck`, `python3 scripts/verify-comments.py check` and the full `./gradlew test` pass on the final tree"
  artifacts:
    - path: src/test/java/com/vrudenko/kanban_board/config/OpenApiParameterCompletenessTest.java
      provides: "document-wide sweeps for both defects plus one literal spot-check that does not depend on the test's own regex"
    - path: src/main/java/com/vrudenko/kanban_board/config/PathTemplateParameterOpenApiCustomizer.java
      provides: "GlobalOpenApiCustomizer that declares any undeclared path-template variable as a required string path parameter"
    - path: src/main/java/com/vrudenko/kanban_board/config/CustomArgumentResolverConfig.java
      provides: "static registration of CurrentUserId on springdoc's annotations-to-ignore list, beside the resolver registration"
  key_links:
    - from: CustomArgumentResolverConfig static initializer
      to: springdoc AbstractRequestService.isParamToIgnore
      via: "SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentUserId.class); the direct-type branch of SpringDocAnnotationsUtils.isAnnotationToIgnore skips the argument"
    - from: PathTemplateParameterOpenApiCustomizer
      to: the cached OpenAPI document
      via: "@Component implementing GlobalOpenApiCustomizer, which springdoc runs over the finished document before caching it"
    - from: OpenApiParameterCompletenessTest
      to: springdoc.api-docs.path
      via: "MockMvc GET plus Jackson readTree, the same harness ProblemDetailOpenApiCustomizerTest uses"
---

# Quick task 261006-dpq: make the generated OpenAPI document fuzzable by Schemathesis

<objective>
Make the generated OpenAPI document state every path parameter that its routes require, and stop
it publishing the session-derived user id as a client parameter. Lock both properties with a test
that walks the whole document.

Purpose: spike 003 found that Schemathesis could not generate a single request for 11 of the 24
operations, which are the deepest routes (tasks, subtasks, reorder). It also found that 22
operations advertise a required `userId` query parameter that the server never reads. Recounted
against `.planning/spikes/003-web-fuzzing-schemathesis/out/openapi.json` during planning: 11
operations miss 19 path-parameter declarations in total, and all 22 `@CurrentUserId` sites leak.

Output: one new test class, one new springdoc customizer, a static registration in
`CustomArgumentResolverConfig`, and two doc updates. No controller changes.

Data flow (3 sentences, per the project directive): on the first GET of `springdoc.api-docs.path`,
springdoc turns each handler-method argument into a Parameter. It skips any argument whose
annotation is on its ignore list, which is where `@CurrentUserId` will be registered. It then runs
every `GlobalOpenApiCustomizer` bean over the finished document and caches the result. The new
customizer reads each path key's `{var}` names and adds a required string path parameter for any
that the operation does not already declare, and the test fetches that cached document through
MockMvc to sweep every operation.
</objective>

<execution_context>
@~/.claude/gsd-core/workflows/execute-plan.md
@~/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.claude/CLAUDE.md
@docs/CODE_STYLE.md
@docs/SESSION_LESSONS.md
@.planning/spikes/003-web-fuzzing-schemathesis/README.md
@.planning/spikes/CONVENTIONS.md
@src/main/java/com/vrudenko/kanban_board/config/ProblemDetailOpenApiCustomizer.java
@src/test/java/com/vrudenko/kanban_board/config/ProblemDetailOpenApiCustomizerTest.java
@src/test/java/com/vrudenko/kanban_board/config/OpenApiDocsTest.java
@src/main/java/com/vrudenko/kanban_board/config/CustomArgumentResolverConfig.java
@src/main/java/com/vrudenko/kanban_board/security/CurrentUserId.java
@src/main/java/com/vrudenko/kanban_board/security/CurrentUserIdResolver.java
</context>

## Approach and trade-offs (project directive: 2 alternatives, matrix, non-obvious trade-offs)

The evidence is springdoc-openapi 2.8.8 and spring-core 6.2.19 source, read from the Gradle cache
during planning:

- Path parameters come only from handler arguments carrying `@PathVariable`
  (`ParameterInfo:108`). Nothing reads the class-level template.
- Line 290 of `AbstractRequestService` resolves `@Parameter` through merged meta-annotations, and
  `parameterDoc.hidden()` drops the argument at line 310.
- `SpringDocAnnotationsUtils.isAnnotationToIgnore` (lines 278-288) matches the annotation type
  directly, and also checks one level of meta-annotation. `@Hidden` is on the default list
  (lines 95-97).
- springdoc hides Spring Security's own identity argument with
  `addAnnotationsToIgnore(AuthenticationPrincipal.class)` (`SpringDocSecurityConfiguration:94-98`).

**Cause 2: leaked `userId` query parameter.** The plan picks D-A.

| Approach | Pros/Cons | Why Picked |
|---|---|---|
| **D-A: `SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentUserId.class)` in a static initializer of `CustomArgumentResolverConfig`** | + This is springdoc's own idiom for exactly this case (`@AuthenticationPrincipal`). It matches on annotation type, the least implementation-dependent branch. The `security` package gains no documentation import. It sits beside the resolver registration, which already says "this annotation is filled from the session". − It is process-wide static state, applied away from the annotation itself. | **Picked.** It is the framework's documented answer to the identical problem, and it covers every `@CurrentUserId` use, present and future. |
| Alt 1: meta-annotate `@CurrentUserId` with `@Parameter(hidden = true)` (or `@Hidden`) | + The rule lives on the annotation, and `dto/annotation/BoardId` sets an in-project precedent for swagger meta-annotations. − `@CurrentUserId.required()` shares its name with `@Parameter.required()`. Spring 6.2.19 treats that as a deprecated convention-based override and logs a WARN (`AnnotationTypeMapping:320-327`), and Spring 7 removes the mechanism. `@Hidden` avoids that collision but depends on springdoc's one-level meta-annotation check. Either variant couples a security annotation to swagger. | Rejected. The `@Parameter` variant brings a deprecation warning, and the `@Hidden` variant relies on an undocumented lookup depth. |
| Alt 2: an OpenApiCustomizer that removes any parameter named `userId` | + Simple. − It is keyed on a parameter name, not on the binding. A future `@CurrentUserId String ownerId` would leak again, and a legitimate `userId` parameter would be silently deleted. | Rejected: it targets the symptom, not the binding. |

**Cause 1: missing path parameters.** The plan picks D-B.

| Approach | Pros/Cons | Why Picked |
|---|---|---|
| **D-B: new `PathTemplateParameterOpenApiCustomizer` (`GlobalOpenApiCustomizer`) adding every undeclared `{var}` as `in: path`, `required: true`, `string` with `minLength: 1`** | + No handler can forget it (D-08, the same design as `ProblemDetailOpenApiCustomizer`). There are zero controller changes, and new nested controllers are covered automatically. − Added parameters carry no description beyond the schema, and the customizer cannot know a variable's type. Every id in this API is a String, so that limit does not bite today. | **Picked.** It fixes a document defect at the document layer and is consistent with D-08. |
| Alt 1: bind every missing ancestor as an unused `@PathVariable` on the 11 handlers | + springdoc emits the parameters natively. − That is 19 unused parameters across 3 controllers. It is exactly the per-handler memory that let 11 of 24 operations drift, and a bound `boardId` implies the service checks it, which it does not. | Rejected: noisy, misleading, and it reopens on the next handler. |
| Alt 2: class-level `@Parameters({@Parameter(name = "boardId", in = PATH)})` on each nested controller (springdoc reads these, `AbstractRequestService:698`) | + Fewer sites (3 classes). − It is still a per-controller memory burden, which D-08 rejects by default, and it has to be kept in step with each class's `@RequestMapping` by hand. | Rejected under D-08. |

**Non-obvious trade-offs.**

- **Performance and memory:** the customizer runs once per document build, which springdoc caches.
  The work is about 18 paths × at most 3 operations × at most 4 variables, roughly 100 string
  comparisons, with a precompiled static `Pattern`. It adds at most 19 small `Parameter` objects
  to the cached document and has no per-request cost.
- **Fresh instances:** each inserted `PathParameter` and `StringSchema` is a new instance. Shared
  instances would make the document a graph of shared mutable nodes; the
  `ProblemDetailOpenApiCustomizer.problemDetailResponse` Javadoc explains that hazard.
- **Process-wide ignore list:** D-A writes to springdoc's static, synchronized ignore list once
  per classloader. Re-adding is idempotent in effect (`anyMatch`). The registration covers every
  springdoc group, which is intended because there is one.
- **Security:** hiding `userId` removes a misleading signal. A published, required `userId` invites
  clients, fuzzers and auditors to treat identity as caller-supplied, which looks like an IDOR
  surface. The real property does not change: `CurrentUserIdResolver` reads only
  `SecurityContextHolder`, which was verified during planning.
- **Known hole, out of scope:** publishing `boardId`/`columnId`/`taskId` as required does not
  make them validated. For example, `ColumnController.updateById` binds only `columnId`, and
  ownership is checked along the leaf's chain to the user. A mismatched ancestor therefore still
  reaches the leaf, with no authorization impact. Record this as a `Known holes:` note in the
  customizer Javadoc.

**Contract change for existing clients.**

- **On the wire:** no change. The server never read `?userId`, Spring MVC ignores extra query
  parameters, and URLs are unchanged.
- **Generated clients:** the sibling `kanban-board-frontend` generates `generated-types.ts` with
  openapi-typescript 7.13.0 from a pinned snapshot (`docs/api/kanban-board-openapi.json`, dated
  2026-09-09). It is unaffected until it re-snapshots. On regeneration, `query.userId` disappears
  from 22 operations, so 19 call sites in 19 files that pass `query: { userId: record.id }` become
  type errors. Fixing each is a deletion. The path types gain required ancestor ids, which those
  call sites already pass (`delete-column-action.ts:52-56`).
- **Follow-up:** that repo owns the follow-up. Record it in the SUMMARY; do not edit it here.

**Hidden-risk scan (planning time).**

- No test under `src/test/java` asserts on OpenAPI `parameters` or sends a `userId` query
  parameter. `ProblemDetailOpenApiCustomizerTest` reads only `responses`,
  `ComposedConstraintPropertyCustomizerTest` reads only `components.schemas`, and `OpenApiDocsTest`
  reads only top-level shape.
- All 22 `@CurrentUserId` parameters are named `userId`, so the test's name-keyed check is an exact
  proxy today.

<tasks>

<task type="tracer" tdd="true">
  <name>Task 1: Completeness test RED on unfixed code, then both document fixes GREEN, one commit</name>
  <files>src/test/java/com/vrudenko/kanban_board/config/OpenApiParameterCompletenessTest.java, src/main/java/com/vrudenko/kanban_board/config/CustomArgumentResolverConfig.java, src/main/java/com/vrudenko/kanban_board/config/PathTemplateParameterOpenApiCustomizer.java</files>
  <read_first>src/test/java/com/vrudenko/kanban_board/config/ProblemDetailOpenApiCustomizerTest.java (harness, failure-collection style), src/test/java/com/vrudenko/kanban_board/config/OpenApiDocsTest.java (base-class choice), src/main/java/com/vrudenko/kanban_board/config/ProblemDetailOpenApiCustomizer.java (customizer contract, Javadoc density, conditional insert), src/main/java/com/vrudenko/kanban_board/config/CustomArgumentResolverConfig.java, docs/CODE_STYLE.md rules 3, 4, 5, 9, 13, 14</read_first>
  <precondition>Docker is running (Testcontainers), and `rg -n 'path\("parameters"\)|"parameters"' src/test/java` returns no OpenAPI parameter assertion</precondition>
  <behavior>
    - Sweep A: every operation (each HTTP-method field under each path key) declares, for each `{var}` in its path key, a parameter with that name, `in: path` and `required: true`, at operation or path-item level. One failure line per violating operation, listing its missing names. Unfixed: exactly 11 operations.
    - Spot-check, a literal oracle independent of the test's own regex: `PUT /boards/{boardId}/columns/{columnId}/tasks/{taskId}/subtasks/{subtaskId}` declares exactly the path names {boardId, columnId, taskId, subtaskId}. Unfixed: only {subtaskId}.
    - Sweep B: no operation declares a parameter named `userId` with `in: query`. Unfixed: exactly 22 operations.
    - Each sweep also asserts it walked at least 20 operations (24 at planning time), so an empty `paths` object cannot pass.
  </behavior>
  <action>
    Step 1, RED. Create `OpenApiParameterCompletenessTest` in the `config` test package.
    - Class setup: `@SpringBootTest @AutoConfigureMockMvc`, extending `AbstractPostgresContainerTest`. It needs no fixtures, which is the same choice and reasoning as `OpenApiDocsTest`, and it shares that class's cached context.
    - Fetch the document exactly as `ProblemDetailOpenApiCustomizerTest` does: `@Value("${springdoc.api-docs.path}")`, `mockMvc.perform(get(apiDocsPath))`, Jackson `ObjectMapper.readTree`. Never autowire the `OpenAPI` bean (spikes/CONVENTIONS.md).
    - Structure: two `@Nested` classes, `PathTemplateParameters` (Sweep A plus the spot-check) and `SessionDerivedUserId` (Sweep B), implementing the behavior block.
    - Style: names follow `should<Outcome>_when<Condition>`, each method has AAA comments, and assertions use qualified `Assertions` (rules 3 and 5). Use an explicit `JsonNode` type wherever the right-hand side is a method call (rule 9).
    - Assertions: collect every violation first, then assert empty with an `.as(...)` description that carries the violation count. One run then reports all violations, and the count can be read from the report.
    - Class Javadoc: one summary line, then `Why this is the way it is:`. Cite spike 003 (Schemathesis generated nothing for 11 nested operations) and explain why the test sweeps the document instead of listing operations.

    Step 2. Run the scoped command from verify (it must fail). Gradle prints no AssertJ message because build.gradle sets no `testLogging`. Read the messages from `build/test-results/test/TEST-*OpenApiParameterCompletenessTest*.xml` and record them in the SUMMARY: expect 11 for Sweep A, 22 for Sweep B, and {subtaskId} alone in the spot-check. If either count differs, STOP and reconcile against the capture before touching main code. Never edit the expectations to fit.

    Step 3, cause 2 (per D-A). Add a static initializer to `CustomArgumentResolverConfig` calling `SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentUserId.class)`, with a short comment: the id comes from the session, so it is never a client parameter. Do not touch `CurrentUserId`, `CurrentUserIdResolver` or any controller.

    Step 4. Rerun to prove the causes are independent: Sweep B passes, and Sweep A plus the spot-check still fail with exactly 11. Record this run.

    Step 5, cause 1 (per D-B and D-08). Create `PathTemplateParameterOpenApiCustomizer` as a `@Component` implementing `GlobalOpenApiCustomizer`, the same contract as `ProblemDetailOpenApiCustomizer`.
    - In `customise(OpenAPI)`, return early when `paths` is null.
    - For each path key, extract the `{name}` variables in order with a static precompiled `Pattern`. Gather the names already declared with `in` = path, at path-item level and at operation level.
    - For each missing name, add a fresh `io.swagger.v3.oas.models.parameters.PathParameter` (in=path, required=true) with a fresh `StringSchema` of `minLength` 1. That mirrors what springdoc publishes for the `@NotBlank`-bound ids.
    - Never modify or remove an existing parameter.
    - Javadoc: a summary line; `Decisions:` covering why a global customizer rather than `@PathVariable` bindings or class-level `@Parameters` (D-08, the 11-of-24 drift); `Known holes:` stating that the added ancestor ids are routing-only, as described in the trade-offs section above; and the guarding test, named with `{@code}`.

    Step 6, GREEN. Run verify. Run `./gradlew spotlessApply` before `spotlessCheck` if formatting fails. Confirm `git status --short` lists only the three task files plus untracked artifacts that existed before this task.

    Step 7. Commit the three files as `fix(quick-261006-dpq): declare every path-template variable and hide @CurrentUserId in the OpenAPI document`. The hook runs gitleaks, verify-comments, spotlessCheck and fastTest, about 4 minutes, so use a long timeout. Never use `--no-verify`.
  </action>
  <verify>
    <automated>./gradlew test --tests '*OpenApiParameterCompletenessTest*' --tests '*ProblemDetailOpenApiCustomizerTest*' --tests '*OpenApiDocsTest*' --tests '*ComposedConstraintPropertyCustomizerTest*' -x jacocoTestCoverageVerification && ./gradlew spotlessCheck && python3 scripts/verify-comments.py check</automated>
  </verify>
  <done>
    - The RED run failed with 11 Sweep A operations, 22 Sweep B operations, and a {subtaskId}-only spot-check.
    - The run after D-A alone left Sweep A at 11 and Sweep B green.
    - The final run is green across all four OpenAPI-reading test classes.
    - `git show --stat HEAD` lists exactly the three task files, and none under `controller/` or `security/`.
  </done>
</task>

<task type="auto">
  <name>Task 2: Live consumer check — pinned Schemathesis against the running app's /api/docs</name>
  <files>(none tracked; outputs go to the gitignored .planning/spikes/003-web-fuzzing-schemathesis/out/)</files>
  <read_first>.planning/spikes/003-web-fuzzing-schemathesis/README.md ("How to Run"), .claude/CLAUDE.md ("Local Development Server")</read_first>
  <precondition>Task 1 is committed. `uvx --offline schemathesis@4.29.3 --version` prints 4.29.3, which was confirmed cached during planning.</precondition>
  <action>
    Step 1. Start the local stack with the spike README's "How to Run" recipe: compose postgres and redpanda, then `bootRun` with the env from CLAUDE.md. Use `SERVER_PORT=8089` when 8080 is taken; check with `ss -ltn`. Wait for "Started KanbanBoardApplication".

    Step 2. Sign up and sign in using the README's exact commands, immediately before the run, because the cookie's Max-Age is 600.

    Step 3. Save the live document to `out/openapi-after.json`. Run `uvx --offline schemathesis@4.29.3 run out/openapi-after.json --url $B -H "Cookie: $C" --max-examples 5 --workers 1 --seed 24925650107731152066331093038258341477` with the spike's seed, and write stdout and stderr to `out/after.txt`. The root `.gitignore` (line 25, `out/`) ignores that directory.

    Step 4. Record before and after in the SUMMARY. The spike's `out/full.txt` showed 5 "is not defined" lines, an "11 errors" summary, and 13 reproductions containing `userId=`. Other findings (the 401 `instance` format, the missing 201 on `/signup`, auth-expiry 401s) are out of scope: record their counts, do not fix them.

    Step 5, teardown. Stop bootRun, run `docker compose down`, and confirm with `docker ps -a` that nothing is left over.

    Fallback: if the stack will not start or the cache lacks 4.29.3, record "could not verify live: <reason>" as the terminal state. Never drop `--offline` to fetch a package.

    Fuzzing writes rows and Kafka events, so point it only at this throwaway local stack, never at nonprod or prod.
  </action>
  <verify>
    <automated>test -s .planning/spikes/003-web-fuzzing-schemathesis/out/after.txt && rg -q '24 selected / 24 total' .planning/spikes/003-web-fuzzing-schemathesis/out/after.txt && ! rg -q "is not defined|userId=" .planning/spikes/003-web-fuzzing-schemathesis/out/after.txt</automated>
  </verify>
  <done>Schemathesis selected 24 of 24 operations, with no undefined-path-parameter errors and no `userId=` in any reproduction. Alternatively, a "could not verify live" row with its cause is recorded. Either way, the local stack is torn down.</done>
</task>

<task type="auto">
  <name>Task 3: Record the decision in the docs, run the full suite, commit</name>
  <files>docs/ARCHITECTURE.md, docs/learning/05-api-layer.md</files>
  <read_first>docs/ARCHITECTURE.md lines 28-48 (the two OpenAPI bullets), docs/learning/05-api-layer.md ("Summary of decisions" table and the `@CurrentUserId` section)</read_first>
  <action>
    Step 1. In `docs/ARCHITECTURE.md`, add one bullet after the composed-constraints bullet, at the same density as its neighbors. It must cover:
    - what is now true: every path-template variable is declared, and session identity is not a parameter;
    - the two mechanisms and the guarding test, by name;
    - spike 003 as the origin;
    - the contract note: no change on the wire, while generated clients drop `userId` and gain required ancestor path parameters when they regenerate.

    Step 2. In `docs/learning/05-api-layer.md`, add row API-20 to the Summary of decisions table. Add one sentence to the `@CurrentUserId` "How it works" text saying that `CustomArgumentResolverConfig` also puts the annotation on springdoc's ignore list. Do not edit the `docs/wiki/` copies; CLAUDE.md treats the originals as authoritative.

    Step 3. Run the full `./gradlew test`, which includes the kafka and realSocket tags and the JaCoCo ratchet. It is long, so use a long timeout or run it in the background and poll. Afterwards, check `docker ps -a --filter label=org.testcontainers` for orphans (no Ryuk here) and remove any.

    Step 4. Commit the two docs as `docs(quick-261006-dpq): record the fuzzable-document decision`.

    Step 5. The SUMMARY must carry:
    - the RED, independence and GREEN evidence with counts, stating that the test was checked failing on the unfixed code and passing on the fix;
    - the Schemathesis before and after;
    - the contract-change note;
    - the frontend follow-up: regenerate types and delete 19 `query: { userId }` call sites in kanban-board-frontend.
  </action>
  <verify>
    <automated>./gradlew test && rg -q 'PathTemplateParameterOpenApiCustomizer' docs/ARCHITECTURE.md && rg -q 'API-20' docs/learning/05-api-layer.md</automated>
  </verify>
  <done>Full `./gradlew test` passes with exit 0, both docs name the mechanism, the docs commit landed through the hook, and no Testcontainers containers are left running.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| client → API | The published document tells clients and fuzzers which inputs the server accepts. A wrong document misleads them about where identity comes from. |
| dev host → PyPI | Task 2 runs a Python tool. It runs offline from the uv cache, so this boundary is not crossed. |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-dpq-01 | Spoofing (perceived) | `userId` query parameter on 22 operations | medium | mitigate | The annotation-type ignore registration (D-A) removes it from the document. `CurrentUserIdResolver` is unchanged and reads only `SecurityContextHolder`. Sweep B guards against regression. |
| T-dpq-02 | Elevation of privilege | ancestor ids now published as required but not cross-checked | low | accept | Ownership is verified along the leaf's chain (`OwnershipVerifierService`), so authorization does not change. Recorded as `Known holes:` in the customizer Javadoc; enforcing ancestor consistency is out of scope. |
| T-dpq-03 | Information disclosure | full route parameters in the document | low | accept | The route shape was already public in the path keys, so no new data is exposed. |
| T-dpq-04 | Denial of service | customizer cost per document build | low | accept | It runs once per cached build at about 100 comparisons, with no per-request work. |
| T-dpq-05 | Tampering | Task 2 fuzzing writes rows and Kafka events | medium | mitigate | Only the local compose stack with throwaway credentials is targeted, never nonprod or prod, and Task 2 ends with `docker compose down`. |
| T-dpq-SC | Tampering | uvx / PyPI | high | mitigate | No dependency is added. `uvx --offline schemathesis@4.29.3` runs only the copy spike 003 already cached, and the fallback refuses to fetch. |
</threat_model>

<verification>
- The RED run (unfixed) failed with 11 and 22, the independence run (after D-A only) left 11 and 0, and the GREEN run passed. All three are recorded in the SUMMARY.
- The four OpenAPI-reading test classes are green, `spotlessCheck` and `verify-comments.py check` exit 0, and the full `./gradlew test` exits 0.
- The live Schemathesis run has zero undefined-path-parameter errors and no `userId=`, or a recorded "could not verify live".
- `git log --stat` for the two commits shows no file under `controller/` or `security/`.
</verification>

<success_criteria>
Schemathesis can generate requests for all 24 operations. No operation tells a client to send its
own user id. A new endpoint that forgets either property fails `OpenApiParameterCompletenessTest`
without anyone having to add it to a list.
</success_criteria>

<output>
Create `.planning/quick/261006-dpq-fix-openapi-doc-so-it-is-schemathesis-fu/261006-dpq-SUMMARY.md` when done.
</output>
