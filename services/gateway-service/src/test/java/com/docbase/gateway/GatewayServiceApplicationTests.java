package com.docbase.gateway;

import com.docbase.gateway.filter.GatewaySecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class GatewayServiceApplicationTests {

    @Autowired
    GatewaySecurityProperties securityProperties;

    @Autowired
    RouteDefinitionLocator routeDefinitionLocator;

    @Test
    void contextLoads() {
        assertThat(securityProperties.anonymousPaths())
                .contains("/api/auth/setup", "/api/auth/register", "/api/auth/registration");
    }

    @Test
    void anonymousRegistrationPostUsesSharedRedisRateLimiter() {
        List<RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions().collectList().block();
        assertThat(routes).isNotNull();
        RouteDefinition registrationRoute = routes.stream()
                .filter(route -> "iam-registration".equals(route.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(registrationRoute.getOrder()).isEqualTo(-10);
        assertThat(registrationRoute.getPredicates())
                .extracting(predicate -> predicate.getName())
                .contains("Path", "Method");
        assertThat(registrationRoute.getFilters())
                .extracting(filter -> filter.getName())
                .contains("RequestRateLimiter");
    }

    @Test
    void anonymousAdminSetupPostUsesSharedRedisRateLimiter() {
        List<RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions().collectList().block();
        assertThat(routes).isNotNull();
        RouteDefinition setupRoute = routes.stream()
                .filter(route -> "iam-admin-setup".equals(route.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(setupRoute.getOrder()).isEqualTo(-10);
        assertThat(setupRoute.getPredicates())
                .extracting(predicate -> predicate.getName())
                .contains("Path", "Method");
        assertThat(setupRoute.getFilters())
                .extracting(filter -> filter.getName())
                .contains("RequestRateLimiter");
    }

    @Test
    void gatewayRedisAclAllowsOnlyTheLuaCommandsNeededByRateLimiter() throws Exception {
        Path aclScript = Path.of("..", "..", "deploy", "redis", "entrypoint.sh");
        String gatewayAcl = Files.readAllLines(aclScript).stream()
                .filter(line -> line.startsWith("user gateway "))
                .findFirst()
                .orElseThrow();
        assertThat(gatewayAcl)
                .contains("%RW~request_rate_limiter.*", "+eval", "+evalsha")
                .doesNotContain("+@scripting");
    }
}
