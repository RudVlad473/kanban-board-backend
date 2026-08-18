package com.vrudenko.kanban_board.security;

import com.vrudenko.kanban_board.constant.ApiPaths;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Plan 08-02 (RESET-01, D-02): a second, path-scoped {@link SecurityFilterChain} that permits the
 * nonprod reset route (and only that route) without a session, leaving {@link
 * SecurityConfiguration} -- production's own catch-all chain -- completely untouched.
 *
 * <p>This bean's own {@code @Profile("nonprod")} gate is what keeps production's filter chain
 * byte-identical to today's: in a production context this bean does not exist, so the permit rule
 * does not merely go unused there -- it does not exist there either. {@link
 * SecurityConfiguration}'s chain carries no {@code @Order}, so it sorts last (Spring's {@code
 * LOWEST_PRECEDENCE} default) and remains the catch-all for every other route, in every context.
 *
 * <p>{@code SessionCreationPolicy.STATELESS} on this chain means a reset call never creates a
 * {@code spring_session} row that the very same reset would then go on to truncate.
 */
@Profile("nonprod")
@Configuration
public class NonprodResetSecurityConfiguration {
    @Bean
    @Order(1)
    public SecurityFilterChain resetEndpointFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(ApiPaths.RESET)
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        return http.build();
    }
}
