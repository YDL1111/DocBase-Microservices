package com.docbase.iam.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.docbase.common.core.BusinessException;
import com.docbase.iam.auth.dto.AdminSetupRequest;
import com.docbase.iam.role.domain.SysRole;
import com.docbase.iam.role.mapper.SysRoleMapper;
import com.docbase.iam.security.JwtProperties;
import com.docbase.iam.user.domain.SysUser;
import com.docbase.iam.user.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-setup-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "iam.admin-setup.key=test-admin-setup-key-at-least-32-chars"
})
class AdminSetupIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired AdminSetupService adminSetupService;
    @Autowired SysUserMapper userMapper;
    @Autowired SysRoleMapper roleMapper;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean StringRedisTemplate redisTemplate;

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        JwtProperties testJwtProperties() throws IOException {
            KeyPair pair = com.docbase.iam.security.TestKeys.generate();
            Path dir = com.docbase.iam.security.TestKeys.writeTempKeyPair(pair);
            return new JwtProperties(dir.resolve("private.pem").toString(),
                    "docbase-iam", "30m", "7d");
        }
    }

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM sys_user_role");
        jdbcTemplate.update("DELETE FROM sys_user");
        jdbcTemplate.update("DELETE FROM sys_role");
        insertSystemRole("系统管理员", "system_admin");
        insertSystemRole("知识库管理员", "knowledge_admin");
    }

    @Test
    void anonymousStatusAndSetupCreateUsableAdminWithSystemRoles() throws Exception {
        mockMvc.perform(get("/api/auth/setup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.required").value(true))
                .andExpect(jsonPath("$.data.enabled").value(true));

        mockMvc.perform(post("/api/auth/setup")
                        .contentType("application/json")
                        .content(setupJson("test-admin-setup-key-at-least-32-chars", "admin", "StrongPass!123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNumber());

        SysUser admin = userMapper.selectOne(new QueryWrapper<SysUser>().eq("username", "admin"));
        assertThat(admin).isNotNull();
        assertThat(admin.getIsAdmin()).isEqualTo(1);
        assertThat(admin.getStatus()).isEqualTo(1);
        assertThat(passwordEncoder.matches("StrongPass!123", admin.getPassword())).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user_role WHERE user_id = ?", Long.class, admin.getUserId()))
                .isEqualTo(2L);

        mockMvc.perform(get("/api/auth/setup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.required").value(false))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    void wrongOperatorKeyIsForbiddenWithoutCreatingUser() throws Exception {
        mockMvc.perform(post("/api/auth/setup")
                        .contentType("application/json")
                        .content(setupJson("wrong-admin-setup-key-at-least-32", "admin", "StrongPass!123")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_SETUP_KEY_INVALID"));

        assertThat(userMapper.selectCount(null)).isZero();
    }

    @Test
    void setupClosesAfterFirstActiveAdministrator() throws Exception {
        adminSetupService.setup(new AdminSetupRequest(
                "test-admin-setup-key-at-least-32-chars", "admin", "Administrator", "StrongPass!123"));

        mockMvc.perform(post("/api/auth/setup")
                        .contentType("application/json")
                        .content(setupJson("test-admin-setup-key-at-least-32-chars", "admin2", "StrongPass!456")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ADMIN_SETUP_CLOSED"));

        assertThat(userMapper.selectCount(null)).isEqualTo(1L);
    }

    @Test
    void invalidRequestReturnsValidationError() throws Exception {
        mockMvc.perform(post("/api/auth/setup")
                        .contentType("application/json")
                        .content(setupJson("test-admin-setup-key-at-least-32-chars", "1", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void passwordLongerThanBcryptByteLimitIsRejectedCleanly() throws Exception {
        mockMvc.perform(post("/api/auth/setup")
                        .contentType("application/json")
                        .content(setupJson("test-admin-setup-key-at-least-32-chars",
                                "admin", "a".repeat(73))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void multibytePasswordOverBcryptByteLimitIsRejectedCleanly() throws Exception {
        mockMvc.perform(post("/api/auth/setup")
                        .contentType("application/json")
                        .content(setupJson("test-admin-setup-key-at-least-32-chars",
                                "admin", "密".repeat(25))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void concurrentFirstAdminRequestsCreateExactlyOneAdministrator() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(() -> runConcurrentSetup(
                    ready, start, "admin-a"));
            Future<String> second = executor.submit(() -> runConcurrentSetup(
                    ready, start, "admin-b"));
            ready.await();
            start.countDown();

            List<String> results = List.of(first.get(), second.get());
            assertThat(results).containsExactlyInAnyOrder("OK", "ADMIN_SETUP_CLOSED");
        }
        assertThat(userMapper.selectCount(new QueryWrapper<SysUser>().eq("is_admin", 1)))
                .isEqualTo(1L);
    }

    private String runConcurrentSetup(CountDownLatch ready, CountDownLatch start, String username)
            throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            adminSetupService.setup(new AdminSetupRequest(
                    "test-admin-setup-key-at-least-32-chars", username, "Administrator", "StrongPass!123"));
            return "OK";
        } catch (BusinessException exception) {
            return exception.code();
        }
    }

    private void insertSystemRole(String roleName, String roleKey) {
        SysRole role = new SysRole();
        role.setRoleName(roleName);
        role.setRoleKey(roleKey);
        role.setRoleSort(1);
        role.setDataScope(1);
        role.setStatus(1);
        role.setIsSystem(1);
        role.setRemark("");
        role.setDeleted(0);
        roleMapper.insert(role);
    }

    private String setupJson(String setupKey, String username, String password) {
        return """
                {"setupKey":"%s","username":"%s","nickname":"Administrator","password":"%s"}
                """.formatted(setupKey, username, password);
    }
}
