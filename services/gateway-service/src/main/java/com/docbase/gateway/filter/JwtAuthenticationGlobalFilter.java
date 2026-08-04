package com.docbase.gateway.filter;

import com.docbase.common.security.JwtVerifier;
import com.docbase.common.security.SecurityHeaders;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gateway-side JWT verification using the IAM public key. Validates the access
 * token's signature, issuer, and expiry, then propagates the verified identity
 * to downstream services via internal headers. The gateway never holds a private key.
 */
@Component
public class JwtAuthenticationGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationGlobalFilter.class);

    private final JwtVerifier jwtVerifier;
    private final GatewaySecurityProperties securityProperties;

    public JwtAuthenticationGlobalFilter(JwtVerifier jwtVerifier, GatewaySecurityProperties securityProperties) {
        this.jwtVerifier = jwtVerifier;
        this.securityProperties = securityProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        if (isAnonymous(path)) {
            return chain.filter(exchange);
        }

        if (!jwtVerifier.isEnabled()) {
            log.warn("JWT verifier is not configured; rejecting protected path: {}", path);
            return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE, "JWT verification not configured");
        }

        String token = resolveToken(request);
        if (token == null) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "missing access token");
        }

        Claims claims = jwtVerifier.verify(token);
        if (claims == null) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "invalid or expired token");
        }

        // Propagate verified identity to downstream services.
        ServerHttpRequest mutated = request.mutate()
                .headers(headers -> {
                    headers.set(SecurityHeaders.USER_ID, claims.getSubject());
                    headers.set(SecurityHeaders.USERNAME, claims.get("username", String.class));
                })
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private boolean isAnonymous(String path) {
        return securityProperties.anonymousPaths().stream().anyMatch(path::startsWith);
    }

    private String resolveToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().add("Content-Type", "application/json");
        String code = status == HttpStatus.UNAUTHORIZED ? "UNAUTHORIZED" : "ERROR";
        String body = "{\"success\":false,\"code\":\"" + code + "\",\"message\":\"" + message + "\"}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes())));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
