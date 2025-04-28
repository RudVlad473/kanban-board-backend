package com.vrudenko.kanban_board.security;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.user_dto.SigninRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.SignupRequestDTO;
import com.vrudenko.kanban_board.mapper.UserMapper;
import com.vrudenko.kanban_board.repository.UserRepository;
import io.vavr.control.Try;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
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
  private final UserRepository userRepository;
  private final UserMapper userMapper;

  // only these authentication routes yield session cookie
  @GetMapping(ApiPaths.SIGNIN)
  public ResponseEntity signin(
      @RequestBody SigninRequestDTO signinDTO,
      HttpServletRequest request,
      HttpServletResponse response) {
    var user = userRepository.findByEmail(signinDTO.getEmail());

    if (user.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    var successfullyAuthenticated = authenticate(user.get().getId(), signinDTO.getPassword());

    if (!successfullyAuthenticated) {
      throw new AccessDeniedException("Was not able to sign in");
    }

    return ResponseEntity.ok().build();
  }

  // only these authentication routes yield session cookie
  @PostMapping(ApiPaths.SIGNUP)
  public ResponseEntity signup(
      @RequestBody SignupRequestDTO signupDTO,
      HttpServletRequest request,
      HttpServletResponse response) {
    var userAlreadyExists = userRepository.findByEmail(signupDTO.getEmail()).isPresent();

    if (userAlreadyExists) {
      return new ResponseEntity("User with this email already exists", HttpStatus.CONFLICT);
    }

    var createdUser = userRepository.save(userMapper.fromSignupRequestDTO(signupDTO));

    var successfullyAuthenticated = authenticate(createdUser.getId(), signupDTO.getPassword());

    if (!successfullyAuthenticated) {
      throw new AccessDeniedException("Was not able to sign up");
    }

    return ResponseEntity.created(URI.create(request.getRequestURI())).build();
  }

  public Boolean authenticate(String userId, String password) {
    var token = UsernamePasswordAuthenticationToken.unauthenticated(userId, password);

    return Try.of(() -> authenticationManager.authenticate(token))
        .mapTry(
            authentication -> {
              // get user credential for wrapped to token
              var context = securityContextHolderStrategy.createEmptyContext();

              // set context application from authentication
              context.setAuthentication(authentication);
              securityContextHolderStrategy.setContext(context);

              return true;
            })
        .getOrElse(false);
  }
}
