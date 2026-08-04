package com.docbase.iam.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Validates RS256 JWT access tokens on each request. Verifies signature, issuer,
 * expiry, token_type, and auth_version. The auth_version check enables immediate
 * invalidation on logout, disable, or password change.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtTokenVerifier tokenVerifier;
    private final TokenStore tokenStore;

    public JwtAuthenticationFilter(JwtTokenVerifier tokenVerifier, TokenStore tokenStore) {
        this.tokenVerifier = tokenVerifier;
        this.tokenStore = tokenStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null && tokenVerifier.isEnabled()) {
            try {
                Claims claims = tokenVerifier.verifyAccess(token);
                if (claims != null) {
                    Long userId = Long.parseLong(claims.getSubject());
                    String username = claims.get("username", String.class);

                    // Verify auth_version matches current (detects logout/disable/password change)
                    long tokenAuthVersion = getAuthVersionFromClaims(claims);
                    long currentAuthVersion = tokenStore.getAuthVersion(userId);
                    // If auth version was never initialized, initialize it now
                    if (currentAuthVersion == 0) {
                        currentAuthVersion = tokenStore.initAuthVersion(userId);
                    }
                    if (tokenAuthVersion != currentAuthVersion) {
                        log.debug("Access token auth_version mismatch for user {}: token={}, current={}",
                                userId, tokenAuthVersion, currentAuthVersion);
                        SecurityContextHolder.clearContext();
                        chain.doFilter(request, response);
                        return;
                    }

                    // Build permissions from token claims
                    Set<String> permissions = extractPermissions(claims);

                    IamUserPrincipal principal = new IamUserPrincipal(userId, username, isAdmin(permissions), permissions);
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (Exception e) {
                log.debug("JWT parse failed: {}", e.getMessage());
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

    @SuppressWarnings("unchecked")
    private Set<String> extractPermissions(Claims claims) {
        Object perms = claims.get("permissions");
        if (perms instanceof List<?> list) {
            return list.stream().map(Object::toString).collect(java.util.stream.Collectors.toSet());
        }
        return Collections.emptySet();
    }

    private long getAuthVersionFromClaims(Claims claims) {
        Object av = claims.get("auth_version");
        if (av instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }

    /**
     * Super-admin is identified by having the special permission "admin:all" or
     * being in the admin role. For simplicity, we check if the user has all permissions.
     */
    private boolean isAdmin(Set<String> permissions) {
        return permissions.contains("admin:all");
    }
}
