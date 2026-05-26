package com.syncro.auth.application;

import com.syncro.auth.api.AuthDtos.AuthUserView;
import com.syncro.auth.api.AuthDtos.LoginResponse;
import com.syncro.auth.infrastructure.AuthUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
  private final AuthUserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenService tokens;

  public AuthService(AuthUserRepository users, PasswordEncoder passwordEncoder, JwtTokenService tokens) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.tokens = tokens;
  }

  public LoginResponse login(String loginIdentifier, String password) {
    var user = users.findByLoginIdentifierIgnoreCase(loginIdentifier.trim())
        .filter(candidate -> candidate.isEnabled() && passwordEncoder.matches(password, candidate.getPasswordHash()))
        .orElseThrow(BadCredentialsException::new);
    return new LoginResponse(
        "Bearer",
        tokens.createToken(user),
        tokens.expiresInSeconds(),
        new AuthUserView(user.getId().toString(), user.getLoginIdentifier(), user.getApplicationRole()));
  }

  public AuthUserView currentUser(JwtTokenService.AuthenticatedUser user) {
    return new AuthUserView(user.id(), user.loginIdentifier(), user.applicationRole());
  }

  public static class BadCredentialsException extends RuntimeException {
  }
}
