package com.vrudenko.kanban_board.security;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.user_dto.SigninRequestDTO;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppMockMvcTest;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Proves the cost half of signin's anti-enumeration guarantee: an unregistered email and a wrong
 * password each cost one {@link PasswordEncoder#matches} call.
 *
 * <p>The calls are counted through {@link AuthenticationController#signin}. {@link
 * AuthenticationTest.Signin.AntiEnumeration} proves the content half (byte-identical bodies);
 * neither supersedes the other, since a response can be byte-identical while leaking timing.
 *
 * <p>Why this is the way it is: {@link CountingPasswordEncoder} does not violate the no-mocks rule
 * ({@code docs/CODE_STYLE.md} rule 4). It forwards every call to the real {@code
 * BeanConfiguration#passwordEncoder()} bean, returns that bean's real answer, and only counts
 * {@code matches(...)} calls. It is wired through {@code @Primary}, so every production path under
 * test, including {@link UserAuthenticationProvider}'s injection point, runs unmodified. The class
 * is separate from {@link AuthenticationTest} because {@link CountingPasswordEncoderConfig} forks
 * its own Spring context cache key, and isolating it spares {@code AuthenticationTest}'s many
 * groups that extra context startup. The invocation counter is per-context mutable state reset at
 * the start of each test, which is deterministic because JUnit runs this class sequentially.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class SigninTimingEqualizationTest extends AbstractAppMockMvcTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private CountingPasswordEncoder countingPasswordEncoder;

    /**
     * Forwards every call to the application's own {@link PasswordEncoder} bean and returns its
     * real answer, counting only {@link #matches(CharSequence, String)} calls. Not a mock, so every
     * comparison is a genuine BCrypt comparison.
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
     * this class's Spring context only.
     *
     * <p>It is never a {@code @Component} in a scanned package, so it cannot leak into production
     * wiring or another class's context.
     *
     * <p>{@code @Qualifier("passwordEncoder")} on the delegate parameter names {@code
     * BeanConfiguration#passwordEncoder()} directly, instead of leaning on Spring's self-reference
     * exclusion to resolve an ambiguous {@link PasswordEncoder} parameter.
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
