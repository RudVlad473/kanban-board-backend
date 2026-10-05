package com.vrudenko.kanban_board.security;

import java.net.URI;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.user_dto.SigninRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.SignupRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.UserResponseDTO;
import com.vrudenko.kanban_board.entity.UserEntity;
import com.vrudenko.kanban_board.exception.AppEntityNotFoundException;
import com.vrudenko.kanban_board.service.UserService;

import io.vavr.control.Try;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping
@Validated
public class AuthenticationController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final PasswordEncoder passwordEncoder;

    @Autowired private UserService userService;

    // Read from configuration rather than request.getRequestURI() so signup's Location header is
    // identical under MockMvc and a real servlet container.
    @Value("${server.servlet.context-path}")
    private String contextPath;

    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid username or password";

    // Hashed once at startup (see equalizerHash) to give the unknown-email signin branch a real
    // BCrypt comparison. Never compared against a real user's credentials.
    private static final String EQUALIZER_PLAINTEXT = "signin-timing-equalizer";

    // Written once during container initialization, before any request, and read-only after, so
    // no synchronization is needed.
    //
    // Do not "tidy" it into a constant: deriving it from the injected PasswordEncoder makes its
    // BCrypt work factor track BeanConfiguration's strength instead of freezing today's cost into
    // a literal that could drift.
    private String equalizerHash;

    @PostConstruct
    private void initializeEqualizerHash() {
        equalizerHash = passwordEncoder.encode(EQUALIZER_PLAINTEXT);
    }

    // only these authentication routes yield session cookie
    @PostMapping(ApiPaths.SIGNIN)
    public ResponseEntity<UserResponseDTO> signin(
            @Valid @RequestBody SigninRequestDTO dto,
            HttpServletRequest request,
            HttpServletResponse response) {
        UserEntity user;
        try {
            user = userService.findByEmail(dto.getEmail());
        } catch (AppEntityNotFoundException e) {
            // Closes finding F1 (2026-08-10 /claude-security scan): run a BCrypt comparison for an
            // unknown email too.
            //
            // Decisions:
            // Without it an unknown email fast-fails with zero BCrypt work while a registered one
            // always pays one, so response latency enumerated registered accounts even though the
            // body was already byte-identical. The result is intentionally discarded: the call
            // exists for its cost, not its answer, and must not be removed as dead code.
            // Residual, not a full fix: the registered-email path still performs one extra indexed
            // DB read (loadUserByUsername), sub-millisecond against BCrypt's tens of milliseconds,
            // so this narrows the channel by a large constant factor but does not make the endpoint
            // provably constant-time.
            passwordEncoder.matches(dto.getPassword(), equalizerHash);

            throw new BadCredentialsException(INVALID_CREDENTIALS_MESSAGE);
        }

        try {
            var successfullyAuthenticated =
                    authenticate(user.getId(), dto.getPassword(), request, response);

            if (!successfullyAuthenticated) {
                throw new AccessDeniedException("Was not able to sign in");
            }
        } catch (Exception e) {
            throw new BadCredentialsException(INVALID_CREDENTIALS_MESSAGE);
        }

        // Return the caller's identity so a frontend BFF learns who just authenticated instead of
        // receiving only an opaque session cookie. Maps the `user` loaded above: no second
        // database read, and the failure arms above are untouched.
        return ResponseEntity.ok(userService.toResponseDTO(user));
    }

    @PostMapping(ApiPaths.SIGNUP)
    public ResponseEntity<UserResponseDTO> signup(
            @Valid @RequestBody SignupRequestDTO signupDTO,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Outside the try block on purpose: a duplicate email throws AppDuplicateResourceException,
        // which must reach GlobalExceptionHandler as a 409, not be swallowed by the blanket catch
        // that collapses authentication failures into the generic 401.
        var createdUser = userService.save(signupDTO);

        try {
            var successfullyAuthenticated =
                    authenticate(createdUser.getId(), signupDTO.getPassword(), request, response);

            if (!successfullyAuthenticated) {
                userService.deleteById(createdUser.getId());

                throw new AccessDeniedException("Was not able to sign up");
            }
        } catch (Exception e) {
            throw new BadCredentialsException(INVALID_CREDENTIALS_MESSAGE);
        }

        // Deliberately not the request-URI-derived Location other ResponseEntity.created sites
        // use: that names the POSTed-to collection, here the signup route, which is no resource.
        //
        // The URI names no resource yet: GET /users/me has no handler. Tracked in
        // .planning/todos/pending/2026-08-12-signup-location-header-points-at-a-uri-with-no-get-handler.md
        return ResponseEntity.created(URI.create(contextPath + ApiPaths.USERS + ApiPaths.ME))
                .body(createdUser);
    }

    private Boolean authenticate(
            String userId,
            String password,
            HttpServletRequest request,
            HttpServletResponse response) {
        var token = UsernamePasswordAuthenticationToken.unauthenticated(userId, password);

        return Try.of(() -> authenticationManager.authenticate(token))
                .mapTry(
                        authentication -> {
                            // Enforce the concurrent-session ceiling and rotate the session id
                            // on the privilege transition.
                            //
                            // Shared by signin and signup: signup auto-authenticates the new
                            // account, and the ceiling can never reject it (zero live sessions). A
                            // rejection throws SessionAuthenticationException, which the Try
                            // collapses to false and the blanket catch turns into a 401.
                            sessionAuthenticationStrategy.onAuthentication(
                                    authentication, request, response);

                            var context = securityContextHolderStrategy.createEmptyContext();

                            context.setAuthentication(authentication);
                            securityContextHolderStrategy.setContext(context);
                            securityContextRepository.saveContext(context, request, response);

                            return true;
                        })
                .getOrElse(false);
    }
}
