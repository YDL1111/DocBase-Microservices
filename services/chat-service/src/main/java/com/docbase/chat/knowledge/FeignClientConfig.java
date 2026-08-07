package com.docbase.chat.knowledge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

/**
 * Enables Feign clients for knowledge-service only when load-balanced Feign is available.
 * Disabled in tests (set docbase.chat.feign.enabled=false) so a mock client is used instead.
 */
@Configuration
@EnableFeignClients(basePackages = "com.docbase.chat.knowledge")
@ConditionalOnProperty(prefix = "docbase.chat", name = "feign.enabled", havingValue = "true", matchIfMissing = true)
public class FeignClientConfig {
}
