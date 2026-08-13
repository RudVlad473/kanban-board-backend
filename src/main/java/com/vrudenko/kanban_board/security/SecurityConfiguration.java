package com.vrudenko.kanban_board.security;

import java.util.List;

import com.vrudenko.kanban_board.constant.ApiPaths;

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
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HeaderWriterLogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.ConcurrentSessionControlAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.header.writers.ClearSiteDataHeaderWriter;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@EnableMethodSecurity
public class SecurityConfiguration {
    private final AuthenticationProvider authenticationProvider;
    private final LogoutHandler handlerLogout;
    private final AuthenticationEntryPoint problemDetailAuthenticationEntryPoint;

    // Shared by the sessionManagement DSL below and by the enforcing bean, so the two cannot
    // drift to different numbers.
    private static final int MAX_CONCURRENT_SESSIONS = 2;

    @Value("${server.servlet.context-path}")
    private String CONTEXT_PATH;

    @Value("${springdoc.api-docs.path}")
    private String SWAGGER_DOCS_PATH;

    // /claude-security F3 (07.1-09): read directly via @Value on this Spring-managed
    // @Configuration bean, not through SecurityConstants.SESSION_NAME -- a @Value on a public
    // static field of a plain, non-Spring-managed class is inert (nothing ever processes it),
    // so that field stayed null forever and every logout.deleteCookies(...) call below
    // registered a handler that tried new Cookie(null, null), throwing IllegalArgumentException
    // from inside LogoutFilter on every real POST /logout. SecurityConstants itself has been
    // deleted; this was its only caller.
    @Value("${server.servlet.session.cookie.name}")
    private String sessionCookieName;

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
                    auth.requestMatchers(
                                    ApiPaths.SIGNIN,
                                    ApiPaths.SIGNUP,
                                    SWAGGER_DOCS_PATH,
                                    String.format("%s/*", SWAGGER_DOCS_PATH),
                                    String.format("%s/*", ApiPaths.SWAGGER_UI),
                                    // Phase 5, INFRA-01: the production Docker healthcheck curls
                                    // this path unauthenticated. This matcher (like ApiPaths.SIGNIN
                                    // above) is context-path-relative -- the servlet container
                                    // strips server.servlet.context-path (=/api) before Spring
                                    // Security evaluates request matchers, so the externally
                                    // resolvable URL is /api/actuator/health even though this entry
                                    // reads /actuator/health.
                                    ApiPaths.ACTUATOR_HEALTH)
                            .permitAll();

