package com.syncro.auth.application;

import com.syncro.auth.api.AuthDtos.AuthUserView;
import com.syncro.auth.api.AuthDtos.LoginResponse;
import com.syncro.auth.infrastructure.AuthUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private static final String DUMMY_PASSWORD_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhiI6BFSH9upL7M9PdPIpEBaU8UQFJ6i";

  private final AuthUserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenService tokens;

  public AuthService(AuthUserRepository users, PasswordEncoder passwordEncoder, JwtTokenService tokens) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.tokens = tokens;
  }

  @Transactional(readOnly = true)
  public LoginResponse login(String loginIdentifier, String password) {
    var user = users.findByLoginIdentifierIgnoreCase(loginIdentifier.trim());
    var passwordHash = user.map(candidate -> candidate.getPasswordHash()).orElse(DUMMY_PASSWORD_HASH);
    var passwordMatches = passwordEncoder.matches(password, passwordHash);
    var authenticatedUser = user
        .filter(candidate -> passwordMatches && candidate.isEnabled())
        .orElseThrow(BadCredentialsException::new);
    return new LoginResponse(
        "Bearer",
        tokens.createToken(authenticatedUser),
        tokens.expiresInSeconds(),
        new AuthUserView(authenticatedUser.getId().toString(), authenticatedUser.getLoginIdentifier(),
            authenticatedUser.getApplicationRole()));
  }

  public AuthUserView currentUser(JwtTokenService.AuthenticatedUser user) {
    return new AuthUserView(user.id(), user.loginIdentifier(), user.applicationRole());
  }

  @Transactional(readOnly = true)
  public java.util.List<AuthUserView> listUsers() {
    return users.findAll().stream()
        .map(u -> new AuthUserView(u.getId().toString(), u.getLoginIdentifier(), u.getApplicationRole()))
        .toList();
  }

  public static class BadCredentialsException extends RuntimeException {
  }
}
