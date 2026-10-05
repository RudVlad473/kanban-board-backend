package com.vrudenko.kanban_board.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@Configuration
@RequiredArgsConstructor
public class BeanConfiguration {
    /**
     * Build the BCrypt encoder with an injectable strength, so the {@code test} profile can run
     * cheaper than production.
     *
     * <p>The {@code :10} fallback IS the production value (Spring Security's own default), so a
     * deployment that never activates the {@code test} profile is unchanged. Only {@code
     * application-test.properties} overrides it, to 4; {@link BCryptPasswordEncoder} rejects
     * anything below 4. {@link com.vrudenko.kanban_board.security.AuthenticationController}'s
     * {@code @PostConstruct} equalizer hash derives from this bean, so it tracks the configured
     * strength: cheaper to compute, not weakened.
     */
    @Bean
    public PasswordEncoder passwordEncoder(@Value("${security.bcrypt.strength:10}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }

    @Bean
    public AuthenticationManager authenticationManager(HttpSecurity http) throws Exception {
        return http.getSharedObject(AuthenticationManagerBuilder.class).build();
    }

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}
