package com.docbase.knowledge;

import com.docbase.common.security.AuthVersionChecker;
import com.docbase.common.security.RedisAuthVersionChecker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that JWT security is properly configured for business services.
 * When Redis is available, AuthVersionChecker should be RedisAuthVersionChecker (not NO_OP).
 *
 * Note: spring.config.import is overridden via @SpringBootTest properties to prevent
 * Nacos config from loading during tests. This is more reliable than application-test.yml
 * because ConfigData processing happens before profile-specific properties are applied.
 */
@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false"
})
@ActiveProfiles("test")
class JwtSecurityIntegrationTest {

    @Autowired
    ApplicationContext applicationContext;

    @Test
    void authVersionCheckerBeanShouldBeProperlyConfigured() {
        // This test verifies that the auto-config correctly resolves AuthVersionChecker.
        // The key assertion: when StringRedisTemplate is available (Redis on classpath),
        // the AuthVersionChecker MUST be RedisAuthVersionChecker, NOT NO_OP.

        // Check if StringRedisTemplate is available in the context
        boolean redisAvailable = applicationContext.getBeansOfType(StringRedisTemplate.class).size() > 0;

        AuthVersionChecker checker = applicationContext.getBean(AuthVersionChecker.class);

        if (redisAvailable) {
            // CRITICAL: When Redis is available, must be RedisAuthVersionChecker (NOT NO_OP)
            // This ensures auth_version check works for token invalidation on logout/disable/password-change
            assertThat(checker)
                    .as("AuthVersionChecker should be RedisAuthVersionChecker when Redis is available, " +
                            "not NO_OP. This ensures auth_version check works for token invalidation.")
                    .isInstanceOf(RedisAuthVersionChecker.class);
        } else {
            // When Redis is not available, falls back to NO_OP (graceful degradation)
            assertThat(checker)
                    .as("AuthVersionChecker should be NO_OP when Redis is not available")
                    .isNotInstanceOf(RedisAuthVersionChecker.class);
        }
    }
}
