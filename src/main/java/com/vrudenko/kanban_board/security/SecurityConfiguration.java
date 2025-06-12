package com.vrudenko.kanban_board.security;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.SecurityConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HeaderWriterLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.header.writers.ClearSiteDataHeaderWriter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@EnableMethodSecurity
public class SecurityConfiguration {
    private final AuthenticationProvider authenticationProvider;
    private final LogoutHandler handlerLogout;

    @Value("${server.servlet.context-path}")
    private String CONTEXT_PATH;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        var securityContextRepository = new HttpSessionSecurityContextRepository();

        // csrf & cors
        http.csrf(AbstractHttpConfigurer::disable).cors(Customizer.withDefaults());

        // storing the session
        http.securityContext(
                (context) -> context.securityContextRepository(securityContextRepository));

        http.authorizeHttpRequests(
                auth -> {
                    auth.requestMatchers(ApiPaths.SIGNIN, ApiPaths.SIGNUP).permitAll();

                    auth.anyRequest().authenticated();
                });

        // session management
        http.sessionManagement(
                (session) -> {
                    session.maximumSessions(2).maxSessionsPreventsLogin(true);
                    session.sessionFixation(
                            SessionManagementConfigurer.SessionFixationConfigurer::newSession);
                    session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED);
                });

        // clear cookie when logout
        http.logout(
                (logout) -> {
                    logout.logoutUrl(CONTEXT_PATH + ApiPaths.LOGOUT);
                    logout.addLogoutHandler(
                            new HeaderWriterLogoutHandler(
                                    new ClearSiteDataHeaderWriter(
                                            ClearSiteDataHeaderWriter.Directive.COOKIES)));
                    logout.deleteCookies(SecurityConstants.SESSION_NAME);
                    logout.logoutSuccessHandler(handlerLogout);
                });

        // auth provider for connect DAO
        http.authenticationProvider(authenticationProvider);

        return http.build();
    }
}
