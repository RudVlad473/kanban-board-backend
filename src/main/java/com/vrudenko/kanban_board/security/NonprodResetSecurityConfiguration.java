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
 * Permit the nonprod reset route, and only that route, without a session via a second
 * SecurityFilterChain.
 *
 * SecurityConfiguration's catch-all chain is untouched. The @Profile("nonprod")
 * gate keeps production's filter chain unchanged: there this bean does not exist, so the permit
 * rule is absent, not merely unused. The catch-all chain has no @Order, so it sorts last
 * (LOWEST_PRECEDENCE) and handles every other route in every context.
 *
 * SessionCreationPolicy.STATELESS means a reset call never creates a
 * spring_session row that the same reset would then truncate.
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
