package com.vrudenko.kanban_board.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.user_dto.SigninRequestDTO;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppMockMvcTest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the <em>cost</em> half of signin's anti-enumeration guarantee -- that an unregistered
 * email and a wrong password each drive exactly one {@link PasswordEncoder#matches(CharSequence,
 * String)} invocation through {@link AuthenticationController#signin}. {@link
 * AuthenticationTest.Signin.AntiEnumeration} proves the <em>content</em> half (byte-identical
 * response bodies); neither test supersedes the other -- a response can be byte-identical while
 * still leaking timing, and this class exists to close that separate channel (finding F1,
 * 2026-08-10 {@code /claude-security} scan).
 *
 * <p>The {@link CountingPasswordEncoder} delegate below does not violate {@code docs/CODE_STYLE.md}
 * rule 4's no-mocks constraint: nothing is stubbed or given a canned answer. Every call forwards to
 * the real {@link PasswordEncoder} bean {@link
 * com.vrudenko.kanban_board.config.BeanConfiguration#passwordEncoder()} publishes and returns that
 * bean's real answer; the delegate only counts {@code matches(...)} calls on the side. It is wired
 * through the real Spring context via {@code @Primary}, so every production code path under test --
 * including {@link UserAuthenticationProvider}'s own injection point -- still runs unmodified.
 *
 * <p>This class is deliberately separate from {@link AuthenticationTest} rather than a new
 * {@code @Nested} group inside it: the {@link CountingPasswordEncoderConfig}
 * {@code @TestConfiguration} below forks its own Spring context cache key (a distinct bean graph),
 * and isolating that fork in its own file keeps it off {@code AuthenticationTest}, which is shared
 * by far more test groups and would otherwise pay that extra context startup for everyone.
 *
 * <p>{@link CountingPasswordEncoder#matchesInvocationCount()} is per-context mutable state, reset
 * at the start of each test via {@link CountingPasswordEncoder#resetMatchesInvocationCount()}. That
 * reset is what makes a single shared counter deterministic across test methods -- safe here
 * because JUnit runs this class sequentially (no {@code junit-platform.properties} declares
 * parallelism anywhere in this project).
 */
@SpringBootTest
@AutoConfigureMockMvc
public class SigninTimingEqualizationTest extends AbstractAppMockMvcTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private CountingPasswordEncoder countingPasswordEncoder;

    /**
     * Delegating {@link PasswordEncoder} that forwards every call to a real encoder and returns its
     * real answer, counting only how many times {@link #matches(CharSequence, String)} was invoked.
     * Not a mock: the wrapped delegate is the application's own configured {@link PasswordEncoder}
     * bean, so every credential comparison this test observes is a genuine BCrypt comparison
     * against a genuine stored hash.
     */
    private static final class CountingPasswordEncoder implements PasswordEncoder {
        private final PasswordEncoder delegate;
        private final AtomicInteger matchesInvocationCount = new AtomicInteger(0);

        private CountingPasswordEncoder(PasswordEncoder delegate) {
            this.delegate = delegate;
        }

        @Override
        public String encode(CharSequence rawPassword) {
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            matchesInvocationCount.incrementAndGet();
            return delegate.matches(rawPassword, encodedPassword);
        }

        @Override
        public boolean upgradeEncoding(String encodedPassword) {
            return delegate.upgradeEncoding(encodedPassword);
        }

        int matchesInvocationCount() {
            return matchesInvocationCount.get();
        }

        void resetMatchesInvocationCount() {
            matchesInvocationCount.set(0);
        }
    }

    /**
     * Publishes {@link CountingPasswordEncoder} as the {@code @Primary} {@link PasswordEncoder} for
     * this test class's Spring context only -- never a {@code @Component} in a scanned package, so
     * it cannot leak into production wiring or any other test class's context. The explicit
     * {@code @Qualifier("passwordEncoder")} on the delegate parameter names {@link
     * com.vrudenko.kanban_board.config.BeanConfiguration#passwordEncoder()} directly, rather than
     * leaning on Spring's self-reference exclusion to resolve an otherwise-ambiguous {@link
     * PasswordEncoder} parameter.
     */
    @TestConfiguration
    static class CountingPasswordEncoderConfig {
        @Bean
        @Primary
        CountingPasswordEncoder countingPasswordEncoder(
                @Qualifier("passwordEncoder") PasswordEncoder delegate) {
            return new CountingPasswordEncoder(delegate);
        }
    }

    @Nested
    class Signin {
        @Test
        void shouldInvokeMatchesExactlyOnce_whenEmailIsUnregistered() throws Exception {
            // arrange
            countingPasswordEncoder.resetMatchesInvocationCount();
            var body =
                    SigninRequestDTO.builder()
                            .email("signin-timing-" + UUID.randomUUID() + "@example.com")
                            .password(generateValidPassword())
                            .build();

            // act
            var result =
                    mockMvc.perform(
                                    post(ApiPaths.SIGNIN)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(body)))
                            .andReturn();

            // assert
            Assertions.assertThat(result.getResponse().getStatus())
                    .isEqualTo(HttpStatus.UNAUTHORIZED.value());
            Assertions.assertThat(countingPasswordEncoder.matchesInvocationCount()).isEqualTo(1);
        }

        @Test
        void shouldInvokeMatchesExactlyOnce_whenPasswordIsWrong() throws Exception {
            // arrange
            countingPasswordEncoder.resetMatchesInvocationCount();
            var body =
                    SigninRequestDTO.builder()
                            .email(getOwningUser().getEmail())
                            .password(getOwningUserPassword().concat("__"))
                            .build();

            // act
            var result =
                    mockMvc.perform(
                                    post(ApiPaths.SIGNIN)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(body)))
                            .andReturn();

            // assert
            Assertions.assertThat(result.getResponse().getStatus())
                    .isEqualTo(HttpStatus.UNAUTHORIZED.value());
            Assertions.assertThat(countingPasswordEncoder.matchesInvocationCount()).isEqualTo(1);
        }
    }
}
