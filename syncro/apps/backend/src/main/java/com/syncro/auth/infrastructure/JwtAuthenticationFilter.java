package com.syncro.auth.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncro.auth.api.AuthDtos.ErrorResponse;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.InvalidTokenException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
  private final JwtTokenService tokens;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Clock clock;

  public JwtAuthenticationFilter(JwtTokenService tokens, Clock clock) {
    this.tokens = tokens;
    this.clock = clock;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    var header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith("Bearer ")) {
      filterChain.doFilter(request, response);
      return;
    }

    var bearerToken = header.substring("Bearer ".length());
    // Story 14-4 (FR-181): the ack-task-list endpoint accepts AUTO_LOGIN tokens — a
    // short-lived, phone-bound, single-use token minted for the 4-hour ack link. The
    // ack controller re-validates phone binding and single-use; any mismatch falls
    // back to normal login with no residual access.
    if (isAckTaskListRequest(request)) {
      try {
        var autoLogin = tokens.parseAutoLogin(bearerToken);
        var user = autoLogin.user();
        var authentication = new UsernamePasswordAuthenticationToken(
            user,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_" + user.applicationRole().name())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        request.setAttribute(AUTO_LOGIN_ATTR, autoLogin);
        filterChain.doFilter(request, response);
        return;
      } catch (InvalidTokenException ignored) {
        // fall through to normal parse — expired/mismatched AUTO_LOGIN behaves like
        // any other invalid token
      }
    }
    try {
      var user = tokens.parse(bearerToken);
      var authentication = new UsernamePasswordAuthenticationToken(
          user,
          null,
          List.of(new SimpleGrantedAuthority("ROLE_" + user.applicationRole().name())));
      SecurityContextHolder.getContext().setAuthentication(authentication);
      filterChain.doFilter(request, response);
    } catch (InvalidTokenException exception) {
      SecurityContextHolder.clearContext();
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      objectMapper.writeValue(response.getWriter(), new ErrorResponse(
          "INVALID_TOKEN",
          "Authentication is required.",
          Instant.now(clock).toString(),
          UUID.randomUUID().toString()));
    }
  }

  /** Attribute key carrying the parsed AUTO_LOGIN token for the ack controller. */
  public static final String AUTO_LOGIN_ATTR = "syncro.autoLoginToken";

  private static boolean isAckTaskListRequest(HttpServletRequest request) {
    var uri = request.getRequestURI();
    return "/api/v1/workorders/ack-task-list".equals(uri)
        || uri.startsWith("/api/v1/workorders/ack-task-list/");
  }
}
