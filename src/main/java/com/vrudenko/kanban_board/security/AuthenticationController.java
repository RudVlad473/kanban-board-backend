package com.vrudenko.kanban_board.security;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.user_dto.SigninRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.SignupRequestDTO;
import com.vrudenko.kanban_board.mapper.UserMapper;
import com.vrudenko.kanban_board.repository.UserRepository;
import com.vrudenko.kanban_board.service.UserService;
import io.vavr.control.Try;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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

    @Autowired private UserService userService;

    // only these authentication routes yield session cookie
    @PostMapping(ApiPaths.SIGNIN)
    public ResponseEntity<Void> signin(
            @RequestBody SigninRequestDTO dto,
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            var user = userService.findByEmail(dto.getEmail());

            var successfullyAuthenticated =
                    authenticate(user.getId(), dto.getPassword(), request, response);

            if (!successfullyAuthenticated) {
                throw new AccessDeniedException("Was not able to sign in");
            }
        } catch (Exception e) {
            throw new BadCredentialsException("Invalid username or password");
        }

        return ResponseEntity.ok().build();
    }

    // only these authentication routes yield session cookie
    @PostMapping(ApiPaths.SIGNUP)
    public ResponseEntity<String> signup(
            @RequestBody SignupRequestDTO signupDTO,
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            var createdUser = userService.save(signupDTO);

            var successfullyAuthenticated =
                    authenticate(createdUser.getId(), signupDTO.getPassword(), request, response);

            if (!successfullyAuthenticated) {
                userService.deleteById(createdUser.getId());

                throw new AccessDeniedException("Was not able to sign up");
            }
        } catch (Exception e) {
            throw new BadCredentialsException("Invalid username or password");
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
