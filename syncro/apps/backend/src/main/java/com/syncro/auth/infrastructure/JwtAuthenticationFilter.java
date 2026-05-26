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
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public JwtAuthenticationFilter(JwtTokenService tokens, ObjectMapper objectMapper, Clock clock) {
    this.tokens = tokens;
    this.objectMapper = objectMapper;
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

    try {
      var user = tokens.parse(header.substring("Bearer ".length()));
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
}
