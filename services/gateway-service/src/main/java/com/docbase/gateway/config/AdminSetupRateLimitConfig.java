package com.docbase.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/** Shared Redis rate-limit key for the one-time anonymous administrator setup. */
@Configuration
public class AdminSetupRateLimitConfig {

    @Bean
    KeyResolver adminSetupKeyResolver() {
        return exchange -> Mono.just(remoteHost(exchange.getRequest().getRemoteAddress()));
    }

    private String remoteHost(InetSocketAddress remoteAddress) {
        if (remoteAddress == null) {
            return "unknown";
        }
        if (remoteAddress.getAddress() != null) {
            return remoteAddress.getAddress().getHostAddress();
        }
        return remoteAddress.getHostString();
    }
}
