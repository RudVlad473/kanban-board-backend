package com.vrudenko.kanban_board.security;

import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserAuthenticationProvider implements AuthenticationProvider {
  private final UserDetailsService userDetailsService;
  private final PasswordEncoder passwordEncoder;

  @Override
  public Authentication authenticate(Authentication authentication) throws AuthenticationException {
    var userId = authentication.getPrincipal().toString();
    var passwordHash = authentication.getCredentials().toString();

    var userDetails = userDetailsService.loadUserByUsername(userId);

    if (!passwordEncoder.matches(passwordHash, userDetails.getPassword())) {
      throw new BadCredentialsException("Invalid username or password");
    }

    return new UsernamePasswordAuthenticationToken(userId, passwordHash, new ArrayList<>());
  }

  @Override
  public boolean supports(Class<?> authentication) {
    return authentication.equals(UsernamePasswordAuthenticationToken.class);
  }
}
