package com.vrudenko.kanban_board.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.vrudenko.kanban_board.support.containers.AbstractPostgresContainerTest;

import com.google.common.base.Splitter;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Proves the test-profile BCrypt cost factor (quick task 260811-ixj) is genuinely in force rather
 * than silently ignored, and that the production fallback stays at Spring Security's default of 10.
 * Extends {@link AbstractPostgresContainerTest} directly, matching {@code CorsConfigTest}'s
 * precedent, rather than {@code AbstractAppTest} -- this test needs no user/board/column/task
 * fixture data, and inheriting {@code AbstractAppTest}'s per-test fixture build is exactly the cost
 * this quick task removes, so pulling it in here would be self-defeating.
 */
@SpringBootTest
class PasswordEncoderStrengthTest extends AbstractPostgresContainerTest {

    @Autowired private PasswordEncoder passwordEncoder;

    @Nested
    class TestProfileCostFactor {

        @Test
        void shouldEncodeAtCostFactorFour_whenUsingTheAutowiredBean() {
            // arrange
            var plaintext = "throwaway-plaintext-for-cost-factor-assertion";

            // act
            var hash = passwordEncoder.encode(plaintext);

            // assert -- a BCrypt hash is "$<algorithm>$<cost>$<salt+digest>"; splitting on '$'
            // yields ["", algorithm, cost, salt+digest]. A typo'd or misplaced
            // security.bcrypt.strength property key makes this red (cost stays 10), not quietly
            // slow.
            String costSegment = Splitter.on('$').splitToList(hash).get(2);
            Assertions.assertThat(costSegment).isEqualTo("04");
        }
    }

    @Nested
    class ProductionFallback {

        @Test
        void shouldFallBackToTen_whenNoOverrideIsConfigured() throws NoSuchMethodException {
            // arrange
            Method passwordEncoderBeanMethod =
                    BeanConfiguration.class.getDeclaredMethod("passwordEncoder", int.class);

            // act
            Value strengthValueAnnotation =
                    passwordEncoderBeanMethod.getParameters()[0].getAnnotation(Value.class);

            // assert -- goes red if anyone changes the production fallback away from 10.
            Assertions.assertThat(strengthValueAnnotation).isNotNull();
            Assertions.assertThat(strengthValueAnnotation.value())
                    .isEqualTo("${security.bcrypt.strength:10}");
        }

        @Test
        void shouldBeAbsentFromDefaultProperties_whenReadingApplicationProperties()
                throws IOException {
            // arrange
            var defaultPropertiesResource = new ClassPathResource("application.properties");

            // act
            List<String> nonCommentLines;
            try (var inputStreamReader =
                            new InputStreamReader(
                                    defaultPropertiesResource.getInputStream(),
                                    StandardCharsets.UTF_8);
                    var bufferedReader = new BufferedReader(inputStreamReader)) {
                nonCommentLines =
                        bufferedReader
                                .lines()
                                .filter(line -> !line.strip().startsWith("#"))
                                .toList();
            }

            // assert -- goes red if the test-only override ever leaks into the default profile.
            Assertions.assertThat(nonCommentLines)
                    .noneMatch(line -> line.contains("security.bcrypt.strength"));
        }
    }
}
