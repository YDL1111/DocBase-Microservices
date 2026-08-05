package com.docbase.ingest.config;

import com.docbase.common.security.AuthVersionChecker;
import com.docbase.common.security.JwtVerifier;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security configuration for ingest-service.
 * Enables JWT verification and method-level security.
 */
@Configuration
@EnableMethodSecurity(prePostEnabled = true)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class IngestSecurityConfig {

    @Bean
    SecurityFilterChain ingestSecurityFilterChain(HttpSecurity http,
                                                   JwtVerifier verifier,
                                                   AuthVersionChecker authVersionChecker) throws Exception {
        IngestJwtAuthenticationFilter filter = new IngestJwtAuthenticationFilter(verifier, authVersionChecker);
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(401);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"success\":false,\"code\":\"UNAUTHORIZED\",\"message\":\"authentication required\"}");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(403);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"success\":false,\"code\":\"FORBIDDEN\",\"message\":\"access denied\"}");
                        }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new jakarta.servlet.Filter() {
                    @Override
                    public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response,
                                         jakarta.servlet.FilterChain chain) throws java.io.IOException, jakarta.servlet.ServletException {
                        filter.doFilter((HttpServletRequest) request, (HttpServletResponse) response, chain);
                    }
                }, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
