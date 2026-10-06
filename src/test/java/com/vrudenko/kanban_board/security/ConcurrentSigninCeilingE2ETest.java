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
 * Concurrent sibling of AuthenticationTest.ConcurrentSessionCeiling: what happens when two
 * signins for one principal reach the session ceiling at the same instant.
 *
 * Decisions:
 *
 * - Finding (2026-08-10 /claude-security scan):
 *   SecurityConfiguration#sessionAuthenticationStrategy's
 *   ConcurrentSessionControlAuthenticationStrategy enforces MAX_CONCURRENT_SESSIONS =
 *   2 by reading the live SPRING_SESSION count and then letting the signin register a
 *   session, a check-then-act sequence. Two concurrent signins for one principal can both read
 *   the same under-threshold count before either persists its row, so both proceed and briefly
 *   exceed the ceiling.
 * - Disposition: accepted as a bounded, self-healing overshoot rather than closed with a
 *   transaction-scoped lock; see SecurityConfiguration's Javadoc for why a
 *   pg_advisory_xact_lock around the count-then-register sequence would not have closed the
 *   race (measured, not assumed).
 * - Real-socket tier: only a genuine multi-threaded HTTP race exercises the window; a MockMvc
 *   approximation would prove a race in the in-process dispatch path, not the deployed
 *   one. @Tag("realSocket") keeps the class out of the pre-commit fastTest gate (a
 *   two-thread HTTP race in the commit hook would be a flake generator), so it guards only
 *   ./gradlew test and CI (docs/CODE_STYLE.md rule 4).
 * - The assertions are an invariant plus a range, not an exact count: asserting that the
 *   overshoot occurs would be flaky by construction, since the window is microseconds wide and
 *   hitting it depends on OS thread scheduling. They hold under both outcomes:
 *   liveSessionCount() == 1 + successCount always, and successCount is 1 (the
 *   racers serialized) or 2 (the accepted overshoot).
 * - Measured 2026-08-11 with a temporary @RepeatedTest(10): 10 of 10 repetitions
 *   produced successCount == 2 on this machine, so the window opened every attempt.
 *   That does not change the disposition (one extra session, self-healing per assert 3, no
 *   capability beyond what two sessions already grant), but the trade-off reads as "the ceiling
 *   reliably allows one extra concurrent signin", not a rare edge case. Falsifier: a run where
 *   successCount is not in [1, 2].
 * - A ceiling rejection is a 401 byte-identical to a wrong-password response. Never "improve"
 *   this class to distinguish the two: that would hand an attacker a validity oracle.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("realSocket")
public class ConcurrentSigninCeilingE2ETest extends AbstractAppE2ETest {

    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * Counts live sessions scoped by PRINCIPAL_NAME, not the absolute table.
     *
     * The principal name is the userId, per UserAuthenticationProvider.
     * SPRING_SESSION rows have no foreign key to users, survive
     * AbstractAppTest's @AfterEach, and accumulate across the JVM run.
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
            // arrange -- one live session, leaving headroom of exactly 1 below the ceiling of 2.
            // Headroom 0 rejects both racers and headroom 2 accepts both; only 1 opens the window.
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

            // act -- two fresh, cookie-less signins racing the ceiling. Futures are dropped, not
            // awaited: awaiting would serialize the submissions and destroy the race window.
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

            // assert (1) -- no lost or phantom rows: every racer is accepted or rejected, never a
            // 500, and live rows equal 1 (the arranged session) plus the accepted racers.
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

            // assert (2) -- the bound: 1 means the racers serialized and the ceiling held; 2 is
            // the accepted TOCTOU overshoot. Both are conformant.
            Assertions.assertThat(successCount)
                    .as(
                            "either the ceiling held exactly (1) or the accepted, bounded TOCTOU"
                                    + " overshoot occurred (2) -- both are conformant per D-01")
                    .isBetween(1, 2);

            // assert (3) -- self-healing, and this test's teeth: a sequential signin after the
            // burst is still refused and creates no row, which separates a transient overshoot from
            // an unenforced ceiling. Goes red if onAuthentication is ever neutralized.
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
