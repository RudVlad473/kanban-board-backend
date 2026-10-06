package com.vrudenko.kanban_board.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Supply the CorsConfigurationSource bean that SecurityConfiguration's
 * http.cors(Customizer.withDefaults()) call auto-detects.
 *
 * That call must not be edited: Spring Security enables CORS automatically only when a
 * UrlBasedCorsConfigurationSource bean is present. allowCredentials(true) is required for
 * cookie-based session auth and forces an explicit, non-wildcard origin allow-list, since the CORS
 * spec disallows * once credentials are allowed. The origin list is externalized to
 * app.cors.allowed-origins so a deployment can widen it without a code change.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost:3000}")
                    List<String> allowedOrigins) {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
