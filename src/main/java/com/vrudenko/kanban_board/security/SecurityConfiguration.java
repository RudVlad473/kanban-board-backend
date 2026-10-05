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

    // Read via @Value here: on a static field of a plain, non-Spring-managed class it is inert and
    // stays null, and null made every logout.deleteCookies(...) throw IllegalArgumentException
    // from LogoutFilter on a real POST /logout.
    @Value("${server.servlet.session.cookie.name}")
    private String sessionCookieName;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        var securityContextRepository = new HttpSessionSecurityContextRepository();

        http.csrf(AbstractHttpConfigurer::disable).cors(Customizer.withDefaults());

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
                                    // The health check calls this path unauthenticated.
                                    //
                                    // Like SIGNIN above, the matcher is context-path-relative: the
                                    // container strips /api before request matchers run, so
                                    // /api/actuator/health matches this /actuator/health entry.
                                    ApiPaths.ACTUATOR_HEALTH)
                            .permitAll();

                    auth.anyRequest().authenticated();
                });

        // Mandatory, unlike CorsConfigurationSource: an AuthenticationEntryPoint bean is NOT
        // auto-detected.
        //
        // Without this call a genuinely unauthenticated request gets the default
        // Http403ForbiddenEntryPoint (bare 403, no body), not the RFC 7807 401 envelope.
        http.exceptionHandling(
                handling ->
                        handling.authenticationEntryPoint(problemDetailAuthenticationEntryPoint));

        // This DSL also installs its own, in-memory ceiling enforcer; see
        // sessionAuthenticationStrategy.
        http.sessionManagement(
                (session) -> {
                    session.maximumSessions(MAX_CONCURRENT_SESSIONS).maxSessionsPreventsLogin(true);
                    session.sessionFixation(
                            SessionManagementConfigurer.SessionFixationConfigurer::changeSessionId);
                    session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED);
                });

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

        http.authenticationProvider(authenticationProvider);

        return http.build();
    }

    /**
     * Enforce the concurrent-session ceiling and session-id rotation on the real signin/signup
     * path.
     *
     * <p>These are the two controls declared in {@code securityFilterChain}'s {@code
     * sessionManagement} block. {@link AuthenticationController#authenticate} invokes {@code
     * onAuthentication(...)} on this strategy directly, before the {@code SecurityContext} is
     * saved.
     *
     * <p>Decisions:
     *
     * <p><b>Two independent ceiling enforcers coexist.</b> {@code SessionManagementConfigurer}
     * installs a {@code SessionManagementFilter} from the same DSL block (measured, not assumed):
     * it holds a separate, DSL-composed {@code CompositeSessionAuthenticationStrategy} backed by an
     * in-memory {@code SessionRegistryImpl}, and fires only when a request reaches the chain
     * already authenticated without a stored context. Signin and signup never produce that shape;
     * MockMvc's {@code .with(user(...))} test shortcut does (see {@code InjectionAttemptTest}).
     * This bean's {@code SpringSessionBackedSessionRegistry} reads the live, JDBC-persisted session
     * count, so its ceiling is consistent across instances; the filter-held registry's is not.
     *
     * <p><b>Known, accepted TOCTOU window (finding F6, 2026-08-10 {@code /claude-security}
     * scan).</b> {@code ConcurrentSessionControlAuthenticationStrategy} reads the caller's live
     * {@code SPRING_SESSION} count and then lets the signin register a new session, a
     * check-then-act sequence. Two genuinely simultaneous signins for one principal can both read
     * the same under-threshold count before either commits its session row, so both proceed. This
     * is a <b>knowingly accepted, bounded</b> trade-off, the same disposition as {@link
     * com.vrudenko.kanban_board.entity.UserEntity}'s deliberately-unversioned last-write-wins theme
     * preference. The bound is a function of concurrency, not a constant: live sessions for one
     * principal never exceed {@code MAX_CONCURRENT_SESSIONS} plus at most one extra per signin
     * genuinely in flight at that instant, never "at most 3" as a flat ceiling. The excess is
     * transient and self-correcting, because the next non-concurrent signin for that principal is
     * still refused.
     *
     * <p><b>A transaction-scoped advisory lock (e.g. {@code pg_advisory_xact_lock}) around the
     * count-then-register sequence does not close the window.</b> Measured 2026-08-11: a probe
     * reading the live {@code SPRING_SESSION} count from a <i>second</i> database connection, taken
     * the instant {@link AuthenticationController#authenticate}'s {@code mapTry} lambda finishes
     * (right after {@code securityContextRepository.saveContext(...)} returns, the latest point any
     * controller-scoped transaction could still be open), read <b>0</b> committed rows for the
     * just-authenticated principal; a client-side probe taken after the HTTP response was fully
     * received read <b>1</b>. The new session row is not committed, so not visible to another
     * connection, until Spring Session's request-scoped filter commits it as the response is
     * flushed, strictly after this method has returned. A lock held across this call would release
     * before the row it was meant to serialize against exists, closing nothing while adding a
     * blocking database round trip to every signin. {@code ConcurrentSigninCeilingE2ETest} is the
     * empirical proof of the accepted bound.
     *
     * <p><b>The ceiling rejection stays a {@code 401}, byte-identical to a wrong-password
     * response.</b> Do not give it its own status code or otherwise make it distinguishable: that
     * would hand an attacker a validity oracle for "these credentials are valid, this account just
     * has sessions open."
     */
    @Bean
    public <S extends Session> SessionAuthenticationStrategy sessionAuthenticationStrategy(
            FindByIndexNameSessionRepository<S> sessionRepository) {
        // A local variable, deliberately NOT its own @Bean.
        //
        // A published SessionRegistry bean would be handed by SessionManagementConfigurer to the
        // already-installed ConcurrentSessionFilter, adding a JdbcIndexedSessionRepository lookup
        // to every authenticated request for a path that is dead by design under
        // maxSessionsPreventsLogin(true): it prevents login instead of marking a session expired.
        var sessionRegistry = new SpringSessionBackedSessionRegistry<>(sessionRepository);

        var concurrentSessionControl =
                new ConcurrentSessionControlAuthenticationStrategy(sessionRegistry);
        concurrentSessionControl.setMaximumSessions(MAX_CONCURRENT_SESSIONS);
        concurrentSessionControl.setExceptionIfMaximumExceeded(true);

        // Order matters: concurrency control MUST run before fixation.
        //
        // That way a login the ceiling rejects does not rotate the caller's existing session id.
        // No RegisterSessionAuthenticationStrategy delegate is needed because the registry is
        // Spring-Session-backed: its registerNewSession is a documented no-op and the live count
        // comes from the JDBC store. That delegate WOULD be mandatory if the registry were ever
        // swapped for an in-memory SessionRegistryImpl.
        return new CompositeSessionAuthenticationStrategy(
                List.of(concurrentSessionControl, new ChangeSessionIdAuthenticationStrategy()));
    }
}
