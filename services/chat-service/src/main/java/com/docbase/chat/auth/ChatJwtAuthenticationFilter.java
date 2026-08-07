package com.docbase.chat.auth;

import com.docbase.common.security.AuthVersionChecker;
import com.docbase.common.security.JwtVerifier;
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

import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * JWT authentication filter for chat-service.
 * Extracts user identity from the JWT access token, checks auth_version,
 * and builds a ChatUserPrincipal.
 */
public class ChatJwtAuthenticationFilter {

    private static final Logger log = LoggerFactory.getLogger(ChatJwtAuthenticationFilter.class);

    private final JwtVerifier jwtVerifier;
    private final AuthVersionChecker authVersionChecker;

    public ChatJwtAuthenticationFilter(JwtVerifier jwtVerifier, AuthVersionChecker authVersionChecker) {
        this.jwtVerifier = jwtVerifier;
        this.authVersionChecker = authVersionChecker;
    }

    public void doFilter(HttpServletRequest request, HttpServletResponse response,
                         FilterChain chain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null && jwtVerifier.isEnabled()) {
            try {
                Claims claims = jwtVerifier.verify(token, "access");
                if (claims != null) {
                    String userIdStr = claims.getSubject();
                    Long userId = Long.parseLong(userIdStr);

                    // Check auth_version for immediate invalidation
                    long tokenAuthVersion = getAuthVersionFromClaims(claims);
                    if (!authVersionChecker.isAuthVersionValid(userIdStr, tokenAuthVersion)) {
                        log.debug("Access token auth_version invalid for user {}", userId);
                        SecurityContextHolder.clearContext();
                        chain.doFilter(request, response);
                        return;
                    }

                    String username = claims.get("username", String.class);
                    boolean admin = false;

                    @SuppressWarnings("unchecked")
                    List<String> permissions = (List<String>) claims.get("permissions");
                    if (permissions != null) {
                        admin = permissions.contains("admin:all");
                    }
                    Collection<SimpleGrantedAuthority> authorities = permissions != null
                            ? permissions.stream().map(SimpleGrantedAuthority::new).toList()
                            : List.of();

                    ChatUserPrincipal principal = new ChatUserPrincipal(userId, username, admin, authorities);
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
