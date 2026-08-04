package com.docbase.common.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Servlet-based JWT security auto-configuration for business services.
 * When jwt.public-key-path is configured, replaces the permissive foundation
 * chain with a JWT-validating chain that also checks auth_version via Redis.
 *
 * Uses ObjectProvider&lt;StringRedisTemplate&gt; to resolve the Redis template at runtime,
 * avoiding auto-configuration ordering issues where RedisAutoConfiguration may not
 * have run when this auto-configuration is processed.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "jwt", name = "public-key-path")
@EnableConfigurationProperties(JwtProperties.class)
public class ServletJwtSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain jwtSecurityFilterChain(HttpSecurity http, JwtVerifier verifier,
                                                AuthVersionChecker authVersionChecker) throws Exception {
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
                .addFilterBefore(new ServletJwtAuthenticationFilter(verifier, authVersionChecker),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Single AuthVersionChecker bean that resolves StringRedisTemplate at runtime.
     * If StringRedisTemplate is available (Redis on classpath), uses RedisAuthVersionChecker.
     * Otherwise falls back to NO_OP.
     */
    @Bean
    @ConditionalOnMissingBean(AuthVersionChecker.class)
    AuthVersionChecker authVersionChecker(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                           JwtProperties jwtProperties) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate != null) {
            return new RedisAuthVersionChecker(redisTemplate, jwtProperties.failClosed());
        }
        return AuthVersionChecker.NO_OP;
    }
}
