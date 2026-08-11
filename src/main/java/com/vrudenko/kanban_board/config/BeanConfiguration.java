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
     * The BCrypt cost factor accepts an injectable strength so the {@code test} Spring profile can
     * run cheaper (quick task 260811-ixj) than production. The {@code :10} fallback IS the
     * production value -- Spring Security's own {@code BCryptPasswordEncoder} default -- so any
     * deployment that never activates the {@code test} profile (i.e. every real deployment) is
     * unchanged; only {@code application-test.properties} overrides this key, to 4. {@link
     * BCryptPasswordEncoder} rejects any value below 4, so this lever cannot be over-pulled by a
     * later edit. {@link com.vrudenko.kanban_board.security.AuthenticationController}'s
     * {@code @PostConstruct} equalizer hash (F1 timing-equalization fix) is derived from this bean,
     * so it automatically tracks whatever strength is configured here -- it is not weakened by a
     * lower cost factor, only made cheaper to compute.
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
