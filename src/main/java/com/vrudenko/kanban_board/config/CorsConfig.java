package com.vrudenko.kanban_board.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Supplies the {@link CorsConfigurationSource} bean that {@link
 * com.vrudenko.kanban_board.security.SecurityConfiguration}'s existing {@code
 * http.cors(Customizer.withDefaults())} call auto-detects -- Spring Security only enables CORS
 * automatically when a {@link UrlBasedCorsConfigurationSource} bean is present, and that line must
 * NOT be edited (D-10, D-11).
 *
 * <p>{@code allowCredentials(true)} is required for this application's cookie-based session auth,
 * which forces an explicit, non-wildcard origin allow-list -- the CORS spec disallows {@code *}
 * once credentials are allowed. The origin list is externalized to {@code app.cors.allowed-origins}
 * so a deployment can widen it without a code change.
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
