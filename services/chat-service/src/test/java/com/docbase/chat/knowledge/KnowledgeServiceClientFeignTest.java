package com.docbase.chat.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the Feign KnowledgeServiceClient can be created when Feign is enabled.
 * This test catches container startup errors caused by missing loadbalancer dependency.
 *
 * Uses @SpringBootTest with Feign enabled (docbase.chat.feign.enabled=true) to verify
 * the full Feign + LoadBalancer auto-configuration works correctly.
 */
@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "docbase.chat.feign.enabled=true",
        "spring.flyway.enabled=false",
        "spring.datasource.url:jdbc:h2:mem:testdb;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
class KnowledgeServiceClientFeignTest {

    @Autowired
    ApplicationContext applicationContext;

    @Test
    void feignClientIsCreated() {
        // The Feign KnowledgeServiceClient bean should be created successfully
        // This will fail at container startup if spring-cloud-starter-loadbalancer is missing
        assertThat(applicationContext.getBean(KnowledgeServiceClient.class)).isNotNull();
    }
}
