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

    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid username or password";

    // Fixed plaintext hashed once at startup (see equalizerHash below) purely to give the
    // unknown-email signin branch a real BCrypt comparison to perform. Never compared against
    // any real user's credentials.
    private static final String EQUALIZER_PLAINTEXT = "signin-timing-equalizer";

    // Written exactly once, in initializeEqualizerHash() below, which runs during container
    // initialization -- happens-before any request is served -- and is read-only thereafter.
    // No synchronization is needed for that reason, and this field must not be "tidied" into a
    // constant (D-01): deriving it from the injected PasswordEncoder bean is what makes its
    // BCrypt work factor automatically track whatever strength BeanConfiguration configures,
    // instead of freezing today's cost into a source literal that could silently drift from it.
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
            // Closes finding F1 (2026-08-10 /claude-security scan): without this, an unknown
            // email fast-fails with zero BCrypt work while a registered email below always pays
            // one, so response *latency* enumerated registered accounts even though D-08 already
            // made the response *body* byte-identical. Performing the same comparison here makes
            // both branches pay the same dominant cost. The result is intentionally discarded --
            // this call exists for its cost, not its answer, and must not be removed as dead
            // code. Residual, not a full fix: the registered-email path below still performs one
            // extra indexed DB read (loadUserByUsername), sub-millisecond against BCrypt's tens
            // of milliseconds -- this narrows the channel by a large constant factor, it does not
            // make the endpoint provably constant-time.
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

        // D-01 (quick task 260812-hs4): the caller's identity, so a frontend BFF learns who just
        // authenticated instead of receiving only an opaque session cookie. Maps the `user`
        // already loaded above -- no second database read, no reordering relative to the
        // authenticate(...) call above, so both failure arms above are untouched.
        return ResponseEntity.ok(userService.toResponseDTO(user));
    }

    // only these authentication routes yield session cookie
    @PostMapping(ApiPaths.SIGNUP)
    public ResponseEntity<String> signup(
            @Valid @RequestBody SignupRequestDTO signupDTO,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Deliberately outside the try block below (D-07/D-09): a duplicate email throws
        // AppDuplicateResourceException, which must reach GlobalExceptionHandler as a 409, not be
        // swallowed by this method's blanket catch, which exists only to collapse a failed
        // *authentication* of the account just created into the same generic 401 every other
        // credential failure returns.
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

        return ResponseEntity.created(URI.create(request.getRequestURI())).build();
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
                            // Enforces the concurrent-session ceiling and rotates the session id
                            // on the privilege transition (D-01). Shared by both signin and
                            // signup, since both go through this helper -- intended, because
                            // signup auto-authenticates the account it just created, and the
                            // ceiling can never reject a signup since a brand-new principal has
                            // zero live sessions. A rejection throws
                            // SessionAuthenticationException,
                            // which the Try below collapses to false, then the caller's blanket
                            // catch turns into a 401.
                            sessionAuthenticationStrategy.onAuthentication(
                                    authentication, request, response);

                            // get user credential for wrapped to token
                            var context = securityContextHolderStrategy.createEmptyContext();

                            // set context application from authentication
                            context.setAuthentication(authentication);
                            securityContextHolderStrategy.setContext(context);
                            securityContextRepository.saveContext(context, request, response);

                            return true;
                        })
                .getOrElse(false);
    }
}
