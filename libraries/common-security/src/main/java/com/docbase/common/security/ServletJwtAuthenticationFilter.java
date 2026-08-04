package com.docbase.common.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Servlet JWT authentication filter for business services.
 * Verifies access tokens using the IAM public key and propagates identity.
 * Optionally checks auth_version for immediate token invalidation.
 */
public class ServletJwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ServletJwtAuthenticationFilter.class);

    private final JwtVerifier verifier;
    private final AuthVersionChecker authVersionChecker;

    public ServletJwtAuthenticationFilter(JwtVerifier verifier) {
        this(verifier, AuthVersionChecker.NO_OP);
    }

    public ServletJwtAuthenticationFilter(JwtVerifier verifier, AuthVersionChecker authVersionChecker) {
        this.verifier = verifier;
        this.authVersionChecker = authVersionChecker;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null && verifier.isEnabled()) {
            try {
                Claims claims = verifier.verify(token, "access");
                if (claims != null) {
                    String userId = claims.getSubject();
                    String username = claims.get("username", String.class);

                    // Check auth_version for immediate invalidation
                    long tokenAuthVersion = getAuthVersionFromClaims(claims);
                    if (!authVersionChecker.isAuthVersionValid(userId, tokenAuthVersion)) {
                        log.debug("Access token auth_version invalid for user {}", userId);
                        SecurityContextHolder.clearContext();
                        chain.doFilter(request, response);
                        return;
                    }

                    // Extract permissions from claims
                    @SuppressWarnings("unchecked")
                    List<String> permissions = (List<String>) claims.get("permissions");

                    Collection<SimpleGrantedAuthority> authorities = permissions != null
                            ? permissions.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList())
                            : List.of();

                    JwtUserPrincipal principal = new JwtUserPrincipal(userId, username, authorities);
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(principal, null, authorities);
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (Exception e) {
                log.debug("JWT verification failed: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    private long getAuthVersionFromClaims(Claims claims) {
        Object av = claims.get("auth_version");
        if (av instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }
}
