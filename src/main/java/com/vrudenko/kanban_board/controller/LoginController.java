package com.vrudenko.kanban_board.controller;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.user_dto.LoginDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping
public class LoginController {
  private final SecurityContextRepository securityContextRepository =
      new HttpSessionSecurityContextRepository();
  private final AuthenticationManager authenticationManager;
  private final SecurityContextHolderStrategy securityContextHolderStrategy =
      SecurityContextHolder.getContextHolderStrategy();

  @PostMapping(ApiPaths.LOGIN)
  public void login(
      @RequestBody LoginDTO loginDTO, HttpServletRequest request, HttpServletResponse response) {
    // get user credential for wrapped to token
    var token =
        UsernamePasswordAuthenticationToken.unauthenticated(
            loginDTO.getEmail(), loginDTO.getPassword());
    var authentication = authenticationManager.authenticate(token);
    var context = securityContextHolderStrategy.createEmptyContext();

    context.setAuthentication(authentication); // set context application from authentication
    securityContextHolderStrategy.setContext(context);

    securityContextRepository.saveContext(context, request, response); // save the auth context
  }
}
