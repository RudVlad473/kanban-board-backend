package com.vrudenko.kanban_board.security;

import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserAuthenticationProvider implements AuthenticationProvider {
    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;

    @Override
    public Authentication authenticate(Authentication authentication)
            throws AuthenticationException {
        var userId = authentication.getPrincipal().toString();
        var plainPassword = authentication.getCredentials().toString();

        var userDetails = userDetailsService.loadUserByUsername(userId);

        if (!passwordEncoder.matches(plainPassword, userDetails.getPassword())) {
            throw new BadCredentialsException("Invalid username or password");
        }

        // Use a minimal principal (username only, no password hash) rather than the
        // full UserEntity returned by loadUserByUsername — the Authentication object
        // is what Spring Session serializes into the JDBC-backed spring_session_attributes
        // table on session change (not on every request), so passing the entity directly
        // would persist passwordHash to the database. Enforced by
        // AuthenticationTest.SigninPersistence#shouldNotPersistBcryptHash_whenSigninSucceeds.
        var principal = new User(userDetails.getUsername(), "", new ArrayList<>());

        return new UsernamePasswordAuthenticationToken(principal, null, new ArrayList<>());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return authentication.equals(UsernamePasswordAuthenticationToken.class);
    }
}