                    auth.anyRequest().authenticated();
                });

        // Mandatory, unlike CorsConfigurationSource: an AuthenticationEntryPoint bean is NOT
        // auto-detected from the context. Without this explicit DSL call, a genuinely
        // unauthenticated request still falls through to Spring Security's default
        // Http403ForbiddenEntryPoint (bare 403, no body) instead of the RFC 7807 401 envelope
        // ProblemDetailAuthenticationEntryPoint produces (D-04, D-05).
        http.exceptionHandling(
                handling ->
                        handling.authenticationEntryPoint(problemDetailAuthenticationEntryPoint));

        // session management
        // Measured, not assumed (quick task 260813-m9x): SessionManagementConfigurer DOES install
        // a filter from these declarations -- both SessionManagementFilter and the
        // ConcurrentSessionFilter this file's sessionAuthenticationStrategy Javadoc already names.
        // That filter holds its OWN CompositeSessionAuthenticationStrategy, composed by the
        // configurer from the two DSL calls above -- a different instance (reference-compared)
        // from the sessionAuthenticationStrategy bean below, backed by an in-memory
        // SessionRegistryImpl rather than that bean's JDBC-backed
        // SpringSessionBackedSessionRegistry.
        // This filter-held strategy only fires on a request that reaches the chain already
        // carrying an authenticated context the session repository never stored -- concretely,
        // MockMvc's .with(user(...)) test shortcut (see InjectionAttemptTest), never the real
        // signin/signup path: AuthenticationController.authenticate authenticates and invokes the
        // bean below explicitly before this filter ever observes an authenticated context on that
        // request, so on that path the filter finds nothing to do. Two independent ceiling
        // enforcers therefore coexist on different paths, backed by different registries -- see the
        // bean's own Javadoc below for the real-path enforcer.
        http.sessionManagement(
                (session) -> {
                    session.maximumSessions(MAX_CONCURRENT_SESSIONS).maxSessionsPreventsLogin(true);
                    session.sessionFixation(
                            SessionManagementConfigurer.SessionFixationConfigurer::changeSessionId);
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
                    logout.deleteCookies(sessionCookieName);
                    logout.logoutSuccessHandler(handlerLogout);
                });

        // auth provider for connect DAO
        http.authenticationProvider(authenticationProvider);

        return http.build();
    }

    /**
     * Enforces the two session controls declared above in {@code securityFilterChain}'s {@code
     * sessionManagement} block, on the real signin/signup path: {@link
     * AuthenticationController#authenticate} invokes {@code onAuthentication(...)} on this strategy
     * directly, before the {@code SecurityContext} is ever saved. This is necessary despite {@code
     * SessionManagementConfigurer} installing a {@code SessionManagementFilter} from the same DSL
     * block (measured, quick task 260813-m9x -- see the {@code sessionManagement} block's own
     * comment above): that filter holds a separate, DSL-composed {@code
     * CompositeSessionAuthenticationStrategy} backed by an in-memory {@code SessionRegistryImpl},
     * not this bean, and only fires when a request reaches the chain already authenticated without
     * a stored context -- a shape this application's own signin/signup requests never produce, but
     * MockMvc's {@code .with(user(...))} test shortcut does (see {@code InjectionAttemptTest}). The
     * two enforcers are independent: this bean's {@code SpringSessionBackedSessionRegistry} reads
     * the live, JDBC-persisted session count, so its ceiling is consistent across instances; the
     * filter-held strategy's in-memory registry is not.
     *
     * <p><b>Known, accepted TOCTOU window (finding F6, 2026-08-10 {@code /claude-security}
     * scan).</b> {@code ConcurrentSessionControlAuthenticationStrategy} enforces the ceiling by
     * reading the caller's live {@code SPRING_SESSION} count and then allowing the signin to
     * register a new session -- a check-then-act sequence. Two genuinely simultaneous signins for
     * one principal can both read the same under-threshold count before either has committed its
     * new session row, so both can proceed. This is a <b>knowingly accepted, bounded</b> trade-off,
     * not an unnoticed gap -- the same disposition style {@link
     * com.vrudenko.kanban_board.entity.UserEntity}'s deliberately-unversioned last-write-wins theme
     * preference carries (D-14). The bound is a function of concurrency, not a constant: live
     * sessions for one principal never exceed {@code MAX_CONCURRENT_SESSIONS} plus at most one
     * extra per signin genuinely in flight at the same instant -- never "at most 3" as a flat
     * ceiling. The excess is transient and self-correcting, because the next non-concurrent signin
     * for that principal is still refused.
     *
     * <p>A transaction-scoped advisory lock (e.g. {@code pg_advisory_xact_lock}) around the
     * count-then-register sequence does not close this window. Measured, not assumed (quick task
     * 260811-h2v, Task 2, 2026-08-11): a probe reading the live {@code SPRING_SESSION} count from a
     * <i>second</i> database connection, taken the instant {@link
     * AuthenticationController#authenticate}'s {@code mapTry} lambda finishes (right after {@code
     * securityContextRepository.saveContext(...)} returns -- the latest point any controller-scoped
     * transaction could still be open), read <b>0</b> committed rows for the just-authenticated
     * principal; a client-side probe taken after the HTTP response was fully received read
     * <b>1</b>. The new session row is not committed, and therefore not visible to another
     * connection, until Spring Session's request-scoped filter commits it as the response is
     * flushed -- strictly after this method (and any transaction scoped around it) has already
     * returned. A {@code pg_advisory_xact_lock} held across this call would therefore release
     * before the row it was meant to serialize against exists, closing nothing while adding a
     * blocking database round trip to every signin. {@code ConcurrentSigninCeilingE2ETest} is the
     * empirical proof of the accepted bound.
     *
     * <p>The rejection this strategy throws stays a {@code 401}, byte-identical to a wrong-password
     * response (D-08). Do not give a ceiling rejection its own status code or otherwise make it
     * distinguishable -- that would hand an attacker a validity oracle for "these credentials are
     * valid, this account just has sessions open."
     */
    @Bean
    public <S extends Session> SessionAuthenticationStrategy sessionAuthenticationStrategy(
            FindByIndexNameSessionRepository<S> sessionRepository) {
        // Local variable, deliberately NOT its own @Bean: a published SessionRegistry bean would
        // be picked up by SessionManagementConfigurer and handed to the already-installed
        // ConcurrentSessionFilter, adding a JdbcIndexedSessionRepository lookup to every
        // authenticated request for a code path that is dead by design under
        // maxSessionsPreventsLogin(true) (it prevents login instead of expiring a session, so it
        // never marks a SessionInformation expired).
        var sessionRegistry = new SpringSessionBackedSessionRegistry<>(sessionRepository);

        var concurrentSessionControl =
                new ConcurrentSessionControlAuthenticationStrategy(sessionRegistry);
        concurrentSessionControl.setMaximumSessions(MAX_CONCURRENT_SESSIONS);
        concurrentSessionControl.setExceptionIfMaximumExceeded(true);

        // Order matters: concurrency control MUST run before fixation, so a login the ceiling
        // rejects does not rotate the caller's existing session id as a side effect of a failed
        // request. No RegisterSessionAuthenticationStrategy delegate is needed here specifically
        // because the registry is Spring-Session-backed -- its registerNewSession is a documented
        // no-op and the live count comes straight from the JDBC store. That delegate WOULD be
        // mandatory if this registry were ever swapped for an in-memory SessionRegistryImpl.
        return new CompositeSessionAuthenticationStrategy(
                List.of(concurrentSessionControl, new ChangeSessionIdAuthenticationStrategy()));
    }
}
