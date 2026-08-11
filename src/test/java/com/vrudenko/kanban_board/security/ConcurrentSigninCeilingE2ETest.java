package com.vrudenko.kanban_board.security;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.user_dto.SigninRequestDTO;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppE2ETest;

import io.restassured.http.ContentType;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import static io.restassured.RestAssured.given;

/**
 * Concurrent sibling of {@link AuthenticationTest.ConcurrentSessionCeiling}'s sequential spec --
 * this class replaces nothing in it. That MockMvc-tier test proves the ceiling rejects a *third,
 * sequential* signin; this class proves what happens when two signins for the same principal arrive
 * at the ceiling *at the same instant*.
 *
 * <p><b>The finding (F6, 2026-08-10 {@code /claude-security} scan):</b> {@code
 * SecurityConfiguration#sessionAuthenticationStrategy}'s {@code
 * ConcurrentSessionControlAuthenticationStrategy} enforces {@code MAX_CONCURRENT_SESSIONS = 2} by
 * reading the caller's live {@code SPRING_SESSION} count and then allowing the signin to register a
 * new session -- a check-then-act sequence. Two genuinely concurrent signins for one principal can
 * both read the same under-threshold count before either has persisted its new session row, so both
 * can proceed, briefly exceeding the ceiling.
 *
 * <p><b>Disposition (D-01, this plan):</b> accepted as a bounded, self-healing overshoot rather
 * than closed with a transaction-scoped lock -- see {@code SecurityConfiguration}'s Javadoc for why
 * a {@code pg_advisory_xact_lock} around the count-then-register sequence would not have closed
 * this race (measured, not assumed, in this plan's Task 2).
 *
 * <p><b>Why real-socket tier (rule 4, {@code docs/CODE_STYLE.md}):</b> only a genuine
 * multi-threaded HTTP race exercises the actual check-then-act window; a MockMvc-tier approximation
 * would prove a race in the in-process dispatch path, not the deployed one.
 * {@code @Tag("realSocket")} deliberately excludes this class from the pre-commit {@code fastTest}
 * gate (a two-thread HTTP race in the commit hook would be a flake generator) -- the regression
 * only guards {@code ./gradlew test} and CI.
 *
 * <p><b>Why the assertions are an invariant plus a range, not an exact count (D-03):</b> asserting
 * that the overshoot *occurs* would make this test flaky by construction, since the race window is
 * microseconds wide and whether it is hit on a given run depends on OS thread scheduling. The
 * shipped assertions instead hold under both outcomes: {@code liveSessionCount() == 1 +
 * successCount} always, and {@code successCount} is one of {@code 1} (the racers happened to
 * serialize) or {@code 2} (the accepted overshoot) -- both conformant.
 *
 * <p><b>Measured overshoot frequency (temporary {@code @RepeatedTest(10)} characterization run,
 * 2026-08-11):</b> <b>10 of 10</b> repetitions produced {@code successCount == 2} (the TOCTOU
 * overshoot) on this machine -- the window opened on every attempt, not narrowly. This is a real,
 * reported measurement, not an assumption: two cookie-less {@code POST /signin} requests racing
 * through {@code RestAssured}/real-socket HTTP against a local Testcontainers Postgres consistently
 * lose the race to the ceiling's check-then-act window under this harness's thread-pool submission
 * pattern. It does not change the disposition (D-01): the overshoot is still exactly one extra
 * session, still self-heals (assert 3 below), and still grants no capability beyond what the two
 * permitted sessions already grant -- but it does mean the accepted trade-off should be read as
 * "the ceiling reliably allows one extra concurrent signin to succeed," not as a rare edge case.
 * The shipped assertion accepts {@code successCount} in {@code [1, 2]} rather than asserting either
 * outcome, since which one occurs depends on scheduling this test does not control.
 *
 * <p><b>D-08 stays intact:</b> a ceiling rejection is a 401, byte-identical to a wrong-password
 * response. This class must never be "improved" to distinguish the two -- doing so would hand an
 * attacker a validity oracle.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("realSocket")
public class ConcurrentSigninCeilingE2ETest extends AbstractAppE2ETest {

    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * Scoped by principal, not an absolute table count: {@code SPRING_SESSION} rows carry no
     * foreign key to {@code users} and survive {@code AbstractAppTest}'s {@code @AfterEach}, so
     * they accumulate across the whole JVM run. Scoping by {@code PRINCIPAL_NAME} (the userId, per
     * {@link UserAuthenticationProvider}) keeps this deterministic regardless of what earlier tests
     * left behind.
     */
    private int liveSessionCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?",
                Integer.class,
                getOwningUser().getId());
    }

    @Nested
    class ConcurrentSignin {
        @Test
        void shouldCreateOneSessionPerAcceptedSignin_whenTwoSigninsRaceTheCeiling()
                throws InterruptedException {
            // arrange -- one live session, so the headroom below the ceiling of 2 is exactly 1.
            // Only a headroom of exactly 1 opens the window this test characterizes: with 0
            // headroom both racers are correctly rejected, with 2 both are correctly accepted.
            signin();
            Assertions.assertThat(liveSessionCount())
                    .as("fixture problem, not a race result, if this is not 1")
                    .isEqualTo(1);

            var dto =
                    SigninRequestDTO.builder()
                            .email(getOwningUser().getEmail())
                            .password(getOwningUserPassword())
                            .build();

            var startGate = new CountDownLatch(1);
            var firstStatus = new AtomicReference<Integer>();
            var secondStatus = new AtomicReference<Integer>();
            ExecutorService executor = Executors.newFixedThreadPool(2);

            // act -- two fresh, cookie-less signins racing the ceiling. Futures deliberately
            // dropped, not awaited: awaiting them would serialize the submissions and destroy the
            // race window (mirrors BoardCreationE2ETest.ConcurrentCreate).
            try {
                {
                    Future<?> unused =
                            executor.submit(
                                    () -> {
                                        try {
                                            startGate.await();
                                            var status =
                                                    given().contentType(ContentType.JSON)
                                                            .body(dto)
                                                            .when()
                                                            .post(ApiPaths.SIGNIN)
                                                            .then()
                                                            .extract()
                                                            .statusCode();
                                            firstStatus.set(status);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                    });
                }
                {
                    Future<?> unused =
                            executor.submit(
                                    () -> {
                                        try {
                                            startGate.await();
                                            var status =
                                                    given().contentType(ContentType.JSON)
                                                            .body(dto)
                                                            .when()
                                                            .post(ApiPaths.SIGNIN)
                                                            .then()
                                                            .extract()
                                                            .statusCode();
                                            secondStatus.set(status);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                    });
                }

                startGate.countDown();
                executor.shutdown();
                Assertions.assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            } finally {
                executor.shutdownNow();
            }

            // assert (1) -- no lost or phantom rows: every racer is either accepted or rejected by
            // the ceiling, never a 500, and live rows always equal 1 (the arranged session) plus
            // however many racers were accepted.
            Assertions.assertThat(firstStatus.get()).isNotNull();
            Assertions.assertThat(secondStatus.get()).isNotNull();
            Assertions.assertThat(firstStatus.get())
                    .isIn(HttpStatus.OK.value(), HttpStatus.UNAUTHORIZED.value());
            Assertions.assertThat(secondStatus.get())
                    .isIn(HttpStatus.OK.value(), HttpStatus.UNAUTHORIZED.value());

            var successCount =
                    (int)
                            Stream.of(firstStatus.get(), secondStatus.get())
                                    .filter(status -> status.equals(HttpStatus.OK.value()))
                                    .count();

            Assertions.assertThat(liveSessionCount())
                    .as("a rejected signin creates no row, an accepted one always does")
                    .isEqualTo(1 + successCount);

            // assert (2) -- the bound (D-01): 1 means the racers happened to serialize and the
            // ceiling held exactly; 2 is the accepted TOCTOU overshoot. Both are conformant -- that
            // is precisely what "bounded and accepted" means.
            Assertions.assertThat(successCount)
                    .as(
                            "either the ceiling held exactly (1) or the accepted, bounded TOCTOU"
                                    + " overshoot occurred (2) -- both are conformant per D-01")
                    .isBetween(1, 2);

            // assert (3) -- self-healing, and this test's teeth: a signin issued sequentially
            // after the burst settles is still refused, and creates no new row. This is what
            // distinguishes "the ceiling overshoots transiently under concurrency" from "the
            // ceiling is not enforced at all" -- it goes red if onAuthentication is ever
            // neutralized.
            var postBurstStatus =
                    given().contentType(ContentType.JSON)
                            .body(dto)
                            .when()
                            .post(ApiPaths.SIGNIN)
                            .then()
                            .extract()
                            .statusCode();

            Assertions.assertThat(postBurstStatus).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            Assertions.assertThat(liveSessionCount()).isEqualTo(1 + successCount);
        }
    }
}
