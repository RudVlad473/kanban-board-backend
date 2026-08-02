# Quick Task 260802-shl: Fix the dead Spring Session JDBC configuration - Context

**Gathered:** 2026-08-02
**Status:** Ready for planning

<domain>
## Task Boundary

Fix the dead Spring Session JDBC configuration. Research from quick task 260802-ryf found that
`application.properties` sets `spring.session.store-type=jdbc` and
`spring.session.jdbc.initialize-schema=always`, and both CLAUDE.md's architecture notes and a
comment in `UserAuthenticationProvider.java:35` describe sessions as persisted to a PostgreSQL
`spring_session` table for horizontal scaling — but `org.springframework.session` is absent from
the runtime classpath entirely (verified via `./gradlew dependencies --configuration
runtimeClasspath`), so those properties are inert and sessions are actually Tomcat's in-memory
`HttpSession` via a plain `HttpSessionSecurityContextRepository`. This means session loss on every
app restart (and `master` auto-deploys to EC2 on every push) and no session sharing if ever scaled
past one instance — contradicting the documented behavior.

</domain>

<decisions>
## Implementation Decisions

### Fix direction
- Wire up spring-session-jdbc for real (not remove-and-document). The existing config, the 180-minute
  session timeout, and the docs all point at intended JDBC-backed sessions — in-memory sessions that
  vanish on every EC2 redeploy is a real UX regression for logged-in users, worth actually fixing
  rather than downgrading the docs to match a bug.

### Schema creation strategy
- Rely on `spring.session.jdbc.initialize-schema=always` (already set in `application.properties`) to
  create the `spring_session`/`spring_session_attributes` tables itself, independent of Hibernate's
  `ddl-auto` (which is unset in the real Postgres profile). Spring Session JDBC's own schema script is
  idempotent (`CREATE TABLE IF NOT EXISTS`), so this needs no manual DDL bridge script and no new
  pre-merge manual gate — unlike the two existing outstanding DDL scripts (optimistic-locking,
  activity-log) which exist specifically because Hibernate's automatic schema generation doesn't run
  in production. Do NOT add a third manual DDL script for this.

### Claude's Discretion
- Exact dependency coordinate/version for `spring-session-jdbc` (compatible with Spring Boot 3.5.0's
  managed BOM — let dependency management resolve the version rather than hand-pinning unless a
  compatibility issue is found).
- Whether the H2 test profile needs any adjustment (Spring Session JDBC's schema script has an H2
  variant; confirm test suite still passes with the dependency added — H2 already works fine today
  with the property set but the dependency absent, so tests currently exercise plain in-memory
  sessions too).
- How to correct the two locations currently describing JDBC-backed sessions as already-true fact
  (CLAUDE.md architecture notes, `UserAuthenticationProvider.java:35` comment) — once the dependency
  is added this becomes true, so those should end up accurate rather than needing invention of new
  wording, but verify the exact claims still hold after the fix (e.g. table name, "horizontal scaling"
  framing).
- Whether `maxSessionsPreventsLogin`/max-2-concurrent-sessions config (documented in STATE.md/
  ARCHITECTURE notes) has any interaction with the store-type switch worth a sanity check.

</decisions>

<specifics>
## Specific Ideas

No specific code shape mandated — the addition is largely dependency-management plus verifying the
already-present properties now do what they were always meant to do. The one hard constraint: no new
manual pre-merge DDL step, per the schema-creation decision above.

</specifics>

<canonical_refs>
## Canonical References

- `.planning/quick/260802-ryf-enable-virtual-threads-in-spring-boot-co/260802-ryf-RESEARCH.md` — the
  research that surfaced this as a side finding (section "3. Spring Session JDBC — the risk does not
  exist in this app", which documents the classpath-absence finding precisely).
- `.planning/todos/pending/2026-08-02-enable-virtual-threads-in-spring-boot-config.md` — carries the
  same side-finding note.
- `src/main/resources/application.properties` lines ~22-24 (the three inert properties) and the
  `spring.session.max-sessions`/`maxSessionsPreventsLogin` config nearby.
- `src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java:38` — current
  `HttpSessionSecurityContextRepository` wiring, to be reviewed/replaced.
- `src/main/java/com/vrudenko/kanban_board/security/UserAuthenticationProvider.java:35` — the comment
  describing (currently inaccurate) JDBC session persistence.
- `CLAUDE.md` architecture notes: "Session persistence: All sessions stored in PostgreSQL
  spring_session table, not in memory. Allows horizontal scaling."

</canonical_refs>
